# X1 picture control and defect claims - technical notes

This page holds the code and register-level detail behind
[X1-PICTURE.md](X1-PICTURE.md) and [X1-DEFECTS.md](X1-DEFECTS.md).

## 1. The 84-key control surface

The TV exposes picture settings as `g_video__*` keys through MediaTek's
`MtkTvConfig` API. The kit uses reflection in the `sony_platform_app` context:

```java
Class<?> c = Class.forName("com.mediatek.twoworlds.tv.MtkTvConfig");
Object cfg = c.getMethod("getInstance").invoke(null);

Method gv = c.getMethod("getConfigValue", int.class, String.class);
Method gd = c.getMethod("getConfigDefaultValue", String.class);
Method gm = c.getMethod("getMinMaxConfigValue", String.class);
Method mn = c.getMethod("getMinValue", int.class);
Method mx = c.getMethod("getMaxValue", int.class);
Method sv = c.getMethod("setConfigValue", String.class, int.class);

int cur = (Integer) gv.invoke(cfg, -1, "g_video__color_temperature");
int def = (Integer) gd.invoke(cfg, "g_video__color_temperature");
int packed = (Integer) gm.invoke(cfg, "g_video__color_temperature");
int lo = (Integer) mn.invoke(null, packed);
int hi = (Integer) mx.invoke(null, packed);
int rc = (Integer) sv.invoke(cfg, "g_video__color_temperature", 3);
```

`-1` means the active input. A 4-argument `setConfigValue(int, String, int,
int)` form is used when an explicit input group is needed. The full source is
`tools/picture-control/tools/PQTool.java`.

The device worker wraps the same calls:

```sh
sh /data/local/tests/x1kit/x1-device.sh get
sh /data/local/tests/x1kit/x1-device.sh minmax g_video__gamma
sh /data/local/tests/x1kit/x1-device.sh set g_video__gamma 2
sh /data/local/tests/x1kit/x1-device.sh set g_video__gamma 2 3
```

`x1-spec.json` is the machine-readable surface. Each key entry looks like:

```json
{
  "key": "g_video__color_temperature",
  "group": "color",
  "type": "range",
  "min": 0,
  "max": 4,
  "default": 1,
  "current_at_sweep": 1,
  "persist": true,
  "settable": true
}
```

The spec has 84 keys, 82 settable, 12 key-bearing groups (11 settable plus
`meta_readonly`). The private key sweep is not included.

## 2. VRR and local-dimming policy

### Native MediaTek app layer

`libmtkapp` `gray_out.c` contains
`a_menu_is_led_dynamic_control_enable @0x1948d0`. In pseudocode:

```c
int a_menu_is_led_dynamic_control_enable(void)
{
    if (restricted_be_video_path())
        return 0;

    if (a_plf_opt_get_platform_series_type() > 6) {
        log("Valhalla 2 or later project will not check VRR status");
        return 1;
    }

    log("Valhalla and project before Valhalla will check VRR status");
    if (a_cfg_cust_get_vrr_status() == 1) {
        log("VRR status is ON, return false");
        return 0;
    }
    return 1;
}
```

The gray-out table wires this to `g_video__led_dynamic_ctl`, Sony's "LED
dynamic control" (Auto Local Dimming).

### Android settings

`DirectSettings` `AutoLocalDimmingMenuItem.isActualEnabled()`:

```java
if (currentInput.isVrr() && !ProductInfoProvider.isVrrHighContrastSupported())
    return false;  // "Feature not available when VRR is enabled"
```

`isVrrHighContrastSupported() = !isValhalla(segment)`; Valhalla is segment
{17, 18, 19, 30}. This unit is segment NX (17).

### Kernel panel path

`sony_pqctrl` `pq_func_921` (`sopq_ext_frc_panel_vrr_state_change`) in
pseudocode:

```c
if (!enabled || cfg(0x210) != 1 || cfg(0x7f6) != 0x27)
    return;

m = cfg(0x672);                 /* VRR mode */
if (m < 2)
    tdcon = 0x27;
else if (m == 2)
    tdcon = 0x8a;
else {
    log("Unknown VRR mode");
    return;
}
*(panel_state + 2) = tdcon;
pq_func_958();  /* uses the model/lut_num/ad_on/tdcon state */
```

