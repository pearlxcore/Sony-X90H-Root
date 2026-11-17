# User claims - X90H issues (source material)

**Status:** owner-provided claim summary, not analysis.  
**Compiled by:** ChatGPT, citing RTINGS and owner reports.  
**Verified by this project:** no. The short answer is in
[CLAIM-ANSWERS.md](CLAIM-ANSWERS.md); the detailed verdicts are in
[X1-DEFECTS.md](X1-DEFECTS.md).

This page preserves the claim set that started the investigation, so a reader
can separate the reported symptoms from what the firmware evidence supports.

## Claim summary

| # | Claim | Reported status | Project verdict |
|---|---|---|---|
| 1 | Blurry 4K120 output | Independently verified | CANDIDATE |
| 2 | VRR disables local dimming | Independently verified | PROVEN as firmware policy |
| 3 | HDMI black screen / no signal | Owner reports into 2026 | OPEN |
| 4 | Local-dimming brightness fluctuations | Owner reports | OPEN |

## 1. Blurry 4K120 output

The X900H was marketed with 4K120. Owners reported visibly reduced sharpness at
4K120: blurry PC text, reduced vertical detail, softer game interfaces and HUD
elements, and less noticeable degradation in fast motion.

RTINGS independently tested the TV and found that 4K120 effectively reduced
vertical detail, producing an image comparable to 3840x1080 before scaling. The
claim says the issue persisted through multiple 2020-2021 firmware revisions.

The claim's key distinction: accepting a 3840x2160 at 120 Hz HDMI signal does
not necessarily mean displaying all the original spatial detail. The receiver
can accept the signal while the downstream video pipeline produces reduced
effective resolution. Possible causes listed: video-processing bandwidth,
display-pipeline architecture, memory bandwidth or internal buffering, and
hardware/firmware limits. The precise bottleneck was not established from
public source code.

The claim says root would allow investigation of the MediaTek display-driver
configuration, timing registers, pipeline initialization, scaler configuration,
buffer formats and firmware paths. If the hardware cannot sustain
full-resolution 120 Hz, Android settings would not overcome it.

## 2. VRR disables local dimming

Owners found that enabling Variable Refresh Rate caused the X900H to disable
full-array local dimming: lower contrast, worse black levels, more backlight
glow and a less impressive HDR image. RTINGS documented the behaviour after the
VRR update.

The claim's reasoning: local dimming controls LED zones from the content; VRR
changes the interval between frames; coordinating both requires variable
presentation timing alongside zone-brightness updates. The X900H's documented
implementation sacrifices local dimming in VRR.

The claim says root could allow examination of the local-dimming driver, VRR
mode-switching logic, backlight-controller interfaces, pipeline synchronisation
and firmware restrictions. Forcing local dimming on without correct timing
could produce flicker, delayed brightness updates or instability.

## 3. HDMI black screen / no signal

The claim describes a July 2025 report:

1. the TV detects an HDMI device;
2. selecting the input gives a black screen;
3. no sound is heard;
4. a restart does not always fix it;
5. a factory reset temporarily restores it;
6. the problem returns after some time.

A February 2026 report said removing external USB storage resolved the HDMI
problem. The storage had applications installed and was connected through a USB
hub; other owners reported similar success in July and August 2026.

The claim notes that USB storage should not normally prevent an unrelated HDMI
input from displaying video. Possible areas: Android storage management,
ExternalInputService, resource contention during device initialization,
system-service crashes, USB hub power interactions and MediaTek HDMI
initialization. The exact mechanism was not established.

The project's own black-screen incident was a wrong HDMI input plus a converter
route, not an FRL failure. The two hypotheses remain separate.

## 4. Dolby Vision and 4K120

The claim says the TV requires different HDMI signal modes:

| HDMI configuration | 4K120 | Dolby Vision | VRR |
|---|---|---|---|
| Enhanced Format | Yes | No | No |
| Enhanced Format (Dolby Vision) | No | Yes, up to 4K60 | No |
| Enhanced Format (VRR) | Yes | No | Yes |

Owners must choose between features rather than enabling everything at once.

## 5. Firmware versions in the claim

The claim included this list. It is external and was compiled by ChatGPT; it is
not verified against this unit's OTA history.

| Version | Claimed note |
|---|---|
| v6.0384 | Early HDMI 2.1 implementation and reported instability |
| v6.1288 | VRR introduced |
| v6.1534 | Additional gaming-related changes |
| v6.1772 | Sony acknowledged unexpected restart problems |
| v6.1874 | Released to address the restart issue |
| v6.2302 | Android 10-era firmware |
| v6.2500 | Android 12-era update |
| v6.2556 | Android 12 firmware reported by owners |
| v6.2624 | July 2026 release |
| v6.2646 | November 2026 release |

Do not use this table as evidence for this TV's update history without an OTA
record.

## 6. Current project verdicts

Read [X1-DEFECTS.md](X1-DEFECTS.md) for the evidence.

- Claim 2: **PROVEN as firmware policy**; hardware apply **STRONG**.
- Claim 1: **CANDIDATE**; the 4k1k chain exists, but runtime selection is not
  shown. "Hardware ceiling" is unsupported.
- Claim 3: **OPEN**; FRL link-training and USB/storage starvation are separate
  hypotheses.
- Claim 4: **OPEN**; the active controller on this model is not established.
- NX55 zone count: **OPEN**.
- Dolby Vision + 4K120: **NOT STUDIED**.

This page is the claim input, not the answer.
