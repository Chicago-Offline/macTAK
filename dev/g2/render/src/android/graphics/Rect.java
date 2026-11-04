package android.graphics;

/** macOS runtime for {@code android.graphics.Rect}; see {@link PointF}. */
public final class Rect {
    public int left;
    public int top;
    public int right;
    public int bottom;

    public Rect() { }

    public Rect(int left, int top, int right, int bottom) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
    }

    public Rect(Rect r) {
        if (r != null) { left = r.left; top = r.top; right = r.right; bottom = r.bottom; }
    }

    public boolean isEmpty() { return left >= right || top >= bottom; }

    public int width() { return right - left; }

    public int height() { return bottom - top; }

    public int centerX() { return (left + right) >> 1; }

    public int centerY() { return (top + bottom) >> 1; }

    public float exactCenterX() { return (left + right) * 0.5f; }

    public float exactCenterY() { return (top + bottom) * 0.5f; }

    public void setEmpty() { left = right = top = bottom = 0; }

    public void set(int left, int top, int right, int bottom) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
    }

    public void set(Rect src) { set(src.left, src.top, src.right, src.bottom); }

    public void offset(int dx, int dy) { left += dx; top += dy; right += dx; bottom += dy; }

    public void offsetTo(int newLeft, int newTop) {
        right += newLeft - left; bottom += newTop - top; left = newLeft; top = newTop;
    }

    public void inset(int dx, int dy) { left += dx; top += dy; right -= dx; bottom -= dy; }

    public boolean contains(int x, int y) {
        return left < right && top < bottom && x >= left && x < right && y >= top && y < bottom;
    }

    public boolean contains(int left, int top, int right, int bottom) {
        return this.left < this.right && this.top < this.bottom
            && this.left <= left && this.top <= top && this.right >= right && this.bottom >= bottom;
    }

    public boolean contains(Rect r) { return contains(r.left, r.top, r.right, r.bottom); }

    public boolean intersect(int left, int top, int right, int bottom) {
        if (this.left < right && left < this.right && this.top < bottom && top < this.bottom) {
            if (this.left < left) this.left = left;
            if (this.top < top) this.top = top;
            if (this.right > right) this.right = right;
            if (this.bottom > bottom) this.bottom = bottom;
            return true;
        }
        return false;
    }

    public boolean intersect(Rect r) { return intersect(r.left, r.top, r.right, r.bottom); }

    public boolean intersects(int left, int top, int right, int bottom) {
        return this.left < right && left < this.right && this.top < bottom && top < this.bottom;
    }

    public static boolean intersects(Rect a, Rect b) {
        return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;
    }

    public void union(int left, int top, int right, int bottom) {
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

    public void union(Rect r) { union(r.left, r.top, r.right, r.bottom); }

    public void union(int x, int y) {
        if (x < left) left = x; else if (x > right) right = x;
        if (y < top) top = y; else if (y > bottom) bottom = y;
    }

    public void sort() {
        if (left > right) { int t = left; left = right; right = t; }
        if (top > bottom) { int t = top; top = bottom; bottom = t; }
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Rect)) return false;
        Rect r = (Rect) o;
        return left == r.left && top == r.top && right == r.right && bottom == r.bottom;
    }

    @Override public int hashCode() {
        int result = left;
        result = 31 * result + top;
        result = 31 * result + right;
        result = 31 * result + bottom;
        return result;
    }

    @Override public String toString() { return "Rect(" + left + ", " + top + " - " + right + ", " + bottom + ")"; }
}
