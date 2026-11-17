"""x90shot_img.py - assemble / validate x90shot captures (2026-11-16).

  python x90shot_img.py assemble META.json OUTPREFIX
      -> OUTPREFIX.npy (uint16 RGB12, H x W x 3), OUTPREFIX-16.png (16-bit RGB),
         OUTPREFIX.png (8-bit view, value >> 4)
  python x90shot_img.py validate META.json
      -> compares every captured interior pixel with the X90ShotPattern grid

META.json = {"step": s, "tiles": [{"bin": path, "x0":..,"y0":..,"w":..,"h":..,
             "step": s, "pair": 0|1}, ...]}
Pixel layout of a tile .bin: uint16 LE, row-major, 3 channels (R, G, B), 12-bit.
pair=0: out col i -> native X = x0 + i*step.  pair=1: first half of the columns
is X = x0 + i*step, second half is the same + 1920.  Row r -> Y = y0 + r*step.
Samples are taken across many refreshes: valid for static content only."""
import json
import struct
import sys
import zlib

import numpy as np


def tile_coords(t):
    s = t['step']
    wo = (t['w'] + s - 1) // s
    oh = (t['h'] + s - 1) // s
    if t['pair']:
        xs = [t['x0'] + (i % wo) * s + (1920 if i >= wo else 0) for i in range(2 * wo)]
    else:
        xs = [t['x0'] + i * s for i in range(wo)]
    ys = [t['y0'] + r * s for r in range(oh)]
    a = np.fromfile(t['bin'], dtype='<u2')
    if a.size != len(xs) * len(ys) * 3:
        sys.exit('size mismatch %s: %d vs %d' % (t['bin'], a.size, len(xs) * len(ys) * 3))
    return np.array(xs), np.array(ys), a.reshape(len(ys), len(xs), 3)


def assemble(meta):
    s = meta['step']
    parts = [tile_coords(t) for t in meta['tiles']]
    xmin = min(p[0].min() for p in parts)
    ymin = min(p[1].min() for p in parts)
    xmax = max(p[0].max() for p in parts)
    ymax = max(p[1].max() for p in parts)
    W, H = (xmax - xmin) // s + 1, (ymax - ymin) // s + 1
    img = np.zeros((H, W, 3), np.uint16)
    got = np.zeros((H, W), bool)
    for xs, ys, a in parts:
        ci = (xs - xmin) // s
        ri = (ys - ymin) // s
        img[np.ix_(ri, ci)] = a
        got[np.ix_(ri, ci)] = True
    return img, got, int(xmin), int(ymin)


def write_png16(path, img12):
    h, w, _ = img12.shape
    v = (img12.astype(np.uint32) << 4 | img12.astype(np.uint32) >> 8).astype('>u2')
    raw = b''.join(b'\x00' + v[y].tobytes() for y in range(h))

    def chunk(t, d):
        c = struct.pack('>I', len(d)) + t + d
        return c + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 16, 2, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(raw, 6)) + chunk(b'IEND', b'')
    open(path, 'wb').write(png)


