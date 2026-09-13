package org.getpixel.magnifier;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Display;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.Collections;

/** Owns a Camera2 session + ImageReader and feeds a {@link FrameBuffer}. */
public final class CameraController {

    public interface Listener {
        void onFrame(FrameBuffer frame, int rotQuarters);

        void onError(String message);
    }

    private CameraManager cameraManager;
    private String cameraId;
    private ImageReader imageReader;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private Handler mainHandler;
    private boolean running;
    private Listener listener;
    private FrameBuffer frame;
    private int baseRotQuarters;
    private int rotOffsetQuarters;
    private boolean useYuv;
    private volatile boolean nv21 = false;

    /** Sets the interleaved chroma ordering (NV21 = V-first), e.g. from the UV chip. */
    public void setNv21(boolean nv21) {
        this.nv21 = nv21;
    }

    /** Extra quarter-turns (0-3, clockwise) applied on top of the sensor/display base. */
    public void setRotOffset(int quarters) {
        this.rotOffsetQuarters = ((quarters % 4) + 4) % 4;
    }

    public int effectiveRotQuarters() {
        return ((baseRotQuarters + rotOffsetQuarters) % 4 + 4) % 4;
    }

    public void open(Context context, Listener listener) {
        this.listener = listener;
        this.mainHandler = new Handler(context.getMainLooper());

        bgThread = new HandlerThread("camera-picker");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());

        try {
            cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            cameraId = backCamera();
            CameraCharacteristics cc = cameraManager.getCameraCharacteristics(cameraId);
            int sensorOrientation = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int displayRotation = displayRotation(context);
            baseRotQuarters = ((sensorOrientation + displayRotation + 360) % 360) / 90;
            running = true;

            int[] size = choosePreviewSize(cc);
            int w = size[0];
            int h = size[1];
            frame = new FrameBuffer(w, h);

            boolean rgba = supportsFormat(cc, PixelFormat.RGBA_8888);
            int format = rgba ? PixelFormat.RGBA_8888 : ImageFormat.YUV_420_888;
            useYuv = !rgba;
            imageReader = ImageReader.newInstance(w, h, format, 3);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, bgHandler);

            if (checkCameraPermission(context)) {
                cameraManager.openCamera(cameraId, stateCallback, bgHandler);
            } else {
                reportError("Camera permission not granted");
            }
        } catch (Exception e) {
            reportError("Camera open failed: " + e.getMessage());
        }
    }

    private boolean checkCameraPermission(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.CAMERA)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** First camera with LENS_FACING_BACK, else any. */
    private String backCamera() throws android.hardware.camera2.CameraAccessException {
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics cc = cameraManager.getCameraCharacteristics(id);
            Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return cameraManager.getCameraIdList()[0];
    }

    private int displayRotation(Context c) {
        Display d = ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        switch (d.getRotation()) {
            case android.view.Surface.ROTATION_90:
                return 90;
            case android.view.Surface.ROTATION_180:
                return 180;
            case android.view.Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }

    private boolean supportsFormat(CameraCharacteristics cc, int format) {
        try {
            return cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(format).length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private int[] choosePreviewSize(CameraCharacteristics cc) throws Exception {
        int[][] cands = trySizes(cc, PixelFormat.RGBA_8888);
        if (cands.length == 0) {
            cands = trySizes(cc, ImageFormat.YUV_420_888);
        }
        if (cands.length == 0) {
            return new int[]{640, 480};
        }
        int[] best = cands[0];
        int bestArea = best[0] * best[1];
        for (int[] s : cands) {
            int area = s[0] * s[1];
            boolean closer = bestArea < 350_000 ? area > bestArea : area < bestArea;
            if (area <= 921_600 && closer) {
                best = s;
                bestArea = area;
            }
        }
        return best;
    }

    private int[][] trySizes(CameraCharacteristics cc, int format) {
        try {
            android.util.Size[] sizes = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(format);
            int[][] out = new int[sizes.length][2];
            for (int i = 0; i < sizes.length; i++) {
                out[i][0] = sizes[i].getWidth();
                out[i][1] = sizes[i].getHeight();
            }
            return out;
        } catch (Exception e) {
            return new int[0][0];
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice cameraDevice) {
            camera = cameraDevice;
            try {
                CaptureRequest.Builder req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                req.addTarget(imageReader.getSurface());
                req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

                    camera.createCaptureSession(Collections.singletonList(imageReader.getSurface()),
                            new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession s) {
                                session = s;
                                try {
                                    s.setRepeatingRequest(req.build(), captureCallback, bgHandler);
                                } catch (Exception e) {
                                    reportError("Preview start failed: " + e.getMessage());
                                }
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession s) {
                                reportError("Camera configuration failed");
                            }
                        }, bgHandler);
            } catch (Exception e) {
                reportError("Camera open failed: " + e.getMessage());
            }
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            closeInternal();
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            reportError("Camera error " + error);
        }
    };

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
        }
    };

    private void onImageAvailable(ImageReader reader) {
        if (!running) return;
        Image image;
        try {
            image = reader.acquireLatestImage();
        } catch (Exception e) {
            return;
        }
        if (image == null) return;
        try {
            try {
                decodeFrame(image);
            } catch (Throwable t) {
                android.util.Log.w("camera-picker", "frame decode failed: " + t);
            }
            frame.markUpdated(android.os.SystemClock.uptimeMillis());
            FrameBuffer f = frame;
            int rot = effectiveRotQuarters();
            Listener l = listener;
            if (l != null) {
                mainHandler.post(() -> l.onFrame(f, rot));
            }
        } finally {
            image.close();
        }
    }

    private void decodeFrame(Image image) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (frame == null || frame.width != w || frame.height != h) {
            frame = new FrameBuffer(w, h);
        }
        Image.Plane plane0 = image.getPlanes()[0];
        if (useYuv) {
            decodeYuv(image);
        } else {
            copyRgba(plane0, h);
        }
    }

    private void copyRgba(Image.Plane plane0, int h) {
        int rowStride = plane0.getRowStride();
        int pixelStride = plane0.getPixelStride();
        ByteBuffer buf = plane0.getBuffer();
        int w = frame.width;
        byte[] row = new byte[rowStride];
        for (int y = 0; y < h; y++) {
            buf.position(y * rowStride);
            buf.get(row, 0, rowStride);
            frame.copyRow(y, row, pixelStride, null);
        }
    }

    private void decodeYuv(Image image) {
        Image.Plane p0 = image.getPlanes()[0];
        Image.Plane p1 = image.getPlanes()[1];
        Image.Plane p2 = image.getPlanes()[2];
        Yuv420.Planes planes = new Yuv420.Planes(
                p0.getBuffer(), p1.getBuffer(), p2.getBuffer(),
                p0.getRowStride(), p1.getRowStride(), p2.getRowStride(),
                p1.getPixelStride(), p2.getPixelStride(),
                frame.width, frame.height, nv21);
        for (int y = 0; y < frame.height; y++) {
            for (int x = 0; x < frame.width; x++) {
                frame.copyPixel(x, y, Yuv420.argbAt(planes, x, y));
            }
        }
    }

    public void close() {
        closeInternal();
    }

    private void closeInternal() {
        running = false;
        try {
            if (session != null) {
                session.close();
                session = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (camera != null) {
                camera.close();
                camera = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        } catch (Exception ignored) {
        }
        if (bgThread != null) {
            bgThread.quitSafely();
            bgThread = null;
        }
    }

    private void reportError(String message) {
        Listener l = listener;
        if (l != null && mainHandler != null) {
            mainHandler.post(() -> l.onError(message));
        }
    }
}