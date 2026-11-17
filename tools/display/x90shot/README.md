# x90shot - native panel still capture

`x90shot` captures the digital image the panel is driven with, after Sony's
processing and after the Android/engine overlays are mixed in, at native
3840x2160 panel coordinates. It reads the two stock OSTG pixel monitors (left
branch SRE 0x6900, right branch 0xa900).

SurfaceFlinger `screencap` cannot do this: Sony's video is a hardware SIDEBAND
plane it never sees.

## What it is not

- Not a single-frame capture. Points are sampled across many refreshes. Use it
  on static content; moving content gives a mosaic.
- Not an optical measurement. Backlight, local dimming and panel response are
  after this point.

## Validation

On 2026-11-16 (boot `87cbac69`) the v0.2 module passed the hashed-pattern test:

- seam 7200/7200 interior exact
- corners 2304/2304 twice
- whole screen step 8: 32400/32400

The pattern file `build/x90shot-pattern.dex` has SHA256
`53ee3a4b6b8f58b716099ce0903710dbbfbf1a39b29d55a48f089f7bb48e5a86`.

## Use

```sh
./x90shot deploy                 # once per boot / after rebuild
./x90shot preview NAME           # whole screen 960x540
./x90shot full NAME [STEP]       # whole screen; STEP 1 = native 4K
./x90shot strip NAME X W [STEP]  # full-height native strip (best for A/B)
./x90shot roi NAME X Y W H [STEP]
./x90shot ab NAME [strip X W | roi X Y W H | full]
./x90shot compare A_DIR B_DIR OUT
./x90shot validate
./x90shot status | abort | finish
```

`ab` captures the same frame twice: Sony alone, then the engine full screen,
restores everything, and compares. Output goes under `shots/NAME-<time>/`.

## Build

The module is built with the WSL kbuild recipe in `build.sh`. It rejects
unversioned imports; only the CRC'd exports on this firmware load. The module
hash of the validated v0.2 build was
`641fb4b1ba0ada37ef58313f1edf2b104400e4a7d7f176157c2a1a305be83274`.

## Safety

- Exact `dtv_driver.ko` hash and module hash are checked before every load.
- It touches only the pixel-monitor control/position words and restores them.
- It refuses if the latency rig or another monitor user is active.
- No PQ, panel, clock, DMA, capture or TEE access.
- Static scenes only.

## Files

- `x90shot.c` - kernel module
- `x90shot` - host CLI
- `x90shot-dev.sh` - device worker
- `x90shot_img.py` - tile assembly, pattern validation, still mask
- `x90shot_cmp.py` - bounds/sharpness/grid/tone metrics
- `x90shot_edges.py`, `x90shot_tonefit.py` - helpers
- `X90ShotPattern.java` - validation surface
- `shaders/` - the x90shot upsh set
- `build.sh`, `make_upsh.py`, `make_fush.py`
