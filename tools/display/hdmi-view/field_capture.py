#!/usr/bin/env python3
"""Bounded source-5 field capture over the tested HDMI1 session owner."""
import argparse, hashlib, json, os, shlex, socket, struct, subprocess, sys, time, uuid
from pathlib import Path
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent
ASSETS = ROOT / 'assets-hdmi1-field'
ADB = os.environ.get("ADB", "adb")
REMOTE = '/data/local/tests'

def adb(serial, *args):
    r = subprocess.run([ADB, '-s', serial, *map(str, args)], capture_output=True, text=True)
    if r.returncode:
        raise RuntimeError('adb ' + ' '.join(map(str, args)) + ': ' + (r.stderr or r.stdout).strip())
    return r.stdout

def recv_exact(sock, n):
    out = bytearray()
    while len(out) < n:
        chunk = sock.recv(n - len(out))
        if not chunk:
            raise EOFError('closed after %d of %d bytes' % (len(out), n))
        out += chunk
    return bytes(out)

def nv12_to_rgb(payload, w, h, stride):
    y = np.frombuffer(payload[:stride*h], np.uint8).reshape(h, stride)[:, :w].astype(np.float32)
    uv = np.frombuffer(payload[stride*h:stride*h + stride*h//2], np.uint8).reshape(h//2, stride//2, 2)[:, :w//2]
    u = uv[:, :, 0].astype(np.float32) - 128.0
    v = uv[:, :, 1].astype(np.float32) - 128.0
    u = np.repeat(np.repeat(u, 2, 0), 2, 1)
    v = np.repeat(np.repeat(v, 2, 0), 2, 1)
    y = (y - 16.0) * 1.164
    r = y + 1.596 * v
    g = y - 0.391 * u - 0.813 * v
    b = y + 2.018 * u
    return np.clip(np.stack([r, g, b], -1), 0, 255).astype(np.uint8)

def weave(top, bottom, w, stride, h):
    th = np.frombuffer(top[:stride*h], np.uint8).reshape(h, stride)[:, :w]
    bh = np.frombuffer(bottom[:stride*h], np.uint8).reshape(h, stride)[:, :w]
    y = np.empty((h*2, w), np.uint8)
    y[0::2] = th
    y[1::2] = bh
    tu = np.frombuffer(top[stride*h:], np.uint8).reshape(h//2, stride//2, 2)[:, :w//2]
    bu = np.frombuffer(bottom[stride*h:], np.uint8).reshape(h//2, stride//2, 2)[:, :w//2]
    uv = ((tu.astype(np.uint16) + bu.astype(np.uint16)) // 2).astype(np.uint8)
    uv = np.repeat(uv, 2, axis=0)
    raw = y.tobytes() + uv.tobytes()
    return nv12_to_rgb(raw, w, h*2, w), y, uv

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--serial', required=True)
    p.add_argument('--seconds', type=int, default=6)
    p.add_argument('--out', required=True)
    p.add_argument('--port', type=int, default=27776)
    a = p.parse_args()
    out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    names = sorted(x.name for x in ASSETS.iterdir() if x.is_file() and x.name != 'SHA256SUMS')
    hashes = {n: hashlib.sha256((ASSETS / n).read_bytes()).hexdigest() for n in names}
    (ASSETS / 'SHA256SUMS').write_text(''.join(f'{hashes[n]}  {n}\n' for n in names), newline='\n')
    token = uuid.uuid4().hex[:8]
    remote = f'{REMOTE}/x90-field-{token}'
    adb(a.serial, 'shell', 'mkdir -p ' + shlex.quote(remote))
    for n in names + ['SHA256SUMS']:
        adb(a.serial, 'push', str(ASSETS / n), remote + '/' + n)
    local = int(adb(a.serial, 'forward', 'tcp:0', 'tcp:' + str(a.port)).strip())
    command = ('(sh ' + shlex.quote(remote + '/run-field-session.sh') + ' ' + ' '.join(map(shlex.quote, [remote, str(a.seconds), str(a.port)])) +
               '; r=$?; printf "\\nX90_FIELD_DONE=%s\\n" "$r") > ' + shlex.quote(remote + '/session.txt') + ' 2>&1; chmod 644 ' + shlex.quote(remote + '/session.txt'))
    adb(a.serial, 'shell', "printf '%s' " + shlex.quote(command) + ' > /data/local/tests/x90cmd.tmp; mv /data/local/tests/x90cmd.tmp /data/local/tests/x90cmd')
    sock = None
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        s = socket.socket(); s.settimeout(2)
        try:
            s.connect(('127.0.0.1', local))
            s.settimeout(6)
            header = recv_exact(s, 32)
            sock = s; break
        except (OSError, EOFError):
            s.close(); time.sleep(.2)
    if sock is None:
        try:
            log = adb(a.serial, 'shell', 'cat ' + shlex.quote(remote + '/session.txt'))
        except Exception as e:
            log = str(e)
        raise SystemExit('capture did not start\n' + log[-2000:])
    magic, version, w, h, stride, _, source = struct.unpack('<8s6I', header)
    if magic != b'X90FLD1\x00' or version != 1 or source != 5 or w != 720 or h != 240 or stride != 720:
        raise SystemExit(f'bad header {magic!r} v{version} {w}x{h} s{stride} src{source}')
    expected = stride * h * 3 // 2
    raw_path = out / 'fields.x90fld'
    meta = []
    with raw_path.open('wb') as f:
        f.write(struct.pack('<8s6I', b'X90FLD1\x00', 1, w, h, stride, 0, 5))
        field = 0; first = None; top = None; bottom = None; woven = False
        begin = time.monotonic(); last_ns = 0; gaps = []
        try:
            while True:
                seq, stamp, ring, count, copied = struct.unpack('<QQIIQ', recv_exact(sock, 32))
                if count != expected:
                    raise SystemExit(f'field {seq} size {count} != {expected}')
                payload = recv_exact(sock, count)
                f.write(struct.pack('<QQIIQ', seq, stamp, ring, count, copied)); f.write(payload)
                parity = (ring >> 8) & 1
                stable = (ring >> 9) & 1
                meta.append({'seq': seq, 'ns': stamp, 'ring': ring, 'top': parity, 'stable': stable,
                             'sha256': hashlib.sha256(payload).hexdigest()})
                if last_ns and stamp > last_ns:
                    d = stamp - last_ns
                    if d > 25_000_000: gaps.append([field, d])
                last_ns = stamp; field += 1
                if not woven:
                    if top is None and parity: top = payload
                    elif top is not None and not parity: bottom = payload
                    if top is not None and bottom is not None:
                        img, y, uv = weave(top, bottom, w, stride, h)
                        Image.fromarray(img).save(out / 'woven-720x480.png')
                        np.save(out / 'woven-y.npy', y)
                        woven = True
                if field == 1:
                    Image.fromarray(nv12_to_rgb(payload, w, h, stride)).save(out / 'first-field.png')
        except (EOFError, OSError):
            pass
        f.seek(4); f.write(struct.pack('<I', field))
    elapsed = (last_ns - meta[0]['ns']) / 1e9 if len(meta) > 1 else 0
    summary = {'fields': len(meta), 'seconds': round(elapsed, 4),
               'fps': round((len(meta)-1)/elapsed, 4) if elapsed > 0 else 0,
               'width': w, 'height': h, 'stride': stride, 'bytes': raw_path.stat().st_size,
               'top': sum(1 for m in meta if m['top']), 'bottom': sum(1 for m in meta if not m['top']),
               'stable': sum(1 for m in meta if m['stable']), 'gaps_over_25ms': gaps[:20],
               'duplicate_hashes': len(meta) - len({m['sha256'] for m in meta}),
               'session': None}
    sid = time.monotonic() + 40
    while time.monotonic() < sid:
        try:
            log = adb(a.serial, 'shell', 'cat ' + shlex.quote(remote + '/session.txt'))
            if 'FIELD_SESSION_FINISHED' in log or 'X90_FIELD_DONE=' in log:
                summary['session'] = log[-4000:]
                break
        except Exception:
            pass
        time.sleep(1)
    (out / 'summary.json').write_text(json.dumps(summary, indent=2))
    (out / 'fields.json').write_text(json.dumps(meta))
    print(json.dumps({k: v for k, v in summary.items() if k != 'session'}, indent=2))
    print(log[-1500:] if summary['session'] is None else summary['session'][-1500:])

if __name__ == '__main__':
    main()
