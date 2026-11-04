package mactak.headless;

import java.io.StringReader;
import java.util.Locale;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
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

        final NodeList pts = ev.getElementsByTagName("point");
        if (pts.getLength() == 0)
            throw new IllegalArgumentException("<event uid=" + ev.getAttribute("uid") + "> has no <point>");
        final Element pt = (Element) pts.item(0);

        String callsign = null;
        final NodeList contacts = ev.getElementsByTagName("contact");
        if (contacts.getLength() > 0) {
            final String cs = ((Element) contacts.item(0)).getAttribute("callsign");
            if (!cs.isEmpty()) callsign = cs;
        }

        return new CotEvent(
                ev.getAttribute("uid"), ev.getAttribute("type"), ev.getAttribute("how"),
                ev.getAttribute("time"), ev.getAttribute("start"), ev.getAttribute("stale"),
                num(pt, "lat"), num(pt, "lon"), num(pt, "hae"), num(pt, "ce"), num(pt, "le"),
                callsign);
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "uid=%s type=%s callsign=%s lat=%.6f lon=%.6f time=%s",
                uid, type, callsign == null ? "-" : callsign, lat, lon, time);
    }

    private static double num(Element e, String attr) {
        final String v = e.getAttribute(attr);
        return v.isEmpty() ? Double.NaN : Double.parseDouble(v);
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
