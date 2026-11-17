# User claim answers

Current verdicts for the four X90H/X9000H claims, as of 2026-11-18. This is the
short answer page; the evidence and code are in the linked documents.

The owner's claim input is in [USER-CLAIMS.md](USER-CLAIMS.md). The earlier
Claude RCA is superseded; this page follows the later closing audit.

## The four claims

| # | Claim | Answer | Basis |
|---|---|---|---|
| 1 | Blurry 4K120 output | **CANDIDATE** | A 3840x1080 "4k1k" chain exists and the 4K-HFR route is MainPQ, but no static path shows 4k1k being selected for 4K120 HDMI in normal use. The cause is not proven. "Hardware ceiling, unfixable" is unsupported. |
| 2 | VRR disables local dimming | **PROVEN as firmware policy**; hardware apply **STRONG** | `libmtkapp` `a_menu_is_led_dynamic_control_enable @0x1948d0` returns false when VRR is on for Valhalla-and-earlier; `DirectSettings` applies the same Valhalla/NX gate. The ACFG disabled-item-to-hardware rule was not traced. |
| 3 | HDMI black screen / no signal | **OPEN** | Two hypotheses: FRL link-training failure, and Android input-service starvation under USB/storage churn. No synchronized incident log identifies which one occurred. |
| 4 | Local-dimming brightness fluctuation | **OPEN** | DBC4PS and FALD temporal filters exist, but the active controller on this model is not established. The earlier pParam/zone trace was wrong. |

Also:

| Item | Answer |
|---|---|
| NX55 physical zone count | **OPEN** - the PDAT panel dataset is encrypted; a live register read is needed |
| Dolby Vision + 4K120 | **NOT STUDIED** - EDID/mode policy was not traced |

## What would close each one

- **Claim 1:** capture MJC/SCPOS mode logs at 4K120 vs 4K60 and read the PSC
  input-height and PDS registers (read-only). If 4k1k is selected, the mechanism
  is proven; if not, the blur is downstream in the TCON/FRC or panel.
- **Claim 2:** raise the LD/panel log levels, toggle VRR, and capture
  `g_video__led_dynamic_ctl`, `DRVCUST_SetLDEn`, and the panel `ad_on`/`tdcon`
  values. A panel measurement would prove the hardware apply.
- **Claim 3:** synchronized logcat (TvInput/session/surface), dmesg
  (FRL/SIGDET), and vold/storage state during failure and after recovery, with
  and without the adopted USB volume.
- **Claim 4:** capture DBC/LD/BL logs on a known flicker scene and record the
  LED control number, LD enable and temporal-filter parameters.
- **NX55 zones:** read the LED control-number register live, or decode the
  encrypted PDAT panel dataset.
- **DV + 4K120:** trace the EDID/mode policy.

## Where the detail is

- [X1-DEFECTS.md](X1-DEFECTS.md) - claim-by-claim analysis, labels and limits.
- [X1-TECHNICAL.md](X1-TECHNICAL.md) - code and register-level detail:
  gray-out policy, `pq_func_921`/`pq_func_958`, 4k1k/MJC, DBC4PS.
- [USER-CLAIMS.md](USER-CLAIMS.md) - the owner's claim input.
- [EVIDENCE-POLICY.md](EVIDENCE-POLICY.md) - what is public and how measurements
  are labelled.

## What not to claim

- Do not call claim 1 a proven hardware ceiling.
- Do not call claim 2 "fixed" or "unfixable"; it is a firmware policy, and the
  reason is not in the code.
- Do not call claims 3 or 4 resolved.
- Do not generalize one input, firmware, panel or boot to another.
- Root alone does not fix any of the four.
