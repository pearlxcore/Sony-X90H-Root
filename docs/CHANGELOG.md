# Changelog

## 2026-11-19 - model coverage

- Added [model-coverage.md](model-coverage.md): which BRAVIA generations the
  published root chain applies to. Of the four generations examined, only the
  2020 VH1 generation ships the perf subsystem enabled; the 2018-2020 F/G/H
  line, the 2021 J series and the 2022 K series either compile it out or fence
  it off, and the 2023 L series is unmeasured.
- Basis is stated in the page: the 2020 verdict is the test unit's own kernel
  config; the other three are Sony's published kernel source for each
  generation. No exploit detail, keys or firmware are published.
- Also records the platform names recovered from Sony's source-release paths
  (`Uroboros`, `Valhalla`, `Valhalla2`) so model-to-generation mapping does not
  have to be guessed from model numbers.

## 2026-11-19 - TEE and Keymaster status

- Added [tee-keymaster.md](tee-keymaster.md): offline status notes on the TEE
  image, the two independent secure-world entry paths (Trusty IPC and MTAL
  direct SMC), normal-world key storage, and the model-shared attestation
  bundle.
- States findings and limits only. No key material, cipher parameters, wrapped
  blobs or complete port/command tables are published; the handling section
  explains why. See [evidence-policy.md](evidence-policy.md).

## 2026-11-18 - research expansion

- Split the long root write-up out of `README.md` into
  [root.md](root.md); the top-level README is now an overview and index.
- Added:
  - [root-test-results.md](root-test-results.md)
  - [root-stages.md](root-stages.md)
  - [bootloader.md](bootloader.md)
  - [recovery.md](recovery.md)
  - [picture-controls.md](picture-controls.md)
  - [X1-DEFECTS.md](X1-DEFECTS.md)
  - [USER-CLAIMS.md](USER-CLAIMS.md)
  - [tools.md](tools.md)
  - [evidence-policy.md](evidence-policy.md)
  - [exploit-history.md](exploit-history.md)
- Added technical notes:
  - [root-code.md](root-code.md)
  - [bootloader-technical.md](bootloader-technical.md)
  - [recovery-technical.md](recovery-technical.md)
  - [picture-firmware-analysis.md](picture-firmware-analysis.md)
  - [code-map.md](code-map.md)
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
