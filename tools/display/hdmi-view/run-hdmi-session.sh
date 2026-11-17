#!/system/bin/sh
# Exact S137 firmware, HDMI4 480i preview. No exploit/reboot/settings setters.
set -eu
A=${1:-}; NAME=${2:-}; SESSION_SECONDS=${3:-}; VPORT=${4:-}; APORT=${5:-}; AUDIO=${6:-0}
FPS=${7:-15}
TRANSPORT=${8:-raw}; QUALITY=${9:-85}
CAPTURE_NICE=${10:-0}
case "$CAPTURE_NICE" in 0|5) ;; *) exit 2;; esac
case "$TRANSPORT" in raw|jpeg) ;; *) exit 2;; esac
case "$QUALITY" in ''|*[!0-9]*) exit 2;; esac
[ "$QUALITY" -ge 50 ] && [ "$QUALITY" -le 95 ]
case "$A" in /data/local/tests/x90-view-*) ;; *) exit 2;; esac
case "$NAME" in ''|*[!a-zA-Z0-9_-]*) exit 2;; esac
for n in "$SESSION_SECONDS" "$VPORT" "$APORT" "$AUDIO" "$FPS"; do case "$n" in ''|*[!0-9]*) exit 2;; esac; done
[ "$FPS" -le 60 ]
[ "$SESSION_SECONDS" -ge 2 ] && [ "$SESSION_SECONDS" -le 600 ]
[ "$VPORT" -ge 1024 ] && [ "$VPORT" -le 65535 ]
[ "$APORT" -ge 1024 ] && [ "$APORT" -le 65535 ]
[ "$AUDIO" = 0 ] || [ "$AUDIO" = 1 ]
BASE=/data/local/tests
[ "$(id -u)" = 0 ]
echo '97738790f18837a41cc1fad9da11657210b5462d29882e272928a8187f9b7125  /vendor/lib/modules/dtv_driver.ko' | sha256sum -c -
cd "$A"
sha256sum -c SHA256SUMS
[ ! -e "$BASE/x90-pq-pending.txt" ]
[ ! -d /sys/module/x90cap_session ]
[ ! -d /sys/module/x90audio_probe ]
mkdir "$BASE/x90-display-operation"
saved=$(cat /proc/sys/kernel/kptr_restrict)
owned=0; video=0; audio=0; encoder=0; unsafe=0; audio_checked=0
probe_audio(){
    tag=$$
    insmod "$A/x90audio_probe.ko" text_lo="$((0x$tl))" text_hi="$((th))" probe_tag="$tag" || true
    proof=$(dmesg | grep "X90AUDIO_UPLOAD0 .*probe_tag=$tag$" | tail -n 1)
    echo "$proof"
    [ -n "$proof" ] && echo "$proof" | grep -q 'enabled=0 channels=2 '
    [ ! -d /sys/module/x90audio_probe ]
}
cleanup(){
    result=$?
    trap - EXIT
    printf '%s\n' "$saved" > /proc/sys/kernel/kptr_restrict || result=1
    if [ "$video" != 0 ]; then kill -TERM "$video" 2>/dev/null || true; wait "$video" || echo "LIVE_VIDEO_STATUS=$?"; fi
    if [ "$encoder" != 0 ]; then kill -TERM "$encoder" 2>/dev/null || true; wait "$encoder" || echo "LIVE_ENCODER_STATUS=$?"; fi
    if [ "$audio" != 0 ]; then
        kill -TERM "$audio" 2>/dev/null || true
        ar=0; wait "$audio" || ar=$?
        [ "$ar" != 3 ] || unsafe=1
        echo "LIVE_AUDIO_STATUS=$ar"
    fi
    if [ "$owned" = 1 ]; then
        "$A/capture-state" --stop-owned || result=1
        if printf 1 > /sys/module/x90cap_session/parameters/command; then
            printf 2 > /sys/module/x90cap_session/parameters/command || result=1
            "$A/capture-state" --query || result=1
            rmmod x90cap_session || result=1
        else echo ERROR_CAPTURE_CLEANUP_RETAINED; result=1; fi
        dmesg | grep X90CAP_SESSION | tail -n 7
    fi
    if [ "$audio_checked" = 1 ]; then probe_audio || { unsafe=1; result=1; }; fi
    if [ ! -d /sys/module/x90cap_session ] && [ "$unsafe" = 0 ]; then
        mv "$BASE/x90-display-operation" "$A/completed-operation-lock" || result=1
    else echo ERROR_OPERATION_RETAINED_INSPECT_BEFORE_RETRY; result=1; fi
    cat "$A/video.txt" "$A/encoder.txt" "$A/audio.txt" 2>/dev/null || true
    echo "HDMI_SESSION_FINISHED result=$result reboot_requested=0"
    exit "$result"
}
trap cleanup EXIT
runcon u:r:shell:s0 dumpsys activity activities | grep 'mResumedActivity.*com.sony.dtv.tvx/'
runcon u:r:sony_platform_app:s0:c512,c768 env -u LD_LIBRARY_PATH CLASSPATH=$A/x90-signal.dex:/vendor/framework/com.mediatek.twoworlds.tv.jar timeout 8 app_process /system/bin SignalRead > "$A/signal.txt" 2>&1
cat "$A/signal.txt"
grep -q VERIFIED_480I "$A/signal.txt"
runcon u:r:sony_platform_app:s0:c512,c768 env LD_LIBRARY_PATH=/vendor/lib:/system/lib "$BASE/x90-pq" snapshot > "$A/pq-before.txt"
grep '^SOURCE 5$' "$A/pq-before.txt"
printf '0\n' > /proc/sys/kernel/kptr_restrict
bss=$(cat /sys/module/dtv_driver/sections/.bss)
text=$(cat /sys/module/dtv_driver/sections/.text)
printf '%s\n' "$saved" > /proc/sys/kernel/kptr_restrict
bh=${bss%????????}; bl=${bss#??????????}
th=${text%????????}; tl=${text#??????????}
if [ "$AUDIO" = 1 ]; then probe_audio; audio_checked=1; fi
chmod 755 "$A/x90-live-video" "$A/x90-live-audio" "$A/capture-state"
insmod "$A/x90cap_session.ko" bss_lo="$((0x$bl))" bss_hi="$((bh))" text_lo="$((0x$tl))" text_hi="$((th))" source=3
owned=1
RAWPORT=$VPORT
if [ "$TRANSPORT" = jpeg ]; then
    RAWPORT=$((VPORT + 2))
    [ "$RAWPORT" -le 65535 ]
    runcon u:r:shell:s0 env -u LD_LIBRARY_PATH CLASSPATH=$A/x90-live-jpeg.dex app_process /system/bin X90LiveJpeg "$RAWPORT" "$VPORT" "$SESSION_SECONDS" "$QUALITY" > "$A/encoder.txt" 2>&1 &
    encoder=$!
fi
nice -n "-$CAPTURE_NICE" env LD_LIBRARY_PATH=/vendor/lib:/system/lib "$A/x90-live-video" "$RAWPORT" "$SESSION_SECONDS" "$FPS" > "$A/video.txt" 2>&1 &
video=$!
if [ "$AUDIO" = 1 ]; then "$A/x90-live-audio" "$APORT" "$SESSION_SECONDS" > "$A/audio.txt" 2>&1 & audio=$!; fi
echo "HDMI_SESSION_READY video_pid=$video audio_pid=$audio"
deadline=$(($(date +%s) + SESSION_SECONDS + 20))
while kill -0 "$video" 2>/dev/null; do
    [ ! -f "$A/stop" ] || break
    [ "$(date +%s)" -lt "$deadline" ] || break
    if [ "$audio" != 0 ]; then kill -0 "$audio" 2>/dev/null || break; fi
    if [ "$encoder" != 0 ]; then kill -0 "$encoder" 2>/dev/null || break; fi
    sleep 1
done
# EXIT cleanup only signals our two native children, never root/refresh keepers.
exit 0
