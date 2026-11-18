# X1 Picture Control v0.2.0

X90H Picture Control is a small toolset over the TV's real picture path. It
drives Sony's X1/MT5895 processing for the active input. It is not PS2-specific
and it does not bypass Sony's processing.

The code and register-level detail are in [picture-firmware-analysis.md](picture-firmware-analysis.md).

## Coverage

- All 84 `g_video__` keys from the MediaTek TV configuration surface.
- 82 settable; 2 are read-only version fields.
- 12 key-bearing groups (11 settable + `meta_readonly`): picture mode, detail,
  noise, motion, colour, dynamic contrast, white balance, colour management,
  HDR, geometry, 3D legacy, meta_readonly.
- Ranges, defaults and enum labels were read live and read-only with
  `MtkTvConfig.getMinMaxConfigValue`; no probe writes.
- 9 keys are conditional (3D, HDR-on-SDR, certain aspect/overscan) and return
  `rc=-3` outside their context.
- A key with `max == 0` on the live sweep is unavailable on that input.

## How it works

- `tools/picture-control/x1` is the host CLI.
- `lib/x1-device.sh` is the device worker, run as root through the root
  mailbox. It refuses unless the firmware fingerprint and `dtv_driver.ko` hash
  match this build.
- `x1-spec.json` is the machine-readable control surface.
- `tools/PQTool.java` / `pqtool.dex` is a generic `MtkTvConfig` get/default/
  minmax/dump/set/setmany tool, invoked by reflection in the
  `sony_platform_app` context.

Quick start (historical; the TV must be booted, rooted and on a signal):

```sh
cd tools/picture-control
./x1 deploy
./x1 check
./x1 get
./x1 snapshot mine
./x1 set g_video__color_temperature 3
./x1 restore mine
```

## Routes and persistence

- `x1 route standard|game|game_plus` selects a persistent picture route.
- `setConfigValue` is the same call Sony's UI uses. It persists through
  `eeprom_A` raw byte arrays; this was proven across a reboot. No EEPROM
  hacking is involved.
- Settings are per input. `snapshot` before changing; `restore` after.
- The experimental custom engine overlay bypasses Sony Reality Creation and
  MEMC, so an engine A/B is not a measurement of this kit's effect.

## SOPQ CLI

The Sony PQ engine is reachable from userspace through `/dev/mtal` ioctl
`0x401011a1` with root. Read, write (with clamp), range read and per-vsync
monitoring of SREGs are proven. `/dev/cli` is a dead stub in this firmware; the
earlier "SELinux blocks /dev/cli" framing was true but not the blocker.

`motionFlow` applies and then reverts when written through the CLI; persistence
is solved by using Sony's official `setConfigValue` path instead.

## Latency

On HDMI4, 480i60, 2026-11-16:

| Mode | Stimulus to panel |
|---|---|
| Standard | 165-185 ms |
| Game | ~32 ms |
| Game + Reality Creation Auto / res 65 / sharp 65 | ~33.7 ms |

The earlier 309 ms Standard result did not repeat on 2026-11-16. Keep both dates
if publishing a comparison. Latency is set by the picture mode, not by
MotionFlow alone.

## Safety

- Snapshot before changing; restore after.
- Do not issue `sopq...get_all`; it reboots the TV.
- Do not run recursive root `ls`/`grep`/`find` on this TV; it has wedged before.
- Never touch `perm`, DRM, TEE, keymaster or RPMB.
- No raw EEPROM writes.

The tool and its full README are in
[tools/picture-control](../tools/picture-control/).
