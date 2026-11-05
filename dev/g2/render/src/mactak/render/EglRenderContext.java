package mactak.render;

import com.atakmap.map.RenderContext;
import com.atakmap.map.RenderSurface;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * The takkernel {@link RenderContext} over {@link NativeEgl}: the macOS
 * counterpart of upstream's {@code gov.tak.platform.engine.JOGLRenderContext}
 * (engine/src/jre). The thread that constructs it is the render thread; it
 * owns the EGL context and drains {@link #queueEvent} work via {@link #pump()}
 * before each frame. The surface is a fixed-size pbuffer.
 * {@code com.atakmap.map.RenderContext} is what {@code GLMapView} takes; it is
 * the {@code gov.tak.api.engine.map} one bound to {@code com.atakmap.map.RenderSurface}.
 */
final class EglRenderContext implements RenderContext {

    private final Thread renderThread = Thread.currentThread();
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private final PbufferSurface surface;

    EglRenderContext(int width, int height, double dpi) {
        this.surface = new PbufferSurface(width, height, dpi);
    }

    /** Run everything queued from other threads; call on the render thread each frame. */
    void pump() {
        Runnable r;
        while (true) {
            synchronized (queue) { r = queue.pollFirst(); }
            if (r == null) return;
            r.run();
        }
    }

    @Override public boolean isRenderThread() { return Thread.currentThread() == renderThread; }

    @Override public void queueEvent(Runnable r) {
        if (isRenderThread()) { r.run(); return; }
        synchronized (queue) { queue.addLast(r); }
    }

    @Override public void requestRefresh() { }
    @Override public void setFrameRate(float rate) { }
    @Override public float getFrameRate() { return 0f; }
    @Override public void setContinuousRenderEnabled(boolean enabled) { }
    @Override public boolean isContinuousRenderEnabled() { return true; }
    @Override public boolean supportsChildContext() { return false; }
    // upstream declares these with the raw type (so does JOGLRenderContext)
    @SuppressWarnings("rawtypes")
    @Override public gov.tak.api.engine.map.RenderContext createChildContext() { return null; }
    @SuppressWarnings("rawtypes")
    @Override public void destroyChildContext(gov.tak.api.engine.map.RenderContext child) { }

    @Override public boolean isAttached() { return isRenderThread() && NativeEgl.isCurrent(); }

    @Override public boolean attach() {
        if (!isRenderThread()) return false;
        return NativeEgl.makeCurrent();
    }

    @Override public boolean detach() {
        if (!isRenderThread()) return false;
        NativeEgl.release();
        return !NativeEgl.isCurrent();
    }

    @Override public boolean isMainContext() { return true; }
    @Override public RenderSurface getRenderSurface() { return surface; }

    /** Fixed-size offscreen surface; size never changes, so listeners are kept but never fired. */
    static final class PbufferSurface implements RenderSurface {
        private final int width, height;
        private final double dpi;
        private final Set<OnSizeChangedListener> listeners =
                java.util.Collections.newSetFromMap(new IdentityHashMap<OnSizeChangedListener, Boolean>());

        PbufferSurface(int width, int height, double dpi) {
            this.width = width; this.height = height; this.dpi = dpi;
        }

        @Override public double getDpi() { return dpi; }
        @Override public int getWidth() { return width; }
        @Override public int getHeight() { return height; }
        @Override public void addOnSizeChangedListener(OnSizeChangedListener l) { synchronized (listeners) { listeners.add(l); } }
        @Override public void removeOnSizeChangedListener(OnSizeChangedListener l) { synchronized (listeners) { listeners.remove(l); } }
    }
}
