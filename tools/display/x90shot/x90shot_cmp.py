"""x90shot_cmp.py - compare two x90shot captures of the same static frame (2026-11-16).

  python x90shot_cmp.py A_DIR B_DIR OUTDIR [--labels Sony Engine]

A_DIR / B_DIR are x90shot output folders (meta.json + NAME.npy, optional still-mask.npy).
Metrics are alignment-free (Sony applies a small zoom, so pixels do not overlay):
  bounds      first/last content row and column (native coords) -> zoom/position
  sharpness   mean luma gradient, high-frequency energy share
  grid        spectral peak at the PS2 source pixel pitch (stair-step visibility)
  tone        per-channel quantiles, and a fitted B->A per-channel curve
              A ~= lift + gain * B^gamma (normalised 0..1) for the engine colour fix
Writes compare.json, compare.txt, sbs.png (A | B, 8-bit) to OUTDIR.
Changed areas (from still-mask.npy, written by `x90shot_img.py still`) are excluded."""
import json
import os
import sys

import numpy as np


def load(d):
    meta = json.load(open(os.path.join(d, 'meta.json')))
    img = np.load(os.path.join(d, meta['name'] + '.npy')).astype(np.float64) / 4095.0
    t = meta['tiles'][0]
    x0 = min(tt['x0'] for tt in meta['tiles'])
    y0 = min(tt['y0'] for tt in meta['tiles'])
    mask = np.ones(img.shape[:2], bool)
    mp = os.path.join(d, 'still-mask.npy')
    if os.path.exists(mp):
        m = np.load(mp)
        if m.shape == mask.shape:
            mask = ~m          # still-mask marks CHANGED pixels
    return meta, img, int(x0), int(y0), meta['step'], mask


def luma(img):
    return img[..., 0] * 0.2126 + img[..., 1] * 0.7152 + img[..., 2] * 0.0722


def bounds(y, x0, y0, s, thr=0.04):
    content = y > thr
    rows = np.where(content.mean(1) > 0.02)[0]
    cols = np.where(content.mean(0) > 0.02)[0]
    if rows.size == 0 or cols.size == 0:
        return None
    return {'top': int(y0 + rows[0] * s), 'bottom': int(y0 + rows[-1] * s),
            'left': int(x0 + cols[0] * s), 'right': int(x0 + cols[-1] * s)}


def sharpness(y, mask):
    gx = np.zeros_like(y)
    gy = np.zeros_like(y)
    gx[:, 1:-1] = (y[:, 2:] - y[:, :-2]) / 2
    gy[1:-1, :] = (y[2:, :] - y[:-2, :]) / 2
    g = np.hypot(gx, gy)
    m = mask & (y > 0.04)
    m[:2, :] = m[-2:, :] = False
    m[:, :2] = m[:, -2:] = False
    lap = np.zeros_like(y)
    lap[1:-1, 1:-1] = 4 * y[1:-1, 1:-1] - y[:-2, 1:-1] - y[2:, 1:-1] - y[1:-1, :-2] - y[1:-1, 2:]
    return {'mean_gradient': float(g[m].mean()) if m.any() else 0.0,
            'p95_gradient': float(np.percentile(g[m], 95)) if m.any() else 0.0,
            'laplacian_energy': float((lap[m] ** 2).mean()) if m.any() else 0.0}


def hf_share(y, mask, s):
    """Share of spectral power above 0.15 cycles/native px (in the content box)."""
    m = mask & (y > 0.04)
    rows = np.where(m.mean(1) > 0.5)[0]
    cols = np.where(m.mean(0) > 0.5)[0]
    if rows.size < 32 or cols.size < 32:
        return None
    sub = y[rows[0]:rows[-1] + 1, cols[0]:cols[-1] + 1]
    sub = sub - sub.mean()
    win = np.outer(np.hanning(sub.shape[0]), np.hanning(sub.shape[1]))
    P = np.abs(np.fft.rfft2(sub * win)) ** 2
    fy = np.abs(np.fft.fftfreq(sub.shape[0]))[:, None] / s
    fx = np.fft.rfftfreq(sub.shape[1])[None, :] / s
    f = np.hypot(fx, fy)
    tot = P[f > 0.002].sum()
    return float(P[f > 0.15].sum() / tot) if tot > 0 else None


def grid_peak(y, mask, s, axis, lo, hi):
    """Peak-to-background ratio of the gradient spectrum at the source pixel pitch.
    axis=1: horizontal pitch (~4.0 native px), axis=0: vertical (~4.5)."""
    m = mask & (y > 0.04)
    g = np.abs(np.diff(y, axis=axis))
    mm = m[:, 1:] & m[:, :-1] if axis == 1 else m[1:, :] & m[:-1, :]
    g = np.where(mm, g, 0)
    prof = g.mean(0) if axis == 1 else g.mean(1)
    prof = prof - prof.mean()
    if prof.size < 64:
        return None
    P = np.abs(np.fft.rfft(prof * np.hanning(prof.size))) ** 2
    f = np.fft.rfftfreq(prof.size) / s
    band = (f > 1 / hi) & (f < 1 / lo)
    bg = (f > 0.08) & (f < 0.45) & ~band
    if not band.any() or not bg.any():
        return None
    i = np.argmax(np.where(band, P, 0))
    return {'ratio': float(P[i] / np.median(P[bg])), 'pitch_px': float(1 / f[i])}


