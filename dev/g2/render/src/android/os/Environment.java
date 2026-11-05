package android.os;

import java.io.File;

/**
 * macOS runtime for the {@code android.os.Environment} calls the engine
 * makes (the transplant's bodies are "Stub!"). "External storage" maps to
 * {@code $MACTAK_HOME} if set, else {@code ~/Library/Application Support/macTAK}.
 * {@code FileSystemUtils} derives its {@code atak/} data root from this.
 */
public class Environment {

    public static final String MEDIA_MOUNTED = "mounted";
    public static final String MEDIA_UNMOUNTED = "unmounted";
    public static final String DIRECTORY_DOWNLOADS = "Download";
    public static final String DIRECTORY_PICTURES = "Pictures";
    public static final String DIRECTORY_DCIM = "DCIM";

    private static final File EXTERNAL;

    static {
        String home = System.getenv("MACTAK_HOME");
        File dir = (home != null && !home.isEmpty())
                ? new File(home)
                : new File(System.getProperty("user.home"), "Library/Application Support/macTAK");
        dir.mkdirs();
        EXTERNAL = dir;
    }

    public static File getExternalStorageDirectory() { return EXTERNAL; }

    public static File getExternalStoragePublicDirectory(String type) {
        File f = new File(EXTERNAL, type);
        f.mkdirs();
        return f;
    }

    public static String getExternalStorageState() { return MEDIA_MOUNTED; }

    public static String getExternalStorageState(File path) { return MEDIA_MOUNTED; }

    public static boolean isExternalStorageRemovable() { return false; }

    public static boolean isExternalStorageEmulated() { return false; }

    public static File getDataDirectory() { return new File(EXTERNAL, "data"); }

    public static File getDownloadCacheDirectory() { return new File(System.getProperty("java.io.tmpdir")); }

    public static File getRootDirectory() { return new File("/"); }
}
