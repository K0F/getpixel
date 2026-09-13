package org.getpixel.magnifier;

import java.util.ArrayList;
import java.util.List;

/** Light-source profile fitted from card patch pairs (measured sRGB -> reference
 *  sRGB): with >=3 pairs a least-squares 3x3 matrix in the linear RGB domain,
 *  with fewer a per-channel white-balance gain. No Android deps. */
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

    private static final int NEUTRAL_TOL = 10;
    private static final int MIN_NEUTRAL_VALUE = 90;

    private final List<double[]> measured = new ArrayList<>(); // linear RGB
    private final List<double[]> reference = new ArrayList<>(); // linear RGB
    private double[][] matrix = IDENTITY();
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

    public void addSample(int measuredArgb, int referenceRgb) {
        measured.add(ColorMath.linearRgb((measuredArgb >> 16) & 0xFF, (measuredArgb >> 8) & 0xFF, measuredArgb & 0xFF));
        reference.add(ColorMath.linearRgb(
                (referenceRgb >> 16) & 0xFF, (referenceRgb >> 8) & 0xFF, referenceRgb & 0xFF));
        fit();
    }

    public void popSample() {
        if (samples() == 0) return;
        measured.remove(measured.size() - 1);
        reference.remove(reference.size() - 1);
        fit();
    }

    public void clear() {
        measured.clear();
        reference.clear();
        matrix = IDENTITY();
        fitted = false;
    }

    private void fit() {
        if (samples() == 0) {
            matrix = IDENTITY();
            fitted = false;
            return;
        }
        if (samples() < 3) {
            fitDiagonal();
            return;
        }
        fitMatrix();
        fitted = true;
    }

    /** Per-channel white-balance gains (1-2 patches, typically a neutral). */
    private void fitDiagonal() {
        double gr = 0, gg = 0, gb = 0;
        int n = samples();
        for (int i = 0; i < n; i++) {
            double[] m = measured.get(i);
            double[] r = reference.get(i);
            gr += gain(m[0], r[0]);
            gg += gain(m[1], r[1]);
            gb += gain(m[2], r[2]);
        }
        gr /= n;
        gg /= n;
        gb /= n;
        matrix = new double[][]{{gr, 0, 0}, {0, gg, 0}, {0, 0, gb}};
        fitted = true;
    }

    private static double gain(double measuredLin, double refLin) {
        double m = Math.max(measuredLin, 2.0 / 255.0);
        double g = refLin / m;
        if (g < 0.02) g = 0.02;
        if (g > 50.0) g = 50.0;
        return g;
    }

    /** Least-squares 3x3 matrix fit (linear RGB): min ||M x - y|| per row via
     *  the normal equations, solved with Gaussian elimination. */
    private void fitMatrix() {
        double[][] aTa = new double[3][3];
        double[] aTbR = new double[3];
        double[] aTbG = new double[3];
        double[] aTbB = new double[3];
        int n = samples();
        for (int i = 0; i < n; i++) {
            double[] x = measured.get(i);
            double[] y = reference.get(i);
            for (int p = 0; p < 3; p++) {
                aTbR[p] += x[p] * y[0];
                aTbG[p] += x[p] * y[1];
                aTbB[p] += x[p] * y[2];
                for (int q = 0; q < 3; q++) {
                    aTa[p][q] += x[p] * x[q];
                }
            }
        }
        double[][] mr = solve3(aTa, aTbR);
        double[][] mg = solve3(aTa, aTbG);
        double[][] mb = solve3(aTa, aTbB);
        matrix = new double[][]{
                {mr[0][0], mr[1][0], mr[2][0]},
                {mg[0][0], mg[1][0], mg[2][0]},
                {mb[0][0], mb[1][0], mb[2][0]}
        };
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

    /** Applies the fitted profile to a linear-RGB triple. */
    public double[] applyLinear(double[] lin) {
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            out[i] = matrix[i][0] * lin[0] + matrix[i][1] * lin[1] + matrix[i][2] * lin[2];
            if (out[i] < 0) out[i] = 0;
            if (out[i] > 1) out[i] = 1;
        }
        return out;
    }

    /** Applies the fitted profile to an ARGB colour, returning the corrected ARGB. */
    public int apply(int argb) {
        double[] lin = applyLinear(ColorMath.linearRgb(
                (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF));
        int r = (int) Math.round(ColorMath.linearToSrgb(lin[0]) * 255);
        int g = (int) Math.round(ColorMath.linearToSrgb(lin[1]) * 255);
        int b = (int) Math.round(ColorMath.linearToSrgb(lin[2]) * 255);
        if (r < 0) r = 0;
        if (g < 0) g = 0;
        if (b < 0) b = 0;
        if (r > 255) r = 255;
        if (g > 255) g = 255;
        if (b > 255) b = 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Fitted XYZ of the scene's white patch (the most neutral reference sample),
     *  after profiling — usable as the scene illuminant white point. Null when no
     *  neutral sample was captured. */
    public double[] measuredWhiteXyz() {
        int best = -1;
        int bestSkew = Integer.MAX_VALUE;
        for (int i = 0; i < reference.size(); i++) {
            int[] c = reference(i);
            if (c[0] < MIN_NEUTRAL_VALUE) continue;
            int skew = Math.max(Math.abs(c[0] - c[1]), Math.abs(c[1] - c[2]));
            if (skew <= NEUTRAL_TOL && skew < bestSkew) {
                bestSkew = skew;
                best = i;
            }
        }
        if (best < 0) return null;
        return ColorMath.linearToXyz(applyLinear(measured.get(best)));
    }

    /** Serializable profile, "" when none fitted. */
    public String encode() {
        if (!fitted) return "";
        StringBuilder sb = new StringBuilder("m");
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                sb.append(';').append(ColorMath.fmt(matrix[r][c], 5));
            }
        }
        return sb.toString();
    }

    public static Calibration decode(String encoded) {
        Calibration cal = new Calibration();
        if (encoded == null || encoded.isEmpty()) return cal;
        String[] parts = encoded.split(";");
        if (parts.length == 10 && parts[0].equals("m")) {
            double[][] m = new double[3][3];
            try {
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) {
                        m[r][c] = Double.parseDouble(parts[1 + r * 3 + c]);
                    }
                }
                cal.matrix = m;
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