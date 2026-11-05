package mactak.headless;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * G1.5 — ATAK data-package importer for atak-headless.
 *
 * <p>An ATAK data package (.dp, a zip) carries everything a client needs to
 * reach its server: {@code preference.pref} (an XML preferences file with the
 * {@code cot_streams} address, the {@code location_units} callsign and the
 * {@code plugin_firecrypt} cert entries) plus a {@code certs/} folder with a
 * PKCS#12 client/truststore pair. {@link #open} resolves the pieces
 * atak-headless needs:</p>
 *
 * <ul>
 *   <li>{@code host}, {@code port}, {@code ssl} — the first {@code addressN}
 *       entry of the first preference whose name contains "cot"
 *       ({@code "host:port[:proto]"}; a proto containing ssl/tls sets
 *       {@code ssl});</li>
 *   <li>{@code callsign} — the first {@code callsign} entry in any preference
 *       ({@code null}: the caller keeps its default);</li>
 *   <li>{@code certPath}, {@code caPath} — the two PKCS#12 files extracted to
 *       {@code certDir} (the one matching {@code ClientFile}/not trust-named,
 *       and the {@code CAFile}/trust-named one; {@code null} when the package
 *       ships no certs — a plaintext server);</li>
 *   <li>{@code certPass}/{@code caPass} — the firecrypt {@code Password}
 *       entry for both files ({@code null}: the caller's env/default rules
 *       apply).</li>
 * </ul>
 *
 * <p><b>Known shape, not yet verified against a real OpenTAKServer package</b>
 * (no real .dp existed on the machine when this landed): the parser is
 * deliberately liberal — {@code addressN}/{@code callsign} keys wherever they
 * appear, firecrypt entries by substring name — and the first live-package
 * run must confirm the key names before this replaces flag plumbing at a
 * demo. See dev/g1/NOTES.md, section G1.5.</p>
 *
 * <p><b>Hardening:</b> the XML parser runs with the same XXE posture as the
 * CoT side (DOCTYPE disallowed, external entities off); zip entry names never
 * reach {@link File} unchecked — traversal names ("..", absolute, backslash)
 * are refused outright (Zip-Slip) and extraction targets are re-verified
 * against {@code certDir} canonically; {@link Resolved}'s string never prints
 * the password.</p>
 *
 * <p>Pure JDK — no commo, no natives — so the importer is testable without
 * the engine: test/datapackage.sh.</p>
 */
public final class DataPackage {

    private DataPackage() {
    }

    /** One {@code <preference>} block of preference.pref: name + its entries. */
    private static final class Pref {
        final String name;
        final Map<String, String> entries = new LinkedHashMap<String, String>();

        Pref(String name) {
            this.name = name;
        }
    }

    /** What {@link #open} resolved from the package; see the class comment. */
    public static final class Resolved {
        public final String host;
        public final int port;
        public final boolean ssl;
        /** null: the package carries no callsign; the caller keeps its default. */
        public final String callsign;
        /** Extracted PKCS#12 paths; null pair: the package ships no certs. */
        public final String certPath;
        public final String caPath;
        /** null: the package names no password; the caller's env/default rules apply. */
        public final String certPass;
        /** Mirrors certPass — a data package ships one password for both files. */
        public final String caPass;

        Resolved(String host, int port, boolean ssl, String callsign,
                 String certPath, String caPath, String certPass) {
            this.host = host;
            this.port = port;
            this.ssl = ssl;
            this.callsign = callsign;
            this.certPath = certPath;
            this.caPath = caPath;
            this.certPass = certPass;
            this.caPass = certPass;
        }

        /** Machine-greppable, like every other headless: line; never the password. */
        @Override
        public String toString() {
            return "host=" + host + " port=" + port + (ssl ? " ssl" : " tcp")
                    + " callsign=" + (callsign != null ? callsign : "none")
                    + " certs=" + (certPath != null
                        ? new File(certPath).getName() + "+" + new File(caPath).getName()
                        : "none")
                    + " certPass=" + (certPass != null ? "set" : "none");
        }
    }

