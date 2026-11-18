# Root stage map - S0 to S16

This is a compact map of the root and exploitation work. It separates what was
achieved from what is closed, blocked or only proven in QEMU.

## Achieved

| Stage | Result |
|---|---|
| S0 | Recon: firmware 626241, kernel `4.19.260-ab626241`, AVB green, SELinux enforcing; source, config and images collected. |
| S1 | Unprivileged entry: `adb shell` = uid 2000, `u:r:shell:s0`; groups include `uhid`, `input`, `inet`. |
| S2 | Surface: `/dev/uhid`, `/dev/uinput`, `/dev/mali0`, `/dev/ion`, `/dev/binder`; `perf_event_paranoid = -1`; `/dev/cli` closed. |
| S3 | QEMU lab: CVE-2023-6931 perf group-read OOB, fake cred, post-free interior-pointer repair. |
| S4 | TV shell root: uid 0 in `u:r:shell:s0`; parked parent and mailbox. |
| S5 | SELinux blocker analysis: no `setenforce`, no `load_policy`, no writable `system_file`, no kexec/devmem. |
| S6 | Init domain: property-service client `sk_security`; fake cred points at `sksec - 4`; repair send writes `fake2`; `setresuid` materialises a real `task_security_struct` with `sid = init`. |
| S7 | Ring-0: Sony-source `x90mod` module, vendor_file image, loop device, `finit_module` with the `selinux_state` address. |
| S8 | SELinux permissive: module writes `disabled = 0`, `enforcing = 0`. |
| S9 | Root server: uid 0 in `u:r:init:s0`, polls `/data/local/tests/x90cmd`, runs `/system/bin/sh -c`, logs to `x90log`. |

## Closed or blocked

| Stage | Status |
|---|---|
| S10 | **BLOCKED.** No root boot consumer of shell-writable data or shell-settable persistent property was found; boot modules load from read-only partitions. This is a bounded scan, not a universal impossibility. |
| S11 | **CLOSED.** The env edit produced orange/unlocked but skipped official provisioning; the TEE/keymaster path broke, and setup appeared on normal power-on after the reset. Not usable. |
| S12 | **NO-GO / reverted.** `disabled = 1` makes task contexts `unlabeled` and destabilises the TV; `enforcing = 0` already gives practical root. |
| S13a | **ACHIEVED.** Server-only keeper survives a parent kill. |
| S13b | **NOT ACHIEVED.** All-protocol cleanup panics in `cgroup_sk_free`; do not unload the module or run cleanup on the daily driver. |
| S14 | **DONE.** App re-root, completion-aware command, server-only detection. Not automatic at boot. |
| S15 | **PARTIAL / QEMU-ONLY.** F004 deterministic UAF, no arbitrary write or TV payload; F001 UAF confirmed, in-object `task_struct` write partially verified; root unverified. |
| S16 | **BLOCKED.** Persistent `su` and stealth depend on S10/S11. |

## What this means

- Root is a runtime capability, not persistence.
- The safest stable point is the per-boot chain plus the qualified channel.
- Do not retry S11, do not unload the module, and do not treat the QEMU-only
  work as a TV exploit.
