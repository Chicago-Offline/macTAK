package mactak.render;

/**
 * How many content pixels a frame needs before the render smokes call it
 * drawn. Shared by {@link RenderSmoke} and {@link WindowApp} so the poll
 * loop and the final verdict cannot disagree.
 *
 * <p>A vector layer alone needs one pixel. A basemap needs to cover the
 * frame: "more than zero" is satisfied by the vector layer on top of it
 * (23890 of 262144 px for the sf-landmarks fixture), so a basemap that
 * never rendered still passed. The smokes frame the camera inside the
 * basemap's coverage, where a working basemap fills every pixel; half the
 * frame is far above what the vectors light and leaves room for tiles
 * that are still loading at the edge.
 */
final class ContentGate {

    /** Share of the frame a basemap must cover. */
    static final double BASEMAP_MIN_COVER = 0.5;

    private ContentGate() { }

    /** Content pixels a {@code w}x{@code h} frame must hold; 0 with nothing attached. */
    static long requiredPixels(int w, int h, boolean hasLayers, boolean hasBasemaps) {
        if (hasBasemaps) return (long) Math.ceil((long) w * h * BASEMAP_MIN_COVER);
        return hasLayers ? 1 : 0;
    }

    /** The FAIL line for a frame with {@code content} of {@code required} pixels. */
    static String failure(long content, long required, int w, int h, boolean hasBasemaps) {
        if (!hasBasemaps) return "layer attached but frame has no content pixels";
        return String.format(java.util.Locale.US,
                "basemap attached but content covers %d of %d px (%.0f%%); need %d (%.0f%%)",
                content, (long) w * h, 100.0 * content / ((long) w * h),
                required, 100.0 * BASEMAP_MIN_COVER);
    }
}
