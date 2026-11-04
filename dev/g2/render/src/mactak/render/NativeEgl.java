package mactak.render;

/**
 * EGL on ANGLE (Metal) for the engine on macOS. Binding is
 * {@code dev/g2/jni-extra/mactak_egl.cpp} inside {@code libtakenginejni.dylib}.
 * One pbuffer, one ES3 context; the libGLESv2 passed to {@link #init} must be
 * the one the engine was linked against (staged beside it in native-dist).
 */
final class NativeEgl {

    private NativeEgl() { }

    static final int STR_BACKEND = 0;
    static final int STR_EGL_VERSION = 1;
    static final int STR_GL_RENDERER = 2;
    static final int STR_GL_VERSION = 3;
    static final int STR_LAST_ERROR = 4;

    static native boolean init(String eglLib, String glesLib, int width, int height);

    static native boolean makeCurrent();

    static native boolean release();

    static native boolean isCurrent();

    static native void finish();

    static native int getGlError();

    static native void fill(float r, float g, float b, float a);

    /** RGBA8 bottom-up into a direct buffer of at least width*height*4 bytes. */
    static native boolean readPixels(java.nio.ByteBuffer dst);

    static native String getString(int which);

    static native void destroy();
}
