import android.net.Uri;
import java.io.File;

/**
 * Assertions for the macOS {@code android.net.Uri} stand-in, run by
 * test/uri.sh (repo idiom: shell-orchestrated javac+java, no JUnit, no
 * natives). The engine opens its databases through
 * {@code Uri.fromFile(file).getPath()}, so a path must survive that round
 * trip whatever characters it holds; the expected strings are Android's.
 */
public final class UriSelfTest {

    private static int failures = 0;

    private UriSelfTest() {
    }

    private static void check(boolean cond, String what) {
        if (cond)
            System.out.println("PASS: " + what);
        else {
            System.out.println("FAIL: " + what);
            failures++;
        }
    }

    private static void checkEquals(String expected, String actual, String what) {
        check(expected == null ? actual == null : expected.equals(actual),
                what + " (expected [" + expected + "], got [" + actual + "])");
    }

    /** fromFile(path) must hand the same path back and invent no query or fragment. */
    private static void fileRoundTrip(String path) {
        final Uri u = Uri.fromFile(new File(path));
        checkEquals(path, u.getPath(), "fromFile round trip: " + path);
        checkEquals(null, u.getFragment(), "fromFile has no fragment: " + path);
        checkEquals(null, u.getQuery(), "fromFile has no query: " + path);
        checkEquals(path, Uri.parse(u.toString()).getPath(), "fromFile -> toString -> parse: " + path);
    }

    public static void main(String[] args) {
        // --- fromFile: characters that are URI syntax must not cut the path ---
        fileRoundTrip("/Users/m/atak/tracks.sqlite");
        fileRoundTrip("/Users/m/My Maps/layers.sqlite");
        fileRoundTrip("/Users/m/Maps #2/tracks.sqlite");
        fileRoundTrip("/Users/m/what?/a.kml");
        fileRoundTrip("/Users/m/100%25 done/a.kml");
        fileRoundTrip("/Users/m/a+b/c&d=e.sqlite");
        fileRoundTrip("/Users/m/Karten/Zürich — 東京.gpkg");

        checkEquals("file", Uri.fromFile(new File("/a/b")).getScheme(), "fromFile scheme");
        checkEquals("file:///Users/m/Maps%20%232/tracks.sqlite",
                Uri.fromFile(new File("/Users/m/Maps #2/tracks.sqlite")).toString(),
                "fromFile encodes the path, slashes kept");

        // --- encode: Android's unreserved set, UTF-8 percent-escapes ---
        checkEquals("a%20b!~'()*-_.", Uri.encode("a b!~'()*-_."), "encode leaves Android's unreserved characters");
        checkEquals("%2Fa%2Fb", Uri.encode("/a/b"), "encode escapes '/' by default");
        checkEquals("/a/b%23c", Uri.encode("/a/b#c", "/"), "encode keeps the allowed characters");
        checkEquals("Z%C3%BCrich", Uri.encode("Zürich"), "encode writes UTF-8");
        checkEquals("%F0%9F%97%BA", Uri.encode("🗺"), "encode handles a surrogate pair");
        checkEquals("a+b c", Uri.decode("a+b%20c"), "decode keeps '+', unescapes %20");

        // --- parse: what already worked must keep working ---
        checkEquals("/plain/path.sqlite", Uri.parse("/plain/path.sqlite").getPath(), "a bare path parses to itself");
        checkEquals(null, Uri.parse("/plain/path.sqlite").getScheme(), "a bare path has no scheme");
        final Uri http = Uri.parse("https://user@tiles.example.com:8443/a/b.png?z=3&name=x%20y#frag");
        checkEquals("https", http.getScheme(), "scheme");
        checkEquals("tiles.example.com", http.getHost(), "host");
        check(http.getPort() == 8443, "port");
        checkEquals("/a/b.png", http.getPath(), "path");
        checkEquals("3", http.getQueryParameter("z"), "query parameter");
        checkEquals("x y", http.getQueryParameter("name"), "query parameter, %20 decoded");
        checkEquals("frag", http.getFragment(), "fragment");

        // --- getQueryParameter: '+' is a space in a query value, as on Android ---
        final Uri q = Uri.parse("https://tiles.example.com/wms?layers=My+Layer&crs=EPSG%3A4326&plus=a%2Bb&empty=&flag");
        checkEquals("My Layer", q.getQueryParameter("layers"), "query value: '+' decodes to a space");
        checkEquals("a+b", q.getQueryParameter("plus"), "query value: %2B stays a literal '+'");
        checkEquals("EPSG:4326", q.getQueryParameter("crs"), "query value: %3A decoded");
        checkEquals("", q.getQueryParameter("empty"), "query value: empty");
        checkEquals("", q.getQueryParameter("flag"), "query flag without '=' is the empty string");
        checkEquals(null, q.getQueryParameter("absent"), "absent query parameter is null");
        checkEquals("layers=My+Layer&crs=EPSG:4326&plus=a+b&empty=&flag", q.getQuery(),
                "getQuery keeps '+' (only parameter values convert it)");
        checkEquals("b.png", http.getLastPathSegment(), "last path segment");

        if (failures > 0) {
            System.out.println("uri-canary: FAIL (" + failures + " check(s))");
            System.exit(1);
        }
        System.out.println("uri-canary: PASS");
    }
}
