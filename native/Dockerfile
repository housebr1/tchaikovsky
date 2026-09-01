# Bullseye rather than Bookworm on purpose:
#  - it still carries python2, which AllJoyn 16.04's SConstruct requires
#  - glibc 2.31 is older than any current Pi OS, and glibc is backward
#    compatible, so the .so built here runs on Bullseye or Bookworm alike.
FROM debian:bullseye-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential git python2 libssl-dev ca-certificates curl \
        openjdk-11-jdk-headless unzip xz-utils junit4 libcap-dev \
    && rm -rf /var/lib/apt/lists/*

# SCons 4.x (the distro package) is Python 3 only. 3.1.2 is the last release
# that still runs under Python 2.
RUN curl -fsSL -o /tmp/scons.tar.gz \
      https://files.pythonhosted.org/packages/de/95/29892f46a6da11e90154147b96a18695c8562e94119e8084352f71e61d24/scons-3.1.2.tar.gz \
 && tar -xzf /tmp/scons.tar.gz -C /opt \
 && rm /tmp/scons.tar.gz

ENV JAVA_HOME=/usr/lib/jvm/java-11-openjdk-armhf
ENV SCONS=/opt/scons-3.1.2/scripts/scons.py
WORKDIR /build
