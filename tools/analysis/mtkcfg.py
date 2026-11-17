#!/usr/bin/env python3
"""Extract MTK TV config keys/values observed in an adb logcat dump.

The TV's own vendor apps log every MtkTvConfig get/set with the symbolic config id and value:
    D/TV_MtkTvConfig( 4967): Enter getConfigValue, inputGroup=-1, cfgId=g_video__sharpness
    D/MtkTvConfig_jni( 4967): getConfigValue_native,inputGroup = -1, s_cfgid = g_video__sharpness, i4_value = 50
    D/TV_MtkTvConfig( 4967): Enter setConfigValue, inputGroup=-1, cfgId=g_video__pic_mode, i4_value = 3
Usage: mtkcfg.py <logcat.txt> [--json out.json]
"""
import json
import re
import sys

GET = re.compile(r"getConfigValue_native,\s*inputGroup\s*=\s*(-?\d+),\s*s_cfgid\s*=\s*(\S+?),\s*i4_value\s*=\s*(-?\d+)")
SET = re.compile(r"setConfigValue_native[^,]*,\s*inputGroup\s*=\s*(-?\d+)\s*,\s*s_cfgid\s*=\s*(\S+?)\s*,\s*(?:i4_)?value\s*=\s*(-?\d+)")
SET2 = re.compile(r"Enter setConfigValue.*cfgId\s*=\s*(\S+?)\s*,\s*(?:i4_)?value\s*=\s*(-?\d+)")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    path = sys.argv[1]
    values = {}
    sets = {}
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            m = GET.search(line)
            if m:
                group, key, val = m.groups()
                values.setdefault(key, set()).add(int(val))
                continue
            m = SET.search(line)
            if m:
                group, key, val = m.groups()
                sets.setdefault(key, set()).add(int(val))
                continue
            m = SET2.search(line)
            if m:
                key, val = m.groups()
                sets.setdefault(key, set()).add(int(val))

    print("observed config reads: %d keys" % len(values))
    for key in sorted(values):
        vals = sorted(values[key])
        print("  %-45s %s" % (key, vals if len(vals) < 8 else str(vals[:8]) + "..."))
    if sets:
        print("\nobserved config writes: %d keys" % len(sets))
        for key in sorted(sets):
            print("  %-45s %s" % (key, sorted(sets[key])))

    if "--json" in sys.argv:
        out = sys.argv[sys.argv.index("--json") + 1]
        data = {
            "reads": {k: sorted(v) for k, v in values.items()},
            "writes": {k: sorted(v) for k, v in sets.items()},
        }
        with open(out, "w", encoding="utf-8") as fh:
            json.dump(data, fh, indent=2, sort_keys=True)
        print("\nwrote", out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
