#ifndef CAPSCREEN_H
#define CAPSCREEN_H

#include <stdint.h>
#include <stdio.h>

typedef struct {
    uint8_t r;
    uint8_t g;
    uint8_t b;
    uint8_t a;
} PixelRGBA;

typedef struct {
    uint32_t width;
    uint32_t height;
    uint32_t format;   // HAL_PIXEL_FORMAT_* reported by screencap
} ScreenInfo;

// Pixel format constants reported in the screencap header (HAL_PIXEL_FORMAT_*)
#define FORMAT_RGB_565   3
#define FORMAT_BGRA_8888 5

// Opens a working screencap pipe, trying root variants first, and reads the
// 16-byte raw header (width, height, pixel format, color space).
// Returns NULL when every candidate fails (no root / no adb shell).
FILE *open_screencap(ScreenInfo *info);

// Reads the color of the pixel at (x, y) directly from an already-opened
// screencap stream (header already consumed). Uses the stream forward-only.
// Returns 0 on success; negative codes on failure (see below).
int read_pixel_at(FILE *fp, const ScreenInfo *info, uint32_t x, uint32_t y, PixelRGBA *out);

// Convenience wrapper: opens screencap and reads one pixel.
int get_pixel_color(uint32_t x, uint32_t y, PixelRGBA *out);

#endif