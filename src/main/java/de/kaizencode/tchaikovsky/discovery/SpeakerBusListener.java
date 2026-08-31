/**
 * Tchaikovsky - A Java library for controlling AllPlay-compatible devices.
 * Copyright (c) 2016 Dominic Lerbs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.kaizencode.tchaikovsky.discovery;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.alljoyn.bus.AboutProxy;
import org.alljoyn.bus.BusAttachment;
import org.alljoyn.bus.BusException;
import org.alljoyn.bus.BusListener;
import org.alljoyn.bus.Mutable;
import org.alljoyn.bus.SessionListener;
import org.alljoyn.bus.SessionOpts;
import org.alljoyn.bus.Status;
import org.alljoyn.bus.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.kaizencode.tchaikovsky.bus.SpeakerBusHandler;
import de.kaizencode.tchaikovsky.bussignal.MediaPlayerSignalHandler;
import de.kaizencode.tchaikovsky.exception.AllPlayException;
import de.kaizencode.tchaikovsky.exception.ConnectionException;
import de.kaizencode.tchaikovsky.exception.SpeakerException;
import de.kaizencode.tchaikovsky.listener.SpeakerAnnouncedListener;
import de.kaizencode.tchaikovsky.speaker.Speaker;
import de.kaizencode.tchaikovsky.speaker.SpeakerDetails;
import de.kaizencode.tchaikovsky.speaker.remote.RemoteSpeaker;
import de.kaizencode.tchaikovsky.speaker.remote.RemoteSpeakerDetails;

/**
 * {@link BusListener} implementation for announcing discovered speakers.
 * 
 * @author Dominic Lerbs
 */
public class SpeakerBusListener extends BusListener {

    private final Logger logger = LoggerFactory.getLogger(SpeakerBusListener.class);

    private static final short PORT = 1;
    private static final int DISCOVERY_THREADS = 4;
    private static final int SHUTDOWN_TIMEOUT_IN_SEC = 5;

    private final BusAttachment busAttachment;
    private final List<SpeakerAnnouncedListener> listeners = new CopyOnWriteArrayList<>();
    private final MediaPlayerSignalHandler signalHandler;

