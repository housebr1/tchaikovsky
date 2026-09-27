# AllPlay zone controller

`AllPlayController.java` plays an HTTP stream on the AllPlay group that is
already on the speakers — for example an Icecast mount fed by librespot/raspotify.
The standalone AllPlay app chooses the rooms. This controller follows that group.

## How the pieces fit

AllJoyn is a **control plane**, not an audio transport. Audio never passes
through it:

```
Spotify ──► librespot ──► FIFO ──► ffmpeg (MP3) ──► Icecast :8000/spotify.mp3
                                                            │ plain HTTP
                                                            ▼
                                                    AllPlay speakers
            AllPlayController ──────────────────►  tells them which URL to pull
```

The controller's whole job is `playItem(url)` on the group lead, plus volume for
the rooms in that group.

## Why a zone

Without one, each speaker pulls the stream independently, buffers on its own
schedule and drifts — you get echo between rooms. The AllPlay app creates the
zone. This controller creates one only when the speakers report that there isn't
one: the rooms it remembers, or every room that is on if nothing is remembered
yet. `GET /group` does that second thing on purpose and remembers the result.

Spotify cannot ask for this. The Spotify app only plays, pauses, skips, seeks
and sets volume, and those already reach the speakers. To put every room that
is on into the group, open `http://<pi>:8080/group` (a home-screen bookmark is
the practical button). A room that is switched off at that moment is not
included.

Leaving rooms out is done in the AllPlay app. The controller notices, remembers
the smaller set, and does not put the others back on the next track. Pause,
skip and seek keep the group. Restarting the controller does not dissolve it.
A speaker that appears after the group exists is not pulled in; add it from the
app, or ask for all rooms that are on again.

## Why it waits for the stream

An Icecast mount has no source until ffmpeg connects, so it is dead whenever
nothing is feeding it. Pointing speakers at a dead URL just makes them error, so
the controller checks Icecast's status page and only starts playback once the
mount is listed. It must not request the audio URL itself: that joins as a
second listener and the rooms already playing hear a short silence, once per
poll. If the stream later drops, playback starts again when it comes back, on
the group that is already there.

## Build and deploy

```sh
javac --release 8 -cp tchaikovsky.jar -d out examples/AllPlayController.java

ssh pi@<pi> mkdir -p '~/allplay'
scp out/*.class tchaikovsky.jar liballjoyn_java.so pi@<pi>:~/allplay/
```

Run it by hand first:

```sh
java -Djava.library.path=$HOME/allplay \
     -Dstream.url=http://127.0.0.1:8000/spotify.mp3 \
     -cp tchaikovsky.jar:. AllPlayController
```

Then install `allplay-controller.service`:

```sh
sudo cp allplay-controller.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now allplay-controller
journalctl -u allplay-controller -f
```

## Control endpoint

The controller serves a plain-text control endpoint (default port 8080). Volume
lives here rather than in the stream: it is applied to each speaker's own volume
over AllJoyn — the same control the physical buttons drive — so it takes effect
instantly and downstream of the MP3 encoder.

The Spotify app drives this endpoint too, via
[`librespot-event.sh`](librespot-event.sh): volume, pause, resume, skip and
seek. See that script for the required `LIBRESPOT_*` settings and why
`volume-ctrl=fixed` makes the slider inert while `log` with `volume-range 1.0`
makes it work without attenuating the stream. (`0.0` is rejected by librespot
and silently falls back to linear.)

The Icecast feeder must read the PCM FIFO at realtime without catching up after
a stall — that is [`pcm-pace`](pcm-pace), not ffmpeg `-re`. `-re` burst-reads
the pause duration on resume and the Spotify scrubber jumps by that amount on
the next pause.

