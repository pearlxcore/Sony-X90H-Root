# Recovery - technical notes

This page holds the low-level details behind [recovery.md](recovery.md). It is
an offline analysis; the TV is in a recovery loop and no live test was possible.

## 1. The 2026-11-17 loop

The last confirmed sequence:

- 03:33 patched `boot_a` written and read back (`23543a64...`).
- 03:37 stock `boot_a` restored deliberately to probe the env.
- 03:39 reboot confirmed **orange/unlocked on a stock boot**. The env blob was
  valid.
- 03:57 patched `boot_a` written again; readback matches; state orange/unlocked.
- 03:58 reboot -> "do not switch off / updating".
- 04:40 stopped at the recovery loop.

The unlock is not the cause. The loop followed the patched boot and the
recovery/factory-restore path.

## 2. Recovery rc behavior

The dumped recovery rc shows:

```
mount mmcblk0p40 /3rd_rw rw
write recovery_mode_flag=1
mount mmcblk0p32 /vendor/recipe ro
insmod dtv_driver osd_ota_stage=1
```

- `mmcblk0p40` (`3rd_rw`) gets `recovery_mode_flag=1`.
- `mmcblk0p32` is the factory-restore source.
- `dtv_driver` with `osd_ota_stage=1` is the "do not power off" OTA UI.
- `misc` still carries `boot-recovery`, so every boot returns to recovery.

Consequence: this is recovery running a factory-restore/OTA stage, not a
downloaded update. `boot_a` is likely being rewritten from the recipe/upgrade
source. If the BCB keeps re-arming, every boot returns to recovery.

## 3. `misc` patch

`misc` layout:

| Offset | Content |
|---|---|
| `0x000` | `misc_message.command[32]` |
| `0x020` | `misc_message.status[32]` |
| `0x040` | `misc_message.recovery[1024]` |
| `0x200` | fastboot marker slot (inside the recovery field) |
| `0x800` | AB0 slot metadata |

The surgical patch clears only `0x000..0x3FF` and preserves the rest,
especially AB0 at `0x800`.

```python
data = bytearray(open(src, "rb").read())
assert len(data) >= 0x100000
data[0x000:0x400] = b"\x00" * 0x400   # command + status + recovery
open(dst, "wb").write(data)
```

Do not flash an all-zero full `misc` image. It wipes the AB0 metadata and can
make the state worse.

The helper is `tools/recovery/misc_patch.py`:

```sh
python tools/recovery/misc_patch.py misc.img --check
python tools/recovery/misc_patch.py misc.img --clear-bcb --output misc.cleared.img
```

It verifies the image size, prints the bytes at `0x000`, `0x200` and `0x800`,
and prints SHA256 before/after.

## 4. Partition offsets

Known from the device `mtdparts`; verify against the live GPT before acting.

| Partition | Size | Offset |
|---|---|---|
| `misc` | 1 MiB | `0x08000000` |
| `boot_a` | 64 MiB | `0x2DB00000` |

Verified stock `boot_a`:

- 67,108,864 bytes
- SHA256 `26fbb93fb2f3e433ea3654add876122b616461b721be3715bdf3f94876c68dd3`

`vbmeta_a` head is byte-identical to the OTA `vbmeta.img` for the first 8192
bytes: `8f3b2b5c927e3de8981a25160f95973b4554746eb970d6fcc755b517b6bc5918`.

`boot_b` is the previous firmware `S111/625981`, not a `boot_a` spare.

## 5. Post-wipe access

The recovery work investigated whether ADB access can survive a factory wipe.

- Normal `WipeData` formats userdata. There is no per-file exclusion mechanism.
- The shipped `Device` pre/post-wipe callbacks resolve to return-success
  no-ops.
- Existing log preservation is cache-specific.
- `recovery.wipe` is a separate partition wipe-list path, not an ADB pathname
  exception.
- Host ext4 experiments confirm format removes a dummy key and minimal
  repopulation restores only seeded data.
- Post-wipe authenticated ADB was **not achieved**.

The debug-ramdisk path requires `INIT_FORCE_DEBUGGABLE=true` plus an
unlocked-device check; no stock locked/green deployment was established.

## 6. First live boot flow (2026-11-16)

- Authenticated LAN ADB appeared at 27.05 s uptime while `bootanim` was still
  running and boot completion was unset.
- Healthy root at 119.97 s.
- One normal restart only; no induced fault.
- The TV-app reset and correct HDMI input recovered an owner-reported black
  PS2 picture.
- Final Doctor green; 20 exposed PQ/source/signal values matched pre-reboot.

A read-only observer is in `tools/recovery/observe_boot.py`:

```sh
python tools/recovery/observe_boot.py snapshot --serial 127.0.0.1:5555 --out boot-snapshot
python tools/recovery/observe_boot.py watch --serial 127.0.0.1:5555 --out boot-watch --duration 180
```

It never reboots or repairs.

## 7. What is proven

- The S11 relock loop was recovered by the hold-power factory wipe.
- The stock `boot_a` and `vbmeta_a` hashes match the OTA.
- The 2026-11-17 recovery rc behavior and the `boot-recovery` BCB are read from
  the dumped recovery files.
- eMMC ISP is the documented primary DIY channel; board swap is the easy path.

## 8. What is not proven

- A successful repair of the current loop. No outcome is recorded.
- Fastboot entry: never observed.
- Recovery LAN access: failed in the recorded test.
- Recovery USB shell and non-booting firmware rescue: unqualified.
- BROM/ISP on MT5895: unproven.
- UART log enable: unknown.
- A 1-006-895 schematic/boardview: not public.
- A validated partition-preserving scatter: not found.

Do not label the TV universally recoverable. Treat the full eMMC dump as
private.
