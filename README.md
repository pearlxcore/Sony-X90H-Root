# Sony X90H research notes

On November 16, 2026, I obtained temporary root on a Sony KD-55X9000H running Android 12, build `626241` (kernel `4.19.260-ab626241`), and repeated the result after a reboot. On November 17, the same TV entered a recovery loop following a separate patched-boot experiment.

- [Temporary root and test results](docs/root-test-results.md)
- [Boot experiments](docs/bootloader.md) and [recovery history](docs/recovery.md)
- [Picture processing and HDMI findings](docs/picture-issues.md)
- [Model compatibility](docs/model-coverage.md): which BRAVIA generations this chain applies to

![Sony X90H](media/x90h.jpg)

The repository includes research notes, tool source, and compiled project artifacts. Sony firmware and device dumps are excluded. The results apply to the tested unit and build.

## Model compatibility

The chain is specific to the **2020 VH1 generation** (X90H / X9000H). Of the four BRAVIA generations examined, only that one ships the kernel feature it uses: the 2018-2020 F/G/H line, the 2021 J series and the 2022 K series either compile it out or fence it off, and the 2023 L series is unmeasured. Read the evidence, the platform names and the one-command check in [model coverage](docs/model-coverage.md).

[Documentation index](docs/README.md) · [MIT license](LICENSE) · [Disclaimer](DISCLAIMER.md)
