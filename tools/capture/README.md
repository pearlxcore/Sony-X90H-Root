# Capture tools

The X90CAP2 capture workstream tools. The PS2 was only the first source; the
tools capture whatever is on the input.

## Files

- `x90cap.py` - X90CAP2 client: session start/stop, frame export, cadence.
- `analyze_cadence.py` - cadence/gap analysis.
- `assemble_hdmi_capture.py` - assemble a capture into a viewable output.
- `frame_diagnostics.py` - frame diagnostics.
- `test_frame_diagnostics.py`, `test_x90cap.py` - tests.
- `x90-pq.c` - source of the typed picture/source/signal reader.
- `module/` - `x90cap_session.c`, `x90cap_probe.c`, `x90audio_probe.c` and the
  build scripts. The exact tested `.ko` files are not included.

## Status

- Latest measured capture cadence: 54.868 fps with gaps.
- Saved-file hardware encoder: 61 frames verified; BT.601 metadata correction
  optional. No real-file TV encode test was established.
- Game audio over the capture path remained unresolved; the DSP upload test was
  all zero.
- The capture image is the decoder picture, not the 4K panel. For the panel, use
  `../display/x90shot/`.

## Build

The module sources are in `module/`. Build them against the Sony
`4.19.260-ab626241` tree with `CONFIG_MODVERSIONS`; the module `Makefile` lists
the three objects. The exact tested `.ko` files are not included.

## Safety

The capture module is loaded on the TV. Exact firmware/driver guards apply. Do
not run it on a TV that is not booted and rooted.
