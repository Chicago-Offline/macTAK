package mactak.render;

import android.opengl.GLES20;
import android.opengl.GLES30;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Assertions for the Buffer contract of the generated GLES shim
 * ({@code dev/g2/jni-extra/mactak_gles.c}), run by
 * {@code dev/g2/render/test/gles-shim.sh} on the ANGLE-linked engine.
 *
 * <p>{@code glVertexAttribPointer} and {@code glVertexAttribIPointer} hand GL
 * a pointer it keeps until the draw call. Android therefore accepts only a
 * direct {@code Buffer} there and throws {@code IllegalArgumentException}
 * for anything else. An array-backed buffer has no stable address: the shim
 * can only pass a temporary copy, which is freed when the call returns.
 * Calls that consume their data before returning ({@code glBufferData})
 * take either kind.
 *
 * <p>Exit: 0 PASS, 1 FAIL, 2 usage, 3 EGL init failed.
 */
public final class GlesShimSelfTest {

    private static int failures = 0;

    private GlesShimSelfTest() { }

    private static void check(boolean cond, String what) {
        System.out.println((cond ? "PASS: " : "FAIL: ") + what);
        if (!cond) failures++;
    }

    /** True when {@code call} throws IllegalArgumentException. */
    private static boolean rejects(Runnable call) {
        try {
            call.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    public static void main(String[] args) {
        java.util.List<String> libs = new java.util.ArrayList<>();
        String egl = null, gles = null;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--lib": libs.add(args[i + 1]); break;
                case "--egl": egl = args[i + 1]; break;
                case "--gles": gles = args[i + 1]; break;
                default: libs.clear();
            }
        }
        if (libs.isEmpty() || egl == null || gles == null || args.length % 2 != 0) {
            System.err.println("usage: GlesShimSelfTest --lib PATH [--lib PATH ...] --egl PATH --gles PATH");
            System.exit(2);
        }
        for (String lib : libs) System.load(lib);
        if (!NativeEgl.init(egl, gles, 16, 16) || !NativeEgl.makeCurrent()) {
            System.err.println("gles-shim-canary: EGL init failed: " + NativeEgl.getString(NativeEgl.STR_LAST_ERROR));
            System.exit(3);
        }

        final FloatBuffer direct = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer();
        final FloatBuffer heap = FloatBuffer.wrap(new float[4]);

        check(rejects(() -> GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, heap)),
                "glVertexAttribPointer rejects an array-backed Buffer (GL would keep a dangling pointer)");
        check(rejects(() -> GLES30.glVertexAttribIPointer(0, 2, GLES20.GL_INT, 0, heap)),
                "glVertexAttribIPointer rejects an array-backed Buffer");
        check(!rejects(() -> GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, direct)),
                "glVertexAttribPointer accepts a direct Buffer");
        check(!rejects(() -> GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, null)),
                "glVertexAttribPointer accepts a null Buffer");

        final int[] vbo = new int[1];
        GLES20.glGenBuffers(1, vbo, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0]);
        check(!rejects(() -> GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 16, heap, GLES20.GL_STATIC_DRAW)),
                "glBufferData still takes an array-backed Buffer (data is copied before the call returns)");
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glDeleteBuffers(1, vbo, 0);
        check(NativeEgl.getGlError() == 0, "no GL error raised");

        NativeEgl.release();
        NativeEgl.destroy();
        System.out.println(failures == 0 ? "gles-shim-canary: PASS" : "gles-shim-canary: FAIL (" + failures + " check(s))");
        System.exit(failures == 0 ? 0 : 1);
    }
}
