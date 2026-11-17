#!/bin/bash
# Build x90shot.ko against the Sony 4.19.260-ab626241 tree.
#
# Required environment:
#   KERNEL_TREE          path to the Sony 4.19.260-ab626241 kernel tree
#   SONY_MODULE_SYMVERS  path to the extracted Module.symvers for that build
# Optional:
#   KBUILD_OUT           output directory (default: ./kbuild)
set -e
K="${KERNEL_TREE:?set KERNEL_TREE to the Sony 4.19.260-ab626241 tree}"
SYMS="${SONY_MODULE_SYMVERS:?set SONY_MODULE_SYMVERS to the extracted Module.symvers}"
O="${KBUILD_OUT:-$PWD/kbuild}"
T="$(cd "$(dirname "$0")" && pwd)"
M="$T/build"
mkdir -p "$M" "$O"
cp "$T/x90shot.c" "$M/x90shot.c"
printf 'obj-m := x90shot.o\n' > "$M/Makefile"
make -C "$K" O="$O" M="$M" ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- \
  KBUILD_EXTRA_SYMBOLS="$SYMS" modules 2>&1 | tee "$M/build.txt"
if grep -E "WARNING|undefined|error" "$M/build.txt" | grep -v "Clock skew\|modification time"; then
  echo X90SHOT_BUILD_REJECTED; exit 1
fi
sha256sum "$M/x90shot.ko" | tee "$M/x90shot.ko.sha256"
echo X90SHOT_BUILD_OK
