/**
 * Groups every discovered AllPlay speaker into one zone and keeps it playing an
 * HTTP stream (for example an Icecast mount fed by librespot), then supervises
 * that state so it survives speaker reboots, network blips and stream outages.
 *
 * Audio never flows through AllJoyn. The speakers pull the stream over plain
 * HTTP; this controller only tells them which URL to pull, keeps them grouped
 * so they stay in sync, and owns their volume.
 *
 * Volume lives here because a librespot bridge running with
 * LIBRESPOT_VOLUME_CTRL=fixed always emits full-scale PCM, so the Spotify app's
 * slider does nothing. The AllPlay speakers are the only real volume control,
 * and they are reachable through this API.
 *
 * Configuration, all via system properties:
 *   -Dstream.url=http://host:8000/spotify.mp3   stream for the speakers to play
 *   -Dmaster.name=Kitchen                       which speaker leads the zone
 *   -Dvolume=35                                 startup volume, 0-100
 *   -Dcontrol.port=8080                         HTTP control endpoint, 0 disables
 *   -Ddiscovery.seconds=25                      initial discovery window
 *   -Dpoll.seconds=12                           supervision interval
 *
 * Licensed under the Apache License, Version 2.0.
 */
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import de.kaizencode.tchaikovsky.AllPlay;
import de.kaizencode.tchaikovsky.exception.AllPlayException;
import de.kaizencode.tchaikovsky.listener.SpeakerAnnouncedListener;
import de.kaizencode.tchaikovsky.listener.SpeakerConnectionListener;
import de.kaizencode.tchaikovsky.speaker.PlayState;
import de.kaizencode.tchaikovsky.speaker.Speaker;
import de.kaizencode.tchaikovsky.speaker.Volume;
import de.kaizencode.tchaikovsky.speaker.VolumeRange;
import de.kaizencode.tchaikovsky.speaker.ZoneItem;

public class AllPlayController {

    private static final String STREAM_URL =
            reachableBySpeakers(System.getProperty("stream.url", "http://127.0.0.1:8000/spotify.mp3"));
    private static final String MASTER_NAME = System.getProperty("master.name", "");
    private static final int DISCOVERY_SECONDS = Integer.getInteger("discovery.seconds", 25);
    private static final int POLL_SECONDS = Integer.getInteger("poll.seconds", 12);
    private static final int CONTROL_PORT = Integer.getInteger("control.port", 8080);
    private static final int MAX_BACKOFF_SECONDS = 120;
    private static final int GRACE_SECONDS = 40;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Discovered speakers by device id. Written from the AllJoyn callback thread. */
    private final Map<String, Speaker> speakers = new ConcurrentHashMap<String, Speaker>();
    private final CountDownLatch firstSpeaker = new CountDownLatch(1);

    private AllPlay allPlay;
    private volatile Speaker master;
    private volatile boolean streaming = false;
    private volatile int desiredVolume = Integer.getInteger("volume", 35);
    private volatile boolean muted = false;
    private volatile Set<String> zonedIds = new HashSet<String>();
    private volatile String lastError = "";
    private int consecutiveFailures = 0;
    /** Speakers report STOPPED briefly while they fetch and buffer the stream. */
    private volatile long playbackStartedAt = 0;
    private int notPlayingStreak = 0;
    /**
     * Usable slice of each speaker's range. The top of a speaker's range is
     * usually far louder than anyone wants indoors, which makes the whole slider
     * twitchy; narrowing the band spreads the same 0-100 across a smaller span.
     * Runtime-adjustable so it can be tuned by ear without a restart.
     */
    private volatile int volumeFloor = Integer.getInteger("volume.floor", 0);
    private volatile int volumeCeiling = Integer.getInteger("volume.ceiling", 100);