def quantiles(img, mask):
    m = mask & (luma(img) > 0.02)
    qs = [1, 5, 10, 25, 50, 75, 90, 95, 99]
    return {c: [float(np.percentile(img[..., i][m], q)) for q in qs] for i, c in enumerate('RGB')}, qs


def fit_curve(a_q, b_q):
    best = None
    a = np.array(a_q)
    b = np.clip(np.array(b_q), 1e-4, 1)
    for gamma in np.linspace(0.5, 2.0, 151):
        X = np.stack([np.ones_like(b), b ** gamma], 1)
        coef, res, *_ = np.linalg.lstsq(X, a, rcond=None)
        err = float(np.sqrt(np.mean((X @ coef - a) ** 2)))
        if best is None or err < best[0]:
            best = (err, float(coef[0]), float(coef[1]), float(gamma))
    return {'lift': best[1], 'gain': best[2], 'gamma': best[3], 'rms_err': best[0]}


def main():
    a_dir, b_dir, out = sys.argv[1:4]
    labels = ['A', 'B']
    if '--labels' in sys.argv:
        i = sys.argv.index('--labels')
        labels = sys.argv[i + 1:i + 3]
    os.makedirs(out, exist_ok=True)
    A = load(a_dir)
    B = load(b_dir)
    res = {'labels': labels, 'a': a_dir, 'b': b_dir, 'step': A[4]}
    # Same region and grid: judge both on the SAME pixels (still in both captures);
    # otherwise each capture's own still area would bias means/sharpness.
    if A[1].shape == B[1].shape and (A[2], A[3], A[4]) == (B[2], B[3], B[4]):
        common = A[5] & B[5]
        A = A[:5] + (common,)
        B = B[:5] + (common,)
        res['mask'] = 'common still area %.1f%%' % (100 * common.mean())
    else:
        res['mask'] = 'per-capture still areas (different regions)'
    for lab, (meta, img, x0, y0, s, mask) in zip(labels, (A, B)):
        y = luma(img)
        q, qs = quantiles(img, mask)
        res[lab] = {'bounds': bounds(y, x0, y0, s), 'sharpness': sharpness(y, mask),
                    'hf_share': hf_share(y, mask, s),
                    'grid_h': grid_peak(y, mask, s, 1, 3.6, 4.6),
                    'grid_v': grid_peak(y, mask, s, 0, 4.0, 5.2),
                    'mean_rgb': [float(img[..., i][mask & (y > 0.02)].mean()) for i in range(3)],
                    'still_fraction': float(mask.mean()), 'quantiles': q}
    res['quantile_levels'] = qs
    res['fit_B_to_A'] = {c: fit_curve(res[labels[0]]['quantiles'][c], res[labels[1]]['quantiles'][c]) for c in 'RGB'}
    ba, bb = res[labels[0]]['bounds'], res[labels[1]]['bounds']
    if ba and bb:
        res['geometry'] = {
            'height_ratio_A_over_B': (ba['bottom'] - ba['top']) / max(1, bb['bottom'] - bb['top']),
            'width_ratio_A_over_B': (ba['right'] - ba['left']) / max(1, bb['right'] - bb['left']),
            'top_shift_px': ba['top'] - bb['top'], 'bottom_shift_px': ba['bottom'] - bb['bottom'],
            'left_shift_px': ba['left'] - bb['left'], 'right_shift_px': ba['right'] - bb['right']}
    json.dump(res, open(os.path.join(out, 'compare.json'), 'w'), indent=1)

    L = []
    L.append('x90shot compare: %s vs %s (step %d), %s' % (labels[0], labels[1], A[4], res['mask']))
    for lab in labels:
        r = res[lab]
        L.append('%-8s bounds %s' % (lab, r['bounds']))
        L.append('         sharpness mean_grad %.4f p95_grad %.4f lap %.6f hf_share %s' % (
            r['sharpness']['mean_gradient'], r['sharpness']['p95_gradient'], r['sharpness']['laplacian_energy'],
            None if r['hf_share'] is None else round(r['hf_share'], 4)))
        L.append('         pixel-grid peak  horizontal %s  vertical %s' % (r['grid_h'], r['grid_v']))
        L.append('         mean RGB %s  still %.1f%%' % ([round(v, 4) for v in r['mean_rgb']], 100 * r['still_fraction']))
    if 'geometry' in res:
        L.append('geometry %s' % {k: round(v, 4) if isinstance(v, float) else v for k, v in res['geometry'].items()})
    for c in 'RGB':
        f = res['fit_B_to_A'][c]
        L.append('tone fit %s: %s = %.3f + %.3f * %s^%.2f  (rms %.4f)' % (c, labels[0], f['lift'], f['gain'], labels[1], f['gamma'], f['rms_err']))
    txt = '\n'.join(L)
    open(os.path.join(out, 'compare.txt'), 'w').write(txt + '\n')
    print(txt)

    from PIL import Image
    a8 = (np.clip(A[1], 0, 1) * 255).astype(np.uint8)
    b8 = (np.clip(B[1], 0, 1) * 255).astype(np.uint8)
    h = min(a8.shape[0], b8.shape[0])
    gap = np.full((h, 8, 3), 255, np.uint8)
    Image.fromarray(np.concatenate([a8[:h], gap, b8[:h]], 1)).save(os.path.join(out, 'sbs.png'))


if __name__ == '__main__':
    main()
