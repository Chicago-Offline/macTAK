package mactak.headless;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Assertions for the G1.5 data-package importer, run by test/datapackage.sh
 * (repo idiom: shell-orchestrated javac+java, no JUnit, no natives).
 *
 * <p>The happy-path fixture comes from test/fixtures/make-datapackage.sh via
 * argv (real self-signed PKCS#12s, realistic preference.pref); every
 * negative case builds its own hostile package in a temp dir with
 * {@link ZipOutputStream}.</p>
 */
public final class DataPackageSelfTest {

    private static int failures = 0;

    private DataPackageSelfTest() {
    }

    private static void check(boolean cond, String what) {
        if (cond)
            System.out.println("PASS: " + what);
        else {
            System.out.println("FAIL: " + what);
            failures++;
        }
    }

    /** Resolve expecting failure; returns the message, or null if it didn't fail. */
    private static String resolveMustFail(File dp, File dir) {
        try {
            DataPackage.open(dp, dir);
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    private static File writeZip(File out, String prefXml,
                                 String[] names, byte[][] blobs) throws IOException {
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out));
        try {
            if (prefXml != null) {
                zos.putNextEntry(new ZipEntry("preference.pref"));
                zos.write(prefXml.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            for (int i = 0; i < names.length; i++) {
                zos.putNextEntry(new ZipEntry(names[i]));
                zos.write(blobs[i]);
                zos.closeEntry();
            }
        } finally {
            zos.close();
        }
        return out;
    }

    private static final String VALID_PREF =
            "<preferences>"
            + "<preference version=\"1\" name=\"cot_streams\">"
            + "<entry key=\"count\" class=\"class java.lang.Integer\">1</entry>"
            + "<entry key=\"address0\" class=\"class java.lang.String\">"
            + "dp.example.test:8089:ssl</entry>"
            + "</preference>"
            + "<preference version=\"1\" name=\"location_units\">"
            + "<entry key=\"callsign\" class=\"class java.lang.String\">X-UNIT</entry>"
            + "</preference>"
            + "<preference version=\"1\" name=\"plugin_firecrypt\">"
            + "<entry key=\"CAFile\" class=\"class java.lang.String\">certs/truststore.p12</entry>"
            + "<entry key=\"ClientFile\" class=\"class java.lang.String\">certs/client.p12</entry>"
            + "<entry key=\"Password\" class=\"class java.lang.String\">pw-123</entry>"
            + "</preference>"
            + "</preferences>";

    public static void main(String[] args) throws Exception {
        File fixture = new File(args[0]);
        String expectCallsign = args.length > 1 ? args[1] : null;
        File dir = Files.createTempDirectory("dpselftest-happy").toFile();
        File work = Files.createTempDirectory("dpselftest-neg").toFile();
        boolean ok;
        try {
            ok = run(fixture, expectCallsign, dir, work);
        } finally {
            // deleteOnExit is a silent no-op on a non-empty directory
            // (File.delete only removes empty ones), so both roots — and the
            // extracted PKCS#12s inside — survived every run (#30). Delete
            // the trees for real, whatever the outcome. The nonzero exit for
            // failures happens in main, AFTER this finally: System.exit
            // would skip it and reintroduce the leak on failing runs.
            deleteTree(dir);
            deleteTree(work);
        }
        if (!ok)
            System.exit(1);
    }

    /** Every assertion; main owns the temp-dir lifecycle (see its finally). */
    private static boolean run(File fixture, String expectCallsign,
                               File dir, File work) throws Exception {
        // --- happy path: the openssl-built fixture ---------------------
        DataPackage.Resolved r = DataPackage.open(fixture, dir);
        check("dp.example.test".equals(r.host), "host from cot_streams address0");
        check(r.port == 8089, "port 8089 parsed");
        check(r.ssl, "address0 proto ssl");
        check(expectCallsign == null || expectCallsign.equals(r.callsign),
                "callsign from location_units (" + r.callsign + ")");
        check(r.certPath != null && new File(r.certPath).isFile()
                        && new File(r.certPath).length() > 0
                        && new File(r.certPath).getName().equals("client.p12"),
                "client PKCS#12 extracted");
        check(r.caPath != null && new File(r.caPath).isFile()
                        && new File(r.caPath).length() > 0
                        && new File(r.caPath).getName().equals("truststore.p12"),
                "truststore PKCS#12 extracted");
        check("dp-cert-pass".equals(r.certPass),
                "firecrypt Password becomes certPass");
        check(r.certPass != null && r.certPass.equals(r.caPass),
                "caPass mirrors certPass");
        check(r.toString().contains("certPass=set")
                        && !r.toString().contains("dp-cert-pass"),
                "Resolved.toString never prints the password");

        // --- hostile/malformed packages ---------------------------------
        byte[] blob = "not-a-real-p12".getBytes(StandardCharsets.UTF_8);

        File slip = writeZip(new File(work, "slip.dp"), VALID_PREF,
                new String[] {"certs/../../evil.p12"}, new byte[][] {blob});
        String msg = resolveMustFail(slip, new File(work, "d-slip"));
        check(msg != null && msg.contains("refusing"),
                "zip-slip entry refused: " + msg);

        File abs = writeZip(new File(work, "abs.dp"), VALID_PREF,
                new String[] {"/abs/evil.p12"}, new byte[][] {blob});
        msg = resolveMustFail(abs, new File(work, "d-abs"));
        check(msg != null && msg.contains("refusing"),
                "absolute entry name refused: " + msg);

        File noPref = writeZip(new File(work, "nopref.dp"), null,
                new String[] {"certs/client.p12"}, new byte[][] {blob});
        msg = resolveMustFail(noPref, new File(work, "d-nopref"));
        check(msg != null && msg.contains("preference.pref"),
                "missing preference.pref rejected: " + msg);

        File badXml = writeZip(new File(work, "badxml.dp"),
                "<preferences><preference", new String[0], new byte[0][]);
        msg = resolveMustFail(badXml, new File(work, "d-badxml"));
        check(msg != null && msg.contains("not valid XML"),
                "malformed preference.pref rejected: " + msg);

        String xxe = "<!DOCTYPE preferences [<!ENTITY xxe SYSTEM "
                + "\"file:///etc/passwd\">]>"
                + "<preferences><preference name=\"cot_streams\">"
                + "<entry key=\"address0\">&xxe;:8089</entry>"
                + "</preference></preferences>";
        File xxeDp = writeZip(new File(work, "xxe.dp"), xxe,
                new String[0], new byte[0][]);
        msg = resolveMustFail(xxeDp, new File(work, "d-xxe"));
        check(msg != null && (msg.contains("XML") || msg.contains("parser")),
                "DOCTYPE/external entity rejected: " + msg);

        File extra = writeZip(new File(work, "extra.dp"), VALID_PREF,
                new String[] {"certs/client.p12", "certs/truststore.p12",
                              "certs/other.p12"},
                new byte[][] {blob, blob, blob});
        msg = resolveMustFail(extra, new File(work, "d-extra"));
        check(msg != null && msg.contains("PKCS#12 pair"),
                "unmatched extra p12 rejected: " + msg);

        // --- plaintext variant: no certs, address without proto ---------
        String plain = "<preferences>"
                + "<preference name=\"cot_streams\">"
                + "<entry key=\"address0\">plain.example.test:8087</entry>"
                + "</preference></preferences>";
        File plainDp = writeZip(new File(work, "plain.dp"), plain,
                new String[0], new byte[0][]);
        DataPackage.Resolved p = DataPackage.open(plainDp,
                new File(work, "d-plain"));
        check("plain.example.test".equals(p.host) && p.port == 8087 && !p.ssl,
                "plaintext address resolved");
        check(p.certPath == null && p.caPath == null,
                "plaintext dp yields no certs");
        check(p.certPass == null, "plaintext dp yields no certPass");

        if (failures == 0) {
            System.out.println("datapackage-selftest: ALL PASS");
            return true;
        }
        System.out.println("datapackage-selftest: " + failures + " FAILURES");
        return false;
    }

    /**
     * Files first, then the dir — {@link File#delete} skips non-empty
     * directories, which is exactly what bit #30. Best-effort: like any
     * temp cleanup it must never mask the run's result by throwing.
     */
    private static void deleteTree(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null)
            for (File kid : kids)
                deleteTree(kid);
        dir.delete();
    }
}