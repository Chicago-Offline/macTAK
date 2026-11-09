package mactak.headless;

import java.io.StringReader;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * Minimal Cursor-on-Target event, receive side: the {@code <event>} /
 * {@code <point>} attributes plus the {@code <detail><contact callsign>}
 * every TAK client relies on — what the tracks DB persists. The full
 * {@code com.atakmap.coremap.cot.event} model is Android-bound (Parcelable,
 * atak app module) and joins at a later rung.
 */
public final class CotEvent {

    public final String uid;
    public final String type;
    public final String how;
    public final String time;
    public final String start;
    public final String stale;
    public final double lat;
    public final double lon;
    public final double hae;
    public final double ce;
    public final double le;
    public final String callsign;   // may be null (no <contact> detail)

    public CotEvent(String uid, String type, String how, String time, String start,
            String stale, double lat, double lon, double hae, double ce, double le,
            String callsign) {
        this.uid = uid;
        this.type = type;
        this.how = how;
        this.time = time;
        this.start = start;
        this.stale = stale;
        this.lat = lat;
        this.lon = lon;
        this.hae = hae;
        this.ce = ce;
        this.le = le;
        this.callsign = callsign;
    }

    /** Parse one {@code <event>} document. Throws on malformed/non-CoT input. */
    public static CotEvent parse(String xml) throws Exception {
        final Document doc = secureBuilder().parse(new InputSource(new StringReader(xml)));
        final Element ev = doc.getDocumentElement();
        if (!"event".equals(ev.getTagName()))
            throw new IllegalArgumentException("root is <" + ev.getTagName() + ">, not <event>");
        if (ev.getAttribute("uid").isEmpty())
            throw new IllegalArgumentException("<event> has no uid");

        // Direct children only: <detail> may nest its own <point> (e.g.
        // <precisionlocation>) or <contact> elements (links, chat
        // recipients) that a document-wide search would pick up instead.
        final Element pt = child(ev, "point");
        if (pt == null)
            throw new IllegalArgumentException("<event uid=" + ev.getAttribute("uid") + "> has no <point>");

        String callsign = null;
        final Element detail = child(ev, "detail");
        final Element contact = detail == null ? null : child(detail, "contact");
        if (contact != null) {
            final String cs = contact.getAttribute("callsign");
            if (!cs.isEmpty()) callsign = cs;
        }

        return new CotEvent(
                ev.getAttribute("uid"), ev.getAttribute("type"), ev.getAttribute("how"),
                ev.getAttribute("time"), ev.getAttribute("start"), ev.getAttribute("stale"),
                coord(pt, "lat", 90.0), coord(pt, "lon", 180.0),
                optional(pt, "hae"), optional(pt, "ce"), optional(pt, "le"),
                callsign);
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "uid=%s type=%s callsign=%s lat=%.6f lon=%.6f time=%s",
                uid, type, callsign == null ? "-" : callsign, lat, lon, time);
    }

    /** The first direct child element of parent named tag, or null. */
    private static Element child(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && tag.equals(((Element) n).getTagName()))
                return (Element) n;
        }
        return null;
    }

    /**
     * A CoT decimal: optional sign, digits with an optional fraction, an
     * optional exponent. Double.parseDouble alone also takes Java-only forms
     * ("45f", "0x1p5", " 1 ", "Infinity") that no other CoT parser reads.
     */
    private static final Pattern DECIMAL =
            Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");

    /**
     * lat/lon: required, a finite CoT decimal within [-limit, limit]. A
     * point without them is not a position: persisted, it would read back
     * from the tracks DB as 0,0 (sqlite stores "NaN" as text in a REAL
     * column, and a numeric read of that text is 0.0).
     */
    private static double coord(Element pt, String attr, double limit) {
        final String v = pt.getAttribute(attr);
        if (v.isEmpty())
            throw new IllegalArgumentException("<point> has no " + attr);
        final double d = decimal(attr, v);
        if (Double.isInfinite(d) || Math.abs(d) > limit)
            throw new IllegalArgumentException("<point> " + attr + "=" + v + " is out of range");
        return d;
    }

    /** hae/ce/le: optional; absent or the literal "NaN" is unknown (NaN). */
    private static double optional(Element pt, String attr) {
        final String v = pt.getAttribute(attr);
        if (v.isEmpty() || "NaN".equals(v))
            return Double.NaN;
        final double d = decimal(attr, v);
        return Double.isInfinite(d) ? Double.NaN : d;
    }

    private static double decimal(String attr, String v) {
        if (!DECIMAL.matcher(v).matches())
            throw new IllegalArgumentException("<point> " + attr + "=" + v + " is not a number");
        return Double.parseDouble(v);
    }

    // Network input: no DTDs, no external entities (XXE hardening).
    private static DocumentBuilder secureBuilder() throws Exception {
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder();
    }
}
