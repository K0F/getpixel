package org.getpixel.magnifier;

/**
 * Latest captured screen frame as ARGB ints. Thread-safe snapshots taken via
 * {@link #copyRow}, the only write path is the capture thread.
 */
public final class FrameBuffer {

    public final int width;
    public final int height;
    private final int[] pixels;
    private volatile long updatedAtMs = -1;

    public FrameBuffer(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
    }

    public boolean inBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    /** ARGB colour at (x, y); transparent black when out of bounds. */
    public int pixelAt(int x, int y) {
        if (!inBounds(x, y)) return 0x00000000;
        return pixels[y * width + x];
    }

    /** Copies a source RGBX row into the buffer (capture thread only). */
    public void copyRow(int dstRow, byte[] srcRow, int pixelStride, int[] scratchArgbs) {
        if (dstRow < 0 || dstRow >= height) return;
        int base = dstRow * width;
        for (int x = 0; x < width; x++) {
            int o = x * pixelStride;
            int argb = 0xFF000000
                    | ((srcRow[o] & 0xFF) << 16)
                    | ((srcRow[o + 1] & 0xFF) << 8)
                    | (srcRow[o + 2] & 0xFF);
            pixels[base + x] = argb;
        }
    }

    /** Synchronously extracts a crop region into the caller's ARGB array. */
    public void copyRegion(int left, int top, int w, int h, int[] out) {
        for (int y = 0; y < h; y++) {
            int row = top + y;
            if (row < 0 || row >= height) {
                java.util.Arrays.fill(out, y * w, y * w + w, 0);
                continue;
            }
            int src = row * width + left;
            int dst = y * w;
            if (left < 0) {
                java.util.Arrays.fill(out, dst, Math.min(dst + w, dst - left), 0);
                src = row * width;
                dst = -left;
            } else if (left + w > width) {
                int keep = width - left;
                System.arraycopy(pixels, src, out, dst, keep);
                java.util.Arrays.fill(out, dst + keep, dst + w, 0);
                continue;
            }
            System.arraycopy(pixels, src, out, dst, Math.min(w, width - left));
        }
    }

    /** Millis (uptime) of the newest completed frame, -1 before the first one. */
    public long updatedAtMs() {
        return updatedAtMs;
    }

    /** Marks a fully-written frame (capture thread). */
    public void markUpdated(long uptimeMs) {
        updatedAtMs = uptimeMs;
    }
}