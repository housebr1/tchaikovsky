# AllPlay zone controller

`AllPlayController.java` groups every discovered AllPlay speaker into a single
zone and keeps it playing an HTTP stream — for example an Icecast mount fed by
librespot/raspotify.

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

The controller's whole job is `speaker.playItem(url)` plus keeping the speakers
grouped.

## Why a zone

Without one, each speaker pulls the stream independently, buffers on its own
schedule and drifts — you get echo between rooms. `createZone()` is called on
the master with the other speakers as slaves, so AllPlay keeps them in sync.

## Why it waits for the stream

An Icecast mount returns 404 until a source connects, so it is dead whenever
Spotify is idle. Pointing speakers at a dead URL just makes them error, so the
controller polls the mount and only starts playback once it returns 200. If the
stream later drops, it regroups when it comes back.

It also rebuilds the zone when the speaker set changes. AllPlay discovery is
asynchronous and speakers routinely appear after the initial window — in
testing, three showed up within 20s and two more arrived afterwards.

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

The controller serves a plain-text control endpoint (default port 8080), because
the Spotify slider cannot work: the librespot bridge runs with
`LIBRESPOT_VOLUME_CTRL=fixed` and always emits full-scale PCM. The speakers are
the only real volume control.

```sh
curl http://<pi>:8080/status
curl "http://<pi>:8080/volume?level=30"   # absolute, 0-100
curl "http://<pi>:8080/volume?delta=-5"  # relative
curl "http://<pi>:8080/mute?on=true"
curl http://<pi>:8080/stop
curl http://<pi>:8080/play
```

Volume is applied to each speaker individually, scaled into that speaker's own
advertised range, so the rooms match rather than keeping whatever level each was
last left at physically.

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
- **Regrouping** - the zone is rebuilt whenever the known speaker set changes.
- **Backoff** - repeated failures back off up to two minutes instead of
  hammering the speakers and filling the journal.
- **Clean shutdown** - stop, dissolve the zone, drop the speakers, then the bus,
  in that order.

## Options

| Property | Default | Meaning |
|---|---|---|
| `stream.url` | `http://127.0.0.1:8000/spotify.mp3` | Stream for the speakers to play |
| `master.name` | *(first found)* | Which speaker leads the zone |
| `volume` | `35` | Startup volume, 0-100 |
| `control.port` | `8080` | HTTP control endpoint, `0` disables |
| `discovery.seconds` | `25` | Initial discovery window |
| `poll.seconds` | `12` | Supervision interval |
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
