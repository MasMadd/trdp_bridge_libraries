#!/usr/bin/env bash
set -euo pipefail

echo "Ciao 1"

TRDP_SRC="${1:-}"
if [[ -z "$TRDP_SRC" ]]; then
  echo "Usage: $0 ./TRDP/3.0.0.0" >&2
  exit 2
fi

echo "Ciao 2"
cd "$TRDP_SRC"
echo "Ciao 3"
# Ensure config exists
if [[ ! -f config/config.mk ]]; then
  make LINUX_X86_64_config
fi
echo "Ciao 4"
make -j libtrdp
echo "Ciao 5"
# Figure out output dir like bld/output/linux-x86_64-rel
OUTDIR=$(make -pn | awk -F'[:= ]+' '/^OUTDIR[ ]*[:]?=/{print $2; exit}')
if [[ -z "$OUTDIR" ]]; then
  echo "Could not detect OUTDIR" >&2
  exit 1
fi
echo "Ciao 6"
LIBA="$OUTDIR/libtrdp.a"
LIBSO="$OUTDIR/libtrdp.so"

if [[ ! -f "$LIBA" ]]; then
  echo "Expected $LIBA" >&2
  exit 1
fi

echo "Linking $LIBSO from $LIBA"
cc -shared -o "$LIBSO" \
  -Wl,--whole-archive "$LIBA" -Wl,--no-whole-archive \
  -lpthread -lrt -luuid

echo "Done: $LIBSO"
