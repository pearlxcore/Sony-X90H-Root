# X1 defect claims - closing audit

This is a static analysis of four public X90H/X9000H defect claims against the
firmware held for this unit. It is not a panel measurement. The TV was already
in a recovery loop when the audit was written, so no live test was possible.

Firmware: `STT2.230505.001.S137` / `626241`.

## Labels

- **PROVEN** - shown directly by code/data, call chain complete.
- **STRONG** - code mechanism found, one link inferred.
- **CANDIDATE** - mechanism exists in code, runtime use not shown.
- **OPEN** - not answerable from the data held.
- **NOT STUDIED** - not investigated.

## Claim 2 - VRR disables local dimming -> PROVEN as firmware policy

The cause is a deliberate, generation-gated firmware policy, implemented twice.

- Native MediaTek app layer (`libmtkapp`, source file `gray_out.c`):
  `a_menu_is_led_dynamic_control_enable` returns false for a restricted
  backend video path, and for `platform_series_type() > 6` it returns without a
  VRR check. For Valhalla and earlier it checks `a_cfg_cust_get_vrr_status()`
  and returns false when VRR is on. The gray-out table wires this to
  `g_video__led_dynamic_ctl` (Sony "LED dynamic control" = Auto Local Dimming).
- Android settings UI (`DirectSettings`): `AutoLocalDimmingMenuItem` disables
  the item when the current input is VRR and `isVrrHighContrastSupported()` is
  false. `isValhalla` matches segment ids 17, 18, 19, 30; this unit is segment
  NX (17), so the menu is disabled.
- VRR also switches to a separate picture slot (`g_video__ssa_pic_mode`) and
  writes VRR status through SonyVendorService.

The remaining inference is **STRONG**, not PROVEN: that a greyed-out
`g_video__led_dynamic_ctl` is applied to hardware as Off. RTINGS measured LD off
in VRR, consistent with it, but the ACFG "disabled item -> applied value" rule
was not traced.

Why Sony did it is not in the code. It is a per-generation switch, not a
demonstrated silicon limit. Forcing it would bypass a vendor guard.

## Claim 1 - blurry 4K120 (vertical detail ~3840x1080) -> CANDIDATE

- The 4K-HFR HDMI route is a Sony policy table. `bDrvIs4KHFRToPQPath` reads
  `u4HdmiSigPath[4]`; the value selects MainPQ or Bypass per HDMI/VRR state.
- `sony_pqctrl` sets the table from an 8x4 table: rows 0-6 are `00 00 00 00`
  (MainPQ), row 7 is `01 00 01 00` (4K-HFR bypass). Row 7 also needs the BYPASS
  flag, the MVI "4KBE" bit and a runtime flag.
- The model-variation data has `0x100A5 = 0x00` for every KD-55/65/75/85X9000H
  and X9077H SKU. On this model the 4K-HFR route is MainPQ by default, not the
  bypass path. The earlier "main engine bypassed at 4K120" claim is
  contradicted.
- The fuse gate does not remove MEMC on this panel: when the 120 MJC support bit
  is absent, `vDrvMJCInit` asks for bypass and MEMC mode 3; on a 4K120 panel
  case 3 logs "4K120 cannot bypass memc" and returns without change. The fuse
  bit on this unit is unknown.
- A complete 3840x1080 "4k1k" chain exists in the driver: `vPds4k1k` halves the
  vertical pre-down registers, `vFscTG4k1k` halves Vtotal/height/porches, MEMC
  modes 4/5/7 use `LineWidthHeight(3840,1080)` and `MFC_3D_2D_4K1K_*` formats,
  and `vPsc4k1k` halves the post-scaler input height and doubles with
  `VDeltaPhase 0x80000`. That is exactly "3840x1080 then upscaled".
- But in the ARM code the PDS/FSC/PSC 4k1k calls are reached only from a debug
  CLI handler (data-referenced only), and MEMC base modes 4/5 only from
  `vMjcSetMemcMode` / `MjcModeFix`, which are set by a test-pattern command.
  MEMC also runs firmware on its own R2 coprocessor in opaque blobs.

Verdict: the artefact matches a 4k1k processing mode the silicon/driver
implements, but no static path shows it being selected for 4K120 HDMI in normal
use. The cause is not proven. "Hardware ceiling, unfixable" is unsupported.

Decisive test: at 4K120 vs 4K60, capture the MJC/SCPOS mode log lines and read
the PSC input-height and PDS registers (read-only).

## Claim 3 - HDMI black screen / no signal -> OPEN

- FRL lock-status readers and `SIGDET TimeOut[FRL]` are real diagnostics, but
  generic failure handling does not identify which fault an incident had.
- The project's own incident was a PS2/converter route recovered by an app reset
  and the correct HDMI input; the backend cause was not localised and it was not
  an HDMI 2.1 FRL case.
- The USB/storage link is a hypothesis only. The cited dmesg errors were USB
  gadget/ADB, not host mass storage. The packages.xml dump predates the later
  USB adoption.

Needed: synchronized logcat (TvInput/session/surface), dmesg (FRL/SIGDET),
vold/storage state during failure and after recovery, with and without the
adopted USB volume.

## Claim 4 - local-dimming brightness fluctuation -> OPEN

- Content-adaptive controllers exist: the DBC4PS state machine (histogram
  classes, Darker/Brighter/Target stepping, hysteresis), MediaTek LD temporal
  filters, and Sony TM/QHB backlight sequencing.
- Which controller is active on this model is not established. Sony logs
  "Disable MstarLD"; `pq_func_743` branches on panel LED type 4 / 1 / "Not PWM
  LD or GL Type". Normal adaptive dimming changes brightness by design.
- No trace links a controller to an abnormal pumping defect, and no
  firmware-to-firmware comparison exists. LDDA field names are not established.

## Still open

- NX55 physical zone count: **OPEN**. No static source found.
- DV + 4K120 exclusivity: **NOT STUDIED**. EDID/mode policy was not traced.

## What not to claim

- Do not call claim 1 a proven hardware ceiling.
- Do not call claims 3 or 4 fixed, caused, or impossible.
- Do not generalize a result from another input, firmware or panel.
- Do not describe the VRR policy as a silicon limit; it is a firmware policy
  whose reason is not in the code.
