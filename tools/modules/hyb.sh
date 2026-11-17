# hyb.sh STEP  (X1 2026-11-16) STEP: load | on | off | unload | fd
set -u
T=/data/local/tests; M=$T/x1b/x90mjcg.ko; P=/sys/module/x90mjcg/parameters
fd(){ dmesg -c >/dev/null; $T/mtalc sopq.prxy.api.oth.frmdly >/dev/null 2>&1; sleep 0.5; dmesg | grep -o "GetVideoFrameDelay.*"; }
case $1 in
load)
  echo "bb2ae9b30af38bc9b98419fb8bca6eed11a11c40b78ce975c278634ec49b9835  $M" | sha256sum -c - || exit 1
  [ -d /sys/module/x90mjcg ] && { echo ALREADY; exit 1; }
  KR=$(cat /proc/sys/kernel/kptr_restrict); echo 0 > /proc/sys/kernel/kptr_restrict
  TX=$(cat /sys/module/dtv_driver/sections/.text); echo "$KR" > /proc/sys/kernel/kptr_restrict
  th=${TX%????????}; tl=${TX#??????????}
  insmod $M text_lo=$((0x$tl)) text_hi=$((th)) permit=${PERMIT:-0} || { echo INSMOD_FAIL; dmesg | grep X90MJCG | tail -2; exit 1; }
  cat $P/ready; dmesg | grep X90MJCG | tail -1; fd;;
on)  echo 1 > $P/set; echo rc=$?; sleep 1; fd;;
off) echo 0 > $P/set; echo rc=$?; sleep 1; fd;;
unload) rmmod x90mjcg; [ -d /sys/module/x90mjcg ] && echo STILL || echo RMMOD_OK;;
fd) fd;;
esac
