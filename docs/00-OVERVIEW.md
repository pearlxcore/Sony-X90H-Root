# 00 - Overview and scope

## Device

- Sony KD-55X9000H / X90H (regional siblings: XBR-55X900H, KD-55XH90xx)
- SoC: MediaTek MT5895
- Platform: Android 12 / BRAVIA VH1
- Firmware: `STT2.230505.001.S137` / `626241`
- Kernel: `4.19.260-ab626241`
- Panel: 3840x2160, 120 Hz

## What this repository is

A research record of:

1. a per-boot root chain from an unprivileged ADB shell;
2. bootloader and security notes: AVB, the U-Boot environment, S11, fastboot,
   `misc`/AB0 and slot handling;
3. recovery analysis: boot flow, post-wipe access, the recovery loop and repair
   options;
4. X1 picture research: the 84-key picture surface, latency, and four public
   defect claims;
5. the first-party tools used to capture, compare and control the TV.

## What it is not

- Not a bootloader unlock.
- Not a persistent root.
- Not a custom kernel or a patched system/vendor image.
- Not a DRM/TEE/keymaster/RPMB bypass.
- Not a universal recovery method.

## How to read the evidence

Claims are labelled:

- **PROVEN** - shown directly by code/data, call chain complete.
- **STRONG** - mechanism found, one link inferred.
- **CANDIDATE** - mechanism exists in code, runtime use not shown.
- **OPEN** - not answerable from the data held.
- **NOT STUDIED** - not investigated.

Every measurement should carry the firmware, input, UI rate, date and
endpoints. A result on one input or one boot is not a general claim.

## Current device state

The test TV is not currently usable. It entered a recovery loop on 2026-11-17
after a patched-boot experiment. The last recorded state was: Sony logo, then a
frozen "do not power off / updating" screen; no ADB, no ping, no USB
enumeration; `misc` still carrying `boot-recovery`; `boot_a` stock-or-patched,
unknown. The repair path is eMMC ISP or a main-board swap. See
[RECOVERY.md](RECOVERY.md).

The root and bootloader results in this repository are dated observations from
before that incident. They are not a claim about the current TV state.

## Public/private boundary

Published: write-ups, measurements, source code, hashes and selected tools.

Not published: firmware, OTA packages, partition images, APKs/JARs, vendor
modules, keys, DRM/TEE/RPMB material, device dumps, serials, MACs, live IPs and
account data. See [EVIDENCE-POLICY.md](EVIDENCE-POLICY.md).

## Safety

Read the document for the subsystem before running anything. The TV can crash,
wipe data or stop booting. The tools are not a product and have no warranty.
