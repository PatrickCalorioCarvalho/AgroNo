"""AgroNo Fly - servidor de voos de drone (MVP).

Recebe o registro do voo enviado pelo app Mapa Drone (inclui a rota planejada e o nome da area) e as fotos
do cartao SD, guarda tudo por voo e mostra numa pagina web simples (GET /).

Armazenamento (mesmo layout nos dois modos):
    <voo_id>/voo.json
    <voo_id>/originais/<foto>
    <voo_id>/miniaturas/<foto>.jpg     (480 px; so se o Pillow estiver instalado)

  - Disco (padrao): pasta --data (padrao ./data).
  - RustFS / S3: ativado quando AGRONO_S3_ENDPOINT, AGRONO_S3_ACCESS_KEY e AGRONO_S3_SECRET_KEY existem
    (bucket em AGRONO_S3_BUCKET, padrao "agrono-drone"; criado se nao existir). Precisa de boto3.

Rotas:
    GET  /                                  pagina web (mapa, rota e galeria; usa URLs relativas,
                                            entao funciona atras de proxy com prefixo, ex.: /agrono-fly)
    GET  /health
    GET  /api/voos                          lista resumida
    GET  /api/voos/<id>                     voo.json completo
    POST /api/voos                          registra/atualiza um voo (corpo: JSON do app)
    GET  /api/voos/<id>/fotos               nomes das fotos recebidas
    PUT  /api/voos/<id>/fotos/<arquivo>     envia uma foto (corpo: bytes)
    GET  /api/voos/<id>/fotos/<arquivo>     foto original
    GET  /api/voos/<id>/miniaturas/<arquivo> miniatura (404 se nao houver)

Mosaico (processado pelo worker.py com o NodeODM; a fila fica em <voo_id>/processamento.json):
    POST /api/voos/<id>/processar           pede o mosaico (corpo opcional: {"qualidade": "rapido"|"completo"})
    GET  /api/voos/<id>/processamento       estado: nenhum | fila | processando | concluido | erro (+ progresso, mensagem)
    POST /api/fila/proximo                  (worker) pega o proximo voo da fila; {"id": null} se nao ha
    POST /api/voos/<id>/processamento       (worker) atualiza estado/progresso
    PUT  /api/voos/<id>/mosaico/<arquivo>   (worker) envia o resultado (orthophoto.tif, mosaico.jpg)
    GET  /api/voos/<id>/mosaico[/<arquivo>] lista / baixa o resultado

Uso:  python server.py [--port 8000] [--data ./data] [--host 0.0.0.0]
Sem autenticacao: use so em rede local confiavel.
"""
import argparse
import io
import json
import mimetypes
import os
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

try:  # opcionais
    import boto3
    from botocore.client import Config
    from botocore.exceptions import ClientError
except ImportError:  # pragma: no cover
    boto3 = None
try:
    from PIL import Image, ImageOps
except ImportError:  # pragma: no cover
    Image = None

ID_RE = re.compile(r"^[0-9A-Za-z_-]{1,64}$")
FILE_RE = re.compile(r"^[0-9A-Za-z_][0-9A-Za-z_.-]{0,127}$")  # nao comeca com ponto
MAX_JSON = 5 * 1024 * 1024
MAX_PHOTO = 100 * 1024 * 1024
THUMB_PX = 480
MAX_MOSAICO = 2 * 1024 * 1024 * 1024
MIN_FOTOS_MOSAICO = 3
FILA_STALE_S = 1800  # job "processando" sem sinal do worker por tanto tempo volta para a fila
FILA_LOCK = threading.Lock()


# ---------------------------------------------------------------- armazenamento

class DiskStorage:
    kind = "disco"

    def __init__(self, root):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)

    def _path(self, key):
        p = (self.root / key).resolve()
        if self.root not in p.parents:
            raise ValueError("chave invalida")
        return p

    def put(self, key, data, content_type=None):
        p = self._path(key)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)

    def get(self, key):
        p = self._path(key)
        return p.read_bytes() if p.is_file() else None

    def list_voos(self):
        return sorted(d.name for d in self.root.iterdir() if d.is_dir() and (d / "voo.json").is_file())

    def list_names(self, prefix):
        d = self._path(prefix)
        return sorted(f.name for f in d.iterdir() if f.is_file()) if d.is_dir() else []

    def describe(self):
        return f"disco em {self.root}"


