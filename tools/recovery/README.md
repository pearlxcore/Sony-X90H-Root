# Recovery tools

These tools were built for the boot-flow, first-boot, post-wipe and
wipe-preservation investigations. They are read-only or host-local. None
installs a recovery, writes a partition or reboots the TV.

| File | Purpose |
|---|---|
| `observe_boot.py` | Snapshot/watch the boot; never reboots or repairs |
| `analyze_boot.py` | Post-process a boot capture |
| `acquire_adb_artifacts.py` | Bounded ADB acquisition |
| `acquire_boot_consumers.py` | Bounded boot-consumer acquisition |
| `acquire_firstboot_artifacts.py` | Bounded first-boot acquisition |
| `resolve_boot_literals.py` | Init literal resolution |
| `demonstrate_format_preservation.py` | Host ext4 format experiment |
| `map_usb_storage.py` | Adopted USB/OTG mapping |
| `misc_patch.py` | Surgical `misc` BCB clear; preserves AB0 at `0x800` |

## What the investigation established

- Authenticated LAN ADB appeared at 27.05 s uptime while `bootanim` was still
  running; healthy root at 119.97 s.
- Post-wipe authenticated ADB was **not achieved**. `WipeData` formats userdata;
  there is no per-file exclusion. The shipped `Device` pre/post-wipe callbacks
  are no-ops.
- The debug-ramdisk path needs `INIT_FORCE_DEBUGGABLE=true` plus an
  unlocked-device check; no stock locked/green deployment was established.
- Recovery LAN access failed in the recorded test; recovery USB shell, fastboot
  and non-booting firmware rescue remain unqualified.

## Repair

The TV is in a recovery loop. Read
[../../docs/recovery.md](../../docs/recovery.md). The repair is eMMC ISP or a
main-board swap; a surgical `misc` patch must preserve the AB0 block at `0x800`.
Do not flash a full zero `misc`.

The host-side scripts that need private firmware or ramdisk dumps (stock wipe config, stock ADB defaults, boot-consumer tracing, first-boot reference preservation) are not included.
