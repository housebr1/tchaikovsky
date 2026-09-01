# Provenance of the prebuilt binaries

These are committed deliberately. AllJoyn is abandoned upstream, the AllSeen
Alliance download site is gone, and rebuilding takes an emulated-ARM container
and roughly 20 minutes, so a known-good artifact is worth keeping.

## What they are

Built 2026-08-31 from github.com/alljoyn/core-alljoyn branch RB16.04
with ../alljoyn-RB16.04-pi.patch applied (sha256 cf71c7c2cd137c8e...),
in the Debian Bullseye armhf container defined by ../Dockerfile.

    scons OS=linux CPU=arm VARIANT=release CROSS_COMPILE=arm-linux-gnueabihf-           BINDINGS=java CRYPTO=builtin WS=off BR=on DOCS=none

See ../README.md for why each of those flags is set, and for the five patches -
the fifth is a genuine runtime bug in AllJoyn's netlink handling that makes the
IP transports silently never start on a modern kernel.

## Checksums

    1eca15f4fbc997f40822f22ab37983836b7d74df699e5b228ec21259d6b29fb8 liballjoyn_java.so
    202026606f59447355708353cbb907d6beabfbba900427e83ffe4d746daa7f8f alljoyn-daemon
    f982ae57e0fee31bbf10a537a3563982081858f4c384a03baab70bf8738f276c liballjoyn.so

## ELF properties

All three: ELF32 ARM, Version5 EABI, hard-float ABI, no TEXTREL.
Max glibc symbol required: 2.28 for the libraries, 2.4 for the daemon - so they
run on Bullseye (2.31), Bookworm (2.36) and Trixie (2.41) alike. Built against
Bullseye deliberately, since glibc is backward compatible and Bullseye is the
last Debian with the python2 that AllJoyn's SConstruct needs.

liballjoyn_java.so depends only on libstdc++.so.6, libpthread.so.0 and
libc.so.6 - CRYPTO=builtin means there is no OpenSSL dependency.

## Verified on hardware

Raspberry Pi Zero 2 W, Raspbian 13 (trixie), kernel 6.18.34, glibc 2.41,
Liberica JRE 17 (arm32 hard-float):

  - five AllPlay speakers discovered, grouped into one zone, playing in sync
  - BusAttachment.connect() ~1.5s
  - survives reboot; the controller service comes up and is playing in ~70s

## Which file you need

  liballjoyn_java.so   the JNI library; put it on java.library.path. This is
                       the only file needed when using the bundled router
                       (-Dorg.alljoyn.bus.address=null:).
  alljoyn-daemon       standalone router, discovers faster than the bundled one
  liballjoyn.so        shared library the daemon links against
  alljoyn-daemon.conf  working daemon config
