"""AgroNo Fly - analise da lavoura sobre o ortomosaico (so RGB, sem infravermelho).

analisar(tif_path, corners=None) le o GeoTIFF reduzido e devolve (resultado, arquivos):
  - indices de vegetacao em cores visiveis: VARI, ExG e GLI (mapas de calor com transparencia fora da area)
  - cobertura do solo (planta x solo, limiar de Otsu sobre o ExG)
  - zonas de vigor (3 classes por quantis do VARI, suavizado)
  - estatisticas (area, cobertura, indices, area por zona)
Nao ha NIR: nao e NDVI. Os indices separam planta de solo e mostram diferencas de vigor, mas dependem da luz
do dia e nao sao comparaveis entre voos sem calibracao.
"""
import io
import json
import time

import numpy as np
from PIL import Image

MAX_PX = 3000          # lado maior da imagem de analise
ZONAS = [("baixo", (214, 69, 65)), ("medio", (240, 192, 64)), ("alto", (46, 160, 67))]
AVISO = ("Indices calculados so com a camera RGB (sem infravermelho): nao e NDVI. "
         "Valem para comparar areas do mesmo voo; entre voos mude a luz e o resultado muda.")

# Mapa de calor vermelho -> amarelo -> verde
_RAMP = np.array([[165, 0, 38], [244, 109, 67], [254, 224, 139], [166, 217, 106], [26, 152, 80]], dtype=np.float32)


def _ramp(t):
    """t em [0,1] (array) -> RGB uint8."""
    t = np.round(np.clip(t, 0, 1) * 23) / 23 * (len(_RAMP) - 1)   # 24 niveis: PNG bem menor
    i = np.minimum(t.astype(int), len(_RAMP) - 2)
    f = (t - i)[..., None]
    return (_RAMP[i] * (1 - f) + _RAMP[i + 1] * f).astype(np.uint8)


def _png(rgb, alpha):
    img = np.dstack([rgb, (alpha * 255).astype(np.uint8)])
    out = io.BytesIO()
    Image.fromarray(img, "RGBA").save(out, "PNG", optimize=True)
    return out.getvalue()


def _box_blur(a, r):
    """Media em janela (2r+1)^2, por soma acumulada (sem scipy)."""
    if r < 1:
        return a
    k = 2 * r + 1
    p = np.pad(a, r, mode="edge").astype(np.float64)
    c = np.cumsum(np.cumsum(p, 0), 1)
    c = np.pad(c, ((1, 0), (1, 0)))
    s = c[k:, k:] - c[:-k, k:] - c[k:, :-k] + c[:-k, :-k]
    return (s / (k * k)).astype(np.float32)


def _otsu(v, bins=256):
    h, e = np.histogram(v, bins=bins)
    h = h.astype(np.float64)
    c = (e[:-1] + e[1:]) / 2
    w0 = np.cumsum(h)
    w1 = w0[-1] - w0
    m0 = np.cumsum(h * c) / np.maximum(w0, 1)
    m1 = (np.sum(h * c) - np.cumsum(h * c)) / np.maximum(w1, 1)
    var = w0 * w1 * (m0 - m1) ** 2
    return float(c[int(np.argmax(var))])


def _stretch(v, mask, lo_p=2, hi_p=98):
    lo, hi = np.percentile(v[mask], [lo_p, hi_p])
    if hi - lo < 1e-6:
        hi = lo + 1e-6
    return float(lo), float(hi)


def _ler(tif_path):
    import rasterio
    from rasterio.enums import Resampling
    from rasterio.warp import transform, transform_bounds

    with rasterio.open(tif_path) as ds:
        if ds.count < 3:
            raise ValueError("o ortomosaico precisa de pelo menos 3 bandas (RGB)")
        esc = min(1.0, MAX_PX / max(ds.width, ds.height))
        w, h = max(1, int(ds.width * esc)), max(1, int(ds.height * esc))
        bands = [1, 2, 3] + ([4] if ds.count >= 4 else [])
        dados = ds.read(bands, out_shape=(len(bands), h, w), resampling=Resampling.average).astype(np.float32)
        rgb = dados[:3]
        if ds.count >= 4:
            valido = dados[3] > 0
        else:
            valido = rgb.max(axis=0) > 0
        res_x = abs(ds.transform.a) / esc   # metros por pixel da imagem reduzida (CRS projetado)
        res_y = abs(ds.transform.e) / esc
        if ds.crs is not None and ds.crs.is_geographic:
            res_x *= 111320 * np.cos(np.radians((ds.bounds.top + ds.bounds.bottom) / 2))
            res_y *= 110540
        w_, s_, e_, n_ = transform_bounds(ds.crs, "EPSG:4326", *ds.bounds)

        def para_pixel(lat_lon):
            xs, ys = transform("EPSG:4326", ds.crs, [p[1] for p in lat_lon], [p[0] for p in lat_lon])
            return [tuple(~ds.transform * (x, y)) for x, y in zip(xs, ys)], esc

        return rgb, valido, (res_x, res_y), [[s_, w_], [n_, e_]], para_pixel, (w, h)


