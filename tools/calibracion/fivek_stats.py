"""
Estadísticas de cómo editan los fotógrafos profesionales (MIT-Adobe FiveK, experto C).

Para cada pareja (original "plana" del sensor / versión del experto) mide, en luz lineal:
  · clave de exposición (media logarítmica de la luz) y cuánto la cambió el experto (pasos EV),
  · altas luces (percentil 99) y zona quemada,
  · color (croma CIELAB: mediana y percentil 95) — ¿cuánto saturan de verdad los profesionales?,
  · contraste global y local,
  · curva de tono (luminancia de la original → la del experto, por cuantiles).
Agrupa por tipo de escena (atardecer, paisaje, ciudad, retrato, noche, interior) y guarda un JSON
con los valores que usa la app (Calibration.kt).

Uso:  python3 tools/calibracion/fivek_stats.py <carpeta_fivek> <salida.json>\n      python3 tools/calibracion/fivek_stats.py <salida.json>   (constantes para Calibration.kt)
Las fotos de FiveK son solo para investigación: no se incluyen en el repositorio.
"""
import glob
import json
import os
import sys

import numpy as np
from PIL import Image

SIDE = 512


def srgb_to_lin(a):
    a = a / 255.0
    return np.where(a <= 0.04045, a / 12.92, ((a + 0.055) / 1.055) ** 2.4)


def lin_to_lab(rgb):
    m = np.array([[0.4124, 0.3576, 0.1805], [0.2126, 0.7152, 0.0722], [0.0193, 0.1192, 0.9505]])
    xyz = rgb @ m.T / np.array([0.95047, 1.0, 1.08883])
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16 / 116)
    L = 116 * f[..., 1] - 16
    a = 500 * (f[..., 0] - f[..., 1])
    b = 200 * (f[..., 1] - f[..., 2])
    return L, a, b


def load(path):
    im = Image.open(path).convert("RGB")
    im.thumbnail((SIDE, SIDE), Image.LANCZOS)
    return np.asarray(im).astype(np.float32)


def align(inp, exp):
    """La original a veces viene girada (sin aplicar la orientación EXIF): se elige el giro que más se parece."""
    best, best_c = inp, -2
    for k in (0, 1, 3, 2):
        r = np.rot90(inp, k)
        if abs(r.shape[0] / r.shape[1] - exp.shape[0] / exp.shape[1]) > 0.05:
            continue
        a = np.asarray(Image.fromarray(r.astype(np.uint8)).resize((64, 64))).mean(2).ravel()
        b = np.asarray(Image.fromarray(exp.astype(np.uint8)).resize((64, 64))).mean(2).ravel()
        c = np.corrcoef(a, b)[0, 1]
        if c > best_c:
            best, best_c = r, c
    img = Image.fromarray(best.astype(np.uint8)).resize((exp.shape[1], exp.shape[0]), Image.LANCZOS)
    return np.asarray(img).astype(np.float32), best_c


def blur(x, r):
    k = np.ones(2 * r + 1) / (2 * r + 1)
    y = np.apply_along_axis(lambda v: np.convolve(np.pad(v, r, mode="edge"), k, "valid"), 0, x)
    return np.apply_along_axis(lambda v: np.convolve(np.pad(v, r, mode="edge"), k, "valid"), 1, y)


QS = np.linspace(0.01, 0.99, 33)


def stats(img):
    lin = srgb_to_lin(img)
    Y = lin @ np.array([0.2126, 0.7152, 0.0722])
    L, a, b = lin_to_lab(lin)
    C = np.sqrt(a * a + b * b)
    logY = np.log2(np.maximum(Y, 1e-4))
    small = logY[::2, ::2]
    local = small - blur(small, 6)
    return {
        "key": float(np.mean(logY)),  # pasos (log2) de la luz media geométrica
        "p99": float(np.percentile(Y, 99)),
        "p01": float(np.percentile(Y, 1)),
        "clipped": float(np.mean(img.max(2) >= 250)),
        "crushed": float(np.mean(img.mean(2) <= 3)),
        "chroma50": float(np.percentile(C, 50)),
        "chroma95": float(np.percentile(C, 95)),
        "contrast": float(np.std(logY)),
        "local": float(np.std(local)),
        "q": np.quantile((img @ np.array([0.2126, 0.7152, 0.0722])) / 255.0, QS).tolist(),
    }


def category(m):
    if m["time"] == "night":
        return "NIGHT"
    if m["time"] == "dusk" and m["location"] != "indoor":
        return "SUNSET"
    if m["location"] == "indoor":
        return "INDOOR"
    if m["subject"] == "people":
        return "PORTRAIT"
    if m["subject"] == "nature":
        return "LANDSCAPE"
    if m["subject"] == "man_made":
        return "CITY"
    return "GENERAL"


