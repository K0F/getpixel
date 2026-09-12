package org.getpixel.magnifier;

/** Pure colour helpers shared with the CLI output format. No Android deps. */
public final class ColorUtil {

    private ColorUtil() {}

    /** Returns {A, R, G, B} components of an ARGB int. */
    public static int[] argb(int color) {
        return new int[]{
                (color >> 24) & 0xFF,
                (color >> 16) & 0xFF,
                (color >> 8) & 0xFF,
                color & 0xFF
        };
    }

    /** "#RRGGBB" (alpha intentionally dropped, matching the CLI). */
    public static String hex(int color) {
        return String.format("#%02X%02X%02X", (color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF);
    }

    /** Lowercase "#rrggbb" (CSS/HTML style), as saved into the palette. */
    public static String hexLc(int color) {
        return String.format("#%02x%02x%02x", (color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF);
    }

    /** Same textual report as the `get_pixel` CLI. */
    public static String report(int x, int y, int color) {
        int[] c = argb(color);
        return String.format("Pixel at (%d, %d) -> R:%d G:%d B:%d A:%d (Hex: %s)",
                x, y, c[1], c[2], c[3], c[0], hex(color));
    }

    /** Expands an RGB_565 little-endian word to ARGB. */
    public static int fromRgb565(int rgb565) {
        int r = ((rgb565 >> 11) & 0x1F) * 255 / 31;
        int g = ((rgb565 >> 5) & 0x3F) * 255 / 63;
        int b = (rgb565 & 0x1F) * 255 / 31;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** True when the ARGB value is fully opaque (captured screen pixels are). */
    public static boolean isOpaque(int color) {
        return ((color >> 24) & 0xFF) == 0xFF;
    }
}