#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>

#include "capscreen.h"

int main(int argc, char *argv[]) {
    if (argc < 3) {
        printf("Usage: %s <x> <y>\n", argv[0]);
        return 1;
    }

    uint32_t x = atoi(argv[1]);
    uint32_t y = atoi(argv[2]);
    PixelRGBA color = {0};

    if (get_pixel_color(x, y, &color) == 0) {
        printf("Pixel at (%u, %u) -> R:%u G:%u B:%u A:%u (Hex: #%02X%02X%02X)\n",
               x, y, color.r, color.g, color.b, color.a, color.r, color.g, color.b);
    } else {
        printf("Failed to retrieve pixel color.\n");
    }

    return 0;
}