#include "capscreen.h"

#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>

// Error codes returned by this module.
enum {
    CAP_ERR_NONE   = 0,   // success
    CAP_ERR_EXEC   = -1,  // could not run any screencap command
    CAP_ERR_HEADER = -2,  // screencap produced no readable header
    CAP_ERR_OOB    = -3,  // coordinates out of screen bounds
    CAP_ERR_EOS    = -4,  // stream ended before the target pixel
    CAP_ERR_PIXEL  = -5,  // could not read the pixel bytes
};

// Candidate screencap invocations, tried in order until one yields a header.
// Runs on Android itself (Termux with root and full /system paths) or from an
// adb shell (where the shell user already has screencap permission).
static const char *const SCREENCAP_CMDS[] = {
    "su -c /system/bin/screencap",
    "su -c screencap",
    "/system/bin/screencap",
    "screencap",
};

FILE *open_screencap(ScreenInfo *info) {
    for (size_t i = 0; i < sizeof(SCREENCAP_CMDS) / sizeof(SCREENCAP_CMDS[0]); i++) {
        FILE *fp = popen(SCREENCAP_CMDS[i], "r");
        if (!fp) continue;

        uint32_t w, h, fmt, cs;
        // A failed su (not found / permission denied) closes the pipe, so the
        // header read below fails and we move on to the next candidate.
        if (fread(&w, sizeof(uint32_t), 1, fp) == 1 &&
            fread(&h, sizeof(uint32_t), 1, fp) == 1 &&
            fread(&fmt, sizeof(uint32_t), 1, fp) == 1 &&
            fread(&cs, sizeof(uint32_t), 1, fp) == 1) {
            info->width = w;
            info->height = h;
            info->format = fmt;
            return fp;
        }
        pclose(fp);
    }
    return NULL;
}

int read_pixel_at(FILE *fp, const ScreenInfo *info, uint32_t x, uint32_t y, PixelRGBA *out) {
    if (x >= info->width || y >= info->height) {
        return CAP_ERR_OOB;
    }

    // A few devices report RGB_565 (2 bytes/pixel); everything else we treat
    // as 4 bytes/pixel (RGBA_8888 / RGBX_8888 / BGRA_8888).
    size_t bytes_per_pixel = (info->format == FORMAT_RGB_565) ? 2 : 4;

    size_t target_byte_offset = ((size_t)y * info->width + x) * bytes_per_pixel;

    // Discard stream data prior to the target pixel (pipes do not support fseek)
    uint8_t buffer[8192];
    size_t bytes_remaining = target_byte_offset;

    while (bytes_remaining > 0) {
        size_t chunk = (bytes_remaining > sizeof(buffer)) ? sizeof(buffer) : bytes_remaining;
        size_t bytes_read = fread(buffer, 1, chunk, fp);
        if (bytes_read == 0) {
            return CAP_ERR_EOS;
        }
        bytes_remaining -= bytes_read;
    }

    if (bytes_per_pixel == 2) {
        // RGB_565: little-endian 16-bit, 5/6/5 channels.
        uint8_t p[2];
        if (fread(p, 1, 2, fp) == 2) {
            uint16_t v = (uint16_t)(p[0] | ((uint16_t)p[1] << 8));
            out->r = (uint8_t)(((v >> 11) & 0x1F) * 255 / 31);
            out->g = (uint8_t)(((v >> 5) & 0x3F) * 255 / 63);
            out->b = (uint8_t)((v & 0x1F) * 255 / 31);
            out->a = 255;
            return CAP_ERR_NONE;
        }
        return CAP_ERR_PIXEL;
    }

    // 4 bytes per pixel: R, G, B, A (or B, G, R, A for BGRA_8888)
    uint8_t pixel[4];
    if (fread(pixel, 1, 4, fp) == 4) {
        if (info->format == FORMAT_BGRA_8888) {
            out->r = pixel[2];
            out->g = pixel[1];
            out->b = pixel[0];
        } else {
            out->r = pixel[0];
            out->g = pixel[1];
            out->b = pixel[2];
        }
        out->a = pixel[3];
        return CAP_ERR_NONE;
    }

    return CAP_ERR_PIXEL;
}

int get_pixel_color(uint32_t x, uint32_t y, PixelRGBA *out) {
    ScreenInfo info;
    FILE *fp = open_screencap(&info);
    if (!fp) {
        return CAP_ERR_EXEC;
    }
    int rc = read_pixel_at(fp, &info, x, y, out);
    pclose(fp);
    return rc;
}