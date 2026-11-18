# U-Boot environment tool

`uboot_env.py` parses and CRC-checks the first 128 KiB of a Sony
`uboot_env_a` image.

## Format

- 128 KiB block.
- Offset `0x0000`: little-endian CRC32.
- Offset `0x0004`: NUL-separated `key=value` strings, zero-padded.
- CRC32 covers `data[4:0x20000]`.

The S11 audit of this unit found 38 variables, `devicestate=lock`, CRC32
`0x22174022`, zero padding after the used region, and no redundant plaintext
copy in `uboot_env_b`.

## Usage

```sh
python tools/bootloader/uboot_env.py uboot_env_a.img --check
python tools/bootloader/uboot_env.py uboot_env_a.img --list
```

Set a variable and write a new file:

```sh
python tools/bootloader/uboot_env.py uboot_env_a.img \
  --set devicestate=unlock --i-understand-s11-risk \
  --write --output uboot_env_a.new.img
```

The tool never writes in place. It refuses to overwrite unless `--force` is
given. Changing `devicestate` requires the explicit
`--i-understand-s11-risk` flag.

## Warning

Changing `devicestate` is the S11 experiment. It produced
`orange/unlocked` but skipped official provisioning, broke the TEE/keymaster
path. Do not use this tool as an unlock recipe.

Read [../../docs/bootloader-technical.md](../../docs/bootloader-technical.md)
and [../../docs/bootloader.md](../../docs/bootloader.md) first.
