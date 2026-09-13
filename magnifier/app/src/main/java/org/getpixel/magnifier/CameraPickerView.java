package org.getpixel.magnifier;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.List;

/** Full-screen colour picker: live camera preview OR a gallery photo, with a
 *  centre/focus crosshair, a magnified inset, a #rrggbb readout and a shared
 *  saved palette. Camera: tap anywhere = save current pixel. Gallery: tap to
 *  move the crosshair, "＋ Add" saves the focused pixel. Tapping a swatch
 *  copies its hex, long-pressing removes it. */
public class CameraPickerView extends View {

    public interface Listener {
        void onSave(int argbColor);

        void onCopy(String hex);

        void onRemove(String hex);

        void onToggleMode();

        void onToggleChroma();

        void onRotate();

        void onCalToggle();

        void onCalSample(int argbColor);

        void onCalUndo();

        void onLightCycle();

        void onRawCalToggle();

        void onExport();
    }

    private static final int INSET_DP = 96;
    private static final int SWATCH_DP = 52;
    private static final int SWATCH_GAP_DP = 10;
    private static final int PANEL_PAD_DP = 14;
    private static final int CROSSHAIR_ARGB = 0xFF33FF99;

    private final Paint paint = new Paint();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private Listener listener;

    private volatile FrameBuffer frame;
    private volatile int rot;
    private float zoom = 3f;
    private Bitmap previewBitmap;
    private int[] scratch;
    private int cachedPixels = -1;
    private List<String> palette = java.util.Collections.emptyList();
    private int currentIndex = -1;
    private boolean galleryMode;
    private float focusX = -1f;
    private float focusY = -1f;
    private float downX;
    private float downY;
    private RectF addButtonRect;
    private RectF modeButtonRect;
    private RectF chromaButtonRect;
    private RectF rotButtonRect;
    private RectF calButtonRect;
    private RectF undoButtonRect;
    private RectF lightButtonRect;
    private RectF rawCalButtonRect;
    private RectF exportButtonRect;
    private boolean nv21Order = true;
    private int rotOffsetQuarters;
    private Calibration cal;
    private boolean useCal;
    private LightSource light = LightSource.bst1Estimate();
    private boolean calCollecting;
    private int calCount;

