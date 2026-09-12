package org.getpixel.magnifier;

/** Pure geometry for mapping a camera frame to the upright preview. No Android deps. */
public final class CamMath {

    private CamMath() {}

    /** Upright displayed size (WxH) for a raw frame of frameW x frameH turned `quarters` x 90° clockwise. */
    public static int[] displaySize(int frameW, int frameH, int quarters) {
        quarters = ((quarters % 4) + 4) % 4;
        if (quarters == 1 || quarters == 3) {
            return new int[]{frameH, frameW};
        }
        return new int[]{frameW, frameH};
    }

    /**
     * Letterboxed rectangle (left, top, right, bottom) of an upright display
     * dispW x dispH inside a viewW x viewH area.
     */
    public static float[] displayRect(int viewW, int viewH, int dispW, int dispH) {
        float scale = Math.min((float) viewW / dispW, (float) viewH / dispH);
        float w = dispW * scale;
        float h = dispH * scale;
        float left = (viewW - w) / 2f;
        float top = (viewH - h) / 2f;
        return new float[]{left, top, left + w, top + h};
    }

    /**
     * Maps a point inside the displayed upright rect to the raw frame pixel.
     * (dx, dy) is relative to the displayed rect origin. Returns {frameX, frameY}.
     */
    public static int[] toFrame(float dx, float dy, int frameW, int frameH, int quarters) {
        quarters = ((quarters % 4) + 4) % 4;
        float u = dx;
        float v = dy;
        int fx;
        int fy;
        switch (quarters) {
            case 1: /* 90° cw: raw(x,y) -> displayed (H-1-y, x); inverse x=v, y=H-1-u */
                fx = Math.round(v);
                fy = frameH - 1 - Math.round(u);
                break;
            case 2:
                fx = frameW - 1 - Math.round(u);
                fy = frameH - 1 - Math.round(v);
                break;
            case 3:
                fx = frameW - 1 - Math.round(v);
                fy = Math.round(u);
                break;
            default:
                fx = Math.round(u);
                fy = Math.round(v);
        }
        if (fx < 0) fx = 0;
        if (fy < 0) fy = 0;
        if (fx >= frameW) fx = frameW - 1;
        if (fy >= frameH) fy = frameH - 1;
        return new int[]{fx, fy};
    }
}