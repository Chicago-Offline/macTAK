package mactak.render;

import com.atakmap.map.Globe;
import com.atakmap.map.opengl.GLMapView;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * G2.1 spike: the engine's native renderer ({@code GLGlobe} behind
 * {@link GLMapView}) draws into an EGL pbuffer on ANGLE/Metal from a JVM.
 *
 * <p>Pass criterion: the pbuffer is pre-filled with a sentinel colour, then
 * {@code GLMapView.render()} runs N frames; the engine's own clear must have
 * replaced the sentinel everywhere (the G0 stub would leave it untouched),
 * with zero GL errors. With no layers attached, the frame is the engine's
 * clear colour — this proves the GL path, not map content (that is G2.2).
 *
 * <p>Exit: 0 PASS, 1 FAIL, 2 usage/environment, 3 EGL init failed.
 */
public final class RenderSmoke {

    private static final String USAGE =
        "usage: RenderSmoke --lib PATH [--lib PATH ...] --egl PATH --gles PATH\n"
      + "                   [--size WxH] [--frames N] [--out FILE.ppm]\n"
      + "  --lib PATH    native library to System.load (repeatable, in order)\n"
      + "  --egl PATH    ANGLE libEGL.dylib (must sit beside the libGLESv2 the engine links)\n"
      + "  --gles PATH   ANGLE libGLESv2.dylib\n"
      + "  --size WxH    pbuffer size (default 512x512)\n"
      + "  --frames N    frames to render (default 5)\n"
      + "  --out FILE    write the last frame as binary PPM\n";

    private RenderSmoke() { }

    public static void main(String[] args) {
        List<String> libs = new ArrayList<>();
        String egl = null, gles = null, out = null;
        int w = 512, h = 512, frames = 5;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--lib": libs.add(args[++i]); break;
                case "--egl": egl = args[++i]; break;
                case "--gles": gles = args[++i]; break;
                case "--out": out = args[++i]; break;
                case "--frames": frames = Integer.parseInt(args[++i]); break;
                case "--size": {
                    String[] wh = args[++i].split("x");
                    w = Integer.parseInt(wh[0]); h = Integer.parseInt(wh[1]);
                    break;
                }
                default: System.err.print(USAGE); System.exit(2);
            }
        }
        if (libs.isEmpty() || egl == null || gles == null) { System.err.print(USAGE); System.exit(2); }

        for (String lib : libs) System.load(lib);

        if (!NativeEgl.init(egl, gles, w, h)) {
            System.err.println("render-smoke: EGL init failed: " + NativeEgl.getString(NativeEgl.STR_LAST_ERROR));
            System.exit(3);
        }
        if (!NativeEgl.makeCurrent()) {
            System.err.println("render-smoke: eglMakeCurrent failed: " + NativeEgl.getString(NativeEgl.STR_LAST_ERROR));
            System.exit(3);
        }
        System.out.println("render-smoke: EGL " + NativeEgl.getString(NativeEgl.STR_EGL_VERSION)
                + " platform=" + NativeEgl.getString(NativeEgl.STR_BACKEND));
        System.out.println("render-smoke: GL_RENDERER=\"" + NativeEgl.getString(NativeEgl.STR_GL_RENDERER) + "\"");
        System.out.println("render-smoke: GL_VERSION=\"" + NativeEgl.getString(NativeEgl.STR_GL_VERSION) + "\"");

        ByteBuffer px = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());

        // Sentinel: magenta. Our own GL path through the shim must work first.
        NativeEgl.fill(1f, 0f, 1f, 1f);
        NativeEgl.finish();
        if (!NativeEgl.readPixels(px) || countColour(px, w, h, 255, 0, 255) != (long) w * h) {
            System.err.println("render-smoke: FAIL: sentinel fill/readback broken (shim GL path)");
            System.exit(1);
        }

        EglRenderContext ctx = new EglRenderContext(w, h, 128d);
        Globe globe = new Globe();
        GLMapView view = new GLMapView(ctx, globe, 0, 0, w, h);
        view.start();

        int glErrors = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < frames; i++) {
            ctx.pump();
            view.render();
            NativeEgl.finish();
            int err;
            while ((err = NativeEgl.getGlError()) != 0) {
                glErrors++;
                System.err.printf("render-smoke: frame %d GL error 0x%04x%n", i, err);
            }
        }
        double msPerFrame = (System.nanoTime() - t0) / 1e6 / frames;

        boolean readOk = NativeEgl.readPixels(px);
        long sentinel = countColour(px, w, h, 255, 0, 255);
        int cx = ((h / 2) * w + w / 2) * 4;
        System.out.printf("render-smoke: %d frames, %.1f ms/frame (incl. glFinish), glErrors=%d%n", frames, msPerFrame, glErrors);
        System.out.printf("render-smoke: sentinel pixels left=%d/%d centre=(%d,%d,%d,%d)%n",
                sentinel, (long) w * h, px.get(cx) & 0xff, px.get(cx + 1) & 0xff, px.get(cx + 2) & 0xff, px.get(cx + 3) & 0xff);

        if (out != null) {
            try { writePpm(out, px, w, h); System.out.println("render-smoke: wrote " + out); }
            catch (IOException e) { System.err.println("render-smoke: ppm write failed: " + e); }
        }

        view.stop();
        view.release();
        globe.dispose();
        NativeEgl.release();
        NativeEgl.destroy();

        boolean pass = readOk && glErrors == 0 && sentinel == 0;
        System.out.println(pass ? "render-smoke: PASS" : "render-smoke: FAIL");
        System.exit(pass ? 0 : 1);
    }

    private static long countColour(ByteBuffer px, int w, int h, int r, int g, int b) {
        long n = 0;
        for (int i = 0; i < w * h * 4; i += 4) {
            if ((px.get(i) & 0xff) == r && (px.get(i + 1) & 0xff) == g && (px.get(i + 2) & 0xff) == b) n++;
        }
        return n;
    }

    private static void writePpm(String path, ByteBuffer px, int w, int h) throws IOException {
        try (OutputStream os = new FileOutputStream(path)) {
            os.write(("P6\n" + w + " " + h + "\n255\n").getBytes("US-ASCII"));
            byte[] row = new byte[w * 3];
            for (int y = h - 1; y >= 0; y--) {            // GL rows are bottom-up
                for (int x = 0; x < w; x++) {
                    int i = (y * w + x) * 4;
                    row[x * 3] = px.get(i); row[x * 3 + 1] = px.get(i + 1); row[x * 3 + 2] = px.get(i + 2);
                }
                os.write(row);
            }
        }
    }
}
