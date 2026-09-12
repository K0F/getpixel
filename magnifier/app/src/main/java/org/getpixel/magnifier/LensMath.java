package org.getpixel.magnifier;

/** Pure magnifier-lens geometry. No Android deps (unit-testable with javac). */
public final class LensMath {

    private LensMath() {}

    /** Clamps a lens of cropW x cropH "screen pixels" around (centerX, centerY). */
    public static int[] cropOrigin(int centerX, int centerY, int cropW, int cropH,
                                   int frameW, int frameH) {
        if (cropW > frameW) cropW = frameW;
        if (cropH > frameH) cropH = frameH;
        int left = centerX - cropW / 2;
        int top = centerY - cropH / 2;
        if (left < 0) left = 0;
        if (top < 0) top = 0;
        if (left + cropW > frameW) left = frameW - cropW;
        if (top + cropH > frameH) top = frameH - cropH;
        if (left < 0) left = 0;
        if (top < 0) top = 0;
        return new int[]{left, top};
    }

    /** Size (in screen pixels) of a crop seen inside a lensPx-wide glass at zoom. */
    public static int cropForZoom(int lensPx, float zoom) {
        if (zoom <= 0f) zoom = 1f;
        return Math.max(1, (int) Math.round(lensPx / zoom));
    }

    /** Restricts zoom to the supported lens range. */
    public static float clampZoom(float zoom) {
        if (zoom < 1f) return 1f;
        if (zoom > 12f) return 12f;
        return zoom;
    }
}