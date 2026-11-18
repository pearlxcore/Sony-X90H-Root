# Disclaimer

This is security research on hardware I own. It is published for reference.

- Use it only on devices owned or explicitly authorised to test.
- It can crash, wipe data, or brick the TV. There is no warranty.
- It does not bypass DRM and does not give persistent root. It records an
  unofficial bootloader `devicestate` edit (S11) after which the bootloader
  reported `unlocked` / `orange`. That is not vendor unlock provisioning, it
  broke the TEE/keymaster path, and it is closed. Do not repeat it.
- The bootloader `devicestate` experiment is closed. Do not repeat it.
- The test TV entered a recovery loop on 2026-11-17. Read
  [docs/recovery.md](docs/recovery.md) before assuming any recovery method.
- Some tools load kernel modules, change picture/display state, or write to
  device files. They are research tools, not products.
- No Sony proprietary firmware, keys, APKs, policy files or device dumps are
  included in this repository.
- Check local laws.
