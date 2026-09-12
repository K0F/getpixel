#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// Stand-in for the Android `screencap` tool, used by the integration test.
// Emits the raw 16-byte header followed by a synthetic 8x6 frame where
// pixel (x,y) -> R=x*10, G=y*20, B=x*y, A=255.
// Format selected by $FAKE_MODE: rgba (default), bgra, rgb565.

static void put32(uint8_t *dst, uint32_t v) {
    dst[0] = (uint8_t)(v);
    dst[1] = (uint8_t)(v >> 8);
    dst[2] = (uint8_t)(v >> 16);
    dst[3] = (uint8_t)(v >> 24);
}

int main(void) {
    enum { W = 8, H = 6 };
    const char *mode = getenv("FAKE_MODE");
    if (!mode) mode = "rgba";
    int rgb565 = strcmp(mode, "rgb565") == 0;
    int bgra = strcmp(mode, "bgra") == 0;
    uint32_t fmt;
    if (rgb565) {
        fmt = 3;
    } else if (bgra) {
        fmt = 5;   // HAL_PIXEL_FORMAT_BGRA_8888
    } else {
        fmt = 1;   // HAL_PIXEL_FORMAT_RGBA_8888
    }
    int bpp = rgb565 ? 2 : 4;

    uint8_t header[16];
    put32(header + 0, W);
    put32(header + 4, H);
    put32(header + 8, fmt);
    put32(header + 12, 0);
    fwrite(header, 1, sizeof(header), stdout);

    for (uint32_t y = 0; y < H; y++) {
        for (uint32_t x = 0; x < W; x++) {
            uint8_t r = (uint8_t)(x * 10);
            uint8_t g = (uint8_t)(y * 20);
            uint8_t b = (uint8_t)(x * y);
            if (bpp == 2) {
                uint16_t v = (uint16_t)(((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3));
                uint8_t p[2] = {(uint8_t)v, (uint8_t)(v >> 8)};
                fwrite(p, 1, 2, stdout);
            } else if (bgra) {
                uint8_t p[4] = {b, g, r, 255};
                fwrite(p, 1, 4, stdout);
            } else {
                uint8_t p[4] = {r, g, b, 255};
                fwrite(p, 1, 4, stdout);
            }
        }
    }
    fflush(stdout);
    return 0;
}