    /**
     * Speaker details are fetched on this pool rather than on the calling thread, because
     * foundAdvertisedName is invoked by the native library and calling back into it from the
     * same thread deadlocks when several speakers are found at once. The pool is bounded so a
     * burst of announcements cannot spawn threads without limit.
     */
    private final ExecutorService discoveryExecutor = Executors.newFixedThreadPool(DISCOVERY_THREADS,
            new ThreadFactory() {
                private final AtomicInteger threadCount = new AtomicInteger(1);

                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "tchaikovsky-discovery-" + threadCount.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                }
            });

    public SpeakerBusListener(BusAttachment busAttachment, MediaPlayerSignalHandler signalHandler) {
        this.busAttachment = busAttachment;
        this.signalHandler = signalHandler;
    }

    /**
     * Stops the discovery workers and waits for them to finish. The listener cannot be reused
     * afterwards. Callers must let this return before releasing the {@link BusAttachment}, since a
     * worker still in flight would otherwise use it after it has been released.
     */
    public void shutdown() {
        discoveryExecutor.shutdownNow();
        try {
            if (!discoveryExecutor.awaitTermination(SHUTDOWN_TIMEOUT_IN_SEC, TimeUnit.SECONDS)) {
                logger.warn("Discovery workers still running after " + SHUTDOWN_TIMEOUT_IN_SEC
                        + "s, they may still be using the bus attachment");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void foundAdvertisedName(String wellKnownName, short transport, String namePrefix) {
        super.foundAdvertisedName(wellKnownName, transport, namePrefix);
        logger.info("foundAdvertisedName " + wellKnownName + " - " + transport + " - " + namePrefix);
        if (!wellKnownName.endsWith(".quiet")) {
            // foundAdvertisedName is called from the native library. If we call the native
            // library from here in the same thread, a deadlock might occur. This happens especially when multiple
            // speakers are found at almost the same time.
            handleFoundSpeakerAsync(wellKnownName);
        }
    }

    private void handleFoundSpeakerAsync(String wellKnownName) {
        Runnable run = new Runnable() {
            @Override
            public void run() {
                try {
                    logger.info("Creating speaker details " + wellKnownName);
                    SpeakerDetails speakerDetails = createSpeakerDetails(wellKnownName);
                    logger.info("Announcing new speaker " + wellKnownName);
                    announceNewSpeaker(wellKnownName, speakerDetails);
                } catch (AllPlayException e) {
                    logger.warn("Unable to announce speaker for advertised name " + wellKnownName, e);
                }
            }
        };
        try {
            discoveryExecutor.execute(run);
        } catch (RejectedExecutionException e) {
            logger.debug("Discovery already shut down, ignoring advertised name " + wellKnownName);
        }
    }

    private SpeakerDetails createSpeakerDetails(String wellKnownName) throws AllPlayException {
        Map<String, Variant> aboutData = getAboutData(wellKnownName);
        try {
            return new RemoteSpeakerDetails(wellKnownName, aboutData);
        } catch (BusException e) {
            throw new SpeakerException("Unable to read speaker details", e);
        }
    }

    private Map<String, Variant> getAboutData(String sessionHost) throws AllPlayException {
        int sessionId = joinSession(sessionHost);
        try {
            AboutProxy aboutProxy = new AboutProxy(busAttachment, sessionHost, sessionId);
            return aboutProxy.getAboutData("en");
        } catch (BusException e) {
            throw new SpeakerException("Unable to retrieve about data", e);
        } finally {
            busAttachment.leaveSession(sessionId);
        }
    }

    private int joinSession(String sessionHost) throws ConnectionException {
        Mutable.IntegerValue sessionId = new Mutable.IntegerValue();
        logger.info("Joining session with host [" + sessionHost + "], port [" + PORT + "]");

        Status status = busAttachment.joinSession(sessionHost, PORT, sessionId, createSessionOptions(),
                new SessionListener());
        if (status != Status.OK) {
            throw new ConnectionException("Unable to join session " + sessionId.value + " on host " + sessionHost,
                    status);
        }
        logger.info("Joined session from local bus [" + busAttachment.getUniqueName() + "] to remote host ["
                + sessionHost + "] on sessionId [" + sessionId.value + "]");
        return sessionId.value;

    }

    private void announceNewSpeaker(String hostName, SpeakerDetails details) {
        SpeakerBusHandler busHandler = new SpeakerBusHandler(busAttachment, hostName, PORT, signalHandler);
        RemoteSpeaker speaker = new RemoteSpeaker(busHandler, details);

        for (SpeakerAnnouncedListener listener : listeners) {
            listener.onSpeakerAnnounced(speaker);
        }
    }

    private SessionOpts createSessionOptions() {
        SessionOpts sessionOpts = new SessionOpts();
        sessionOpts.traffic = SessionOpts.TRAFFIC_MESSAGES;
        sessionOpts.isMultipoint = false;
        sessionOpts.proximity = SessionOpts.PROXIMITY_ANY;
        sessionOpts.transports = SessionOpts.TRANSPORT_ANY;
        return sessionOpts;
    }

    /**
     * Add a new {@link SpeakerAnnouncedListener} to be informed when a new {@link Speaker} has been discovered.
     * 
     * @param listener
     *            The {@link SpeakerAnnouncedListener} to be informed
     */
    public void addSpeakerAnnouncedListener(SpeakerAnnouncedListener listener) {
        listeners.add(listener);
        logger.debug("New SpeakerAnnouncedListener " + listener.toString() + " has been added");
    }

    /**
     * Remove a {@link SpeakerAnnouncedListener}
     * 
     * @param listener
     *            The {@link SpeakerAnnouncedListener} to be removed
     */
    public void removeSpeakerAnnouncedListener(SpeakerAnnouncedListener listener) {
        listeners.remove(listener);
        logger.debug("SpeakerAnnouncedListener " + listener.toString() + " has been removed");
    }

}
