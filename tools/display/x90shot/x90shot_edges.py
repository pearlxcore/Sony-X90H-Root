"""x90shot_edges.py - stair-step (jaggy) metrics for x90shot captures (2026-11-16).

  python x90shot_edges.py DIR [DIR ...] [--common]

For each capture: luma -> Sobel gradients -> structure tensor (5x5 box) -> coherence
c = (l1-l2)/(l1+l2) in [0,1]. On strong edges (gradient magnitude above the 90th
percentile of content pixels) we report:
  coherence_all   mean coherence on strong edges (1 = perfectly straight, consistent edges)
  coherence_diag  the same restricted to diagonal edges (orientation 20..70 deg off-axis),
                  where pixel staircases flip the local orientation between 0 and 90 deg
  axis_share_diag fraction of strong-edge pixels whose PIXEL-SCALE gradient is axis-aligned
                  (within 10 deg of 0/90) although the 5x5 orientation is diagonal -> stair steps
Higher coherence and lower axis_share_diag = smoother diagonals. With --common, all
captures are judged on the pixels that are still in every capture (same grid required)."""
import json
import os
import sys

import numpy as np


def load(d):
    meta = json.load(open(os.path.join(d, 'meta.json')))
    img = np.load(os.path.join(d, meta['name'] + '.npy')).astype(np.float64) / 4095.0
    mp = os.path.join(d, 'still-mask.npy')
    changed = np.load(mp) if os.path.exists(mp) else np.zeros(img.shape[:2], bool)
    return img, ~changed


def box(a, k):
    p = k // 2
    c = np.cumsum(np.cumsum(np.pad(a, ((p + 1, p), (p + 1, p)), mode='edge'), 0), 1)
    return (c[k:, k:] - c[:-k, k:] - c[k:, :-k] + c[:-k, :-k]) / (k * k)


def metrics(img, still):
    y = img[..., 0] * 0.2126 + img[..., 1] * 0.7152 + img[..., 2] * 0.0722
    pad = np.pad(y, 1, mode='edge')
    gx = (pad[:-2, 2:] + 2 * pad[1:-1, 2:] + pad[2:, 2:] - pad[:-2, :-2] - 2 * pad[1:-1, :-2] - pad[2:, :-2]) / 8
    gy = (pad[2:, :-2] + 2 * pad[2:, 1:-1] + pad[2:, 2:] - pad[:-2, :-2] - 2 * pad[:-2, 1:-1] - pad[:-2, 2:]) / 8
    jxx, jyy, jxy = box(gx * gx, 5), box(gy * gy, 5), box(gx * gy, 5)
    tr = jxx + jyy
    disc = np.sqrt((jxx - jyy) ** 2 + 4 * jxy ** 2)
    coh = np.where(tr > 1e-12, disc / np.maximum(tr, 1e-12), 0)
    ang5 = np.degrees(0.5 * np.arctan2(2 * jxy, jxx - jyy)) % 180       # dominant gradient orientation
    ang1 = np.degrees(np.arctan2(gy, gx)) % 180                          # pixel-scale orientation
    off5 = np.minimum(ang5 % 90, 90 - ang5 % 90)                         # 0 = axis, 45 = diagonal
    off1 = np.minimum(ang1 % 90, 90 - ang1 % 90)
    g = np.hypot(gx, gy)
    m = still & (y > 0.04)
    m[:3, :] = m[-3:, :] = m[:, :3] = m[:, -3:] = False
    if not m.any():
        return None
    strong = m & (g > np.percentile(g[m], 90))
    diag = strong & (off5 >= 20) & (off5 <= 45)
    return {'strong_px': int(strong.sum()), 'diag_px': int(diag.sum()),
            'coherence_all': float(coh[strong].mean()),
            'coherence_diag': float(coh[diag].mean()) if diag.any() else None,
            'axis_share_diag': float((off1[diag] < 10).mean()) if diag.any() else None}


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    caps = [(d, *load(d)) for d in args]
    if '--common' in sys.argv:
        shapes = {c[1].shape for c in caps}
        if len(shapes) != 1:
            raise SystemExit('--common needs identical grids')
        common = np.logical_and.reduce([c[2] for c in caps])
        caps = [(d, img, common) for d, img, _ in caps]
    for d, img, still in caps:
        r = metrics(img, still)
        print('%-70s %s' % (os.path.basename(os.path.dirname(d.rstrip('/\\'))) + '/' + os.path.basename(d.rstrip('/\\')),
                            {k: (round(v, 4) if isinstance(v, float) else v) for k, v in r.items()} if r else None))


if __name__ == '__main__':
    main()
