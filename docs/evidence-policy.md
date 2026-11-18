# Evidence policy

## What is published

- Write-ups and measurement summaries.
- Source code for first-party tools.
- SHA256 hashes of the payload, module, image and tool sources.
- Reproduction commands and toolchain versions.
- Analysis of shipped firmware behaviour, without shipping the firmware.

## What is not published

- Sony firmware, OTA packages, `boot.img`, `vbmeta`, system/vendor images.
- Vendor kernel modules (`dtv_driver.ko`, `sony_pqctrl.ko`, `libmtkapp.so`).
- APKs, JARs, model-variation CSVs, panel calibration, SREG/recipe data.
- Keys: signing, ADB, DRM, TEE/RPMB, keymaster, account data.
- Full device dumps, eMMC images, backups, serials, MACs, live IPs.
- Operational ledgers and logs with host paths or personal data.

If a document needs an address or a hash to make a point, it uses the address or
hash, not the binary or the dump.

## Claim labels

- **PROVEN** - shown directly by code/data, call chain complete.
- **STRONG** - mechanism found, one link inferred.
- **CANDIDATE** - mechanism exists in code, runtime use not shown.
- **OPEN** - not answerable from the data held.
- **NOT STUDIED** - not investigated.

A claim without an address, file:line or evidence path does not leave draft.

## Measurement rules

Every measurement should state:

- model and firmware;
- input and source format;
- UI refresh rate;
- endpoints (stimulus and sensor);
- date and report title;
- the number of runs, if it varied.

Do not generalize one input, one boot or one scene to the whole TV. Do not
describe a still-image comparison as a video-quality result.

## Publication boundary

A file cited in a local report is not automatically cleared for wholesale
copying. This repository is an index- and report-level publication: the prose
and the small tables are public; the raw evidence, firmware and keys remain
private. Third-party code keeps its own license.
