#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# build-native-unix.sh
#
# Compiles the TRDP C library for the current Unix platform (Linux or macOS)
# and copies the resulting shared library into the JNA auto-extraction path
# inside the Maven resources tree:
#
#   src/main/resources/com/sun/jna/<jna-platform>/lib<name>.<ext>
#
# JNA picks the right file at runtime based on Platform.RESOURCE_PREFIX,
# extracts it to a temp directory, and loads it — completely transparent to
# the consumer of the Java library.
#
# Usage:
#   ./scripts/build-native-unix.sh          # auto-detects platform
#   ./scripts/build-native-unix.sh --debug  # build with DEBUG=TRUE
#
# Prerequisites:
#   Linux : gcc, make, libuuid-dev (Debian/Ubuntu) or libuuid-devel (RHEL)
#   macOS : Xcode Command Line Tools  (xcode-select --install)
# ---------------------------------------------------------------------------

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAVA_PROJECT="$(dirname "$SCRIPT_DIR")"
TRDP_ROOT="$(dirname "$(dirname "$JAVA_PROJECT")")/TRDP/3.0.0.0"
RESOURCES_BASE="$JAVA_PROJECT/src/main/resources/com/sun/jna"

DEBUG_BUILD=0
[[ "${1:-}" == "--debug" ]] && DEBUG_BUILD=1

# ── Platform detection ──────────────────────────────────────────────────────

OS=$(uname -s)
ARCH=$(uname -m)

echo "==> Detected platform: $OS / $ARCH"

case "$OS-$ARCH" in
    Linux-x86_64)
        TRDP_CONFIG=LINUX_X86_64_config
        TRDP_ARCH=linux-x86_64
        JNA_PLATFORM=linux-x86-64
        LINK_FLAGS="-lrt -luuid -lpthread"
        LINK_CMD="gcc -shared -fPIC"
        LIB_EXT=so
        ;;
    Linux-aarch64)
        # No official aarch64 config ships with TRDP 3.0.0.0;
        # LINUX_config is the most generic POSIX fallback.
        # You may want to create a dedicated LINUX_AARCH64_config based on
        # LINUX_X86_64_config with ARCH=linux-aarch64 and no -m64/-m32 flags.
        TRDP_CONFIG=LINUX_config
        TRDP_ARCH=linux-aarch64
        JNA_PLATFORM=linux-aarch64
        LINK_FLAGS="-lrt -luuid -lpthread"
        LINK_CMD="gcc -shared -fPIC"
        LIB_EXT=so
        ;;
    Darwin-x86_64)
        TRDP_CONFIG=OSX_X86_64_config
        TRDP_ARCH=osx_x86_64
        JNA_PLATFORM=darwin-x86-64
        LINK_FLAGS="-lpthread"
        LINK_CMD="gcc -dynamiclib -fPIC"
        LIB_EXT=dylib
        ;;
    Darwin-arm64)
        # Apple Silicon: the shipped OSX_X86_64_config adds -m64 which forces
        # x86_64 output (works under Rosetta 2 with an x86_64 JVM).
        # To produce a *native* arm64 dylib, create OSX_ARM64_config from
        # OSX_X86_64_config removing -m64 and setting ARCH=osx_arm64, then
        # re-run this script (it will auto-detect the new config).
        if [ -f "$TRDP_ROOT/config/OSX_ARM64_config" ]; then
            TRDP_CONFIG=OSX_ARM64_config
            TRDP_ARCH=osx_arm64
        else
            echo "==> NOTE: OSX_ARM64_config not found."
            echo "    Using OSX_X86_64_config with -m64 (Rosetta 2 / x86_64 JVM only)."
            echo "    To build native arm64: copy OSX_X86_64_config → OSX_ARM64_config,"
            echo "    remove -m64, set ARCH=osx_arm64, then re-run."
            TRDP_CONFIG=OSX_X86_64_config
            TRDP_ARCH=osx_x86_64
        fi
        JNA_PLATFORM=darwin-aarch64
        LINK_FLAGS="-lpthread"
        LINK_CMD="gcc -dynamiclib -fPIC"
        LIB_EXT=dylib
        ;;
    *)
        echo "ERROR: Unsupported platform $OS-$ARCH" >&2
        exit 1
        ;;
esac

BUILD_SUFFIX="rel"
MAKE_ARGS=""
if [ "$DEBUG_BUILD" -eq 1 ]; then
    BUILD_SUFFIX="dbg"
    MAKE_ARGS="DEBUG=TRUE"
fi

OUTDIR="$TRDP_ROOT/bld/output/${TRDP_ARCH}-${BUILD_SUFFIX}"
LIB_FILE="$OUTDIR/libtrdp.$LIB_EXT"

# ── Build ───────────────────────────────────────────────────────────────────

echo "==> TRDP source: $TRDP_ROOT"
echo "==> Config     : $TRDP_CONFIG"
echo "==> Output dir : $OUTDIR"

cd "$TRDP_ROOT"

echo "==> Selecting config..."
make "${TRDP_CONFIG}"

echo "==> Building static library + objects..."
# shellcheck disable=SC2086
make libtrdp $MAKE_ARGS

# ── Link shared library ─────────────────────────────────────────────────────

echo "==> Linking shared library: $LIB_FILE"

# Collect all .o files produced by the build
OBJ_FILES=$(find "$OUTDIR" -maxdepth 1 -name '*.o' | tr '\n' ' ')

if [ -z "$OBJ_FILES" ]; then
    echo "ERROR: No .o files found in $OUTDIR" >&2
    exit 1
fi

# shellcheck disable=SC2086
$LINK_CMD -o "$LIB_FILE" $OBJ_FILES $LINK_FLAGS

echo "==> Linked: $LIB_FILE"

# ── Copy to JNA resource path ───────────────────────────────────────────────

DEST_DIR="$RESOURCES_BASE/$JNA_PLATFORM"
mkdir -p "$DEST_DIR"
cp "$LIB_FILE" "$DEST_DIR/"

echo ""
echo "==> Done. Library installed at:"
echo "    $DEST_DIR/libtrdp.$LIB_EXT"
echo ""
echo "    JNA will auto-extract and load it on $JNA_PLATFORM at runtime."
