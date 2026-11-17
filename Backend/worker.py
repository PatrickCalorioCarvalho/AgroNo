"""AgroNo Fly - worker de mosaico.

Fica em loop pedindo ao servidor o proximo voo da fila (POST /api/fila/proximo). Para cada voo:
baixa as fotos do servidor, manda para o NodeODM (OpenDroneMap), acompanha o progresso, baixa o
ortomosaico (orthophoto.tif) e devolve ao servidor junto com uma previa (mosaico.jpg).
O servidor guarda o resultado em <voo_id>/mosaico/ e a pagina mostra o estado em tempo real.

So fala HTTP com a API e com o NodeODM: nao precisa de credencial do S3.

Variaveis:
    AGRONO_API          URL da API            (padrao http://agrono-fly-api:8000)
    NODEODM_URL         URL do NodeODM        (padrao http://agrono-fly-nodeodm:3000)
    WORKER_POLL_S       intervalo da fila, s  (padrao 10)
    ODM_ORTHO_RES_CM    resolucao do mosaico, cm/pixel (padrao: o ODM escolhe pelo GSD)

Uso local:  python worker.py
"""
import io
import json
import os
import shutil
import tempfile
import time
from urllib.parse import quote

import requests

try:
    from PIL import Image
    Image.MAX_IMAGE_PIXELS = None
except ImportError:  # pragma: no cover
    Image = None

API = os.environ.get("AGRONO_API", "http://agrono-fly-api:8000").rstrip("/")
ODM = os.environ.get("NODEODM_URL", "http://agrono-fly-nodeodm:3000").rstrip("/")
POLL_S = int(os.environ.get("WORKER_POLL_S", "10"))
ORTHO_RES = os.environ.get("ODM_ORTHO_RES_CM", "").strip()
PREVIEW_PX = 2400

# Codigos de estado do NodeODM
ODM_QUEUED, ODM_RUNNING, ODM_FAILED, ODM_COMPLETED, ODM_CANCELED = 10, 20, 30, 40, 50


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def api(method, path, **kw):
    kw.setdefault("timeout", 60)
    return requests.request(method, API + path, **kw)


def report(vid, status, progresso=None, mensagem=""):
    body = {"status": status, "mensagem": mensagem}
    if progresso is not None:
        body["progresso"] = int(progresso)
    try:
        api("POST", f"/api/voos/{vid}/processamento", json=body)
    except requests.RequestException as e:
        log(f"aviso: nao consegui atualizar o estado de {vid}: {e}")


def odm_options(qualidade):
    opts = []
    if qualidade == "rapido":
        opts.append({"name": "fast-orthophoto", "value": True})
    if ORTHO_RES:
        opts.append({"name": "orthophoto-resolution", "value": float(ORTHO_RES)})
    return opts


