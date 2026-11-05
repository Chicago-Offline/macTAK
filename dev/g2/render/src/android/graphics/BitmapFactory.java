package android.graphics;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** macOS runtime for {@code android.graphics.BitmapFactory}, decoding through ImageIO. */
public class BitmapFactory {

    public static class Options {
        public Bitmap inBitmap;
        public boolean inMutable;
        public boolean inJustDecodeBounds;
        public int inSampleSize;
        public Bitmap.Config inPreferredConfig = Bitmap.Config.ARGB_8888;
        public boolean inPremultiplied = true;
        public boolean inDither;
        public int inDensity;
        public int inTargetDensity;
        public int inScreenDensity;
        public boolean inScaled = true;
        public boolean inPurgeable;
        public boolean inInputShareable;
        public boolean inPreferQualityOverSpeed;
        public int outWidth;
        public int outHeight;
        public String outMimeType;
        public byte[] inTempStorage;
        public boolean mCancel;

        public Options() { }

        public void requestCancelDecode() { mCancel = true; }
    }

    private BitmapFactory() { }

    public static Bitmap decodeFile(String pathName) { return decodeFile(pathName, null); }

    public static Bitmap decodeFile(String pathName, Options opts) {
        try (InputStream in = new FileInputStream(new File(pathName))) {
            return decodeStream(in, null, opts);
        } catch (IOException e) {
            return null;
        }
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length) {
        return decodeByteArray(data, offset, length, null);
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length, Options opts) {
        return decodeStream(new ByteArrayInputStream(data, offset, length), null, opts);
    }

    public static Bitmap decodeStream(InputStream is) { return decodeStream(is, null, null); }

    public static Bitmap decodeStream(InputStream is, Rect outPadding, Options opts) {
        if (is == null) return null;
        try (ImageInputStream iis = ImageIO.createImageInputStream(is)) {
            if (iis == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                if (opts != null) {
                    opts.outWidth = reader.getWidth(0);
                    opts.outHeight = reader.getHeight(0);
                    opts.outMimeType = mime(reader.getFormatName());
                    if (opts.inJustDecodeBounds) return null;
                }
                BufferedImage img = reader.read(0);
                if (img == null) return null;
                int sample = opts != null && opts.inSampleSize > 1 ? opts.inSampleSize : 1;
                Bitmap.Config cfg = opts != null && opts.inPreferredConfig != null ? opts.inPreferredConfig : Bitmap.Config.ARGB_8888;
                Bitmap b = new Bitmap(img, cfg);
                if (sample > 1) {
                    b = Bitmap.createScaledBitmap(b, Math.max(1, img.getWidth() / sample), Math.max(1, img.getHeight() / sample), true);
                }
                b.setHasAlpha(img.getColorModel().hasAlpha());
                if (opts != null) { opts.outWidth = b.getWidth(); opts.outHeight = b.getHeight(); }
                return b;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static Bitmap decodeResource(android.content.res.Resources res, int id) { return null; }

    public static Bitmap decodeResource(android.content.res.Resources res, int id, Options opts) { return null; }

    private static String mime(String fmt) {
        if (fmt == null) return null;
        switch (fmt.toLowerCase()) {
            case "png": return "image/png";
            case "jpeg": case "jpg": return "image/jpeg";
            case "gif": return "image/gif";
            case "bmp": return "image/bmp";
            default: return "image/" + fmt.toLowerCase();
        }
    }
}
