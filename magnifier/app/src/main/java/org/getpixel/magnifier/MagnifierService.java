package org.getpixel.magnifier;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Owns the MediaProjection capture. This must run as a foreground service of
 * type {@code mediaProjection} so capture survives the app leaving the foreground
 * (a hard requirement on Android 14+). Frames land in a shared {@link FrameBuffer}
 * and a floating {@link LensView} renders the magnifier glass over any app.
 */
public class MagnifierService extends Service {

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String ACTION_STOP = "org.getpixel.magnifier.action.STOP";

    private static final int NOTIFICATION_ID = 42;
    private static final String CHANNEL_ID = "magnifier";

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private Handler mainHandler;
    private FrameBuffer frame;
    private LensView lensView;
    private WindowManager windowManager;
    private byte[] rowBuffer;

    private final ImageReader.OnImageAvailableListener imageListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) return;
                Image.Plane plane = image.getPlanes()[0];
                if (plane.getPixelStride() < 3) return;

                int w = image.getWidth();
                int h = image.getHeight();
                int rowStride = plane.getRowStride();
                if (frame == null || frame.width != w || frame.height != h) {
                    frame = new FrameBuffer(w, h);
                }
                if (rowBuffer == null || rowBuffer.length < rowStride) {
                    rowBuffer = new byte[rowStride];
                }

                java.nio.ByteBuffer buf = plane.getBuffer();
                for (int y = 0; y < h; y++) {
                    buf.position(y * rowStride);
                    buf.get(rowBuffer, 0, rowStride);
                    frame.copyRow(y, rowBuffer, plane.getPixelStride(), null);
                }
                frame.markUpdated(SystemClock.uptimeMillis());

                LensView v = lensView;
                if (v != null) {
                    mainHandler.post(() -> v.onFrameUpdated(frame));
                }
            } finally {
                if (image != null) image.close();
            }
        }
    };

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            stopSelf();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(getMainLooper());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startInForeground();
        if (intent != null) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (data != null && projection == null) {
                startCapture(resultCode, data);
            }
        }
        return START_NOT_STICKY;
    }

    private void startInForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void startCapture(int resultCode, Intent resultData) {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            Toast.makeText(this, "Screen capture denied", Toast.LENGTH_SHORT).show();
            stopSelf();
            return;
        }
        projection.registerCallback(projectionCallback, mainHandler);

        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        int w = dm.widthPixels;
        int h = dm.heightPixels;
        int dpi = dm.densityDpi;

        frame = new FrameBuffer(w, h);
        captureThread = new HandlerThread("magnifier-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(imageListener, captureHandler);

        @SuppressWarnings("deprecation")
        VirtualDisplay vd = projection.createVirtualDisplay(
                "getpixel-magnifier", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, captureHandler);
        virtualDisplay = vd;

        showLensOverlay();
    }

    private void showLensOverlay() {
        if (lensView != null) return;
        int lensPx = Math.max(240, Math.round(320 * getResources().getDisplayMetrics().density));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                lensPx, lensPx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = getResources().getConfiguration().getLayoutDirection()
                == android.view.View.LAYOUT_DIRECTION_LTR
                ? android.view.Gravity.TOP | android.view.Gravity.END
                : android.view.Gravity.TOP | android.view.Gravity.START;
        lensView = new LensView(this, windowManager, lp, frame);
        windowManager.addView(lensView, lp);
    }

    private Notification buildNotification() {
        Intent stop = new Intent(this, MagnifierService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(contentPi)
                .setOngoing(true)
                .addAction(0, getString(R.string.notif_stop), stopPi)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        if (lensView != null) {
            windowManager.removeView(lensView);
            lensView = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}