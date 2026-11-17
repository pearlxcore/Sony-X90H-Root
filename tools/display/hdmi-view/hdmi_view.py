"""Local HDMI4 preview for the researched VH1/S137/480i capture path.

Uses existing per-boot root mailbox, never starts an exploit. Every session has
fresh assets/logs, firmware/source/idle guards and an independent TV time limit.
"""
import argparse
import ctypes as C
import hashlib
import json
import io
import os
from pathlib import Path
import queue
import shlex
import socket
import struct
import subprocess
import threading
import time
import uuid

import numpy as np
from PIL import Image, ImageTk

NO_WINDOW = 0x08000000 if os.name == 'nt' else 0


def receive(sock, count):
    data = bytearray()
    while len(data) < count:
        block = sock.recv(count - len(data))
        if not block:
            raise EOFError('TV stream ended')
        data.extend(block)
    return bytes(data)


def nv12_image(raw, width, height, stride):
    pixels = np.frombuffer(raw, np.uint8)
    y = pixels[:stride * height].reshape(height, stride)[:, :width].astype(np.int32) - 16
    uv = pixels[stride * height:].reshape(height // 2, stride)[:, :width].astype(np.int32) - 128
    u = uv[:, 0::2].repeat(2, 0).repeat(2, 1)
    v = uv[:, 1::2].repeat(2, 0).repeat(2, 1)
    rgb = np.stack(((298*y + 409*v + 128) >> 8,
                    (298*y - 100*u - 208*v + 128) >> 8,
                    (298*y + 516*u + 128) >> 8), axis=2)
    return Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8))


class WaveOut:
    """Windows PCM output with at most six 16ms packets queued."""
    class Format(C.Structure):
        _pack_ = 1
        _fields_ = [('tag', C.c_ushort), ('channels', C.c_ushort),
                    ('rate', C.c_uint), ('bytes_per_second', C.c_uint),
                    ('alignment', C.c_ushort), ('bits', C.c_ushort), ('extra', C.c_ushort)]

    class Header(C.Structure):
        _fields_ = [('data', C.c_void_p), ('length', C.c_uint), ('recorded', C.c_uint),
                    ('user', C.c_size_t), ('flags', C.c_uint), ('loops', C.c_uint),
                    ('next', C.c_void_p), ('reserved', C.c_size_t)]

    def __init__(self):
        self.api = C.WinDLL('winmm')
        self.handle = C.c_void_p()
        self.buffers = []
        fmt = self.Format(1, 2, 48000, 192000, 4, 16, 0)
        rc = self.api.waveOutOpen(C.byref(self.handle), C.c_uint(-1), C.byref(fmt), 0, 0, 0)
        if rc:
            raise RuntimeError(f'Windows audio output unavailable: {rc}')

    def write(self, pcm):
        keep = []
        for header, memory in self.buffers:
            if header.flags & 1:
                self.api.waveOutUnprepareHeader(self.handle, C.byref(header), C.sizeof(header))
            else:
                keep.append((header, memory))
        self.buffers = keep
        if len(keep) >= 6:
            return  # Drop audio instead of accumulating delay on a stalled PC.
        memory = C.create_string_buffer(pcm)
        header = self.Header(C.cast(memory, C.c_void_p), len(pcm), 0, 0, 0, 0, None, 0)
        if self.api.waveOutPrepareHeader(self.handle, C.byref(header), C.sizeof(header)):
            raise RuntimeError('Cannot prepare Windows audio buffer')
        self.buffers.append((header, memory))
        if self.api.waveOutWrite(self.handle, C.byref(header), C.sizeof(header)):
            raise RuntimeError('Cannot play Windows audio buffer')

    def close(self):
        self.api.waveOutReset(self.handle)
        for header, memory in self.buffers:
            self.api.waveOutUnprepareHeader(self.handle, C.byref(header), C.sizeof(header))
        self.buffers.clear()
        self.api.waveOutClose(self.handle)


