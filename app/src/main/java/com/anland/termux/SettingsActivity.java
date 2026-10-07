package com.anland.termux;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;


public class SettingsActivity extends ComponentActivity {
    private static final String TAG = "AnlandSettings";
    private static final String PREFS_NAME = "anland_settings";
    private static final String KEY_BOUND_KEYCODE = "bound_keycode";
    private static final int UNBOUND = -1;

    // SAF file-picker request code for loading custom extra-keys layout JSON.
    private static final int REQ_PICK_LAYOUT = 2001;
    private static final String KEY_EXTRA_KEYS_LAYOUT = "extra_keys_layout";

    // Key-binding capture state, exposed to Compose via bridge methods.
    private CountDownTimer listenTimer;
    private boolean isListening = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ComposeSettings.install(this);
    }

    // ============================================================
    // Compose bridge methods
    // ============================================================

    /** Entry point used by the Compose settings surface. */
    public void startKeyBindingFromCompose() {
        startListening();
    }

    /** Exposes the transient key-binding state to Compose. */
    public boolean isKeyBindingListening() {
        return isListening;
    }

    /** Entry point used by the Compose custom-layout editor. */
    public void pickLayoutFileFromCompose() {
        pickLayoutFile();
    }

    // ============================================================
    // Key-binding capture logic
    // ============================================================

    private void startListening() {
        if (isListening) return;
        isListening = true;

        listenTimer = new CountDownTimer(5000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {}

            @Override
            public void onFinish() {
                finishListening(UNBOUND);
            }
        }.start();
    }

    private void finishListening(int keycode) {
        isListening = false;
        if (listenTimer != null) {
            listenTimer.cancel();
            listenTimer = null;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putInt(KEY_BOUND_KEYCODE, keycode).apply();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!isListening) return super.onKeyDown(keyCode, event);

        // Ignore generic Virtual Keyboard keycode (it's a placeholder)
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return true;

        finishListening(keyCode);
        Log.i(TAG, "Bound keycode: " + keyCode);
        return true;
    }

    // ============================================================
    // Custom layout file picker (Storage Access Framework)
    // ============================================================

    // Launch the system document picker to load a layout JSON from any provider
    // (Downloads, Drive, etc.). Uses SAF, so no storage permission is required.
    private void pickLayoutFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
            new String[]{"application/json", "text/plain"});
        try {
            startActivityForResult(intent, REQ_PICK_LAYOUT);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.toast_no_picker, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_LAYOUT || resultCode != RESULT_OK || data == null)
            return;
        Uri uri = data.getData();
        if (uri == null) return;
        String text = readTextFromUri(uri);
        if (text == null) {
            Toast.makeText(this, R.string.toast_read_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        // Compose observes the preference change and updates the editor.
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_EXTRA_KEYS_LAYOUT, text).apply();
    }

    private String readTextFromUri(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            if (in == null) return null;
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "readTextFromUri failed", e);
            return null;
        }
    }
}
