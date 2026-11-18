# Root channel qualification - 2026-11-16

This page records the qualification of the root transport on the test TV. It is
a dated observation, not a claim about any other unit or the current TV state.

## What was tested

- Initial boot `87cbac69`.
- Firmware fingerprint and `dtv_driver.ko` hash matched the brief.
- Health: keeper count 2, SELinux `Permissive`, mailbox free, tools present,
  helper hash OK.
- Acceptance tests AT-1..AT-5:
  - AT-1: Doctor green before and after.
  - AT-2: uid 0 with the exact completion marker and a durable audit line.
  - AT-3: a busy mailbox returns `MAILBOX_BUSY` and the request SHA256 is
    unchanged.
  - AT-4: a 40-second command with a 5-second deadline returns `TIMEOUT`, the
    request is preserved, and it executes exactly once.
  - AT-5: the same command twice produces distinct nonces.
- Long job: returned in 1.385 s; a second root command completed during the
  sleep.
- Rename: the request was retained, the mailbox absent, and the result present.
- Four mailbox traps (`DEDUPE`, `LIMIT`, `BLOCKING`, `RENAME`) were reproduced
  with a test-only publisher.

## Fresh-boot repeat

- Boot `24f3b96e`: one `adb reboot`; automatic root recovered without a manual
  payload run.
- Fresh Doctor green; AT-2..AT-5 repeated and passed.
- Readiness was within the 180-second gate (164 s including reboot/observation
  overhead).
- Boot security: green, vbmeta locked. The boot reason was `kernel_panic` from
  the documented teardown, not an exploit crash.
- The first PQ probe failed because Home was foreground; the raw failure was
  retained and the retry succeeded.
- All 20 exposed PQ keys, source and signal matched the pre-boot baseline.

## Client hardening

The qualified Windows client enforces:

- exact firmware and driver identity on every command;
- a healthy keeper count and Permissive state;
- ADB exit status, including publish and acknowledgement failures;
- a host-side named mutex per TV serial;
- busy recheck, noclobber temporary file and preserve-on-failure;
- a 900-byte wrapper limit even if the caller requests more;
- exact completion-line matching and exit-code propagation;
- digest verification on acknowledgement and rename success;
- refusal of an existing archive target; long jobs detached with `setsid`.

## Remaining limits

- This is a qualified client, not proof that arbitrary commands are safe.
- The keeper is unchanged: 1023-byte read, content dedupe, blocking `waitpid`.
- The mutex covers this host's PowerShell client only.
- Digest-then-rename has a race against an uncooperative external writer.
- There is no automatic retry or exactly-once guarantee across multiple servers.
- ADB subprocess calls have no hard transport deadline.
- The Doctor checks tool presence and device identity, not every helper hash.
- The legacy Bash transport lacks the digest and length checks.

## Revert

The channel hardening changed local files only. The TV keeper, app and picture
configuration were not changed. Root request/result archives and the pushed
test scripts have no boot consumer and need no revert.

## Current state

The TV is in a recovery loop. Root is not available now. After repair, re-run a
fresh qualification; do not reuse old PIDs, addresses or root state.
