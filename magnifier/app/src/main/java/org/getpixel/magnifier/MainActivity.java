package org.getpixel.magnifier;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;

/** Colour picker: live camera preview or a gallery photo; palette is persisted. */
public class MainActivity extends Activity {

    private static final int REQ_CAMERA = 2001;
    private static final int REQ_PICK_IMAGE = 2002;
    private static final String PREFS = "picker";
    private static final String KEY_PALETTE = "palette";
    private static final String KEY_NV21 = "nv21";
    private static final String KEY_ROT = "rot";
    private static final String KEY_ROT_RESET = "rot_reset_v2";
    private static final String KEY_CAL = "cal";
    private static final String KEY_LIGHT = "light";
    private static final String KEY_USECAL = "usecal";
    private static final int MAX_PIXELS = 2048;

    private CameraController camera;
    private CameraPickerView pickerView;
    private Palette palette = new Palette();
    private boolean galleryMode;
    private boolean nv21 = false;
    private int rotOffset;
    private Calibration cal;
    private LightSource light = LightSource.bst1Estimate();
    private LightSource bst1Light;
    private int lightIndex = 3;
    private boolean useCal = true;
    private boolean calCollecting;

    private final CameraPickerView.Listener listener = new CameraPickerView.Listener() {
        @Override
        public void onSave(int argbColor) {
            String hex = ColorUtil.hexLc(savedColor(argbColor));
            palette.add(hex);
            persistPalette();
            pickerView.setPalette(palette.asList());
            toast(getString(R.string.picker_saved, hex));
        }

        @Override
        public void onCopy(String hex) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("pixel", hex));
                toast(getString(R.string.picker_copied, hex));
            }
        }

        @Override
        public void onRemove(String hex) {
            palette.remove(hex);
            persistPalette();
            pickerView.setPalette(palette.asList());
            toast(getString(R.string.picker_removed, hex));
        }

        @Override
        public void onToggleMode() {
            if (galleryMode) {
                switchToCamera();
            } else {
                switchToGallery();
            }
        }

        @Override
        public void onToggleChroma() {
            nv21 = !nv21;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_NV21, nv21)
                    .apply();
            if (camera != null) camera.setNv21(nv21);
            pickerView.setChroma(nv21);
        }

        @Override
        public void onRotate() {
            rotOffset = (rotOffset + 1) % 4;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt(KEY_ROT, rotOffset)
                    .apply();
            if (camera != null) camera.setRotOffset(rotOffset);
            pickerView.setRotOffset(rotOffset);
        }

        @Override
        public void onCalToggle() {
            calCollecting = !calCollecting;
            pushCalState();
        }

        @Override
        public void onCalSample(int argbColor) {
            if (cal == null) cal = new Calibration();
            int ndx = cal.samples();
            cal.addSample(argbColor, ndx % Calibration.refCount(), light.white());
            if (cal.isFitted()) useCal = true;
            if (lightIndex == 3 && cal.measuredWhiteXyz() != null) {
                bst1Light = LightSource.measuredWhite(cal.measuredWhiteXyz(), "BST1");
                light = bst1Light;
            }
            persistCal();
            pushCalState();
            if (cal.samples() < 3) {
                toast(getString(R.string.picker_patch_want3));
            } else {
                toast(getString(R.string.picker_patch_added, Math.min(cal.samples(), Calibration.refCount())));
            }
        }

        @Override
        public void onCalUndo() {
            if (cal == null || cal.samples() == 0) return;
            cal.popSample();
            if (cal.samples() == 0) useCal = false;
            persistCal();
            pushCalState();
        }

        @Override
        public void onLightCycle() {
            lightIndex = (lightIndex + 1) % 4;
            light = LightSource.preset(lightIndex, bst1Light);
            persistCal();
            pushCalState();
        }

        @Override
        public void onRawCalToggle() {
            if (cal == null || !cal.isFitted()) return;
            useCal = !useCal;
            persistCal();
            pushCalState();
        }

        @Override
        public void onExport() {
            exportGallery();
        }
    };

    private int savedColor(int argbColor) {
        if (useCal && cal != null && cal.isFitted()) {
            return cal.apply(argbColor);
        }
        return argbColor;
    }

    private void pushCalState() {
        pickerView.setCaliber(cal, useCal, calCollecting, cal == null ? 0 : cal.samples(), light);
    }

    private void persistCal() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_CAL, cal == null ? "" : cal.encode())
                .putInt(KEY_LIGHT, lightIndex)
                .putBoolean(KEY_USECAL, useCal)
                .apply();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        palette = Palette.decode(prefs.getString(KEY_PALETTE, ""));
        nv21 = prefs.getBoolean(KEY_NV21, false);
        // One-time migration: earlier builds stored a stale calibration offset
        // (rot=2 -> quarter-turn 3), which is upside down against the pinned
        // ruby baseline (quarter-turn 1). Start from 0 again.
        if (!prefs.contains(KEY_ROT_RESET)) {
            prefs.edit().putInt(KEY_ROT, 0).putBoolean(KEY_ROT_RESET, true).apply();
        }
        rotOffset = prefs.getInt(KEY_ROT, 0);
        cal = Calibration.decode(prefs.getString(KEY_CAL, ""));
        lightIndex = prefs.getInt(KEY_LIGHT, 3);
        useCal = prefs.getBoolean(KEY_USECAL, true);
        if (lightIndex == 3 && cal.isFitted() && cal.measuredWhiteXyz() != null) {
            bst1Light = LightSource.measuredWhite(cal.measuredWhiteXyz(), "BST1");
        }
        light = LightSource.preset(lightIndex, bst1Light);

        pickerView = new CameraPickerView(this);
        pickerView.setListener(listener);
        pickerView.setPalette(palette.asList());
        pickerView.setChroma(nv21);
        pickerView.setRotOffset(rotOffset);
        pushCalState();
        setContentView(pickerView);

        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            startCamera();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (ok) {
            startCamera();
        } else {
            toast(getString(R.string.picker_permission));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!galleryMode && camera == null
                && checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (camera != null) {
            camera.close();
            camera = null;
        }
    }

    private void startCamera() {
        if (galleryMode || camera != null) return;
        camera = new CameraController();
        camera.setNv21(nv21);
        camera.setRotOffset(rotOffset);
        camera.open(this, new CameraController.Listener() {
            @Override
            public void onFrame(FrameBuffer f, int rot) {
                if (!galleryMode) {
                    pickerView.setGalleryMode(false, null);
                    pickerView.onFrame(f, rot);
                }
            }

            @Override
            public void onError(String message) {
                toast(message);
            }
        });
        pickerView.setGalleryMode(false, null);
    }

    private void switchToGallery() {
        galleryMode = true;
        if (camera != null) {
            camera.close();
            camera = null;
        }
        pickForImage();
    }

    private void switchToCamera() {
        galleryMode = false;
        startCamera();
    }

    private FrameBuffer frameGallery;

    private void pickForImage() {
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.setType("image/*");
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(pick, REQ_PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGE) {
            Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
            onImagePicked(uri);
        }
    }

    private void onImagePicked(Uri uri) {
        if (uri == null) {
            switchToCamera();
            return;
        }
        try {
            Bitmap bmp = decodeSampled(uri);
            if (bmp == null) {
                toast(getString(R.string.picker_decode_failed));
                switchToCamera();
                return;
            }
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            int[] argb = new int[w * h];
            bmp.getPixels(argb, 0, w, 0, 0, w, h);
            bmp.recycle();
            frameGallery = FrameBuffer.fromArgb(argb, w, h);
            pickerView.setGalleryMode(true, frameGallery);
        } catch (Exception e) {
            toast(getString(R.string.picker_decode_failed));
            switchToCamera();
        }
    }

    private Bitmap decodeSampled(Uri uri) throws java.io.IOException {
        ContentResolver cr = getContentResolver();
        int orientation = 0;
        try (InputStream is = cr.openInputStream(uri)) {
            android.media.ExifInterface exif = new android.media.ExifInterface(is);
            orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
        } catch (Throwable ignored) {
            orientation = android.media.ExifInterface.ORIENTATION_NORMAL;
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = cr.openInputStream(uri)) {
            BitmapFactory.decodeStream(is, null, bounds);
        }
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (longest / sample / 2 >= MAX_PIXELS) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream is = cr.openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(is, null, opts);
        }
        return rotateByExif(bmp, orientation);
    }

    /** Applies EXIF orientation (90/180/270) so gallery photos show upright. */
    private Bitmap rotateByExif(Bitmap bmp, int orientation) {
        if (bmp == null) return null;
        int deg;
        switch (orientation) {
            case android.media.ExifInterface.ORIENTATION_ROTATE_180:
                deg = 180;
                break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_90:
                deg = 90;
                break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_270:
                deg = 270;
                break;
            default:
                return bmp;
        }
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postRotate(deg);
        Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (out != bmp) bmp.recycle();
        return out;
    }

    private void persistPalette() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_PALETTE, palette.encode())
                .apply();
    }

    /** Applies the lighting profile to the gallery photo and exports the corrected
     *  PNG plus an L*u*v* report to any app (share sheet). */
    private void exportGallery() {
        FrameBuffer fb = frameGallery;
        if (fb == null) {
            toast(getString(R.string.picker_decode_failed));
            return;
        }
        try {
            int w = fb.width;
            int h = fb.height;
            int[] px = new int[w * h];
            fb.copyRegion(0, 0, w, h, px);
            if (useCal && cal != null && cal.isFitted()) {
                for (int i = 0; i < px.length; i++) {
                    px[i] = cal.apply(px[i]);
                }
            }
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            out.setPixels(px, 0, w, 0, 0, w, h);

            File dir = new File(getCacheDir(), "export");
            if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("no cache dir");
            String base = "pixelpick_" + System.currentTimeMillis();
            File png = new File(dir, base + ".png");
            try (FileOutputStream fos = new FileOutputStream(png)) {
                if (!out.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                    throw new java.io.IOException("png encode failed");
                }
            }
            out.recycle();

            File txt = new File(dir, base + ".txt");
            try (PrintWriter pw = new PrintWriter(new FileOutputStream(txt))) {
                pw.println("Pixel Pick report");
                pw.println("light: " + light.name + "  white(XYZ) = "
                        + ColorMath.fmt(light.xn, 4) + " "
                        + ColorMath.fmt(light.yn, 4) + " "
                        + ColorMath.fmt(light.zn, 4));
                pw.println("profile: " + (cal != null && cal.isFitted() ? "fitted" : "none"));
                if (cal != null && cal.isFitted()) {
                    double[][] m = cal.matrix();
                    for (int r = 0; r < 3; r++) {
                        pw.println("  row" + (r + 1) + " = " + ColorMath.fmt(m[r][0], 4)
                                + " " + ColorMath.fmt(m[r][1], 4) + " " + ColorMath.fmt(m[r][2], 4));
                    }
                }
                pw.println("mode: " + (cal != null && cal.isFitted() && useCal ? "CALIBRATED" : "RAW"));
                pw.println();
                pw.println("#rrggbb  R   G   B   |  X    Y    Z   |  L*   u*   v*");
                double[] white = light.white();
                for (String hex : palette.asList()) {
                    int argb = parseColor(hex);
                    int[] c = ColorUtil.argb(argb);
                    double[] xyz = ColorMath.argbToXyz(argb);
                    double[] luv = ColorMath.xyzToLuv(xyz, white);
                    pw.println(hex + "  " + ColorMath.fmt(c[1], 0) + " " + ColorMath.fmt(c[2], 0) + " "
                            + ColorMath.fmt(c[3], 0) + "  |  " + ColorMath.fmt(xyz[0], 3) + " "
                            + ColorMath.fmt(xyz[1], 3) + " " + ColorMath.fmt(xyz[2], 3) + "  |  "
                            + ColorMath.fmt(luv[0], 1) + " " + ColorMath.fmt(luv[1], 1) + " "
                            + ColorMath.fmt(luv[2], 1));
                }
            }

            Intent send = new Intent(Intent.ACTION_SEND_MULTIPLE);
            send.setType("*/*");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.putExtra(Intent.EXTRA_SUBJECT, "Pixel Pick export");
            ArrayList<Uri> uris = new ArrayList<>();
            uris.add(uriOf(png.getName()));
            uris.add(uriOf(txt.getName()));
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            send.putExtra(Intent.EXTRA_TEXT, "Pixel Pick: corrected photo + L*u*v* report");
            startActivity(Intent.createChooser(send, getString(R.string.picker_export)));
        } catch (Exception e) {
            toast(getString(R.string.picker_decode_failed));
        }
    }

    private static Uri uriOf(String name) {
        return new Uri.Builder().scheme("content").authority(ExportProvider.AUTHORITY)
                .path("/" + name).build();
    }

    private static int parseColor(String hex) {
        try {
            return android.graphics.Color.parseColor(hex);
        } catch (Exception e) {
            return 0xFFCCCCCC;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}