"""Summarize an observe_boot.py timeline without contacting the TV."""
import argparse
import json
from pathlib import Path
import re


def parse(row):
    value = row.get("stdout", "")
    props = dict(re.findall(r"^PROP ([^=]+)=(.*)$", value, re.M))
    boot = re.search(r"^BOOT_ID\n([^\n]+)", value, re.M)
    uptime = re.search(r"^UPTIME\n([0-9.]+)", value, re.M)
    enforce = re.search(r"^ENFORCE=(.*)$", value, re.M)
    count = re.search(r"^PARENT_COUNT=(\d+)$", value, re.M)
    complete = (row.get("returncode") == 0 and not row.get("timed_out")
                and "OBSERVER_END" in value.splitlines()
                and "uid=2000(shell)" in value and boot and uptime)
    return {"capture_utc": row.get("utc"), "monitor_elapsed": row.get("monitor_elapsed"),
            "boot_id": boot.group(1) if boot else None,
            "uptime": float(uptime.group(1)) if uptime else None,
            "properties": props, "enforce": enforce.group(1) if enforce else None,
            "keeper": int(count.group(1)) if count else None,
            "complete": bool(complete)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("timeline", type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    with args.timeline.open(encoding="utf-8") as stream:
        raw = [json.loads(line) for line in stream if line.strip()]
    rows = [parse(row) for row in raw if row.get("kind") != "reconnect"]
    valid = [row for row in rows if row["complete"]]
    boots = list(dict.fromkeys(row["boot_id"] for row in valid))
    summary = {"source": str(args.timeline.resolve()), "boots_observed": boots,
               "shell_samples": len(rows), "complete_shell_samples": len(valid),
               "transport_timeouts": sum(bool(row.get("timed_out")) for row in raw),
               "interpretation": "single normal restart, not a boot-failure recovery trial"}
    if len(boots) != 2:
        raise SystemExit("Expected exactly two boot IDs; inspect raw evidence")
    new = [row for row in valid if row["boot_id"] == boots[-1]]
    def first(predicate):
        return next((row for row in new if predicate(row)), None)
    summary["new_boot"] = {
        "first_complete_shell": first(lambda row: True),
        "shell_while_animation_running_and_boot_incomplete": first(
            lambda row: row["properties"].get("init.svc.bootanim") == "running"
            and "sys.boot_completed" in row["properties"]
            and row["properties"]["sys.boot_completed"] != "1"),
        "first_boot_completed": first(lambda row: row["properties"].get("sys.boot_completed") == "1"),
        "first_animation_stopped": first(lambda row: row["properties"].get("init.svc.bootanim") == "stopped"),
        "first_healthy_root": first(lambda row: row["enforce"] == "Permissive" and row["keeper"] == 2),
        "final_complete_sample": new[-1],
    }
    with args.out.open("x", encoding="utf-8") as stream:
        json.dump(summary, stream, indent=2)
        stream.write("\n")
    for label, row in summary["new_boot"].items():
        print(label, "not observed" if row is None else
              "uptime=%.2fs monitor=%.2fs" % (row["uptime"], row["monitor_elapsed"]))


if __name__ == "__main__":
    main()
