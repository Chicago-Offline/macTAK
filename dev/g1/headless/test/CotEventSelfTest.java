package mactak.headless;

/**
 * Assertions for the CoT text path of atak-headless, run by test/cot.sh
 * (repo idiom: shell-orchestrated javac+java, no JUnit, no natives).
 *
 * <p>Receive side: {@link CotEvent#parse} — the fields the tracks DB
 * persists, direct-child {@code <point>}/{@code <detail><contact>} lookup,
 * refusal of non-CoT and of DOCTYPE (XXE). Send side: {@link CotXml} — the
 * escaping, the {@code --send-marker} coordinate check, and the presence
 * and marker events, each parsed back through {@link CotEvent} so a
 * builder that emits malformed XML fails here, not on a TAK server.</p>
 */
public final class CotEventSelfTest {

    private static int failures = 0;

    private CotEventSelfTest() {
    }

    private static void check(boolean cond, String what) {
        if (cond)
            System.out.println("PASS: " + what);
        else {
            System.out.println("FAIL: " + what);
            failures++;
        }
    }

    /** Parse expecting failure; returns the message, or null if it parsed. */
    private static String parseMustFail(String xml) {
        try {
            CotEvent.parse(xml);
            return null;
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    private static final String TIME_RE =
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";

    public static void main(String[] args) throws Exception {
        parseSide();
        escapeAndCoordinates();
        builders();
        if (failures == 0) {
            System.out.println("cot-selftest: ALL PASS");
            return;
        }
        System.out.println("cot-selftest: " + failures + " FAILURES");
        System.exit(1);
    }

    private static void parseSide() throws Exception {
        // --- happy path ----------------------------------------------------
        CotEvent e = CotEvent.parse("<event version='2.0' uid='u-1' type='a-f-G-U-C'"
                + " how='m-g' time='2026-11-09T00:00:00.000Z'"
                + " start='2026-11-09T00:00:01.000Z' stale='2026-11-09T00:05:00.000Z'>"
                + "<point lat='37.5' lon='-122.25' hae='12.5' ce='3.0' le='4.0'/>"
                + "<detail><contact callsign='ALPHA'/></detail></event>");
        check("u-1".equals(e.uid) && "a-f-G-U-C".equals(e.type) && "m-g".equals(e.how),
                "event attributes read");
        check("2026-11-09T00:00:00.000Z".equals(e.time)
                        && "2026-11-09T00:00:01.000Z".equals(e.start)
                        && "2026-11-09T00:05:00.000Z".equals(e.stale),
                "time/start/stale read");
        check(e.lat == 37.5 && e.lon == -122.25 && e.hae == 12.5
                        && e.ce == 3.0 && e.le == 4.0,
                "point attributes read as numbers");
        check("ALPHA".equals(e.callsign), "detail/contact callsign read");

        e = CotEvent.parse("<event uid='u-2' type='t'><point lat='1' lon='2'/></event>");
        check(e.callsign == null, "no <detail>: callsign is null");
        check(Double.isNaN(e.hae) && Double.isNaN(e.ce) && Double.isNaN(e.le),
                "absent point attributes are NaN");

        e = CotEvent.parse("<event uid='u-3' type='t'><point lat='1' lon='2'/>"
                + "<detail><contact callsign=''/></detail></event>");
        check(e.callsign == null, "empty callsign is null");

        // --- direct children only (the audit's CotEvent fix) ----------------
        e = CotEvent.parse("<event uid='u-4' type='t'>"
                + "<detail><precisionlocation><point lat='9' lon='9'/></precisionlocation>"
                + "<link><contact callsign='WRONG'/></link>"
                + "<contact callsign='RIGHT'/></detail>"
                + "<point lat='37.75' lon='-122.5'/></event>");
        check(e.lat == 37.75 && e.lon == -122.5,
                "event's own <point> wins over one nested in <detail>");
        check("RIGHT".equals(e.callsign),
                "detail's own <contact> wins over one nested in <link>");

        String msg = parseMustFail("<event uid='u-5' type='t'><detail>"
                + "<precisionlocation><point lat='1' lon='2'/></precisionlocation>"
                + "</detail></event>");
        check(msg != null && msg.contains("has no <point>"),
                "only a nested <point>: refused, not borrowed: " + msg);

        e = CotEvent.parse("<event uid='u-6' type='t'><point lat='1' lon='2'/>"
                + "<contact callsign='NOT-IN-DETAIL'/></event>");
        check(e.callsign == null, "<contact> outside <detail> is not the callsign");

        // --- refusals --------------------------------------------------------
        msg = parseMustFail("<ping uid='x'/>");
        check(msg != null && msg.contains("not <event>"), "non-event root refused: " + msg);
        msg = parseMustFail("<event type='t'><point lat='1' lon='2'/></event>");
        check(msg != null && msg.contains("no uid"), "event without uid refused: " + msg);
        msg = parseMustFail("<event uid='u' type='t'><point lat='north' lon='2'/></event>");
        check(msg != null, "non-numeric lat refused: " + msg);
        msg = parseMustFail("<event uid='u' type='t'><point lat='1' lon='2'/>");
        check(msg != null, "truncated XML refused: " + msg);
        msg = parseMustFail("<?xml version='1.0'?><!DOCTYPE event ["
                + "<!ENTITY x SYSTEM 'file:///etc/passwd'>]>"
                + "<event uid='&x;' type='t'><point lat='1' lon='2'/></event>");
        check(msg != null && msg.contains("DOCTYPE"),
                "DOCTYPE (XXE) refused: " + msg);

        // An XML prolog is fine (commo prefixes one on the wire).
        e = CotEvent.parse("<?xml version='1.0' encoding='UTF-8'?>"
                + "<event uid='u-7' type='t'><point lat='1' lon='2'/></event>");
        check("u-7".equals(e.uid), "XML prolog accepted");
    }

    private static void escapeAndCoordinates() {
        check("&amp;&lt;&gt;&apos;&quot;".equals(CotXml.xmlEscape("&<>'\"")),
                "xmlEscape covers & < > ' \"");
        check("plain-CALLSIGN_1".equals(CotXml.xmlEscape("plain-CALLSIGN_1")),
                "xmlEscape leaves plain text alone");
        check("&amp;amp;".equals(CotXml.xmlEscape("&amp;")),
                "xmlEscape escapes an existing entity (no double-decode)");

        check("37.7749".equals(CotXml.coordinate("37.7749", CotXml.MAX_LAT)),
                "lat 37.7749 kept as given");
        check("-122.4194".equals(CotXml.coordinate("-122.4194", CotXml.MAX_LON)),
                "lon -122.4194 kept as given");
        check("90.0".equals(CotXml.coordinate("90", CotXml.MAX_LAT))
                        && "-180.0".equals(CotXml.coordinate("-180", CotXml.MAX_LON)),
                "range bounds are inclusive");
        check(CotXml.coordinate("90.0001", CotXml.MAX_LAT) == null
                        && CotXml.coordinate("180.5", CotXml.MAX_LON) == null,
                "out-of-range refused");
        check(CotXml.coordinate("NaN", CotXml.MAX_LAT) == null
                        && CotXml.coordinate("Infinity", CotXml.MAX_LON) == null,
                "NaN/Infinity refused");
        check(CotXml.coordinate("north", CotXml.MAX_LAT) == null
                        && CotXml.coordinate("", CotXml.MAX_LAT) == null,
                "non-numbers refused");
        check("45.0".equals(CotXml.coordinate("45f", CotXml.MAX_LAT))
                        && "10.0".equals(CotXml.coordinate("1e1", CotXml.MAX_LAT))
                        && "32.0".equals(CotXml.coordinate("0x1p5", CotXml.MAX_LAT)),
                "Java-only number forms normalized to plain decimal");
        String tiny = CotXml.coordinate("1e-5", CotXml.MAX_LAT);
        check(tiny != null && tiny.indexOf('E') < 0
                        && Double.parseDouble(tiny) == 1e-5,
                "tiny values stay plain decimal, never 1.0E-5: " + tiny);
    }

    private static void builders() throws Exception {
        String hostileUid = "u'1&<x>";
        String hostileCs = "O'BRIEN & \"CO\" <HQ>";

        String xml = CotXml.presence(hostileUid, hostileCs);
        CotEvent p = CotEvent.parse(xml);
        check(hostileUid.equals(p.uid), "presence uid round-trips through escaping");
        check(hostileCs.equals(p.callsign),
                "presence callsign round-trips through escaping");
        check("a-f-G-U-C".equals(p.type) && "m-g".equals(p.how),
                "presence is a self-SA (a-f-G-U-C, m-g)");
        check(p.time.matches(TIME_RE) && p.start.equals(p.time)
                        && p.stale.matches(TIME_RE) && p.stale.compareTo(p.time) > 0,
                "presence time/start/stale are CoT UTC times, stale after time");
        check(xml.contains("<__group name='Cyan' role='Team Member'/>"),
                "presence carries the __group OpenTAKServer needs to register");

        String m = CotXml.marker(hostileUid, hostileCs,
                CotXml.coordinate("37.7749", CotXml.MAX_LAT),
                CotXml.coordinate("-122.4194", CotXml.MAX_LON));
        CotEvent mk = CotEvent.parse(m);
        check((hostileUid + "-m1").equals(mk.uid), "marker uid is <uid>-m1");
        check((hostileCs + "-M1").equals(mk.callsign), "marker callsign is <cs>-M1");
        check(mk.lat == 37.7749 && mk.lon == -122.4194,
                "marker point comes from the event, not precisionlocation");
        check(mk.stale.compareTo(mk.time) > 0, "marker stale after time");
    }
}
