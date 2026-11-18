# Which models this root chain applies to

The published chain targets one specific weakness: a perf-event
out-of-bounds in the Linux kernel, reached from an unprivileged ADB shell.
This page records which BRAVIA generations actually expose it, and why.

Labels follow [evidence-policy.md](evidence-policy.md). No exploit detail, no
key material and no firmware is included here.

## Short answer

**Of the four generations examined, only the 2020 VH1 generation exposes the
entry point.** Three later and earlier generations ship with the perf subsystem
compiled out or fenced off.

## What an exploit needs

Three independent things, which fail separately:

1. a bug in the code;
2. that code reachable by the caller (this is configuration, not luck);
3. the memory layout of that exact build (addresses never carry over).

The perf bug itself is present in **every** kernel examined. What differs is
point 2.

## The four generations

| Generation | Platform | Kernel | Perf in the Android product config | This chain |
| --- | --- | --- | --- | --- |
| 2018-2020 F/G/H line | `Uroboros` | 4.9.125 | compiled out, or strict guard | **not applicable** |
| **2020 VH1 (X90H / X9000H)** | `Valhalla` | 4.19.260 | **enabled, no guard** | **applies** |
| 2021 J series | `Valhalla2` | 4.19.75 | compiled out, or strict guard | **not applicable** |
| 2022 K series | `Valhalla2` 2.1 | 4.19.75 | compiled out, or strict guard | **not applicable** |
| 2023 L series | - | not measured | - | unknown |

The platform names are Sony's own, taken from their source-release paths, not
inferred from model numbers:

```
Uroboros_all_2021_0601        F / G / H families (kernel 4.9)
Valhalla2_2021_0618           J series          (kernel 4.19.75)
Valhalla2_2.1_2022_0614       J and K series    (kernel 4.19.75)
```

## How this was established

- The **2020 verdict** is direct: the test unit's own kernel configuration
  reports `CONFIG_PERF_EVENTS=y` with no `SECURITY_PERF_EVENTS_RESTRICT`, and
  the chain roots the unit from a locked state.
- The **other verdicts** come from Sony's published kernel source for each
  generation (Sony's OSS distribution service). In every non-2020 tree, the
  Android product configs either set `# CONFIG_PERF_EVENTS is not set` or set
  `CONFIG_SECURITY_PERF_EVENTS_RESTRICT=y`, which makes
  `sysctl_perf_event_paranoid` default to 3 - "disallow all unpriv perf event
  use".
- The vulnerable function itself (`perf_event_validate_size`) is the same
  pre-fix code in the 4.9.125, 4.19.75 and 4.19.260 trees. Four generations
  share the bug; one shares the exposure.

## What "not applicable" does and does not mean

It means **this chain does not work there**, because the code it uses is absent
or blocked. It does **not** mean the device is secure: other bug classes, other
enabled subsystems and other entry points were not examined. It also does not
mean those devices are unrootable - only that a different route would be
needed, with its own bug and its own per-build address work.

## Check your own unit

One command answers it:

```sh
adb shell cat /proc/sys/kernel/perf_event_paranoid
```

- file missing -> the perf subsystem is not compiled in
- `3` -> all unprivileged perf use is refused
- `2` or lower -> the entry point exists

## Limits

- Build configs are build *inputs*. The three negative verdicts are strong but
  one step removed from any particular television; the 2020 verdict is direct.
- The 2023 L series was not measured.
- A config change could arrive with any firmware update; treat these as
  observations of the releases examined, not permanent properties.
