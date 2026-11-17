"""Local ext4 experiment using new image files only, never the TV or a block device.

Demonstrates format versus repopulation; dummy text is not a valid ADB key, and
the property text is not Android's binary persistent-property storage format.
"""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "evidence/wipe-preservation-20261116-1523/ext4-lab"
TOKEN = "DEMONSTRATION_PUBLIC_KEY_NOT_VALID_FOR_ADB\n"


def run(args):
    p = subprocess.run(args, capture_output=True, encoding="utf-8", errors="replace", timeout=45)
    return {"argv": args, "returncode": p.returncode, "stdout": p.stdout, "stderr": p.stderr}


def wslpath(path):
    result = run(["wsl.exe", "--exec", "wslpath", "-a", str(path.resolve())])
    if result["returncode"] != 0:
        raise RuntimeError(result)
    value = result["stdout"].strip()
    if not value.startswith("/"):
        raise RuntimeError("Unexpected WSL path")
    return value


def record(name, result):
    with (OUT / (name + ".json")).open("x", encoding="utf-8") as stream:
        json.dump(result, stream, indent=2)
        stream.write("\n")
    return result


def make_image(image, seed=None):
    if image.parent.resolve() != OUT.resolve() or image.suffix != ".ext4":
        raise RuntimeError("Image must be an explicit regular file in this experiment")
    if not image.exists():
        with image.open("xb") as stream:
            stream.truncate(64 * 1024 * 1024)
    args = ["wsl.exe", "--exec", "/usr/sbin/mke2fs", "-q", "-F", "-t", "ext4"]
    if seed:
        args.extend(["-d", wslpath(seed)])
    args.append(wslpath(image))
    result = record(image.stem + "-format", run(args))
    if result["returncode"] != 0:
        raise RuntimeError(result)


def read_key(image, name):
    return record(name, run(["wsl.exe", "--exec", "/usr/sbin/debugfs", "-R",
                             "cat /misc/adb/adb_keys", wslpath(image)]))


def main():
    OUT.mkdir(exist_ok=False)
    original = OUT / "original-content"
    seed = OUT / "minimal-repopulation"
    for directory in (original, seed):
        (directory / "misc/adb").mkdir(parents=True)
        (directory / "misc/adb/adb_keys").write_text(TOKEN, encoding="ascii")
        (directory / "property").mkdir()
        (directory / "property/adb-settings-DEMO.txt").write_text(
            "persist.sys.usb.config=adb\npersist.adb.tcp.port=5555\n", encoding="ascii")
    (original / "unrelated-user-data-DEMO.txt").write_text("Not retained\n", encoding="ascii")
    before = OUT / "before.ext4"
    formatted = OUT / "after-format.ext4"
    repopulated = OUT / "after-repopulation.ext4"
    make_image(before, original)
    before_read = read_key(before, "before-key")
    assert before_read["stdout"] == TOKEN
    # Keep the original image; format only a new regular-file copy.
    shutil.copyfile(before, formatted)
    make_image(formatted)
    after_read = read_key(formatted, "after-format-key")
    assert TOKEN not in after_read["stdout"] and "not found" in after_read["stderr"].lower()
    make_image(repopulated, seed)
    restored_read = read_key(repopulated, "repopulated-key")
    assert restored_read["stdout"] == TOKEN
    unrelated = record("repopulated-unrelated", run([
        "wsl.exe", "--exec", "/usr/sbin/debugfs", "-R",
        "stat /unrelated-user-data-DEMO.txt", wslpath(repopulated)]))
    assert "not found" in unrelated["stderr"].lower()
    result = {"format_removes_existing_key_path": True,
              "repopulation_recreates_selected_dummy_key": True,
              "unrelated_dummy_data_not_restored": True,
              "scope": "local unencrypted ext4 semantics only; no Android/ADB/SELinux/FBE/startup claim",
              "tv_contacted": False, "host_block_devices_used": False,
              "images": [{"name": p.name, "bytes": p.stat().st_size,
                          "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
                         for p in (before, formatted, repopulated)]}
    record("RESULT", result)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
