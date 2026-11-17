# x1-device.sh v0.2.0 - device-side worker for X90H Picture Control (root via the mailbox).
# Generic Sony/MediaTek picture config via PQTool (MtkTvConfig). Input-agnostic (per-input).
# Usage (one verb per mailbox call):
#   x1-device.sh check
#   x1-device.sh get [inputGroup]                 (read-only: SOURCE/SIGNAL + all keys, cur/min/max/def)
#   x1-device.sh minmax <key>                     (read-only: one key's range)
#   x1-device.sh set <key> <value> [inputGroup]   (persistent, official API; inputGroup default -1=active)
#   x1-device.sh snapshot <name> [inputGroup]     (save all settable keys' current values)
#   x1-device.sh restore <name>                   (re-apply the snapshot, verify)
#   x1-device.sh route standard|game|game_plus    (persistent config routes)
#   x1-device.sh frmdly
# Scope: x90h-internal. Device fingerprint STT2.230505.001.S137. Never touches perm/DRM.
set -u
T=/data/local/tests; O=$T/x1kit; mkdir -p $O
FP_WANT="Sony/BRAVIA_VH1_PA/BRAVIA_VH1:12/STT2.230505.001.S137/626241:user/release-keys"
DRV_WANT="97738790f18837a41cc1fad9da11657210b5462d29882e272928a8187f9b7125"
PQBASE="runcon u:r:sony_platform_app:s0:c512,c768 env -u LD_LIBRARY_PATH CLASSPATH=$T/pqtool.dex:/vendor/framework/com.mediatek.twoworlds.tv.jar"
PQ(){ $PQBASE timeout 50 app_process /system/bin PQTool "$@"; }
snap_src(){ runcon u:r:sony_platform_app:s0:c512,c768 env LD_LIBRARY_PATH=/vendor/lib:/system/lib $T/x90-pq-hdmi4-orig snapshot 2>/dev/null | grep -E '^SOURCE|^SIGNAL' || true; }
KEYS=$O/x1-keys.txt

guard(){
  fp=$(getprop ro.build.fingerprint)
  [ "$fp" = "$FP_WANT" ] || { echo "ABORT fingerprint mismatch: $fp"; exit 2; }
  drv=$(sha256sum /vendor/lib/modules/dtv_driver.ko 2>/dev/null | cut -d' ' -f1)
  [ "$drv" = "$DRV_WANT" ] || { echo "ABORT dtv_driver hash mismatch: $drv"; exit 2; }
  [ -s $T/pqtool.dex ] || { echo "ABORT pqtool.dex missing (run: x1 deploy)"; exit 2; }
  [ -s $KEYS ] || { echo "ABORT x1-keys.txt missing (run: x1 deploy)"; exit 2; }
}

case "${1:-}" in
check)
  guard; echo "fingerprint OK"; echo "dtv_driver OK"
  echo "keeper $(ps -A | grep -c prod_exploit) proc"; snap_src ;;
get)
  guard; snap_src; PQ dump $(cat $KEYS) ;;
minmax)
  guard; PQ minmax "$2" ;;
set)
  guard; k=$2; v=$3; g=${4:--1}
  if [ "$g" = -1 ]; then PQ set "$k" "$v" | tail -1; else PQ set "$k" "$v" "$g" | tail -1; fi ;;
snapshot)
  guard; n=${2:-default}; { snap_src; PQ dump $(cat $KEYS); } > $O/snap-$n.txt
  grep -c '^DUMP' $O/snap-$n.txt | sed 's/^/saved keys=/' ;;
restore)
  guard; n=${2:-default}
  [ -s $O/snap-$n.txt ] || { echo "NO_SUCH_SNAPSHOT $n"; exit 1; }
  # Re-apply every snapshot cur= value in one JVM, then verify (compare cur= of each key).
  pairs=$(awk '/^DUMP /{v=$5; sub(/cur=/,"",v); print $2, v}' $O/snap-$n.txt | tr '\n' ' ')
  res=$(PQ setmany $pairs 2>&1 | grep '^SETMANY'); echo "$res"
  sleep 2; { snap_src; PQ dump $(cat $KEYS); } > $O/snap-$n-after.txt
  awk '/^DUMP /{print $2, $5}' $O/snap-$n.txt       > $O/snap-$n.cmp
  awk '/^DUMP /{print $2, $5}' $O/snap-$n-after.txt  > $O/snap-$n-after.cmp
  if cmp -s $O/snap-$n.cmp $O/snap-$n-after.cmp; then echo RESTORED_IDENTICAL
  else echo RESTORE_DIFF; diff $O/snap-$n.cmp $O/snap-$n-after.cmp | head -40; fi ;;
route)
  guard; r=$2
  case "$r" in
    standard)  PQ set g_video__pic_mode 1 | tail -1 ;;
    game)      PQ set g_video__pic_mode 3 | tail -1 ;;
    game_plus) for kv in "g_video__pic_mode 3" "g_video__reality_creation 2" "g_video__resolution 65" "g_video__sharpness 65"; do PQ set $kv | tail -1; done ;;
    *) echo "UNKNOWN_ROUTE $r (standard|game|game_plus; hybrid is in hyb.sh)"; exit 1 ;;
  esac ;;
frmdly)
  guard; dmesg -c >/dev/null; $T/mtalc sopq.prxy.api.oth.frmdly >/dev/null 2>&1; sleep 0.5; dmesg | grep -o "GetVideoFrameDelay.*" ;;
*) echo "usage: x1-device.sh check|get|minmax|set|snapshot|restore|route|frmdly"; exit 2 ;;
esac
chmod -R a+rX $O 2>/dev/null || true