    /**
     * Resolve dp (an ATAK data package) into a Resolved, extracting the
     * PKCS#12 pair into certDir (created if missing).
     *
     * @throws IOException with a user-ready message for a package that is
     *         missing pieces, malformed, or hostile (see the class comment)
     */
    public static Resolved open(File dp, File certDir) throws IOException {
        if (!dp.isFile())
            throw new IOException("data package is not a file: " + dp);
        try {
            if (!certDir.isDirectory() && !certDir.mkdirs() && !certDir.isDirectory())
                throw new IOException("cannot create cert dir: " + certDir);
        } catch (SecurityException e) {
            throw new IOException("cannot create cert dir: " + certDir
                    + ": " + e.getMessage());
        }

        final List<Pref> prefs;
        final String certPath;
        final String caPath;
        final String certPass;

        try (ZipFile zip = new ZipFile(dp)) {
            ZipEntry prefEntry = zip.getEntry("preference.pref");
            if (prefEntry == null)
                throw new IOException("no preference.pref in " + dp.getName());
            prefs = parsePref(readAll(zip, prefEntry));

            // --- the PKCS#12 pair -----------------------------------------
            // firecrypt's ClientFile/CAFile name them explicitly when present;
            // otherwise: the trust-named file is the truststore, the other the
            // client. Anything else (extra p12s, only one of the pair) fails.
            String clientHint = prefValue(prefs, "plugin_firecrypt", "ClientFile");
            String trustHint = prefValue(prefs, "plugin_firecrypt", "CAFile");
            String clientName = null;
            String trustName = null;
            int clients = 0;
            int trusts = 0;
            List<String> p12Names = new ArrayList<String>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (e.isDirectory() || name.startsWith("META-INF/"))
                    continue;
                String lower = name.toLowerCase(Locale.US);
                if (!lower.endsWith(".p12") && !lower.endsWith(".pfx"))
                    continue;
                p12Names.add(name);
                checkEntryName(name);
                if (matchesHint(name, trustHint) || lower.contains("trust")) {
                    trustName = name;
                    trusts++;
                } else {
                    clientName = name;
                    clients++;
                }
            }
            if (p12Names.isEmpty()) {
                certPath = null;      // a plaintext-server package ships no certs
                caPath = null;
            } else if (clients == 1 && trusts == 1) {
                certPath = extract(zip, zip.getEntry(clientName), certDir).getPath();
                caPath = extract(zip, zip.getEntry(trustName), certDir).getPath();
            } else {
                throw new IOException("expected a client + truststore PKCS#12 "
                        + "pair, found: " + p12Names);
            }

            // --- the connection -------------------------------------------
            Pref cot = null;
            for (Pref p : prefs) {
                if (p.name != null
                        && p.name.toLowerCase(Locale.US).contains("cot")) {
                    cot = p;
                    break;
                }
            }
            if (cot == null)
                throw new IOException("no cot_streams-like preference in "
                        + dp.getName());
            String address = indexedValue(cot.entries, "address");
            if (address == null || address.isEmpty())
                throw new IOException(cot.name + " carries no addressN entry");
            String[] parts = address.split(":", 3);
            if (parts.length < 2 || parts[0].isEmpty())
                throw new IOException("cannot parse cot_streams address: "
                        + address);
            int port;
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IOException("cannot parse cot_streams port: "
                        + address);
            }
            boolean ssl = parts.length == 3
                    && (parts[2].toLowerCase(Locale.US).contains("ssl")
                        || parts[2].toLowerCase(Locale.US).contains("tls"));

            String callsign = null;
            for (Pref p : prefs) {
                String cs = p.entries.get("callsign");
                if (cs != null && !cs.isEmpty()) {
                    callsign = cs;
                    break;
                }
            }

