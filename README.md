# Sony X90H (KD-55X9000H) - root and research notes

![Sony X90H](media/x90h.jpg)

*by pearlxcore*

This repository documents a root chain and related research on the Sony X90H /
KD-55X9000H (BRAVIA VH1, Android 12, firmware `626241`, kernel
`4.19.260-ab626241`). It covers the root chain, bootloader and security notes,
recovery analysis, X1 picture research, and the tools used to measure the TV.

Root is **per boot**. It is not a bootloader unlock and not a persistent
Magisk-style root. A reboot removes it.

The chain is build-specific. The addresses, offsets and module CRCs are for
firmware `626241` / kernel `4.19.260-ab626241`. Other builds will probably not
work.

## Current status

- Root was achieved and qualified on 2026-11-16, including a fresh-boot repeat.
- The bootloader `devicestate` experiment (S11) is closed. It produced
  `orange/unlocked` but skipped official provisioning, broke the TEE/keymaster
  path, and was not usable.
- The test TV later entered a recovery loop on 2026-11-17, after a patched-boot
  experiment. It is not currently booting Android. The recovery notes below
  describe both the earlier successful recovery and the current repair path.
- Do not assume the old hold-power factory wipe fixes the current loop. Read
  [docs/RECOVERY.md](docs/RECOVERY.md) first.

## Documentation

| Document | What it covers |
|---|---|
| [docs/ROOT.md](docs/ROOT.md) | The full root chain: surface, shell root, init domain, kernel module, server |
| [docs/ROOT-QUALIFICATION.md](docs/ROOT-QUALIFICATION.md) | 2026-11-16 channel qualification and fresh-boot repeat |
| [docs/ROOT-STAGES.md](docs/ROOT-STAGES.md) | Stage map S0-S16: achieved, closed, blocked, QEMU-only |
| [docs/BOOTLOADER.md](docs/BOOTLOADER.md) | AVB, U-Boot env, S11, fastboot surface, misc/AB0, boot_b |
| [docs/RECOVERY.md](docs/RECOVERY.md) | Boot flow, post-wipe analysis, the 2026-11-17 loop, repair options |
| [docs/X1-PICTURE.md](docs/X1-PICTURE.md) | X90H Picture Control v0.2.0 and the 84-key PQ surface |
| [docs/X1-DEFECTS.md](docs/X1-DEFECTS.md) | Four public defect claims, with PROVEN/CANDIDATE/OPEN labels |
| [docs/TOOLS.md](docs/TOOLS.md) | The tools included here and what they need |
| [docs/EVIDENCE-POLICY.md](docs/EVIDENCE-POLICY.md) | What is public, what stays private, and how measurements are labelled |
| [docs/EXPLOIT-HISTORY.md](docs/EXPLOIT-HISTORY.md) | The v21 source, its hashes, and the change policy |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | Repository changes |

## Repository layout

```
exploit/        prod_exploit_v21.c and its build/source-of-truth notes
module/         x90mod.c and the module build files
prebuilt/       the original payload, module and image
tools/          selected first-party tools (display, picture, recovery, capture)
docs/           write-ups and evidence policy
media/          images
```

## Quick start

Read [docs/ROOT.md](docs/ROOT.md) for the full chain, requirements and warnings.
The short version on the tested build:

```sh
adb connect <tv-ip>:5555
adb push prebuilt/prod_exploit_v21 /data/local/tmp/
adb push prebuilt/x90mod_sony.img /data/local/tests/x90mod.img
adb shell chmod 755 /data/local/tmp/prod_exploit_v21
adb shell "nohup /data/local/tmp/prod_exploit_v21 > /data/local/tmp/p21.log 2>&1 < /dev/null &"
```

Wait 2-3 minutes, then check:

```sh
adb shell getenforce
adb shell tail -5 /data/local/tests/x90log
```

Expected: `Permissive` and `X90INIT READY`.

## Safety

- Use this only on a TV you own or are explicitly authorised to test.
- Do not kill the parked exploit parent or close its file descriptors. There is
  a `cgroup_sk_free` panic waiting there.
- Do not flip `devicestate` in the U-Boot environment. It can break the
  TEE/keystore, wipe data and boot-loop the TV.
- Do not flash a full zero `misc` image. If repairing, read
  [docs/RECOVERY.md](docs/RECOVERY.md).
- Nothing here bypasses DRM or gives persistent root.

## What is not in this repository

No Sony proprietary firmware, OTA packages, partition images, APKs, JARs,
policy files, keys, DRM/TEE/RPMB material, device dumps or account data. The
tools are source and documentation only; they expect the user to supply the
device and the exact firmware.

## Building

The exploit is built with:

```sh
aarch64-linux-gnu-gcc -static -O2 -o prod_exploit_v21 prod_exploit_v21.c
```

The module is built against the Sony `4.19.260-ab626241` tree with
`CONFIG_MODVERSIONS`:

```sh
make -C <sony-tree> M=$PWD ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- modules
```

The module CRCs are in `module/sony-Module.symvers`. The source and binary
hashes are in [exploit/README.md](exploit/README.md).

## License and disclaimer

MIT. See [LICENSE](LICENSE) and [DISCLAIMER.md](DISCLAIMER.md). This is security
research on owned hardware, published for reference, with no warranty.
