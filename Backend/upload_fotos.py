"""Envia as fotos de uma pasta (ex.: copiadas do cartao SD do drone) para o servidor, para um voo ja sincronizado.

Uso:  python upload_fotos.py <voo_id> <pasta_com_fotos> [--server http://localhost:8000]
Envia so .jpg/.jpeg/.dng; o voo precisa ter sido enviado antes pelo app (Mais > Sincronizar voos).
"""
import argparse
import sys
import urllib.error
import urllib.request
from pathlib import Path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("voo_id")
    ap.add_argument("pasta")
    ap.add_argument("--server", default="http://localhost:8000")
    args = ap.parse_args()

    fotos = sorted(p for p in Path(args.pasta).iterdir() if p.suffix.lower() in {".jpg", ".jpeg", ".dng"})
    if not fotos:
        sys.exit("Nenhuma foto encontrada na pasta.")
    ok = 0
    for foto in fotos:
        req = urllib.request.Request(
            f"{args.server.rstrip('/')}/api/voos/{args.voo_id}/fotos/{foto.name}",
            data=foto.read_bytes(), method="PUT", headers={"Content-Type": "application/octet-stream"})
        try:
            urllib.request.urlopen(req, timeout=120).read()
            ok += 1
            print(f"ok  {foto.name}")
        except urllib.error.HTTPError as e:
            print(f"ERRO {foto.name}: HTTP {e.code} {e.read().decode('utf-8', 'replace')}")
        except OSError as e:
            sys.exit(f"Falha de rede em {foto.name}: {e}")
    print(f"{ok}/{len(fotos)} fotos enviadas.")


if __name__ == "__main__":
    main()