class Session:
    def __init__(self, args):
        self.args = args
        self.token = time.strftime('%Y%m%d-%H%M%S-') + uuid.uuid4().hex[:8]
        self.remote = '/data/local/tests/x90-view-' + self.token
        self.logdir = args.logs / self.token
        self.logdir.mkdir(parents=True, exist_ok=False)
        self.stop_event = threading.Event()
        self.frames = queue.Queue(maxsize=3)
        self.status = 'Preparing HDMI 4 capture...'
        self.sockets, self.forwards, self.threads = [], [], []
        self.started = False
        self.audio_enabled, self.boost = True, True
        self.frame_count = self.audio_bytes = self.audio_peak = 0
        self.played_packets = 0
        self.rendered_frames = 0
        self.error = None
        self.video_shape = None
        self.times = []
        self.arrivals = []
        self.sequences = []
        self.render_times = []
        self.payload_bytes = 0
        self.done = threading.Event()

    def adb(self, *command, timeout=20, check=True):
        p = subprocess.run([str(self.args.adb), '-s', self.args.serial, *command],
                           capture_output=True, timeout=timeout, creationflags=NO_WINDOW)
        output = p.stdout.decode(errors='replace').replace('\r', '')
        if check and p.returncode:
            raise RuntimeError(p.stderr.decode(errors='replace') or output)
        return output

    def logs(self):
        return self.adb('shell', 'cat ' + shlex.quote(self.remote + '/session.txt') + ' 2>/dev/null; true', timeout=5)

    def connect(self, port):
        local = int(self.adb('forward', 'tcp:0', 'tcp:' + str(port)).strip())
        self.forwards.append(local)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline and not self.stop_event.is_set():
            sock = socket.socket()
            sock.settimeout(2)
            try:
                sock.connect(('127.0.0.1', local))
                # adbd may accept before the TV listener is ready; require header.
                sock.settimeout(6)
                header = receive(sock, 32)
                sock.settimeout(4)
                self.sockets.append(sock)
                return sock, header
            except (OSError, EOFError):
                sock.close()
                time.sleep(.15)
        if not self.stop_event.is_set():
            log = self.logs()
            if 'HDMI_SESSION_FINISHED' in log or 'X90_VIEW_DONE=' in log:
                raise RuntimeError('TV declined capture; see session.txt: ' + log[-1600:])
        raise RuntimeError('TV capture did not start. Existing root, HDMI4 and 480i are required.')

    def prepare(self):
        assets = self.args.assets
        manifest = json.loads((assets / 'manifest.json').read_text())
        for name, digest in manifest.items():
            if Path(name).name != name or hashlib.sha256((assets / name).read_bytes()).hexdigest() != digest:
                raise RuntimeError('HDMI capture asset checksum mismatch: ' + name)
        self.adb('shell', 'mkdir ' + shlex.quote(self.remote))
        for name in manifest:
            self.adb('push', str(assets / name), self.remote + '/' + name)
        sums = ''.join(digest + '  ' + name + '\n' for name, digest in manifest.items())
        (self.logdir / 'SHA256SUMS').write_text(sums, newline='\n')
        self.adb('push', str(self.logdir / 'SHA256SUMS'), self.remote + '/SHA256SUMS')
        base_port = 43000 + int(uuid.uuid4().hex[:4], 16) % 10000
        self.video_port, self.audio_port = base_port, base_port + 1
        command = 'sh ' + shlex.quote(self.remote + '/run-hdmi-session.sh') + ' ' + ' '.join(map(shlex.quote, [
            self.remote, self.token, str(self.args.seconds), str(base_port), str(base_port+1),
            '0' if self.args.no_audio else '1', str(self.args.fps), self.args.transport, str(self.args.quality), str(self.args.capture_nice), str(self.args.source)]))
        wrapper = '(' + command + '; r=$?; printf "\\nX90_VIEW_DONE=%s\\n" "$r") > ' + shlex.quote(self.remote + '/session.txt') + ' 2>&1; chmod 644 ' + shlex.quote(self.remote + '/session.txt')
        if self.stop_event.is_set():
            return
        self.adb('shell', "printf '%s' " + shlex.quote(wrapper) + ' > /data/local/tests/x90cmd.tmp; mv /data/local/tests/x90cmd.tmp /data/local/tests/x90cmd')
        self.started = True

    def video(self):
        try:
            sock, header = self.connect(self.video_port)
            magic, version, w, h, stride, _, source = struct.unpack('<8s6I', header)
            if magic not in (b'X90LIVE1', b'X90JPEG1') or version != 1 or source != self.args.source or not (0 < w <= stride <= 720 and 0 < h <= 480 and not w % 2 and not h % 2):
                raise RuntimeError('Invalid HDMI video stream header')
            self.video_shape = [w, h, stride]
            expected = stride * h * 3 // 2
            first = None
            while not self.stop_event.is_set():
                seq, stamp, ring, count, copied = struct.unpack('<QQIIQ', receive(sock, 32))
                if (magic == b'X90LIVE1' and count != expected) or not 0 < count <= expected*2 or ring > 2:
                    raise RuntimeError('Invalid HDMI video frame')
                pixels = receive(sock, count)
                image = (Image.open(io.BytesIO(pixels)).convert('RGB') if magic == b'X90JPEG1'
                         else nv12_image(pixels, w, h, stride))
                if image.size != (w, h):
                    raise RuntimeError('JPEG geometry changed')
                self.frame_count += 1
                self.times.append(stamp)
                self.arrivals.append(time.monotonic_ns())
                self.sequences.append(seq)
                self.payload_bytes += count
                if first is None:
                    first = image
                    image.save(self.logdir / 'first-frame.png')
                if self.frames.full():
                    try:
                        self.frames.get_nowait()
                    except queue.Empty:
                        pass
                self.frames.put_nowait(image)
                recent = self.times[-120:]
                fps = (len(recent)-1)*1e9/(recent[-1]-recent[0]) if len(recent)>1 else 0
                self.status = f'HDMI 4 . {w}x{h} . {fps:.1f} fps . {self.frame_count} frames . audio peak {self.audio_peak}/32768'
        except (EOFError, OSError) as error:
            if not self.stop_event.is_set() and self.frame_count == 0:
                self.error = str(error)
        except Exception as error:
            self.error = str(error)
        finally:
            self.stop_event.set()

    def audio(self):
        player = None
        try:
            sock, header = self.connect(self.audio_port)
            magic, rate, channels, bits, flags, start = struct.unpack('<8s4IQ', header)
            if (magic, rate, channels, bits) != (b'X90AUD01', 48000, 2, 16):
                raise RuntimeError('Invalid HDMI audio stream header')
            if not self.args.headless:
                try:
                    player = WaveOut()
                except Exception as error:
                    self.error = str(error)
            while not self.stop_event.is_set():
                stamp, count, seq = struct.unpack('<QII', receive(sock, 16))
                if count < 4 or count > 3072 or count % 4:
                    raise RuntimeError('Invalid HDMI PCM packet')
                pcm = receive(sock, count)
                self.audio_bytes += count
                values = np.frombuffer(pcm, '<i2').astype(np.int32)
                self.audio_peak = max(self.audio_peak, int(np.abs(values).max()))
                if self.boost:
                    # Known upload0 path has tiny 16-bit samples. Boost is local,
                    # opt-in, and limited to 25% full scale for unexpectedly loud input.
                    pcm = np.clip(values * 1024, -8192, 8192).astype('<i2').tobytes()
                if player and self.audio_enabled:
                    player.write(pcm)
                    self.played_packets += 1
        except (EOFError, OSError):
            pass
        except Exception as error:
            self.error = 'Audio: ' + str(error)
        finally:
            if player:
                player.close()

    def run(self):
        try:
            self.prepare()
            if not self.started:
                return
            if not self.args.no_audio:
                t = threading.Thread(target=self.audio, daemon=True)
                self.threads.append(t)
                t.start()
            t = threading.Thread(target=self.video, daemon=True)
            self.threads.append(t)
            t.start()
            limit = time.monotonic() + self.args.seconds + 25
            while not self.stop_event.wait(.2) and time.monotonic() < limit:
                pass
        except Exception as error:
            self.error = str(error)
        finally:
            self.status = 'Stopping HDMI capture and checking TV cleanup...'
            self.stop_event.set()
            if self.started:
                try:
                    self.adb('shell', 'touch ' + shlex.quote(self.remote + '/stop'))
                except Exception as error:
                    self.error = str(error)
            for sock in self.sockets:
                try:
                    sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                sock.close()
            for t in self.threads:
                t.join(6)
            log = ''
            if self.started:
                deadline = time.monotonic() + 15
                while time.monotonic() < deadline:
                    try:
                        log = self.logs()
                    except Exception:
                        break
                    if 'X90_VIEW_DONE=' in log:
                        break
                    time.sleep(.4)
                (self.logdir / 'session.txt').write_text(log)
                if 'HDMI_SESSION_FINISHED result=0' not in log:
                    self.error = 'TV cleanup not confirmed. Do not restart; inspect ' + str(self.logdir / 'session.txt')
            for port in self.forwards:
                try:
                    self.adb('forward', '--remove', 'tcp:' + str(port))
                except Exception:
                    pass
            (self.logdir / 'frame-timing.csv').write_text('sequence,tv_completion_ns,pc_decode_ns\n'+''.join(
                f'{seq},{stamp},{arrival}\n' for seq,stamp,arrival in zip(self.sequences,self.times,self.arrivals)))
            (self.logdir / 'render-timing.csv').write_text('pc_render_ns\n'+''.join(f'{stamp}\n' for stamp in self.render_times))
            summary = dict(frames=self.frame_count, shape=self.video_shape, audio_bytes=self.audio_bytes,
                           audio_peak=self.audio_peak, pc_audio_packets=self.played_packets, rendered_frames=self.rendered_frames,
                           transport=self.args.transport, error=self.error, remote=self.remote,
                           payload_bytes=self.payload_bytes,
                           pc_receive_rate=(len(self.arrivals)-1)*1e9/(self.arrivals[-1]-self.arrivals[0]) if len(self.arrivals)>1 else 0,
                           pc_render_rate=(len(self.render_times)-1)*1e9/(self.render_times[-1]-self.render_times[0]) if len(self.render_times)>1 else 0,
                           video_rate=(len(self.times)-1)*1e9/(self.times[-1]-self.times[0]) if len(self.times)>1 else 0)
            (self.logdir / 'summary.json').write_text(json.dumps(summary, indent=2))
            self.status = self.error or 'Stopped . TV capture cleanup verified'
            self.done.set()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--assets', type=Path, default=Path(__file__).with_name('assets'))
    parser.add_argument('--logs', type=Path, default=Path(__file__).with_name('logs'))
    parser.add_argument('--seconds', type=int, default=300)
    parser.add_argument('--headless', action='store_true')
    parser.add_argument('--no-audio', action='store_true')
    parser.add_argument('--fps', type=int, default=0, help='0 removes the publishing cap')
    parser.add_argument('--transport', choices=['raw','jpeg'], default='jpeg')
    parser.add_argument('--quality', type=int, default=85)
    parser.add_argument('--capture-nice', type=int, choices=[0,5], default=5, help='Temporary native-client scheduling boost')
    parser.add_argument('--source', type=int, choices=[3,5], default=3, help='VENC capture source (3 = 696x464 crop, 5 = full 720x480)')
    args = parser.parse_args()
    if not 2 <= args.seconds <= 600:
        parser.error('Session duration must be 2..600 seconds')
    if not 0 <= args.fps <= 60:
        parser.error('FPS must be 0..60')
    if not 50 <= args.quality <= 95:
        parser.error('JPEG quality must be 50..95')
    session = Session(args)
    thread = threading.Thread(target=session.run, daemon=True)
    if args.headless:
        thread.start()
        while not session.done.wait(.5):
            pass
        print((session.logdir / 'summary.json').read_text())
        return 1 if session.error or not session.frame_count else 0
    import tkinter as tk
    from tkinter import ttk
    root = tk.Tk()
    root.title('Sony X90H Remote View - HDMI 4 + Audio')
    root.geometry('1050x760')
    root.configure(bg='#0b141b')
    bar = tk.Frame(root, bg='#0b141b')
    bar.pack(fill='x', padx=16, pady=12)
    tk.Label(bar, text='X90H  |  HDMI 4', fg='#55d8cf', bg='#0b141b', font=('Segoe UI', 15, 'bold')).pack(side='left')
    sound, boost = tk.BooleanVar(value=True), tk.BooleanVar(value=True)
    ttk.Checkbutton(bar, text='PC sound', variable=sound).pack(side='right', padx=10)
    ttk.Checkbutton(bar, text='Boost faint HDMI audio (+60 dB, limited)', variable=boost).pack(side='right')
    canvas = tk.Canvas(root, bg='black', highlightthickness=0)
    canvas.pack(fill='both', expand=True, padx=16)
    text = tk.StringVar(value=session.status)
    tk.Label(root, textvariable=text, fg='#bed0dc', bg='#0b141b', anchor='w', wraplength=1000).pack(fill='x', padx=16, pady=8)
    tk.Label(root, text=f'480i preview - {args.transport.upper()} - {"uncapped" if args.fps==0 else str(args.fps)+" fps cap"} - {args.seconds}s session', fg='#8a9ba9', bg='#0b141b').pack(pady=(0, 6))
    closing = [False]
    last_status_update = [0.0]
    def close():
        closing[0] = True
        session.stop_event.set()
        text.set('Stopping and checking TV cleanup...')
    ttk.Button(root, text='Stop / close', command=close).pack(pady=(0, 12))
    root.protocol('WM_DELETE_WINDOW', close)
    def tick():
        session.audio_enabled, session.boost = sound.get(), boost.get()
        recent = session.render_times[-120:]
        rendered = (len(recent)-1)*1e9/(recent[-1]-recent[0]) if len(recent)>1 else 0
        if time.monotonic() - last_status_update[0] >= .25:
            text.set(session.status + (f' . display {rendered:.1f} fps' if rendered else ''))
            last_status_update[0] = time.monotonic()
        try:
            image = session.frames.get_nowait()
            # Preserve 4:3 PS2 display aspect instead of the capture pixel aspect.
            width, height = max(4, canvas.winfo_width()), max(3, canvas.winfo_height())
            scale = min(width / 4, height / 3)
            image = image.resize((int(scale*4), int(scale*3)), Image.Resampling.BILINEAR)
            if (hasattr(canvas, 'photo') and canvas.photo.width() == image.width
                    and canvas.photo.height() == image.height):
                canvas.photo.paste(image)
            else:
                canvas.photo = ImageTk.PhotoImage(image)
            if not hasattr(canvas, 'image_id'):
                canvas.image_id = canvas.create_image(width//2, height//2, image=canvas.photo)
            else:
                canvas.coords(canvas.image_id, width//2, height//2)
                canvas.itemconfigure(canvas.image_id, image=canvas.photo)
            session.rendered_frames += 1
            session.render_times.append(time.monotonic_ns())
        except queue.Empty:
            pass
        if session.done.is_set() and closing[0]:
            if session.error:
                from tkinter import messagebox
                messagebox.showerror('HDMI capture', session.error)
            root.destroy()
            return
        if session.done.is_set():
            end_timer()
        root.after(1 if not session.done.is_set() else 100, tick)
    thread.start()
    root.after(30, tick)
    timer_api = C.WinDLL('winmm') if os.name == 'nt' else None
    timer_started = timer_api is not None and timer_api.timeBeginPeriod(1) == 0
    timer_closed = [False]
    def end_timer():
        if timer_started and not timer_closed[0]:
            timer_api.timeEndPeriod(1)
            timer_closed[0] = True
    try:
        root.mainloop()
    finally:
        end_timer()
    return 1 if session.error else 0


if __name__ == '__main__':
    raise SystemExit(main())