    public CameraPickerView(Context context) {
        super(context);
        textPaint.setColor(Color.WHITE);
        textPaint.setAntiAlias(true);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoom = LensMath.clampZoom(zoom * d.getScaleFactor());
                invalidate();
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                if (!galleryMode) return;
                int swatch = swatchAt(e.getX(), e.getY());
                if (swatch >= 0 && swatch < palette.size() && listener != null) {
                    listener.onRemove(palette.get(swatch));
                }
            }
        });
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** New live frame from the camera (rot = 90° quarters clockwise). */
    public void onFrame(FrameBuffer f, int rotQuarters) {
        this.frame = f;
        this.rot = rotQuarters;
        invalidate();
    }

    /** Switches to gallery (fixed bitmap source) or back to camera. */
    public void setGalleryMode(boolean gallery, FrameBuffer image) {
        this.galleryMode = gallery;
        this.rot = 0;
        if (gallery && image != null) {
            this.frame = image;
            focusX = focusY = -1f;
        }
        invalidate();
    }

    /** Reflects the current interleaved chroma ordering for the UV chip label. */
    public void setChroma(boolean nv21) {
        this.nv21Order = nv21;
        invalidate();
    }

    /** Rotation calibration offset (0..3 quarter-turns) for the ⟳ chip label. */
    public void setRotOffset(int quarters) {
        this.rotOffsetQuarters = ((quarters % 4) + 4) % 4;
        invalidate();
    }

    /** Full calibration pipeline state from the activity. */
    public void setCaliber(Calibration model, boolean applyCal, boolean collecting, int count, LightSource lightSrc) {
        this.cal = model;
        this.useCal = applyCal;
        this.calCollecting = collecting;
        this.calCount = count;
        if (lightSrc != null) this.light = lightSrc;
        invalidate();
    }

    public void setPalette(List<String> colors) {
        this.palette = colors == null ? java.util.Collections.emptyList() : colors;
        currentIndex = -1;
        int[] mp = focusPixel();
        int col = frame == null ? 0 : frame.pixelAt(mp[0], mp[1]);
        String hex = ColorUtil.hexLc(col);
        for (int i = 0; i < this.palette.size(); i++) {
            if (this.palette.get(i).equals(hex)) {
                currentIndex = i;
                break;
            }
        }
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private int dpRound(float v) {
        return Math.round(dp(v));
    }

    private float[] displayRectOf() {
        FrameBuffer f = frame;
        if (f == null) return null;
        int[] d = CamMath.displaySize(f.width, f.height, rot);
        return CamMath.displayRect(getWidth(), getHeight(), d[0], d[1]);
    }

    /** Clamped focus point in view coordinates. */
    private float focusX() {
        if (focusX < 0) focusX = getWidth() / 2f;
        return focusX;
    }

    private float focusY() {
        if (focusY < 0) focusY = getHeight() / 2f;
        return focusY;
    }

    /** Raw frame coords of the pixel under the focus point. */
    private int[] focusPixel() {
        float[] rect = displayRectOf();
        if (rect == null) return new int[]{0, 0};
        FrameBuffer f = frame;
        float[] g = focusGrid(rect);
        return CamMath.toFrame(g[0], g[1], f.width, f.height, rot);
    }

    /** Crosshair position in the upright display grid (frame-pixel units). */
    private float[] focusGrid(float[] rect) {
        FrameBuffer f = frame;
        int[] ds = CamMath.displaySize(f.width, f.height, rot);
        float sx = (rect[2] - rect[0]) / (float) ds[0];
        float sy = (rect[3] - rect[1]) / (float) ds[1];
        return new float[]{(focusX() - rect[0]) / sx, (focusY() - rect[1]) / sy};
    }

    /** The colour the readout/save should present: profiled when CAL is on. */
    private int readoutColor(int rawColor) {
        if (useCal && cal != null && cal.isFitted()) {
            return cal.apply(rawColor);
        }
        return rawColor;
    }

    /** 11x11-pixel mean around the crosshair, used for calibration patch taps. */
    private int sampleTarget() {
        FrameBuffer f = frame;
        if (f == null) return 0;
        int[] mid = focusPixel();
        int r = 0, g = 0, b = 0, n = 0;
        for (int dy = -5; dy <= 5; dy++) {
            int fy = mid[1] + dy;
            if (fy < 0 || fy >= f.height) continue;
            int base = fy * f.width;
            for (int dx = -5; dx <= 5; dx++) {
                int fx = mid[0] + dx;
                if (fx < 0 || fx >= f.width) continue;
                int px = f.pixelAt(fx, fy);
                r += (px >> 16) & 0xFF;
                g += (px >> 8) & 0xFF;
                b += px & 0xFF;
                n++;
            }
        }
        if (n == 0) return f.pixelAt(mid[0], mid[1]);
        return 0xFF000000 | ((r / n) << 16) | ((g / n) << 8) | (b / n);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        FrameBuffer f = frame;

        if (f == null) {
            canvas.drawColor(0xFF101218);
            textPaint.setTextSize(dp(16f));
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(getContext().getString(R.string.picker_waiting), w / 2f, h / 2f, textPaint);
            return;
        }

        float[] rect = displayRectOf();
        drawPreview(canvas, f, rect);

        int[] mid = focusPixel();
        int focusColor = f.pixelAt(mid[0], mid[1]);
        int readColor = readoutColor(focusColor);
        String hex = ColorUtil.hexLc(readColor);

        drawInset(canvas, f, rect);
        drawReadout(canvas, readColor, hex);
        drawCrosshair(canvas);
        drawModeButton(canvas);
        if (galleryMode) {
            drawExportButton(canvas);
        } else {
            drawChromaButton(canvas);
            drawRotateButton(canvas);
            drawCalButton(canvas);
            if (calCollecting && calCount > 0) drawUndoButton(canvas);
            if (cal != null && cal.isFitted()) drawRawCalButton(canvas);
            drawLightButton(canvas);
        }
        if (galleryMode) drawAddButton(canvas, hex);
        drawPalette(canvas, hex);
    }

    private void drawPreview(Canvas canvas, FrameBuffer f, float[] rect) {
        int[] ds = CamMath.displaySize(f.width, f.height, rot);
        int dw = ds[0];
        int dh = ds[1];
        int need = dw * dh;
        if (previewBitmap == null || previewBitmap.getWidth() != dw || previewBitmap.getHeight() != dh
                || cachedPixels != need) {
            if (previewBitmap != null) previewBitmap.recycle();
            previewBitmap = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
            cachedPixels = need;
        }
        if (scratch == null || scratch.length < need) {
            scratch = new int[need];
        }
        if (rot % 4 == 0) {
            f.copyRegion(0, 0, f.width, f.height, scratch);
        } else {
            int q = ((rot % 4) + 4) % 4;
            for (int dy = 0; dy < dh; dy++) {
                int base = dy * dw;
                for (int dx = 0; dx < dw; dx++) {
                    int fx;
                    int fy;
                    switch (q) {
                        case 1: fx = dy;         fy = f.height - 1 - dx; break;
                        case 2: fx = f.width - 1 - dx; fy = f.height - 1 - dy; break;
                        default: fx = f.width - 1 - dy; fy = dx;             break;
                    }
                    scratch[base + dx] = f.pixelAt(fx, fy);
                }
            }
        }
        previewBitmap.setPixels(scratch, 0, dw, 0, 0, dw, dh);
        paint.setFilterBitmap(true);
        canvas.drawBitmap(previewBitmap, null, new RectF(rect[0], rect[1], rect[2], rect[3]), paint);
    }

    private void drawInset(Canvas canvas, FrameBuffer f, float[] rect) {
        int[] ds = CamMath.displaySize(f.width, f.height, rot);
        int dw = ds[0];
        int dh = ds[1];
        int side = dpRound(INSET_DP);
        int cropPx = LensMath.cropForZoom(side, zoom);
        float[] g = focusGrid(rect);
        int[] origin = LensMath.cropOrigin(Math.round(g[0]), Math.round(g[1]),
                cropPx, cropPx, dw, dh);
        int left = origin[0];
        int top = origin[1];
        int cw = Math.min(cropPx, Math.max(0, dw - left));
        int ch = Math.min(cropPx, Math.max(0, dh - top));

        int right = getWidth() - dpRound(10f);
        int topY = dpRound(46f);
        RectF dst = new RectF(right - side, topY, right, topY + side);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xBB000000);
        canvas.drawRect(dst, paint);

        if (cw > 0 && ch > 0) {
            int[] crop = new int[cw * ch];
            int[] raw = new int[2];
            for (int dy = 0; dy < ch; dy++) {
                int by = top + dy;
                int base = dy * cw;
                for (int dx = 0; dx < cw; dx++) {
                    CamMath.toFrame(left + dx, by, f.width, f.height, rot, raw);
                    crop[base + dx] = f.pixelAt(raw[0], raw[1]);
                }
            }
            Bitmap cropBmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
            cropBmp.setPixels(crop, 0, cw, 0, 0, cw, ch);
            paint.setFilterBitmap(false);
            canvas.drawBitmap(cropBmp, null, dst, paint);
            cropBmp.recycle();
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dpRound(2f));
        paint.setColor(CROSSHAIR_ARGB);
        canvas.drawRoundRect(dst, dpRound(4f), dpRound(4f), paint);
    }

    private void drawReadout(Canvas canvas, int color, String hex) {
        float cx = getWidth() / 2f;
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(dp(30f));
        canvas.drawText(hex, cx, dp(120f), textPaint);

        textPaint.setTextSize(dp(14f));
        int[] c = ColorUtil.argb(color);
        textPaint.setColor(0xCCFFFFFF);
        canvas.drawText("R:" + c[1] + "  G:" + c[2] + "  B:" + c[3], cx, dp(142f), textPaint);

        textPaint.setTextSize(dp(12f));
        textPaint.setColor(0x99FFFFFF);
        double[] luv = ColorMath.argbToLuv(color, light.white());
        canvas.drawText(getContext().getString(R.string.picker_luv,
                ColorMath.fmt(luv[0], 1), ColorMath.fmt(luv[1], 1), ColorMath.fmt(luv[2], 1),
                light.name), cx, dp(160f), textPaint);
    }

    private void drawCrosshair(Canvas canvas) {
        float cx = focusX();
        float cy = focusY();
        float r = dp(26f);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x66000000);
        canvas.drawCircle(cx, cy, r + dp(6f), paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(CROSSHAIR_ARGB);
        canvas.drawCircle(cx, cy, r, paint);
        canvas.drawLine(cx - r - dp(8f), cy, cx + r + dp(8f), cy, paint);
        canvas.drawLine(cx, cy - r - dp(8f), cx, cy + r + dp(8f), paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, dp(2f), paint);
    }

    private void drawModeButton(Canvas canvas) {
        String label = getContext().getString(
                galleryMode ? R.string.picker_toggle_camera : R.string.picker_toggle_gallery);
        textPaint.setTextSize(dp(13f));
        textPaint.setTextAlign(Paint.Align.CENTER);
        float tw = textPaint.measureText(label);
        float pad = dp(14f);
        float left = dp(10f);
        float top = dp(10f);
        RectF r = new RectF(left, top, left + tw + pad * 2, top + dp(36f));
        modeButtonRect = r;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xE6323B46);
        canvas.drawRoundRect(r, dpRound(18f), dpRound(18f), paint);
        textPaint.setColor(Color.WHITE);
        canvas.drawText(label, r.centerX(), r.bottom - dp(10f), textPaint);
    }

    private void drawChromaButton(Canvas canvas) {
        String label = nv21Order ? "NV21" : "NV12";
        textPaint.setTextSize(dp(13f));
        textPaint.setTextAlign(Paint.Align.CENTER);
        float tw = textPaint.measureText(label);
        float pad = dp(14f);
        float left = modeButtonRect == null ? dp(10f) : modeButtonRect.left;
        float top = (modeButtonRect == null ? dp(10f) : modeButtonRect.bottom) + dp(6f);
        RectF r = new RectF(left, top, left + tw + pad * 2, top + dp(36f));
        chromaButtonRect = r;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xE6323B46);
        canvas.drawRoundRect(r, dpRound(18f), dpRound(18f), paint);
        textPaint.setColor(0xFFCCFFE0);
        canvas.drawText(label, r.centerX(), r.bottom - dp(10f), textPaint);
    }

    private void drawRotateButton(Canvas canvas) {
        String label = getContext().getString(R.string.picker_rotate, ((rot % 4) + 4) % 4 * 90);
        textPaint.setTextSize(dp(13f));
        textPaint.setTextAlign(Paint.Align.CENTER);
        float tw = textPaint.measureText(label);
        float pad = dp(14f);
        float left = chromaButtonRect == null ? dp(10f) : chromaButtonRect.left;
        float top = (chromaButtonRect == null ? dp(10f) : chromaButtonRect.bottom) + dp(6f);
        RectF r = new RectF(left, top, left + tw + pad * 2, top + dp(36f));
        rotButtonRect = r;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xE6323B46);
        canvas.drawRoundRect(r, dpRound(18f), dpRound(18f), paint);
        textPaint.setColor(0xFFDDE9FF);
        canvas.drawText(label, r.centerX(), r.bottom - dp(10f), textPaint);
    }

    /** Draws a stacked rounded chip below `prev`; returns the new rect. */
    private RectF drawChip(Canvas canvas, RectF prev, String label, int chip, int text) {
        textPaint.setTextSize(dp(13f));
        textPaint.setTextAlign(Paint.Align.CENTER);
        float tw = textPaint.measureText(label);
        float pad = dp(14f);
        float left = prev == null ? dp(10f) : prev.left;
        float top = (prev == null ? dp(10f) : prev.bottom) + dp(6f);
        RectF r = new RectF(left, top, left + tw + pad * 2, top + dp(36f));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(chip);
        canvas.drawRoundRect(r, dpRound(18f), dpRound(18f), paint);
        textPaint.setColor(text);
        canvas.drawText(label, r.centerX(), r.bottom - dp(10f), textPaint);
        return r;
    }

    private void drawCalButton(Canvas canvas) {
        String label;
        int chip;
        int text;
        if (calCollecting) {
            label = getContext().getString(R.string.picker_cal_collect, calCount);
            chip = 0xE68A5A12;
            text = 0xFFFFF3C2;
        } else {
            label = getContext().getString(R.string.picker_cal_idle);
            chip = 0xE6323B46;
            text = 0xFFCCE0FF;
        }
        calButtonRect = drawChip(canvas, rotButtonRect, label, chip, text);
    }

    private void drawUndoButton(Canvas canvas) {
        undoButtonRect = drawChip(canvas, calButtonRect,
                getContext().getString(R.string.picker_undo), 0xE64A3232, 0xFFFFD0D0);
    }

    private void drawRawCalButton(Canvas canvas) {
        boolean out = useCal;
        rawCalButtonRect = drawChip(canvas, undoButtonRect == null ? calButtonRect : undoButtonRect,
                getContext().getString(out ? R.string.picker_cal_out : R.string.picker_raw),
                out ? 0xE6125A2C : 0xE6323B46,
                out ? 0xFFCCFFD5 : 0xFFE0E0E0);
    }

    private void drawLightButton(Canvas canvas) {
        RectF above = rawCalButtonRect == null
                ? (undoButtonRect == null ? calButtonRect : undoButtonRect)
                : rawCalButtonRect;
        lightButtonRect = drawChip(canvas, above,
                getContext().getString(R.string.picker_light, light.name), 0xE6323B46, 0xFFBDE0FF);
    }

    private void drawExportButton(Canvas canvas) {
        exportButtonRect = drawChip(canvas, modeButtonRect,
                getContext().getString(R.string.picker_export), 0xE6323B46, 0xFFBDE0FF);
    }

    private void drawAddButton(Canvas canvas, String hex) {
        String label = getContext().getString(R.string.picker_add, hex);
        textPaint.setTextSize(dp(15f));
        textPaint.setTextAlign(Paint.Align.CENTER);
        float tw = textPaint.measureText(label);
        float pad = dp(18f);
        float w2 = tw + pad * 2;
        float cx = getWidth() / 2f;
        float top = getHeight() - dp(8f) - addPanelHeight();
        RectF r = new RectF(cx - w2 / 2f, top - dp(40f), cx + w2 / 2f, top - dp(8f));
        addButtonRect = r;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xE6323B46);
        canvas.drawRoundRect(r, dpRound(20f), dpRound(20f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dpRound(1.5f));
        paint.setColor(CROSSHAIR_ARGB);
        canvas.drawRoundRect(r, dpRound(20f), dpRound(20f), paint);
        textPaint.setColor(Color.WHITE);
        canvas.drawText(label, cx, r.bottom - dp(11f), textPaint);
    }

    private float addPanelHeight() {
        return galleryMode ? dp(48f) : 0f;
    }

    private int palettePanelH() {
        return dpRound(SWATCH_DP) + dpRound(34f) + dpRound(PANEL_PAD_DP) * 2;
    }

    private void drawPalette(Canvas canvas, String currentHex) {
        int w = getWidth();
        int h = getHeight();
        int sw = dpRound(SWATCH_DP);
        int gap = dpRound(SWATCH_GAP_DP);
        int pad = dpRound(PANEL_PAD_DP);
        int panelH = palettePanelH() + Math.round(addPanelHeight());
        float panelTop = h - panelH;
        float panelBottom = h;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xCC101218);
        canvas.drawRect(0, panelTop, w, panelBottom, paint);

        currentIndex = -1;
        int n = palette.size();
        float textY = panelBottom - pad - dp(4f);
        for (int i = 0; i < n && i < 6; i++) {
            float swatchLeft = pad + i * (sw + gap);
            float swatchTop = panelTop + pad + dp(4f);
            RectF r = new RectF(swatchLeft, swatchTop, swatchLeft + sw, swatchTop + sw);
            String hex = palette.get(i);
            if (hex.equalsIgnoreCase(currentHex)) currentIndex = i;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(parseColor(hex));
            canvas.drawRoundRect(r, dpRound(8f), dpRound(8f), paint);
            if (currentIndex == i) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpRound(3f));
                paint.setColor(Color.WHITE);
                canvas.drawRoundRect(r, dpRound(8f), dpRound(8f), paint);
            }
            textPaint.setTextSize(dp(10f));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setColor(0xFFCCCCCC);
            canvas.drawText(hex, r.centerX(), textY, textPaint);
        }

        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(dp(10f));
        textPaint.setColor(0xFF999999);
        String hint;
        if (galleryMode) {
            hint = getContext().getString(R.string.picker_hint_gallery);
        } else if (calCollecting) {
            hint = getContext().getString(R.string.picker_hint_cal);
        } else if (n == 0) {
            hint = getContext().getString(R.string.picker_hint_save);
        } else {
            hint = getContext().getString(R.string.picker_hint_swatch);
        }
        canvas.drawText(hint, pad, panelTop - dp(4f), textPaint);
    }

    private static int parseColor(String hex) {
        try {
            return Color.parseColor(hex);
        } catch (Exception e) {
            return 0xFFCCCCCC;
        }
    }

    private int swatchAt(float x, float y) {
        int pad = dpRound(PANEL_PAD_DP);
        int sw = dpRound(SWATCH_DP);
        int gap = dpRound(SWATCH_GAP_DP);
        float panelTop = getHeight() - (palettePanelH() + addPanelHeight());
        if (y < panelTop || y > getHeight()) return -1;
        for (int i = 0; i < palette.size() && i < 6; i++) {
            float l = pad + i * (sw + gap);
            float t = panelTop + pad + dpRound(4f);
            if (x >= l && x <= l + sw && y >= t && y <= t + sw) {
                return i;
            }
        }
        return -1;
    }