class S3Storage:
    kind = "s3"

    def __init__(self, endpoint, access_key, secret_key, bucket):
        if boto3 is None:
            raise SystemExit("AGRONO_S3_* definidas, mas o boto3 nao esta instalado (pip install -r requirements.txt).")
        self.bucket = bucket
        self.endpoint = endpoint
        self.s3 = boto3.client(
            "s3", endpoint_url=endpoint, aws_access_key_id=access_key, aws_secret_access_key=secret_key,
            region_name="us-east-1", config=Config(signature_version="s3v4", s3={"addressing_style": "path"}))
        self._ensure_bucket(bucket)

    def _ensure_bucket(self, bucket, tentativas=30):
        # No docker compose o RustFS pode demorar alguns segundos para aceitar conexoes.
        for i in range(tentativas):
            try:
                try:
                    self.s3.head_bucket(Bucket=bucket)
                except ClientError as e:
                    if e.response["Error"]["Code"] in ("403", "AccessDenied"):
                        raise
                    self.s3.create_bucket(Bucket=bucket)
                return
            except Exception as e:  # conexao recusada, credencial invalida etc.
                if i == tentativas - 1:
                    raise SystemExit(f"Nao foi possivel usar o S3 {self.endpoint}: {e}")
                time.sleep(2)

    def put(self, key, data, content_type=None):
        extra = {"ContentType": content_type} if content_type else {}
        self.s3.put_object(Bucket=self.bucket, Key=key, Body=data, **extra)

    def get(self, key):
        try:
            return self.s3.get_object(Bucket=self.bucket, Key=key)["Body"].read()
        except ClientError as e:
            if e.response["Error"]["Code"] in ("NoSuchKey", "404", "NotFound"):
                return None
            raise

    def _list(self, prefix, delimiter=None):
        kwargs = {"Bucket": self.bucket, "Prefix": prefix}
        if delimiter:
            kwargs["Delimiter"] = delimiter
        while True:
            resp = self.s3.list_objects_v2(**kwargs)
            yield resp
            if not resp.get("IsTruncated"):
                return
            kwargs["ContinuationToken"] = resp["NextContinuationToken"]

    def list_voos(self):
        ids = set()
        for resp in self._list("", "/"):
            for cp in resp.get("CommonPrefixes", []):
                vid = cp["Prefix"].rstrip("/")
                if self.get(f"{vid}/voo.json") is not None:
                    ids.add(vid)
        return sorted(ids)

    def list_names(self, prefix):
        names = []
        for resp in self._list(prefix.rstrip("/") + "/"):
            names += [o["Key"].rsplit("/", 1)[-1] for o in resp.get("Contents", []) if not o["Key"].endswith("/")]
        return sorted(names)

    def describe(self):
        return f"S3 {self.endpoint} bucket {self.bucket}"


def make_storage(data_dir):
    endpoint = os.environ.get("AGRONO_S3_ENDPOINT")
    access = os.environ.get("AGRONO_S3_ACCESS_KEY")
    secret = os.environ.get("AGRONO_S3_SECRET_KEY")
    if endpoint and access and secret:
        return S3Storage(endpoint, access, secret, os.environ.get("AGRONO_S3_BUCKET", "agrono-drone"))
    return DiskStorage(data_dir)


def make_thumbnail(data):
    """Miniatura JPEG de 480 px, ou None se o Pillow nao estiver instalado / a imagem nao abrir."""
    if Image is None:
        return None
    try:
        img = Image.open(io.BytesIO(data))
        img = ImageOps.exif_transpose(img)
        img.thumbnail((THUMB_PX, THUMB_PX))
        out = io.BytesIO()
        img.convert("RGB").save(out, "JPEG", quality=80)
        return out.getvalue()
    except Exception:
        return None


