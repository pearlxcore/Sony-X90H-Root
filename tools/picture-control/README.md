# X90H Picture Control v0.2.0

Drive the TV's real picture path (Sony X1 / MT5895) from software, safely and
reversibly, for any input. The PS2 was only the first test source.

## Coverage

- All 84 `g_video__` keys; 82 settable; 12 key-bearing groups (11 settable plus
  `meta_readonly`).
- Ranges, defaults and enum labels read live and read-only.
- 9 keys are conditional (3D, HDR-on-SDR, aspect) and return `rc=-3` outside
  their context.
- Live round-trip verified: set new keys, then `restore` -> `RESTORED_IDENTICAL`.

## Files

- `x1` - host CLI: spec/groups/list/get/minmax/set/snapshot/restore/route/
  measure/deploy.
- `lib/x1-device.sh` - device worker, run as root through the mailbox; checks
  firmware and `dtv_driver.ko` hash.
- `lib/x1-keys.txt` - key list used by deploy.
- `x1-spec.json` - machine-readable control surface.
- `tools/PQTool.java`, `tools/pqtool.dex` - generic `MtkTvConfig` get/default/
  minmax/dump/set/setmany.

## Quick start

```sh
cd tools/picture-control
./x1 deploy
./x1 check
./x1 get
./x1 snapshot mine
./x1 set g_video__color_temperature 3
./x1 restore mine
./x1 route game_plus
```

## Safety

- Settings are per input. Snapshot before changing; restore after.
- Do not issue `sopq...get_all`; it reboots the TV.
- Do not run recursive root `ls`/`grep`/`find` on this TV.
- No EEPROM raw writes; persistence uses Sony's official `setConfigValue`.
- Never touch `perm`, DRM, TEE, keymaster or RPMB.
- The kit controls Sony's path. The experimental engine overlay bypasses Sony
  Reality Creation/MEMC, so an engine A/B is not a measurement of this kit.

The spec is pre-generated. The private key-sweep and its build script are not included.
