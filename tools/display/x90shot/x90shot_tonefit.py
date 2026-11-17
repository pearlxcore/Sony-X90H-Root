"""x90shot_tonefit.py - pixel-paired tone fit between two GEOMETRY-MATCHED captures (2026-11-16).

  python x90shot_tonefit.py SONY_DIR ENGINE_DIR [--applied "R:l,g,y G:... B:..."] [--blur 9]

Both captures must cover the same native region with the same picture geometry (e.g. the
engine run with the Sony-matched zoom). Both images are box-blurred (tolerates small
misalignment and texture differences), pixels that changed during either capture or lie in
black borders are dropped, and for each channel the median Sony value is taken per engine
value bin. The binned curve is fitted with  sony = lift + gain * engine^gamma.
If --applied gives the curve that was ALREADY applied inside the engine for ENGINE_DIR, the
result is composed with it, so the printed curve is the TOTAL curve to apply to the engine's
untoned output (feed it to make_upsh.py --tone)."""
import argparse
import json
import os

import numpy as np


def load(d):
    meta = json.load(open(os.path.join(d, 'meta.json')))
    img = np.load(os.path.join(d, meta['name'] + '.npy')).astype(np.float64) / 4095.0
    mp = os.path.join(d, 'still-mask.npy')
    changed = np.load(mp) if os.path.exists(mp) else np.zeros(img.shape[:2], bool)
    return img, changed


def boxblur(a, k):
    c = np.cumsum(np.cumsum(np.pad(a, ((k // 2 + 1, k // 2), (k // 2 + 1, k // 2), (0, 0)), mode='edge'), 0), 1)
    return (c[k:, k:] - c[:-k, k:] - c[k:, :-k] + c[:-k, :-k]) / (k * k)


def fit(x, y):
    best = None
    for gamma in np.linspace(0.4, 2.5, 211):
        X = np.stack([np.ones_like(x), np.clip(x, 1e-4, 1) ** gamma], 1)
        coef, *_ = np.linalg.lstsq(X, y, rcond=None)
        err = float(np.sqrt(np.mean((X @ coef - y) ** 2)))
        if best is None or err < best[0]:
            best = (err, float(coef[0]), float(coef[1]), float(gamma))
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('sony')
    ap.add_argument('engine')
    ap.add_argument('--applied', default='')
    ap.add_argument('--blur', type=int, default=9)
    a = ap.parse_args()
    S, sc = load(a.sony)
    E, ec = load(a.engine)
    if S.shape != E.shape:
        raise SystemExit('shape mismatch %s vs %s' % (S.shape, E.shape))
    applied = {c: (0.0, 1.0, 1.0) for c in 'RGB'}
    for part in a.applied.split():
        c, v = part.split(':')
        applied[c] = tuple(float(x) for x in v.split(','))
    Sb, Eb = boxblur(S, a.blur), boxblur(E, a.blur)
    ok = ~sc & ~ec & (Sb.mean(2) > 0.02) & (Eb.mean(2) > 0.02)
    print('paired pixels used: %d (%.1f%% of region)' % (ok.sum(), 100 * ok.mean()))
    out = []
    for i, c in enumerate('RGB'):
        e, s = Eb[..., i][ok], Sb[..., i][ok]
        bins = np.linspace(0, 1, 65)
        idx = np.digitize(e, bins) - 1
        xs, ys, ns = [], [], []
        for b in range(64):
            m = idx == b
            if m.sum() >= 200:
                xs.append(np.median(e[m]))
                ys.append(np.median(s[m]))
                ns.append(m.sum())
        xs, ys = np.array(xs), np.array(ys)
        err, l, g, y = fit(xs, ys)
        # compose with the curve already inside the engine: total(u) = F(T(u))
        al, ag, ay = applied[c]
        u = np.linspace(0.02, 0.98, 200)
        tu = np.clip(al + ag * u ** ay, 0, 1)
        tot = np.clip(l + g * np.clip(tu, 1e-4, 1) ** y, 0, 1)
        terr, tl, tg, ty = fit(u, tot)
        out.append('%s:%.4f,%.4f,%.3f' % (c, tl, tg, ty))
        print('%s  residual fit  sony = %.4f + %.4f * engine^%.3f  (rms %.4f, %d bins, x %.3f..%.3f)' % (
            c, l, g, y, err, len(xs), xs.min(), xs.max()))
        print('   TOTAL curve   %.4f + %.4f * u^%.3f  (rms %.4f)' % (tl, tg, ty, terr))
    print('TONE_TOTAL "%s"' % ' '.join(out))


if __name__ == '__main__':
    main()
