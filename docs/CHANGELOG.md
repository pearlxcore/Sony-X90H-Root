# Changelog

## 2026-11-18 - research expansion

- Split the long root write-up out of `README.md` into
  [ROOT.md](ROOT.md); the top-level README is now an overview and index.
- Added:
  - [ROOT-QUALIFICATION.md](ROOT-QUALIFICATION.md)
  - [ROOT-STAGES.md](ROOT-STAGES.md)
  - [BOOTLOADER.md](BOOTLOADER.md)
  - [RECOVERY.md](RECOVERY.md)
  - [X1-PICTURE.md](X1-PICTURE.md)
  - [X1-DEFECTS.md](X1-DEFECTS.md)
  - [USER-CLAIMS.md](USER-CLAIMS.md)
  - [TOOLS.md](TOOLS.md)
  - [EVIDENCE-POLICY.md](EVIDENCE-POLICY.md)
  - [EXPLOIT-HISTORY.md](EXPLOIT-HISTORY.md)
- Added technical notes:
  - [ROOT-CODE.md](ROOT-CODE.md)
  - [BOOTLOADER-TECHNICAL.md](BOOTLOADER-TECHNICAL.md)
  - [RECOVERY-TECHNICAL.md](RECOVERY-TECHNICAL.md)
  - [X1-TECHNICAL.md](X1-TECHNICAL.md)
  - [CODE-MAP.md](CODE-MAP.md)
- Added tools:
  - `tools/bootloader/uboot_env.py` (U-Boot env parser/CRC)
  - `tools/recovery/misc_patch.py` (surgical `misc` BCB clear)
- Added selected first-party tools under `tools/`.
- Verified the exploit source is the original v21:
  - source blob `963f74d4...`, 47,942 bytes;
  - rebuild with `aarch64-linux-gnu-gcc -static -O2` reproduces the prebuilt
    `a19d7b81...`, 707,216 bytes;
  - `x90mod_sony.ko` `93a45574...` and `x90mod_sony.img` `a4573d1e...` are
    unchanged.
- Added a reproducible-build/hash check for the exploit.
- No exploit code change. Main is frozen at v21.

## 2026-10-28 - initial release

- Root chain, module source, prebuilt payload and module image.
- Initial README with the root workflow, technical details and warnings.

## 2026-10-28 to 2026-11-13 - README edits

- Author-section removal and a small README update. The exploit source was not
  changed in any of these commits.
