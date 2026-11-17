#!/system/bin/sh
# Executed only by the existing per-boot root command server.
set -eu
ACTION=${1:-status}
SOURCE=${2:-}
BIN=/data/local/tests/x90-refresh
case "$ACTION" in on|off|status) ;; *) echo 'ERROR: invalid refresh action'; exit 1;; esac
if [ "$ACTION" != status ]; then
    if pidof x90-pq >/dev/null || [ -f /data/local/tests/x90-pq-pending.txt ] || [ -e /data/local/tests/x90-preset-pending.txt ] || [ -e /data/local/tests/x90-engine-active ]; then
        echo 'ERROR: finish or restore the picture experiment first'; exit 1
    fi
    mkdir /data/local/tests/x90-display-operation 2>/dev/null || { echo 'ERROR: display operation busy'; exit 1; }
    JOURNAL=/data/local/tests/x90-refresh-operation-$(cat /proc/sys/kernel/random/uuid)
    mkdir "$JOURNAL"
    cleanup(){ result=$?;trap - EXIT;mv /data/local/tests/x90-display-operation "$JOURNAL/completed-lock" || result=1;exit "$result"; }
    trap cleanup EXIT
fi
HASH=$(sha256sum /vendor/lib/hw/hwcomposer.mt5895.so)
case "$HASH" in
 f3e79f85e6a92a2cce33e444acf93d3c59ab43c92b438f96e13b1ce1ccf89e2f*) ;;
 *) echo 'ERROR: this firmware is not supported'; exit 1;;
esac
P=$(pidof android.hardware.graphics.composer@2.4-service)
case "$P" in ''|*' '*) echo 'ERROR: Composer unavailable'; exit 1;; esac
if [ -n "$SOURCE" ]; then
    EXPECTED=44443a57e693925d82f36b55ca2826a18b2452001fed8811a0104608bb2ab1b1
    [ "$(sha256sum "$SOURCE" | cut -d' ' -f1)" = "$EXPECTED" ] || { echo 'ERROR: helper integrity';exit 1; }
    CURRENT=$(sha256sum "$BIN" 2>/dev/null | cut -d' ' -f1) || CURRENT=
    if [ "$CURRENT" != "$EXPECTED" ];then
        # A live daemon must keep its executable identity until explicitly stopped.
        if [ -s /data/local/tests/x90refresh/pid ];then echo 'ERROR: refresh helper update requires idle watchdog';exit 1;fi
        [ -n "${JOURNAL:-}" ] || { echo 'ERROR: helper update needs operation journal';exit 1; }
        cp "$SOURCE" "$JOURNAL/staged-helper";chmod 700 "$JOURNAL/staged-helper"
        if [ -e "$BIN" ];then mv "$BIN" "$JOURNAL/prior-helper";fi
        cp "$JOURNAL/staged-helper" "$BIN";chmod 700 "$BIN"
    fi
fi
if [ ! -x "$BIN" ]; then echo 'ERROR: refresh helper missing'; exit 1; fi
if [ "$ACTION" = status ]; then "$BIN" status "$P"; exit $?; fi
if [ "$ACTION" = off ]; then
    "$BIN" off "$P" || exit $?
    INFO=$(runcon u:r:shell:s0 dumpsys display)
    if echo "$INFO" | grep 'mActiveSfDisplayMode=.*refreshRate=60\.' >/dev/null; then
        echo REFRESH_OK_60
        exit 0
    fi
    echo 'ERROR: stock timing restored; check the active Android mode'
    exit 1
fi
"$BIN" on "$P" || exit $?
sleep 1
runcon u:r:shell:s0 timeout 8 service call SurfaceFlinger 1037
sleep 1
INFO=$(runcon u:r:shell:s0 dumpsys display)
if echo "$INFO" | grep 'mActiveSfDisplayMode=.*refreshRate=120\.' >/dev/null; then
    "$BIN" commit "$P" || { "$BIN" off "$P"; exit 1; }
    echo REFRESH_OK_120
else
    "$BIN" off "$P"
    echo 'ERROR: Android did not activate120Hz; restored60Hz'
    exit 1
fi
