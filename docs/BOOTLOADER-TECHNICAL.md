# Bootloader and security - technical notes

This page holds the low-level layouts and code paths behind
[BOOTLOADER.md](BOOTLOADER.md). It is analysis, not a flashing guide.

## 1. U-Boot environment

`uboot_env_a` is the environment partition. The environment occupies the first
128 KiB; the partition image itself can be 2 MiB. The first four bytes are a
little-endian CRC32; the environment data follows and is zero-padded.

From the S11 audit of this unit:

- environment block: 128 KiB
- CRC32 over `data[4:0x20000]`
- 38 plaintext variables
- `devicestate=lock`
- CRC32 value `0x22174022`
- zero padding after the used region
- no redundant plaintext environment in `uboot_env_b`

The variable area is a sequence of NUL-terminated `key=value` strings, ending
with an empty string.

A read-only parser is in `tools/bootloader/uboot_env.py`:

```sh
python tools/bootloader/uboot_env.py uboot_env_a.img --check
python tools/bootloader/uboot_env.py uboot_env_a.img --list
```

The parser verifies the CRC and prints the variables. It does not write unless
`--write --output FILE` is given, and it never writes in place.

Minimal CRC check:

```python
import struct, zlib
data = open(path, "rb").read()[:0x20000]
stored = struct.unpack("<I", data[:4])[0]
calc = zlib.crc32(data[4:0x20000]) & 0xffffffff
print("crc", hex(stored), hex(calc), stored == calc)
```

Changing `devicestate` and fixing the CRC is what S11 did. It produced
`orange/unlocked` but skipped official provisioning. Do not repeat it on a
daily-use TV. See [BOOTLOADER.md](BOOTLOADER.md).

## 2. AVB state machine

The bootloader implements Android Verified Boot class B:

```
if (locked) {
    if (verify_ok) return GREEN;   /* boots */
    else           return RED;     /* halted */
} else {
    if (verify_ok) return ORANGE;  /* boots */
    else           return ORANGE;  /* boots */
}
```

Consequence: never flash while `devicestate=lock`. Unlock first, or use a route
that does not require flashing.

`veritymode` stays `enforcing` in the S11 test, so an AVB bypass was never
proved.

## 3. `misc` and the BCB

`misc_message` is defined in the board code as:

```c
struct misc_message {
    char command[32];     /* 0x000 */
    char status[32];      /* 0x020 */
    char recovery[1024];  /* 0x040 */
};
```

Readers look at `misc+0` and `misc+0x200`. The AB0 slot metadata is at
`misc+0x800`.

`misc` is 1 MiB. A recovery command at `misc+0` forces recovery on every boot.
The surgical repair is to clear only `0x000..0x3FF` and preserve
`0x400..0xFFFFF`, especially AB0 at `0x800`. Never flash an all-zero full
`misc`.

The helper `tools/recovery/misc_patch.py` implements this safely:

```sh
python tools/recovery/misc_patch.py misc.img --check
python tools/recovery/misc_patch.py misc.img --clear-bcb --output misc.cleared.img
```

It writes a new file, prints the SHA256 before and after, and refuses to
overwrite without `--force`.

## 4. Fastboot gating

The shipped `do_flashing` path is generic. In pseudocode:

```c
if (devicestate_is_locked())
    refuse("not allowed in locked state");

if (!partition_name_in_mtdparts(name))
    refuse("unknown partition");

if (is_uboot_name(name) && critical_lock)
    refuse("uboot is protected");

if (image_size > partition_size)
    refuse("image too large");

/* no name allow-list; no signature check at flash time */
flash(name, data, size);
```

Packed `BOOTLDR!` images take a different path that skips the locked check;
that is recorded as an open item and was not used.

`critical_lock` is a file-static variable, default 1, set by
`lock_critical`/`unlock_critical`. It is not stored in the environment, so the
`uboot*` ban re-arms on every reboot. `unlock_critical` must be re-issued per
fastboot session.

`get_unlock_ability` echoes `devicestate`; it is not independent evidence.

`do_setActive` validates only the slot letter and calls `ab_setActiveSlot()`.
There is no `devicestate` check, so `set_active` works while locked and writes
the AB0 block.

`erase userdata` is allowed while locked. It writes the BCB and does not zero
the partition. Other erases are refused while locked.

## 5. Fastboot marker

The fastboot marker is the ASCII string `fastboot` at `misc+0x200`. While
locked, `flash` and `erase` both refuse, so the marker is one-way: a
marker-only rehearsal parks the TV in fastboot. Fastboot entry was never
observed on this unit.

`misc+0x000` is the boot command (`boot-recovery` for recovery). The recovery
field at `0x200` is the fastboot marker slot.

## 6. `boot_b`

`boot_b` is not a byte-copy of `boot_a`. It is the previous firmware
`S111/625981`, with a different kernel and ramdisk:

- `boot_a` stock: `26fbb93fb2f3e433ea3654add876122b616461b721be3715bdf3f94876c68dd3`
- `boot_b` S111: `cd012e25ecbf1311fa5f74c1cde40e42e7fbb60f0c2f11ef0c960ce42eba1739`
- `boot_b` rollback_index 0, flags 0

It is a whole-slot fallback via `set_active b`, not a drop-in `boot_a`
replacement.

## 7. What is not proven

- Fastboot entry on this unit.
- The exact attestation/Keymaster consequence of unlock. No Trusty, keymaster
  or RPMB capture exists.
- The upgrade/oops/svc/recipe and USB-update path.
- BROM/eMMC ISP on MT5895.
- The current partition state after the 2026-11-17 recovery loop.

See [RECOVERY-TECHNICAL.md](RECOVERY-TECHNICAL.md) for the loop and repair.
