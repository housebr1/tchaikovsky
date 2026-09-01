# Building `liballjoyn_java.so` for Raspberry Pi

Tchaikovsky is pure Java, so `tchaikovsky.jar` is architecture-independent and
runs anywhere. The one per-architecture piece is the AllJoyn JNI library,
`liballjoyn_java.so`, which must be on `java.library.path`.

This directory reproduces that library for **32-bit ARM (armhf)** — Raspberry Pi
OS 32-bit on a Pi 2, Pi 3, Pi 4, or Pi Zero 2 W. The binary itself is not
committed, matching this project's existing convention (`lib/alljoyn_java.dll`
is gitignored).

Note the README's link to `allseenalliance.org` is dead — that domain has been
re-registered and is unrelated to the AllSeen Alliance. Source now comes from
the GitHub mirror.

## Why a container

AllJoyn 16.04 needs Python 2, which was dropped after Debian Bullseye. Building
in an emulated **Bullseye armhf** userland solves two problems at once: Python 2
is still available, and glibc 2.31 is older than any current Pi OS. Since glibc
is backward compatible, the result runs on Bullseye, Bookworm and Trixie alike.

This is emulation, not cross-compilation. The container *is* ARM Debian, so the
compiler, headers and libraries are the real thing and the output is ABI-correct
by construction — no cross-toolchain or dependency juggling.

## Build

Requires Docker with QEMU/binfmt for `linux/arm/v7` (Docker Desktop includes it).

```sh
docker build --platform linux/arm/v7 -t alljoyn-armhf-build .

curl -L -o RB16.04.zip \
    https://github.com/alljoyn/core-alljoyn/archive/refs/heads/RB16.04.zip
unzip -q RB16.04.zip && rm RB16.04.zip
patch -p1 -d core-alljoyn-RB16.04 < alljoyn-RB16.04-pi.patch

docker run --rm --platform linux/arm/v7 -v "$PWD:/build" \
    alljoyn-armhf-build sh /build/build.sh
```

Output lands in
`core-alljoyn-RB16.04/build/linux/arm/release/dist/java/lib/liballjoyn_java.so`.
A prebuilt copy is kept in `native/prebuilt/` (gitignored) when one has been built.
Set `VARIANT=debug` for a build with tracing compiled in (release strips it,
which hides failures on the transport startup path).

On Windows/Git Bash, prefix docker commands with `MSYS_NO_PATHCONV=1` or the
`/build` mount path gets mangled.

## The patch

Five changes across three files, all required to build 2016 code with GCC 10 and
run it on a current kernel.

| # | File | Change | Why |
|---|------|--------|-----|
| 1 | `conf/linux/arm/SConscript` | `-march=armv6` → `armv7-a` | GCC refuses ARMv6 + hard-float VFP + Thumb-1. Every supported Pi is ARMv7+. |
| 2 | `conf/linux/SConscript` | drop `_GLIBCXX_USE_C99_FP_MACROS_DYNAMIC` | On GCC 10 it makes `<math.h>` import `std::` functions that aren't declared. Independent of `-std=c++11` vs `gnu++11`. |
| 3 | `conf/linux/SConscript` | `-Werror` → `-Wno-error` | GCC 10 raises warnings (`class-memaccess`) that didn't exist in 2016. |
| 4 | `conf/linux/SConscript` | add `-fPIC` | `BR=on` links the *static* router lib into the shared JNI lib. Without PIC that yields a TEXTREL `.so` the JVM refuses to `dlopen`. |
| 5 | `common/os/posix/IfConfigLinux.cc` | scan all netlink messages for `NLMSG_DONE` | **The important one — see below.** |

### Patch 5: the netlink hang

`NetlinkRecv()` loops `recv()` until it sees `NLMSG_DONE`, but only inspected the
first `nlmsghdr` in each datagram. Modern kernels coalesce the terminating
`NLMSG_DONE` into the same datagram as the last data message, so AllJoyn saw
`RTM_NEWADDR` at offset 0, missed the trailing `DONE`, and blocked forever in the
next `recv()`.

It is the **IPv6** address query that trips it — the third of `IfConfig`'s three
netlink calls. A host with a single global IPv6 address produces a reply small
enough for the kernel to coalesce; the larger IPv4 reply is not.

The consequences were entirely silent. `qcc::IfConfig()` never returned, so
`IpNameService::OpenInterface()` never returned, so `TCPTransport::DoStartListen()`
never created a socket — and release builds compile out the tracing on that path.
Symptoms: zero `AF_INET` sockets, no listeners on the configured port, no errors
logged, and `BusAttachment.connect()` taking minutes instead of seconds.

The fix walks every message in each datagram. Both callers already `switch` on
and ignore `NLMSG_DONE`/`NLMSG_ERROR`, so including that datagram is safe.

## Running

Install the library where `java.library.path` can find it, then **either**:

**Bundled router** (no extra process):

```sh
java -Dorg.alljoyn.bus.address=null: \
     -Djava.library.path=/home/pi \
     -cp tchaikovsky.jar:. YourApp
```

`-Dorg.alljoyn.bus.address=null:` is **required**. `BusAttachment.connect()`
otherwise defaults to `unix:abstract=alljoyn` and waits for a standalone daemon
that isn't running.

**Standalone daemon** — discovers faster; prefer it if latency matters:

```sh
LD_LIBRARY_PATH=. ./alljoyn-daemon --config-file=alljoyn-daemon.conf &
java -Djava.library.path=/home/pi -cp tchaikovsky.jar:. YourApp
```

Raspberry Pi OS Lite ships no JRE. Either `apt install openjdk-17-jre-headless`,
or unpack a Liberica arm32 hard-float JRE if you lack sudo.

## Verified

Pi Zero 2 W, Raspbian 13 (trixie), kernel 6.18.34, glibc 2.41, OpenJDK 17:

- `ELF32 ARM, Version5 EABI, hard-float`; max symbol `GLIBC_2.28`; needs only
  `libstdc++`, `libpthread`, `libc`; no TEXTREL
- daemon binds tcp+udp on port 9955
- `connect()` in ~1.5s
- five AllPlay speakers discovered, clean disconnect
- works with both the bundled router and the standalone daemon
