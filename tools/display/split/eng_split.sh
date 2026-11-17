# 2026-11-16 split-screen A/B runner: eng.sh (HDMI4, self-restoring) with ENGDEX/JOPT/FUSH
# overrides so it runs engine-ll9 (-Dx90.split) with the fastup shaders. No stimulus pattern
# (plain picture for visual compare). Usage: ENGDEX=.. JOPT=".." FUSH=.. sh eng_split.sh TAG SECONDS
set -eu
TAG=$1; SECS=$2; FILTER=${3:-catmullrom-clamped.frag}
BASE=/data/local/tests
SRC=$BASE/x90-engine-session-6414958007124d6bb6ef7463fde75889
A=/dev/shm/x90-eng-$TAG
PQ=$BASE/x90-pq-hdmi4-orig
( set -C; : > /dev/shm/x90-eng-lock.$TAG ) 2>/dev/null || { echo LOCKED; exit 0; }
[ "$(id -u)" = 0 ]
[ ! -e $BASE/x90-engine-active ] || { echo ERROR_ENGINE_ALREADY_ACTIVE; exit 1; }
[ ! -d /sys/module/x90cap_session ] && [ ! -d /sys/module/x90field_sample ]
echo '97738790f18837a41cc1fad9da11657210b5462d29882e272928a8187f9b7125  /vendor/lib/modules/dtv_driver.ko' | sha256sum -c -
echo 'fef08b38d52d30efe8956c7c82405d07fe3a1a20d58dccee8aae1822cdc5f430  '$PQ | sha256sum -c -
mkdir $A
cd $SRC; for f in $(awk '{print $2}' SHA256SUMS); do cp $SRC/$f $A/; done; cp SHA256SUMS $A/
cd $A; sha256sum -c SHA256SUMS >/dev/null && echo BUNDLE_OK
# --- split variant overrides ---
[ -n "${ENGDEX:-}" ] && { cp "$ENGDEX" $A/engine.dex; echo "ENGDEX=$ENGDEX $(sha256sum $ENGDEX | cut -d' ' -f1)"; }
[ -n "${FUSH:-}" ] && { cp $FUSH/fu-pre-block.frag $FUSH/fu-pre-cross.frag $FUSH/fu-h.frag $FUSH/fu-v.frag $A/; echo FASTUP_SHADERS_ADDED; }
# --- deinterlace shader override (motion-gated di=3 etc.); applied after checksum like FUSH ---
[ -n "${DISH:-}" ] && { cp "$DISH" $A/field-di-vt.frag; echo DI_SHADER=$DISH $(sha256sum "$DISH"|cut -d" " -f1); }
# --- upscale shader override (2026-11-16, x90shot work): replaces catmullrom-clamped.frag; use without -Dx90.fastup ---
[ -n "${UPSH:-}" ] && { cp "$UPSH" $A/catmullrom-clamped.frag; echo UP_SHADER=$UPSH $(sha256sum "$UPSH"|cut -d" " -f1); }
echo "JOPT=${JOPT:-}"
DEX=$A/x90-field-engine-vt.dex; [ -n "${ENGDEX:-}" ] && DEX=$A/engine.dex
: > $A/stop; : > $A/ui-hidden; chmod 666 $A/stop $A/ui-hidden
saved=$(cat /proc/sys/kernel/kptr_restrict)
owned=0;sampler=0;video=0;renderer=0
log(){ echo "X90ENG $TAG $*" > /dev/kmsg; echo "$*"; }
cleanup(){
  reason=$?; result=0; trap - EXIT
  printf stop > $A/stop
  n=0; while [ "$renderer" != 0 ] && kill -0 $renderer 2>/dev/null && [ $n -lt 4 ]; do sleep 1; n=$((n+1)); done
  if [ "$renderer" != 0 ]; then kill -TERM $renderer 2>/dev/null || true; wait $renderer || echo ENGINE_STATUS=$?; fi
  if [ "$video" != 0 ]; then kill -TERM $video 2>/dev/null || true; wait $video || echo FIELD_VIDEO_STATUS=$?; fi
  echo "$saved" > /proc/sys/kernel/kptr_restrict || result=1
  if [ $owned = 1 ]; then
    $A/capture-state --stop-owned || result=1
    if echo 1 > /sys/module/x90cap_session/parameters/command; then
      echo 2 > /sys/module/x90cap_session/parameters/command || result=1
      rmmod x90cap_session || result=1
    else echo ERROR_CAPTURE_CLEANUP_RETAINED; result=1; fi
    dmesg | grep X90CAP_SESSION | tail -n 4
  fi
  [ $sampler = 1 ] && { rmmod x90field_sample || result=1; }
  runcon u:r:shell:s0 dumpsys SurfaceFlinger --list > $A/after-layers.txt || result=1
  grep -q X90FieldEngine $A/after-layers.txt && { echo ERROR_ENGINE_LAYER_RETAINED; result=1; }
  runcon u:r:sony_platform_app:s0:c512,c768 env LD_LIBRARY_PATH=/vendor/lib:/system/lib $PQ snapshot > $A/pq-after.txt || result=1
  cmp -s $A/pq-before.txt $A/pq-after.txt || { echo PQ_DIFF; result=1; }
  [ -d $BASE/x90-display-operation ] && [ "$(cat $BASE/x90-display-operation/engine-owner 2>/dev/null)" = "$A" ] && mv $BASE/x90-display-operation $A/completed-operation-lock
  [ "$(cat $BASE/x90-engine-active 2>/dev/null)" = "$A" ] && mv $BASE/x90-engine-active $A/active-receipt.txt
  [ $result = 0 ] && echo SONY_RESTORED > $A/state-stopped || echo REVIEW_REQUIRED > $A/state-error
  chmod -R a+r $A
  log "ENGINE_EXIT=$result reason=$reason"
  exit $result
}
trap cleanup EXIT
trap 'exit 1' TERM INT HUP
mkdir $BASE/x90-display-operation; echo $A > $BASE/x90-display-operation/engine-owner; echo $A > $BASE/x90-engine-active
runcon u:r:shell:s0 dumpsys activity activities | grep 'mResumedActivity.*com.sony.dtv.tvx/'
runcon u:r:sony_platform_app:s0:c512,c768 env -u LD_LIBRARY_PATH CLASSPATH="$A/x90-signal.dex:/vendor/framework/com.mediatek.twoworlds.tv.jar" timeout 8 app_process /system/bin SignalRead > $A/signal.txt 2>&1
grep -q VERIFIED_480I $A/signal.txt || { cat $A/signal.txt; exit 1; }
runcon u:r:sony_platform_app:s0:c512,c768 env LD_LIBRARY_PATH=/vendor/lib:/system/lib $PQ snapshot > $A/pq-before.txt
grep '^SOURCE 5$' $A/pq-before.txt
echo 0 > /proc/sys/kernel/kptr_restrict
bss=$(cat /sys/module/dtv_driver/sections/.bss); text=$(cat /sys/module/dtv_driver/sections/.text)
echo "$saved" > /proc/sys/kernel/kptr_restrict
[ "$text" != 0 ] && [ "$bss" != 0 ] || { log ABORT_ZERO; exit 1; }
bh=${bss%????????}; bl=${bss#??????????}; th=${text%????????}; tl=${text#??????????}
chmod 755 $A/x90-field-capture $A/capture-state
insmod $A/x90cap_session.ko bss_lo=$((0x$bl)) bss_hi=$((bh)) text_lo=$((0x$tl)) text_hi=$((th)) source=5
owned=1
insmod $A/x90field_sample.ko text_lo=$((0x$tl)) text_hi=$((th))
sampler=1
env LD_LIBRARY_PATH=/vendor/lib:/system/lib $A/x90-field-capture 27776 0 0 > $A/video.txt 2>&1 &
video=$!
runcon u:r:shell:s0 env -u LD_LIBRARY_PATH CLASSPATH=$DEX app_process ${JOPT:-} /system/bin X90FieldEngine $A 27776 0 ${OW:-1920} ${OH:-1080} $FILTER > $A/renderer.txt 2>&1 &
renderer=$!
log "SESSION_READY video=$video engine=$renderer dex=$DEX"
t=0
while [ ! -s $A/stop ] && [ $t -lt $SECS ]; do
  kill -0 $video && kill -0 $renderer || { log CHILD_EXITED; exit 1; }
  if [ ! -e $A/state-running ] && grep -q FIELD_HISTORY $A/renderer.txt; then echo CUSTOM_ACTIVE > $A/state-running; log CUSTOM_ACTIVE; fi
  sleep 1; t=$((t+1))
done
log TIME_UP