```sh
curl http://<pi>:8080/status
curl "http://<pi>:8080/volume?level=30"   # absolute, 0-100
curl "http://<pi>:8080/volume?delta=-5"  # relative
curl "http://<pi>:8080/band?ceiling=45"   # cap the usable band
curl "http://<pi>:8080/mute?on=true"
curl http://<pi>:8080/pause
curl http://<pi>:8080/resume
curl http://<pi>:8080/skip
curl http://<pi>:8080/seek
curl http://<pi>:8080/stop
curl http://<pi>:8080/play
curl http://<pi>:8080/group   # every room that is on, right now
```

`/status` lists the lead, the rooms in the group, and the rooms left out.

Volume is applied to each speaker in the group, scaled into that speaker's own
advertised range, so the rooms match rather than keeping whatever level each was
last left at physically.

`volume.floor`/`volume.ceiling` narrow the usable band: the 0–100 slider maps
onto that slice of each speaker's range instead of the whole thing. The top of a
speaker's range is usually far louder than anyone wants indoors, which leaves the
useful adjustment crammed into the bottom of the slider. Tune it by ear with
`/band?ceiling=N` — set the slider near maximum, then walk the ceiling down until
full-slider is as loud as you would ever want.

## Robustness

For unattended use the controller supervises its own state every `poll.seconds`:

- **Bus recovery** - if the AllJoyn bus drops, it rebuilds the attachment and
  rediscovers. Old `Speaker` handles belong to the dead attachment, so they are
  discarded rather than reused.
- **Playback watchdog** - if the master stops while the stream is live, playback
  restarts. It waits out a settling period after starting and needs two
  consecutive bad readings first, because speakers report `STOPPED` for a while
  as they open the stream and restarting on a single sample causes an audible
  glitch.
- **The app's group is followed** - a group already on the speakers is not
  rebuilt when a speaker appears or disappears. It is formed only when there is
  none, or when `/group` asks for every room that is on. Shutdown does not
  release it.
- **Backoff** - repeated failures back off up to two minutes instead of
  hammering the speakers and filling the journal.
- **Volume survives restarts** - volume, mute, band and the remembered rooms are
  persisted. librespot
  only emits `volume_changed` when the slider actually moves, so without this a
  restart resets to the default and nothing corrects it until someone touches
  the slider; that presents as the speakers being inaudible after a reboot.
- **Clean shutdown** - cleanup runs on SIGTERM rather than from a JVM shutdown
  hook, because `alljoyn.jar` registers its own hook and JVM hooks run
  concurrently in no defined order. It stops playback and drops the bus, and
  leaves the speaker group in place.

## Options

| Property | Default | Meaning |
|---|---|---|
| `stream.url` | `http://127.0.0.1:8000/spotify.mp3` | Stream for the speakers to play |
| `master.name` | *(current lead, else first found)* | Preferred lead when this controller forms a group, and which group to follow when several exist |
| `volume` | `35` | Startup volume, 0-100 |
| `control.port` | `8080` | HTTP control endpoint, `0` disables |
| `volume.floor` | `0` | Bottom of the usable band, 0-100 |
| `volume.ceiling` | `100` | Top of the usable band, 0-100 |
| `discovery.seconds` | `25` | Initial discovery window |
| `poll.seconds` | `12` | Supervision interval |
| `state.file` | `allplay.state` | Remembers volume/mute/band and the group across restarts |
| `org.alljoyn.bus.address` | `null:` | Router to use |

A loopback host in `stream.url` is rewritten to this host's LAN address, since
the speakers fetch the stream from their own machines — `127.0.0.1` would point
each speaker at itself and it would play nothing.

`org.alljoyn.bus.address` defaults to `null:` (the router bundled into
`liballjoyn_java.so`), because the AllJoyn default of `unix:abstract=alljoyn`
blocks for minutes waiting for a standalone daemon that usually isn't running.
Clear it if you do run `alljoyn-daemon` — that discovers noticeably faster
(five speakers in ~11s versus three in ~30s in testing).

See [../native/README.md](../native/README.md) for building `liballjoyn_java.so`.
