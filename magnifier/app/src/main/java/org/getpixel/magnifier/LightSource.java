package org.getpixel.magnifier;

/** A lighting condition represented by its reference white point for CIE L*u*v*.
 *  No Android deps. Presets cover common viewing conditions incl. the "BST1"
 *  studio light, whose white point is measured from a card white patch when a
 *  calibration has been fitted, else estimated from a blackbody CCT. */
public final class LightSource {

    public final String name;
    public final double xn;   // X of the white point, Yn normalized to 1
    public final double yn;
    public final double zn;

    public LightSource(String name, double xn, double yn, double zn) {
        this.name = name;
        this.xn = xn;
        this.yn = yn;
        this.zn = zn;
    }

    public double[] white() {
        return new double[]{xn, yn, zn};
    }

    /** Estimate used for "BST1" until a card white patch overwrites it. */
    public static final int BST1_CCT_KELVIN = 5000;

    public static LightSource bst1Estimate() {
        return bst1(BST1_CCT_KELVIN);
    }

    /** Blackbody-BST1 with the "BST1" label regardless of the CCT used. */
    public static LightSource bst1(int kelvin) {
        double[] w = ColorMath.cctToWhite(kelvin);
        return new LightSource("BST1", w[0], 1.0, w[2]);
    }

    public static LightSource cct(int kelvin) {
        double[] w = ColorMath.cctToWhite(kelvin);
        return new LightSource("CCT " + kelvin / 1000 + "k", w[0], 1.0, w[2]);
    }

    public static final LightSource D65 = new LightSource("D65", 0.95047, 1.0, 1.08883);
    public static final LightSource D50 = new LightSource("D50", 0.96422, 1.0, 0.82521);
    public static final LightSource A = new LightSource("A", 1.09850, 1.0, 0.35585);

    /** Measured white (from the calibrated card), used as the BST1 scene white. */
    public static LightSource measuredWhite(double[] xyz, String label) {
        double y = xyz[1];
        if (y <= 0) return bst1Estimate();
        return new LightSource(label == null ? "BST1" : label, xyz[0] / y, 1.0, xyz[2] / y);
    }

    /** Short label keys for the persistence/cycling UI. */
    public static final String[] PRESET_KEYS = {"d65", "d50", "a", "bst1"};

    public static LightSource preset(int index, LightSource currentBst1) {
        switch (((index % 4) + 4) % 4) {
            case 1:
                return D50;
            case 2:
                return A;
            case 3:
                return currentBst1 != null ? currentBst1 : bst1Estimate();
            default:
                return D65;
        }
    }
}