/**
 * Plays an HTTP stream (for example an Icecast mount fed by librespot) on the
 * AllPlay group that is already on the speakers, and supervises that playback
 * so it survives speaker reboots, network blips and stream outages.
 *
 * Audio never flows through AllJoyn. The speakers pull the stream over plain
 * HTTP. This controller tells the group lead which URL to pull and owns volume
 * for the rooms in that group. It does not keep rebuilding the group.
 *
 * The standalone AllPlay app is how rooms are chosen. Spotify cannot ask: it
 * only plays, pauses, skips, seeks and sets volume. While a group exists, that
 * group is followed and remembered. A group is formed here only when the
 * speakers report none — the remembered rooms, or every room that is on if
 * nothing has been remembered yet — and when {@code GET /group} asks for every
 * room that is on. Shutdown leaves the group in place.
 *
 * Volume lives here because a librespot bridge running with
 * LIBRESPOT_VOLUME_CTRL=fixed always emits full-scale PCM, so the Spotify app's
 * slider does nothing. The AllPlay speakers are the only real volume control,
 * and they are reachable through this API.
 *
 * Configuration, all via system properties:
 *   -Dstream.url=http://host:8000/spotify.mp3   stream for the speakers to play
 *   -Dmaster.name=Kitchen                       preferred lead, and which group
 *                                               to follow when several exist
 *   -Dvolume=35                                 startup volume, 0-100
 *   -Dcontrol.port=8080                         HTTP control endpoint, 0 disables
 *   -Ddiscovery.seconds=25                      initial discovery window
 *   -Dpoll.seconds=12                           supervision interval
 *
 * Licensed under the Apache License, Version 2.0.
 */
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
import de.kaizencode.tchaikovsky.speaker.ZoneInfo;
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
    private static final String STATE_FILE =
            System.getProperty("state.file", "allplay.state");

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Discovered speakers by device id. Written from the AllJoyn callback thread. */
    private final Map<String, Speaker> speakers = new ConcurrentHashMap<String, Speaker>();
    private final CountDownLatch firstSpeaker = new CountDownLatch(1);

    private AllPlay allPlay;
    private volatile Speaker master;
    private volatile boolean streaming = false;
    private volatile int desiredVolume = Integer.getInteger("volume", 35);
    private volatile boolean muted = false;
    /**
     * Rooms we currently drive. Volume and stop use this set only, so a room
     * left out of the group is left alone. Replaced, never mutated in place.
     */
    private volatile Set<String> groupIds = Collections.emptySet();
    /** Remembered rooms, in name order. Put back only when the speakers report no group. */
    private volatile List<String> savedIds = Collections.emptyList();
    private volatile List<String> savedNames = Collections.emptyList();
    /**
     * A smaller remembered set is written only after two scans agree. One empty
     * zone read must not forget a room.
     */
    private List<String> pendingSavedIds;
    private List<String> pendingSavedNames;
    /** When we last formed a group. "No group" is ignored until that settles. */
    private volatile long groupCreatedAt = 0;
    /**
     * Rebuilds while streaming. Reset when a group is actually visible, so a
     * firmware that never reports a zone id cannot tear the audio down on every
     * grace period. A later pause and play may form the group again.
     */
    private int restoreAttempts = 0;
    /** Consecutive scans that saw no group. One empty read must not rebuild it. */
    private int missingZoneStreak = 0;
    private volatile String lastError = "";
    private int consecutiveFailures = 0;
    /** Speakers report STOPPED briefly while they fetch and buffer the stream. */
    private volatile long playbackStartedAt = 0;
    private int notPlayingStreak = 0;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    /**
     * True until the source reports playing. Starts true so a live-but-silent
     * Icecast mount (ffmpeg connected, FIFO idle) does not make us playItem()
     * on boot — that pointed the speakers at a dead HTTP body (18:44:19) and
     * the first real track was silent.
     */
    private volatile boolean sourcePaused = true;
    private static final long PAUSE_DEBOUNCE_MS = Long.getLong("pause.debounce.ms", 1500);
    /** librespot emits paused immediately after playing on session start. */
    private static final long PAUSE_IGNORE_AFTER_RESUME_MS =
            Long.getLong("pause.ignore.after.resume.ms", 2500);
    private volatile long lastResumeAt = 0;
    /** AllJoyn stop() can block; don't let one speaker hang pause forever. */
    private static final long STOP_JOIN_MS = Long.getLong("pause.stop.join.ms", 4000);
    /**
     * Interrupted pause can finish stop() on every speaker after resume, each
     * calling playItem() (observed 6 reopens in one second at 18:14:11). Skip
     * and seek do not use this window.
     */
    private static final long REOPEN_COALESCE_MS = Long.getLong("reopen.coalesce.ms", 750);
    private long lastReopenAt = 0;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "allplay-transport");
            t.setDaemon(true);
            return t;
        }
    });
    private ScheduledFuture<?> pendingPause;
    /**
     * Bumped on every pause schedule and every cancel. The debounce runnable
     * captures the value it was scheduled with and aborts if it no longer
     * matches, so a late speaker.stop() cannot land after the user has already
     * hit play (observed 17:31:39 resume, 17:31:40 stop).
     */
    private volatile int pauseEpoch = 0;
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

        // Everything the controller reports goes to stdout, so stderr can be
        // discarded to drop AllJoyn's native teardown flood. Route anything that
        // would otherwise die silently on stderr through the same channel.
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread thread, Throwable error) {
                log("UNCAUGHT on " + thread.getName() + ": " + error);
                for (StackTraceElement frame : error.getStackTrace()) {
                    log("    at " + frame);
                }
            }
        });

        final AllPlayController controller = new AllPlayController();
        controller.installTerminationHandler();
        controller.run();
    }

    private void run() throws Exception {
        loadState();
        connectBus();
        log("discovering for " + DISCOVERY_SECONDS + "s ...");
        firstSpeaker.await(DISCOVERY_SECONDS, TimeUnit.SECONDS);
        Thread.sleep(TimeUnit.SECONDS.toMillis(DISCOVERY_SECONDS));
        log("discovery window closed with " + speakers.size()
                + " speaker(s); a room that appears later is not added to an existing group");

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
        // The source told us it paused. Icecast still answers 200 because ffmpeg
        // stays connected, so without this the loop sees a live stream plus
        // streaming=false and starts playback straight back up - undoing the
        // pause about ten seconds after it took effect.
        if (sourcePaused) {
            return;
        }
        if (!isStreamLive()) {
            if (streaming) {
                log("stream went away, will play again when it returns");
                streaming = false;
            }
            return;
        }
        if (!streaming) {
            // Reopen when the lead is already there. Forming a group is the
            // audible tear-down, so it happens only when there is no group and
            // more remembered rooms are now on, or when reopen has no lead.
            if (maybeMoreRooms() || !reopenStream("supervise")) {
                startPlayback();
            }
        } else {
            // A new speaker must not rebuild the group. Follow a group the app
            // changed; form one only if the speakers report that there isn't one.
            trackGroup();
            verifyPlayback();
        }
    }

    /** Restarts playback if the lead is no longer actually playing. */
    private void verifyPlayback() {
        Speaker current = master;
        if (current == null || !current.isConnected()) {
            log("lead is gone, will rejoin its group");
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
            // Four bad readings, not two. Two fired about a minute into a song
            // that was actually playing (Blue Hawaii, watchdog at 61s, heard
            // near 57s) and the reopen was the glitch. A real stall still
            // reopens, just not on a pair of bad samples.
            if (++notPlayingStreak < 4) {
                log("lead reported " + state + " " + notPlayingStreak + "/4, "
                        + ((System.currentTimeMillis() - playbackStartedAt) / 1000)
                        + "s after playItem");
                return;
            }
            notPlayingStreak = 0;
            // Re-open on the lead we already have. createZone() is the audible
            // tear-down — it fired twice in two minutes when a skip left the lead
            // briefly stopped — and a group that is still there must be kept.
            if (reopenStream("watchdog (" + state + ")")) {
                return;
            }
            streaming = false;
        } catch (AllPlayException e) {
            log("could not read play state (" + e.getMessage() + "), will rejoin the group");
            notPlayingStreak = 0;
            streaming = false;
        }
    }

    /**
     * Adopts the group already on the speakers, or forms one when there isn't
     * one. Forming uses the remembered rooms that are on, or every room that is
     * on when nothing is remembered. {@code allOn} always forms that second
     * group and replaces what was remembered. Returns null when nobody to play
     * is on.
     *
     * The caller scans before taking the lock; see {@link #scanForGroup()}.
     */
    private synchronized Speaker ensureGroup(boolean allOn, Scan scan) throws AllPlayException {
        if (!allOn && scan.zone != null) {
            Speaker lead = scan.zoneLead;
            if (lead == null || !lead.isConnected()) {
                log("a group is on the speakers but its lead is not reachable; /group starts a new one");
                throw new IllegalStateException("group lead is not reachable");
            }
            adoptZone(scan);
            return lead;
        }
        if (!allOn && !scan.readable && !scan.connected.isEmpty()) {
            throw new IllegalStateException("could not read whether the speakers are grouped");
        }

        List<Speaker> targets = new ArrayList<Speaker>();
        boolean fresh = allOn || savedIds.isEmpty();
        if (fresh) {
            targets.addAll(scan.connected);
        } else {
            List<String> wanted = savedIds;
            for (Speaker speaker : scan.connected) {
                if (wanted.contains(speaker.getId())) {
                    targets.add(speaker);
                }
            }
            if (targets.isEmpty()) {
                log("remembered rooms are not on");
                return null;
            }
        }
        if (targets.isEmpty()) {
            return null;
        }

        Speaker lead = chooseLead(targets);
        // Already formed this exact set while streaming and the speakers still
        // report no zone. Creating it again on the next poll tears the audio
        // down. /group is an explicit request and still creates.
        if (!allOn && restoreAttempts > 0 && sameSpeakers(groupIds, targets)
                && master != null && lead.getId().equals(master.getId())) {
            master = lead;
            log("not forming the group again; /group does it if the rooms are apart");
            return lead;
        }
        if (targets.size() > 1) {
            List<String> slaves = new ArrayList<String>();
            for (Speaker speaker : targets) {
                if (!speaker.getId().equals(lead.getId())) {
                    slaves.add(speaker.getId());
                }
            }
            ZoneItem zone = lead.zoneManager().createZone(slaves);
            groupCreatedAt = System.currentTimeMillis();
            String why = allOn ? "all rooms that are on"
                    : (fresh ? "no group yet, every room that is on" : "remembered rooms");
            log("zone " + zone.getZoneId() + " lead=" + lead.getName()
                    + " slaves=" + slaves.size() + " (" + why + ")");
        } else {
            log("one room on (" + lead.getName() + "), not creating a group");
        }
        restoreAttempts = 1;
        missingZoneStreak = 0;
        master = lead;
        groupIds = idsOf(targets);
        if (fresh) {
            pendingSavedIds = null;
            pendingSavedNames = null;
            replaceSaved(targets);
        }
        applyVolume();
        return lead;
    }

    /**
     * While streaming, follow a group the app changed. Do not build one over a
     * group that is still there.
     *
     * The scan talks to every speaker and can take tens of seconds. It must
     * not hold the controller lock while it does: /skip's playItem waits on
     * that lock, so a scan overlapping a track change reopened the stream
     * half a minute into the new song (heard around 55s, after the speaker
     * buffer). The lock is taken only to publish the result.
     */
    private void trackGroup() throws AllPlayException {
        Scan scan = scanForGroup();
        synchronized (this) {
            trackGroupLocked(scan, scan.zoneLead);
        }
    }

    /**
     * Scan, and connect the zone's lead, without the controller lock. Every
     * path that looks at the group does this first and locks only to act on
     * the result. A room that refuses its session costs ~25s per connect()
     * (16:34:59-16:35:25), and a scan under the lock held /resume long enough
     * that the bridge's curl timed out (16:35:44).
     */
    private Scan scanForGroup() {
        long started = System.currentTimeMillis();
        Scan scan = scanSpeakers(true);
        // connect() is AllJoyn too. Do it before taking the lock.
        scan.zoneLead = scan.zone == null ? null : reachableLead(scan.zone);
        long scanMs = System.currentTimeMillis() - started;
        if (scanMs >= 1000) {
            log("group scan took " + scanMs + "ms");
        }
        return scan;
    }

    private void trackGroupLocked(Scan scan, Speaker lead) throws AllPlayException {
        if (scan.zone != null) {
            if (lead == null) {
                log("a group is on the speakers but its lead is not reachable");
                return;
            }
            String previousLead = master == null ? "" : master.getId();
            adoptZone(scan);
            if (!lead.getId().equals(previousLead)) {
                log("group lead is now " + lead.getName());
                if (!reopenStream("group lead changed")) {
                    playOn(lead);
                }
            }
            return;
        }
        if (!scan.readable) {
            return;
        }
        if (System.currentTimeMillis() - groupCreatedAt < TimeUnit.SECONDS.toMillis(GRACE_SECONDS)) {
            return;
        }
        if (restoreAttempts > 0) {
            return;
        }
        // Same rule as the playback watchdog: one empty reading is not enough.
        // A group that is still there must not be rebuilt over a bad read.
        if (++missingZoneStreak < 2) {
            log("speakers report no group, confirming on the next pass");
            return;
        }
        missingZoneStreak = 0;
        log("speakers are not grouped, restoring remembered rooms");
        startPlaybackLocked(scan);
    }

    private Speaker reachableLead(ZoneHit zone) {
        Speaker lead = zone.lead;
        if (lead == null) {
            return null;
        }
        if (!lead.isConnected() && !connect(lead)) {
            return null;
        }
        return lead.isConnected() ? lead : null;
    }

    /**
     * True when more remembered rooms are connected than the live group has.
     * Connected, not merely discovered: a room that is visible but refuses its
     * session would otherwise send every resume through a full scan.
     * Resume then looks at the speakers before reopening, so a room that came
     * back while nothing was grouped can join at the start of play. A group
     * that is already there is adopted instead, not rebuilt.
     */
    private boolean maybeMoreRooms() {
        List<String> saved = savedIds;
        Set<String> live = groupIds;
        if (saved.size() <= live.size()) {
            return false;
        }
        int present = 0;
        for (Speaker speaker : speakers.values()) {
            if (speaker.isConnected() && saved.contains(speaker.getId())) {
                present++;
            }
        }
        return present > live.size();
    }

    /**
     * Buckets speakers that share a zone id. With {@code reconnect}, first
     * connects what we can see; without, reads only rooms already connected.
     */
    private Scan scanSpeakers(boolean reconnect) {
        Scan scan = new Scan();
        Map<String, ZoneHit> zones = new LinkedHashMap<String, ZoneHit>();
        for (Speaker speaker : speakers.values()) {
            if (!speaker.isConnected() && (!reconnect || !connect(speaker))) {
                continue;
            }
            scan.connected.add(speaker);
            ZoneInfo info;
            try {
                info = speaker.getPlayerInfo().getZoneInfo();
            } catch (AllPlayException e) {
                log("could not read group on " + speaker.getName() + ": " + e.getMessage());
                continue;
            }
            if (info == null) {
                continue;
            }
            scan.readable = true;
            scan.readableSpeakers.add(speaker);
            String zoneId = info.getZoneId();
            if (zoneId == null || zoneId.trim().isEmpty()) {
                continue;
            }
            ZoneHit zone = zones.get(zoneId);
            if (zone == null) {
                zone = new ZoneHit(zoneId);
                zones.put(zoneId, zone);
            }
            zone.members.add(speaker);
            zone.memberIds.add(speaker.getId());
            if (info.isLeadPlayer()) {
                zone.lead = speaker;
            } else if (zone.lead == null) {
                String leadId = info.getLeadPlayerID();
                Speaker known = leadId == null ? null : speakers.get(leadId);
                if (known != null) {
                    zone.lead = known;
                }
            }
        }
        for (ZoneHit zone : zones.values()) {
            if (zone.lead != null && zone.memberIds.add(zone.lead.getId())) {
                zone.members.add(zone.lead);
            }
        }
        scan.zone = pickZone(zones);
        if (zones.size() > 1 && scan.zone != null) {
            log(zones.size() + " groups on the speakers, following the one led by "
                    + (scan.zone.lead == null ? scan.zone.zoneId : scan.zone.lead.getName()));
        }
        return scan;
    }

    /** Prefer -Dmaster.name, then the group we were already driving, then the largest. */
    private ZoneHit pickZone(Map<String, ZoneHit> zones) {
        ZoneHit best = null;
        for (ZoneHit zone : zones.values()) {
            if (best == null || preferZone(zone, best)) {
                best = zone;
            }
        }
        return best;
    }

    private boolean preferZone(ZoneHit candidate, ZoneHit current) {
        if (!MASTER_NAME.isEmpty()) {
            boolean candidateNamed = containsName(candidate, MASTER_NAME);
            boolean currentNamed = containsName(current, MASTER_NAME);
            if (candidateNamed != currentNamed) {
                return candidateNamed;
            }
        }
        Speaker currentLead = master;
        if (currentLead != null) {
            boolean candidateHas = candidate.memberIds.contains(currentLead.getId());
            boolean currentHas = current.memberIds.contains(currentLead.getId());
            if (candidateHas != currentHas) {
                return candidateHas;
            }
        }
        if (candidate.members.size() != current.members.size()) {
            return candidate.members.size() > current.members.size();
        }
        return candidate.zoneId.compareTo(current.zoneId) < 0;
    }

    private static boolean containsName(ZoneHit zone, String name) {
        for (Speaker speaker : zone.members) {
            if (name.equalsIgnoreCase(speaker.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The speakers' group wins over memory. Rooms we could not read stay
     * remembered. A room we could read outside this group is forgotten only
     * after a second scan says the same, so one empty read does not drop it.
     *
     * @return true when the live lead or the live members changed
     */
    private boolean adoptZone(Scan scan) {
        ZoneHit zone = scan.zone;
        if (zone == null || zone.lead == null) {
            return false;
        }
        restoreAttempts = 0;
        missingZoneStreak = 0;
        String previousLead = master == null ? "" : master.getId();
        Set<String> previousIds = groupIds;
        master = zone.lead;
        groupIds = new HashSet<String>(zone.memberIds);

        LinkedHashMap<String, String> proposed = new LinkedHashMap<String, String>();
        List<String> previousSaved = savedIds;
        List<String> previousNames = savedNames;
        for (int i = 0; i < previousSaved.size() && i < previousNames.size(); i++) {
            proposed.put(previousSaved.get(i), previousNames.get(i));
        }
        for (Speaker speaker : scan.readableSpeakers) {
            if (!zone.memberIds.contains(speaker.getId())) {
                proposed.remove(speaker.getId());
            }
        }
        for (Speaker member : zone.members) {
            proposed.put(member.getId(), member.getName());
        }
        List<String> proposedIds = new ArrayList<String>(proposed.keySet());
        List<String> proposedNames = new ArrayList<String>(proposed.values());
        sortByName(proposedIds, proposedNames);

        if (sameIds(proposedIds, previousSaved)) {
            pendingSavedIds = null;
            pendingSavedNames = null;
            if (!proposedNames.equals(previousNames)) {
                savedIds = proposedIds;
                savedNames = proposedNames;
                saveState();
            }
        } else if (properSubset(proposedIds, previousSaved)) {
            if (pendingSavedIds != null && sameIds(pendingSavedIds, proposedIds)) {
                pendingSavedIds = null;
                pendingSavedNames = null;
                savedIds = proposedIds;
                savedNames = proposedNames;
                saveState();
                log("remembering smaller group " + joinList(proposedNames));
            } else {
                pendingSavedIds = proposedIds;
                pendingSavedNames = proposedNames;
                log("group looks smaller (" + joinList(proposedNames)
                        + "); confirming before forgetting the other rooms");
            }
        } else {
            pendingSavedIds = null;
            pendingSavedNames = null;
            savedIds = proposedIds;
            savedNames = proposedNames;
            saveState();
            log("remembering " + joinList(proposedNames));
        }

        boolean changed = !zone.lead.getId().equals(previousLead) || !groupIds.equals(previousIds);
        if (changed) {
            log("following " + joinNames(groupIds) + ", lead " + zone.lead.getName());
            applyVolume();
        }
        return changed;
    }

    /** Starts the stream on the lead of {@link #ensureGroup(boolean, Scan) ensureGroup(false, scan)}. */
    private void startPlayback() throws AllPlayException {
        Scan scan = scanForGroup();
        synchronized (this) {
            startPlaybackLocked(scan);
        }
    }

    private synchronized void startPlaybackLocked(Scan scan) throws AllPlayException {
        Speaker lead = ensureGroup(false, scan);
        if (lead == null) {
            if (speakers.isEmpty()) {
                throw new IllegalStateException("no speakers discovered yet");
            }
            throw new IllegalStateException("remembered rooms are not on");
        }
        playOn(lead);
        log("playing " + STREAM_URL + " on " + joinNames(groupIds) + " at volume "
                + desiredVolume + (muted ? " (muted)" : ""));
    }

    private void playOn(Speaker lead) throws AllPlayException {
        lead.playItem(liveStreamUrl());
        streaming = true;
        playbackStartedAt = System.currentTimeMillis();
        lastResumeAt = playbackStartedAt;
        notPlayingStreak = 0;
        applyVolume();
    }

    /** Every room that is on, replacing the remembered group. Does not start playback while paused. */
    private String groupAllOn() throws AllPlayException {
        Scan scan = scanForGroup();
        boolean live = isStreamLive();
        synchronized (this) {
            return groupAllOnLocked(scan, live);
        }
    }

    private String groupAllOnLocked(Scan scan, boolean live) throws AllPlayException {
        Speaker lead = ensureGroup(true, scan);
        if (lead == null) {
            return "no speakers on\n";
        }
        String names = joinNames(groupIds);
        if (sourcePaused || !live) {
            log("grouped every room that is on (" + names + "), not playing yet");
            return "grouped " + names + "\n";
        }
        playOn(lead);
        log("grouped every room that is on (" + names + ") and started the stream");
        return "grouped " + names + "\n";
    }

    /**
     * Remembers volume, mute, band and the group across restarts.
     *
     * Without this every restart resets to the -Dvolume default and throws away
     * where the Spotify slider actually is. librespot only emits volume_changed
     * when the slider moves, so nothing corrects it until someone happens to
     * touch it - which presents as the speakers being inaudible after a reboot.
     */
    private synchronized void saveState() {
        try {
            Properties props = new Properties();
            props.setProperty("volume", Integer.toString(desiredVolume));
            props.setProperty("muted", Boolean.toString(muted));
            props.setProperty("volume.floor", Integer.toString(volumeFloor));
            props.setProperty("volume.ceiling", Integer.toString(volumeCeiling));
            String group = encodeGroup();
            if (!group.isEmpty()) {
                props.setProperty("group", group);
            }
            OutputStream out = new FileOutputStream(STATE_FILE);
            try {
                props.store(out, "AllPlayController state; safe to delete");
            } finally {
                out.close();
            }
        } catch (Exception e) {
            log("could not save state to " + STATE_FILE + ": " + e.getMessage());
        }
    }

    private void loadState() {
        File file = new File(STATE_FILE);
        if (!file.isFile()) {
            log("no saved state, starting at volume " + desiredVolume);
            return;
        }
        try {
            Properties props = new Properties();
            InputStream in = new FileInputStream(file);
            try {
                props.load(in);
            } finally {
                in.close();
            }
            desiredVolume = Integer.parseInt(props.getProperty("volume", Integer.toString(desiredVolume)));
            muted = Boolean.parseBoolean(props.getProperty("muted", Boolean.toString(muted)));
            volumeFloor = Integer.parseInt(props.getProperty("volume.floor", Integer.toString(volumeFloor)));
            volumeCeiling = Integer.parseInt(props.getProperty("volume.ceiling", Integer.toString(volumeCeiling)));
            decodeGroup(props.getProperty("group", ""));
            String rooms = joinList(savedNames);
            log("restored volume " + desiredVolume + (muted ? " (muted)" : "")
                    + ", band " + volumeFloor + "-" + volumeCeiling
                    + (rooms.isEmpty() ? "" : ", rooms " + rooms));
        } catch (Exception e) {
            log("could not read state from " + STATE_FILE + " (" + e.getMessage() + "), using defaults");
        }
    }

    /**
     * Pushes the desired volume to each speaker in the current group, scaled
     * into that speaker's own range. Rooms left out of the group are not
     * touched. Members are set individually rather than through the lead, so
     * the rooms match instead of keeping whatever level each was last left at.
     */
    private void applyVolume() {
        Set<String> ids = groupIds;
        for (Speaker speaker : speakers.values()) {
            if (!speaker.isConnected() || !ids.contains(speaker.getId())) {
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
            final Speaker watched = speaker;
            speaker.addSpeakerConnectionListener(new SpeakerConnectionListener() {
                public void onConnectionLost(String hostName, int reason) {
                    Speaker lead = master;
                    if (lead != null && lead.getId().equals(watched.getId())) {
                        log("lead " + watched.getName() + " connection lost (reason " + reason
                                + "), will rejoin its group");
                        streaming = false;
                    } else {
                        log("connection lost to " + watched.getName() + " (reason " + reason
                                + "), group left as it is");
                    }
                }
            });
            return true;
        } catch (AllPlayException e) {
            log("cannot connect " + speaker.getName() + ": " + e.getMessage());
            return false;
        }
    }

    /** Preferred lead when this controller has to form a group: -Dmaster.name, else the current lead. */
    private Speaker chooseLead(List<Speaker> candidates) {
        if (!MASTER_NAME.isEmpty()) {
            for (Speaker speaker : candidates) {
                if (MASTER_NAME.equalsIgnoreCase(speaker.getName())) {
                    return speaker;
                }
            }
        }
        Speaker current = master;
        if (current != null) {
            for (Speaker speaker : candidates) {
                if (current.getId().equals(speaker.getId())) {
                    return speaker;
                }
            }
        }
        return candidates.get(0);
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
        groupIds = Collections.emptySet();
        master = null;
        streaming = false;
        restoreAttempts = 0;
        missingZoneStreak = 0;
        groupCreatedAt = 0;
        connectBus();
        log("bus reconnected, rediscovering speakers");
    }

    /**
     * Stops the speakers only if the source is still paused after the debounce.
     * Cancelled by resume, so transient paused events during track changes do
     * not interrupt playback.
     *
     * Stopping five speakers over AllJoyn takes longer than the debounce, so
     * cancel() alone is not enough: the runnable may already be inside stop().
     * An epoch plus a re-check before each stop, and a reopen if we already
     * stopped anyone after being cancelled, closes that window.
     */
    private synchronized void schedulePause() {
        cancelPendingPause();
        final int epoch = pauseEpoch;
        pendingPause = timer.schedule(new Runnable() {
            public void run() {
                applyPause(epoch);
            }
        }, PAUSE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelPendingPause() {
        pauseEpoch++;
        if (pendingPause != null) {
            pendingPause.cancel(false);
            pendingPause = null;
        }
    }

    private boolean stillThisPause(int epoch) {
        return sourcePaused && epoch == pauseEpoch;
    }

    private void applyPause(int epoch) {
        if (!stillThisPause(epoch)) {
            return;
        }
        try {
            refreshGroupBeforePause();
        } catch (Exception e) {
            log("could not read the group before pause (" + e.getMessage()
                    + "), stopping the last known rooms");
        }
        if (!stillThisPause(epoch)) {
            return;
        }
        int stopped = stopSpeakersParallel(epoch);
        synchronized (this) {
            if (!stillThisPause(epoch)) {
                recoverInterruptedPause(stopped);
                return;
            }
            streaming = false;
            pendingPause = null;
            log("source paused, stopped " + stopped + " speaker(s)");
        }
    }

    /**
     * Pause has to hit the group the app left in place, which may have changed
     * since the last poll. Look first; do not form a group from here.
     *
     * Read only rooms already connected, and outside the lock. A resume right
     * after the pause needs the lock, and must not wait on reconnect attempts.
     */
    private void refreshGroupBeforePause() throws AllPlayException {
        if (speakers.isEmpty()) {
            return;
        }
        Scan scan = scanSpeakers(false);
        Speaker lead = scan.zone == null ? null : scan.zone.lead;
        if (lead == null || !lead.isConnected()) {
            return;
        }
        synchronized (this) {
            adoptZone(scan);
        }
    }

    /**
     * stop() every speaker in the current group at once. Rooms left out are
     * not stopped. Sequential stops took several seconds per room, so a short
     * pause/play always landed inside stop() (and a hung AllJoyn call at 18:00
     * left paused=true with streaming=true).
     */
    private int stopSpeakersParallel(final int epoch) {
        return stopSpeakers(groupedTargets(), true, epoch);
    }

    /** Stop the current group. Used by /stop and shutdown; pause has its own epoch. */
    private int stopGrouped() {
        return stopSpeakers(groupedTargets(), false, 0);
    }

    private List<Speaker> groupedTargets() {
        List<Speaker> targets = new ArrayList<Speaker>();
        Set<String> ids = groupIds;
        for (Speaker speaker : speakers.values()) {
            if (speaker.isConnected() && ids.contains(speaker.getId())) {
                targets.add(speaker);
            }
        }
        if (targets.isEmpty() && ids.isEmpty()) {
            Speaker current = master;
            if (current != null && current.isConnected()) {
                targets.add(current);
            }
        }
        return targets;
    }

    private int stopSpeakers(final List<Speaker> targets, final boolean onlyIfPaused, final int epoch) {
        if (targets.isEmpty()) {
            return 0;
        }
        final AtomicInteger stopped = new AtomicInteger(0);
        List<Thread> threads = new ArrayList<Thread>();
        for (final Speaker speaker : targets) {
            Thread thread = new Thread(new Runnable() {
                public void run() {
                    if (onlyIfPaused && !stillThisPause(epoch)) {
                        return;
                    }
                    try {
                        speaker.stop();
                        stopped.incrementAndGet();
                    } catch (Exception e) {
                        log("could not stop " + speaker.getName() + ": " + e.getMessage());
                    }
                    if (onlyIfPaused && !stillThisPause(epoch) && !sourcePaused) {
                        reopenStream("late stop after resume", true);
                    }
                }
            }, "allplay-stop-" + speaker.getName().replace(' ', '-'));
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
        }
        long deadline = System.currentTimeMillis() + STOP_JOIN_MS;
        for (int i = 0; i < threads.size(); i++) {
            long wait = deadline - System.currentTimeMillis();
            // Thread.join(0) waits forever. After the deadline, stop waiting.
            if (wait <= 0) {
                break;
            }
            try {
                threads.get(i).join(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int alive = 0;
        for (int i = 0; i < threads.size(); i++) {
            if (threads.get(i).isAlive()) {
                alive++;
            }
        }
        if (alive > 0) {
            log((onlyIfPaused ? "pause: " : "stop: ") + alive
                    + " speaker stop(s) still running after " + STOP_JOIN_MS + "ms, continuing");
        }
        return stopped.get();
    }

    /**
     * Resume cancelled us after we had already stopped some speakers, and
     * /resume is a no-op while streaming is still true. Put those speakers
     * back on the live URL without rebuilding the zone.
     */
    private void recoverInterruptedPause(int stopped) {
        if (stopped <= 0 || sourcePaused) {
            return;
        }
        log("pause interrupted after stopping " + stopped + " speaker(s), reopening");
        reopenStream("pause interrupted", true);
    }

    /**
     * Re-opens STREAM_URL on the existing zone. Returns false if there is no
     * connected master, so the caller can fall back to createZone().
     *
     * Skip, resume and the watchdog all need this: createZone() is what made
     * pause/play mint a new zone id every time (~17:11-17:12).
     */
    private boolean reopenStream(String reason) {
        return reopenStream(reason, false);
    }

    /**
     * The wait is how long this reopen sat behind another controller operation
     * (usually the group scan). playItem is how long the speaker call itself
     * took. A wait of many seconds is a mid-song hole: the reopen lands late.
     */
    private boolean reopenStream(String reason, boolean coalesce) {
        long requested = System.currentTimeMillis();
        synchronized (this) {
            long waited = System.currentTimeMillis() - requested;
            Speaker current = master;
            if (current == null || !current.isConnected()) {
                if (waited >= 200) {
                    log(reason + ": reopen gave up after waiting " + waited + "ms, lead gone");
                }
                return false;
            }
            long now = System.currentTimeMillis();
            if (coalesce && streaming && now - lastReopenAt < REOPEN_COALESCE_MS) {
                log(reason + ": coalesced reopen on " + current.getName()
                        + " (waited " + waited + "ms)");
                return true;
            }
            long call = System.currentTimeMillis();
            try {
                current.playItem(liveStreamUrl());
                long took = System.currentTimeMillis() - call;
                lastReopenAt = now;
                streaming = true;
                playbackStartedAt = System.currentTimeMillis();
                notPlayingStreak = 0;
                log(reason + ": reopened stream on " + current.getName()
                        + " (zone kept, waited " + waited + "ms, playItem " + took + "ms)");
                return true;
            } catch (Exception e) {
                long took = System.currentTimeMillis() - call;
                log(reason + " reopen failed after " + took + "ms, waited " + waited
                        + "ms (" + e.getMessage() + ")");
                streaming = false;
                return false;
            }
        }
    }

    /**
     * Cache-bust so playItem() is never the same URL the speakers already have.
     * AllPlay treats a repeat playItem of the identical URL as a no-op, which
     * is why a long pause whose stop() timed out (stopped 0) stayed silent on
     * resume until the user skipped.
     */
    private String liveStreamUrl() {
        char sep = STREAM_URL.indexOf('?') >= 0 ? '&' : '?';
        return STREAM_URL + sep + "t=" + System.currentTimeMillis();
    }

    /**
     * Clears a source pause and starts the speakers again if they are down.
     * While streaming is still true the in-flight debounce is cancelled by
     * epoch; if it already stopped anyone it reopens them itself.
     */
    private String resumeFromSource(String reason) {
        sourcePaused = false;
        lastResumeAt = System.currentTimeMillis();
        cancelPendingPause();
        if (streaming) {
            return "resumed\n";
        }
        // A group that is already there is reopened, not rebuilt. Look again
        // only when more remembered rooms have shown up than the live group has.
        if (!maybeMoreRooms() && reopenStream(reason)) {
            return "resumed\n";
        }
        resumeNow();
        return "resumed\n";
    }

    /**
     * startPlayback() off the HTTP thread when there is no zone yet, or when
     * Icecast is not live. A long pause lets Icecast drop the source
     * (source-timeout); ffmpeg then dies with a broken pipe on the next PCM and
     * systemd restarts it. A single isStreamLive() check at that moment fails
     * and we used to wait until the next supervision pass (~12s). That was the
     * ~15s from pressing play to sound. Retry here on a dedicated thread so we
     * do not block the pause timer.
     */
    private void resumeNow() {
        Thread thread = new Thread(new Runnable() {
            public void run() {
                for (int attempt = 0; attempt < 20; attempt++) {
                    if (sourcePaused || streaming) {
                        return;
                    }
                    try {
                        if (isStreamLive()) {
                            if (maybeMoreRooms() || !reopenStream("resume")) {
                                startPlayback();
                            }
                            return;
                        }
                    } catch (Exception e) {
                        log("resume failed (" + e.getMessage() + "), supervision will retry");
                        streaming = false;
                        return;
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                log("resume: stream not live after 10s, supervision will retry");
            }
        }, "allplay-resume");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * True when Icecast has a source on this mount.
     *
     * Do not GET the audio mount to find out. That request joins as another
     * listener, and with burst-size 0 Icecast then glitches the speaker that is
     * already playing. Seen every poll: listener count 1, then 2, then 1, client
     * Java, about 1.6 KB, and a short silence in the song. status-json is an
     * ordinary short document and does not attach to the stream.
     */
    private boolean isStreamLive() {
        HttpURLConnection connection = null;
        try {
            URL stream = new URL(STREAM_URL);
            int port = stream.getPort();
            if (port < 0) {
                port = stream.getDefaultPort();
            }
            String mount = stream.getPath();
            if (mount == null || mount.isEmpty()) {
                return false;
            }
            URL stats = new URL("http", "127.0.0.1", port, "/status-json.xsl");
            connection = (HttpURLConnection) stats.openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            if (connection.getResponseCode() != 200) {
                return false;
            }
            InputStream in = connection.getInputStream();
            try {
                byte[] buf = new byte[4096];
                StringBuilder body = new StringBuilder();
                int total = 0;
                int n;
                while ((n = in.read(buf)) != -1 && total < 65536) {
                    body.append(new String(buf, 0, n, UTF8));
                    total += n;
                }
                return body.indexOf(mount) >= 0;
            } finally {
                in.close();
            }
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
                    + "  (/status /volume /band /mute /pause /resume /skip /seek /play /stop /group)");
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
            saveState();
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
            saveState();
            return "band=" + volumeFloor + "-" + volumeCeiling + " volume=" + desiredVolume + "\n";
        }
        if (path.startsWith("/mute")) {
            muted = !q.containsKey("on") || Boolean.parseBoolean(q.get("on"));
            applyVolume();
            saveState();
            return "muted=" + muted + "\n";
        }
        // Pause has to reach the speakers, not just the source. Stopping the
        // source only stops refilling the pipeline, so the speakers keep playing
        // whatever they have already buffered - measured at 15-20s here, most of
        // it inside the speakers themselves where it cannot be tuned away.
        //
        // stop() rather than pause() because a paused speaker keeps its buffer
        // and would resume on stale audio. Dropping it means resume restarts
        // from live, at the cost of a short re-buffer.
        // librespot emits transient "paused" during track transitions - observed
        // playing at :54 and paused at :56 mid-playback - so acting immediately
        // stops the speakers in the middle of normal listening. Defer the stop
        // and cancel it if playback resumes, which costs a little pause latency
        // and makes the behaviour correct.
        if (path.startsWith("/pause")) {
            long sinceResume = System.currentTimeMillis() - lastResumeAt;
            if (lastResumeAt > 0 && sinceResume < PAUSE_IGNORE_AFTER_RESUME_MS) {
                log("ignoring pause " + sinceResume + "ms after resume");
                return "pause ignored (just resumed)\n";
            }
            sourcePaused = true;
            log("pause requested, debouncing " + PAUSE_DEBOUNCE_MS + "ms");
            schedulePause();
            return "pause scheduled in " + PAUSE_DEBOUNCE_MS + "ms\n";
        }
        // Resume has to act now. Deferring to the supervision loop meant up to
        // poll.seconds of silence after pressing play. Do the reopen on this
        // thread so streaming=true before we return; otherwise superviseOnce
        // races us and playItem()s twice.
        if (path.startsWith("/resume")) {
            return resumeFromSource("resume");
        }
        // Skip and seek: re-open the stream URL on the existing zone. The
        // speakers hold 15-20s of the previous track (or the pre-seek position)
        // and nothing else makes them drop it. Deliberately does NOT
        // createZone(): rebuilding the zone is the audible tear-down that made
        // skipping worse than leaving it alone.
        if (path.startsWith("/skip") || path.startsWith("/seek")) {
            String reason = path.startsWith("/seek") ? "seek" : "skip";
            if (sourcePaused) {
                return "paused, " + reason + " ignored (resume will start from the new position)\n";
            }
            if (!streaming) {
                return "not streaming, " + reason + " deferred to the next pass\n";
            }
            if (reopenStream(reason)) {
                return ("seek".equals(reason) ? "seeked\n" : "skipped\n");
            }
            return reason + " failed, supervision will recover\n";
        }
        if (path.startsWith("/play")) {
            return resumeFromSource("play");
        }
        // Every room that is currently on. Not something Spotify can ask for.
        if (path.startsWith("/group")) {
            return groupAllOn();
        }
        if (path.startsWith("/stop")) {
            stopGrouped();
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
        sb.append("paused    ").append(sourcePaused).append(" (reported by source)\n");
        sb.append("lead      ").append(current == null ? "-" : current.getName()).append('\n');
        if (groupIds.isEmpty()) {
            String remembered = joinList(savedNames);
            sb.append("group     ").append(remembered.isEmpty()
                    ? "- (playback groups every room that is on)"
                    : "- (remembered " + remembered + ")").append('\n');
        } else {
            sb.append("group     ").append(joinNames(groupIds)).append('\n');
        }
        String leftOut = leftOutNames();
        sb.append("left out  ").append(leftOut.isEmpty() ? "-" : leftOut).append('\n');
        String off = rememberedOffNames();
        if (!off.isEmpty()) {
            sb.append("off       ").append(off).append('\n');
        }
        sb.append("all rooms GET /group\n");
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
            if (current != null && current.getId().equals(speaker.getId())) {
                sb.append(" (lead)");
            } else if (!groupIds.isEmpty() && !groupIds.contains(speaker.getId())) {
                sb.append(" (not in group)");
            }
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
     * Cleans up on SIGTERM rather than from a JVM shutdown hook.
     *
     * alljoyn.jar registers its own shutdown hook inside BusAttachment.connect().
     * JVM hooks run concurrently in no defined order, so cleanup registered as a
     * hook races AllJoyn tearing the bus down underneath it. Handling the signal
     * instead runs cleanup while the bus is still fully alive. The group is
     * left in place: releaseZone() is what would dissolve the rooms chosen in
     * the standalone app, and a restart must not do that.
     *
     * halt() rather than exit() afterwards, because the work is already done and
     * letting AllJoyn's hook run adds thousands of error lines about sessions
     * that have gone away. The process is ending, so the OS reclaims the rest.
     *
     * sun.misc.Signal is reached reflectively so this still compiles with
     * --release 8, and falls back to a plain hook if it is unavailable.
     */
    private void installTerminationHandler() {
        try {
            final Class<?> signalClass = Class.forName("sun.misc.Signal");
            final Class<?> handlerClass = Class.forName("sun.misc.SignalHandler");
            Object handler = Proxy.newProxyInstance(handlerClass.getClassLoader(),
                    new Class<?>[] { handlerClass }, new InvocationHandler() {
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("handle".equals(method.getName())) {
                                shutdown();
                                Runtime.getRuntime().halt(0);
                            }
                            return null;
                        }
                    });
            Object signal = signalClass.getConstructor(String.class).newInstance("TERM");
            signalClass.getMethod("handle", signalClass, handlerClass)
                    .invoke(null, signal, handler);
            log("SIGTERM handler installed (cleanup runs before AllJoyn tears the bus down)");
            return;
        } catch (Throwable t) {
            log("no SIGTERM handler available (" + t.getClass().getSimpleName()
                    + "), falling back to a shutdown hook; stop may race AllJoyn");
        }
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                shutdown();
            }
        }, "allplay-shutdown"));
    }

    /**
     * Stop playback, then drop the speakers, then the bus. The group is not
     * released: the rooms chosen in the app have to still be grouped after a
     * restart. Stop only the rooms in the group.
     */
    private void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        log("shutting down, leaving the speaker group in place");
        try {
            stopGrouped();
        } catch (Exception e) {
            log("could not stop playback: " + e.getMessage());
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

    private void replaceSaved(List<Speaker> members) {
        List<String> ids = new ArrayList<String>();
        List<String> names = new ArrayList<String>();
        for (Speaker speaker : members) {
            ids.add(speaker.getId());
            names.add(speaker.getName());
        }
        sortByName(ids, names);
        savedIds = ids;
        savedNames = names;
        saveState();
    }

    private static Set<String> idsOf(List<Speaker> members) {
        Set<String> ids = new HashSet<String>();
        for (Speaker speaker : members) {
            ids.add(speaker.getId());
        }
        return ids;
    }

    private static boolean sameSpeakers(Set<String> ids, List<Speaker> members) {
        if (ids.size() != members.size()) {
            return false;
        }
        for (Speaker speaker : members) {
            if (!ids.contains(speaker.getId())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameIds(List<String> left, List<String> right) {
        if (left.size() != right.size()) {
            return false;
        }
        return new HashSet<String>(left).equals(new HashSet<String>(right));
    }

    private static boolean properSubset(List<String> smaller, List<String> larger) {
        if (smaller.size() >= larger.size()) {
            return false;
        }
        return new HashSet<String>(larger).containsAll(smaller);
    }

    private static void sortByName(List<String> ids, List<String> names) {
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                if (names.get(j).compareToIgnoreCase(names.get(i)) < 0) {
                    Collections.swap(names, i, j);
                    Collections.swap(ids, i, j);
                }
            }
        }
    }

    private String joinNames(Set<String> ids) {
        List<String> names = new ArrayList<String>();
        for (Speaker speaker : speakers.values()) {
            if (ids.contains(speaker.getId())) {
                names.add(speaker.getName());
            }
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return joinList(names);
    }

    private String leftOutNames() {
        Set<String> ids = groupIds;
        if (ids.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<String>();
        for (Speaker speaker : speakers.values()) {
            if (!ids.contains(speaker.getId())) {
                names.add(speaker.getName());
            }
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return joinList(names);
    }

    private String rememberedOffNames() {
        List<String> names = new ArrayList<String>();
        List<String> ids = savedIds;
        List<String> saved = savedNames;
        for (int i = 0; i < ids.size() && i < saved.size(); i++) {
            if (!speakers.containsKey(ids.get(i))) {
                names.add(saved.get(i));
            }
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return joinList(names);
    }

    private static String joinList(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(names.get(i));
        }
        return sb.toString();
    }

    private String encodeGroup() {
        StringBuilder sb = new StringBuilder();
        List<String> ids = savedIds;
        List<String> names = savedNames;
        int count = Math.min(ids.size(), names.size());
        for (int i = 0; i < count; i++) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(urlEncode(ids.get(i))).append('=').append(urlEncode(names.get(i)));
        }
        return sb.toString();
    }

    private void decodeGroup(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<String>();
        List<String> names = new ArrayList<String>();
        String[] tokens = raw.split(",");
        for (int i = 0; i < tokens.length; i++) {
            int eq = tokens[i].indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                ids.add(URLDecoder.decode(tokens[i].substring(0, eq), "UTF-8"));
                names.add(URLDecoder.decode(tokens[i].substring(eq + 1), "UTF-8"));
            } catch (UnsupportedEncodingException e) {
                log("ignoring unreadable remembered room");
            }
        }
        savedIds = ids;
        savedNames = names;
    }

    private static String urlEncode(String raw) {
        try {
            return URLEncoder.encode(raw, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return raw;
        }
    }

    private static final class ZoneHit {
        final String zoneId;
        Speaker lead;
        final List<Speaker> members = new ArrayList<Speaker>();
        final Set<String> memberIds = new HashSet<String>();

        ZoneHit(String zoneId) {
            this.zoneId = zoneId;
        }
    }

    private static final class Scan {
        ZoneHit zone;
        /** zone.lead once connected, else null. Set by {@link #scanForGroup()}. */
        Speaker zoneLead;
        boolean readable;
        final List<Speaker> connected = new ArrayList<Speaker>();
        final List<Speaker> readableSpeakers = new ArrayList<Speaker>();
    }

    private static void log(String message) {
        System.out.println("[allplay] " + message);
        System.out.flush();
    }
}
