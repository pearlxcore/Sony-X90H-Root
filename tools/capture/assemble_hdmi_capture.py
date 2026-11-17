"""Assemble the bounded 2026-11-04 PS2 capture using recorded sample times.

Offline only: no ADB, hardware operations, upscaling or frame interpolation.
"""
import argparse
import hashlib
import json
import re
import statistics
import subprocess
from pathlib import Path


def run(*args):
    subprocess.run(args, check=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('folder', type=Path)
    args = ap.parse_args()
    folder = args.folder.resolve()
    text = (folder / 'sequence-retry-live-trial.txt').read_text()
    samples = [(int(a), int(b)) for a, b in re.findall(r'SAMPLE number=(\d+) monotonic_ns=(\d+)', text)]
    sizes = [(int(a), int(b)) for a, b in re.findall(r'FRAME guards=1 .*?width=(\d+) height=(\d+)', text)]
    if len(samples) != 60 or len(sizes) != 60 or len(set(sizes)) != 1:
        raise ValueError('Expected60 guarded frames with constant input dimensions')
    if [i for i, _ in samples] != list(range(60)):
        raise ValueError('Missing or reordered sample')
    width, height = sizes[0]
    stride = (width + 15) & ~15
    if not (0 < width <= stride <= 720 and 0 < height <= 480 and height % 2 == 0):
        raise ValueError('Unsupported image geometry')
    intervals = [(b[1] - a[1]) / 1e9 for a, b in zip(samples, samples[1:])]
    if any(t <= 0 or t > 1 for t in intervals):
        raise ValueError('Invalid recorded timing')
    work = folder / 'sequence-render'
    work.mkdir()  # Refuse overwrite of a previous render/evidence.
    packed = work / 'capture.nv12'
    hashes = []
    n = stride * height
    with packed.open('xb') as output:
        for index, _ in samples:
            data = (folder / f'ps2-sequence-20261104-{index:03d}.raw').read_bytes()
            if len(data) != 720 * 480 * 3 // 2:
                raise ValueError('Incomplete raw capture')
            plane = data[:n] + data[720 * 480:720 * 480 + n // 2]
            hashes.append(hashlib.sha256(plane).hexdigest())
            output.write(plane)
    run('ffmpeg', '-v', 'error', '-f', 'rawvideo', '-pixel_format', 'nv12',
        '-video_size', f'{stride}x{height}', '-i', str(packed),
        '-frames:v', '60', str(work / 'frame-%03d.png'))
    concat = work / 'frames.ffconcat'
    rows = ['ffconcat version 1.0']
    duration = statistics.median(intervals)
    for i in range(60):
        rows += [f"file 'frame-{i+1:03d}.png'", 'option framerate 1000',
                 f'duration {(intervals[i] if i < 59 else duration):.9f}']
    # A repeated endpoint expresses the last captured frame's hold duration.
    rows += ["file 'frame-060.png'", 'option framerate 1000']
    concat.write_text('\n'.join(rows) + '\n', encoding='utf-8')
    output = folder / 'ps2-hdmi-capture.mp4'
    if output.exists():
        raise ValueError('Refusing to overwrite MP4')
    run('ffmpeg', '-v', 'error', '-safe', '0', '-f', 'concat', '-i', str(concat),
        '-vf', f'crop={width}:{height}:0:0,setsar=8/9', '-fps_mode', 'vfr',
        '-c:v', 'libx264', '-preset', 'medium', '-crf', '16', '-pix_fmt', 'yuv420p',
        '-video_track_timescale', '1000000', '-movflags', '+faststart', str(output))
    report = dict(captured_frames=60, unique_pixel_hashes=len(set(hashes)),
                  captured_width=width, captured_height=height, stride=stride,
                  sample_interval_median_ms=statistics.median(intervals)*1000,
                  sample_interval_min_ms=min(intervals)*1000,
                  sample_interval_max_ms=max(intervals)*1000,
                  measured_sample_rate_hz=59/((samples[-1][1]-samples[0][1])/1e9),
                  source='TV main FSC capture tap, PS2 HDMI4', audio=False,
                  encoding='PC FFmpeg/libx264, offline',
                  note='60 captured buffers; final image repeated once to encode its hold time. Capture timestamps are CPU read times, not HDMI PTS. NV12 layout visually verified for this signal; colour calibration unverified.',
                  hashes=hashes)
    (folder / 'sequence-assembly-proof.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k != 'hashes'}, indent=2))


if __name__ == '__main__':
    main()
