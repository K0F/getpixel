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