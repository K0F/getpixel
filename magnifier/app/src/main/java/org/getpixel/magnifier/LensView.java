package org.getpixel.magnifier;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;

/**
 * The on-screen magnifier "glass". Draws a magnified, pixel-exact crop of the
 * captured frame centred on the pixel under the glass centre, with a
 * crosshair and live colour readout.
 *
 * Interactions: drag = move the glass, pinch = zoom (1x-12x),
 * long-press = copy the centre pixel's #RRGGBB to the clipboard.
 */
public class LensView extends View {

    private static final int READOUT_BG = 0xCC111111;
    private static final int READOUT_FG = 0xFFFFFFFF;
    private static final int CROSSHAIR_ARGB = 0xFF33FF99;

    private final WindowManager windowManager;
    private final WindowManager.LayoutParams params;
    private volatile FrameBuffer frame;
    private boolean hasFrame;
    private final GestureDetector gestureDetector;
    private final ScaleGestureDetector scaleDetector;
    private final Paint paint = new Paint();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[] cropScratch = new int[0];
    private Bitmap cropBitmap;
    private int cropW;
    private int cropH;
    private float zoom = 3f;
    private String readoutText = "";

    public LensView(Context context, WindowManager wm, WindowManager.LayoutParams lp, FrameBuffer initial) {
        super(context);
        this.windowManager = wm;
        this.params = lp;
        this.frame = initial;
        this.gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                copyCentrePixel();
            }
        });
        this.scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoom = LensMath.clampZoom(zoom * d.getScaleFactor());
                invalidate();
                return true;
            }
        });
        textPaint.setColor(READOUT_FG);
        textPaint.setTextSize(dp(13f));
        textPaint.setAntiAlias(true);
        setBackgroundColor(Color.TRANSPARENT);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private int dpRound(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** UI-thread callback storing the latest captured frame. */
    public void onFrameUpdated(FrameBuffer newFrame) {
        frame = newFrame;
        hasFrame = true;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        if (event.getPointerCount() == 1 && !scaleDetector.isInProgress()
                && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            params.x += Math.round(event.getRawX());
            params.y += Math.round(event.getRawY());
            clampPosition();
            windowManager.updateViewLayout(this, params);
            invalidate();
        }
        return true;
    }

    private void clampPosition() {
        int maxW;
        int maxH;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
            maxW = bounds.width();
            maxH = bounds.height();
        } else {
            @SuppressWarnings("deprecation")
            DisplayMetrics dm = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(dm);
            maxW = dm.widthPixels;
            maxH = dm.heightPixels;
        }
        if (params.x + getWidth() > maxW) params.x = maxW - getWidth();
        if (params.y + getHeight() > maxH) params.y = maxH - getHeight();
        if (params.x < 0) params.x = 0;
        if (params.y < 0) params.y = 0;
    }

    private void copyCentrePixel() {
        FrameBuffer f = frame;
        if (f == null || !hasFrame) return;
        int cx = params.x + getWidth() / 2;
        int cy = params.y + getHeight() / 2;
        int color = f.pixelAt(cx, cy);
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("pixel", ColorUtil.hex(color)));
        }
        readoutText = ColorUtil.report(cx, cy, color) + "  [" + getContext().getString(R.string.lens_copied)
                + " " + ColorUtil.hex(color) + "]";
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        FrameBuffer f = frame;
        int w = getWidth();
        int h = getHeight();

        if (!hasFrame || f == null) {
            canvas.drawColor(0xCC000000);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(getContext().getString(R.string.lens_waiting), w / 2f, h / 2f, textPaint);
            return;
        }

        int cx = params.x + w / 2;
        int cy = params.y + h / 2;
        int cropPx = LensMath.cropForZoom(Math.max(w, h), zoom);

        int[] origin = LensMath.cropOrigin(cx, cy, cropPx, cropPx, f.width, f.height);
        int left = origin[0];
        int top = origin[1];
        int cw = Math.min(cropPx, Math.max(0, f.width - left));
        int ch = Math.min(cropPx, Math.max(0, f.height - top));

        if (cw > 0 && ch > 0) {
            int[] scratch = ensureCropScratch(cw, ch);
            f.copyRegion(left, top, cw, ch, scratch);
            if (cropBitmap == null || cropBitmap.getWidth() != cw || cropBitmap.getHeight() != ch) {
                if (cropBitmap != null) cropBitmap.recycle();
                cropBitmap = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
                cropW = cw;
                cropH = ch;
            }
            cropBitmap.setPixels(scratch, 0, cw, 0, 0, cw, ch);

            paint.setFilterBitmap(false);
            paint.setColor(Color.BLACK);
            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(cropBitmap, null, new Rect(0, 0, w, h), paint);
        } else {
            canvas.drawColor(0xCC000000);
        }

        drawCrosshair(canvas, w, h);

        int color = f.pixelAt(cx, cy);
        readoutText = ColorUtil.report(cx, cy, color);
        drawReadout(canvas, readoutText);
    }

    private int[] ensureCropScratch(int w, int h) {
        int need = w * h;
        if (cropScratch.length < need) {
            cropScratch = new int[need];
        }
        return cropScratch;
    }

    private void drawCrosshair(Canvas canvas, int w, int h) {
        float cx = w / 2f;
        float cy = h / 2f;
        paint.setStrokeWidth(dp(1.2f));
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(CROSSHAIR_ARGB);
        canvas.drawLine(cx, 0, cx, h, paint);
        canvas.drawLine(0, cy, w, cy, paint);
        canvas.drawCircle(cx, cy, dp(16f), paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, dp(2f), paint);
    }

    private void drawReadout(Canvas canvas, String text) {
        Rect bounds = new Rect();
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.getTextBounds(text, 0, text.length(), bounds);
        int pad = dpRound(10f);
        float textW = bounds.width();
        float textH = bounds.height();
        float left = getWidth() / 2f - textW / 2f - pad;
        float top = dpRound(8f);
        float right = getWidth() / 2f + textW / 2f + pad;
        float bottom = top + textH + pad * 2;
        left = Math.max(0, left);
        right = Math.min(getWidth(), right);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(READOUT_BG);
        canvas.drawRoundRect(left, top, right, bottom, dpRound(6f), dpRound(6f), paint);
        textPaint.setColor(READOUT_FG);
        canvas.drawText(text, left + pad, bottom - pad, textPaint);
    }
}