/** True when the press started on a UI control (palette, buttons). */
    private boolean inControls(float x, float y) {
        if (swatchAt(x, y) >= 0) return true;
        if (modeButtonRect != null && modeButtonRect.contains(x, y)) return true;
        if (exportButtonRect != null && galleryMode && exportButtonRect.contains(x, y)) return true;
        if (galleryMode) return addButtonRect != null && addButtonRect.contains(x, y);
        if (chromaButtonRect != null && chromaButtonRect.contains(x, y)) return true;
        if (rotButtonRect != null && rotButtonRect.contains(x, y)) return true;
        if (calButtonRect != null && calButtonRect.contains(x, y)) return true;
        if (undoButtonRect != null && undoButtonRect.contains(x, y)) return true;
        if (lightButtonRect != null && lightButtonRect.contains(x, y)) return true;
        if (rawCalButtonRect != null && rawCalButtonRect.contains(x, y)) return true;
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress()) {
                    float dx = Math.abs(event.getX() - downX);
                    float dy = Math.abs(event.getY() - downY);
                    if ((dx >= dp(10f) || dy >= dp(10f)) && !inControls(downX, downY)) {
                        moveFocus(event.getX(), event.getY());
                    }
                }
                return true;
            case MotionEvent.ACTION_UP: {
                if (!scaleDetector.isInProgress()) {
                    float dx = Math.abs(event.getX() - downX);
                    float dy = Math.abs(event.getY() - downY);
                    if (dx < dp(10f) && dy < dp(10f)) {
                        handleTap(event.getX(), event.getY());
                    }
                }
                return true;
            }
            default:
                return true;
        }
    }

    private void handleTap(float x, float y) {
        if (modeButtonRect != null && modeButtonRect.contains(x, y)) {
            Listener l = listener;
            if (l != null) l.onToggleMode();
            return;
        }
        if (chromaButtonRect != null && !galleryMode && chromaButtonRect.contains(x, y)) {
            Listener l = listener;
            if (l != null) l.onToggleChroma();
            return;
        }
        if (rotButtonRect != null && !galleryMode && rotButtonRect.contains(x, y)) {
            Listener l = listener;
            if (l != null) l.onRotate();
            return;
        }
        if (calButtonRect != null && !galleryMode && calButtonRect.contains(x, y)) {
            Listener l = listener;
            if (l != null) l.onCalToggle();
            return;
        }
        if (undoButtonRect != null && !galleryMode && undoButtonRect.contains(x, y)) {
            Listener l = listener;
            if (l != null) l.onCalUndo();
            return;
        }
        if (lightButtonRect != null && !galleryMode && lightButtonRect.contains(x, y)) {
            Listener l = listener;
            if (l != null) l.onLightCycle();
            return;
        }
        if (rawCalButtonRect != null && !galleryMode && rawCalButtonRect.contains(x, y)) {
            Listener l = listener;
            if (l != null) l.onRawCalToggle();
            return;
        }
        if (exportButtonRect != null && galleryMode && exportButtonRect.contains(x, y)) {
            Listener l = listener;
            if (l != null) l.onExport();
            return;
        }
        int swatch = swatchAt(x, y);
        if (swatch >= 0 && swatch < palette.size()) {
            Listener l = listener;
            if (l != null) l.onCopy(palette.get(swatch));
            return;
        }
        if (galleryMode) {
            if (addButtonRect != null && addButtonRect.contains(x, y)) {
                Listener l = listener;
                int[] mid = focusPixel();
                FrameBuffer f = frame;
                if (l != null && f != null) l.onSave(f.pixelAt(mid[0], mid[1]));
            } else {
                moveFocus(x, y);
            }
            return;
        }
        Listener l = listener;
        FrameBuffer f = frame;
        if (l == null || f == null) return;
        if (calCollecting) {
            l.onCalSample(sampleTarget());
            return;
        }
        int[] mid = focusPixel();
        l.onSave(f.pixelAt(mid[0], mid[1]));
    }

    private void moveFocus(float x, float y) {
        float[] rect = displayRectOf();
        if (rect == null) return;
        focusX = Math.max(rect[0], Math.min(rect[2], x));
        focusY = Math.max(rect[1], Math.min(rect[3], y));
        invalidate();
    }
}