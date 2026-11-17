# Android 120 Hz helper

This is a guarded way to put the Android UI into its 120 Hz display mode by
patching two instructions in the live `hwcomposer.mt5895.so` mapping. It is not
a panel or GPU overclock. The panel is 120 Hz; the 120/VRR HDMI path is
vendor-side. The Android UI 120 Hz is an HWC mode, not a GPU clock change.

## Files

- `x90-refresh.c` - helper source. Exact VH1 626241 HWC only; restores stock
  instructions on SIGTERM or lost verification.
- `refresh-switch.sh` - on/off/status wrapper: firmware hash, composer
  singleton, helper hash `44443a57...ab1b1`, operation journal, 120 Hz readback,
  commit, restore on failure.
- `RefreshRateController.java` - app-side controller.

## Build

```sh
aarch64-linux-gnu-gcc -static -O2 -o x90-refresh x90-refresh.c
```

The validated device binary was 15,824 bytes, SHA256
`44443a57e693925d82f36b55ca2826a18b2452001fed8811a0104608bb2ab1b1`.

## Use

```sh
refresh-switch.sh on
refresh-switch.sh status
refresh-switch.sh off
```

`off` verifies the active Android mode is back to 60 Hz.

## Safety

- Refuses if a picture experiment or engine session is active.
- Checks the HWC hash `f3e79f85...`.
- Refuses a second composer.
- Keeps an operation journal.
- Touches only the composer process memory; no panel register, clock, block
  device or vendor filesystem write.
- If Android does not activate 120 Hz, it restores 60 Hz.