            certPass = prefValue(prefs, "plugin_firecrypt", "Password");
            return new Resolved(parts[0], port, ssl, callsign,
                    certPath, caPath, certPass);
        }
    }

    /** Refuse path-traversal entry names (Zip-Slip) before anything touches disk. */
    private static void checkEntryName(String name) throws IOException {
        if (name.startsWith("/") || name.contains("\\") || name.indexOf('\0') >= 0)
            throw new IOException("refusing suspicious zip entry: " + name);
        for (String seg : name.split("/")) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals(".."))
                throw new IOException("refusing suspicious zip entry: " + name);
        }
    }

    /** Extract one entry into certDir: basename only, canonically checked. */
    private static File extract(ZipFile zip, ZipEntry entry, File certDir)
            throws IOException {
        String name = entry.getName();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String base = slash >= 0 ? name.substring(slash + 1) : name;
        File target = new File(certDir, base);
        if (!target.getCanonicalPath()
                .startsWith(certDir.getCanonicalPath() + File.separator))
            throw new IOException("refusing entry outside cert dir: " + name);
        try (InputStream in = zip.getInputStream(entry);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
        }
        return target;
    }

    /**
     * Parse preference.pref into preference blocks. XXE-hardened: DOCTYPE
     * disallowed, external entities never resolved — a hostile package gets
     * an error, not a file read.
     */
    private static List<Pref> parsePref(String xml) throws IOException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder;
        try {
            // Same XXE posture as the CoT side: never resolve external entities.
            dbf.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature(
                    "http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            builder = dbf.newDocumentBuilder();
            // Quiet, deterministic: Xerces' default handler prints
            // "[Fatal Error] …" lines to stderr before our catch wraps the
            // failure — rethrow instead, and the message is ours alone.
            builder.setErrorHandler(new ErrorHandler() {
                @Override public void warning(SAXParseException e) {
                    // recoverable: keep parsing silently
                }
                @Override public void error(SAXParseException e) throws SAXException {
                    throw e;
                }
                @Override public void fatalError(SAXParseException e) throws SAXException {
                    throw e;
                }
            });
        } catch (ParserConfigurationException e) {
            throw new IOException("XML parser setup failed: " + e.getMessage());
        }
        Document doc;
        try {
            doc = builder.parse(new InputSource(new StringReader(xml)));
        } catch (SAXException e) {
            throw new IOException("preference.pref is not valid XML: "
                    + e.getMessage());
        } catch (IOException e) {
            throw new IOException("cannot read preference.pref: " + e.getMessage());
        }

        List<Pref> prefs = new ArrayList<Pref>();
        NodeList prefNodes = doc.getElementsByTagName("preference");
        for (int i = 0; i < prefNodes.getLength(); i++) {
            Node n = prefNodes.item(i);
            if (!(n instanceof Element))
                continue;
            Element prefEl = (Element) n;
            Pref pref = new Pref(prefEl.getAttribute("name"));
            NodeList entryNodes = prefEl.getElementsByTagName("entry");
            for (int j = 0; j < entryNodes.getLength(); j++) {
                Node en = entryNodes.item(j);
                if (!(en instanceof Element))
                    continue;
                Element entryEl = (Element) en;
                String key = entryEl.getAttribute("key");
                if (key.isEmpty())
                    continue;
                pref.entries.put(key, entryEl.getTextContent().trim());
            }
            prefs.add(pref);
        }
        if (prefs.isEmpty())
            throw new IOException("preference.pref has no <preference> blocks");
        return prefs;
    }

    private static String readAll(ZipFile zip, ZipEntry entry) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Value of key in the first preference whose name contains namePart. */
    private static String prefValue(List<Pref> prefs, String namePart, String key) {
        String want = namePart.toLowerCase(Locale.US);
        for (Pref p : prefs) {
            if (p.name != null
                    && p.name.toLowerCase(Locale.US).contains(want)) {
                String v = p.entries.get(key);
                if (v != null && !v.isEmpty())
                    return v;
            }
        }
        return null;
    }

    /** Entry {@code base0} when present, else the lowest-numbered {@code baseN}. */
    private static String indexedValue(Map<String, String> entries, String base) {
        String exact = entries.get(base + "0");
        if (exact != null && !exact.isEmpty())
            return exact;
        Pattern p = Pattern.compile(Pattern.quote(base) + "(\\d+)");
        int best = Integer.MAX_VALUE;
        String value = null;
        for (Map.Entry<String, String> e : entries.entrySet()) {
            Matcher m = p.matcher(e.getKey());
            if (m.matches()) {
                int n = Integer.parseInt(m.group(1));
                if (n < best) {
                    best = n;
                    value = e.getValue();
                }
            }
        }
        return value;
    }

    /** Does entry name match a firecrypt hint (basename, case-insensitive)? */
    private static boolean matchesHint(String name, String hint) {
        if (hint == null || hint.isEmpty())
            return false;
        String h = hint.replace('\\', '/');
        int slash = h.lastIndexOf('/');
        h = slash >= 0 ? h.substring(slash + 1) : h;
        int nSlash = name.lastIndexOf('/');
        String n = nSlash >= 0 ? name.substring(nSlash + 1) : name;
        return n.equalsIgnoreCase(h);
    }
}