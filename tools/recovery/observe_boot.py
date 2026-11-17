"""Read-only TV boot observer. No reboot, property changes, or root requests.

Uses the existing trusted ADB host. Every child process has a transport deadline.
Output directories must be new; captures are never overwritten.
"""
import argparse
import base64
import datetime as dt
import hashlib
import json
from pathlib import Path
import subprocess
import time

FINGERPRINT = "Sony/BRAVIA_VH1_PA/BRAVIA_VH1:12/STT2.230505.001.S137/626241:user/release-keys"
DRIVER_SHA = "97738790f18837a41cc1fad9da11657210b5462d29882e272928a8187f9b7125"
PROPERTIES = (
    "ro.build.fingerprint", "ro.build.version.incremental", "ro.hardware",
    "ro.boot.bootreason", "ro.boot.slot_suffix", "ro.boot.verifiedbootstate",
    "ro.boot.vbmeta.device_state", "ro.boot.force_normal_boot", "ro.debuggable",
    "ro.adb.secure", "sys.boot_completed", "dev.bootcomplete",
    "init.svc.adbd", "init.svc.bootanim", "init.svc.zygote",
    "init.svc.zygote_secondary", "init.svc.netd", "init.svc.dhcpcd",
    "service.bootanim.exit", "service.adb.tcp.port", "persist.adb.tcp.port",
    "persist.sys.usb.config", "sys.usb.config", "sys.usb.state",
    "sys.usb.controller", "sys.usb.configfs", "sys.usb.ffs.ready",
    "sys.usb.adb.disabled", "vendor.sys.usb.adb.disabled",
    "ro.crypto.state", "ro.crypto.type", "vold.decrypt",
    "init.svc_debug_pid.adbd", "ro.boottime.init", "ro.boottime.init.first_stage",
    "ro.boottime.init.selinux", "ro.boottime.init.cold_boot_wait",
    "ro.boottime.adbd", "ro.boottime.netd", "ro.boottime.zygote",
    "ro.boottime.bootanim", "vendor.svp.sony_ko.boot_completed",
)
FILES = (
    "/system/etc/init/hw/init.rc", "/system/etc/init/hw/init.usb.rc",
    "/system/etc/init/hw/init.usb.configfs.rc", "/system/etc/init/bootanim.rc",
    "/vendor/etc/init/hw/init.mt5895.rc",
    "/vendor/etc/init/hw/init.mt5895.usb.rc",
    "/vendor/etc/init/hw/init.boottime_trace.rc",
    "/proc/boottime", "/proc/cmdline", "/proc/uptime",
)


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def invoke(args, adb_args, timeout=8):
    start = time.monotonic()
    try:
        proc = subprocess.run([args.adb, *adb_args], capture_output=True,
                              timeout=timeout, encoding="utf-8", errors="replace")
        return {"utc": now(), "elapsed": time.monotonic() - start,
                "returncode": proc.returncode, "stdout": proc.stdout,
                "stderr": proc.stderr, "timed_out": False}
    except subprocess.TimeoutExpired as exc:
        def decode(value):
            return value.decode("utf-8", "replace") if isinstance(value, bytes) else value or ""
        return {"utc": now(), "elapsed": time.monotonic() - start,
                "returncode": None, "stdout": decode(exc.stdout),
                "stderr": decode(exc.stderr), "timed_out": True}


def shell(args, script, timeout=8):
    encoded = base64.b64encode(script.encode()).decode("ascii")
    return invoke(args, ["-s", args.serial, "shell",
                         "printf %s " + encoded + " | base64 -d | sh"], timeout)


def write_json(path, value):
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")


