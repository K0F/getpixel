package org.getpixel.magnifier;

import java.util.ArrayList;
import java.util.List;

/** True-colorimetric light/sensor profile fitted from card patch taps.
 *
 *  With >=3 samples the profile is a least-squares 3x3 matrix mapping the
 *  linearized camera RGB to CIE XYZ *relative to the scene illuminant*: the
 *  ColorChecker's published D65 xyY values are chromatically adapted (Bradford)
 *  to the user's light source, so the fit only absorbs sensor/response faults
 *  while the illuminant itself is handled through the matrix and the L*u*v*
 *  white point. Display colour is the object's appearance under D65; XYZ/L*u*v*
 *  stay true to the measured illuminant. With fewer samples only per-channel
 *  white-balance gains (in linear RGB) are fitted.
 *
 *  No Android deps. */
public final class Calibration {

    /** SpotResolution of the classic X-Rite ColorChecker 24, as sRGB 8-bit under D65. */
    public static final int[][] REF_CC24_RGB = {
            {115, 82, 68},   //  1 dark skin
            {194, 150, 130}, //  2 light skin
            {98, 122, 157},  //  3 blue sky
            {87, 108, 67},   //  4 foliage
            {133, 128, 177}, //  5 blue flower
            {103, 189, 170}, //  6 bluish green
            {214, 126, 44},  //  7 orange
            {80, 91, 166},   //  8 purplish blue
            {193, 90, 99},   //  9 moderate red
            {94, 60, 108},   // 10 purple
            {157, 188, 26},  // 11 yellow green
            {224, 163, 46},  // 12 orange yellow
            {56, 61, 150},   // 13 blue
            {70, 148, 73},   // 14 green
            {175, 54, 60},   // 15 red
            {231, 199, 31},  // 16 yellow
            {187, 86, 149},  // 17 magenta
            {8, 133, 161},   // 18 cyan
            {243, 243, 242}, // 19 white
            {200, 200, 200}, // 20 neutral 8
            {160, 160, 160}, // 21 neutral 6.5
            {122, 122, 121}, // 22 neutral 5
            {85, 85, 85},    // 23 neutral 3.5
            {52, 52, 52}     // 24 black
    };

    /** CIE xyY of each ColorChecker patch under D65 (Pascale/BabelColor, Y = luminance factor). */
    public static final double[][] REF_CC24_XY_D65 = {
            {0.4002, 0.3504, 0.1005},
            {0.3773, 0.3446, 0.3578},
            {0.2470, 0.2514, 0.1954},
            {0.3372, 0.4220, 0.0951},
            {0.2651, 0.2400, 0.3024},
            {0.2608, 0.3430, 0.5461},
            {0.5060, 0.4070, 0.2702},
            {0.2113, 0.1753, 0.2026},
            {0.5003, 0.3087, 0.1687},
            {0.2849, 0.2027, 0.1408},
            {0.3824, 0.4887, 0.4061},
            {0.4711, 0.4046, 0.4823},
            {0.1456, 0.0673, 0.0647},
            {0.2816, 0.5497, 0.2172},
            {0.5081, 0.3039, 0.1254},
            {0.4029, 0.4620, 0.6121},
            {0.3431, 0.2221, 0.1724},
            {0.1596, 0.2397, 0.4281},
            {0.3127, 0.3290, 0.8325},
            {0.3138, 0.3309, 0.5550},
            {0.3143, 0.3308, 0.3469},
            {0.3176, 0.3351, 0.1830},
            {0.3205, 0.3374, 0.0876},
            {0.3115, 0.3391, 0.0463}
    };

    private static final int NEUTRAL_TOL = 10;
    private static final int MIN_NEUTRAL_VALUE = 90;

    private final List<double[]> measured = new ArrayList<>();   // linear RGB taps
    private final List<double[]> reference = new ArrayList<>();  // XYZ relative to illuminant white
    private final List<Integer> refIdx = new ArrayList<>();      // tapped CC24 patch for the WB path
    private double[][] matrix = IDENTITY();                      // linear RGB -> XYZ (colorimetric) or RGB->RGB (legacy WB)
    private double[] fitWhite;                                   // illuminant white (Yn = 1) the XYZ fit is relative to
    private boolean xyzModel;
    private boolean fitted;

    private static double[][] IDENTITY() {
        return new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    }

    public int samples() {
        return measured.size();
    }

    public boolean isFitted() {
        return fitted;
    }

    /** The illuminant white the colorimetric fit is relative to (null for WB-only fits). */
    public double[] white() {
        return fitWhite == null ? null : new double[]{fitWhite[0], fitWhite[1], fitWhite[2]};
    }

    public void addSample(int measuredArgb, int referenceIndex, double[] illuminantWhite) {
        measured.add(ColorMath.linearRgb((measuredArgb >> 16) & 0xFF, (measuredArgb >> 8) & 0xFF, measuredArgb & 0xFF));
        reference.add(referenceXyz(referenceIndex, illuminantWhite));
        refIdx.add(referenceIndex);
        fit(illuminantWhite);
    }