# ---------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    storage = None

    def _send_bytes(self, code, body, ctype, cache=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        if cache:
            self.send_header("Cache-Control", cache)
        self.end_headers()
        self.wfile.write(body)

    def _send(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self._send_bytes(code, body, "application/json; charset=utf-8")

    def _read_body(self, limit):
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > limit:
            return None
        return self.rfile.read(length)

    def _voo(self, voo_id):
        raw = self.storage.get(f"{voo_id}/voo.json")
        return json.loads(raw) if raw is not None else None

    def _proc(self, voo_id):
        raw = self.storage.get(f"{voo_id}/processamento.json")
        return json.loads(raw) if raw is not None else {"status": "nenhum"}

    def _set_proc(self, voo_id, proc):
        proc["atualizado"] = time.time()
        self.storage.put(f"{voo_id}/processamento.json", json.dumps(proc, ensure_ascii=False).encode("utf-8"),
                         "application/json")

    def _read_json(self, limit=MAX_JSON):
        """Corpo JSON (objeto). Corpo vazio vale {}; invalido devolve None."""
        if int(self.headers.get("Content-Length", "0")) <= 0:
            return {}
        raw = self._read_body(limit)
        try:
            obj = json.loads(raw) if raw is not None else None
        except ValueError:
            return None
        return obj if isinstance(obj, dict) else None

    def do_GET(self):
        parts = self.path.split("?")[0].strip("/").split("/")
        st = self.storage
        if parts == [""] or parts == ["index.html"]:
            return self._send_bytes(200, INDEX_HTML.encode("utf-8"), "text/html; charset=utf-8")
        if parts == ["health"]:
            return self._send(200, {"ok": True, "storage": st.kind})
        if parts == ["api", "voos"]:
            flights = []
            for vid in st.list_voos():
                try:
                    v = self._voo(vid)
                    flights.append({
                        "id": v.get("id", vid), "areaName": v.get("areaName", ""), "status": v.get("status"),
                        "startedAt": v.get("startedAt"), "photosTriggered": v.get("photosTriggered"),
                        "fotosRecebidas": len(st.list_names(f"{vid}/originais")),
                        "mosaico": self._proc(vid).get("status", "nenhum")})
                except (OSError, ValueError, AttributeError):
                    continue
            return self._send(200, flights)
        if len(parts) >= 3 and parts[:2] == ["api", "voos"] and ID_RE.match(parts[2]):
            vid = parts[2]
            if len(parts) == 3:
                v = self._voo(vid)
                return self._send(200, v) if v is not None else self._send(404, {"error": "nao encontrado"})
            if len(parts) == 4 and parts[3] == "fotos":
                if self._voo(vid) is None:
                    return self._send(404, {"error": "nao encontrado"})
                return self._send(200, st.list_names(f"{vid}/originais"))
            if len(parts) == 4 and parts[3] == "processamento":
                if self._voo(vid) is None:
                    return self._send(404, {"error": "nao encontrado"})
                return self._send(200, self._proc(vid))
            if len(parts) == 4 and parts[3] == "mosaico":
                return self._send(200, st.list_names(f"{vid}/mosaico"))
            if len(parts) == 5 and parts[3] == "mosaico" and FILE_RE.match(parts[4]):
                data = st.get(f"{vid}/mosaico/{parts[4]}")
                if data is None:
                    return self._send(404, {"error": "nao encontrado"})
                return self._send_bytes(200, data, mimetypes.guess_type(parts[4])[0] or "image/tiff", "no-cache")
            if len(parts) == 5 and parts[3] in ("fotos", "miniaturas") and FILE_RE.match(parts[4]):
                folder, name = ("originais", parts[4]) if parts[3] == "fotos" else ("miniaturas", parts[4] + ".jpg")
                data = st.get(f"{vid}/{folder}/{name}")
                if data is None:
                    return self._send(404, {"error": "nao encontrado"})
                ctype = "image/jpeg" if folder == "miniaturas" else (mimetypes.guess_type(name)[0] or "application/octet-stream")
                return self._send_bytes(200, data, ctype, "public, max-age=86400")
        self._send(404, {"error": "nao encontrado"})

    def do_POST(self):
        parts = self.path.split("?")[0].strip("/").split("/")
        if parts == ["api", "fila", "proximo"]:
            return self._fila_proximo()
        if len(parts) == 4 and parts[:2] == ["api", "voos"] and ID_RE.match(parts[2]) \
                and parts[3] in ("processar", "processamento"):
            return self._processar(parts[2]) if parts[3] == "processar" else self._atualizar_proc(parts[2])
        if parts != ["api", "voos"]:
            return self._send(404, {"error": "nao encontrado"})
        raw = self._read_body(MAX_JSON)
        if raw is None:
            return self._send(400, {"error": "corpo vazio ou grande demais"})
        try:
            voo = json.loads(raw)
        except ValueError:
            return self._send(400, {"error": "JSON invalido"})
        if not isinstance(voo, dict):
            return self._send(400, {"error": "JSON invalido"})
        voo_id = str(voo.get("id", ""))
        if not ID_RE.match(voo_id):
            return self._send(400, {"error": "id do voo invalido"})
        self.storage.put(f"{voo_id}/voo.json", json.dumps(voo, ensure_ascii=False, indent=2).encode("utf-8"),
                         "application/json")
        self.log_message("voo %s salvo (%s pontos na rota, area '%s')", voo_id, len(voo.get("route", [])),
                         voo.get("areaName", ""))
        self._send(200, {"ok": True, "id": voo_id})

    def _processar(self, vid):
        body = self._read_json()
        if body is None:
            return self._send(400, {"error": "JSON invalido"})
        if self._voo(vid) is None:
            return self._send(404, {"error": "voo nao registrado"})
        qualidade = body.get("qualidade", "rapido")
        if qualidade not in ("rapido", "completo"):
            return self._send(400, {"error": "qualidade deve ser 'rapido' ou 'completo'"})
        n = len(self.storage.list_names(f"{vid}/originais"))
        if n < MIN_FOTOS_MOSAICO:
            return self._send(400, {"error": f"so {n} foto(s) no servidor; o mosaico precisa de pelo menos {MIN_FOTOS_MOSAICO}"})
        with FILA_LOCK:
            atual = self._proc(vid)
            ativo = atual.get("status") in ("fila", "processando") and time.time() - atual.get("atualizado", 0) < FILA_STALE_S
            if ativo:
                return self._send(409, {"error": f"ja esta '{atual['status']}'", "processamento": atual})
            self._set_proc(vid, {"status": "fila", "qualidade": qualidade, "solicitado": time.time(),
                                 "progresso": 0, "mensagem": "Na fila"})
        self.log_message("mosaico do voo %s na fila (%s, %s fotos)", vid, qualidade, n)
        self._send(200, {"ok": True, "status": "fila"})

    def _fila_proximo(self):
        """Entrega ao worker o voo mais antigo da fila (ou um 'processando' abandonado) e o marca como processando."""
        with FILA_LOCK:
            candidatos = []
            for vid in self.storage.list_voos():
                p = self._proc(vid)
                abandonado = p.get("status") == "processando" and time.time() - p.get("atualizado", 0) > FILA_STALE_S
                if p.get("status") == "fila" or abandonado:
                    candidatos.append((p.get("solicitado", 0), vid, p))
            if not candidatos:
                return self._send(200, {"id": None})
            _, vid, p = min(candidatos, key=lambda c: (c[0], c[1]))
            p.update({"status": "processando", "mensagem": "Iniciando", "progresso": 0})
            self._set_proc(vid, p)
            fotos = self.storage.list_names(f"{vid}/originais")
        self.log_message("worker pegou o voo %s (%s fotos)", vid, len(fotos))
        self._send(200, {"id": vid, "qualidade": p.get("qualidade", "rapido"), "fotos": fotos})

    def _atualizar_proc(self, vid):
        body = self._read_json()
        if body is None or self._voo(vid) is None:
            return self._send(400 if body is None else 404, {"error": "JSON invalido ou voo nao registrado"})
        status = body.get("status")
        if status not in ("processando", "concluido", "erro"):
            return self._send(400, {"error": "status invalido"})
        with FILA_LOCK:
            p = self._proc(vid)
            p["status"] = status
            if "progresso" in body:
                p["progresso"] = body["progresso"]
            p["mensagem"] = str(body.get("mensagem", ""))[:500]
            self._set_proc(vid, p)
        self._send(200, {"ok": True})

    def do_PUT(self):
        parts = self.path.split("?")[0].strip("/").split("/")
        if len(parts) == 5 and parts[:2] == ["api", "voos"] and parts[3] == "mosaico" \
                and ID_RE.match(parts[2]) and FILE_RE.match(parts[4]):
            vid, name = parts[2], parts[4]
            if self._voo(vid) is None:
                return self._send(404, {"error": "voo nao registrado"})
            raw = self._read_body(MAX_MOSAICO)
            if raw is None:
                return self._send(400, {"error": "corpo vazio ou grande demais"})
            self.storage.put(f"{vid}/mosaico/{name}", raw, mimetypes.guess_type(name)[0] or "application/octet-stream")
            return self._send(200, {"ok": True})
        if len(parts) == 5 and parts[:2] == ["api", "voos"] and parts[3] == "fotos" \
                and ID_RE.match(parts[2]) and FILE_RE.match(parts[4]):
            vid, name = parts[2], parts[4]
            if self._voo(vid) is None:
                return self._send(404, {"error": "voo nao registrado"})
            raw = self._read_body(MAX_PHOTO)
            if raw is None:
                return self._send(400, {"error": "corpo vazio ou grande demais"})
            ctype = mimetypes.guess_type(name)[0] or "application/octet-stream"
            self.storage.put(f"{vid}/originais/{name}", raw, ctype)
            thumb = make_thumbnail(raw)
            if thumb:
                self.storage.put(f"{vid}/miniaturas/{name}.jpg", thumb, "image/jpeg")
            return self._send(200, {"ok": True, "miniatura": bool(thumb)})
        self._send(404, {"error": "nao encontrado"})


# ---------------------------------------------------------------- pagina web

INDEX_HTML = r"""<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>AgroNo Fly · Voos</title>
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css">
<style>
  :root { --bg:#0b0f14; --panel:#141b23; --card:#1c2631; --text:#e6edf3; --dim:#9aa7b4; --accent:#2ea043; --line:#2a3644; }
  * { box-sizing: border-box; }
  body { margin:0; background:var(--bg); color:var(--text); font:14px/1.45 system-ui,Segoe UI,Roboto,sans-serif; display:flex; height:100vh; }
  aside { width:320px; background:var(--panel); border-right:1px solid var(--line); overflow:auto; padding:14px; flex:none; }
  main { flex:1; overflow:auto; padding:16px 20px; }
  h1 { font-size:18px; margin:0 0 12px; } h2 { font-size:15px; margin:18px 0 8px; color:var(--dim); font-weight:600; }
  .area { margin:14px 0 4px; color:var(--dim); font-size:12px; text-transform:uppercase; letter-spacing:.06em; }
  .voo { display:block; width:100%; text-align:left; background:var(--card); color:var(--text); border:1px solid transparent;
         border-radius:10px; padding:9px 11px; margin:5px 0; cursor:pointer; font:inherit; }
  .voo:hover { border-color:var(--line); } .voo.sel { border-color:var(--accent); }
  .voo small { display:block; color:var(--dim); }
  .tag { display:inline-block; padding:1px 8px; border-radius:99px; background:#27323f; font-size:12px; margin-left:6px; }
  .tag.ok { background:#1b4d2b; } .tag.warn { background:#5a4510; } .tag.bad { background:#5a1d1d; }
  #map { height:420px; border-radius:12px; border:1px solid var(--line); }
  .leaflet-tile-pane { filter: invert(1) hue-rotate(180deg) brightness(.9) contrast(.9); }
  .grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(150px,1fr)); gap:10px; margin-top:6px; }
  .grid div { background:var(--card); border-radius:10px; padding:10px; } .grid b { display:block; font-size:16px; }
  .grid span { color:var(--dim); font-size:12px; }
  .gal { display:grid; grid-template-columns:repeat(auto-fill,minmax(170px,1fr)); gap:8px; }
  .gal a { display:block; background:var(--card); border-radius:8px; overflow:hidden; aspect-ratio:4/3; }
  .gal img { width:100%; height:100%; object-fit:cover; display:block; }
  pre { background:var(--card); padding:10px; border-radius:10px; overflow:auto; max-height:220px; white-space:pre-wrap; }
  .empty { color:var(--dim); padding:30px 0; }
  .hicon { background:#0d47a1; color:#fff; font-weight:700; border:2px solid #fff; border-radius:50%; text-align:center; line-height:24px; }
  @media (max-width:800px){ body{flex-direction:column;height:auto} aside{width:auto;max-height:40vh} }
</style>
</head>
<body>
<aside>
  <h1>AgroNo Fly</h1>
  <div id="list"><div class="empty">Carregando…</div></div>
</aside>
<main id="main"><div class="empty">Escolha um voo na lista.</div></main>
<script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js"></script>
<script>
const $ = (s) => document.querySelector(s);
const esc = (t) => String(t ?? '').replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let map = null, selected = null;
// URLs relativas: atras de proxy com prefixo (ex.: /agrono-fly) tudo resolve dentro do prefixo.
// Se a pagina foi aberta sem barra final (/agrono-fly), o prefixo e re-incluido para o relativo nao subir um nivel.
const last = location.pathname.split('/').pop();
const API = (last && !last.includes('.') ? last + '/' : '') + 'api/voos';

function statusClass(s) {
  s = (s || '').toLowerCase();
  if (s.startsWith('concluido')) return 'ok';
  if (s.includes('erro') || s.includes('interrompido')) return 'bad';
  return 'warn';
}

async function loadList() {
  const r = await fetch(API);
  const voos = await r.json();
  voos.sort((a, b) => String(b.id).localeCompare(String(a.id)));
  const groups = {};
  voos.forEach((v) => { (groups[(v.areaName || '').trim() || 'Sem nome'] ||= []).push(v); });
  const el = $('#list');
  if (!voos.length) { el.innerHTML = '<div class="empty">Nenhum voo ainda.</div>'; return; }
  el.innerHTML = '';
  Object.keys(groups).sort().forEach((area) => {
    const h = document.createElement('div'); h.className = 'area'; h.textContent = `${area} (${groups[area].length})`; el.appendChild(h);
    groups[area].forEach((v) => {
      const b = document.createElement('button'); b.className = 'voo' + (v.id === selected ? ' sel' : '');
      b.innerHTML = `<b>${esc(v.id)}</b><span class="tag ${statusClass(v.status)}">${esc(v.status)}</span>` +
        `<small>${esc(v.startedAt)} · fotos ${esc(v.fotosRecebidas)} recebidas / ${esc(v.photosTriggered ?? '?')} disparadas</small>`;
      b.onclick = () => openVoo(v.id);
      el.appendChild(b);
    });
  });
}

function stat(label, value) { return `<div><b>${esc(value ?? '—')}</b><span>${esc(label)}</span></div>`; }

async function openVoo(id) {
  selected = id; loadList();
  const [v, fotos] = await Promise.all([
    fetch(`${API}/${encodeURIComponent(id)}`).then((r) => r.json()),
    fetch(`${API}/${encodeURIComponent(id)}/fotos`).then((r) => r.json()).catch(() => []),
  ]);
  const pct = (x) => (x == null ? '—' : Math.round(x * 100) + '%');
  $('#main').innerHTML = `
    <h1>${esc(v.areaName || 'Sem nome')} · ${esc(v.id)} <span class="tag ${statusClass(v.status)}">${esc(v.status)}</span></h1>
    <div id="map"></div>
    <h2>Mosaico</h2><div id="mos"></div>
    <h2>Parâmetros</h2>
    <div class="grid">
      ${stat('altura (m)', v.altitudeM)}${stat('sobreposição frontal', pct(v.frontOverlap))}${stat('sobreposição lateral', pct(v.sideOverlap))}
      ${stat('velocidade (m/s)', v.speedMs)}${stat('partes', `${v.partsDone ?? 0}/${v.partsTotal ?? '?'}`)}
      ${stat('pontos planejados', v.waypointCount)}${stat('fotos disparadas', v.photosTriggered)}${stat('fotos recebidas', fotos.length)}
      ${stat('início', v.startedAt)}${stat('fim', v.endedAt || '—')}
    </div>
    ${v.notes ? `<h2>Notas</h2><pre>${esc(v.notes)}</pre>` : ''}
    <h2>Fotos (${fotos.length})</h2>
    <div class="gal" id="gal">${fotos.length ? '' : '<div class="empty">Nenhuma foto recebida. Use upload_fotos.py.</div>'}</div>
    <h2>Log</h2><pre>${esc((v.log || []).join('\n'))}</pre>`;
  const gal = $('#gal');
  fotos.forEach((f) => {
    const base = `${API}/${encodeURIComponent(id)}`;
    const a = document.createElement('a'); a.href = `${base}/fotos/${encodeURIComponent(f)}`; a.target = '_blank'; a.title = f;
    const img = document.createElement('img'); img.loading = 'lazy'; img.src = `${base}/miniaturas/${encodeURIComponent(f)}`;
    img.onerror = () => { img.onerror = null; img.src = a.href; };
    a.appendChild(img); gal.appendChild(a);
  });
  drawMap(v);
  renderMosaico(id, fotos.length);
}

let mosTimer = null;
async function renderMosaico(id, nFotos) {
  clearTimeout(mosTimer);
  const el = $('#mos');
  if (!el || selected !== id) return;
  const base = `${API}/${encodeURIComponent(id)}`;
  const p = await fetch(`${base}/processamento`).then((r) => r.json()).catch(() => ({ status: 'nenhum' }));
  const busy = p.status === 'fila' || p.status === 'processando';
  let html = '';
  if (busy) html += `<div class="tag warn">${esc(p.status)}</div> ${esc(p.mensagem || '')} <b>${esc(p.progresso ?? 0)}%</b>`;
  else if (p.status === 'erro') html += `<div class="tag bad">erro</div> ${esc(p.mensagem || '')}`;
  else if (p.status === 'concluido') {
    html += `<div class="tag ok">concluído</div> <a href="${base}/mosaico/orthophoto.tif" style="color:var(--accent)">baixar GeoTIFF</a>` +
      ` · <a href="${base}/mosaico/mosaico.jpg" target="_blank" style="color:var(--accent)">abrir imagem</a>` +
      `<div style="margin-top:8px"><img src="${base}/mosaico/mosaico.jpg?t=${Date.now()}" style="max-width:100%;border-radius:10px" onerror="this.remove()"></div>`;
  }
  if (!busy) {
    html += `<div style="margin-top:8px"><button class="voo" id="btnMos" style="width:auto" ${nFotos < 3 ? 'disabled' : ''}>` +
      `${p.status === 'concluido' ? 'Gerar de novo' : 'Gerar mosaico'}</button> ` +
      `<select id="selQ" style="background:var(--card);color:var(--text);border:1px solid var(--line);border-radius:8px;padding:6px">` +
      `<option value="rapido">rápido</option><option value="completo">completo</option></select>` +
      `${nFotos < 3 ? ' <small style="color:var(--dim)">precisa de pelo menos 3 fotos no servidor</small>' : ''}</div>`;
  }
  el.innerHTML = html;
  const b = $('#btnMos');
  if (b) b.onclick = async () => {
    b.disabled = true;
    const r = await fetch(`${base}/processar`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ qualidade: $('#selQ').value }) });
    if (!r.ok) alert((await r.json().catch(() => ({}))).error || 'Falha ao pedir o mosaico');
    renderMosaico(id, nFotos);
  };
  if (busy) mosTimer = setTimeout(() => renderMosaico(id, nFotos), 5000);
}

function drawMap(v) {
  if (map) { map.remove(); map = null; }
  map = L.map('map');
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© OpenStreetMap' }).addTo(map);
  const all = [];
  const corners = (v.corners || []).map((c) => [c.lat, c.lon]);
  if (corners.length >= 3) { L.polygon(corners, { color: '#2ea043', weight: 2, fillOpacity: .15 }).addTo(map); all.push(...corners); }
  const route = v.route || [];
  if (route.length) {
    L.polyline(route, { color: '#3b82f6', weight: 2 }).addTo(map);
    route.forEach((p, i) => L.circleMarker(p, { radius: i === 0 ? 7 : 3, color: i === 0 ? '#2ea043' : '#3b82f6', fillOpacity: 1, weight: 1 }).addTo(map));
    all.push(...route);
  }
  if (v.home) {
    const p = [v.home.lat, v.home.lon];
    L.marker(p, { icon: L.divIcon({ className: '', html: '<div class="hicon">H</div>', iconSize: [24, 24] }) }).bindTooltip('Decolagem / pouso').addTo(map);
    all.push(p);
  }
  if (all.length) map.fitBounds(all, { padding: [24, 24] }); else map.setView([-15.78, -47.93], 4);
}

loadList();
</script>
</body>
</html>
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--data", default="data")
    ap.add_argument("--host", default="0.0.0.0")
    args = ap.parse_args()
    Handler.storage = make_storage(args.data)
    print(f"AgroNo Fly em http://{args.host}:{args.port}  (armazenamento: {Handler.storage.describe()};"
          f" miniaturas: {'sim' if Image else 'nao (instale Pillow)'})")
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
