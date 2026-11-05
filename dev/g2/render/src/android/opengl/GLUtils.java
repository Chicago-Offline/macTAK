package android.opengl;

import android.graphics.Bitmap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * macOS runtime for {@code android.opengl.GLUtils}: uploads a {@link Bitmap}
 * as an RGBA8 texture through the GLES shim. Every Bitmap here is ARGB_8888
 * storage, so format/type are always GL_RGBA / GL_UNSIGNED_BYTE regardless
 * of the config the caller asked for (GLTexture passes its own _format/_type,
 * which may name RGB565 — the data we hand GL is still RGBA8 and we say so).
 */
public final class GLUtils {

    private GLUtils() { }

    public static int getInternalFormat(Bitmap bitmap) { return GLES20.GL_RGBA; }

    public static int getType(Bitmap bitmap) { return GLES20.GL_UNSIGNED_BYTE; }

    public static void texImage2D(int target, int level, Bitmap bitmap, int border) {
        texImage2D(target, level, GLES20.GL_RGBA, bitmap, GLES20.GL_UNSIGNED_BYTE, border);
    }

    public static void texImage2D(int target, int level, int internalformat, Bitmap bitmap, int border) {
        texImage2D(target, level, internalformat, bitmap, GLES20.GL_UNSIGNED_BYTE, border);
    }

    public static void texImage2D(int target, int level, int internalformat, Bitmap bitmap, int type, int border) {
        ByteBuffer px = rgba(bitmap);
        GLES20.glTexImage2D(target, level, GLES20.GL_RGBA, bitmap.getWidth(), bitmap.getHeight(), border,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
    }

    public static void texSubImage2D(int target, int level, int xoffset, int yoffset, Bitmap bitmap) {
        texSubImage2D(target, level, xoffset, yoffset, bitmap, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE);
    }

    public static void texSubImage2D(int target, int level, int xoffset, int yoffset, Bitmap bitmap, int format, int type) {
        ByteBuffer px = rgba(bitmap);
        GLES20.glTexSubImage2D(target, level, xoffset, yoffset, bitmap.getWidth(), bitmap.getHeight(),
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
    }

    private static ByteBuffer rgba(Bitmap bitmap) {
        ByteBuffer px = ByteBuffer.allocateDirect(bitmap.getWidth() * bitmap.getHeight() * 4).order(ByteOrder.nativeOrder());
        bitmap.copyPixelsToBuffer(px);
        px.rewind();
        return px;
    }
}