def state_script():
    lines = ["echo OBSERVER_BEGIN", "id", "date", "echo BOOT_ID",
             "cat /proc/sys/kernel/random/boot_id", "echo UPTIME", "cat /proc/uptime"]
    for prop in PROPERTIES:
        lines.append("echo PROP " + prop + "=\"$(getprop " + prop + ")\"")
    lines.extend(["echo ENFORCE=$(getenforce)",
                  "echo PARENT_COUNT=$(ps -A | grep -c '[p]rod_exploit_v21')",
                  "echo OBSERVER_END"])
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("snapshot", "watch"))
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default="127.0.0.1:5555")
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--duration", type=int, default=240)
    parser.add_argument("--interval", type=float, default=2)
    args = parser.parse_args()
    if not 1 <= args.duration <= 900 or not 0.5 <= args.interval <= 30:
        parser.error("duration must be 1..900, interval 0.5..30")
    recovery = Path(__file__).resolve().parents[1]
    output = args.out.resolve()
    if output.exists():
        parser.error("output path already exists; choose a new directory")
    output.mkdir(parents=True, exist_ok=False)
    identity = shell(args, "getprop ro.build.fingerprint; sha256sum /vendor/lib/modules/dtv_driver.ko")
    write_json(output / "identity-before.json", identity)
    if (identity["returncode"] != 0 or FINGERPRINT not in identity["stdout"].splitlines()
            or not any(line.startswith(DRIVER_SHA + " ") for line in identity["stdout"].splitlines())):
        raise SystemExit("Identity unavailable or mismatch; evidence retained, no further probes")
    write_json(output / "observer.json", {"started": now(), "mode": args.mode,
               "serial": args.serial, "duration": args.duration, "interval": args.interval,
               "observer_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
               "scope": "read-only; no reboot; no root mailbox; no property or device-role writes"})
    if args.mode == "snapshot":
        write_json(output / "state.json", shell(args, state_script()))
        for index, path in enumerate(FILES):
            # All paths are fixed constants, never operator-supplied shell fragments.
            result = shell(args, "cat " + path)
            result["device_path"] = path
            write_json(output / ("file-%02d.json" % index), result)
        probes = {
            "usb": "ls -ld /sys/class/udc; ls -la /sys/class/udc; cat /config/usb_gadget/g1/UDC; cat /sys/class/android_usb/android0/state; cat /sys/devices/platform/soc/1202d000.usb1/of_node/dr_mode",
            "network": "ip -o link show; ip -o -4 addr show; ip -4 route show; dumpsys ethernet",
            "boot-events": "logcat -d -b events -v monotonic -s boot_progress_start:I boot_progress_preload_start:I boot_progress_preload_end:I boot_progress_system_run:I boot_progress_pms_start:I boot_progress_pms_system_scan_start:I boot_progress_pms_data_scan_start:I boot_progress_pms_scan_end:I boot_progress_pms_ready:I boot_progress_ams_ready:I boot_progress_enable_screen:I sf_stop_bootanim:I '*:S'",
            "foreground": "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'; dumpsys tv_input | grep -E 'HDMI|hdmi|active|Active|Hardware|port_id'",
        }
        for name, script in probes.items():
            write_json(output / (name + ".json"), shell(args, script, 12))
        print(str(output), flush=True)
        return
    start = time.monotonic()
    with (output / "timeline.jsonl").open("x", encoding="utf-8") as stream:
        while time.monotonic() - start < args.duration:
            result = shell(args, state_script(), 2)
            result["monitor_elapsed"] = time.monotonic() - start
            stream.write(json.dumps(result) + "\n")
            stream.flush()
            print("%.1fs rc=%s timeout=%s" % (result["monitor_elapsed"],
                  result["returncode"], result["timed_out"]), flush=True)
            # Reconnect only the expressly selected LAN endpoint; never restart ADB.
            if result["returncode"] != 0 and ":" in args.serial:
                reconnect = invoke(args, ["connect", args.serial], 2)
                reconnect["monitor_elapsed"] = time.monotonic() - start
                reconnect["kind"] = "reconnect"
                stream.write(json.dumps(reconnect) + "\n")
                stream.flush()
            time.sleep(args.interval)
    print("Observation finished; no recovery action executed", flush=True)


if __name__ == "__main__":
    main()
