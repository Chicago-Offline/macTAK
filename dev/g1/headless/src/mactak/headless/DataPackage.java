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
 *       ({@code "host:port[:proto]"}: an IPv6 literal is bracketed
 *       ({@code "[::1]:8089"}) and stored unbracketed; the port is checked
 *       1–65535; the proto must be exactly {@code ssl}/{@code tls} (→
 *       {@code ssl}) or {@code tcp}/absent (→ plaintext) — anything else,
 *       {@code dtls} included, is refused, not guessed);</li>
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
 * against {@code certDir} canonically; every entry is size-capped while it is
 * copied (#31) — {@code preference.pref} at 16 MiB, each PKCS#12 at 64 MiB —
 * so an oversize (crafted or accidental) fails with a one-line message, exit
 * 2, instead of filling the disk or OOMing the heap, and a refused cert
 * leaves no partial file behind; the address line (#32) takes a bracketed
 * IPv6 literal (kept unbracketed — the form commo's addStreamingInterface
 * takes), range-checks the port to 1–65535 before commo can see it, matches
 * the proto token exactly, and refuses two PKCS#12s that share one basename —
 * the cert dir keeps one flat file per basename, so the two could not coexist;
 * {@link Resolved}'s string never prints the password.</p>
 *
 * <p>Pure JDK — no commo, no natives — so the importer is testable without
 * the engine: test/datapackage.sh.</p>
 */
public final class DataPackage {

    private DataPackage() {
    }

    /** Cap on preference.pref once decompressed: readAll holds it in memory. */
    private static final int MAX_PREF_BYTES = 16 * 1024 * 1024;

    /** Cap on one extracted PKCS#12: extract streams it straight to disk. */
    private static final int MAX_CERT_BYTES = 64 * 1024 * 1024;

    /** One {@code <preference>} block of preference.pref: name + its entries. */
    private static final class Pref {
        final String name;
        final Map<String, String> entries = new LinkedHashMap<String, String>();

        Pref(String name) {
            this.name = name;
        }
    }

    /** One parsed {@code addressN} value; see {@link #parseAddress}. */
    private static final class EndPoint {
        final String host;
        final int port;
        final boolean ssl;

        EndPoint(String host, int port, boolean ssl) {
            this.host = host;
            this.port = port;
            this.ssl = ssl;
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
            prefs = parsePref(readAll(zip, prefEntry, MAX_PREF_BYTES));

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
            } else if (p12Names.size() == 2
                    && baseName(p12Names.get(0))
                        .equalsIgnoreCase(baseName(p12Names.get(1)))) {
                // (#32) Basename hints cannot split a same-basename pair —
                // both entries classify alike (trusts 2/clients 0 or the
                // mirror), so the pair check above always fires for them.
                // Name the real problem instead of claiming a pair is
                // missing. Nothing was extracted: the cert dir keeps one
                // flat file per basename, so these two could never coexist
                // in it — refuse rather than let the second overwrite the
                // first.
                throw new IOException("two PKCS#12s share one basename and "
                        + "could not both live in the cert dir: " + p12Names);
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
            EndPoint ep = parseAddress(address);

            String callsign = null;
            for (Pref p : prefs) {
                String cs = p.entries.get("callsign");
                if (cs != null && !cs.isEmpty()) {
                    callsign = cs;
                    break;
                }
            }

            certPass = prefValue(prefs, "plugin_firecrypt", "Password");
            return new Resolved(ep.host, ep.port, ep.ssl, callsign,
                    certPath, caPath, certPass);
        }
    }

    /**
     * Parse {@code host:port[:proto]} (#32). An IPv6 literal is bracketed —
     * {@code "[::1]:8089:ssl"} — and stored unbracketed: commo's
     * addStreamingInterface takes host and port separately, and the brackets
     * are a host:port-package convention, not something an address lookup
     * accepts. The port is range-checked here so 0/99999 dies with a
     * one-line message instead of an obscure commo failure at connect, and
     * the proto token matches exactly: ssl/tls (any case) set {@code ssl},
     * tcp/absent mean plaintext, anything else — dtls included, which the
     * old {@code contains("tls")} test happily mislabeled ssl — is refused.
     */
    private static EndPoint parseAddress(String address) throws IOException {
        String host;
        String tail;                       // ":port" or ":port:proto"
        if (address.startsWith("[")) {
            int close = address.indexOf(']');
            if (close < 0)
                throw new IOException("cannot parse cot_streams address"
                        + " (no closing ]): " + address);
            host = address.substring(1, close);
            tail = address.substring(close + 1);
            if (host.isEmpty())
                throw new IOException("cannot parse cot_streams address"
                        + " (empty IPv6 literal): " + address);
        } else {
            // More colons than host:port:proto can hold means an unbracketed
            // IPv6 literal; say so instead of the old misleading "cannot
            // parse cot_streams port" a "[::1]:8089" used to get.
            int colons = 0;
            for (int i = 0; i < address.length(); i++)
                if (address.charAt(i) == ':')
                    colons++;
            if (colons > 2)
                throw new IOException("cannot parse cot_streams address: "
                        + address + " (an IPv6 literal needs brackets,"
                        + " e.g. [::1]:8089)");
            String[] parts = address.split(":", 3);
            if (parts.length < 2 || parts[0].isEmpty())
                throw new IOException("cannot parse cot_streams address: "
                        + address);
            host = parts[0];
            tail = ":" + parts[1] + (parts.length == 3 ? ":" + parts[2] : "");
        }
        if (!tail.startsWith(":"))
            throw new IOException("cannot parse cot_streams address"
                    + " (no port): " + address);
        String rest = tail.substring(1);   // port or port:proto
        int protoAt = rest.indexOf(':');
        String portPart = protoAt >= 0 ? rest.substring(0, protoAt) : rest;
        String proto = protoAt >= 0 ? rest.substring(protoAt + 1) : null;
        int port;
        try {
            port = Integer.parseInt(portPart);
        } catch (NumberFormatException e) {
            throw new IOException("cannot parse cot_streams port: " + address);
        }
        if (port < 1 || port > 65535)
            throw new IOException("cot_streams port out of range (1-65535): "
                    + port + " in " + address);
        boolean ssl;
        if (proto == null || proto.isEmpty()) {
            ssl = false;
        } else {
            String p = proto.toLowerCase(Locale.US);
            if (p.equals("ssl") || p.equals("tls"))
                ssl = true;
            else if (p.equals("tcp"))
                ssl = false;
            else
                throw new IOException("unsupported cot_streams proto '"
                        + proto + "' in " + address);
        }
        return new EndPoint(host, port, ssl);
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

    /**
     * Extract one entry into certDir: basename only, canonically checked.
     * Only p12s reach here today, and checkEntryName has already run on each
     * in the scan loop — if extraction ever widens past p12s, the name check
     * must move with it (#32).
     */
    private static File extract(ZipFile zip, ZipEntry entry, File certDir)
            throws IOException {
        String name = entry.getName();
        String base = baseName(name);
        File target = new File(certDir, base);
        if (!target.getCanonicalPath()
                .startsWith(certDir.getCanonicalPath() + File.separator))
            throw new IOException("refusing entry outside cert dir: " + name);
        try (InputStream in = zip.getInputStream(entry);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_CERT_BYTES)
                    throw new IOException(name + " exceeds "
                            + MAX_CERT_BYTES + " bytes");
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            // The cap (or a real write error) must not leave a partial
            // PKCS#12 in certDir. Best-effort delete, then let the message
            // travel: no deleteOnExit — the JVM outlives this by minutes.
            target.delete();
            throw e;
        }
        return target;
    }

    /** The basename of a zip entry name (either slash); nothing else survives. */
    private static String baseName(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash >= 0 ? name.substring(slash + 1) : name;
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

    /** Slurp an entry fully, refusing anything past limit (#31): an
     * oversized preference.pref would OOM the JVM mid-read, before the
     * clean-error path had a chance to run. */
    private static String readAll(ZipFile zip, ZipEntry entry, int limit)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > limit)
                    throw new IOException(entry.getName() + " exceeds "
                            + limit + " bytes");
                out.write(buf, 0, n);
            }
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