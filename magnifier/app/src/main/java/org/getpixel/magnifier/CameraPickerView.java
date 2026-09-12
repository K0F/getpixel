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
    private int cachedPixels = -1;
    private List<String> palette = java.util.Collections.emptyList();
    private int currentIndex = -1;
    private boolean galleryMode;
    private float focusX = -1f;
    private float focusY = -1f;
    private RectF addButtonRect;
    private RectF modeButtonRect;
    private float lastRawX;
    private float lastRawY;

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
        FrameBuffer f = frame;
        if (f == null) return new int[]{0, 0};
        float[] rect = displayRectOf();
        if (rect == null) return new int[]{0, 0};
        return CamMath.toFrame(focusX() - rect[0], focusY() - rect[1], f.width, f.height, rot);
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

        int[] mid = CamMath.toFrame(focusX() - rect[0], focusY() - rect[1], f.width, f.height, rot);
        int focusColor = f.pixelAt(mid[0], mid[1]);
        String hex = ColorUtil.hexLc(focusColor);

        drawInset(canvas, f, mid[0], mid[1]);
        drawReadout(canvas, focusColor, hex);
        drawCrosshair(canvas);
        drawModeButton(canvas);
        if (galleryMode) drawAddButton(canvas, hex);
        drawPalette(canvas, hex);
    }

    private void drawPreview(Canvas canvas, FrameBuffer f, float[] rect) {
        int w = f.width;
        int h = f.height;
        int need = w * h;
        if (previewBitmap == null || previewBitmap.getWidth() != w || previewBitmap.getHeight() != h
                || cachedPixels != need) {
            if (previewBitmap != null) previewBitmap.recycle();
            previewBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            cachedPixels = need;
        }
        int[] scratch = new int[need];
        f.copyRegion(0, 0, w, h, scratch);
        previewBitmap.setPixels(scratch, 0, w, 0, 0, w, h);
        paint.setFilterBitmap(true);
        canvas.drawBitmap(previewBitmap, null, new RectF(rect[0], rect[1], rect[2], rect[3]), paint);
    }

    private void drawInset(Canvas canvas, FrameBuffer f, int midX, int midY) {
        int side = dpRound(INSET_DP);
        int cropPx = LensMath.cropForZoom(side, zoom);
        int[] origin = LensMath.cropOrigin(midX, midY, cropPx, cropPx, f.width, f.height);
        int left = origin[0];
        int top = origin[1];
        int cw = Math.min(cropPx, Math.max(0, f.width - left));
        int ch = Math.min(cropPx, Math.max(0, f.height - top));

        int right = getWidth() - dpRound(10f);
        int topY = dpRound(46f);
        RectF dst = new RectF(right - side, topY, right, topY + side);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xBB000000);
        canvas.drawRect(dst, paint);

        if (cw > 0 && ch > 0) {
            int[] crop = new int[cw * ch];
            f.copyRegion(left, top, cw, ch, crop);
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
        String hint = (n == 0)
                ? getContext().getString(R.string.picker_hint_save)
                : getContext().getString(R.string.picker_hint_swatch);
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

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                lastRawX = event.getRawX();
                lastRawY = event.getRawY();
                return true;
            case MotionEvent.ACTION_UP: {
                float dx = Math.abs(event.getRawX() - lastRawX);
                float dy = Math.abs(event.getRawY() - lastRawY);
                if (!scaleDetector.isInProgress() && dx < dp(24f) && dy < dp(24f)) {
                    handleTap(event.getX(), event.getY());
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