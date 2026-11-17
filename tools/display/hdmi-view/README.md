# HDMI input viewer

`hdmi_view.py` is a PC viewer and session owner for the HDMI input. It captures
through the vendor VSS path below the secure layer, sends JPEG frames, and can
carry audio. `field_capture.py` is the field-level helper;
`run-hdmi-session.sh` is the TV-side coordinator.

## Validated result

On HDMI4 on 2026-11-15: 57 frames, 9.65 fps, 696x464, stride 704, clean restore.
The later performance assets reported 58.04 receive / 57.98 picture updates per
second. The image is the decoder picture, **not** the 4K panel and not the
Android OSD.

## Requirements

- An existing per-boot root.
- The exact firmware/driver.
- An idle capture tap.
- The device-side capture module and runtime assets (not included here; the
  source is under `../capture/module/`).

## Typical command

```sh
python hdmi_view.py --adb <adb> --serial <tv-ip>:5555 \
  --assets <asset-dir> --seconds 6 --fps 10 --headless --no-audio
```

The session script owns a runlock and restores the capture state on exit.

## Limits

- 696x464 decoder picture, not the 4K panel.
- A/V sync and full musical fidelity were not established.
- It does not supersede panel capture (`../x90shot/`) or X1 picture control.

## Safety

The capture module is loaded on the TV. The exact firmware/driver guards apply.
Do not run it on a TV that is not booted and rooted.
