"""Apaga do servidor as fotos a mais de um voo (ex.: fotos de outro voo que entraram no envio).

As fotos do DJI tem numeracao sequencial (DJI_0001.JPG...), entao as fotos do voo sao as ULTIMAS N
(N = fotos disparadas segundo o registro do voo, ou o valor de --manter). As anteriores sao tidas como sobras.
Sem --apagar so mostra o que seria apagado. As fotos continuam no cartao SD do drone.

Uso:  python apagar_fotos.py <voo_id> [--manter N] [--apagar] [--server http://192.168.18.30:8010]
"""
import argparse
import json
import sys
import urllib.error
import urllib.request


def call(method, url):
    with urllib.request.urlopen(urllib.request.Request(url, method=method), timeout=60) as r:
        return json.loads(r.read() or b"null")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("voo_id")
    ap.add_argument("--manter", type=int, help="quantas fotos manter (padrao: fotos disparadas do voo)")
    ap.add_argument("--apagar", action="store_true", help="apaga de verdade (sem isso, so mostra)")
    ap.add_argument("--server", default="http://localhost:8000")
    a = ap.parse_args()
    base = f"{a.server.rstrip('/')}/api/voos/{a.voo_id}"
    voo = call("GET", base)
    fotos = sorted(call("GET", base + "/fotos"))
    n = a.manter if a.manter is not None else voo.get("photosTriggered")
    if not n or n <= 0:
        sys.exit("Informe --manter N (o voo nao tem fotos disparadas registradas).")
    if len(fotos) <= n:
        sys.exit(f"O servidor tem {len(fotos)} foto(s) e o voo disparou {n}: nada a apagar.")
    sobras, ficam = fotos[:-n], fotos[-n:]
    print(f"Voo {a.voo_id}: {len(fotos)} fotos no servidor, ficam as {n} ultimas ({ficam[0]} a {ficam[-1]}).")
    print(f"Sobras ({len(sobras)}): {sobras[0]} a {sobras[-1]}")
    if not a.apagar:
        sys.exit("Nada foi apagado. Confira acima e rode de novo com --apagar.")
    ok = 0
    for nome in sobras:
        try:
            call("DELETE", f"{base}/fotos/{nome}")
            ok += 1
        except urllib.error.HTTPError as e:
            print(f"ERRO {nome}: HTTP {e.code} {e.read().decode('utf-8', 'replace')}")
    print(f"{ok}/{len(sobras)} fotos apagadas.")


if __name__ == "__main__":
    main()
