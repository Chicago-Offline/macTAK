package mactak.smoke;

import org.gdal.gdal.Band;
import org.gdal.gdal.Dataset;
import org.gdal.gdal.Driver;
import org.gdal.gdal.gdal;
import org.gdal.gdalconst.gdalconstConstants;
import org.gdal.osr.CoordinateTransformation;
import org.gdal.osr.SpatialReference;
import org.gdal.osr.osrConstants;

import java.io.File;

/**
 * G2.4b smoke for the staged GDAL Java bindings (dev/g2/fetch-gdal-java.sh).
 *
 * Proves, in order: the JNI library loads and is the Homebrew libgdal the
 * engine links (version match); GTiff round-trips through org.gdal (create →
 * write → reopen → read, geotransform + SRS intact); and records the GDAL 3
 * axis-order behaviour for the exact construction the engine uses
 * ({@code new SpatialReference().ImportFromEPSG(4326)} fed lon,lat) — the
 * G2.4 b.2 question — with and without OAMS_TRADITIONAL_GIS_ORDER.
 *
 * Exit 0 = PASS. Any gate failure exits 1 with the first failing line.
 */
public final class GdalJavaSmoke {
    private GdalJavaSmoke() {}

    private static void fail(String why) {
        System.out.println("gdal-java-smoke: FAIL: " + why);
        System.exit(1);
    }

    private static void check(boolean ok, String why) {
        if (!ok) fail(why);
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            fail("usage: GdalJavaSmoke <expected-gdal-version> <scratch-dir>");
        }
        final String expectedVersion = args[0];
        final File scratch = new File(args[1]);

        // 1. load + version: the loaded libgdal must be the one gdal-config
        //    describes (the engine dylib links the same install name).
        gdal.AllRegister();
        final String release = gdal.VersionInfo("RELEASE_NAME");
        System.out.println("gdal-java-smoke: org.gdal loaded, GDAL " + release
                + " (" + gdal.GetDriverCount() + " drivers)");
        check(expectedVersion.equals(release),
                "loaded GDAL " + release + " != gdal-config " + expectedVersion);
        check(gdal.GetDriverCount() > 0, "no drivers registered");
        final Driver gtiff = gdal.GetDriverByName("GTiff");
        check(gtiff != null, "GTiff driver missing");

        // 2. GeoTIFF round-trip: 64x64 byte, EPSG:4326, gradient
        final int w = 64, h = 64;
        final File tif = new File(scratch, "smoke.tif");
        final SpatialReference wgs84 = new SpatialReference();
        check(wgs84.ImportFromEPSG(4326) == 0, "ImportFromEPSG(4326) failed");
        final double[] gt = {-122.5, 0.1 / w, 0, 37.8, 0, -0.1 / h};
        {
            final Dataset ds = gtiff.Create(tif.getPath(), w, h, 1, gdalconstConstants.GDT_Byte);
            check(ds != null, "GTiff Create failed: " + gdal.GetLastErrorMsg());
            ds.SetGeoTransform(gt);
            check(ds.SetProjection(wgs84.ExportToWkt()) == 0, "SetProjection failed");
            final byte[] px = new byte[w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    px[y * w + x] = (byte) ((x + y) & 0xff);
            final Band b = ds.GetRasterBand(1);
            check(b.WriteRaster(0, 0, w, h, gdalconstConstants.GDT_Byte, px) == 0,
                    "WriteRaster failed: " + gdal.GetLastErrorMsg());
            ds.delete();   // flush + close
        }
        {
            final Dataset ds = gdal.Open(tif.getPath(), gdalconstConstants.GA_ReadOnly);
            check(ds != null, "reopen failed: " + gdal.GetLastErrorMsg());
            check(ds.getRasterXSize() == w && ds.getRasterYSize() == h,
                    "size " + ds.getRasterXSize() + "x" + ds.getRasterYSize());
            final double[] got = ds.GetGeoTransform();
            for (int i = 0; i < 6; i++)
                check(Math.abs(got[i] - gt[i]) < 1e-9, "geotransform[" + i + "] " + got[i] + " != " + gt[i]);
            final String wkt = ds.GetProjectionRef();
            check(wkt != null && wkt.contains("4326"), "projection lost EPSG 4326: " + wkt);
            final byte[] px = new byte[w * h];
            check(ds.GetRasterBand(1).ReadRaster(0, 0, w, h, gdalconstConstants.GDT_Byte, px) == 0,
                    "ReadRaster failed: " + gdal.GetLastErrorMsg());
            check((px[10 * w + 20] & 0xff) == 30, "pixel(20,10)=" + (px[10 * w + 20] & 0xff) + " != 30");
            System.out.println("gdal-java-smoke: GTiff round-trip OK " + w + "x" + h
                    + " gt[0]=" + got[0] + " gt[3]=" + got[3] + " srs=EPSG:4326");
            ds.delete();
        }

        // 3. axis order, GDAL 3: the engine's GdalDatasetProjection2 does
        //    geo2proj.TransformPoint(lon, lat) on an EPSG:4326 built exactly
        //    like `wgs84` above, with no axis-mapping strategy set.
        final SpatialReference merc = new SpatialReference();
        check(merc.ImportFromEPSG(3857) == 0, "ImportFromEPSG(3857) failed");
        final double lon = -122.4, lat = 37.7;
        final double expX = -13625000, expY = 4538000, tol = 10000;   // EPSG:3857 of (-122.4, 37.7)
        final double[] asIs = new CoordinateTransformation(wgs84, merc).TransformPoint(lon, lat);
        final boolean asIsOk = Math.abs(asIs[0] - expX) < tol && Math.abs(asIs[1] - expY) < tol;
        System.out.println(String.format(
                "gdal-java-smoke: axis-order as-engine (ImportFromEPSG(4326), TransformPoint(lon,lat)): "
                + "strategy=%d → (%.0f, %.0f) %s",
                wgs84.GetAxisMappingStrategy(), asIs[0], asIs[1],
                asIsOk ? "= expected (lon,lat honoured)" : "!= expected (lat,lon authority order)"));
        final SpatialReference trad = new SpatialReference();
        trad.ImportFromEPSG(4326);
        trad.SetAxisMappingStrategy(osrConstants.OAMS_TRADITIONAL_GIS_ORDER);
        final SpatialReference mercTrad = new SpatialReference();
        mercTrad.ImportFromEPSG(3857);
        mercTrad.SetAxisMappingStrategy(osrConstants.OAMS_TRADITIONAL_GIS_ORDER);
        final double[] fixed = new CoordinateTransformation(trad, mercTrad).TransformPoint(lon, lat);
        final boolean fixedOk = Math.abs(fixed[0] - expX) < tol && Math.abs(fixed[1] - expY) < tol;
        System.out.println(String.format(
                "gdal-java-smoke: axis-order with OAMS_TRADITIONAL_GIS_ORDER: → (%.0f, %.0f) %s",
                fixed[0], fixed[1], fixedOk ? "= expected" : "!= expected"));
        check(fixedOk, "EPSG:4326→3857 wrong even with OAMS_TRADITIONAL_GIS_ORDER — PROJ data missing?");
        System.out.println("gdal-java-smoke: axis-order verdict: "
                + (asIsOk ? "engine construction is SAFE on this GDAL"
                          : "engine construction FLIPS axes on this GDAL — GdalLibrary needs OAMS_TRADITIONAL_GIS_ORDER (G2.4 b.2)"));

        System.out.println("gdal-java-smoke: PASS");
    }
}