    public void popSample() {
        if (samples() == 0) return;
        measured.remove(measured.size() - 1);
        reference.remove(reference.size() - 1);
        refIdx.remove(refIdx.size() - 1);
        fit(fitWhite);
    }

    public void clear() {
        measured.clear();
        reference.clear();
        refIdx.clear();
        matrix = IDENTITY();
        fitWhite = null;
        xyzModel = false;
        fitted = false;
    }

    /** Reference XYZ (under illuminantWhite) with Y normalized so the best-white tap maps to 1. */
    private double[] referenceXyz(int patch, double[] white) {
        double[] xy = REF_CC24_XY_D65[patch];
        double Y = xy[2];
        double[] xyz = {Y * xy[0] / xy[1], Y, Y * (1.0 - xy[0] - xy[1]) / xy[1]};
        if (white != null) {
            xyz = ColorMath.bradford(xyz, ColorMath.D65_WHITE, white);
        }
        return xyz;
    }

    private void fit(double[] white) {
        if (samples() == 0) {
            clear();
            return;
        }
        if (samples() < 3) {
            fitDiagonal();
            return;
        }
        fitMatrix(white);
    }

    /** Per-channel white-balance gains (1-2 patches), in linear RGB. */
    private void fitDiagonal() {
        double gr = 0, gg = 0, gb = 0;
        int n = samples();
        for (int i = 0; i < n; i++) {
            double[] r = refRgb(i);
            double[] m = measured.get(i);
            gr += gain(m[0], r[0]);
            gg += gain(m[1], r[1]);
            gb += gain(m[2], r[2]);
        }
        gr /= n;
        gg /= n;
        gb /= n;
        matrix = new double[][]{{gr, 0, 0}, {0, gg, 0}, {0, 0, gb}};
        fitWhite = null;
        xyzModel = false;
        fitted = true;
    }

    /** Reference sRGB (D65 chart encoding) of tap i, linearized; used for the WB path. */
    private double[] refRgb(int i) {
        int[] c = REF_CC24_RGB[refIdx.get(i)];
        return ColorMath.linearRgb(c[0], c[1], c[2]);
    }

    private static double gain(double measuredLin, double refLin) {
        double m = Math.max(measuredLin, 2.0 / 255.0);
        double g = refLin / m;
        if (g < 0.02) g = 0.02;
        if (g > 50.0) g = 50.0;
        return g;
    }

    /** Least-squares 3x3 matrix fit linear RGB -> relative XYZ (normal equations, Gaussian elimination). */
    private void fitMatrix(double[] white) {
        double[][] aTa = new double[3][3];
        double[] aTbX = new double[3];
        double[] aTbY = new double[3];
        double[] aTbZ = new double[3];
        int n = samples();
        double scale = 1.0 / maxRefY();
        for (int i = 0; i < n; i++) {
            double[] x = measured.get(i);
            double[] y = reference.get(i);
            for (int p = 0; p < 3; p++) {
                aTbX[p] += x[p] * y[0] * scale;
                aTbY[p] += x[p] * y[1] * scale;
                aTbZ[p] += x[p] * y[2] * scale;
                for (int q = 0; q < 3; q++) {
                    aTa[p][q] += x[p] * x[q];
                }
            }
        }
        double[][] mx = solve3(aTa, aTbX);
        double[][] my = solve3(aTa, aTbY);
        double[][] mz = solve3(aTa, aTbZ);
        matrix = new double[][]{
                {mx[0][0], mx[1][0], mx[2][0]},
                {my[0][0], my[1][0], my[2][0]},
                {mz[0][0], mz[1][0], mz[2][0]}
        };
        fitWhite = white == null ? new double[]{ColorMath.D65_WHITE[0], 1, 1.08883} : new double[]{white[0], 1, white[2]};
        xyzModel = true;
        fitted = true;
    }

    /** Largest reference Y among the tapped patches; its inverse normalizes the display. */
    private double maxRefY() {
        double y = 0;
        for (double[] r : reference) {
            if (r[1] > y) y = r[1];
        }
        return y > 0 ? y : 1.0;
    }

