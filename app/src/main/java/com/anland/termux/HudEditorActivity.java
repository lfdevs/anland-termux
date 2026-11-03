package com.anland.termux;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.anland.termux.hud.HudOverlayView;

/** Standalone editor for the on-screen HUD layout. */
public final class HudEditorActivity extends Activity {
    private static final String PREFS_NAME = "anland_settings";
    private HudOverlayView hudOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setDecorFitsSystemWindows(false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        hudOverlay = new HudOverlayView(this, prefs, new HudOverlayView.HudHost() {
            @Override public void sendKey(int action, int evdevCode) {}
            @Override public void sendTextInput(byte[] utf8) {}
            @Override public void sendMouseMotion(float dx, float dy) {}
            @Override public void sendMouseButton(int button, boolean pressed) {}
            @Override public void sendMouseScroll(int axis, float value) {}
            @Override public void toggleSystemKeyboard() {}
            @Override public void toggleVirtualKeyboard() {}
            @Override public void openSettings() {
                startActivity(new Intent(HudEditorActivity.this, SettingsActivity.class));
            }
            @Override public void onLayoutSaved() {}
            @Override public boolean isNativeReady() { return false; }
            @Override public void onEditModeExited() { finish(); }
        });
        root.addView(hudOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        hideSystemBars();
        hudOverlay.post(() -> {
            if (hudOverlay != null && !isFinishing()) hudOverlay.setEditMode(true);
        });
    }

    private void hideSystemBars() {
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    public void onBackPressed() {
        if (hudOverlay != null && hudOverlay.isEditMode()) {
            hudOverlay.requestExit();
            return;
        }
        super.onBackPressed();
    }
}
