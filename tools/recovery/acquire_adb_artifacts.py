"""Acquire a fixed set of public ADB executable/config artifacts, read-only.

Never reads authorization key contents, user databases or partition contents.
No root mailbox, settings mutation, daemon restart or reboot.
"""
import base64
import datetime as dt
import hashlib
import json
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parents[1]
OUT = HERE / "evidence/postwipe-analysis-20261116-1510"
ADB = "C:/tools/platform-tools/adb.exe"
SERIAL = "127.0.0.1:5555"
FILES = {
    "adbd": "/apex/com.android.adbd/bin/adbd",
    "libadbd_auth.so": "/system/lib/libadbd_auth.so",
    "adbd.rc": "/apex/com.android.adbd/etc/init/adbd.rc",
}


def adb(*args):
    p = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True,
                       encoding="utf-8", errors="replace", timeout=25)
    return {"utc": dt.datetime.now(dt.timezone.utc).isoformat(),
            "returncode": p.returncode, "stdout": p.stdout, "stderr": p.stderr}


def sh(script):
    encoded = base64.b64encode(script.encode()).decode()
    return adb("shell", "printf %s " + encoded + " | base64 -d | sh")


def save(name, value):
    with (OUT / name).open("x", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")


def main():
    OUT.mkdir(parents=True, exist_ok=False)
    identity = sh("getprop ro.build.fingerprint; sha256sum /vendor/lib/modules/dtv_driver.ko")
    save("identity.json", identity)
    if (identity["returncode"] != 0 or
            "Sony/BRAVIA_VH1_PA/BRAVIA_VH1:12/STT2.230505.001.S137/626241:user/release-keys" not in identity["stdout"].splitlines()
            or "97738790f18837a41cc1fad9da11657210b5462d29882e272928a8187f9b7125 " not in identity["stdout"]):
        raise SystemExit("Identity failed, no acquisition")
    metadata = sh("""id
cat /proc/sys/kernel/random/boot_id
for p in ro.boot.verifiedbootstate ro.boot.vbmeta.device_state ro.debuggable ro.secure ro.adb.secure service.adb.tcp.port persist.adb.tcp.port persist.sys.usb.config sys.usb.config init.svc.adbd; do echo "$p=$(getprop $p)"; done
for k in development_settings_enabled adb_enabled adb_wifi_enabled adb_network_enabled; do echo "$k=$(settings get global $k)"; done
echo KEY_PATH_METADATA_ONLY
ls -ld /data/property /data/misc/adb /adb_keys /product/etc/security/adb_keys
echo DAEMON_EXE
p=$(pidof adbd); echo "pid=$p"; readlink /proc/$p/exe
echo ARTIFACT_HASHES
sha256sum /apex/com.android.adbd/bin/adbd /system/lib/libadbd_auth.so /apex/com.android.adbd/etc/init/adbd.rc
""")
    save("metadata.json", metadata)
    binaries = OUT / "binaries"
    binaries.mkdir()
    manifest = []
    for name, remote in FILES.items():
        local = binaries / name
        result = adb("pull", remote, str(local))
        save("pull-" + name + ".json", result)
        if result["returncode"] == 0 and local.is_file():
            manifest.append({"device_path": remote, "path": str(local),
                             "bytes": local.stat().st_size,
                             "sha256": hashlib.sha256(local.read_bytes()).hexdigest()})
    save("artifacts.json", manifest)
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
