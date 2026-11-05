package mactak.render;

import android.content.Context;

import com.atakmap.map.AtakMapView;
import com.atakmap.map.RenderSurface;
import com.atakmap.map.opengl.GLMapSurface;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * The takkernel render context over {@link NativeEgl}: the macOS counterpart
 * of upstream's {@code gov.tak.platform.engine.JOGLRenderContext}. Extends the
 * jar's abstract {@code GLMapSurface} (RenderContext + RenderSurface) because
 * {@code GLMapView.getSurface()} casts the context to it and the asynchronous
 * layer renderers call that. The thread that constructs it is the render
 * thread; it owns the EGL context and drains {@link #queueEvent} work via
 * {@link #pump()} before each frame. The surface is a fixed-size pbuffer.
 */
@SuppressWarnings("deprecation")   // GLMapSurface.getGLMapView() is typed with the deprecated MapRenderer
final class EglRenderContext extends GLMapSurface {

    private final Thread renderThread = Thread.currentThread();
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private final int width, height;
    private final double dpi;
    private final Set<OnSizeChangedListener> listeners =
            java.util.Collections.newSetFromMap(new IdentityHashMap<OnSizeChangedListener, Boolean>());
    private com.atakmap.map.MapRenderer glMapView;

    EglRenderContext(int width, int height, double dpi) {
        this.width = width; this.height = height; this.dpi = dpi;
        GLMapSurface.setMainGLThread();
    }

    void setGLMapView(com.atakmap.map.MapRenderer view) { this.glMapView = view; }

    /** Run everything queued from other threads; call on the render thread each frame. */
    void pump() {
        Runnable r;
        while (true) {
            synchronized (queue) { r = queue.pollFirst(); }
            if (r == null) return;
            r.run();
        }
    }

    // ---- GLMapSurface -------------------------------------------------------
    @Override public com.atakmap.map.MapRenderer getGLMapView() { return glMapView; }
    @Override public AtakMapView getMapView() { return null; }
    @Override public Context getContext() { return new Context(); }

    // ---- RenderContext ------------------------------------------------------
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
    @Override public RenderSurface getRenderSurface() { return this; }

    // ---- RenderSurface (fixed-size pbuffer; size never changes) ------------
    @Override public double getDpi() { return dpi; }
    @Override public int getWidth() { return width; }
    @Override public int getHeight() { return height; }
    @Override public void addOnSizeChangedListener(OnSizeChangedListener l) { synchronized (listeners) { listeners.add(l); } }
    @Override public void removeOnSizeChangedListener(OnSizeChangedListener l) { synchronized (listeners) { listeners.remove(l); } }
}
