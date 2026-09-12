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
import android.widget.Toast;

/**
 * The on-screen magnifier "glass": a tiny 20x20 px pixel-exact crop of the
 * captured frame centred on the pixel under the glass centre, with a
 * crosshair. The actual view is larger than the glass so the window is easy
 * to grab; only the 20x20 centre is drawn.
 *
 * Interactions: drag = move the glass, pinch = zoom (1x-12x),
 * long-press = copy the centre pixel's #RRGGBB to the clipboard.
 */
public class LensView extends View {

    private static final int LENS_PX = 20;
    private static final int CROSSHAIR_ARGB = 0xFF33FF99;
    private static final float CROSSHAIR_STROKE = 1f;

    private final WindowManager windowManager;
    private final WindowManager.LayoutParams params;
    private volatile FrameBuffer frame;
    private boolean hasFrame;
    private final GestureDetector gestureDetector;
    private final ScaleGestureDetector scaleDetector;
    private final Paint paint = new Paint();
    private int[] cropScratch = new int[0];
    private Bitmap cropBitmap;
    private int cropW;
    private int cropH;
    private float zoom = 3f;
    private float downRawX;
    private float downRawY;
    private int startX;
    private int startY;

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
        setBackgroundColor(Color.TRANSPARENT);
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

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN && event.getPointerCount() == 1) {
            downRawX = event.getRawX();
            downRawY = event.getRawY();
            startX = params.x;
            startY = params.y;
            return true;
        }
        if (event.getPointerCount() == 1 && !scaleDetector.isInProgress()
                && action == MotionEvent.ACTION_MOVE) {
            params.x = startX + Math.round(event.getRawX() - downRawX);
            params.y = startY + Math.round(event.getRawY() - downRawY);
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
        String msg = ColorUtil.hex(color) + " " + getContext().getString(R.string.lens_copied);
        Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        FrameBuffer f = frame;
        int w = getWidth();
        int h = getHeight();
        int gx = w / 2;
        int gy = h / 2;

        if (!hasFrame || f == null) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xCC000000);
            canvas.drawRect(gx - LENS_PX / 2, gy - LENS_PX / 2,
                    gx + LENS_PX / 2, gy + LENS_PX / 2, paint);
            return;
        }

        int cx = params.x + w / 2;
        int cy = params.y + h / 2;
        int cropPx = LensMath.cropForZoom(LENS_PX, zoom);

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
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.BLACK);
            canvas.drawColor(Color.BLACK);
            Rect dst = new Rect(gx - LENS_PX / 2, gy - LENS_PX / 2, gx + LENS_PX / 2, gy + LENS_PX / 2);
            canvas.drawBitmap(cropBitmap, null, dst, paint);
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xCC000000);
            canvas.drawRect(gx - LENS_PX / 2, gy - LENS_PX / 2,
                    gx + LENS_PX / 2, gy + LENS_PX / 2, paint);
        }

        drawCrosshair(canvas, gx, gy);
    }

    private int[] ensureCropScratch(int w, int h) {
        int need = w * h;
        if (cropScratch.length < need) {
            cropScratch = new int[need];
        }
        return cropScratch;
    }

    private void drawCrosshair(Canvas canvas, int gx, int gy) {
        paint.setStrokeWidth(CROSSHAIR_STROKE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(CROSSHAIR_ARGB);
        int half = LENS_PX / 2;
        canvas.drawLine(gx, gy - half, gx, gy + half, paint);
        canvas.drawLine(gx - half, gy, gx + half, gy, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(gx, gy, 1f, paint);
    }
}