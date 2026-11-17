#!/usr/bin/env python3
"""Parse and CRC-check a Sony U-Boot environment block.

The environment is the first 128 KiB of uboot_env_a:
  offset 0x0000: little-endian CRC32
  offset 0x0004: NUL-separated key=value strings, zero-padded
  CRC32 covers data[4:0x20000]

This tool is read-only by default. It writes only when --write --output FILE
is given. It never writes in place.

Changing devicestate is the S11 experiment. It produced orange/unlocked but
skipped official provisioning and can boot-loop the TV. Do not use this tool
as an unlock recipe.
"""
import argparse
import hashlib
import struct
import sys
import zlib
from pathlib import Path

ENV_SIZE = 0x20000


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_env(path):
    raw = Path(path).read_bytes()
    if len(raw) < ENV_SIZE:
        raise ValueError(f"{path}: expected at least {ENV_SIZE} bytes, got {len(raw)}")
    return raw[:ENV_SIZE], raw


def parse_vars(block):
    body = block[4:ENV_SIZE]
    out = []
    for token in body.split(b"\x00"):
        if not token:
            continue
        text = token.decode("ascii", "replace")
        if "=" in text:
            out.append(text)
    return out


def crc_state(block):
    stored = struct.unpack("<I", block[:4])[0]
    calc = zlib.crc32(block[4:ENV_SIZE]) & 0xFFFFFFFF
    return stored, calc, stored == calc


def build_env(variables):
    body = b"\x00".join(v.encode("ascii") for v in variables) + b"\x00"
    if len(body) > ENV_SIZE - 4:
        raise ValueError("environment data does not fit in 128 KiB")
    body = body.ljust(ENV_SIZE - 4, b"\x00")
    crc = zlib.crc32(body) & 0xFFFFFFFF
    return struct.pack("<I", crc) + body


def main():
    ap = argparse.ArgumentParser(description="Parse/check a Sony U-Boot environment block")
    ap.add_argument("image", help="uboot_env_a image or partition dump")
    ap.add_argument("--check", action="store_true", help="verify the CRC")
    ap.add_argument("--list", action="store_true", help="list variables")
    ap.add_argument("--set", action="append", default=[], metavar="KEY=VALUE",
                    help="set/replace a variable (requires --write --output)")
    ap.add_argument("--write", action="store_true", help="write a new image")
    ap.add_argument("--output", help="output path (never in place)")
    ap.add_argument("--force", action="store_true", help="overwrite the output")
    ap.add_argument("--i-understand-s11-risk", action="store_true",
                    help="required to change devicestate away from lock")
    args = ap.parse_args()

    block, raw = read_env(args.image)
    stored, calc, ok = crc_state(block)

    print(f"file       {args.image}")
    print(f"size       {len(raw)} bytes")
    print(f"crc stored {stored:#010x}")
    print(f"crc calc   {calc:#010x}")
    print(f"crc ok     {ok}")
    print(f"sha256     {sha256(args.image)}")

    if args.check and not ok:
        return 2

    variables = parse_vars(block)
    if args.list or not args.set:
        for v in variables:
            print(v)

    if args.set:
        risky = False
        for item in args.set:
            k, sep, val = item.partition("=")
            if sep and k == "devicestate" and val != "lock":
                risky = True
        if risky and not args.i_understand_s11_risk:
            print("ERROR: changing devicestate is the S11 experiment; it can break "
                  "the TEE/keymaster path and boot-loop the TV. Re-run with "
                  "--i-understand-s11-risk only if you accept that.", file=sys.stderr)
            return 2
        if not args.write or not args.output:
            print("ERROR: --set requires --write --output FILE", file=sys.stderr)
            return 2
        out = Path(args.output)
        if out.resolve() == Path(args.image).resolve():
            print("ERROR: refusing to write in place", file=sys.stderr)
            return 2
        if out.exists() and not args.force:
            print(f"ERROR: {out} exists; use --force", file=sys.stderr)
            return 2

        current = {}
        for v in variables:
            k, _, val = v.partition("=")
            current[k] = val
        for item in args.set:
            k, sep, val = item.partition("=")
            if not sep or not k:
                print(f"ERROR: bad --set {item!r}", file=sys.stderr)
                return 2
            current[k] = val

        new_block = build_env([f"{k}={v}" for k, v in current.items()])
        # Preserve any bytes beyond the 128 KiB environment block.
        new_raw = new_block + raw[ENV_SIZE:]
        out.write_bytes(new_raw)
        print(f"wrote      {out}")
        print(f"sha256     {sha256(out)}")
        print(f"crc        {crc_state(new_block)[1]:#010x}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
