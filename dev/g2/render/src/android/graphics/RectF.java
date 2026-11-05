package android.graphics;

/** macOS runtime for {@code android.graphics.RectF}; see {@link PointF}. */
public class RectF {
    public float left;
    public float top;
    public float right;
    public float bottom;

    public RectF() { }

    public RectF(float left, float top, float right, float bottom) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
    }

    public RectF(RectF r) {
        if (r != null) { left = r.left; top = r.top; right = r.right; bottom = r.bottom; }
    }

    public RectF(Rect r) {
        if (r != null) { left = r.left; top = r.top; right = r.right; bottom = r.bottom; }
    }

    public final boolean isEmpty() { return left >= right || top >= bottom; }

    public final float width() { return right - left; }

    public final float height() { return bottom - top; }

    public final float centerX() { return (left + right) * 0.5f; }

    public final float centerY() { return (top + bottom) * 0.5f; }

    public void setEmpty() { left = right = top = bottom = 0; }

    public void set(float left, float top, float right, float bottom) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
    }

    public void set(RectF src) { set(src.left, src.top, src.right, src.bottom); }

    public void set(Rect src) { set(src.left, src.top, src.right, src.bottom); }

    public void offset(float dx, float dy) { left += dx; top += dy; right += dx; bottom += dy; }

    public void offsetTo(float newLeft, float newTop) {
        right += newLeft - left; bottom += newTop - top; left = newLeft; top = newTop;
    }

    public void inset(float dx, float dy) { left += dx; top += dy; right -= dx; bottom -= dy; }

    public boolean contains(float x, float y) {
        return left < right && top < bottom && x >= left && x < right && y >= top && y < bottom;
    }

    public boolean contains(float left, float top, float right, float bottom) {
        return this.left < this.right && this.top < this.bottom
            && this.left <= left && this.top <= top && this.right >= right && this.bottom >= bottom;
    }

    public boolean contains(RectF r) { return contains(r.left, r.top, r.right, r.bottom); }

    public boolean intersect(float left, float top, float right, float bottom) {
        if (this.left < right && left < this.right && this.top < bottom && top < this.bottom) {
            if (this.left < left) this.left = left;
            if (this.top < top) this.top = top;
            if (this.right > right) this.right = right;
            if (this.bottom > bottom) this.bottom = bottom;
            return true;
        }
        return false;
    }

    public boolean intersect(RectF r) { return intersect(r.left, r.top, r.right, r.bottom); }

    public boolean intersects(float left, float top, float right, float bottom) {
        return this.left < right && left < this.right && this.top < bottom && top < this.bottom;
    }

    public static boolean intersects(RectF a, RectF b) {
        return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;
    }

    public void round(Rect dst) {
        dst.set(Math.round(left), Math.round(top), Math.round(right), Math.round(bottom));
    }

    public void roundOut(Rect dst) {
        dst.set((int) Math.floor(left), (int) Math.floor(top), (int) Math.ceil(right), (int) Math.ceil(bottom));
    }

    public void union(float left, float top, float right, float bottom) {
        if (left < right && top < bottom) {
            if (this.left < this.right && this.top < this.bottom) {
                if (this.left > left) this.left = left;
                if (this.top > top) this.top = top;
                if (this.right < right) this.right = right;
                if (this.bottom < bottom) this.bottom = bottom;
            } else {
                this.left = left; this.top = top; this.right = right; this.bottom = bottom;
            }
        }
    }

    public void union(RectF r) { union(r.left, r.top, r.right, r.bottom); }

    public void union(float x, float y) {
        if (x < left) left = x; else if (x > right) right = x;
        if (y < top) top = y; else if (y > bottom) bottom = y;
    }

    public void sort() {
        if (left > right) { float t = left; left = right; right = t; }
        if (top > bottom) { float t = top; top = bottom; bottom = t; }
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RectF)) return false;
        RectF r = (RectF) o;
        return left == r.left && top == r.top && right == r.right && bottom == r.bottom;
    }

    @Override public int hashCode() {
        int result = (left != +0.0f ? Float.floatToIntBits(left) : 0);
        result = 31 * result + (top != +0.0f ? Float.floatToIntBits(top) : 0);
        result = 31 * result + (right != +0.0f ? Float.floatToIntBits(right) : 0);
        result = 31 * result + (bottom != +0.0f ? Float.floatToIntBits(bottom) : 0);
        return result;
    }

    @Override public String toString() { return "RectF(" + left + ", " + top + ", " + right + ", " + bottom + ")"; }
}
