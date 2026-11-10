package mactak.headless;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.atakmap.database.CursorIface;
import com.atakmap.database.DatabaseIface;
import com.atakmap.database.impl.DatabaseImpl;

/**
 * Track persistence through the engine's own database stack
 * (Java → JNI glue → C++ Database2 → sqlite). Every received position is
 * appended; {@link #latest()} returns the newest fix per uid — the
 * in-memory "plot" of the G1 exit criterion.
 *
 * Methods are synchronized: {@code record} runs on commo's receive thread,
 * the exit summary on main.
 */
public final class TrackStore implements AutoCloseable {

    /** One uid's newest fix plus how many fixes the DB holds for it. */
    public static final class Track {
        public final CotEvent latest;
        public final int fixes;

        Track(CotEvent latest, int fixes) {
            this.latest = latest;
            this.fixes = fixes;
        }

        @Override
        public String toString() {
            return latest + " fixes=" + fixes;
        }
    }

    private final DatabaseIface db;
    private boolean closed;

    /** {@code path == null} opens a private temporary database (engine semantics). */
    public TrackStore(String path) {
        db = DatabaseImpl.openOrCreate(path);
        if (db == null) throw new IllegalStateException("DatabaseImpl.openOrCreate failed for " + path);
        db.execute("CREATE TABLE IF NOT EXISTS tracks ("
                + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " uid TEXT NOT NULL,"
                + " callsign TEXT,"
                + " type TEXT,"
                + " lat REAL, lon REAL, hae REAL,"
                + " time TEXT,"
                + " stale TEXT,"
                + " received_at INTEGER NOT NULL)", null);
        db.execute("CREATE INDEX IF NOT EXISTS tracks_uid ON tracks(uid, id)", null);
    }

    public synchronized void record(CotEvent e, long receivedAtMillis) {
        if (closed) return;   // a receive callback racing shutdown
        // Bind args are Strings only, so NULLIF turns '' into SQL NULL: an
        // absent callsign, and an unknown (NaN) hae. Bound as "NaN", sqlite
        // would keep the text in the REAL column and read it back as 0.0.
        db.execute("INSERT INTO tracks(uid, callsign, type, lat, lon, hae, time, stale, received_at)"
                + " VALUES (?, NULLIF(?, ''), ?, ?, ?, NULLIF(?, ''), ?, ?, ?)",
                new String[] {
                    e.uid, e.callsign == null ? "" : e.callsign, e.type,
                    Double.toString(e.lat), Double.toString(e.lon), real(e.hae),
                    e.time, e.stale, Long.toString(receivedAtMillis) });
    }

    /** A finite double as SQL text; NaN/infinite (unknown) as '', which binds NULL. */
    private static String real(double d) {
        return Double.isNaN(d) || Double.isInfinite(d) ? "" : Double.toString(d);
    }

    public synchronized int count() {
        final CursorIface c = db.query("SELECT COUNT(*) FROM tracks", null);
        try {
            return c.moveToNext() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /** Newest row per uid (with that uid's fix count), ordered by callsign/uid. */
    public synchronized List<Track> latest() {
        final List<Track> out = new ArrayList<Track>();
        final CursorIface c = db.query(
                "SELECT t.uid, t.callsign, t.type, t.lat, t.lon, t.hae, t.time, t.stale, m.n"
                + " FROM tracks t JOIN (SELECT uid, MAX(id) AS id, COUNT(*) AS n FROM tracks GROUP BY uid) m"
                + "   ON t.id = m.id"
                + " ORDER BY COALESCE(t.callsign, t.uid)", null);
        try {
            while (c.moveToNext()) {
                out.add(new Track(new CotEvent(
                        c.getString(0), c.getString(2), "", c.getString(6), c.getString(6), c.getString(7),
                        c.getDouble(3), c.getDouble(4), c.isNull(5) ? Double.NaN : c.getDouble(5),
                        Double.NaN, Double.NaN,
                        c.isNull(1) ? null : c.getString(1)), c.getInt(8)));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** The exit demo: one {@code headless: track …} line per uid, then a totals line. */
    public String summary(String label) {
        final List<Track> rows = latest();
        final StringBuilder sb = new StringBuilder();
        for (Track t : rows) sb.append("headless: track ").append(t).append('\n');
        sb.append(String.format(Locale.US, "headless: tracks uids=%d fixes=%d db=%s%n",
                rows.size(), count(), label));
        return sb.toString();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        db.close();
    }
}