def expected_bits(X, Y):
    cx = (X // 16).astype(np.int64)
    cy = (Y // 16).astype(np.int64)
    hsh = ((cx * 73856093) ^ (cy * 19349663) ^ 0x1b873593) & 0xffffffff
    hsh = hsh.astype(np.int64)
    hsh ^= (hsh & 0xffffffff) >> 13
    return hsh & 7


def still(outdir, thr=100):
    """Compare probe-pre / probe-post (sparse captures before and after the main
    capture). Writes still-mask.npy (True = changed during capture) at the main
    capture's resolution, still.png and still.json."""
    import os
    meta = json.load(open(os.path.join(outdir, 'meta.json')))
    img, got, x0, y0 = assemble(meta)
    s = meta['step']
    pre = json.load(open(os.path.join(outdir, 'probe-pre', 'meta.json')))
    post = json.load(open(os.path.join(outdir, 'probe-post', 'meta.json')))
    a, _, px0, py0 = assemble(pre)
    b, _, _, _ = assemble(post)
    ps = pre['step']
    changed = np.abs(a.astype(int) - b.astype(int)).max(2) > thr
    # dilate by one probe cell so changes between probe points are covered
    d = changed.copy()
    d[1:, :] |= changed[:-1, :]
    d[:-1, :] |= changed[1:, :]
    d[:, 1:] |= changed[:, :-1]
    d[:, :-1] |= changed[:, 1:]
    H, W = img.shape[:2]
    X = x0 + np.arange(W) * s
    Y = y0 + np.arange(H) * s
    ci = np.clip(np.rint((X - px0) / ps).astype(int), 0, d.shape[1] - 1)
    ri = np.clip(np.rint((Y - py0) / ps).astype(int), 0, d.shape[0] - 1)
    mask = d[np.ix_(ri, ci)]
    np.save(os.path.join(outdir, 'still-mask.npy'), mask)
    view = (img >> 4).astype(np.uint8)
    view[mask] = (view[mask] * 0.35 + np.array([255, 0, 160]) * 0.65).astype(np.uint8)
    from PIL import Image
    Image.fromarray(view, 'RGB').save(os.path.join(outdir, 'still.png'))
    rep = {'changed_fraction': float(mask.mean()), 'probe_points_changed': int(changed.sum()),
           'probe_points': int(changed.size), 'threshold_rgb12': thr}
    json.dump(rep, open(os.path.join(outdir, 'still.json'), 'w'))
    print('STILL %.2f%% of the capture changed during the capture (%d/%d probe points); masked in magenta in still.png' % (
        100 * rep['changed_fraction'], rep['probe_points_changed'], rep['probe_points']))


def main():
    cmd, mf = sys.argv[1], sys.argv[2]
    if cmd == 'still':
        still(mf)
        return
    meta = json.load(open(mf))
    img, got, x0, y0 = assemble(meta)
    s = meta['step']
    if cmd == 'assemble':
        out = sys.argv[3]
        np.save(out + '.npy', img)
        write_png16(out + '-16.png', img)
        from PIL import Image
        Image.fromarray((img >> 4).astype(np.uint8), 'RGB').save(out + '.png')
        print('ASSEMBLED %dx%d (native origin %d,%d step %d, filled %.2f%%) -> %s.png' %
              (img.shape[1], img.shape[0], x0, y0, s, 100.0 * got.mean(), out))
    elif cmd == 'validate':
        H, W, _ = img.shape
        X = x0 + np.arange(W)[None, :] * s + np.zeros((H, 1), int)
        Y = y0 + np.arange(H)[:, None] * s + np.zeros((1, W), int)
        act = (img[..., 0] > 2048).astype(int) | (img[..., 1] > 2048) << 1 | (img[..., 2] > 2048) << 2
        exp = expected_bits(X, Y)
        interior = got & (X % 16 >= 2) & (X % 16 <= 13) & (Y % 16 >= 2) & (Y % 16 <= 13)
        edge = got & ~interior
        ok_i = (act == exp) & interior
        ok_e = (act == exp) & edge
        print('VALIDATE interior %d/%d exact (%.4f%%), edge %d/%d (%.2f%%, scaler blend expected)' % (
            ok_i.sum(), interior.sum(), 100.0 * ok_i.sum() / max(1, interior.sum()),
            ok_e.sum(), edge.sum(), 100.0 * ok_e.sum() / max(1, edge.sum())))
        bad = np.argwhere(interior & (act != exp))
        for r, c in bad[:10]:
            print('  bad X=%d Y=%d rgb=%s expected_bits=%d' % (X[r, c], Y[r, c], img[r, c].tolist(), exp[r, c]))
        hi = img[interior & (exp == 7)]
        if hi.size:
            print('  white-cell RGB12 mean %s (12-bit full scale 4095)' % hi.mean(0).round(1).tolist())
        sys.exit(0 if ok_i.sum() == interior.sum() else 3)


if __name__ == '__main__':
    main()
