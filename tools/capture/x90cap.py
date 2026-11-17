"""Validate X90CAP2 captures and export timestamped video without interpolation."""
import argparse
import hashlib
import json
import statistics
import struct
import subprocess
from pathlib import Path

FILE = struct.Struct('<8s6I')
FRAME = struct.Struct('<QQIIQ')


def read_capture(path):
    with Path(path).open('rb') as stream:
        raw = stream.read(FILE.size)
        if len(raw) != FILE.size:
            raise ValueError('Truncated file header')
        magic, version, width, height, stride, count, source = FILE.unpack(raw)
        if magic != b'X90CAP2\0' or version != 2:
            raise ValueError('Unsupported capture format')
        if not (0 < width <= stride <= 1920 and width % 2 == 0 and stride % 16 == 0
                and 0 < height <= 1080 and height % 2 == 0 and 0 < count <= 300 and source == 3):
            raise ValueError('Invalid capture geometry/count/source')
        size = stride * height * 3 // 2
        frames = []
        previous = 0
        for index in range(count):
            raw = stream.read(FRAME.size)
            if len(raw) != FRAME.size:
                raise ValueError('Truncated frame header')
            sequence, completion, ring, length, copied = FRAME.unpack(raw)
            if sequence != index+1 or completion <= previous or copied < completion or ring > 2 or length != size:
                raise ValueError('Invalid frame sequence/timing/ring/size')
            if previous and completion-previous > 2_000_000_000:
                raise ValueError('Unsupported gap over two seconds')
            pixels = stream.read(size)
            if len(pixels) != size:
                raise ValueError('Truncated pixels')
            frames.append((completion, copied, ring, pixels))
            previous = completion
        if stream.read(1):
            raise ValueError('Unexpected trailing data')
    return dict(width=width, height=height, stride=stride, source=source, frames=frames)


def describe(capture):
    frames = capture['frames']
    gaps = [(b[0]-a[0])/1e6 for a, b in zip(frames, frames[1:])]
    # Hash active pixels, excluding row padding and unused allocation bytes.
    w, h, s = (capture[k] for k in ('width', 'height', 'stride'))
    hashes = []
    for _, _, _, pixels in frames:
        y = b''.join(pixels[row*s:row*s+w] for row in range(h))
        uv = b''.join(pixels[s*h+row*s:s*h+row*s+w] for row in range(h//2))
        hashes.append(hashlib.sha256(y+uv).hexdigest())
    return dict(width=w, height=h, stride=s, source=capture['source'], frames=len(frames),
                unique_active_pixel_hashes=len(set(hashes)),
                completion_rate_hz=(len(frames)-1)*1e9/(frames[-1][0]-frames[0][0]) if gaps else None,
                median_interval_ms=statistics.median(gaps) if gaps else None,
                max_interval_ms=max(gaps) if gaps else None,
                intervals_over_25ms=sum(g>25 for g in gaps),
                timestamps='Client callback delivery CLOCK_MONOTONIC, not HDMI hardware PTS',
                audio=False, active_pixel_hashes=hashes)


def export(capture, output, sar):
    # A unique directory prevents partial work from overwriting evidence.
    output.mkdir()
    stride, width, height = (capture[k] for k in ('stride', 'width', 'height'))
    frames = capture['frames']
    raw = output/'packed.nv12'
    with raw.open('xb') as stream:
        for frame in frames:
            stream.write(frame[3])
    subprocess.run(['ffmpeg', '-v', 'error', '-f', 'rawvideo', '-pixel_format', 'nv12',
                    '-video_size', f'{stride}x{height}', '-i', str(raw), '-frames:v', str(len(frames)),
                    '-vf', f'crop={width}:{height}:0:0', str(output/'frame-%04d.png')], check=True)
    if len(frames)>1:
        intervals=[(b[0]-a[0])/1e9 for a,b in zip(frames,frames[1:])]
        last=statistics.median(intervals)
        entries=['ffconcat version 1.0']
        for index in range(len(frames)):
            entries += [f"file 'frame-{index+1:04d}.png'", 'option framerate 1000000',
                        f'duration {(intervals[index] if index<len(intervals) else last):.9f}']
        entries += [f"file 'frame-{len(frames):04d}.png'", 'option framerate 1000000']
        concat=output/'frames.ffconcat'
        concat.write_text('\n'.join(entries)+'\n',encoding='utf-8')
        subprocess.run(['ffmpeg','-v','error','-safe','0','-f','concat','-i',str(concat),
                        '-vf',f'setsar={sar}','-fps_mode','vfr','-c:v','libx264','-crf','16',
                        '-pix_fmt','yuv420p','-video_track_timescale','1000000','-movflags','+faststart',
                        str(output/'capture.mp4')],check=True)
    report=describe(capture)
    report.update(sample_aspect_ratio=sar, aspect_note='Explicit caller assumption; not measured calibration',
                  terminal_hold='Final source image repeated once to encode median final hold duration')
    (output/'proof.json').write_text(json.dumps(report,indent=2)+'\n')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('capture',type=Path)
    parser.add_argument('--export',type=Path)
    parser.add_argument('--sar',choices=['1/1','8/9','10/11'],default='1/1',
                        help='Explicit pixel aspect assumption; use1/1 until calibrated')
    args=parser.parse_args()
    capture=read_capture(args.capture)
    summary=describe(capture)
    print(json.dumps({k:v for k,v in summary.items() if k!='active_pixel_hashes'},indent=2))
    if args.export:export(capture,args.export.resolve(),args.sar)


if __name__=='__main__':main()
