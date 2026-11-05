package android.os;

/** macOS runtime for {@code android.os.SystemClock} (the transplant's methods are native). */
public final class SystemClock {
    private SystemClock() { }

    private static final long BOOT_NANOS = System.nanoTime();

    public static long uptimeMillis() { return (System.nanoTime() - BOOT_NANOS) / 1_000_000L; }

    public static long elapsedRealtime() { return uptimeMillis(); }

    public static long elapsedRealtimeNanos() { return System.nanoTime() - BOOT_NANOS; }

    public static long currentThreadTimeMillis() {
        return java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() / 1_000_000L;
    }

    public static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