    public static void main(String[] args) throws Exception {
        // Without this, BusAttachment.connect() defaults to unix:abstract=alljoyn
        // and blocks for minutes waiting for a standalone daemon that is not
        // running. "null:" selects the router bundled into liballjoyn_java.so.
        if (System.getProperty("org.alljoyn.bus.address") == null) {
            System.setProperty("org.alljoyn.bus.address", "null:");
        }
        log("bus address = " + System.getProperty("org.alljoyn.bus.address"));
        log("stream url  = " + STREAM_URL);

        final AllPlayController controller = new AllPlayController();
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                controller.shutdown();
            }
        }, "allplay-shutdown"));

        controller.run();
    }

    private void run() throws Exception {
        connectBus();
        log("discovering for " + DISCOVERY_SECONDS + "s ...");
        firstSpeaker.await(DISCOVERY_SECONDS, TimeUnit.SECONDS);
        Thread.sleep(TimeUnit.SECONDS.toMillis(DISCOVERY_SECONDS));
        log("discovery window closed with " + speakers.size()
                + " speaker(s); late arrivals join on the next supervision pass");

        startControlServer();

        // Supervision loop. Everything here is best-effort and retried: the
        // Icecast mount 404s whenever nothing feeds it, speakers reboot, and the
        // AllJoyn bus can drop. None of that should need a restart.
        while (true) {
            int sleepSeconds = POLL_SECONDS;
            try {
                superviseOnce();
                consecutiveFailures = 0;
                lastError = "";
            } catch (Exception e) {
                consecutiveFailures++;
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                streaming = false;
                // Back off so a persistent fault does not hammer the speakers or
                // fill the journal, but keep checking often enough to self-heal.
                sleepSeconds = Math.min(POLL_SECONDS * consecutiveFailures, MAX_BACKOFF_SECONDS);
                log("supervision failed (" + consecutiveFailures + "x): " + lastError
                        + "; retrying in " + sleepSeconds + "s");
            }
            Thread.sleep(TimeUnit.SECONDS.toMillis(sleepSeconds));
        }
    }

    private void superviseOnce() throws Exception {
        if (allPlay == null || !allPlay.isConnected()) {
            log("AllJoyn bus is down, reconnecting");
            reconnectBus();
            return;
        }
        if (!isStreamLive()) {
            if (streaming) {
                log("stream went away, will regroup when it returns");
                streaming = false;
            }
            return;
        }
        if (!streaming) {
            startPlayback();
        } else if (!speakers.keySet().equals(zonedIds)) {
            // Discovery is asynchronous and speakers appear well after the
            // initial window, so regroup when the known set changes.
            log("speaker set changed (" + zonedIds.size() + " -> " + speakers.size()
                    + "), rebuilding zone");
            startPlayback();
        } else {
            verifyPlayback();
        }
    }

    /** Restarts playback if the master is no longer actually playing. */
    private void verifyPlayback() {
        Speaker current = master;
        if (current == null || !current.isConnected()) {
            log("master is gone, regrouping");
            streaming = false;
            return;
        }
        // A speaker reports STOPPED for a while after playItem() as it opens the
        // stream, so leave it alone until it has had a chance to settle.
        if (System.currentTimeMillis() - playbackStartedAt < TimeUnit.SECONDS.toMillis(GRACE_SECONDS)) {
            return;
        }
        try {
            PlayState.State state = current.getPlayState().getState();
            if (state == PlayState.State.PLAYING || state == PlayState.State.BUFFERING
                    || state == PlayState.State.TRANSITIONING) {
                notPlayingStreak = 0;
                return;
            }
            // Require two consecutive bad readings. Restarting on a single
            // sample causes an audible glitch every time a speaker blips.
            if (++notPlayingStreak < 2) {
                log("master reported " + state + ", confirming on next pass");
                return;
            }
            log("master is " + state + " while the stream is live, restarting playback");
            notPlayingStreak = 0;
            streaming = false;
        } catch (AllPlayException e) {
            log("could not read play state (" + e.getMessage() + "), regrouping");
            notPlayingStreak = 0;
            streaming = false;
        }
    }

    /** Connects every speaker, groups them behind one master and starts the stream. */
    private void startPlayback() throws AllPlayException {
        if (speakers.isEmpty()) {
            throw new IllegalStateException("no speakers discovered yet");
        }
        Speaker chosen = chooseMaster();
        List<String> slaveIds = new ArrayList<String>();
        Set<String> grouped = new HashSet<String>();

        for (Speaker speaker : speakers.values()) {
            if (!speaker.isConnected() && !connect(speaker)) {
                continue;
            }
            grouped.add(speaker.getId());
            if (!speaker.getId().equals(chosen.getId())) {
                slaveIds.add(speaker.getId());
            }
        }

        if (!chosen.isConnected()) {
            throw new IllegalStateException("master " + chosen.getName() + " is not connected");
        }

        // createZone() runs on the master and takes the slaves. Without a zone
        // each speaker pulls the stream independently and the rooms drift apart.
        ZoneItem zone = chosen.zoneManager().createZone(slaveIds);
        master = chosen;
        log("zone " + zone.getZoneId() + " master=" + chosen.getName()
                + " slaves=" + slaveIds.size());

        chosen.playItem(STREAM_URL);
        zonedIds = grouped;
        streaming = true;
        playbackStartedAt = System.currentTimeMillis();
        notPlayingStreak = 0;
        applyVolume();
        log("playing " + STREAM_URL + " on " + grouped.size() + " speaker(s) at volume "
                + desiredVolume + (muted ? " (muted)" : ""));
    }

    /**
     * Pushes the desired volume to every connected speaker, scaled into each
     * one's own range. Speakers are set individually rather than through the
     * zone master so the rooms match instead of inheriting whatever level each
     * was last left at physically.
     */
    private void applyVolume() {
        for (Speaker speaker : speakers.values()) {
            if (!speaker.isConnected()) {
                continue;
            }
            try {
                Volume volume = speaker.volume();
                if (!volume.isControlEnabled()) {
                    log("volume control disabled on " + speaker.getName());
                    continue;
                }
                VolumeRange range = volume.getVolumeRange();
                volume.setVolume(scaleToRange(desiredVolume, range));
                volume.mute(muted);
            } catch (AllPlayException e) {
                log("could not set volume on " + speaker.getName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Maps 0-100 onto a speaker's advertised range, constrained to the usable
     * band [volumeFloor, volumeCeiling].
     *
     * The band exists because a speaker's full range is rarely all usable
     * indoors: the top of it is far louder than anyone wants, which makes the
     * whole slider twitchy. Narrowing the band spreads the same 0-100 slider
     * across a smaller, more usable span so small movements stay small.
     */
    private int scaleToRange(int percent, VolumeRange range) {
        int min = range.getMin();
        int max = range.getMax();
        int clamped = Math.max(0, Math.min(100, percent));
        double banded = volumeFloor + (volumeCeiling - volumeFloor) * (clamped / 100.0);
        return min + (int) Math.round((max - min) * (banded / 100.0));
    }

    private boolean connect(Speaker speaker) {
        try {
            speaker.connect();
            speaker.addSpeakerConnectionListener(new SpeakerConnectionListener() {
                public void onConnectionLost(String hostName, int reason) {
                    log("connection lost to " + hostName + " (reason " + reason
                            + "), will regroup");
                    streaming = false;
                }
            });
            return true;
        } catch (AllPlayException e) {
            log("cannot connect " + speaker.getName() + ": " + e.getMessage());
            return false;
        }
    }

    /** Prefers -Dmaster.name when it matches, otherwise any discovered speaker. */
    private Speaker chooseMaster() {
        if (!MASTER_NAME.isEmpty()) {
            for (Speaker speaker : speakers.values()) {
                if (MASTER_NAME.equalsIgnoreCase(speaker.getName())) {
                    return speaker;
                }
            }
        }
        return speakers.values().iterator().next();
    }

    private void connectBus() throws Exception {
        allPlay = new AllPlay("AllPlayController");
        allPlay.addSpeakerAnnouncedListener(new SpeakerAnnouncedListener() {
            public void onSpeakerAnnounced(Speaker speaker) {
                if (speakers.putIfAbsent(speaker.getId(), speaker) == null) {
                    log("discovered " + speaker.getName() + " [" + speaker.getId() + "]");
                    firstSpeaker.countDown();
                }
            }
        });
        allPlay.connect();
        allPlay.discoverSpeakers();
    }

    /**
     * Rebuilds the bus from scratch. The old Speaker handles belong to the dead
     * BusAttachment, so they are dropped and rediscovered rather than reused.
     */
    private void reconnectBus() throws Exception {
        try {
            if (allPlay != null) {
                allPlay.disconnect();
            }
        } catch (Exception e) {
            log("ignoring error while dropping old bus: " + e.getMessage());
        }
        speakers.clear();
        zonedIds = new HashSet<String>();
        master = null;
        streaming = false;
        connectBus();
        log("bus reconnected, rediscovering speakers");
    }

    /** Icecast returns 404 on the mount until a source connects to it. */
    private boolean isStreamLive() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(STREAM_URL).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            return connection.getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ---------------------------------------------------------------- control

    private void startControlServer() {
        if (CONTROL_PORT <= 0) {
            log("control endpoint disabled");
            return;
        }
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(CONTROL_PORT), 0);
            server.createContext("/", new HttpHandler() {
                public void handle(HttpExchange exchange) throws IOException {
                    String response;
                    try {
                        response = handleControl(exchange.getRequestURI());
                    } catch (Exception e) {
                        response = "error: " + e.getMessage() + "\n";
                    }
                    byte[] body = response.getBytes(UTF8);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(200, body.length);
                    OutputStream out = exchange.getResponseBody();
                    try {
                        out.write(body);
                    } finally {
                        out.close();
                    }
                }
            });
            server.setExecutor(null);
            server.start();
            log("control endpoint on http://0.0.0.0:" + CONTROL_PORT
                    + "  (/status /volume?level=N /volume?delta=N /band?ceiling=N /mute?on=true /play /stop)");
        } catch (IOException e) {
            log("could not start control endpoint: " + e.getMessage());
        }
    }

    private String handleControl(URI uri) throws Exception {
        String path = uri.getPath();
        Map<String, String> q = parseQuery(uri.getRawQuery());

        if (path.startsWith("/volume")) {
            if (q.containsKey("level")) {
                desiredVolume = Math.max(0, Math.min(100, Integer.parseInt(q.get("level"))));
            } else if (q.containsKey("delta")) {
                desiredVolume = Math.max(0, Math.min(100,
                        desiredVolume + Integer.parseInt(q.get("delta"))));
            } else {
                return "volume=" + desiredVolume + "\n";
            }
            applyVolume();
            return "volume=" + desiredVolume + "\n";
        }
        if (path.startsWith("/band")) {
            if (q.containsKey("floor")) {
                volumeFloor = Math.max(0, Math.min(100, Integer.parseInt(q.get("floor"))));
            }
            if (q.containsKey("ceiling")) {
                volumeCeiling = Math.max(0, Math.min(100, Integer.parseInt(q.get("ceiling"))));
            }
            if (volumeCeiling < volumeFloor) {
                int swap = volumeFloor;
                volumeFloor = volumeCeiling;
                volumeCeiling = swap;
            }
            applyVolume();
            return "band=" + volumeFloor + "-" + volumeCeiling + " volume=" + desiredVolume + "\n";
        }
        if (path.startsWith("/mute")) {
            muted = !q.containsKey("on") || Boolean.parseBoolean(q.get("on"));
            applyVolume();
            return "muted=" + muted + "\n";
        }
        if (path.startsWith("/play")) {
            streaming = false;
            return "playback will restart on the next supervision pass\n";
        }
        if (path.startsWith("/stop")) {
            Speaker current = master;
            if (current != null && current.isConnected()) {
                current.stop();
            }
            streaming = false;
            return "stopped\n";
        }
        return status();
    }

    private String status() {
        StringBuilder sb = new StringBuilder();
        Speaker current = master;
        sb.append("stream    ").append(STREAM_URL).append('\n');
        sb.append("live      ").append(isStreamLive()).append('\n');
        sb.append("streaming ").append(streaming).append('\n');
        sb.append("master    ").append(current == null ? "-" : current.getName()).append('\n');
        sb.append("volume    ").append(desiredVolume).append(muted ? " (muted)" : "").append('\n');
        sb.append("failures  ").append(consecutiveFailures).append('\n');
        if (!lastError.isEmpty()) {
            sb.append("lastError ").append(lastError).append('\n');
        }
        sb.append("band      ").append(volumeFloor).append('-').append(volumeCeiling)
          .append(" of each speaker's range\n");
        sb.append("speakers  ").append(speakers.size()).append('\n');
        for (Speaker speaker : speakers.values()) {
            sb.append("  ").append(speaker.isConnected() ? "* " : "  ").append(speaker.getName());
            if (speaker.isConnected()) {
                try {
                    Volume volume = speaker.volume();
                    VolumeRange range = volume.getVolumeRange();
                    sb.append("  actual=").append(volume.getVolume())
                      .append(" range=").append(range.getMin()).append("..").append(range.getMax())
                      .append(" step=").append(range.getIncrement());
                } catch (AllPlayException e) {
                    sb.append("  (volume unreadable: ").append(e.getMessage()).append(')');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new HashMap<String, String>();
        if (rawQuery == null) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return map;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The speakers fetch the stream themselves, from their own machines, so a
     * loopback host in the configured URL points each speaker at itself and it
     * hears nothing. Rewrite loopback to this host's LAN address; the liveness
     * probe works equally well against that.
     */
    private static String reachableBySpeakers(String url) {
        try {
            URL parsed = new URL(url);
            if (!InetAddress.getByName(parsed.getHost()).isLoopbackAddress()) {
                return url;
            }
            String lan = primaryLanAddress();
            if (lan == null) {
                log("WARNING: " + parsed.getHost() + " is loopback and no LAN address"
                        + " was found; speakers will not be able to fetch the stream");
                return url;
            }
            String rewritten =
                    new URL(parsed.getProtocol(), lan, parsed.getPort(), parsed.getFile()).toString();
            log("rewrote loopback stream url to " + rewritten + " so speakers can reach it");
            return rewritten;
        } catch (Exception e) {
            log("could not normalise stream url (" + e.getMessage() + "), using as-is");
            return url;
        }
    }

    /** First non-loopback IPv4 address on an interface that is up. */
    private static String primaryLanAddress() throws SocketException {
        for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!iface.isUp() || iface.isLoopback()) {
                continue;
            }
            for (InetAddress address : Collections.list(iface.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    return address.getHostAddress();
                }
            }
        }
        return null;
    }

    /**
     * Stop playback, then dissolve the zone, then drop the speakers, then the
     * bus. Tearing down in that order keeps the router from logging a storm of
     * failures about sessions that vanished underneath it.
     */
    private void shutdown() {
        log("shutting down");
        Speaker current = master;
        try {
            if (current != null && current.isConnected()) {
                current.stop();
            }
        } catch (Exception e) {
            log("could not stop playback: " + e.getMessage());
        }
        try {
            if (current != null && current.isConnected()) {
                current.zoneManager().releaseZone();
            }
        } catch (Exception e) {
            log("could not release zone: " + e.getMessage());
        }
        for (Speaker speaker : speakers.values()) {
            try {
                if (speaker.isConnected()) {
                    speaker.disconnect();
                }
            } catch (Exception e) {
                // Best effort; we are on the way out.
            }
        }
        if (allPlay != null) {
            try {
                allPlay.disconnect();
            } catch (Exception e) {
                log("could not disconnect bus: " + e.getMessage());
            }
        }
    }

    private static void log(String message) {
        System.out.println("[allplay] " + message);
        System.out.flush();
    }
}
