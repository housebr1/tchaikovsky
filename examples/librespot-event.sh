#!/bin/bash
#
# Bridges the Spotify app's volume slider to the AllPlay speakers.
#
# Install as /usr/local/bin/librespot-event.sh (mode 755) and point librespot at
# it with LIBRESPOT_ONEVENT. It requires these settings in /etc/raspotify/conf:
#
#   LIBRESPOT_VOLUME_CTRL="log"
#   LIBRESPOT_VOLUME_RANGE="0.0"
#   LIBRESPOT_ONEVENT=/usr/local/bin/librespot-event.sh
#
# Why that pairing:
#
#   volume-ctrl=fixed reports 65535 once at session connect and then swallows
#   every slider change, so no volume_changed events fire at all and the app's
#   slider does nothing. Verified by logging events with fixed set.
#
#   volume-ctrl=log/linear/cubic does track the slider, but softvol is the only
#   mixer available on the pipe backend, so it attenuates the PCM *before* the
#   MP3 encoder. That costs signal-to-noise at exactly the moment you want the
#   signal loud, and a change has to traverse the FIFO, encoder and Icecast and
#   speaker buffers before you hear it - several seconds of lag.
#
#   volume-range 0.0 resolves that: librespot tracks the slider and emits
#   volume_changed events, but applies a 0 dB span, so the stream stays at full
#   scale. Attenuation happens at the speakers, where it is instant, per-room
#   capable, and downstream of the encoder. Confirm with:
#     journalctl -u raspotify | grep softvol   ->  volume control: Log(0.0)
#
# Note LIBRESPOT_INITIAL_VOLUME also propagates to the speakers on every session
# connect once volume-ctrl is no longer fixed. Keep it moderate; 100 will start
# every listening session at full volume in every room.

set -uo pipefail

CONTROL="${ALLPLAY_CONTROL:-http://127.0.0.1:8080}"
LOG="${ALLPLAY_EVENT_LOG:-/tmp/librespot-events.log}"

echo "$(date +%T) event=${PLAYER_EVENT:-?} volume=${VOLUME:-none}" >> "$LOG" 2>&1

if [[ "${PLAYER_EVENT:-}" == "volume_changed" && -n "${VOLUME:-}" ]]; then
    # librespot reports 0-65535; the controller wants 0-100. Round to nearest.
    level=$(( (VOLUME * 100 + 32767) / 65535 ))
    if ! curl -fsS --max-time 4 "${CONTROL}/volume?level=${level}" >> "$LOG" 2>&1; then
        echo "$(date +%T) bridge failed for level=${level}" >> "$LOG"
    fi
fi

exit 0
