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

import java.io.InputStream;

/** Colour picker: live camera preview or a gallery photo; palette is persisted. */
public class MainActivity extends Activity {

    private static final int REQ_CAMERA = 2001;
    private static final int REQ_PICK_IMAGE = 2002;
    private static final String PREFS = "picker";
    private static final String KEY_PALETTE = "palette";
    private static final String KEY_NV21 = "nv21";
    private static final String KEY_ROT = "rot";
    private static final int MAX_PIXELS = 2048;

    private CameraController camera;
    private CameraPickerView pickerView;
    private Palette palette = new Palette();
    private boolean galleryMode;
    private boolean nv21 = false;
    private int rotOffset;

    private final CameraPickerView.Listener listener = new CameraPickerView.Listener() {
        @Override
        public void onSave(int argbColor) {
            String hex = ColorUtil.hexLc(argbColor);
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
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        palette = Palette.decode(prefs.getString(KEY_PALETTE, ""));
        nv21 = prefs.getBoolean(KEY_NV21, false);
        rotOffset = prefs.getInt(KEY_ROT, 0);

        pickerView = new CameraPickerView(this);
        pickerView.setListener(listener);
        pickerView.setPalette(palette.asList());
        pickerView.setChroma(nv21);
        pickerView.setRotOffset(rotOffset);
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

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}