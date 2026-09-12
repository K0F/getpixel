#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../capscreen.h"

static int failures = 0;

#define CHECK(cond, msg)                                                    \
    do {                                                                    \
        if (!(cond)) {                                                      \
            fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, msg);   \
            failures++;                                                     \
        }                                                                   \
    } while (0)

static void put32(uint8_t *dst, uint32_t v) {
    dst[0] = (uint8_t)(v);
    dst[1] = (uint8_t)(v >> 8);
    dst[2] = (uint8_t)(v >> 16);
    dst[3] = (uint8_t)(v >> 24);
}

// Builds a synthetic screencap raw stream for the given format.
// pattern: pixel (x,y) -> R=x*10, G=y*20, B=x*y, A=255.
static uint8_t *make_frame(uint32_t w, uint32_t h, uint32_t format,
                           size_t *out_len, int *pixel_bytes) {
    int bpp = (format == FORMAT_RGB_565) ? 2 : 4;
    size_t data_len = (size_t)w * h * bpp;
    size_t total = 16 + data_len;
    uint8_t *buf = malloc(total);

    put32(buf + 0, w);
    put32(buf + 4, h);
    put32(buf + 8, format);
    put32(buf + 12, 0 /* colorspace */);

    for (uint32_t y = 0; y < h; y++) {
        for (uint32_t x = 0; x < w; x++) {
            uint8_t r = (uint8_t)(x * 10);
            uint8_t g = (uint8_t)(y * 20);
            uint8_t b = (uint8_t)(x * y);
            uint8_t a = 255;
            uint8_t *p = buf + 16 + ((size_t)y * w + x) * bpp;
            if (bpp == 2) {
                uint16_t v = (uint16_t)(((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3));
                p[0] = (uint8_t)v;
                p[1] = (uint8_t)(v >> 8);
            } else if (format == FORMAT_BGRA_8888) {
                p[0] = b; p[1] = g; p[2] = r; p[3] = a;
            } else {
                p[0] = r; p[1] = g; p[2] = b; p[3] = a;
            }
        }
    }
    *out_len = total;
    *pixel_bytes = bpp;
    return buf;
}

static void test_pixel(uint32_t w, uint32_t h, uint32_t format,
                       uint32_t x, uint32_t y) {
    size_t len;
    int bpp;
    uint8_t *frame = make_frame(w, h, format, &len, &bpp);

    ScreenInfo info = {w, h, format};
    FILE *fp = fmemopen(frame, len, "r");
    uint32_t got_w, got_h, got_fmt, cs;
    CHECK(fread(&got_w, 4, 1, fp) == 1 && got_w == w, "header width");
    CHECK(fread(&got_h, 4, 1, fp) == 1 && got_h == h, "header height");
    CHECK(fread(&got_fmt, 4, 1, fp) == 1 && got_fmt == format, "header format");
    CHECK(fread(&cs, 4, 1, fp) == 1, "header colorspace");

    PixelRGBA c = {0};
    int rc = read_pixel_at(fp, &info, x, y, &c);
    CHECK(rc == 0, "read_pixel_at should succeed");
    if (format == FORMAT_RGB_565) {
        uint8_t er = (uint8_t)((x * 10) & 0xF8);
        uint8_t eg = (uint8_t)((y * 20) & 0xFC);
        uint8_t eb = (uint8_t)((x * y) & 0xF8);
        CHECK(abs((int)c.r - er) <= 8 && abs((int)c.g - eg) <= 4 && abs((int)c.b - eb) <= 8,
              "rgb565 expansion within rounding");
        CHECK(c.a == 255, "rgb565 alpha is opaque");
    } else {
        CHECK(c.r == (uint8_t)(x * 10), "R channel");
        CHECK(c.g == (uint8_t)(y * 20), "G channel");
        CHECK(c.b == (uint8_t)(x * y), "B channel");
        CHECK(c.a == 255, "A channel");
    }
    fclose(fp);
    free(frame);
}

static void test_out_of_bounds(void) {
    size_t len;
    int bpp;
    uint8_t *frame = make_frame(8, 6, 1, &len, &bpp);
    ScreenInfo info = {8, 6, 1};
    FILE *fp = fmemopen(frame, len, "r");
    uint32_t w, h, fmt, cs;
    (void)!fread(&w, 4, 1, fp);
    (void)!fread(&h, 4, 1, fp);
    (void)!fread(&fmt, 4, 1, fp);
    (void)!fread(&cs, 4, 1, fp);
    PixelRGBA c;
    CHECK(read_pixel_at(fp, &info, 8, 0, &c) == -3, "x at width is out of bounds");
    CHECK(read_pixel_at(fp, &info, 0, 6, &c) == -3, "y at height is out of bounds");
    fclose(fp);
    free(frame);
}

static void test_truncated(void) {
    // Frame who knows w=8,h=6,format=1 but only 10 bytes of pixel data.
    uint8_t tiny[16 + 10];
    put32(tiny + 0, 8);
    put32(tiny + 4, 6);
    put32(tiny + 8, 1);
    put32(tiny + 12, 0);
    memset(tiny + 16, 0xAB, 10);

    ScreenInfo info = {8, 6, 1};
    FILE *fp = fmemopen(tiny, sizeof(tiny), "r");
    uint32_t w, h, fmt, cs;
    (void)!fread(&w, 4, 1, fp);
    (void)!fread(&h, 4, 1, fp);
    (void)!fread(&fmt, 4, 1, fp);
    (void)!fread(&cs, 4, 1, fp);
    PixelRGBA c;
    // (7,0) sits at offset 28, but only 10 bytes of pixel data exist.
    int rc = read_pixel_at(fp, &info, 7, 0, &c);
    CHECK(rc == -4, "stream ends before far pixel -> EOS");
    fclose(fp);
}

// Integration test: run the real get_pixel binary with a fake `su`/`screencap`
// on PATH, exercising the popen candidate-command fallback chain.
static void test_popen_chain(const char *shim_dir, const char *mode, const char *args,
                             const char *expect) {
    char path[4096];
    snprintf(path, sizeof(path), "%s:%s", shim_dir, getenv("PATH") ? getenv("PATH") : "/usr/bin");
    setenv("PATH", path, 1);
    setenv("FAKE_MODE", mode, 1);

    char cmd[512];
    snprintf(cmd, sizeof(cmd), "./get_pixel %s 2>/dev/null", args);
    FILE *fp = popen(cmd, "r");
    if (!fp) {
        CHECK(0, "popen get_pixel");
        return;
    }
    char out[512] = {0};
    size_t n = fread(out, 1, sizeof(out) - 1, fp);
    out[n] = '\0';
    pclose(fp);

    char msg[600];
    snprintf(msg, sizeof(msg), "get_pixel(%s) with mode %s -> '%s'", args, mode, out);
    CHECK(strstr(out, expect) != NULL, msg);
}

int main(int argc, char **argv) {
    const char *shim_dir = ".";
    if (argc > 1) shim_dir = argv[1];

    // RGBA_8888 sample pixels across the frame
    test_pixel(8, 6, 1, 0, 0);
    test_pixel(8, 6, 1, 3, 2);
    test_pixel(1024, 768, 1, 100, 200);
    // BGRA_8888 byte order
    test_pixel(8, 6, FORMAT_BGRA_8888, 5, 1);
    // RGB_565 expansion
    test_pixel(8, 6, FORMAT_RGB_565, 7, 5);

    test_out_of_bounds();
    test_truncated();

    // popen chain: with fake `su` and `screencap` shims in $TESTDIR.
    // (2,3) -> R=20 G=60 B=6  and  (7,5) -> R=70 G=100 B=35
    test_popen_chain(shim_dir, "rgba", "2 3", "R:20 G:60 B:6 A:255 (Hex: #143C06)");
    test_popen_chain(shim_dir, "bgra", "2 3", "R:20 G:60 B:6 A:255 (Hex: #143C06)");
    test_popen_chain(shim_dir, "rgb565", "7 5", "Hex: #416520");

    if (failures) {
        fprintf(stderr, "\n%d test(s) FAILED\n", failures);
        return 1;
    }
    printf("All capscreen tests passed.\n");
    return 0;
}