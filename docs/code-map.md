# Code map

Where the code lives and what each piece does.

## Root chain

| Path | What it is |
|---|---|
| `exploit/prod_exploit_v21.c` | The v21 chain: perf OOB, fake cred, init domain, module load, command server. |
| `exploit/README.md` | Source of truth, build, hashes, change policy. |
| `exploit/SHA256SUMS` | Source/prebuilt/module/image hashes. |
| `module/x90mod.c` | SELinux-state module: writes `disabled=0`, `enforcing=0`. |
| `module/Makefile` | Module build. |
| `module/sony-Module.symvers` | CRCs for the Sony kernel. |
| `prebuilt/prod_exploit_v21` | Original payload, 707,216 bytes. |
| `prebuilt/x90mod_sony.ko` | Original module. |
| `prebuilt/x90mod_sony.img` | 8 MiB ext4 image with the module. |
| `tools/verify-exploit.sh` | Source hash + reproducible build check. |

Key functions in `prod_exploit_v21.c`:

- `leak_slide()` - KASLR slide from perf IP samples.
- `leak_tsec()` - shell `task_security_struct` pointer.
- `leak_peer_sksec()` - property socket `sk_security_struct`.
- `calibrate_counter()` - exact `sk_write_space` increment.
- `build_fake()` / `build_fake2()` - fake cred layouts.
- `build_nlrep()` - netlink repair payload.
- `copy_image()` / loop / `finit_module` - module load.
- `init_server()` - init-domain command server.

## Bootloader and recovery

| Path | What it is |
|---|---|
| `tools/bootloader/uboot_env.py` | Parse and CRC-check `uboot_env_a`; optional write to a new file. |
| `tools/recovery/misc_patch.py` | Surgical `misc` BCB clear; preserves AB0 at `0x800`. |
| `tools/recovery/observe_boot.py` | Read-only boot snapshot/watch. |
| `tools/recovery/acquire_*.py` | Bounded ADB/boot/first-boot acquisition. |
| `tools/recovery/analyze_boot.py` | Post-process a boot capture. |
| `tools/recovery/map_usb_storage.py` | Adopted USB/OTG mapping. |
| `docs/bootloader-technical.md` | U-Boot env, AVB, misc, fastboot gating. |
| `docs/recovery-technical.md` | Recovery rc, BCB patch, offsets, post-wipe. |

## Display tools

| Path | What it is |
|---|---|
| `tools/display/x90shot/` | Native panel still capture + A/B compare. |
| `tools/display/x90shot/x90shot.c` | OSTG pixel-monitor kernel module. |
| `tools/display/x90shot/x90shot_img.py` | Tile assembly/pattern validation. |
| `tools/display/x90shot/x90shot_cmp.py` | Bounds/sharpness/grid/tone metrics. |
| `tools/display/hdmi-view/hdmi_view.py` | HDMI input viewer. |
| `tools/display/split/eng_split.sh` | Split-screen A/B runner. |
| `tools/display/split/engine/X90FieldEngine.java` | Experimental engine source. |
| `tools/display/refresh-120hz/x90-refresh.c` | HWC 120 Hz helper. |

## X1 picture control

| Path | What it is |
|---|---|
| `tools/picture-control/x1` | Host CLI. |
| `tools/picture-control/lib/x1-device.sh` | Device worker. |
| `tools/picture-control/lib/x1sh.sh` | Root mailbox helper. |
| `tools/picture-control/tools/PQTool.java` | `MtkTvConfig` reflection tool. |
| `tools/picture-control/x1-spec.json` | 84-key control surface. |
| `docs/picture-firmware-analysis.md` | Control path, policy code, 4k1k, DBC4PS. |

Key functions:

- `a_menu_is_led_dynamic_control_enable @0x1948d0` - VRR/LD gray-out gate.
- `pq_func_921` - VRR panel state (`tdcon` 0x27/0x8a).
- `pq_func_958` - panel LUT update (`ad_on`, `tdcon`).
- `FUN_00198730` - 8x4 MainPQ/Bypass table.
- `pq_func_486` - DBC4PS backlight state machine.
- `vDrvMJCInit` - MJC/efuse gate.
- `vDrvCalPanelTiming` - DDDS closed/open loop.

## Build and verify

```sh
# exploit
cd exploit
aarch64-linux-gnu-gcc -static -O2 -o prod_exploit_v21 prod_exploit_v21.c
cd ..
./tools/verify-exploit.sh

# module
make -C <sony-tree> M=$PWD ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- modules

# Python syntax
find tools -name '*.py' -exec python -m py_compile {} +
```

## What is not included

- Sony firmware, OTA packages, partition images.
- Vendor modules (`dtv_driver.ko`, `sony_pqctrl.ko`, `libmtkapp.so`).
- APKs/JARs, model CSVs, SREG/recipe/panel data.
- Keys, DRM/TEE/RPMB material, device dumps.
- The full private research tree and its evidence.

The public code is the first-party tooling and the root chain. The private
evidence stays with the owner.
