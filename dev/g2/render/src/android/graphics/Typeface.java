package android.graphics;

import java.awt.Font;
import java.awt.GraphicsEnvironment;

/** macOS runtime for {@code android.graphics.Typeface}, backed by {@link java.awt.Font}. */
public class Typeface {
    public static final int NORMAL = 0;
    public static final int BOLD = 1;
    public static final int ITALIC = 2;
    public static final int BOLD_ITALIC = 3;

    public static final Typeface DEFAULT = new Typeface(Font.SANS_SERIF, NORMAL);
    public static final Typeface DEFAULT_BOLD = new Typeface(Font.SANS_SERIF, BOLD);
    public static final Typeface SANS_SERIF = DEFAULT;
    public static final Typeface SERIF = new Typeface(Font.SERIF, NORMAL);
    public static final Typeface MONOSPACE = new Typeface(Font.MONOSPACED, NORMAL);

    final String family;
    final int style;

    private Typeface(String family, int style) { this.family = family; this.style = style; }

    public int getStyle() { return style; }
    public boolean isBold() { return (style & BOLD) != 0; }
    public boolean isItalic() { return (style & ITALIC) != 0; }

    public static Typeface create(String familyName, int style) {
        String fam = Font.SANS_SERIF;
        if (familyName != null) {
            if (familyName.equalsIgnoreCase("monospace")) fam = Font.MONOSPACED;
            else if (familyName.equalsIgnoreCase("serif")) fam = Font.SERIF;
            else if (!familyName.equalsIgnoreCase("sans-serif") && isInstalled(familyName)) fam = familyName;
        }
        return new Typeface(fam, style & BOLD_ITALIC);
    }

    public static Typeface create(Typeface family, int style) {
        return new Typeface(family != null ? family.family : Font.SANS_SERIF, style & BOLD_ITALIC);
    }

    public static Typeface defaultFromStyle(int style) { return create(DEFAULT, style); }

    /** AWT font at the given pixel size (Java2D font sizes are in user-space pixels here). */
    Font awtFont(float sizePx) {
        int awtStyle = (isBold() ? Font.BOLD : 0) | (isItalic() ? Font.ITALIC : 0);
        return new Font(family, awtStyle, 1).deriveFont(sizePx);
    }

    private static boolean isInstalled(String name) {
        for (String f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
            if (f.equalsIgnoreCase(name)) return true;
        return false;
    }

    @Override public boolean equals(Object o) {
        return o instanceof Typeface && ((Typeface) o).style == style && ((Typeface) o).family.equals(family);
    }

    @Override public int hashCode() { return family.hashCode() * 31 + style; }

    @Override public String toString() { return "Typeface(" + family + ", style=" + style + ")"; }
}
