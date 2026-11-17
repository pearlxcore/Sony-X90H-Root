"""Read-only storage/controller metadata. No disk contents, role changes or root.

Each capture requires a new output path and the exact known TV identity.
"""
import argparse
import base64
import datetime as dt
import json
from pathlib import Path
import subprocess

FP = "Sony/BRAVIA_VH1_PA/BRAVIA_VH1:12/STT2.230505.001.S137/626241:user/release-keys"
SHA = "97738790f18837a41cc1fad9da11657210b5462d29882e272928a8187f9b7125"
SCRIPT = """echo BOOT_ID
cat /proc/sys/kernel/random/boot_id
echo VOLUMES
sm list-disks
sm list-volumes all
echo ADOPTED_MOUNT
cat /proc/mounts | grep '^/dev/block/.* /mnt/expand/'
echo BLOCK_LINKS
for p in /sys/class/block/sd*; do
  [ -e "$p" ] || continue
  echo "$p"
  readlink -f "$p/device"
done
echo USB_LINKS
ls -l /sys/bus/usb/devices
echo USB_FLAGS
cat /sys/module/usbcore/parameters/usb_adb_enable
cat /sys/module/usbcore/parameters/usb_adb_port
echo UDC
ls -l /sys/class/udc
echo SELECTED_USB_DEVICE_METADATA
for p in /sys/bus/usb/devices/1-2 /sys/bus/usb/devices/3-1; do
  for n in manufacturer product speed bDeviceClass; do
    echo "$p/$n"
    cat "$p/$n"
  done
done
echo CONTROLLER_DEVICE_TREE
for p in /sys/bus/platform/devices/1202d000.usb1 /sys/bus/platform/devices/100d4000.usb2; do
  echo "$p"
  readlink -f "$p/driver"
  readlink -f "$p/of_node"
  for n in compatible dr_mode; do
    echo "$n"
    cat "$p/of_node/$n" | tr '\\000' '\\n'
  done
done
echo FOREGROUND
dumpsys activity activities | grep mResumedActivity
echo USB_MAP_END
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--serial", default="127.0.0.1:5555")
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit("Refusing to overwrite evidence")

    def shell(script):
        payload = base64.b64encode(script.encode()).decode()
        proc = subprocess.run([args.adb, "-s", args.serial, "shell",
                               "printf %s " + payload + " | base64 -d | sh"],
                              capture_output=True, text=True, encoding="utf-8",
                              errors="replace", timeout=25)
        return {"utc": dt.datetime.now(dt.timezone.utc).isoformat(),
                "command": script, "returncode": proc.returncode,
                "stdout": proc.stdout, "stderr": proc.stderr}

    identity = shell("getprop ro.build.fingerprint; sha256sum /vendor/lib/modules/dtv_driver.ko")
    if identity["returncode"] or FP not in identity["stdout"].splitlines() or SHA + " " not in identity["stdout"]:
        raise SystemExit("Identity mismatch; metadata collection stopped")
    result = {"identity": identity, "metadata": shell(SCRIPT)}
    with args.output.open("x", encoding="utf-8") as stream:
        json.dump(result, stream, indent=2)
        stream.write("\n")
    print(result["metadata"]["stdout"])
    print(result["metadata"]["stderr"])


if __name__ == "__main__":
    main()
