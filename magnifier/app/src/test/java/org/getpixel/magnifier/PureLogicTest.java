package org.getpixel.magnifier;

/**
 * Tests for the Android-free logic classes (ColorUtil, LensMath, FrameBuffer).
 * Deliberately JUnit-free so it also runs standalone with plain javac/java:
 *     javac ColorUtil LensMath FrameBuffer PureLogicTest
 *     java org.getpixel.magnifier.PureLogicTest
 * Gradle runs it through LogicTestSuite.
 */
public final class PureLogicTest {

    private static int failures = 0;

    private static void eq(String expected, String actual, String name) {
        if (!expected.equals(actual)) {
            System.err.println("FAIL: " + name + " expected=" + expected + " actual=" + actual);
            failures++;
        }
    }

    private static void check(boolean cond, String name) {
        if (!cond) {
            System.err.println("FAIL: " + name);
            failures++;
        }
    }

    /** Runs every check; returns the failure count. */
    public static int runAll() {
        failures = 0;

        // --- ColorUtil -----------------------------------------------------
        int c = 0xFF1C3C06; // R=0x1C G=0x3C B=0x06
        eq("#1C3C06", ColorUtil.hex(c), "hex");
        check(ColorUtil.isOpaque(c), "opaque");
        check(!ColorUtil.isOpaque(0x00FFFFFF), "not opaque");

        int a = 0x80A0B0C0;
        int[] comps = ColorUtil.argb(a);
        eq("128", Integer.toString(comps[0]), "argb A");
        eq("160", Integer.toString(comps[1]), "argb R");
        eq("176", Integer.toString(comps[2]), "argb G");
        eq("192", Integer.toString(comps[3]), "argb B");

        int w565 = 0xF800 | 0x07E0 | 0x001F; // RGB_565 white
        eq("#FFFFFF", ColorUtil.hex(ColorUtil.fromRgb565(w565)), "rgb565 white");
        eq("#0000FF", ColorUtil.hex(ColorUtil.fromRgb565(0x001F)), "rgb565 blue");

        eq("Pixel at (2, 3) -> R:28 G:60 B:6 A:255 (Hex: #1C3C06)",
           ColorUtil.report(2, 3, 0xFF1C3C06), "report format");

        // --- LensMath ------------------------------------------------------
        int[] o = LensMath.cropOrigin(100, 100, 40, 40, 1000, 1000);
        eq("80", Integer.toString(o[0]), "crop origin left 100-20");
        eq("80", Integer.toString(o[1]), "crop origin top");

        o = LensMath.cropOrigin(5, 5, 40, 40, 1000, 1000);
        eq("0", Integer.toString(o[0]), "clamp left");
        eq("0", Integer.toString(o[1]), "clamp top");

        o = LensMath.cropOrigin(990, 990, 40, 40, 1000, 1000);
        eq("960", Integer.toString(o[0]), "clamp right");
        eq("960", Integer.toString(o[1]), "clamp bottom");

        o = LensMath.cropOrigin(50, 50, 2000, 2000, 100, 100);
        eq("0", Integer.toString(o[0]), "oversize crop left");
        eq("0", Integer.toString(o[1]), "oversize crop top");

        eq("107", Integer.toString(LensMath.cropForZoom(320, 3)), "cropForZoom 320/3");
        eq("1",   Integer.toString(LensMath.cropForZoom(10, 100)), "cropForZoom min 1");
        eq("1.0", Float.toString(LensMath.clampZoom(0.1f)), "clampZoom floor");
        eq("12.0",Float.toString(LensMath.clampZoom(99f)), "clampZoom cap");

        // --- FrameBuffer ---------------------------------------------------
        FrameBuffer fb = new FrameBuffer(8, 6);
        check(fb.pixelAt(0, 0) == 0, "empty buffer is transparent black");
        check( fb.inBounds(7, 5), "in bounds");
        check(!fb.inBounds(8, 0), "x out of bounds");
        check(!fb.inBounds(0, 6), "y out of bounds");
        check(!fb.inBounds(-1, -1), "negative out of bounds");

        byte[] row = new byte[8 * 4]; // row 0: R=x*10, G=10, B=5
        for (int x = 0; x < 8; x++) {
            row[x * 4]     = (byte) (x * 10);
            row[x * 4 + 1] = 10;
            row[x * 4 + 2] = 5;
            row[x * 4 + 3] = (byte) 0xFF;
        }
        fb.copyRow(0, row, 4, null);
        eq("FF140A05", Integer.toHexString(fb.pixelAt(2, 0)).toUpperCase(), "copyRow pixel (2,0)");
        eq("FF3C0A05", Integer.toHexString(fb.pixelAt(6, 0)).toUpperCase(), "copyRow pixel (6,0)");
        fb.copyRow(5, row, 4, null);
        eq("FF280A05", Integer.toHexString(fb.pixelAt(4, 5)).toUpperCase(), "copyRow last row");

        for (int y = 2; y < 4; y++) {
            for (int x = 0; x < 8; x++) {
                row[x * 4]     = (byte) x;
                row[x * 4 + 1] = (byte) y;
                row[x * 4 + 2] = 9;
                row[x * 4 + 3] = (byte) 0xFF;
            }
            fb.copyRow(y, row, 4, null);
        }

        int[] crop = new int[6];
        fb.copyRegion(1, 2, 3, 2, crop);
        eq("FF010209", Integer.toHexString(crop[0]).toUpperCase(), "crop (1,2)");
        eq("FF020209", Integer.toHexString(crop[1]).toUpperCase(), "crop (2,2)");
        eq("FF030209", Integer.toHexString(crop[2]).toUpperCase(), "crop (3,2)");
        eq("FF010309", Integer.toHexString(crop[3]).toUpperCase(), "crop (1,3)");
        eq("FF020309", Integer.toHexString(crop[4]).toUpperCase(), "crop (2,3)");
        eq("FF030309", Integer.toHexString(crop[5]).toUpperCase(), "crop (3,3)");

        // --- ColorUtil.hexLc ------------------------------------------------
        eq("#1c3c06", ColorUtil.hexLc(0xFF1C3C06), "hexLc lowercase");
        eq("#0a0b0c", ColorUtil.hexLc(0x0A0B0C), "hexLc alpha dropped");

        // --- Palette ---------------------------------------------------------
        Palette p = new Palette();
        check(p.size() == 0, "palette starts empty");
        check(p.add("#AABBCC"), "palette add valid");
        check(p.add("ddEeFf"), "palette add without hash + case");
        eq("#ddeeff", p.get(1), "palette normalised lowercase");
        check(p.add("#AABBCC"), "palette re-add duplicate");
        eq("#aabbcc", p.get(p.size() - 1), "duplicate moved to end");
        eq("2", Integer.toString(p.size()), "palette deduped size");
        String enc = p.encode();
        Palette p2 = Palette.decode(enc);
        eq(enc, p2.encode(), "palette encode/decode round-trip");
        check(p2.contains("#DDEEFF"), "decoded palette contains colour");
        check(Palette.normalize("@xyz") == null, "palette rejects invalid hex");
        check(Palette.normalize("#ABC").equals("#aabbcc"), "palette expands #RGB");
        check(p.remove("#ddeeff"), "palette remove");
        eq("1", Integer.toString(p.size()), "palette size after remove");
        for (int i = 0; i < 30; i++) {
            p.add(String.format("#%02x%02x%02x", i, 0, 0));
        }
        eq(Integer.toString(Palette.MAX_SIZE), Integer.toString(p.size()), "palette capped at MAX_SIZE");

        // --- CamMath ------------------------------------------------------------------
        int[] ds = CamMath.displaySize(1920, 1080, 1);
        eq("1080x1920", ds[0] + "x" + ds[1], "displaySize 90");
        ds = CamMath.displaySize(1080, 1920, 3);
        eq("1920x1080", ds[0] + "x" + ds[1], "displaySize 270");
        float[] rect = CamMath.displayRect(1000, 800, 400, 300);
        check(Math.abs(rect[0]) < 1e-3 && Math.abs(rect[2] - 1000f) < 1e-3, "displayRect fills width");
        check(Math.abs((rect[1] + rect[3]) - 800f) < 1e-2, "displayRect vertically centred");
        int[] fr = CamMath.toFrame(200, 150, 1920, 1080, 0);
        eq("200;150", fr[0] + ";" + fr[1], "toFrame identity ~ inside");
        fr = CamMath.toFrame(200, 150, 1920, 1080, 1); // raw 90ccw: x'=v, y'=H-1-u
        eq("150;879", fr[0] + ";" + fr[1], "toFrame 90 round-trip");
        fr = CamMath.toFrame(-5, -5, 1920, 1080, 1);
        check(fr[0] >= 0 && fr[1] >= 0, "toFrame clamps negatives");
        fr = CamMath.toFrame(99999, 99999, 1920, 1080, 2);
        eq("0;0", fr[0] + ";" + fr[1], "toFrame clamps oversize");

        // --- CamMath int-array API (used by the magnifier inset) ----------------------
        // Inset crop is sampled in display space; its centre must equal the crosshair's
        // raw pixel (the hex readout), for every rotation quarter.
        int[] raw = new int[2];
        for (int q = 0; q < 4; q++) {
            CamMath.toFrame(540, 960, 1920, 1080, q, raw);
            int[] floatApi = CamMath.toFrame((float) 540, (float) 960, 1920, 1080, q);
            eq(raw[0] + ";" + raw[1],
               floatApi[0] + ";" + floatApi[1],
               "int API matches float API q=" + q);
            check(raw[0] >= 0 && raw[0] < 1920 && raw[1] >= 0 && raw[1] < 1080,
                    "inset centre in raw bounds q=" + q);
        }
        int[] centre = CamMath.toFrame(540, 960, 1920, 1080, 1, new int[2]);
        eq("960;539", centre[0] + ";" + centre[1], "inset centre == focus pixel (q=1)");
        centre = CamMath.toFrame(540, 960, 1920, 1080, 3, new int[2]);
        eq("959;540", centre[0] + ";" + centre[1], "inset centre == focus pixel (q=3)");
        // The display corner (frame's last pixel is top-left in display for q=1).
        int[] corner = CamMath.toFrame(0, 1919, 1920, 1080, 1, new int[2]);
        eq("1919;1079", corner[0] + ";" + corner[1], "display corner q=1 in range");
        corner = CamMath.toFrame(1079, 0, 1920, 1080, 1, new int[2]);
        eq("0;0", corner[0] + ";" + corner[1], "display origin q=1");

        // --- ColorMath: sRGB -> XYZ -> CIE L*u*v* ----------------------------
        double[] xyz = ColorMath.argbToXyz(0xFFFF0000);
        check(Math.abs(xyz[0] - 0.4124564) < 1e-3, "xyz red X");
        check(Math.abs(xyz[1] - 0.2126729) < 1e-3, "xyz red Y");
        check(Math.abs(xyz[2] - 0.0193339) < 1e-3, "xyz red Z");

        double[] luv = ColorMath.argbToLuv(0xFFFF0000, LightSource.D65.white());
        check(Math.abs(luv[0] - 53.24) < 0.1, "luv red L* ~53.2");
        check(Math.abs(luv[1] - 175.0) < 0.5, "luv red u* ~175");
        check(Math.abs(luv[2] - 37.8) < 0.5, "luv red v* ~37.8");

        double[] luvGray = ColorMath.argbToLuv(0xFF808080, LightSource.D65.white());
        check(Math.abs(luvGray[0] - 53.6) < 0.5, "luv gray L* ~53.6");
        check(Math.abs(luvGray[1]) < 0.5, "luv gray u* ~0");
        check(Math.abs(luvGray[2]) < 0.5, "luv gray v* ~0");

        check(Math.abs(ColorMath.srgbToLinear(0.0)) < 1e-9, "linear black");
        check(Math.abs(ColorMath.srgbToLinear(1.0) - 1.0) < 1e-9, "linear white");
        double round = ColorMath.linearToSrgb(ColorMath.srgbToLinear(0.2));
        check(Math.abs(round - 0.2) < 1e-6, "srgb linear round-trip");

        double[] w6500 = ColorMath.cctToWhite(6500);
        check(w6500[0] > 0.90 && w6500[0] < 1.0, "cct6500 X sane");     // X = x/y ~ 0.968
        check(w6500[1] == 1.0, "cct white Y normalized");
        check(w6500[2] > 1.0 && w6500[2] < 1.2, "cct6500 Z sane");

        // --- LightSource -------------------------------------------------------
        check(LightSource.D65.white()[0] > 0.9 && LightSource.D65.white()[2] > 1.0,
                "D65 chromaticity sanity");
        check("D65".equals(LightSource.preset(0, null).name), "preset 0 = D65");
        check("A".equals(LightSource.preset(2, null).name), "preset 2 = illuminant A");
        check("BST1".equals(LightSource.preset(3, null).name), "preset 3 = BST1 estimate");
        LightSource meas = LightSource.measuredWhite(new double[]{0.35, 0.36, 0.29}, "BST1");
        check(Math.abs(meas.xn - 0.35 / 0.36) < 1e-6, "measuredWhite normalizes Yn=1");

        // --- Calibration: WB fit maps a neutral measured to its reference ---------
        Calibration cal = new Calibration();
        check(!cal.isFitted(), "empty calibration not fitted");
        cal.addSample(0xFFF3F3F2, Calibration.refHexArgb(18)); // white patch measured as white
        check(cal.isFitted(), "diagonal WB fit active");
        int applied = cal.apply(0xFFE8E4DA); // warmish measured -> cool toward reference
        int[] ap = ColorUtil.argb(applied);
        check(ap[1] >= ap[3], "WB cools warm white toward blue side");

        cal.clear();
        check(cal.samples() == 0 && !cal.isFitted(), "clear resets calibration");
        cal.addSample(0xFFE8E4DA, Calibration.refHexArgb(18));
        // gaining the blue channel, the same warm patch must map to the reference white
        applied = cal.apply(0xFFE8E4DA);
        check(Math.abs(((applied >> 16) & 0xFF) - ((applied) & 0xFF)) < 12, "WB balances warm patch");

        // --- Calibration: >=3 patches matrix, encode/decode round-trip -------------
        Calibration cal3 = new Calibration();
        cal3.addSample(Calibration.refHexArgb(0), Calibration.refHexArgb(0));
        cal3.addSample(Calibration.refHexArgb(6), Calibration.refHexArgb(6));
        cal3.addSample(Calibration.refHexArgb(18), Calibration.refHexArgb(18));
        check(cal3.isFitted(), "matrix fit active with 3 patches");
        applied = cal3.apply(Calibration.refHexArgb(6));
        check(Math.abs(((applied >> 16) & 0xFF) - 214) < 4 && Math.abs(((applied >> 8) & 0xFF) - 126) < 4,
                "identity-like matrix preserves measured colour");
        String enc3 = cal3.encode();
        Calibration cal3b = Calibration.decode(enc3);
        check(cal3b.isFitted(), "decoded matrix is fitted");
        check(cal3b.apply(0xFFD67E2C) == cal3.apply(0xFFD67E2C), "decoded matrix same result");
        check(new Calibration().encode().isEmpty(), "unfitted calibration encodes empty");
        check(!Calibration.decode("").isFitted(), "empty decodes unfitted");

        return failures;
    }

    /** Standalone runner (plain javac/java, no JUnit on classpath). */
    public static void main(String[] args) {
        int n = runAll();
        if (n > 0) {
            System.err.println("\n" + n + " test(s) FAILED");
            System.exit(1);
        }
        System.out.println("All pure-logic tests passed.");
    }
}