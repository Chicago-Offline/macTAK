package android.net;

import java.io.File;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * macOS runtime for the {@code android.net.Uri} API the engine uses
 * (parse/fromFile/getScheme/getPath/getHost/getQueryParameter/getFragment/
 * decode). Lenient like Android's StringUri: a bare path parses to a Uri
 * with no scheme whose path is the input.
 */
public final class Uri implements Comparable<Uri> {
    private final String uriString;
    private String scheme, authority, path, query, fragment;

    private Uri(String s) {
        uriString = s;
        parseInto(s);
    }

    private void parseInto(String s) {
        int i = 0, n = s.length();
        int colon = s.indexOf(':');
        int slash = s.indexOf('/');
        if (colon > 0 && (slash < 0 || colon < slash)) {
            scheme = s.substring(0, colon);
            i = colon + 1;
        }
        int hash = s.indexOf('#', i);
        if (hash >= 0) { fragment = s.substring(hash + 1); n = hash; }
        int q = s.indexOf('?', i);
        if (q >= 0 && q < n) { query = s.substring(q + 1, n); n = q; }
        String rest = s.substring(i, n);
        if (rest.startsWith("//")) {
            int end = rest.indexOf('/', 2);
            authority = end < 0 ? rest.substring(2) : rest.substring(2, end);
            path = end < 0 ? "" : rest.substring(end);
        } else {
            path = rest;
        }
    }

    public static Uri parse(String uriString) { return new Uri(uriString); }

    public static Uri fromFile(File file) {
        if (file == null) throw new NullPointerException("file");
        return new Uri("file://" + file.getAbsolutePath());
    }

    public static Uri fromParts(String scheme, String ssp, String fragment) {
        return new Uri(scheme + ":" + ssp + (fragment != null ? "#" + fragment : ""));
    }

    public String getScheme() { return scheme; }
    public boolean isAbsolute() { return scheme != null; }
    public boolean isRelative() { return scheme == null; }
    public boolean isHierarchical() { return scheme == null || uriString.startsWith(scheme + ":/"); }
    public boolean isOpaque() { return !isHierarchical(); }
    public String getEncodedPath() { return path; }
    public String getPath() { return path == null ? null : decode(path); }
    public String getEncodedAuthority() { return authority; }
    public String getAuthority() { return authority == null ? null : decode(authority); }

    public String getHost() {
        if (authority == null) return null;
        String a = authority;
        int at = a.lastIndexOf('@');
        if (at >= 0) a = a.substring(at + 1);
        int colon = a.indexOf(':');
        return decode(colon >= 0 ? a.substring(0, colon) : a);
    }

    public int getPort() {
        if (authority == null) return -1;
        int colon = authority.lastIndexOf(':');
        if (colon < 0 || colon < authority.lastIndexOf('@')) return -1;
        try { return Integer.parseInt(authority.substring(colon + 1)); } catch (NumberFormatException e) { return -1; }
    }

    public String getEncodedQuery() { return query; }
    public String getQuery() { return query == null ? null : decode(query); }
    public String getEncodedFragment() { return fragment; }
    public String getFragment() { return fragment == null ? null : decode(fragment); }

    public String getSchemeSpecificPart() {
        return scheme == null ? uriString : uriString.substring(scheme.length() + 1, fragment == null ? uriString.length() : uriString.lastIndexOf('#'));
    }

    public String getLastPathSegment() {
        String p = getPath();
        if (p == null || p.isEmpty()) return null;
        int end = p.endsWith("/") ? p.length() - 1 : p.length();
        int start = p.lastIndexOf('/', end - 1);
        return p.substring(start + 1, end);
    }

    public String getQueryParameter(String key) {
        if (query == null) return null;
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            String k = eq < 0 ? kv : kv.substring(0, eq);
            if (decode(k).equals(key)) return eq < 0 ? "" : decode(kv.substring(eq + 1));
        }
        return null;
    }

    public java.util.Set<String> getQueryParameterNames() {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        if (query != null) for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            names.add(decode(eq < 0 ? kv : kv.substring(0, eq)));
        }
        return names;
    }

    public Builder buildUpon() { return new Builder(this); }

    public static String decode(String s) {
        if (s == null) return null;
        try { return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8"); } catch (Exception e) { return s; }
    }

    public static String encode(String s) { return encode(s, null); }

    public static String encode(String s, String allow) {
        if (s == null) return null;
        try {
            String enc = URLEncoder.encode(s, "UTF-8").replace("+", "%20");
            if (allow != null) for (char c : allow.toCharArray()) enc = enc.replace(URLEncoder.encode(String.valueOf(c), "UTF-8"), String.valueOf(c));
            return enc;
        } catch (Exception e) { return s; }
    }

    @Override public String toString() { return uriString; }
    @Override public int compareTo(Uri o) { return uriString.compareTo(o.uriString); }
    @Override public boolean equals(Object o) { return o instanceof Uri && ((Uri) o).uriString.equals(uriString); }
    @Override public int hashCode() { return uriString.hashCode(); }

    public static final class Builder {
        private String scheme, authority, path, fragment;
        private final Map<String, String> params = new LinkedHashMap<>();
        private String rawQuery;

        public Builder() { }

        Builder(Uri u) { scheme = u.scheme; authority = u.authority; path = u.path; rawQuery = u.query; fragment = u.fragment; }

        public Builder scheme(String s) { scheme = s; return this; }
        public Builder authority(String a) { authority = a; return this; }
        public Builder encodedAuthority(String a) { authority = a; return this; }
        public Builder path(String p) { path = p; return this; }
        public Builder encodedPath(String p) { path = p; return this; }
        public Builder appendPath(String seg) { path = (path == null ? "" : path) + "/" + encode(seg); return this; }
        public Builder appendEncodedPath(String seg) { path = (path == null ? "" : path) + "/" + seg; return this; }
        public Builder appendQueryParameter(String k, String v) { params.put(encode(k), encode(v)); return this; }
        public Builder encodedQuery(String q) { rawQuery = q; return this; }
        public Builder fragment(String f) { fragment = f; return this; }

        public Uri build() {
            StringBuilder sb = new StringBuilder();
            if (scheme != null) sb.append(scheme).append(':');
            if (authority != null) sb.append("//").append(authority);
            if (path != null) sb.append(path);
            StringBuilder q = new StringBuilder(rawQuery == null ? "" : rawQuery);
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (q.length() > 0) q.append('&');
                q.append(e.getKey()).append('=').append(e.getValue());
            }
            if (q.length() > 0) sb.append('?').append(q);
            if (fragment != null) sb.append('#').append(fragment);
            return new Uri(sb.toString());
        }
    }
}
