/**
 * Groups every discovered AllPlay speaker into one zone and keeps it playing an
 * HTTP stream (for example an Icecast mount fed by librespot).
 *
 * Audio never flows through AllJoyn. The speakers pull the stream over plain
 * HTTP; this controller only tells them which URL to pull, and keeps them
 * grouped into a zone so they stay in sync.
 *
 * Configuration, all via system properties:
 *   -Dstream.url=http://host:8000/spotify.mp3   stream for the speakers to play
 *   -Dmaster.name=Kitchen                       which speaker leads the zone
 *   -Ddiscovery.seconds=30                      how long to gather speakers
 *   -Dpoll.seconds=15                           supervision interval
 *
 * Licensed under the Apache License, Version 2.0.
 */
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.kaizencode.tchaikovsky.AllPlay;
import de.kaizencode.tchaikovsky.exception.AllPlayException;
import de.kaizencode.tchaikovsky.listener.SpeakerAnnouncedListener;
import de.kaizencode.tchaikovsky.listener.SpeakerConnectionListener;
import de.kaizencode.tchaikovsky.speaker.Speaker;
import de.kaizencode.tchaikovsky.speaker.ZoneItem;

public class AllPlayController {

    private static final String STREAM_URL =
            System.getProperty("stream.url", "http://127.0.0.1:8000/spotify.mp3");
    private static final String MASTER_NAME = System.getProperty("master.name", "");
    private static final int DISCOVERY_SECONDS = Integer.getInteger("discovery.seconds", 30);
    private static final int POLL_SECONDS = Integer.getInteger("poll.seconds", 15);

    /** Discovered speakers by device id. Written from the AllJoyn callback thread. */
    private final Map<String, Speaker> speakers = new ConcurrentHashMap<String, Speaker>();
    private final CountDownLatch firstSpeaker = new CountDownLatch(1);

    private AllPlay allPlay;
    private Speaker master;
    private volatile boolean streaming = false;
    /** Ids that are actually in the current zone, so late arrivals can be spotted. */
    private Set<String> zonedIds = new HashSet<String>();

    public static void main(String[] args) throws Exception {
        // Without this, BusAttachment.connect() defaults to unix:abstract=alljoyn
        // and blocks for minutes waiting for a standalone daemon that is not
        // running. "null:" selects the router bundled into liballjoyn_java.so.
        // Override on the command line if you do run a separate alljoyn-daemon.
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
        log("discovering for " + DISCOVERY_SECONDS + "s ...");

        // Wait for the first speaker, then keep listening so the slower ones are
        // included before the zone is built.
        firstSpeaker.await(DISCOVERY_SECONDS, TimeUnit.SECONDS);
        Thread.sleep(TimeUnit.SECONDS.toMillis(DISCOVERY_SECONDS));

        if (speakers.isEmpty()) {
            log("no speakers found, nothing to do");
            return;
        }
        log("discovery window closed with " + speakers.size()
                + " speaker(s); late arrivals join on the next supervision pass");

        // Supervision loop. The Icecast mount 404s whenever nothing is feeding it,
        // so wait for the stream rather than sending speakers at a dead URL, and
        // regroup automatically when it comes back.
        while (true) {
            try {
                if (isStreamLive()) {
                    // Discovery is asynchronous and AllPlay speakers can appear
                    // well after the initial window, so regroup whenever the set
                    // of known speakers no longer matches the live zone.
                    if (!streaming) {
                        startPlayback();
                    } else if (!speakers.keySet().equals(zonedIds)) {
                        log("speaker set changed (" + zonedIds.size() + " -> "
                                + speakers.size() + "), rebuilding zone");
                        startPlayback();
                    }
                } else if (streaming) {
                    log("stream went away, will regroup when it returns");
                    streaming = false;
                }
            } catch (Exception e) {
                log("supervision error: " + e.getMessage() + ", retrying");
                streaming = false;
            }
            Thread.sleep(TimeUnit.SECONDS.toMillis(POLL_SECONDS));
        }
    }

    /** Connects every speaker, groups them behind one master and starts the stream. */
    private void startPlayback() throws AllPlayException {
        master = chooseMaster();
        List<String> slaveIds = new ArrayList<String>();
        Set<String> grouped = new HashSet<String>();

        for (Speaker speaker : speakers.values()) {
            if (!speaker.isConnected() && !connect(speaker)) {
                continue;
            }
            grouped.add(speaker.getId());
            if (!speaker.getId().equals(master.getId())) {
                slaveIds.add(speaker.getId());
            }
        }

        if (!master.isConnected()) {
            throw new IllegalStateException("master " + master.getName() + " is not connected");
        }

        // createZone() is called on the master and takes the slaves. Without a
        // zone each speaker pulls the HTTP stream independently and they drift
        // out of sync with each other.
        ZoneItem zone = master.zoneManager().createZone(slaveIds);
        log("zone " + zone.getZoneId() + " master=" + master.getName()
                + " slaves=" + slaveIds.size());

        master.playItem(STREAM_URL);
        zonedIds = grouped;
        streaming = true;
        log("playing " + STREAM_URL + " on " + grouped.size() + " speaker(s)");
    }

    private boolean connect(Speaker speaker) {
        try {
            speaker.connect();
            speaker.addSpeakerConnectionListener(new SpeakerConnectionListener() {
                public void onConnectionLost(String hostName, int reason) {
                    log("connection lost to " + hostName + " (reason " + reason + ")");
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
            log("master.name '" + MASTER_NAME + "' not found, using first speaker");
        }
        return speakers.values().iterator().next();
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

    private void shutdown() {
        log("shutting down");
        try {
            if (master != null && master.isConnected()) {
                master.zoneManager().releaseZone();
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
            allPlay.disconnect();
        }
    }

    private static void log(String message) {
        System.out.println("[allplay] " + message);
        System.out.flush();
    }
}