def analisar(tif_path, corners=None):
    """Devolve (resultado: dict, arquivos: {nome: bytes})."""
    rgb, valido, (rx, ry), bounds, para_pixel, (w, h) = _ler(tif_path)
    r, g, b = rgb
    aviso_poly = None

    # Restringe a area planejada do voo (cantos), se houver; o mosaico tem sobra nas bordas.
    mask = valido.copy()
    if corners and len(corners) >= 3:
        try:
            pts, esc = para_pixel([(c["lat"], c["lon"]) for c in corners])
            m = Image.new("L", (w, h), 0)
            from PIL import ImageDraw
            ImageDraw.Draw(m).polygon([(x * esc, y * esc) for x, y in pts], fill=1)
            dentro = np.array(m, dtype=bool) & valido
            if dentro.sum() > 0.2 * valido.sum() or dentro.sum() > 1000:
                mask = dentro
            else:
                aviso_poly = "a area do voo nao cobre o mosaico; usando o mosaico inteiro"
        except Exception as e:  # CRS exotico etc.: nao e fatal
            aviso_poly = f"nao consegui aplicar o poligono do voo ({e}); usando o mosaico inteiro"
    if mask.sum() < 100:
        raise ValueError("o mosaico nao tem area valida para analisar")

    tot = r + g + b + 1e-6
    rn, gn, bn = r / tot, g / tot, b / tot
    exg = 2 * gn - rn - bn
    vari = (g - r) / (g + r - b + 1e-6)
    vari = np.clip(vari, -1, 1)
    gli = (2 * g - r - b) / (2 * g + r + b + 1e-6)

    pix_m2 = rx * ry
    area_ha = float(mask.sum() * pix_m2 / 10000)
    raio = max(1, int(round(1.5 / max(rx, 1e-3))))   # suavizacao de ~1,5 m nas zonas

    arquivos = {}
    indices = {}
    for nome, arr in (("vari", vari), ("exg", exg), ("gli", gli)):
        lo, hi = _stretch(arr, mask)
        rgb_c = _ramp((_box_blur(arr, 2) - lo) / (hi - lo))   # leve suavizacao so na imagem
        arquivos[f"{nome}.png"] = _png(rgb_c, mask.astype(np.float32) * 0.9)
        v = arr[mask]
        indices[nome] = {"min": lo, "max": hi, "media": float(v.mean()),
                         "p10": float(np.percentile(v, 10)), "p50": float(np.percentile(v, 50)),
                         "p90": float(np.percentile(v, 90))}

    # Cobertura do solo: planta x solo (Otsu sobre o ExG).
    limiar = _otsu(exg[mask])
    veg = (exg > limiar) & mask
    cobertura = float(veg.sum() / mask.sum() * 100)
    cov = np.zeros((h, w, 3), np.uint8)
    cov[veg] = (46, 160, 67)
    cov[mask & ~veg] = (150, 104, 70)
    arquivos["cobertura.png"] = _png(cov, mask.astype(np.float32) * 0.75)

    # Zonas de vigor: quantis do VARI suavizado dentro da area.
    mf = mask.astype(np.float32)
    vs = _box_blur(np.where(mask, vari, 0), raio) / np.maximum(_box_blur(mf, raio), 1e-3)   # media so da area valida
    q1, q2 = np.percentile(vs[mask], [100 / 3, 200 / 3])
    cls = np.where(vs < q1, 0, np.where(vs < q2, 1, 2))
    zrgb = np.zeros((h, w, 3), np.uint8)
    zonas = []
    for i, (nome, cor) in enumerate(ZONAS):
        sel = mask & (cls == i)
        zrgb[sel] = cor
        zonas.append({"nome": nome, "cor": "#%02x%02x%02x" % cor, "pct": float(sel.sum() / mask.sum() * 100),
                      "area_ha": float(sel.sum() * pix_m2 / 10000),
                      "vari_medio": float(vs[sel].mean()) if sel.any() else None})
    arquivos["zonas.png"] = _png(zrgb, mask.astype(np.float32) * 0.8)

    resultado = {
        "versao": 1, "gerado": time.time(), "bounds": bounds,
        "resolucao_m": float((rx + ry) / 2), "pixels": [w, h],
        "area_ha": area_ha, "cobertura_pct": cobertura, "limiar_exg": float(limiar),
        "indices": indices, "zonas": zonas, "camadas": sorted(arquivos),
        "aviso": AVISO + (" " + aviso_poly if aviso_poly else ""),
        "poligono_do_voo": bool(corners) and aviso_poly is None,
    }
    arquivos["analise.json"] = json.dumps(resultado, ensure_ascii=False).encode("utf-8")
    return resultado, arquivos
