"""
Ajusta los pesos y umbrales de la calificación de la foto (PhotoScore) con KonIQ-10k: miles de
fotos reales con la nota media que les dieron cientos de personas.

Entrada: koniq_features.csv (medidas hechas con el código de la app, ScoreFeaturesTest).
Se ajusta con las fotos de entrenamiento y se mide con las de prueba (no usadas para ajustar):
correlación de orden (Spearman) entre la nota de la app y la nota humana.

Uso: python3 tools/calibracion/ajustar_nota.py <koniq_features.csv>
"""
import csv
import sys

import numpy as np
from scipy.optimize import minimize
from scipy.stats import pearsonr, spearmanr

NAMES = ["Ws", "Ts", "Rs", "Wn", "Tn", "Rn", "Wc", "Tc", "Rc", "Wk", "Tk", "Rk", "Wd", "Td", "Rd", "Wb", "Tb", "Rb", "Wt"]
# Valores actuales de la app (antes de calibrar).
START = [35, 0.38, 0.20, 15, 3.0, 7.0, 20, 0.03, 0.22, 12, 0.10, 0.30, 18, 0.18, 0.12, 12, 0.78, 0.15, 10]


def score(p, X):
    Ws, Ts, Rs, Wn, Tn, Rn, Wc, Tc, Rc, Wk, Tk, Rk, Wd, Td, Rd, Wb, Tb, Rb, Wt = p
    s, n, c, k, m, t = X.T
    cl = lambda v: np.clip(v, 0, 1)
    pen = Ws * cl((Ts - s) / max(Rs, 1e-3)) + Wn * cl((n - Tn) / max(Rn, 1e-3))
    exp = Wc * cl((c - Tc) / max(Rc, 1e-3)) + Wk * cl((k - Tk) / max(Rk, 1e-3)) + Wd * cl((Td - m) / max(Rd, 1e-3)) + Wb * cl((m - Tb) / max(Rb, 1e-3))
    pen = pen + np.minimum(exp, 30)
    tilt = np.where(np.isnan(t), 0, Wt * cl((np.abs(np.nan_to_num(t)) - 0.8) / 3.2))
    return np.clip(100 - pen - tilt, 0, 100)


def load(path):
    rows = list(csv.DictReader(open(path)))
    X = np.array([[float(r["sharp"]), float(r["noise"]), float(r["clipped"]), float(r["crushed"]), float(r["mean"]),
                   float(r["tilt"]) if r["tilt"] != "nan" else np.nan] for r in rows])
    y = np.array([float(r["mos"]) for r in rows])
    st = np.array([r["set"] for r in rows])
    return X, y, st


def main(path):
    X, y, st = load(path)
    tr, te = st == "training", st == "test"
    print(f"fotos: {tr.sum()} de ajuste · {te.sum()} de prueba")
    # Las relaciones con la nota humana, una por una:
    for i, n in enumerate(["nitidez", "ruido", "quemado", "negro", "brillo medio"]):
        print(f"  correlación {n}: {spearmanr(X[tr, i], y[tr]).correlation:+.3f}")

    def loss(p):
        p = np.abs(p)
        sc = score(p, X[tr])
        if np.std(sc) < 1e-6:
            return 1.0
        return -pearsonr(sc, y[tr])[0]

    best = None
    for seed in range(6):
        rng = np.random.default_rng(seed)
        x0 = np.array(START) * (1 + (rng.random(len(START)) - 0.5) * (0.6 if seed else 0))
        r = minimize(loss, x0, method="Nelder-Mead", options={"maxiter": 6000, "xatol": 1e-4, "fatol": 1e-6})
        if best is None or r.fun < best.fun:
            best = r
    p = np.abs(best.x)
    before = spearmanr(score(START, X[te]), y[te]).correlation
    after = spearmanr(score(p, X[te]), y[te]).correlation
    pb = pearsonr(score(START, X[te]), y[te])[0]
    pa = pearsonr(score(p, X[te]), y[te])[0]
    print(f"PRUEBA  Spearman {before:.3f} → {after:.3f} · Pearson {pb:.3f} → {pa:.3f}")
    for n, v in zip(NAMES, p):
        print(f"  {n} = {v:.4f}")


if __name__ == "__main__":
    main(sys.argv[1])
