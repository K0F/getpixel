package org.getpixel.magnifier;

/** Pure colourimetry (sRGB -> CIE XYZ -> CIE L*u*v*, CCT white points).
 *  No Android deps so it runs under plain javac in the pure-logic tests. */
public final class ColorMath {

    private ColorMath() {}

    /** sRGB (D65) -> CIE 1931 2° XYZ matrix, rows R,G,B (Y in [0,1]). */
    private static final double[][] SRGB_XYZ = {
            {0.4124564, 0.3575761, 0.1804375},
            {0.2126729, 0.7151522, 0.0721750},
            {0.0193339, 0.1191920, 0.9503041}
    };

    /** Inverse (XYZ -> linear sRGB). */
    public static final double[][] SRGB_XYZ_INV = {
            {3.2404542, -1.5371385, -0.4985314},
            {-0.9692660, 1.8760108, 0.0415560},
            {0.0556434, -0.2040259, 1.0572252}
    };

    /** CIE D65 reference white, Yn = 1. */
    public static final double[] D65_WHITE = {0.95047, 1.0, 1.08883};

    /** Bradford CAT primaries and their inverse. */
    private static final double[][] BRADFORD = {
            {0.8951, 0.2664, -0.1614},
            {-0.7502, 1.7135, 0.0367},
            {0.0389, -0.0685, 1.0296}
    };
    private static final double[][] BRADFORD_INV = {
            {0.9869929, -0.1470543, 0.1599627},
            {0.4323053, 0.5183603, 0.0492912},
            {-0.0085287, 0.0400428, 0.9684867}
    };

    /** Inverse of the sRGB EOTF; c in [0,1]. */
    public static double srgbToLinear(double c) {
        if (c < 0.0) c = 0.0;
        if (c > 1.0) c = 1.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** sRGB OETF; c in [0,1]. */
    public static double linearToSrgb(double c) {
        if (c < 0.0) c = 0.0;
        if (c > 1.0) c = 1.0;
        return c <= 0.0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055;
    }

    /** 8-bit sRGB channels -> linear [0,1] triple. */
    public static double[] linearRgb(int r8, int g8, int b8) {
        return new double[]{srgbToLinear(r8 / 255.0), srgbToLinear(g8 / 255.0), srgbToLinear(b8 / 255.0)};
    }

    /** Linear Bradford chromatic adaptation: srcWhite-spaced XYZ -> dstWhite-spaced XYZ. */
    public static double[] bradford(double[] xyz, double[] srcWhite, double[] dstWhite) {
        double[] l = new double[3];
        double[] o = new double[3];
        for (int i = 0; i < 3; i++) {
            l[i] = mul(BRADFORD[i], xyz);
            o[i] = mul(BRADFORD[i], srcWhite);
        }
        double[] o2 = new double[3];
        for (int i = 0; i < 3; i++) o2[i] = mul(BRADFORD[i], dstWhite);
        double[] adapted = new double[3];
        for (int i = 0; i < 3; i++) adapted[i] = o2[i] > 0 ? l[i] * (o2[i] / o[i]) : 0.0;
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            out[i] = mul(BRADFORD_INV[i], adapted);
        }
        return out;
    }

    private static double mul(double[] row, double[] v) {
        return row[0] * v[0] + row[1] * v[1] + row[2] * v[2];
    }

    /** Linear RGB -> CIE XYZ (Y in [0,1]). */
    public static double[] linearToXyz(double[] lin) {
        double[] xyz = new double[3];
        for (int i = 0; i < 3; i++) {
            xyz[i] = SRGB_XYZ[i][0] * lin[0] + SRGB_XYZ[i][1] * lin[1] + SRGB_XYZ[i][2] * lin[2];
        }
        return xyz;
    }

    /** CIE XYZ -> linear sRGB (D65 viewing). */
    public static double[] xyzToLinearRgb(double[] xyz) {
        double[] rgb = new double[3];
        for (int i = 0; i < 3; i++) {
            rgb[i] = SRGB_XYZ_INV[i][0] * xyz[0] + SRGB_XYZ_INV[i][1] * xyz[1] + SRGB_XYZ_INV[i][2] * xyz[2];
        }
        return rgb;
    }

    /** CIE XYZ -> 0xFFRRGGBB sRGB (D65 viewing), channels clamped. */
    public static int xyzToArgb(double[] xyz) {
        double[] lin = xyzToLinearRgb(xyz);
        int r = (int) Math.round(linearToSrgb(lin[0]) * 255);
        int g = (int) Math.round(linearToSrgb(lin[1]) * 255);
        int b = (int) Math.round(linearToSrgb(lin[2]) * 255);
        if (r < 0) r = 0;
        if (g < 0) g = 0;
        if (b < 0) b = 0;
        if (r > 255) r = 255;
        if (g > 255) g = 255;
        if (b > 255) b = 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** ARGB int -> CIE XYZ. */
    public static double[] argbToXyz(int argb) {
        return linearToXyz(linearRgb((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF));
    }

    private static double lStarOf(double yRatio) {
        if (yRatio > 0.008856451679035631) return 116.0 * Math.cbrt(yRatio) - 16.0;
        return yRatio * 903.2962962962963;
    }

    /** CIE XYZ -> CIE 1976 (L*, u*, v*) relative to the reference white point. */
    public static double[] xyzToLuv(double[] xyz, double[] white) {
        double den = xyz[0] + 15.0 * xyz[1] + 3.0 * xyz[2];
        double dw = white[0] + 15.0 * white[1] + 3.0 * white[2];
        double u = den > 0 ? 4.0 * xyz[0] / den : 0.0;
        double v = den > 0 ? 9.0 * xyz[1] / den : 0.0;
        double un = dw > 0 ? 4.0 * white[0] / dw : 0.0;
        double vn = dw > 0 ? 9.0 * white[1] / dw : 0.0;
        double l = lStarOf(xyz[1] / white[1]);
        if (l <= 0.0) return new double[]{Math.max(0.0, l), 0.0, 0.0};
        return new double[]{l, 13.0 * l * (u - un), 13.0 * l * (v - vn)};
    }

    /** Convenience: ARGB -> L*u*v* under a white point. */
    public static double[] argbToLuv(int argb, double[] white) {
        return xyzToLuv(argbToXyz(argb), white);
    }

    /** xy chromaticity (sum=1) -> XYZ normalized so Y = 1. */
    public static double[] xyToXyz(double x, double y) {
        return new double[]{x / y, 1.0, (1.0 - x - y) / y};
    }

    /** Planckian-locus white point (blackbody) for a CCT in Kelvin, via Krystek's
     *  closed-form approximation. Returns XYZ normalized so Y = 1. */
    public static double[] cctToWhite(int kelvin) {
        if (kelvin < 1667) kelvin = 1667;
        if (kelvin > 25000) kelvin = 25000;
        double t = kelvin;
        double x;
        if (t >= 4000) {
            x = -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390;
        } else {
            x = -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910;
        }
        double x2 = x * x;
        double x3 = x2 * x;
        double y;
        if (t < 2222) {
            y = -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * x - 0.20219683;
        } else if (t < 4000) {
            y = -0.9549476 * x3 - 1.37418593 * x2 + 2.09137015 * x - 0.16748867;
        } else {
            y = 3.0817580 * x3 - 5.87338670 * x2 + 3.75112997 * x - 0.37001483;
        }
        return xyToXyz(x, y);
    }

    /** Formats a number with `decimals` places in the same way for tests/UI. */
    public static String fmt(double d, int decimals) {
        return String.format(java.util.Locale.US, "%." + decimals + "f", d);
    }
}