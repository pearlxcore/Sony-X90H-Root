# Tools

These are first-party tools from the research. They are not products. Read the
README in each directory and the linked document before running anything.

Risk labels:

- **READ-ONLY** - reads device state or host files.
- **MUTATING** - changes display, picture, module state or a process.
- **ROOT/REPAIR** - needs root, writes boot/recovery state, or can brick.
- **HOST-ONLY** - runs on the PC and touches local files only.

## Display and capture

| Tool | Purpose | Risk | Needs |
|---|---|---|---|
| [x90shot](../tools/display/x90shot/) | Native 3840x2160 still capture of the final panel output through the two OSTG pixel monitors; includes a same-frame A/B mode | MUTATING | Root, exact firmware, `x90shot.ko` build |
| [hdmi-view](../tools/display/hdmi-view/) | HDMI input viewer/screenshot through the vendor capture path | MUTATING | Root, exact firmware, capture module |
| [split](../tools/display/split/) | Live split-screen A/B: Sony on the left, custom engine on the right | MUTATING | Root, engine bundle/APK, exact firmware |
| [refresh-120hz](../tools/display/refresh-120hz/) | Android UI 120 Hz through a guarded HWC patch; restores 60 Hz | MUTATING | Root, exact firmware, composer running |

`x90shot` is a still capture: it samples points across many refreshes. Use it on
static content. `hdmi-view` returns the decoder picture (696x464 in the tested
path), not the 4K panel. `split` compares two paths on the same screen; it is
not a product feature. `refresh-120hz` changes the Android display mode, not the
panel or GPU clock.

## Picture control

| Tool | Purpose | Risk | Needs |
|---|---|---|---|
| [picture-control](../tools/picture-control/) | X90H Picture Control v0.2.0: 84 keys, 82 settable, snapshot/restore, routes, latency | MUTATING | Root, exact firmware, active input |

Read [X1-PICTURE.md](X1-PICTURE.md). Settings are per input; snapshot
before changing. Do not issue `sopq...get_all`.

## Recovery and analysis

| Tool | Purpose | Risk | Needs |
|---|---|---|---|
| [recovery](../tools/recovery/) | Read-only boot-flow, first-boot, wipe and USB-storage analysis | READ-ONLY | A booted TV for acquisition; host for analysis |
| [capture](../tools/capture/) | X90CAP2 capture, cadence/frame diagnostics, `x90-pq` source | MUTATING | Root, capture module |
| [analysis](../tools/analysis/) | Logcat `MtkTvConfig` parser; other firmware-specific helpers are private | HOST-ONLY | A logcat dump |
| [modules](../tools/modules/) | Sources for experimental modules and the MTAL ioctl client | MUTATING | Exact kernel/tree |

The recovery tools never reboot or repair. The analysis tools are host-side.
The module sources are reference material; some are marked DO-NOT-LOAD.

## What is not included

- Compiled experimental kernel modules (build them from source).
- Sony vendor modules, APKs, JARs, firmware, policy files, keys or dumps.
- The custom-engine APK/bundle. The split runner and engine source are included;
  the full Android build harness is not.
- The HDMI runtime asset bundle (it contains device modules and binaries).

## A note on versions

The tools were written against firmware `626241` and often check a SHA256 of
`dtv_driver.ko` or `hwcomposer.mt5895.so` before acting. Do not bypass those
checks or reuse a tool on another build. A mismatch should stop you.

