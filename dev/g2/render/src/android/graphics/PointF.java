package android.graphics;

/**
 * macOS runtime for the {@code android.graphics.PointF} API the engine's
 * Java render path uses. The android-port transplant carries the platform
 * {@code android.jar} whose bodies throw {@code RuntimeException("Stub!")};
 * TAK's own desktop android-port (gated artifactory) is unobtainable, so the
 * value types are re-implemented here from the public API contract.
 * Compiled ahead of the transplant on the classpath (see dev/g2/render/run.sh).
 */
public class PointF {
    public float x;
    public float y;

    public PointF() { }

    public PointF(float x, float y) { this.x = x; this.y = y; }

    public PointF(Point p) { this.x = p.x; this.y = p.y; }

    public PointF(PointF p) { this.x = p.x; this.y = p.y; }

    public final void set(float x, float y) { this.x = x; this.y = y; }

    public final void set(PointF p) { this.x = p.x; this.y = p.y; }

    public final void negate() { x = -x; y = -y; }

    public final void offset(float dx, float dy) { x += dx; y += dy; }

    public final boolean equals(float x, float y) { return this.x == x && this.y == y; }

    public final float length() { return length(x, y); }

    public static float length(float x, float y) { return (float) Math.hypot(x, y); }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PointF)) return false;
        PointF p = (PointF) o;
        return Float.compare(p.x, x) == 0 && Float.compare(p.y, y) == 0;
    }

    @Override public int hashCode() {
        int result = (x != +0.0f ? Float.floatToIntBits(x) : 0);
        result = 31 * result + (y != +0.0f ? Float.floatToIntBits(y) : 0);
        return result;
    }

    @Override public String toString() { return "PointF(" + x + ", " + y + ")"; }
}
