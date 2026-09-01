import de.kaizencode.tchaikovsky.AllPlay;
import de.kaizencode.tchaikovsky.speaker.Speaker;
import de.kaizencode.tchaikovsky.listener.SpeakerAnnouncedListener;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

public class PiTest2 {
    static long t0 = System.currentTimeMillis();
    static void log(String s) {
        System.out.printf("[%6.1fs] %s%n", (System.currentTimeMillis()-t0)/1000.0, s);
        System.out.flush();
    }
    public static void main(String[] args) throws Exception {
        log("constructing AllPlay (loads native lib)");
        AllPlay allPlay = new AllPlay("TchaikovskyPiTest");

        final List<Speaker> found = new CopyOnWriteArrayList<>();
        allPlay.addSpeakerAnnouncedListener(new SpeakerAnnouncedListener() {
            public void onSpeakerAnnounced(Speaker s) {
                log("*** SPEAKER: " + s.getName() + " [" + s.getId() + "]");
                found.add(s);
            }
        });

        log("connect() ...");
        allPlay.connect();
        log("connected=" + allPlay.isConnected());

        String mode = args.length > 1 ? args[1] : "ANNOUNCEMENT_BASED";
        log("discoverSpeakers(" + mode + ") ...");
        try {
            allPlay.discoverSpeakers(AllPlay.DiscoveryMode.valueOf(mode));
            log("discovery started OK");
        } catch (Exception e) {
            log("discovery FAILED: " + e.getMessage());
        }

        int wait = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        log("listening " + wait + "s ...");
        Thread.sleep(wait * 1000L);

        log("speakers found: " + found.size());
        log("disconnect() ...");
        allPlay.disconnect();
        log("RESULT speakers=" + found.size());
    }
}
