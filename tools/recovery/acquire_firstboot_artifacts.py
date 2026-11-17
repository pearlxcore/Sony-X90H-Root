"""Read-only acquisition of fixed public first-boot/ADB implementation artifacts."""
from pathlib import Path
import base64
import datetime as dt
import hashlib
import json
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "evidence/firstboot-maintenance-20261116-1544"
ADB = "C:/tools/platform-tools/adb.exe"
SERIAL = "127.0.0.1:5555"
FILES = {
    "SonyVendorService.apk": "/vendor/app/SonyVendorService/SonyVendorService.apk",
    "SettingsProvider.apk": "/system/priv-app/SettingsProvider/SettingsProvider.apk",
    "services.jar": "/system/framework/services.jar",
    "BroadcastServiceSetup.apk": "/system/priv-app/BroadcastServiceSetup/BroadcastServiceSetup.apk",
    "jni-system.so": "/system/lib/libcom_mediatek_twoworlds_tv_jni.mediatek.so",
    "jni-vendor.so": "/vendor/lib/libcom_mediatek_twoworlds_tv_jni.mediatek.so",
    "mtktvapi-cwrapper.so": "/vendor/lib/vendor.mediatek_common.tv.mtktvapi@1.0-cwrapper.so",
}


def adb(*args):
    proc = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True,
                          encoding="utf-8", errors="replace", timeout=40)
    return {"utc": dt.datetime.now(dt.timezone.utc).isoformat(),
            "returncode": proc.returncode, "stdout": proc.stdout, "stderr": proc.stderr}


def sh(text):
    encoded = base64.b64encode(text.encode()).decode()
    return adb("shell", "printf %s " + encoded + " | base64 -d | sh")


def save(name, value):
    with (OUT / name).open("x", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")


def main():
    OUT.mkdir(parents=True, exist_ok=False)
    identity = sh("getprop ro.build.fingerprint; sha256sum /vendor/lib/modules/dtv_driver.ko; cat /proc/sys/kernel/random/boot_id")
    save("identity.json", identity)
    if (identity["returncode"] != 0
            or "Sony/BRAVIA_VH1_PA/BRAVIA_VH1:12/STT2.230505.001.S137/626241:user/release-keys" not in identity["stdout"].splitlines()
            or "97738790f18837a41cc1fad9da11657210b5462d29882e272928a8187f9b7125 " not in identity["stdout"]):
        raise SystemExit("Identity mismatch; no acquisition")
    binary = OUT / "binaries"
    binary.mkdir()
    records = []
    for name, path in FILES.items():
        save("device-hash-" + name + ".json", sh("sha256sum " + path))
        result = adb("pull", path, str(binary / name))
        save("pull-" + name + ".json", result)
        local = binary / name
        if result["returncode"] == 0 and local.is_file():
            records.append({"device_path": path, "local_name": name, "bytes": local.stat().st_size,
                            "sha256": hashlib.sha256(local.read_bytes()).hexdigest()})
    save("artifacts.json", records)
    print(json.dumps(records, indent=2))


if __name__ == "__main__":
    main()