def main(folder, out):
    meta = {}
    for f in glob.glob(os.path.join(folder, "*_meta.json")):
        for m in json.load(open(f)):
            meta[m["id"]] = m
    rows = []
    for rid, m in sorted(meta.items()):
        pi, pe = os.path.join(folder, rid + "_in.png"), os.path.join(folder, rid + "_exp.png")
        if not (os.path.exists(pi) and os.path.exists(pe)):
            continue
        exp = load(pe)
        inp, corr = align(load(pi), exp)
        if corr < 0.6:
            continue  # no se pudo emparejar con seguridad
        si, se = stats(inp), stats(exp)
        rows.append({"id": rid, "cat": category(m), "in": si, "exp": se, "ev": se["key"] - si["key"]})
    print(f"parejas válidas: {len(rows)}")
    res = {"n": len(rows), "categories": {}}
    cats = sorted(set(r["cat"] for r in rows)) + ["ALL"]
    for c in cats:
        rs = [r for r in rows if c == "ALL" or r["cat"] == c]
        if len(rs) < 3:
            continue

        def med(side, k):
            return float(np.median([r[side][k] for r in rs]))

        def pct(side, k, p):
            return float(np.percentile([r[side][k] for r in rs], p))

        curve = np.median([r["exp"]["q"] for r in rs], axis=0).tolist()
        curve_in = np.median([r["in"]["q"] for r in rs], axis=0).tolist()
        res["categories"][c] = {
            "n": len(rs),
            "ev_median": float(np.median([r["ev"] for r in rs])),
            "ev_p25": float(np.percentile([r["ev"] for r in rs], 25)),
            "ev_p75": float(np.percentile([r["ev"] for r in rs], 75)),
            "exp_key": med("exp", "key"),
            "exp_p99_median": med("exp", "p99"),
            "exp_p99_p90": pct("exp", "p99", 90),
            "exp_clipped_p90": pct("exp", "clipped", 90),
            "exp_crushed_p90": pct("exp", "crushed", 90),
            "exp_chroma50": med("exp", "chroma50"),
            "exp_chroma95_median": med("exp", "chroma95"),
            "exp_chroma95_p90": pct("exp", "chroma95", 90),
            "in_chroma95_median": med("in", "chroma95"),
            "chroma_gain_median": float(np.median([r["exp"]["chroma95"] / max(r["in"]["chroma95"], 1e-3) for r in rs])),
            "exp_contrast": med("exp", "contrast"),
            "exp_local": med("exp", "local"),
            "in_local": med("in", "local"),
            "tone_in": curve_in,
            "tone_exp": curve,
        }
        k = res["categories"][c]
        print(f"{c:9s} n={len(rs):3d}  EV {k['ev_median']:+.2f} [{k['ev_p25']:+.2f},{k['ev_p75']:+.2f}]  "
              f"p99 {k['exp_p99_median']:.2f}  quemado p90 {k['exp_clipped_p90']*100:.1f}%  "
              f"croma95 {k['exp_chroma95_median']:.0f} (p90 {k['exp_chroma95_p90']:.0f}, original {k['in_chroma95_median']:.0f})  "
              f"contraste local {k['in_local']:.2f}→{k['exp_local']:.2f}")
    res["rows"] = [{"id": r["id"], "cat": r["cat"], "ev": r["ev"]} for r in rows]
    json.dump(res, open(out, "w"), indent=1)


if __name__ == "__main__" and len(sys.argv) == 3:
    main(sys.argv[1], sys.argv[2])


def kotlin_constants(res_path):
    """Imprime los valores para Calibration.kt a partir del JSON de estadísticas."""
    res = json.load(open(res_path))
    cats = res["categories"]
    print("// Croma (CIELAB) del experto por escena: rodilla = mediana del p95, techo = p90 del p95 (+ margen)")
    for c, k in cats.items():
        knee = k["exp_chroma95_median"]
        ceil = max(k["exp_chroma95_p90"] + 4, knee + 10)
        print(f'        "{c}" to NaturalProfile.Limits({knee:.1f}f, {ceil:.1f}f),  // n={k["n"]}')
    # Curva de tono: luminancia de la original → la del experto, sin el cambio de exposición
    # (la exposición la decide el cerebro): se desplaza para que el gris medio quede igual.
    k = cats["ALL"]
    xin, xout = np.array(k["tone_in"]), np.array(k["tone_exp"])
    mid = 0.46
    out_mid = np.interp(mid, xin, xout)
    # Desplazamiento en exposición equivalente (en luz lineal) para conservar el gris medio.
    lin = lambda v: np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4)
    enc = lambda v: np.where(v <= 0.0031308, v * 12.92, 1.055 * np.power(np.maximum(v, 0), 1 / 2.4) - 0.055)
    gain = float(lin(mid) / lin(out_mid))
    xs = np.concatenate([[0.0], xin, [1.0]])
    ys = np.concatenate([[0.0], xout, [max(xout[-1], 0.97)]])
    ys = enc(np.minimum(lin(ys) * gain, 1.0))
    # Hombro: más allá del último cuantil la curva llega a 1 suavemente.
    ys = np.maximum.accumulate(ys)
    print(f"// Curva de tono (ALL, n={k['n']}), ganancia para conservar el gris medio ×{gain:.2f}")
    print("        val TONE_IN = floatArrayOf(" + ", ".join(f"{v:.3f}f" for v in xs) + ")")
    print("        val TONE_OUT = floatArrayOf(" + ", ".join(f"{v:.3f}f" for v in ys) + ")")
    print("// EV del experto respecto a la toma original, por escena")
    for c, k in cats.items():
        print(f'        "{c}" to {k["ev_median"]:.2f}f,  // [{k["ev_p25"]:.2f}, {k["ev_p75"]:.2f}] n={k["n"]}')


if __name__ == "__main__" and len(sys.argv) == 2:
    kotlin_constants(sys.argv[1])
