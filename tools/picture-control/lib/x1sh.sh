#!/usr/bin/env bash
# Root mailbox helper: run one command through /data/local/tests/x90cmd.
# x1sh.sh - run one command as root via the prod_exploit_v21 mailbox (/data/local/tests/x90cmd).
# Usage: x1sh.sh [-t SECONDS] 'command'     (default timeout 30 s)
# Device-side mailbox: single publisher, timeout, never deletes the completed command.
# Single-slot mailbox: refuses if busy; on success renames x90cmd to *.completed-command.txt (never deletes).
set -u
T=30; [ "${1:-}" = "-t" ] && { T=$2; shift 2; }
CMD=$1
ADB="${ADB:-adb} -s ${TV_SERIAL:-127.0.0.1:5555}"
n=$(date +%s%N | cut -c1-13)
base=/data/local/tests/app-command-x1-$n
out=$base.txt
mark=X1_DONE_$n
script="(id; ($CMD); rc=\$?; printf '\n---- rc=%s $mark\n' \"\$rc\") > $out 2>&1; chmod 644 $out"
busy=$($ADB shell 'if [ -s /data/local/tests/x90cmd ] || [ -e /data/local/tests/x90cmd.tmp ]; then echo BUSY; fi' | tr -d '\r')
[ "$busy" = BUSY ] && { echo MAILBOX_BUSY; exit 1; }
b=$(printf '%s' "$script" | base64 -w0)
$ADB shell "echo $b | base64 -d > /data/local/tests/x90cmd.tmp && mv /data/local/tests/x90cmd.tmp /data/local/tests/x90cmd" >/dev/null
end=$(( $(date +%s) + T ))
while [ "$(date +%s)" -le "$end" ]; do
  t=$($ADB shell "cat $out 2>/dev/null" | tr -d '\r')
  if printf '%s' "$t" | grep -q "$mark"; then
    $ADB shell "[ -e /data/local/tests/x90cmd ] && mv /data/local/tests/x90cmd ${base}.completed-command.txt" >/dev/null 2>&1
    printf '%s\n' "$t"; exit 0
  fi
  sleep 0.4
done
echo TIMEOUT; exit 1
