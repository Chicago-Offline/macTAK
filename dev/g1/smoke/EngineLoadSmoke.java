import java.io.File;

import com.atakmap.database.CursorIface;
import com.atakmap.database.DatabaseIface;
import com.atakmap.database.impl.DatabaseImpl;

/**
 * G1 Milestone 1 smoke: the takkernel engine loads IN-PROCESS from a real
 * JVM and does real native work — no GL context, no Android.
 *
 * System.load(libtakengine.dylib)  → core engine, no JNI exports (G0)
 * System.load(libtakenginejni.dylib) → JNI glue, Java_* exports + JNI_OnLoad
 *   (its classloader setup FindClass'es com.atakmap.map.EngineLibrary —
 *   resolves from the engine jar on this classpath; its <clinit> swallows
 *   its own native-load failure, which is fine: the libs are already loaded)
 * DatabaseImpl.openOrCreate(tmp)   → Java → JNI glue → C++ Database2 →
 *   bundled sqlite → CREATE/INSERT/SELECT → back to Java.
 *
 * Success = prints PASS with 42, exit code 0.
 */
public final class EngineLoadSmoke {

    public static void main(final String[] args) throws Exception {
        final String coreLib = args[0];
        final String jniLib = args[1];

        System.out.println("[smoke] java " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vm.name") + ")");

        System.load(coreLib);
        System.out.println("[smoke] libtakengine.dylib loaded (core, no JNI exports)");

        System.load(jniLib);
        System.out.println("[smoke] libtakenginejni.dylib loaded (JNI_OnLoad ran)");

        final File dbFile = File.createTempFile("g1-smoke", ".db");
        if (!dbFile.delete()) // openOrCreate wants to create it fresh
            throw new IllegalStateException("cannot clear " + dbFile);

        DatabaseIface db = null;
        CursorIface cur = null;
        try {
            db = DatabaseImpl.openOrCreate(dbFile.getAbsolutePath());
            System.out.println("[smoke] DatabaseImpl.openOrCreate ok (JNI -> C++ Database2 -> sqlite)");

            db.execute("CREATE TABLE smoke(v INTEGER)", null);
            db.execute("INSERT INTO smoke VALUES (41)", null);

            cur = db.query("SELECT v+1 AS r FROM smoke", null);
            if (!cur.moveToNext())
                throw new IllegalStateException("query returned no rows");
            final int r = cur.getInt(0);
            System.out.println("[smoke] SELECT 41+1 through the JNI stack = " + r);

            if (r != 42)
                throw new IllegalStateException("expected 42, got " + r);

            System.out.println("[smoke] PASS: engine loads and runs in-process from the JVM");
        } catch (Throwable t) {
            System.out.println("[smoke] FAIL");
            t.printStackTrace(System.out);
            System.exit(1);
        } finally {
            if (cur != null) cur.close();
            if (db != null) db.close();
            dbFile.delete();
        }
    }
}
