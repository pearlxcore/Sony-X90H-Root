#!/usr/bin/env python3
"""Surgical Sony X90H misc/BCB patch.

Clears only the boot-command area at 0x000..0x3FF and preserves everything
else, especially the AB0 slot metadata at 0x800. This is the repair documented
in the recovery handoff for the 2026-11-17 recovery loop.

This tool never writes in place. It writes a new file and prints SHA256 before
and after.

Do NOT flash an all-zero full misc image. It wipes the AB0 metadata.
"""
import argparse
import hashlib
import sys
from pathlib import Path

BCB_START = 0x000
BCB_END = 0x400          # command[32] + status[32] + recovery[1024]
FASTBOOT_SLOT = 0x200
AB0 = 0x800
MIN_SIZE = 0x1000


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def printable(data):
    return "".join(chr(b) if 32 <= b < 127 else "." for b in data)


def show(data):
    print(f"size       {len(data)} bytes")
    print(f"0x000 cmd  {printable(data[0x000:0x020])}")
    print(f"0x020 stat {printable(data[0x020:0x040])}")
    print(f"0x040 rec  {printable(data[0x040:0x080])}")
    print(f"0x200 fb   {printable(data[0x200:0x208])}")
    print(f"0x800 ab0  {data[0x800:0x810].hex()}")


def main():
    ap = argparse.ArgumentParser(description="Surgical misc/BCB clear (never in place)")
    ap.add_argument("image", help="misc partition image")
    ap.add_argument("--clear-bcb", action="store_true",
                    help="zero 0x000..0x3FF and preserve the rest")
    ap.add_argument("--check", action="store_true", help="print the BCB fields")
    ap.add_argument("--output", help="output path (never in place)")
    ap.add_argument("--force", action="store_true", help="overwrite the output")
    args = ap.parse_args()

    raw = Path(args.image).read_bytes()
    if len(raw) < MIN_SIZE:
        print(f"ERROR: expected at least {MIN_SIZE} bytes, got {len(raw)}", file=sys.stderr)
        return 2

    print(f"file       {args.image}")
    print(f"sha256     {sha256(args.image)}")
    show(raw)

    if not args.clear_bcb:
        if args.check:
            return 0
        print("No action. Use --clear-bcb --output FILE to write a patched copy.")
        return 0

    if not args.output:
        print("ERROR: --clear-bcb requires --output FILE", file=sys.stderr)
        return 2
    out = Path(args.output)
    if out.resolve() == Path(args.image).resolve():
        print("ERROR: refusing to write in place", file=sys.stderr)
        return 2
    if out.exists() and not args.force:
        print(f"ERROR: {out} exists; use --force", file=sys.stderr)
        return 2

    data = bytearray(raw)
    data[BCB_START:BCB_END] = b"\x00" * (BCB_END - BCB_START)
    out.write_bytes(data)

    print(f"wrote      {out}")
    print(f"sha256     {sha256(out)}")
    show(data)
    print("AB0 at 0x800 preserved; command area 0x000..0x3FF cleared.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
