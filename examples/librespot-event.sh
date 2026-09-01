#!/bin/bash
#
# Bridges Spotify player events to the AllPlay speakers.
#
# Install as /usr/local/bin/librespot-event.sh (mode 755) and point librespot at
# it with LIBRESPOT_ONEVENT. Requires in /etc/raspotify/conf:
#
#   LIBRESPOT_VOLUME_CTRL="log"
#   LIBRESPOT_VOLUME_RANGE="1.0"
#   LIBRESPOT_ONEVENT=/usr/local/bin/librespot-event.sh
#
# Why volume-ctrl=log with range 1.0:
#
#   fixed reports 65535 once at session connect and then swallows every slider
#   change, so no volume_changed event fires and the app's slider is inert.
#
#   log/linear/cubic do track the slider, but softvol is the only mixer on the
#   pipe backend, so it attenuates the PCM before the MP3 encoder and a change
#   has to cross the FIFO, encoder and Icecast and speaker buffers before it is
#   audible.
#
#   range 0.0 looks like the answer but librespot REJECTS it -- "Log(0.0) does
#   not work with 0 dB range, using linear mapping instead" -- and silently
#   attenuates. 1.0 is accepted and spans at most 1 dB across the whole slider,
#   so the stream stays effectively full scale and the speakers do the real
#   attenuating. Confirm with:
#     journalctl -u raspotify | grep softvol   ->  volume control: Log(1.0)
#
#   LIBRESPOT_INITIAL_VOLUME also propagates to the speakers once volume-ctrl is
#   not fixed. Keep it moderate; 100 starts every session at full volume.
#
# Why transport events are forwarded at all:
#
#   Stopping the source only stops refilling the pipeline. The speakers keep
#   playing what they have already buffered -- 15-20s, most of it inside the
#   speakers where it cannot be tuned away -- so pause and skip have to be sent
#   to the speakers directly.
#
# Event mapping:
#
#   paused / stopped  -> /pause    (controller debounces; librespot emits a
#                                   transient paused on track change and session
#                                   connect, and stopping on those kills mid-song)
#   playing           -> /resume   (also fires on every track change; harmless,
#                                   it is a no-op while already streaming)
#   track_changed     -> /skip     (re-opens the URL on the existing zone so the
#                                   speakers drop the previous track's buffer)
#   seeked            -> /seek     (same reopen; scrubbing otherwise leaves the
#                                   speakers on the pre-seek Icecast buffer)
#   volume_changed    -> /volume
set -uo pipefail

CONTROL="${ALLPLAY_CONTROL:-http://127.0.0.1:8080}"
LOG="${ALLPLAY_EVENT_LOG:-/tmp/librespot-events.log}"

echo "$(date +%T) event=${PLAYER_EVENT:-?} volume=${VOLUME:-none}" >> "$LOG" 2>&1

call() {
    curl -fsS --max-time 4 "${CONTROL}/$1" >> "$LOG" 2>&1 \
        || echo "$(date +%T) bridge failed: $1" >> "$LOG"
}

case "${PLAYER_EVENT:-}" in
    volume_changed)
        if [[ -n "${VOLUME:-}" ]]; then
            # librespot reports 0-65535; the controller wants 0-100.
            call "volume?level=$(( (VOLUME * 100 + 32767) / 65535 ))"
        fi
        ;;
    paused|stopped)
        call "pause"
        ;;
    playing)
        call "resume"
        ;;
    track_changed)
        # The controller ignores this while paused, so a skip made while paused
        # does not start anything; the following resume plays the new track.
        call "skip"
        ;;
    seeked)
        call "seek"
        ;;
esac

exit 0