The decompiled helper is described as
`calc_panel_control_update_lut(model, lut_num, ad_on, tdcon)`. It switches on
the TCON model and reprograms the panel LUT when `lut_num` or `ad_on` changes.
`ad_on` is byte 1 of its log; `tdcon` is byte 2.

The earlier RCA said `pq_func_921` wrote `ad_on`; it writes `tdcon`. That
correction matters for the current verdict.

## 3. Claim 1 - the 4K-HFR route and 4k1k

### Route table

`sony_pqctrl` `FUN_00198730` reads an 8x4 table at `0x28d7bc`. Rows 0-6 are
`00 00 00 00` (MainPQ); row 7 is `01 00 01 00` (4K-HFR bypass). Row 7 also
needs the BYPASS flag, the MVI "4KBE" bit and a runtime flag.

The MVI data has `0x100A5 = 0x00` for every KD-55/65/75/85X9000H and X9077H
SKU. On this model, 4K-HFR is MainPQ by default.

### MJC efuse gate

`vDrvMJCInit` reads a capability bit:

```c
if (!IS_Support120MJC() &&
    PANEL_GetPixelClk60Hz() > 1000000000 &&
    PANEL_GetPanelWidth() == 3840) {
    MJC_IsLog("4K120hz MJC bypss by EFUSE.");
    vDrvMJCBypassOnOff(5, 1);
}
```

`IS_Support120MJC` reads bit 3 of `tv_reg_base+0x86c4`; the adjacent 60-MJC
capability bit is in the same register. The same function asks for MEMC mode 3;
on a 4K120 panel case 3 logs "4K120 cannot bypass memc" and returns without
change. The fuse bit on this unit was inferred, not measured.

### 4k1k chain

The driver contains:

- `vPds4k1k` - half the vertical pre-down registers;
- `vFscTG4k1k` - half Vtotal, panel height, vsync porches;
- MEMC modes 4/5/7 - `LineWidthHeight(3840,1080)` and
  `MFC_3D_2D_4K1K_*` formats;
- `vPsc4k1k` - half the post-scaler input height, `VDeltaPhase 0x80000`.

But the ARM-side 4k1k calls are reached only from a debug CLI handler, and MEMC
base modes 4/5 only from a test-pattern command. No static path shows the mode
being selected for 4K120 HDMI.

## 4. Claim 4 - DBC4PS backlight stepping

`pq_func_486` (`calc_dyn_pic_dbc4ps_main`) in pseudocode:

```c
build Y/saturation/maxRGB histograms;
classify "Full Black" vs "Normal" from Conditions 1..7;
state = calc_dyn_pic_dbc4ps_state_transition_and_backlight_control();

switch (state) {
case Darker:
    BL = cur + downStep(param + 7);
    break;
case Brighter:
    BL = cur - upStep(param + 6);
    break;
case Target:
    BL = snap_toward(target);
    break;
}
BL = clamp(BL, 0, 255);
write BL[2];
```

A hysteresis counter can suppress a step. The thresholds and step sizes come
from the Sony recipe. The active controller on this model is not established:
Sony logs "Disable MstarLD", and `pq_func_743` branches on panel LED type 4 / 1
/ "Not PWM LD or GL Type".

## 5. Measurement code

The X1 kit can snapshot and restore the 82 settable keys:

```sh
./x1 snapshot mine
./x1 set g_video__color_temperature 3
./x1 restore mine
```

The same-frame panel comparison is in `tools/display/x90shot`:

```sh
./x90shot ab NAME strip 1888 960
```

The split runner is `tools/display/split/eng_split.sh`.

## 6. Evidence

- `docs/X1-DEFECTS.md` - the current verdicts.
- The full private research tree (not published) holds the longer analysis and
  the raw evidence.
- The public repo intentionally does not include firmware, APKs, JARs, SREG
  dumps, recipe data or the vendor modules.
