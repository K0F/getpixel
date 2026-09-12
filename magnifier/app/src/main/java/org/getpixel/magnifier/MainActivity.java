package org.getpixel.magnifier;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/** Entry point: grants overlay + notification permissions, then starts capture. */
public class MainActivity extends Activity {

    private static final int REQ_MEDIA_PROJECTION = 1001;
    private static final int REQ_OVERLAY = 1002;

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        status.setMovementMethod(new ScrollingMovementMethod());
        if (savedInstanceState != null) {
            status.setText(savedInstanceState.getCharSequence("status"));
        }

        ((Button) findViewById(R.id.btn_start)).setOnClickListener(this::onStartClicked);
        ((Button) findViewById(R.id.btn_stop)).setOnClickListener(v ->
                stopService(new Intent(MainActivity.this, MagnifierService.class)));

        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        out.putCharSequence("status", status.getText());
        super.onSaveInstanceState(out);
    }

    private void onStartClicked(View v) {
        if (!Settings.canDrawOverlays(this)) {
            appendStatus(getString(R.string.status_overlay_needed));
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            appendStatus(getString(R.string.status_notification_needed));
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 42);
            return;
        }
        requestProjection();
    }

    private void requestProjection() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) return;
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Intent svc = new Intent(this, MagnifierService.class);
                svc.putExtra(MagnifierService.EXTRA_RESULT_CODE, resultCode);
                svc.putExtra(MagnifierService.EXTRA_RESULT_DATA, data);
                startForegroundService(svc);
                appendStatus(getString(R.string.status_capturing));
            } else {
                appendStatus(getString(R.string.status_denied));
            }
        } else if (requestCode == REQ_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                appendStatus(getString(R.string.status_overlay_ok));
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                        || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                                == PackageManager.PERMISSION_GRANTED) {
                    requestProjection();
                }
            } else {
                appendStatus(getString(R.string.status_overlay_no));
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        appendStatus(ok ? getString(R.string.status_notification_ok)
                        : getString(R.string.status_notification_no));
        if (ok) requestProjection();
    }

    private void refreshStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean notif = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED;
        appendStatus(getString(overlay ? R.string.status_overlay_have : R.string.status_overlay_missing));
        appendStatus(getString(notif ? R.string.status_notification_have : R.string.status_notification_missing));
    }

    private void appendStatus(String line) {
        CharSequence prev = status.getText();
        String sep = (prev == null || prev.length() == 0) ? "" : "\n";
        status.setText(prev + sep + line);
    }
}