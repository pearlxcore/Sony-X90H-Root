# Recovery notes

This page separates two different recoveries. They are not interchangeable.

## Case 1 - S11 relock boot loop (historical)

After the S11 `devicestate` experiment, returning to `devicestate=lock` produced
a Sony-logo boot loop. The recorded recovery was:

1. Unplug the TV.
2. Hold the power button on the TV.
3. Plug the TV back in while still holding.
4. The TV shows an erasing screen, wipes `/data`, reboots and reaches setup.

That worked for the S11 case. It is **not** a universal recovery.

## Case 2 - 2026-11-17 recovery loop (current incident)

After a later patched-boot experiment, the TV stopped here:

- power on -> long Sony logo -> frozen "do not power off / updating" screen with a
  split/offset OSD and a dead spinner;
- no ADB, no ping, no USB-device enumeration, remote and buttons inert;
- the panel, backlight and power supply are fine; this is a software/firmware
  boot loop.

The last confirmed boot state before the loop was orange/unlocked with a patched
boot written and read back. `boot_a` is now stock-or-patched, unknown.

### Root cause (off-line analysis)

The recovery rc mounts `mmcblk0p40` (`3rd_rw`) read-write and writes
`recovery_mode_flag=1`; mounts `mmcblk0p32` as `/vendor/recipe` read-only (the
factory-restore source); and loads `dtv_driver` with `osd_ota_stage=1` (the
"do not power off" OTA UI).

`misc` still carries the `boot-recovery` command, so every boot returns to
recovery, and the factory-restore stage hangs.

### Repair options

**Option 1 - board-level repair (keeps the original board, serial and keys).**
Use eMMC ISP or MTK BROM via mainboard test points. No SoC desoldering is
required for in-circuit work.

1. Dump `misc` and `boot_a` first.
2. In the `misc` dump, clear **only** the boot-command bytes at `0x000..0x3FF`.
   Preserve the AB0 slot metadata at `0x800`.
3. Write the patched `misc` back.
4. `boot_a` is optional while unlocked; use the verified stock image if a clean
   boot is wanted.
5. Reboot.

Known offsets (verify against the live GPT before acting):

| Partition | Size | Offset |
|---|---|---|
| `misc` | 1 MiB | `0x08000000` |
| `boot_a` | 64 MiB | `0x2DB00000` |

Verified stock `boot_a`: 67,108,864 bytes, SHA256
`26fbb93fb2f3e433ea3654add876122b616461b721be3715bdf3f94876c68dd3`.

**Option 2 - main-board replacement.** A healthy 1-006-895-xx board matched to
the panel boots normally. The TV then carries the donor board's serial, MAC and
DRM keys. Keep the original board; it holds the original identity and is still
repairable.

### Do not

- Do not flash a full all-zero `misc` image. It wipes the AB0 metadata at
  `0x800` and can make the state worse.
- Do not blind-flash random firmware.
- Do not power-cycle while the progress UI is moving.
- Do not assume a hardware fault; the panel and power supply work.

## Post-wipe access analysis

The recovery department also investigated how to keep access after a factory
wipe. The result:

- Normal `WipeData` formats userdata. There is no per-file exclusion mechanism
  in that path.
- The shipped `Device` pre/post-wipe callbacks resolve to return-success no-ops.
- Existing log preservation is cache-specific; `recovery.wipe` is a separate
  partition wipe-list path, not an ADB pathname exception.
- Host ext4 experiments confirm format removes a dummy key and minimal
  repopulation restores only seeded data.
- Post-wipe authenticated ADB was **not achieved**.

The debug-ramdisk path requires `INIT_FORCE_DEBUGGABLE=true` plus an
unlocked-device check; no stock locked/green deployment was established.

## First live boot flow (2026-11-16)

- Authenticated LAN ADB was observed at 27.05 s uptime while `bootanim` was
  still running and boot completion was unset.
- Healthy root was present at 119.97 s.
- One normal restart only; no induced fault.
- The TV-app reset and the correct HDMI input recovered an owner-reported black
  PS2 picture.
- Final Doctor green; 20 exposed PQ/source/signal values matched pre-reboot.

## What is proven

- The S11 wipe recovery worked for the S11 relock loop.
- The stock `boot_a` and `vbmeta_a` hashes match the OTA.
- The 2026-11-17 recovery rc behaviour and the `boot-recovery` BCB are read from
  the dumped recovery files.
- eMMC/ISP is the documented primary DIY channel; board swap is the easy path.

## What is not proven

- A successful repair of the current loop. No outcome is recorded.
- Fastboot entry: never observed.
- Recovery LAN access: failed in the recorded test.
- Recovery USB shell and non-booting firmware rescue: unqualified.
- BROM/ISP on MT5895: unproven.
- UART log enable: unknown; Sony TVs may ship with it disabled.
- A 1-006-895 schematic/boardview: not public.
- A validated partition-preserving scatter: not found.

Do not label the TV "universally recoverable". Use the documented repair path
and treat the full eMMC dump as private.
