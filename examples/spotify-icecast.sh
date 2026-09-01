#!/bin/bash
# Feeds librespot PCM into Icecast as MP3.
#
# The source credential is NOT stored here. It lives in /etc/icecast2/source.env
# (mode 600, root) so this script can stay readable without leaking it.
#
# The FIFO must be read at the PCM sample rate. Without pacing, ffmpeg drains
# it as fast as Icecast and the speakers will accept bytes, so librespot races
# ahead (observed ~2 min jumps of the Spotify app timer on pause).
#
# ffmpeg -re is the usual fix but it catches up after a stall: a pause of N
# seconds is burst-read on resume, librespot jumps N seconds ahead, and the
# app reveals that the next time it pauses. pcm-pace is a token bucket with
# 100 ms of burst so idle time does not accumulate credit.
#
# Skip still needs the controller to playItem() so the speakers drop their
# own 15-20s HTTP buffer.
#
# Icecast source-timeout must be far longer than a pause (86400 on this host).
# The default 300s drops the source while ffmpeg is blocked on an idle FIFO;
# the next PCM then hits a dead socket, ffmpeg dies, and first play waits for
# a systemd restart plus the controller's poll.
set -euo pipefail

FIFO="/var/lib/raspotify/spotify.pcm"
PORT="8000"
MOUNT="/spotify.mp3"
BITRATE="160"

# shellcheck disable=SC1091
. /etc/icecast2/source.env

mkdir -p /var/lib/raspotify
if [[ ! -p "${FIFO}" ]]; then
  rm -f "${FIFO}"
  mkfifo "${FIFO}"
  chmod 666 "${FIFO}"
fi

# Pipeline, not exec: systemd KillMode=control-group reaps both. python -u so
# PCM is not stuck in a stdio buffer.
python3 -u /usr/local/bin/pcm-pace < "${FIFO}" | \
/usr/bin/ffmpeg -nostdin -hide_banner -fflags nobuffer -flags low_delay -loglevel warning \
  -f s16le -ar 44100 -ac 2 -i pipe:0 \
  -c:a libmp3lame -b:a "${BITRATE}k" -ar 44100 -ac 2 -reservoir 0 \
  -muxdelay 0 -muxpreload 0 -flush_packets 1 \
  -content_type audio/mpeg -f mp3 \
  "icecast://${ICECAST_SOURCE_USER}:${ICECAST_SOURCE_PASS}@127.0.0.1:${PORT}${MOUNT}"
