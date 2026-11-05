package mactak.headless;

import com.atakmap.commoncommo.Commo;
import com.atakmap.commoncommo.CommoException;
import com.atakmap.commoncommo.CommoLogger;
import com.atakmap.commoncommo.CoTMessageType;
import com.atakmap.commoncommo.Contact;
import com.atakmap.commoncommo.ContactPresenceListener;
import com.atakmap.commoncommo.NetInterface;
import com.atakmap.commoncommo.InterfaceStatusListener;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * atak-headless entry point: one JVM, both stacks. {@link #main} loads the
 * engine ({@code libtakengine} + JNI glue) and comms ({@code libcommoncommo}
 * + JNI glue) dylibs from {@code -Dmactak.native.dist} into this process,
 * then dispatches: {@code --layer} → {@link LayerCLI} (G1.3), anything else
 * → the CoT endpoint below.
 *
 * G1.2 — headless CoT endpoint on the commoncommo JNI stack.
 *
 * Connects to a TAK server's CoT port as a streaming (TCP client)
 * interface — plaintext (e.g. FreeTAKServer tcp/8087, OpenTAKServer 8088)
 * or, with {@code --cert} + {@code --ca} (PKCS#12, the two files in an ATAK
 * data package; passwords from {@code MACTAK_CERT_PASS} /
 * {@code MACTAK_CA_PASS}), SSL with a client certificate (OpenTAKServer
 * 8089). Announces presence on a
 * short heartbeat once the interface comes up (the server only registers a
 * client after its first message — and commo only makes the interface
 * sendable after the interfaceUp listeners return, so sends never happen
 * inside the callback; see the sender thread in main), prints every
 * received CoT verbatim, and exits 0 after --duration seconds.
 *
 * G1.4 — every received CoT that parses as an {@code <event>} with a
 * {@code <point>} is persisted through the engine's {@code DatabaseImpl}
 * JNI into a sqlite tracks DB ({@code --db PATH}; a private temporary DB
 * when omitted), and the newest fix per uid is printed on exit — the
 * roadmap's "plots them in-memory" demo. Everything the smoke script
 * asserts on is printed as a single machine-greppable "headless:" line:
 *
 *   headless: iface-up | iface-down
 *   headless: contact-added &lt;uid&gt;
 *   headless: cot-received &lt;raw xml, one line&gt;
 *   headless: cot-unparsed &lt;reason&gt;
 *   headless: presence-sent | presence-heartbeat n=N
 *   headless: marker-sent lat=&lt;lat&gt; lon=&lt;lon&gt;
 *   headless: summary received=N persisted=N ifaceUpSeen=&lt;b&gt; presenceSent=N markerSent=N
 *   headless: track uid=&lt;uid&gt; type=&lt;t&gt; callsign=&lt;cs&gt; lat=&lt;lat&gt; lon=&lt;lon&gt; time=&lt;t&gt; fixes=N
 *   headless: tracks uids=N fixes=N db=&lt;path|temporary&gt;
 *
 * Exit codes: 0 completed the run, 2 usage, 3 stack/connect failure,
 * 4 dead run: no CoT received or interface never came up.
 */
public final class HeadlessCLI {

    /** Desktop load order: core before its JNI glue, per stack (EngineLibrary's list). */
    private static final String[] NATIVE_LIBS = {
        "libtakengine.dylib", "libtakenginejni.dylib",
        "libcommoncommo.dylib", "libcommoncommojni.dylib",
    };

    private static final SimpleDateFormat COT_TIME = new SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
    static {
        COT_TIME.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private static String now(long offsetMs) {
        synchronized (COT_TIME) {
            return COT_TIME.format(new Date(System.currentTimeMillis() + offsetMs));
        }
    }

    public static class Log implements CommoLogger {
        public void log(CommoLogger.Level l, CommoLogger.Type t,
                        String m, CommoLogger.LoggingDetail d) {
            if (l == CommoLogger.Level.VERBOSE || l == CommoLogger.Level.DEBUG)
                return;
            System.err.println("commo[" + l + "] " + m);
        }
    }

    /**
     * G1.5: resolve an ATAK data package (DataPackage.java), extracting its
     * PKCS#12 pair to a private temp dir. Failure prints the reason, exits 2.
     *
     * The dir and the extracted PKCS#12s never outlive this process:
     * deleteOnExit covers every exit path (System.exit here and in main,
     * SIGINT/SIGTERM) — the dir is registered first so its files, registered
     * after, delete first (deleteOnExit is LIFO) — and a failed open() wipes
     * the dir at once, so a mid-extraction failure leaves nothing either.
     * kill -9 is the one exception, as with any temp cleanup.
     */
    private static DataPackage.Resolved resolveDataPackage(String dpPath) {
        final File certDir;
        try {
            certDir = Files.createTempDirectory("mactak-dp-certs.").toFile();
        } catch (IOException e) {
            System.err.println("atak-headless: cannot use data package '"
                    + dpPath + "': cannot create temp cert dir: " + e.getMessage());
            System.exit(2);
            return null;    // not reached
        }
        certDir.deleteOnExit();
        final DataPackage.Resolved dpResolved;
        try {
            dpResolved = DataPackage.open(new File(dpPath), certDir);
        } catch (IOException e) {
            deleteTree(certDir);    // open() may have died mid-extraction
            System.err.println("atak-headless: cannot use data package '"
                    + dpPath + "': " + e.getMessage());
            System.exit(2);
            return null;    // not reached
        }
        if (dpResolved.certPath != null)
            new File(dpResolved.certPath).deleteOnExit();
        if (dpResolved.caPath != null)
            new File(dpResolved.caPath).deleteOnExit();
        System.out.println("headless: datapackage resolved " + dpResolved);
        return dpResolved;
    }

    /**
     * Best-effort recursive delete for the cert temp dir (it holds only the
     * flat PKCS#12 files DataPackage wrote — no subdirs, no links); like any
     * temp cleanup it must never mask the real error by throwing.
     */
    private static void deleteTree(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null)
            for (File kid : kids)
                deleteTree(kid);
        dir.delete();
    }

    private static void usage(int code) {
        System.err.println(
                "usage: atak-headless --connect host:port [--uid UID] [--callsign CS]\n" +
                "                     [--duration SECONDS] [--send-marker LAT LON] [--db PATH]\n" +
                "                     [--cert CLIENT.p12 --ca TRUSTSTORE.p12]   (SSL; passwords via\n" +
                "                      MACTAK_CERT_PASS / MACTAK_CA_PASS, default 'atakatak')\n" +
                "       atak-headless --datapackage FILE   (an ATAK data package: host:port\n" +
                "                      from preference.pref's cot_streams, callsign, and the\n" +
                "                      PKCS#12 pair extracted to a temp dir; --connect /\n" +
                "                      --callsign / --cert + --ca override what it resolved,\n" +
                "                      MACTAK_CERT_PASS overrides the package's cert password)\n" +
                "       atak-headless --layer FILE [--layer FILE ...] [--layer-db DIR]\n" +
                "                     [--expect-features N] [--quiet]");
        System.exit(code);
    }

    public static void main(String[] args) {
        final String dist = System.getProperty("mactak.native.dist");
        if (dist == null) {
            System.err.println("mactak.native.dist not set — run via the atak-headless launcher");
            System.exit(3);
        }
        try {
            for (String lib : NATIVE_LIBS) System.load(dist + "/" + lib);
        } catch (UnsatisfiedLinkError e) {
            System.err.println("atak-headless: native load failed: " + e.getMessage());
            System.exit(3);
        }

        if (args.length > 0 && "--layer".equals(args[0])) {
            System.exit(LayerCLI.run(args));
            return;
        }

        String host = null, uid = "g1-headless-" + (System.nanoTime() % 1000000L),
               callsign = null, markerLat = null, markerLon = null, dbPath = null,
               certPath = null, caPath = null, dpPath = null, dpCertPass = null;
        int port = -1, duration = 15;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--connect":
                    if (i + 1 >= args.length) usage(2);
                    String[] hp = args[++i].split(":");
                    if (hp.length != 2) usage(2);
                    host = hp[0];
                    try { port = Integer.parseInt(hp[1]); }
                    catch (NumberFormatException e) { usage(2); }
                    break;
                case "--uid":       if (i + 1 >= args.length) usage(2);
                                    uid = args[++i]; break;
                case "--callsign":  if (i + 1 >= args.length) usage(2);
                                    callsign = args[++i]; break;
                case "--duration":  if (i + 1 >= args.length) usage(2);
                                    try { duration = Integer.parseInt(args[++i]); }
                                    catch (NumberFormatException e) { usage(2); }
                                    break;
                case "--send-marker":
                    if (i + 2 >= args.length) usage(2);
                    markerLat = args[++i];
                    markerLon = args[++i];
                    break;
                case "--db":        if (i + 1 >= args.length) usage(2);
                                    dbPath = args[++i]; break;
                case "--cert":      if (i + 1 >= args.length) usage(2);
                                    certPath = args[++i]; break;
                case "--ca":        if (i + 1 >= args.length) usage(2);
                                    caPath = args[++i]; break;
                case "--datapackage":
                                    if (i + 1 >= args.length) usage(2);
                                    dpPath = args[++i]; break;
                default: usage(2);
            }
        }
        if (host == null && dpPath == null) usage(2);
        if ((certPath == null) != (caPath == null)) usage(2);   // SSL needs both
        // G1.5: an ATAK data package fills in whatever the flags left unset
        // (explicit flags win); MACTAK_CERT_PASS overrides its password, below.
        if (dpPath != null) {
            DataPackage.Resolved dp = resolveDataPackage(dpPath);
            if (host == null) {
                host = dp.host;
                port = dp.port;
            }
            if (callsign == null && dp.callsign != null)
                callsign = dp.callsign;
            if (certPath == null) {
                certPath = dp.certPath;
                caPath = dp.caPath;
            }
            dpCertPass = dp.certPass;
        }
        if (callsign == null)
            callsign = "HEADLESS";

        final AtomicInteger received = new AtomicInteger();
        final AtomicInteger persisted = new AtomicInteger();
        final AtomicInteger presenceSent = new AtomicInteger();
        final AtomicInteger markerSent = new AtomicInteger();
        final AtomicBoolean ifaceUpSeen = new AtomicBoolean(false);
        final AtomicBoolean done = new AtomicBoolean(false);

        TrackStore tracks = null;
        try {
            // Engine side first: the tracks DB goes through DatabaseImpl's JNI
            // (Java -> glue -> C++ Database2 -> sqlite), same path as the G1.1 smoke.
            tracks = new TrackStore(dbPath);
            final TrackStore fTracks = tracks;
            System.out.println("headless: tracks-db " + (dbPath == null ? "temporary" : dbPath));

            Commo.initThirdpartyNativeLibraries();

            final Commo commo = new Commo(new Log(), uid, callsign);
            final String fUid = uid, fCs = callsign,
                         fLat = markerLat, fLon = markerLon;

            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    if (done.compareAndSet(false, true))
                        commo.shutdown();
                }
            });

            commo.addInterfaceStatusListener(new InterfaceStatusListener() {
                public void interfaceUp(NetInterface iface) {
                    if (!ifaceUpSeen.compareAndSet(false, true)) return;
                    System.out.println("headless: iface-up");
                    // Do NOT broadcast here. commo fires interfaceUp
                    // listeners BEFORE inserting the context into its
                    // sendable up-contexts list (streamingsocketmanagement
                    // fires the change, then inserts), so a send from this
                    // callback reaches zero interfaces — broadcastCoT
                    // reports success while queueing nothing. The sender
                    // thread below announces on a short delay instead.
                }
                public void interfaceDown(NetInterface iface) {
                    System.out.println("headless: iface-down");
                }
                public void interfaceError(NetInterface iface,
                        com.atakmap.commoncommo.NetInterfaceErrorCode code) {
                    System.out.println("headless: iface-error " + code);
                }
            });

            commo.addContactPresenceListener(new ContactPresenceListener() {
                public void contactAdded(Contact c) {
                    System.out.println("headless: contact-added " + c.contactUID);
                }
                public void contactRemoved(Contact c) {
                    System.out.println("headless: contact-removed " + c.contactUID);
                }
            });

            commo.addCoTMessageListener(
                    new com.atakmap.commoncommo.CoTMessageListener() {
                public void cotMessageReceived(String cotMessage, String rxEndpointId) {
                    received.incrementAndGet();
                    System.out.println("headless: cot-received " + (cotMessage == null
                            ? "" : cotMessage.replaceAll("\\s+", " ").trim()));
                    if (cotMessage == null || done.get()) return;
                    try {
                        final CotEvent e = CotEvent.parse(cotMessage);
                        // TAK server pings (t-x-c-t, "<uid>-ping") are not tracks; ATAK drops them too.
                        if (e.type.startsWith("t-x-c-t")) return;
                        fTracks.record(e, System.currentTimeMillis());
                        persisted.incrementAndGet();
                    } catch (Exception e) {
                        System.out.println("headless: cot-unparsed " + e.getMessage());
                    }
                }
            });

            System.out.println("headless: connecting " + host + ":" + port
                    + (certPath == null ? "" : " ssl cert=" + new File(certPath).getName())
                    + " uid=" + uid + " callsign=" + callsign);
            // A client cert switches commo's streaming interface to SSL. Data-package
            // PKCS#12 is RC2-40: OpenSSL 3 needs its legacy provider (NativeSsl shim).
            // G1.5 precedence: MACTAK_CERT_PASS over the data package's
            // firecrypt Password over the built-in default.
            final String certPass = env("MACTAK_CERT_PASS",
                    dpCertPass != null ? dpCertPass : "atakatak");
            final String caPass = env("MACTAK_CA_PASS", certPass);
            final byte[] clientCert = certPath == null ? null : readFile(certPath);
            final byte[] caCert = caPath == null ? null : readFile(caPath);
            if (clientCert != null && !NativeSsl.loadLegacyProvider())
                System.err.println("headless: OpenSSL legacy provider unavailable; RC2-40 PKCS#12 will fail");
            commo.addStreamingInterface(host, port,
                    new CoTMessageType[] { CoTMessageType.SITUATIONAL_AWARENESS },
                    clientCert, caCert,
                    clientCert == null ? null : certPass,
                    caCert == null ? null : caPass,
                    null, null);

            long deadline = System.currentTimeMillis() + duration * 1000L;

            // Announcements run off the interfaceUp callback: the first
            // presence goes out sendDelayMs after iface-up (comfortably
            // after commo makes the interface sendable), then on a steady
            // heartbeat — mirroring ATAK's periodic SA — until the run
            // ends. The marker, if requested, goes out once, on the first
            // send window.
            final long sendDelayMs = 500L, sendPeriodMs = 2000L;
            Thread sender = new Thread() {
                public void run() {
                    boolean markerDone = (fLat == null);
                    boolean firstPresence = true;
                    while (!done.get()
                            && System.currentTimeMillis() < deadline) {
                        if (!ifaceUpSeen.get()) {
                            try { Thread.sleep(100); }
                            catch (InterruptedException e) { return; }
                            continue;
                        }
                        try { Thread.sleep(firstPresence ? sendDelayMs
                                                         : sendPeriodMs); }
                        catch (InterruptedException e) { return; }
                        if (done.get()) return;
                        try {
                            commo.broadcastCoT(presence(fUid, fCs));
                            int n = presenceSent.incrementAndGet();
                            System.out.println(firstPresence
                                    ? "headless: presence-sent"
                                    : "headless: presence-heartbeat n=" + n);
                            firstPresence = false;
                        } catch (CommoException e) {
                            System.err.println("commo[ERROR] presence send failed: " + e);
                        }
                        if (!markerDone) {
                            try {
                                commo.broadcastCoT(
                                        marker(fUid, fCs, fLat, fLon));
                                markerSent.incrementAndGet();
                                System.out.println("headless: marker-sent lat="
                                        + fLat + " lon=" + fLon);
                            } catch (CommoException e) {
                                System.err.println("commo[ERROR] marker send failed: " + e);
                            }
                            markerDone = true;
                        }
                    }
                }
            };
            sender.setDaemon(true);
            sender.start();
            while (sender.isAlive()) {
                try { sender.join(250); } catch (InterruptedException e) { break; }
            }

            if (done.compareAndSet(false, true))
                commo.shutdown();

            System.out.println("headless: summary received=" + received.get()
                    + " persisted=" + persisted.get()
                    + " ifaceUpSeen=" + ifaceUpSeen.get()
                    + " presenceSent=" + presenceSent.get()
                    + " markerSent=" + markerSent.get());
            // The exit demo: what the tracks DB holds, latest fix per uid.
            System.out.print(tracks.summary(dbPath == null ? "temporary" : dbPath));
            tracks.close();
            tracks = null;
            System.exit(received.get() > 0 && ifaceUpSeen.get() ? 0 : 4);
        } catch (CommoException e) {
            System.err.println("commo[ERROR] stack failure: " + e);
            System.exit(3);
        } catch (Throwable t) {
            System.err.println("commo[ERROR] fatal: " + t);
            System.exit(3);
        } finally {
            if (tracks != null) tracks.close();
        }
    }

    private static byte[] readFile(String path) throws IOException {
        return Files.readAllBytes(new File(path).toPath());
    }

    private static String env(String name, String dflt) {
        final String v = System.getenv(name);
        return (v == null || v.isEmpty()) ? dflt : v;
    }

    /**
     * Self-SA as ATAK sends it (a-f-G-U-C + contact/__group/takv/uid): this is
     * what registers the EUD on a TAK server — OpenTAKServer only starts the
     * per-client fan-out consumer once a parseable self-SA with uid, callsign
     * and group has arrived; a bare t-x-a-t-a-k ping does not register.
     */
    private static String presence(String uid, String callsign) {
        String t = now(0), stale = now(30000L);
        return "<event version='2.0' uid='" + uid + "' type='a-f-G-U-C'"
                + " time='" + t + "' start='" + t + "' stale='" + stale + "' how='m-g'>"
                + "<point lat='0.0' lon='0.0' ce='9999999' le='9999999' hae='0.0'/>"
                + "<detail>"
                + "<takv os='macOS' device='macTAK' platform='atak-headless' version='0.1.0'/>"
                + "<contact endpoint='*:-1:stcp' callsign='" + callsign + "'/>"
                + "<uid Droid='" + callsign + "'/>"
                + "<__group name='Cyan' role='Team Member'/>"
                + "<status battery='100'/>"
                + "<track course='0.0' speed='0.0'/>"
                + "</detail></event>";
    }

    private static String marker(String uid, String callsign, String lat, String lon) {
        String t = now(0), stale = now(120000L);
        return "<event version='2.0' uid='" + uid + "-m1' type='a-f-G-U-C'"
                + " time='" + t + "' start='" + t + "' stale='" + stale + "' how='m-g'>"
                + "<point lat='" + lat + "' lon='" + lon + "' ce='10.0' le='10.0' hae='0.0'/>"
                + "<detail>"
                + "<contact callsign='" + callsign + "-M1'/>"
                + "<precisionlocation geopossrc='gps'><point>" + lat + "," + lon
                + "</point></precisionlocation>"
                + "</detail></event>";
    }

    private HeadlessCLI() {}
}