    /** Solves A x = b (3x3 real) for the 3 unknown coefficients of one output row. */
    private static double[][] solve3(double[][] a, double[] b) {
        double[][] m = new double[3][4];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(a[i], 0, m[i], 0, 3);
            m[i][3] = b[i];
        }
        for (int col = 0; col < 3; col++) {
            int piv = col;
            for (int r = col + 1; r < 3; r++) {
                if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r;
            }
            double[] tmp = m[piv];
            m[piv] = m[col];
            m[col] = tmp;
            double d = m[col][col];
            if (Math.abs(d) < 1e-9) d = (d < 0) ? -1e-9 : 1e-9;
            for (int c = col; c < 4; c++) m[col][c] /= d;
            for (int r = 0; r < 3; r++) {
                if (r == col) continue;
                double f = m[r][col];
                for (int c = col; c < 4; c++) m[r][c] -= f * m[col][c];
            }
        }
        return new double[][]{{m[0][3]}, {m[1][3]}, {m[2][3]}};
    }

    /** Applies the fitted profile to a linear-RGB triple, returning linear RGB (D65 appearance). */
    public double[] applyLinear(double[] lin) {
        if (xyzModel) {
            double[] xyz = mulMatrix(lin);
            double[] xyzD65 = ColorMath.bradford(xyz, fitWhite, ColorMath.D65_WHITE);
            return ColorMath.xyzToLinearRgb(xyzD65);
        }
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            out[i] = matrix[i][0] * lin[0] + matrix[i][1] * lin[1] + matrix[i][2] * lin[2];
            if (out[i] < 0) out[i] = 0;
            if (out[i] > 1) out[i] = 1;
        }
        return out;
    }

    private double[] mulMatrix(double[] lin) {
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            out[i] = matrix[i][0] * lin[0] + matrix[i][1] * lin[1] + matrix[i][2] * lin[2];
            if (out[i] < 0) out[i] = 0;
        }
        return out;
    }

    /** True-colorimetric XYZ of a captured colour, relative to the illuminant white (Y = 1 for the white tap). */
    public double[] applyXyz(int argb) {
        double[] lin = ColorMath.linearRgb((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
        if (xyzModel) return mulMatrix(lin);
        return ColorMath.linearToXyz(applyLinear(lin));
    }

    /** Applies the fitted profile to an ARGB colour, returning the corrected ARGB (D65 appearance). */
    public int apply(int argb) {
        double[] lin = ColorMath.linearRgb(
                (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
        double[] out = applyLinear(lin);
        int r = (int) Math.round(ColorMath.linearToSrgb(out[0]) * 255);
        int g = (int) Math.round(ColorMath.linearToSrgb(out[1]) * 255);
        int b = (int) Math.round(ColorMath.linearToSrgb(out[2]) * 255);
        if (r < 0) r = 0;
        if (g < 0) g = 0;
        if (b < 0) b = 0;
        if (r > 255) r = 255;
        if (g > 255) g = 255;
        if (b > 255) b = 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Fitted XYZ of the scene's best-white tap, after profiling — usable as the scene
     *  illuminant white point. Scale is the relative Y of the whitest tap. */
    public double[] measuredWhiteXyz() {
        int best = -1;
        double bestY = -1;
        for (int i = 0; i < reference.size(); i++) {
            if (reference.get(i)[1] > bestY) {
                bestY = reference.get(i)[1];
                best = i;
            }
        }
        if (best < 0) return null;
        double[] lin = measured.get(best);
        if (xyzModel) return mulMatrix(lin);
        double[] rgb = applyLinear(lin);
        return ColorMath.linearToXyz(rgb);
    }

    /** Serializable profile, "" when none fitted. "m" = legacy WB/RGB matrix, "z" = colorimetric XYZ. */
    public String encode() {
        if (!fitted) return "";
        StringBuilder sb = new StringBuilder(xyzModel ? "z" : "m");
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                sb.append(';').append(ColorMath.fmt(matrix[r][c], 5));
            }
        }
        if (xyzModel && fitWhite != null) {
            sb.append(';').append(ColorMath.fmt(fitWhite[0], 5))
                    .append(';').append(ColorMath.fmt(fitWhite[1], 5))
                    .append(';').append(ColorMath.fmt(fitWhite[2], 5));
        }
        return sb.toString();
    }

    public static Calibration decode(String encoded) {
        Calibration cal = new Calibration();
        if (encoded == null || encoded.isEmpty()) return cal;
        String[] parts = encoded.split(";");
        if (parts.length >= 10 && (parts[0].equals("m") || parts[0].equals("z"))) {
            double[][] m = new double[3][3];
            try {
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) {
                        m[r][c] = Double.parseDouble(parts[1 + r * 3 + c]);
                    }
                }
                cal.matrix = m;
                cal.xyzModel = parts[0].equals("z");
                if (cal.xyzModel && parts.length >= 13) {
                    cal.fitWhite = new double[]{
                            Double.parseDouble(parts[10]),
                            Double.parseDouble(parts[11]),
                            Double.parseDouble(parts[12])};
                }
                cal.fitted = true;
            } catch (NumberFormatException ignored) {
                cal.matrix = IDENTITY();
                cal.fitted = false;
            }
        }
        return cal;
    }

    public double[][] matrix() {
        double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++) System.arraycopy(matrix[i], 0, out[i], 0, 3);
        return out;
    }

    /** Whether the fitted profile is the colorimetric (XYZ) model. */
    public boolean isColorimetric() {
        return xyzModel;
    }

    /** 8-bit reference channels of patch i (0-based). */
    public int[] reference(int i) {
        return REF_CC24_RGB[i];
    }

    /** ARGB swatch colour of reference patch i (0-based). */
    public static int refHexArgb(int i) {
        int[] c = REF_CC24_RGB[i];
        return 0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2];
    }

    public static int refCount() {
        return REF_CC24_RGB.length;
    }
}