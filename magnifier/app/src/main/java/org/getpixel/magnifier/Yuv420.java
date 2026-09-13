package org.getpixel.magnifier;

import java.nio.ByteBuffer;

/** Minimal YUV_420_888 decoder for single-pixel/region reads. No Android deps. */
public final class Yuv420 {

    private Yuv420() {}

    public static class Planes {
        final ByteBuffer y;
        final ByteBuffer u;
        final ByteBuffer v;
        final int yStride;
        final int uStride;
        final int vStride;
        final int uPixelStride;
        final int vPixelStride;
        final int width;
        final int height;
        final boolean nv21;

        public Planes(ByteBuffer y, ByteBuffer u, ByteBuffer v,
                      int yStride, int uStride, int vStride,
                      int uPixelStride, int vPixelStride, int width, int height,
                      boolean nv21) {
            this.y = y;
            this.u = u;
            this.v = v;
            this.yStride = yStride;
            this.uStride = uStride;
            this.vStride = vStride;
            this.uPixelStride = uPixelStride;
            this.vPixelStride = vPixelStride;
            this.width = width;
            this.height = height;
            this.nv21 = nv21;
        }

        private boolean interleaved() {
            return uPixelStride == 2 || vPixelStride == 2;
        }

        /** True when the U plane carries V samples too (2-plane store … we treat 3-plane interleaved). */
        private boolean sameStore() {
            return interleaved() && uPixelStride == vPixelStride;
        }
    }

    /** Returns 0xFFRRGGBB for the pixel at (x, y). Out of bounds -> transparent black. */
    public static int argbAt(Planes p, int x, int y) {
        if (p == null) return 0x00000000;
        if (x < 0 || y < 0 || x >= p.width || y >= p.height) return 0x00000000;

        int yOff = y * p.yStride + x;
        int yVal = safeGet(p.y, yOff);

        int cx = x >> 1;
        int cy = y >> 1;
        int u;
        int v;
        if (p.interleaved()) {
            // Semi-planar NV12 (U first) / NV21 (V first): one UV byte stream used twice.
            // Ordering is a fixed per-device property (set via the UV calibration chip),
            // never guessed per pixel. Some devices expose chroma planes shorter than the
            // frame suggests; neutral chroma (128) is used where null padding would have been.
            int base = cy * p.uStride + cx * 2;
            int first = safeGet(p.u, base);
            int second = safeGet(p.u, base + 1);
            if (p.nv21) {
                u = second;
                v = first;
            } else {
                u = first;
                v = second;
            }
        } else {
            int uOff = cy * p.uStride + cx * p.uPixelStride;
            int vOff = cy * p.vStride + cx * p.vPixelStride;
            u = safeGet(p.u, uOff);
            v = safeGet(p.v, vOff);
        }

        return yuvToArgb(yVal, u, v);
    }

    /** Bounds-safe byte read: 128 (neutral) for reads outside the plane buffer. */
    private static int safeGet(ByteBuffer b, int idx) {
        if (b == null || idx < 0 || idx >= b.limit()) return 128;
        return b.get(idx) & 0xFF;
    }

    /** ITU-R BT.601 limited-range YUV -> 0xFFRRGGBB. */
    public static int yuvToArgb(int y, int u, int v) {
        float yy = y - 16f;
        float uu = u - 128f;
        float vv = v - 128f;
        int r = Math.round(1.164f * yy + 1.596f * vv);
        int g = Math.round(1.164f * yy - 0.813f * vv - 0.391f * uu);
        int b = Math.round(1.164f * yy + 2.018f * uu);
        r = clamp(r);
        g = clamp(g);
        b = clamp(b);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }
}