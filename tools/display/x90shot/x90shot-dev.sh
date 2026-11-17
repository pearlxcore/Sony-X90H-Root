#!/system/bin/sh
# x90shot device worker (run as root via the mailbox). 2026-11-16.
#   start TAG X0 Y0 W H STEP PAIR [LIMIT_S]   load x90shot.ko (returns at once)
#   status                                    print parameters/status
#   finish                                    rmmod (waits for the capture thread), release lock
#   pattern SECONDS | pattern-stop            validation Surface on/off
# Guards: exact dtv_driver + module hashes, single capture at a time (mkdir lock),
# refuses if the module is already loaded. Never touches PQ/panel/clock/DMA/TEE.
set -u
D=/data/local/tests/x90shot
KO=$D/x90shot.ko
KO_SHA=@KO_SHA@
DRV_SHA=97738790f18837a41cc1fad9da11657210b5462d29882e272928a8187f9b7125
act=${1:-status}; shift || true
case "$act" in
start)
  [ $# -ge 7 ] || { echo "usage: start TAG X0 Y0 W H STEP PAIR [LIMIT_S]"; exit 2; }
  [ "$(sha256sum /vendor/lib/modules/dtv_driver.ko | cut -d' ' -f1)" = "$DRV_SHA" ] || { echo ERROR_DRIVER_HASH; exit 1; }
  [ "$(sha256sum $KO | cut -d' ' -f1)" = "$KO_SHA" ] || { echo ERROR_MODULE_HASH; exit 1; }
  [ ! -d /sys/module/x90shot ] || { echo ERROR_ALREADY_LOADED; exit 1; }
  [ ! -d /sys/module/x90lat_panel ] || { echo ERROR_LATENCY_RIG_ACTIVE; exit 1; }
  mkdir $D/lock 2>/dev/null || { echo ERROR_LOCKED; exit 1; }
  [ -e $D/shot-$1.bin ] && mv $D/shot-$1.bin $D/shot-$1.bin.prev-$(date +%s)
  if insmod $KO permit=1 tag=$1 x0=$2 y0=$3 w=$4 h=$5 step=$6 pair=$7 limit_s=${8:-1200}; then
    echo STARTED; cat /sys/module/x90shot/parameters/status
  else
    rc=$?; mv $D/lock $D/lock-released-$(date +%s); echo "ERROR_INSMOD rc=$rc"; dmesg | grep X90SHOT | tail -2; exit 1
  fi ;;
status)
  if [ -d /sys/module/x90shot ]; then cat /sys/module/x90shot/parameters/status; else echo state=unloaded; fi ;;
finish)
  if [ -d /sys/module/x90shot ]; then rmmod x90shot && echo UNLOADED; fi
  [ -d $D/lock ] && mv $D/lock $D/lock-released-$(date +%s)
  dmesg | grep X90SHOT | tail -1; chmod 644 $D/shot-*.bin 2>/dev/null; : ;;
pattern)
  [ -e $D/pattern-stop ] && mv $D/pattern-stop $D/pattern-stop.used-$(date +%s)
  runcon u:r:shell:s0 env -u LD_LIBRARY_PATH CLASSPATH=$D/x90shot-pattern.dex \
    nohup app_process /system/bin X90ShotPattern ${1:-120} > $D/pattern.log 2>&1 &
  sleep 2; cat $D/pattern.log ;;
pattern-stop)
  : > $D/pattern-stop; sleep 1; cat $D/pattern.log ;;
*) echo "unknown action"; exit 2 ;;
esac
