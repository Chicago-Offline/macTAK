import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;

import javax.imageio.ImageIO;

/**
 * Synthetic, georeferenced basemap for the G2 render fixtures: a graticule
 * over a two-tone land texture with coordinate labels, as an EPSG:4326 PNG
 * plus ESRI world file. make-basemap.sh turns it into MBTiles with
 * gdalwarp. Deliberately not real imagery — CI is offline and the point is
 * to prove the raster tile pipeline (TileReader → BitmapFactory → texture
 * atlas → terrain-draped quads), which any georeferenced raster does.
 *
 * usage: MakeBasemap OUT.png WEST SOUTH EAST NORTH PX_PER_DEG
 */
public final class MakeBasemap {
    public static void main(String[] a) throws IOException {
        File out = new File(a[0]);
        double w = Double.parseDouble(a[1]), s = Double.parseDouble(a[2]);
        double e = Double.parseDouble(a[3]), n = Double.parseDouble(a[4]);
        double ppd = Double.parseDouble(a[5]);
        int W = (int) Math.round((e - w) * ppd), H = (int) Math.round((n - s) * ppd);

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 0.01° checker: two pale land tones so tile seams and projection are visible
        double cell = 0.01;
        for (double lon = Math.floor(w / cell) * cell; lon < e; lon += cell) {
            for (double lat = Math.floor(s / cell) * cell; lat < n; lat += cell) {
                boolean odd = (((long) Math.round(lon / cell) + (long) Math.round(lat / cell)) & 1) == 1;
                g.setColor(odd ? new Color(0xE8E4D8) : new Color(0xDDD8C8));
                int x0 = px(lon, w, ppd), x1 = px(lon + cell, w, ppd);
                int y0 = py(lat + cell, n, ppd), y1 = py(lat, n, ppd);
                g.fillRect(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0));
            }
        }

        // graticule: thin every 0.01°, bold every 0.05°, labels at bold crossings
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(10, (int) (ppd * 0.0018))));
        for (double lon = Math.floor(w / cell) * cell; lon <= e; lon += cell) {
            boolean bold = Math.abs(Math.round(lon / 0.05) * 0.05 - lon) < 1e-9;
            g.setColor(bold ? new Color(0x8A7F6A) : new Color(0xC4BBA8));
            g.setStroke(new BasicStroke(bold ? 2f : 1f));
            int x = px(lon, w, ppd);
            g.draw(new Line2D.Float(x, 0, x, H));
        }
        for (double lat = Math.floor(s / cell) * cell; lat <= n; lat += cell) {
            boolean bold = Math.abs(Math.round(lat / 0.05) * 0.05 - lat) < 1e-9;
            g.setColor(bold ? new Color(0x8A7F6A) : new Color(0xC4BBA8));
            g.setStroke(new BasicStroke(bold ? 2f : 1f));
            int y = py(lat, n, ppd);
            g.draw(new Line2D.Float(0, y, W, y));
        }
        g.setColor(new Color(0x4A4336));
        for (double lon = Math.ceil(w / 0.05) * 0.05; lon <= e; lon += 0.05) {
            for (double lat = Math.ceil(s / 0.05) * 0.05; lat <= n; lat += 0.05) {
                g.drawString(String.format(Locale.US, "%.2f, %.2f", lat, lon), px(lon, w, ppd) + 4, py(lat, n, ppd) - 4);
            }
        }
        g.dispose();

        ImageIO.write(img, "png", out);
        // world file: pixel size, rotation, centre of the upper-left pixel (SRS is EPSG:4326 by contract)
        String base = out.getPath().replaceAll("\\.png$", "");
        try (PrintWriter pw = new PrintWriter(new FileWriter(base + ".pgw"))) {
            pw.printf(Locale.US, "%.10f%n0.0%n0.0%n-%.10f%n%.10f%n%.10f%n", 1 / ppd, 1 / ppd, w + 0.5 / ppd, n - 0.5 / ppd);
        }
        System.out.printf(Locale.US, "make-basemap: %dx%d px, [%.4f %.4f .. %.4f %.4f] -> %s%n", W, H, s, w, n, e, out);
    }

    static int px(double lon, double w, double ppd) { return (int) Math.round((lon - w) * ppd); }
    static int py(double lat, double n, double ppd) { return (int) Math.round((n - lat) * ppd); }
}
