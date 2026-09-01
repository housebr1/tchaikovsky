#!/bin/sh
#
# Runs INSIDE the armhf build container (see README.md). Builds the AllJoyn
# Java binding for 32-bit ARM and reports where the artifacts landed.
#
set -e
SRC=/build/core-alljoyn-RB16.04
export PYTHONPATH=/opt/scons-3.1.2/engine

if [ ! -d "$SRC" ]; then
    echo "error: $SRC not found - see README.md for the download/patch steps" >&2
    exit 1
fi
cd "$SRC"

# CRYPTO=builtin: AllJoyn 16.04 predates OpenSSL 1.1's opaque structs and will
#                 not compile against them. Builtin also leaves the .so with no
#                 OpenSSL dependency at all.
# BR=on:          alljoyn_java/jni/SConscript requires the bundled router be
#                 linked into the JNI library.
# CROSS_COMPILE:  on Debian armhf the arm-linux-gnueabihf-* binaries ARE the
#                 native toolchain; the prefix just satisfies AllJoyn's
#                 linux/arm config, which insists on one.
python2 /opt/scons-3.1.2/script/scons \
    OS=linux CPU=arm VARIANT="${VARIANT:-release}" \
    CROSS_COMPILE=arm-linux-gnueabihf- \
    BINDINGS=java CRYPTO=builtin \
    WS=off BR=on DOCS=none \
    -j"$(nproc)"

V="${VARIANT:-release}"
echo
echo "Artifacts:"
ls -la "$SRC/build/linux/arm/$V/dist/java/lib/liballjoyn_java.so"
ls -la "$SRC/build/linux/arm/$V/dist/cpp/bin/alljoyn-daemon"
