# Bootloader and security notes

This page records what was learned about the boot chain on the Sony X90H /
KD-55X9000H, firmware `626241` / kernel `4.19.260-ab626241`. It is analysis and
a warning, not a flashing guide.

Technical detail - U-Boot env format/CRC, the `misc` struct and the fastboot
gating pseudocode - is in [bootloader-technical.md](bootloader-technical.md).

> The bootloader experiment (S11) is closed. It produced `orange/unlocked` but
> skipped official provisioning, broke the TEE/keymaster path. Do not repeat it on a daily-use TV.

## AVB state machine

The bootloader uses Android Verified Boot class B:

| Device state | Verification | Result |
|---|---|---|
| locked | OK | green, boots |
| locked | error | red, halted |
| unlocked | OK | orange, boots |
| unlocked | error | orange, boots |

Consequence: **never flash an image while `devicestate=lock`**. Unlock first, or
use a route that does not require flashing.

## U-Boot environment

- The environment occupies the first 128 KiB of `uboot_env_a`; it is plaintext
  with a CRC32.
- Variables include `bootcmd`, `bootdelay` and `devicestate`.
- The bootloader reads it and does not rewrite it.
- There is no signature on the environment. That is the integrity weakness S11
  used.

Writing `devicestate=unlock` and fixing the CRC makes the bootloader report
`unlocked` / `orange`. It does **not** perform the official unlock provisioning
(keybox/RPMB). The Trusty keymaster then hangs in `generate_key`; setup, DRM and
attestation break. After relock, the operator reached setup on an ordinary power-on following the reset. See [the corrected S11 account](recovery.md).

## What the unlock did and did not do

- The environment edit was performed more than once. The TV was observed in
  both green/locked and orange/unlocked states.
- On 2026-11-17 the unlock was confirmed again: orange/unlocked on a **stock**
  boot at 03:39, before the patched boot was written.
- The current recovery loop is **not** evidence that the unlock failed. It
  followed writing the patched `boot_a` (`23543a64...`), built to add
  `/adb_keys`, `/force_debuggable` and `/adb_debug.prop` for ADB access in
  recovery, then rebooting.
- The loop mechanism is the recovery/factory-restore stage: `misc` carries
  `boot-recovery`, recovery mounts the recipe partition read-only and runs
  `dtv_driver` with `osd_ota_stage=1`.
- The unlock state is orange/unlocked. `boot_a` is stock-or-patched, unknown.

## Fastboot surface

The fastboot command surface was analysed from the shipped U-Boot source and
the device's partition table. The important properties:

- `fastboot flash` is refused while locked.
- The partition name must exist in `mtdparts`.
- Any `uboot*` name is refused while `critical_lock` is set.
- The image size must fit the partition.
- There is no name allow-list and no signature check at flash time.
- Packed `BOOTLDR!` images take a different path that skips the locked check -
  recorded as an open item, not used.
- `critical_lock` is RAM-only. It defaults to 1 and re-arms on every reboot;
  `unlock_critical` must be re-issued per fastboot session.
- `get_unlock_ability` echoes `devicestate`; it is not independent evidence.
- `set_active` has no lock gate.
- `erase userdata` is allowed while locked; it writes the BCB and does not
  zero the partition. Other erases are refused while locked.

The fastboot marker is written at `misc+0x200` as the ASCII string `fastboot`.
While locked, flash and erase both refuse, so the marker is one-way: a
marker-only rehearsal parks the TV in fastboot. Fastboot entry was **never
observed** on this unit. Do not rely on it.

## `misc`, BCB and slots

- `misc_message` starts at `0x000`; the command field is at `0x000` and the
  recovery field at `0x200`.
- The AB0 slot metadata is at `0x800` and must be preserved.
- A recovery command in `misc` forces recovery on every boot. Clearing only the
  command bytes is the surgical repair; do not zero the whole partition.
- `boot_b` is not a byte-copy of `boot_a`. It is the previous firmware
  `S111/625981`, with a different kernel and ramdisk. It is a whole-slot
  fallback only, not a drop-in `boot_a` replacement.

## What is proven

- The environment is plaintext, CRC32-protected and unsigned.
- The env edit changes the reported device state.
- The official provisioning step is skipped; the TEE/keymaster path breaks.
- The AVB class B state machine matches the bootloader source.
- The fastboot gating and `misc`/AB0 layout match the shipped source.

## What is not proven

- Fastboot entry on this unit. It was never observed.
- The exact attestation/Keymaster consequence of unlock: no Trusty, keymaster or
  RPMB capture exists.
- The upgrade/oops/svc/recipe and USB-update path is not fully mapped.
- BROM/eMMC ISP on MT5895 is unproven; public tooling is phone-focused.
- The current partition state after the 2026-11-17 recovery loop is unknown.

## Safety

- Do not repeat the `devicestate` edit.
- Do not flash while locked.
- Do not rely on the fastboot marker while locked.
- Do not treat an old green/locked reading as the current state.
- If the TV is in a recovery loop, use [recovery.md](recovery.md), not a blind
  flash.