def download_photos(vid, names, folder):
    for i, name in enumerate(names):
        r = api("GET", f"/api/voos/{vid}/fotos/{quote(name)}", stream=True, timeout=120)
        r.raise_for_status()
        with open(os.path.join(folder, name), "wb") as f:
            for chunk in r.iter_content(1 << 20):
                f.write(chunk)
        report(vid, "processando", 1 + 9 * (i + 1) // len(names), f"Baixando fotos do servidor ({i + 1}/{len(names)})")


def odm_run(vid, folder, names, qualidade):
    """Cria a tarefa no NodeODM, envia as fotos, espera terminar. Devolve o uuid."""
    r = requests.post(f"{ODM}/task/new/init", data={"name": vid, "options": json.dumps(odm_options(qualidade))}, timeout=60)
    r.raise_for_status()
    uuid = r.json()["uuid"]
    for i, name in enumerate(names):
        with open(os.path.join(folder, name), "rb") as f:
            requests.post(f"{ODM}/task/new/upload/{uuid}", files={"images": (name, f)}, timeout=300).raise_for_status()
        report(vid, "processando", 10 + 10 * (i + 1) // len(names), f"Enviando fotos ao NodeODM ({i + 1}/{len(names)})")
    requests.post(f"{ODM}/task/new/commit/{uuid}", timeout=60).raise_for_status()

    while True:
        time.sleep(POLL_S)
        info = requests.get(f"{ODM}/task/{uuid}/info", timeout=60).json()
        code = info["status"]["code"]
        prog = float(info.get("progress", 0))
        report(vid, "processando", 20 + 70 * prog / 100, "Na fila do NodeODM" if code == ODM_QUEUED else f"Processando ({prog:.0f}%)")
        if code == ODM_COMPLETED:
            return uuid
        if code in (ODM_FAILED, ODM_CANCELED):
            raise RuntimeError("NodeODM falhou: " + str(info["status"].get("errorMessage") or info["status"]))


def make_preview(tif_path):
    """JPEG reduzido do GeoTIFF (fundo transparente vira preto). None se nao der."""
    if Image is None:
        return None
    try:
        img = Image.open(tif_path)
        img.thumbnail((PREVIEW_PX, PREVIEW_PX))
        out = io.BytesIO()
        img.convert("RGB").save(out, "JPEG", quality=85)
        return out.getvalue()
    except Exception as e:
        log(f"aviso: sem previa do mosaico ({e})")
        return None


def odm_log(uuid):
    """Log (console) da tarefa no NodeODM, como texto. Vazio se nao der."""
    try:
        r = requests.get(f"{ODM}/task/{uuid}/output", params={"line": 0}, timeout=60)
        data = r.json()
        return chr(10).join(data) if isinstance(data, list) else str(data)
    except Exception:
        return ""


def is_tiff(path):
    with open(path, "rb") as f:
        return f.read(4) in (b"II*\x00", b"MM\x00*", b"II+\x00", b"MM\x00+")


def process(job):
    vid, names = job["id"], job["fotos"]
    log(f"voo {vid}: {len(names)} fotos, qualidade {job.get('qualidade')}")
    tmp = tempfile.mkdtemp(prefix="agrono-")
    uuid = None
    keep_task = False
    try:
        download_photos(vid, names, tmp)
        uuid = odm_run(vid, tmp, names, job.get("qualidade", "rapido"))

        report(vid, "processando", 92, "Baixando o mosaico do NodeODM")
        # O log do ODM vai sempre para o servidor (<voo>/mosaico/odm.log): e a unica pista se algo der errado.
        log_text = odm_log(uuid)
        if log_text:
            api("PUT", f"/api/voos/{vid}/mosaico/odm.log", data=log_text.encode("utf-8"), timeout=60)
        tif = os.path.join(tmp, "orthophoto.tif")
        with requests.get(f"{ODM}/task/{uuid}/download/orthophoto.tif", stream=True, timeout=300) as r:
            r.raise_for_status()
            with open(tif, "wb") as f:
                for chunk in r.iter_content(1 << 20):
                    f.write(chunk)
        if not is_tiff(tif):
            # O NodeODM responde 200 com {"error": "Invalid asset"} quando o ortomosaico nao existe. Mantem a tarefa para depurar.
            keep_task = True
            tail = " | ".join([l for l in log_text.splitlines() if l.strip()][-3:])
            raise RuntimeError("O NodeODM terminou sem gerar o ortomosaico (veja mosaico/odm.log). " + tail[:300])

        report(vid, "processando", 95, "Enviando o mosaico ao servidor")
        preview = make_preview(tif)
        if preview:
            api("PUT", f"/api/voos/{vid}/mosaico/mosaico.jpg", data=preview, timeout=120).raise_for_status()
        with open(tif, "rb") as f:
            api("PUT", f"/api/voos/{vid}/mosaico/orthophoto.tif", data=f, timeout=1800).raise_for_status()
        report(vid, "concluido", 100, "Mosaico pronto" if preview else "Mosaico pronto (sem previa em JPG)")
        log(f"voo {vid}: concluido")
    except Exception as e:
        log(f"voo {vid}: ERRO {e}")
        report(vid, "erro", None, str(e))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
        if uuid and not keep_task:
            try:
                requests.post(f"{ODM}/task/remove", data={"uuid": uuid}, timeout=30)
            except requests.RequestException:
                pass


def main():
    log(f"worker iniciado (API {API}, NodeODM {ODM})")
    while True:
        try:
            job = api("POST", "/api/fila/proximo").json()
            if job.get("id"):
                process(job)
                continue
        except (requests.RequestException, ValueError) as e:
            log(f"aguardando a API: {e}")
        time.sleep(POLL_S)


if __name__ == "__main__":
    main()
