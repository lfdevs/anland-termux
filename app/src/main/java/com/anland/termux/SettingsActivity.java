package com.anland.termux;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.util.Log;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar; // ===== 新增导入
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;


public class SettingsActivity extends Activity {
    private static final String TAG = "AnlandSettings";
    private static final String PREFS_NAME = "anland_settings";
    private static final String KEY_BOUND_KEYCODE = "bound_keycode";
    private static final String KEY_SOCKET_PATH = "socket_path";
    private static final String KEY_USE_ROOT = "use_root";
    private static final String KEY_MIC_ENABLED = "mic_enabled";
    private static final String KEY_CAMERA_ENABLED = "camera_enabled";
    private static final String KEY_AUDIO_KEEPALIVE = "audio_keepalive";
    private static final String KEY_SPEAKER_LATENCY_MS = "speaker_latency_ms";
    private static final String KEY_MIC_LATENCY_MS = "mic_latency_ms";
    private static final String KEY_ACCESSIBILITY_ENABLED = "accessibility_key_intercept";
    private static final String KEY_EXTRA_KEYS_ENABLED = "extra_keys_bar";
    private static final String KEY_AUTO_SHOW_EXTRA_KEYS = "auto_show_extra_keys";
    private static final String KEY_EXTRA_KEYS_LAYOUT = "extra_keys_layout";
    private static final String KEY_KEYBOARD_FLOATING = "keyboard_floating";
    private static final String KEY_NOTIFICATION_ENABLED = "settings_notification";
    private static final String KEY_SCREEN_ORIENTATION = "screen_orientation";
    private static final String KEY_PIP_MODE = "pip_mode";
    private static final String DEFAULT_SOCKET_PATH = "/data/data/com.termux/files/usr/tmp/anland/display_daemon.sock";
    private static final int UNBOUND = -1;

    // Stored values match Termux:X11's forceOrientation preference.
    private static final String[] SCREEN_ORIENTATIONS = {
        "auto", "portrait", "landscape", "reverse portrait", "reverse landscape"
    };

    // ===== 新增：触摸板 Key =====
    private static final String KEY_TOUCHPAD_MODE = "touchpad_mode";
    private static final String KEY_MOUSE_ACCEL = "mouse_speed";
    private static final String KEY_POINTER_CAPTURE = "pointer_capture";
    private static final String KEY_TRANSFORM_CAPTURED_POINTER = "transform_captured_pointer";
    private static final String KEY_CAPTURED_POINTER_SPEED_FACTOR = "captured_pointer_speed_factor";
    private static final String KEY_SCROLL_SPEED = "scroll_speed";
    private static final String KEY_SCROLL_REVERSE = "scroll_reverse";
    private static final String KEY_SCROLL_THRESHOLD = "touchpad_scroll_threshold";
    private static final String KEY_MOVE_THRESHOLD = "touchpad_move_threshold";
    private static final String KEY_GESTURE_SCALE = "touchpad_gesture_scale";
    private static final String[] CAPTURED_POINTER_TRANSFORMS = {
        "no", "c", "cc", "ud", "at"
    };

    // Latency presets: target buffer in ms (0 = auto). The user-visible labels live
    // in the R.array.latency_labels string-array, parallel to this array.
    private static final int[] LATENCY_MS = {0, 1, 3, 5, 10, 20};

    // Which secondary page is on screen. Back returns HOME -> exits the activity.
    private enum Page { HOME, KEYBOARD, TOUCHPAD, CONNECTION, DISPLAY, GENERAL }
    private Page currentPage = Page.HOME;

    private Button bindButton;
    private TextView statusText;
    private CountDownTimer listenTimer;
    private boolean isListening = false;

    // Custom extra-keys layout editor (JSON), and the SAF file-picker request code.
    private EditText layoutInput;
    private static final int REQ_PICK_LAYOUT = 2001;

    // Android keycode → localized name string resource
    private static final SparseIntArray KEY_NAME_RES = new SparseIntArray();
    static {
        KEY_NAME_RES.put(KeyEvent.KEYCODE_VOLUME_UP, R.string.key_volume_up);
        KEY_NAME_RES.put(KeyEvent.KEYCODE_VOLUME_DOWN, R.string.key_volume_down);
        KEY_NAME_RES.put(KeyEvent.KEYCODE_VOLUME_MUTE, R.string.key_volume_mute);
        KEY_NAME_RES.put(KeyEvent.KEYCODE_POWER, R.string.key_power);
        KEY_NAME_RES.put(KeyEvent.KEYCODE_CAMERA, R.string.key_camera);
        KEY_NAME_RES.put(KeyEvent.KEYCODE_HEADSETHOOK, R.string.key_headset_hook);
        KEY_NAME_RES.put(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, R.string.key_media_play_pause);
        KEY_NAME_RES.put(KeyEvent.KEYCODE_MEDIA_NEXT, R.string.key_media_next);
        KEY_NAME_RES.put(KeyEvent.KEYCODE_MEDIA_PREVIOUS, R.string.key_media_previous);
        KEY_NAME_RES.put(KeyEvent.KEYCODE_BRIGHTNESS_UP, R.string.key_brightness_up);
        KEY_NAME_RES.put(KeyEvent.KEYCODE_BRIGHTNESS_DOWN, R.string.key_brightness_down);
        KEY_NAME_RES.put(KeyEvent.KEYCODE_HOME, R.string.key_home);
        KEY_NAME_RES.put(KeyEvent.KEYCODE_BACK, R.string.key_back);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showHome();
    }

    // ============================================================
    // Navigation: a home list of categories, each opening a page.
    // Every page is a fresh LinearLayout wrapped by setContent().
    // ============================================================

    // Wrap `content` in the themed ScrollView, apply edge-to-edge insets,
    // and install it. Reused by the home list and every secondary page.
    private void setContent(final LinearLayout content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getColor(R.color.settings_background));
        scroll.addView(content);
        setContentView(scroll);

        // Edge-to-edge is enforced on Android 15+ (targetSdk 37): the system no
        // longer auto-resizes the window for the IME, so a manifest "adjustResize"
        // is ignored and the soft keyboard overlaps the bottom EditTexts. Take over
        // inset handling and pad the scrollable content by the system-bar + IME
        // insets ourselves, so the ScrollView can scroll the focused field above
        // the keyboard. Base padding (dp(24)) is preserved on all edges.
        getWindow().setDecorFitsSystemWindows(false);
        final int base = dp(24);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets in = insets.getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(base + in.left, base + in.top,
                         base + in.right, base + in.bottom);
            return insets;
        });
    }

    private void showHome() {
        currentPage = Page.HOME;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(R.string.settings_title);
        title.setTextSize(24);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.START);
        title.setPadding(0, 0, 0, dp(24));
        root.addView(title);

        addCategoryRow(root, R.string.cat_keyboard_title,
            R.string.cat_keyboard_subtitle, this::showKeyboardPage);
        addCategoryRow(root, R.string.cat_touchpad_title,
            R.string.cat_touchpad_subtitle, this::showTouchpadPage);
        addCategoryRow(root, R.string.section_connection,
            R.string.cat_connection_subtitle, this::showConnectionPage);
        addCategoryRow(root, R.string.section_display,
            R.string.cat_display_subtitle, this::showDisplayPage);
        addCategoryRow(root, R.string.cat_general_title,
            R.string.cat_general_subtitle, this::showGeneralPage);

        // Build version, injected from git at build time (see app/build.gradle).
        TextView version = new TextView(this);
        version.setText(BuildConfig.VERSION_NAME
            + " (" + BuildConfig.VERSION_CODE + ")");
        version.setTextSize(12);
        version.setGravity(Gravity.START);
        version.setPadding(0, dp(24), 0, 0);
        version.setAlpha(0.5f);
        root.addView(version);

        setContent(root);
    }

    // A tappable "title / subtitle ›" row plus a hairline divider.
    private void addCategoryRow(LinearLayout parent, int titleRes, int subtitleRes,
                                final Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(16), 0, dp(16));
        row.setClickable(true);
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true)) {
            row.setBackgroundResource(tv.resourceId);
        }
        row.setOnClickListener(v -> onClick.run());

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(titleRes);
        t.setTextSize(18);
        t.setTextColor(getColor(R.color.settings_text_primary));
        texts.addView(t);

        TextView s = new TextView(this);
        s.setText(subtitleRes);
        s.setTextSize(13);
        s.setTextColor(getColor(R.color.settings_text_secondary));
        s.setPadding(0, dp(2), 0, 0);
        texts.addView(s);

        row.addView(texts);

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(22);
        chevron.setTextColor(getColor(R.color.settings_text_secondary));
        row.addView(chevron);

        parent.addView(row);

        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        divider.setBackgroundColor(getColor(R.color.settings_divider));
        parent.addView(divider);
    }

    // A fresh page root with a back link and a bold page title.
    private LinearLayout newPage(int titleRes) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView back = new TextView(this);
        back.setText(R.string.nav_back);
        back.setTextSize(16);
        back.setTextColor(getColor(R.color.settings_accent));
        back.setPadding(0, 0, 0, dp(12));
        back.setClickable(true);
        back.setOnClickListener(v -> showHome());
        root.addView(back);

        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextSize(24);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.START);
        title.setPadding(0, 0, 0, dp(24));
        root.addView(title);

        return root;
    }

    private void showKeyboardPage() {
        currentPage = Page.KEYBOARD;
        LinearLayout root = newPage(R.string.cat_keyboard_title);
        buildVirtualKeyboardSection(root);
        buildAccessibilitySection(root);
        buildExtraKeysSection(root);
        buildCustomLayoutSection(root);
        setContent(root);
        updateStatus();
    }

    private void showTouchpadPage() {
        currentPage = Page.TOUCHPAD;
        LinearLayout root = newPage(R.string.cat_touchpad_title);
        buildTouchpadSection(root);
        setContent(root);
    }

    private void showConnectionPage() {
        currentPage = Page.CONNECTION;
        LinearLayout root = newPage(R.string.section_connection);
        addConnectionSection(root);
        setContent(root);
    }

    private void showDisplayPage() {
        currentPage = Page.DISPLAY;
        LinearLayout root = newPage(R.string.section_display);
        addResolutionSection(root);
        addScreenOrientationSection(root);
        addDisplayCutoutSection(root);
        addPipSection(root);
        setContent(root);
    }

    private void showGeneralPage() {
        currentPage = Page.GENERAL;
        LinearLayout root = newPage(R.string.cat_general_title);
        buildNotificationSection(root);
        buildUserActionsSection(root);
        setContent(root);
    }

    @Override
    public void onBackPressed() {
        // While listening for a key binding, let onKeyDown capture the Back key
        // instead of navigating back.
        if (isListening) return;
        if (currentPage != Page.HOME) {
            showHome();
        } else {
            super.onBackPressed();
        }
    }

    // ============================================================
    // Keyboard & Keys page sections
    // ============================================================

    private void buildVirtualKeyboardSection(LinearLayout root) {
        TextView bindLabel = new TextView(this);
        bindLabel.setText(R.string.section_virtual_keyboard);
        bindLabel.setTextSize(16);
        bindLabel.setTypeface(null, Typeface.BOLD);
        bindLabel.setPadding(0, 0, 0, dp(8));
        root.addView(bindLabel);

        statusText = new TextView(this);
        statusText.setTextSize(14);
        statusText.setTextColor(getColor(R.color.settings_text_secondary));
        statusText.setPadding(0, 0, 0, dp(16));
        root.addView(statusText);

        bindButton = new Button(this);
        bindButton.setText(R.string.bind_key_button);
        bindButton.setOnClickListener(v -> startListening());
        root.addView(bindButton);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Switch raiseDesktopSwitch = new Switch(this);
        raiseDesktopSwitch.setText(R.string.raise_desktop_for_soft_keyboard);
        raiseDesktopSwitch.setTextSize(14);
        raiseDesktopSwitch.setPadding(0, dp(8), 0, 0);
        raiseDesktopSwitch.setChecked(!prefs.getBoolean(KEY_KEYBOARD_FLOATING, true));
        raiseDesktopSwitch.setOnCheckedChangeListener((v, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_KEYBOARD_FLOATING, !checked).apply());
        root.addView(raiseDesktopSwitch);

        TextView raiseDesktopHint = new TextView(this);
        raiseDesktopHint.setText(R.string.raise_desktop_for_soft_keyboard_hint);
        raiseDesktopHint.setTextSize(12);
        raiseDesktopHint.setTextColor(getColor(R.color.settings_text_secondary));
        raiseDesktopHint.setPadding(0, dp(4), 0, dp(8));
        root.addView(raiseDesktopHint);
    }

    private void buildAccessibilitySection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        Switch accessibilitySwitch = new Switch(this);
        accessibilitySwitch.setText(R.string.accessibility_switch);
        accessibilitySwitch.setTextSize(14);
        accessibilitySwitch.setPadding(0, dp(16), 0, 0);
        accessibilitySwitch.setChecked(prefs.getBoolean(KEY_ACCESSIBILITY_ENABLED, false));
        accessibilitySwitch.setOnCheckedChangeListener((v, checked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_ACCESSIBILITY_ENABLED, checked).apply();
            if (checked) {
                KeyInterceptor.launch(SettingsActivity.this);
            } else {
                KeyInterceptor.shutdown(false);
            }
        });
        root.addView(accessibilitySwitch);

        TextView accessibilityHint = new TextView(this);
        accessibilityHint.setText(R.string.accessibility_hint);
        accessibilityHint.setTextSize(12);
        accessibilityHint.setTextColor(getColor(R.color.settings_text_secondary));
        accessibilityHint.setPadding(0, dp(4), 0, dp(8));
        root.addView(accessibilityHint);
    }

    private void buildExtraKeysSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        TextView header = new TextView(this);
        header.setText(R.string.section_extra_keys);
        header.setTextSize(16);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(0, dp(24), 0, dp(8));
        root.addView(header);

        // === Extra-keys bar switch ===
        Switch extraKeysSwitch = new Switch(this);
        extraKeysSwitch.setText(R.string.extra_keys_switch);
        extraKeysSwitch.setTextSize(14);
        extraKeysSwitch.setPadding(0, dp(8), 0, 0);
        extraKeysSwitch.setChecked(prefs.getBoolean(KEY_EXTRA_KEYS_ENABLED, false));
        extraKeysSwitch.setOnCheckedChangeListener((v, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_EXTRA_KEYS_ENABLED, checked).apply());
        root.addView(extraKeysSwitch);

        TextView extraKeysHint = new TextView(this);
        extraKeysHint.setText(R.string.extra_keys_hint);
        extraKeysHint.setTextSize(12);
        extraKeysHint.setTextColor(getColor(R.color.settings_text_secondary));
        extraKeysHint.setPadding(0, dp(4), 0, dp(8));
        root.addView(extraKeysHint);

        // === Auto-show extra keys with keyboard ===
        Switch autoShowSwitch = new Switch(this);
        autoShowSwitch.setText(R.string.auto_show_switch);
        autoShowSwitch.setTextSize(14);
        autoShowSwitch.setPadding(0, dp(16), 0, 0);
        autoShowSwitch.setChecked(prefs.getBoolean(KEY_AUTO_SHOW_EXTRA_KEYS, true));
        autoShowSwitch.setOnCheckedChangeListener((v, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO_SHOW_EXTRA_KEYS, checked).apply());
        root.addView(autoShowSwitch);

        TextView autoShowHint = new TextView(this);
        autoShowHint.setText(R.string.auto_show_hint);
        autoShowHint.setTextSize(12);
        autoShowHint.setTextColor(getColor(R.color.settings_text_secondary));
        autoShowHint.setPadding(0, dp(4), 0, dp(8));
        root.addView(autoShowHint);

    }

    private void buildCustomLayoutSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        TextView layoutHeader = new TextView(this);
        layoutHeader.setText(R.string.section_custom_layout);
        layoutHeader.setTextSize(16);
        layoutHeader.setTypeface(null, Typeface.BOLD);
        layoutHeader.setPadding(0, dp(24), 0, dp(8));
        root.addView(layoutHeader);

        layoutInput = new EditText(this);
        layoutInput.setTypeface(Typeface.MONOSPACE);
        layoutInput.setTextSize(12);
        layoutInput.setGravity(Gravity.TOP | Gravity.START);
        layoutInput.setInputType(InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        layoutInput.setHorizontallyScrolling(false);
        layoutInput.setMinLines(6);
        String savedLayout = prefs.getString(KEY_EXTRA_KEYS_LAYOUT, "");
        if (savedLayout.isEmpty()) savedLayout = ExtraKeysBar.defaultLayoutJson();
        layoutInput.setText(savedLayout);
        root.addView(layoutInput);

        final TextView layoutStatus = new TextView(this);
        layoutStatus.setTextSize(12);
        layoutStatus.setPadding(0, dp(4), 0, dp(4));
        root.addView(layoutStatus);
        updateLayoutStatus(layoutStatus, layoutInput.getText().toString());

        layoutInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(KEY_EXTRA_KEYS_LAYOUT, s.toString()).apply();
                updateLayoutStatus(layoutStatus, s.toString());
            }
        });

        LinearLayout layoutButtons = new LinearLayout(this);
        layoutButtons.setOrientation(LinearLayout.HORIZONTAL);

        Button loadDefaultBtn = new Button(this);
        loadDefaultBtn.setText(R.string.btn_load_default);
        loadDefaultBtn.setOnClickListener(v ->
            layoutInput.setText(ExtraKeysBar.defaultLayoutJson()));
        layoutButtons.addView(loadDefaultBtn);

        Button loadFileBtn = new Button(this);
        loadFileBtn.setText(R.string.btn_load_file);
        loadFileBtn.setOnClickListener(v -> pickLayoutFile());
        layoutButtons.addView(loadFileBtn);

        root.addView(layoutButtons);

        TextView layoutHint = new TextView(this);
        layoutHint.setText(R.string.layout_hint);
        layoutHint.setTextSize(12);
        layoutHint.setTextColor(getColor(R.color.settings_text_secondary));
        layoutHint.setPadding(0, dp(4), 0, dp(8));
        root.addView(layoutHint);
    }

    // ============================================================
    // General page sections
    // ============================================================
    private void buildNotificationSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        TextView header = new TextView(this);
        header.setText(R.string.section_notification);
        header.setTextSize(16);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(0, 0, 0, dp(8));
        root.addView(header);

        Switch notificationSwitch = new Switch(this);
        notificationSwitch.setText(R.string.notification_switch);
        notificationSwitch.setTextSize(14);
        notificationSwitch.setPadding(0, dp(8), 0, 0);
        notificationSwitch.setChecked(prefs.getBoolean(KEY_NOTIFICATION_ENABLED, true));
        notificationSwitch.setOnCheckedChangeListener((v, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_NOTIFICATION_ENABLED, checked).apply());
        root.addView(notificationSwitch);

        TextView notificationHint = new TextView(this);
        notificationHint.setText(R.string.notification_hint);
        notificationHint.setTextSize(12);
        notificationHint.setTextColor(getColor(R.color.settings_text_secondary));
        notificationHint.setPadding(0, dp(4), 0, dp(8));
        root.addView(notificationHint);
    }

    private void buildUserActionsSection(LinearLayout root) {
        TextView header = new TextView(this);
        header.setText(R.string.section_user_actions);
        header.setTextSize(16);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(0, dp(24), 0, dp(8));
        root.addView(header);

        addUserActionSpinner(root, R.string.user_action_volume_up, UserActions.VOLUME_UP);
        addUserActionSpinner(root, R.string.user_action_volume_down, UserActions.VOLUME_DOWN);
        addUserActionSpinner(root, R.string.user_action_back_button, UserActions.BACK_BUTTON);
        addUserActionSpinner(root, R.string.user_action_notification_tap,
            UserActions.NOTIFICATION_TAP);
        addUserActionSpinner(root, R.string.user_action_notification_first_button,
            UserActions.NOTIFICATION_FIRST_BUTTON);
        addUserActionSpinner(root, R.string.user_action_notification_second_button,
            UserActions.NOTIFICATION_SECOND_BUTTON);
        addUserActionSpinner(root, R.string.user_action_media_keys, UserActions.MEDIA_KEYS);
    }

    private void addUserActionSpinner(LinearLayout root, int labelRes, String action) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        TextView label = new TextView(this);
        label.setText(labelRes);
        label.setTextSize(14);
        label.setPadding(0, dp(12), 0, dp(4));
        root.addView(label);

        String[] responses = UserActions.responsesFor(action);
        String[] labels = new String[responses.length];
        for (int i = 0; i < responses.length; i++)
            labels[i] = getString(responseLabelResource(responses[i]));

        Spinner spinner = newSettingsSpinner();
        spinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, labels));

        String current = UserActions.getResponse(prefs, action);
        int selected = 0;
        for (int i = 0; i < responses.length; i++) {
            if (responses[i].equals(current)) {
                selected = i;
                break;
            }
        }
        spinner.setSelection(selected);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putString(action, responses[position]).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(spinner);
    }

    private int responseLabelResource(String response) {
        switch (response) {
            case UserActions.TOGGLE_SOFT_KEYBOARD:
                return R.string.user_response_toggle_soft_keyboard;
            case UserActions.TOGGLE_ADDITIONAL_KEY_BAR:
                return R.string.user_response_toggle_additional_key_bar;
            case UserActions.OPEN_PREFERENCES:
                return R.string.user_response_open_preferences;
            case UserActions.RELEASE_POINTER_AND_KEYBOARD_CAPTURE:
                return R.string.user_response_release_captures;
            case UserActions.RESTART_ACTIVITY:
                return R.string.user_response_restart_activity;
            case UserActions.EXIT:
                return R.string.user_response_exit;
            case UserActions.TOGGLE_TOUCHPAD_MODE:
                return R.string.user_response_toggle_touchpad_mode;
            case UserActions.TOGGLE_SCREEN_ORIENTATION:
                return R.string.user_response_toggle_screen_orientation;
            case UserActions.SEND_VOLUME_UP:
                return R.string.user_response_send_volume_up;
            case UserActions.SEND_VOLUME_DOWN:
                return R.string.user_response_send_volume_down;
            case UserActions.SEND_MEDIA_ACTION:
                return R.string.user_response_send_media_action;
            default:
                return R.string.user_response_no_action;
        }
    }

    // ============================================================
    // ===== 触摸板设置区域 =====
    // ============================================================
    private void buildTouchpadSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // 触摸板模式开关
        Switch touchpadModeSwitch = new Switch(this);
        touchpadModeSwitch.setText(R.string.touchpad_mode_switch);
        touchpadModeSwitch.setTextSize(14);
        touchpadModeSwitch.setPadding(0, dp(8), 0, 0);
        touchpadModeSwitch.setChecked(prefs.getBoolean(KEY_TOUCHPAD_MODE, false));
        touchpadModeSwitch.setOnCheckedChangeListener((v, checked) ->
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean(KEY_TOUCHPAD_MODE, checked).apply());
        root.addView(touchpadModeSwitch);

        TextView touchpadHint = new TextView(this);
        touchpadHint.setText(R.string.touchpad_hint);
        touchpadHint.setTextSize(12);
        touchpadHint.setTextColor(getColor(R.color.settings_text_secondary));
        touchpadHint.setPadding(0, dp(4), 0, dp(12));
        root.addView(touchpadHint);

        // 鼠标加速度（灵敏度）—— 范围 0.5 ~ 10.0
        LinearLayout accelLayout = new LinearLayout(this);
        accelLayout.setOrientation(LinearLayout.VERTICAL);
        accelLayout.setPadding(0, dp(8), 0, dp(16));

        TextView accelLabel = new TextView(this);
        accelLabel.setText(R.string.mouse_sensitivity_label);
        accelLabel.setTextSize(14);
        accelLayout.addView(accelLabel);

        final TextView accelValue = new TextView(this);
        accelValue.setTextSize(14);
        accelValue.setTextColor(getColor(R.color.settings_accent));
        accelLayout.addView(accelValue);

        SeekBar accelSeek = new SeekBar(this);
        accelSeek.setMax(190); // 0.5 ~ 10.0 step 0.05
        float curAccel = prefs.getFloat(KEY_MOUSE_ACCEL, 1.0f);
        curAccel = Math.max(0.5f, Math.min(10.0f, curAccel));
        accelSeek.setProgress((int)((curAccel - 0.5f) / 0.05f));
        accelValue.setText(getString(R.string.mouse_accel_value, curAccel));
        accelSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float val = 0.5f + progress * 0.05f;
                accelValue.setText(getString(R.string.mouse_accel_value, val));
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putFloat(KEY_MOUSE_ACCEL, val).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        accelLayout.addView(accelSeek);
        root.addView(accelLayout);

        buildPointerCaptureSection(root, prefs);
        buildHardwareTouchpadTuningSection(root, prefs);
    }

    private void buildPointerCaptureSection(LinearLayout root, SharedPreferences prefs) {
        Switch captureSwitch = new Switch(this);
        captureSwitch.setText(R.string.pointer_capture_switch);
        captureSwitch.setTextSize(14);
        captureSwitch.setPadding(0, dp(8), 0, 0);
        captureSwitch.setChecked(prefs.getBoolean(KEY_POINTER_CAPTURE, false));
        root.addView(captureSwitch);

        TextView captureHint = new TextView(this);
        captureHint.setText(R.string.pointer_capture_hint);
        captureHint.setTextSize(12);
        captureHint.setTextColor(getColor(R.color.settings_text_secondary));
        captureHint.setPadding(0, dp(4), 0, dp(12));
        root.addView(captureHint);

        TextView transformLabel = new TextView(this);
        transformLabel.setText(R.string.transform_captured_pointer_label);
        transformLabel.setTextSize(14);
        root.addView(transformLabel);

        Spinner transformSpinner = newSettingsSpinner();
        transformSpinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,
            getResources().getStringArray(R.array.captured_pointer_transform_labels)));
        String currentTransform = prefs.getString(KEY_TRANSFORM_CAPTURED_POINTER,
            CAPTURED_POINTER_TRANSFORMS[0]);
        int selectedTransform = 0;
        for (int i = 0; i < CAPTURED_POINTER_TRANSFORMS.length; i++) {
            if (CAPTURED_POINTER_TRANSFORMS[i].equals(currentTransform)) {
                selectedTransform = i;
                break;
            }
        }
        transformSpinner.setSelection(selectedTransform);
        transformSpinner.setEnabled(captureSwitch.isChecked());
        transformSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putString(KEY_TRANSFORM_CAPTURED_POINTER,
                    CAPTURED_POINTER_TRANSFORMS[position]).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(transformSpinner);

        LinearLayout speedLayout = new LinearLayout(this);
        speedLayout.setOrientation(LinearLayout.VERTICAL);
        speedLayout.setPadding(0, dp(12), 0, dp(16));

        TextView speedLabel = new TextView(this);
        speedLabel.setText(R.string.captured_pointer_speed_label);
        speedLabel.setTextSize(14);
        speedLayout.addView(speedLabel);

        TextView speedValue = new TextView(this);
        speedValue.setTextSize(14);
        speedValue.setTextColor(getColor(R.color.settings_accent));
        speedLayout.addView(speedValue);

        SeekBar speedSeek = new SeekBar(this);
        speedSeek.setMax(299);
        int speedPercent = Math.max(1, Math.min(300,
            prefs.getInt(KEY_CAPTURED_POINTER_SPEED_FACTOR, 100)));
        speedSeek.setProgress(speedPercent - 1);
        speedSeek.setEnabled(captureSwitch.isChecked());
        speedValue.setText(getString(R.string.captured_pointer_speed_value, speedPercent));
        speedSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + 1;
                speedValue.setText(getString(R.string.captured_pointer_speed_value, value));
                if (fromUser)
                    prefs.edit().putInt(KEY_CAPTURED_POINTER_SPEED_FACTOR, value).apply();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        speedLayout.addView(speedSeek);
        root.addView(speedLayout);

        captureSwitch.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean(KEY_POINTER_CAPTURE, checked).apply();
            transformSpinner.setEnabled(checked);
            speedSeek.setEnabled(checked);
        });
    }

    private void buildHardwareTouchpadTuningSection(
            LinearLayout root, SharedPreferences prefs) {
        Switch reverseScrollSwitch = new Switch(this);
        reverseScrollSwitch.setText(R.string.scroll_reverse_switch);
        reverseScrollSwitch.setTextSize(14);
        reverseScrollSwitch.setPadding(0, dp(8), 0, 0);
        reverseScrollSwitch.setChecked(prefs.getBoolean(KEY_SCROLL_REVERSE, false));
        reverseScrollSwitch.setOnCheckedChangeListener((v, checked) ->
            prefs.edit().putBoolean(KEY_SCROLL_REVERSE, checked).apply());
        root.addView(reverseScrollSwitch);

        TextView reverseScrollHint = new TextView(this);
        reverseScrollHint.setText(R.string.scroll_reverse_hint);
        reverseScrollHint.setTextSize(12);
        reverseScrollHint.setTextColor(getColor(R.color.settings_text_secondary));
        reverseScrollHint.setPadding(0, dp(4), 0, dp(12));
        root.addView(reverseScrollHint);

        addFloatSlider(root, R.string.scroll_speed_label, R.string.scroll_speed_value,
            null, KEY_SCROLL_SPEED, 0.05f, 3.0f, 0.05f,
            Touchpad.DEFAULT_SCROLL_SPEED);
        addFloatSlider(root, R.string.scroll_threshold_label,
            R.string.threshold_factor_value, R.string.scroll_threshold_hint,
            KEY_SCROLL_THRESHOLD, 0.05f, 3.0f, 0.05f,
            Touchpad.DEFAULT_SCROLL_THRESHOLD_FACTOR);
        addFloatSlider(root, R.string.move_threshold_label,
            R.string.threshold_factor_value, R.string.move_threshold_hint,
            KEY_MOVE_THRESHOLD, 0.1f, 8.0f, 0.05f,
            Touchpad.DEFAULT_MOVE_THRESHOLD_FACTOR);
        addFloatSlider(root, R.string.gesture_scale_label,
            R.string.gesture_scale_value, R.string.gesture_scale_hint,
            KEY_GESTURE_SCALE, 100f, 3000f, 20f,
            Touchpad.DEFAULT_GESTURE_SCALE);
    }

    private void addFloatSlider(LinearLayout root, int labelRes, int valueFormatRes,
            Integer hintRes, final String key, final float min, float max,
            final float step, float defaultValue) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, dp(8), 0, hintRes == null ? dp(16) : 0);

        TextView label = new TextView(this);
        label.setText(labelRes);
        label.setTextSize(14);
        layout.addView(label);

        TextView value = new TextView(this);
        value.setTextSize(14);
        value.setTextColor(getColor(R.color.settings_accent));
        layout.addView(value);

        SeekBar seek = new SeekBar(this);
        seek.setMax(Math.round((max - min) / step));
        float current = Math.max(min,
            Math.min(max, prefs.getFloat(key, defaultValue)));
        seek.setProgress(Math.round((current - min) / step));
        value.setText(getString(valueFormatRes, current));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(
                    SeekBar seekBar, int progress, boolean fromUser) {
                float selected = min + progress * step;
                value.setText(getString(valueFormatRes, selected));
                if (fromUser)
                    prefs.edit().putFloat(key, selected).apply();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        layout.addView(seek);
        root.addView(layout);

        if (hintRes != null) {
            TextView hint = new TextView(this);
            hint.setText(hintRes);
            hint.setTextSize(12);
            hint.setTextColor(getColor(R.color.settings_text_secondary));
            hint.setPadding(0, dp(2), 0, dp(12));
            root.addView(hint);
        }
    }

    // Connection settings: a custom daemon socket path and a "connect with root"
    // toggle. In root mode the app launches the bundled helper via `su -c`, which
    // connects to the socket and passes the fd back (see MainActivity).
    private void addConnectionSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Socket path
        TextView sockLabel = new TextView(this);
        sockLabel.setText(R.string.socket_path_label);
        sockLabel.setTextSize(14);
        sockLabel.setTextColor(getColor(R.color.settings_text_secondary));
        sockLabel.setPadding(0, 0, 0, dp(4));
        root.addView(sockLabel);

        EditText socketInput = new EditText(this);
        socketInput.setSingleLine(true);
        socketInput.setText(prefs.getString(KEY_SOCKET_PATH, DEFAULT_SOCKET_PATH));
        socketInput.setHint(DEFAULT_SOCKET_PATH);
        socketInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(KEY_SOCKET_PATH, s.toString().trim()).apply();
            }
        });
        root.addView(socketInput);

        // Connect with root
        Switch rootSwitch = new Switch(this);
        rootSwitch.setText(R.string.root_switch);
        rootSwitch.setTextSize(14);
        rootSwitch.setPadding(0, dp(16), 0, 0);
        rootSwitch.setChecked(prefs.getBoolean(KEY_USE_ROOT, false));
        rootSwitch.setOnCheckedChangeListener((v, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_USE_ROOT, checked).apply());
        root.addView(rootSwitch);

        TextView rootHint = new TextView(this);
        rootHint.setText(R.string.root_hint);
        rootHint.setTextSize(12);
        rootHint.setTextColor(getColor(R.color.settings_text_secondary));
        rootHint.setPadding(0, dp(4), 0, 0);
        root.addView(rootHint);

        // Forward microphone: capture the device mic and expose it to the Linux
        // desktop as a recording source. Requires the RECORD_AUDIO permission, which
        // MainActivity requests when this is on.
        Switch micSwitch = new Switch(this);
        micSwitch.setText(R.string.mic_switch);
        micSwitch.setTextSize(14);
        micSwitch.setPadding(0, dp(16), 0, 0);
        micSwitch.setChecked(prefs.getBoolean(KEY_MIC_ENABLED, false));
        micSwitch.setOnCheckedChangeListener((v, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_MIC_ENABLED, checked).apply());
        root.addView(micSwitch);

        TextView micHint = new TextView(this);
        micHint.setText(R.string.mic_hint);
        micHint.setTextSize(12);
        micHint.setTextColor(getColor(R.color.settings_text_secondary));
        micHint.setPadding(0, dp(4), 0, 0);
        root.addView(micHint);

        // Forward camera: expose the device camera(s) to the Linux desktop. When on,
        // the app pre-creates the camera service resources at startup (CameraX is only
        // opened once the desktop actually requests a recording). Requires the CAMERA
        // permission, which MainActivity requests when this is enabled.
        Switch cameraSwitch = new Switch(this);
        cameraSwitch.setText(R.string.camera_switch);
        cameraSwitch.setTextSize(14);
        cameraSwitch.setPadding(0, dp(16), 0, 0);
        cameraSwitch.setChecked(prefs.getBoolean(KEY_CAMERA_ENABLED, false));
        cameraSwitch.setOnCheckedChangeListener((v, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_CAMERA_ENABLED, checked).apply());
        root.addView(cameraSwitch);

        TextView cameraHint = new TextView(this);
        cameraHint.setText(R.string.camera_hint);
        cameraHint.setTextSize(12);
        cameraHint.setTextColor(getColor(R.color.settings_text_secondary));
        cameraHint.setPadding(0, dp(4), 0, 0);
        root.addView(cameraHint);

        // Keep the output stream hot for short Linux UI sounds, or let it
        // idle-stop after silence to save standby power. Disabled by default.
        Switch keepaliveSwitch = new Switch(this);
        keepaliveSwitch.setText(R.string.audio_keepalive_switch);
        keepaliveSwitch.setTextSize(14);
        keepaliveSwitch.setPadding(0, dp(16), 0, 0);
        keepaliveSwitch.setChecked(prefs.getBoolean(KEY_AUDIO_KEEPALIVE, false));
        keepaliveSwitch.setOnCheckedChangeListener((v, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_AUDIO_KEEPALIVE, checked).apply());
        root.addView(keepaliveSwitch);

        TextView keepaliveHint = new TextView(this);
        keepaliveHint.setText(R.string.audio_keepalive_hint);
        keepaliveHint.setTextSize(12);
        keepaliveHint.setTextColor(getColor(R.color.settings_text_secondary));
        keepaliveHint.setPadding(0, dp(4), 0, 0);
        root.addView(keepaliveHint);

        // Audio latency presets, separately for the speaker (playback) and microphone
        // (capture) paths. The chosen buffer is forwarded to the producer's PipeWire
        // nodes; smaller = lower latency but more risk of audio glitches.
        TextView latTitle = new TextView(this);
        latTitle.setText(R.string.audio_latency_title);
        latTitle.setTextSize(15);
        latTitle.setTypeface(Typeface.DEFAULT_BOLD);
        latTitle.setPadding(0, dp(20), 0, 0);
        root.addView(latTitle);

        root.addView(makeLatencySpinner(getString(R.string.latency_speaker_label),
                                        KEY_SPEAKER_LATENCY_MS, prefs));
        root.addView(makeLatencySpinner(getString(R.string.latency_mic_label),
                                        KEY_MIC_LATENCY_MS, prefs));

        TextView latHint = new TextView(this);
        latHint.setText(R.string.latency_hint);
        latHint.setTextSize(12);
        latHint.setTextColor(getColor(R.color.settings_text_secondary));
        latHint.setPadding(0, dp(4), 0, 0);
        root.addView(latHint);
    }

    private void addResolutionSection(LinearLayout root) {
    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

    TextView title = new TextView(this);
    title.setText(R.string.section_resolution);
    title.setTextSize(16);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    title.setPadding(0, 0, 0, dp(8));
    root.addView(title);

    // Width / height fields. Created first (but added below the preset picker) so
    // the picker can populate them; their TextWatchers are the single source of
    // truth that persists custom_width/custom_height.
    final EditText widthInput = new EditText(this);
    widthInput.setSingleLine(true);
    widthInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    widthInput.setHint(R.string.width_hint);
    widthInput.setText(String.valueOf(prefs.getInt("custom_width", 0)));
    widthInput.addTextChangedListener(new TextWatcher() {
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        public void onTextChanged(CharSequence s, int a, int b, int c) {}
        public void afterTextChanged(Editable s) {
            try {
                int w = Integer.parseInt(s.toString().trim());
                prefs.edit().putInt("custom_width", w).apply();
            } catch (NumberFormatException e) {}
        }
    });

    final EditText heightInput = new EditText(this);
    heightInput.setSingleLine(true);
    heightInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    heightInput.setHint(R.string.height_hint);
    heightInput.setText(String.valueOf(prefs.getInt("custom_height", 0)));
    heightInput.addTextChangedListener(new TextWatcher() {
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        public void onTextChanged(CharSequence s, int a, int b, int c) {}
        public void afterTextChanged(Editable s) {
            try {
                int h = Integer.parseInt(s.toString().trim());
                prefs.edit().putInt("custom_height", h).apply();
            } catch (NumberFormatException e) {}
        }
    });

    // Preset picker: fills width/height (which persist via their watchers). Index
    // 0 is a no-op placeholder so the Spinner's initial auto-selection and manual
    // edits leave the fields untouched.
    Spinner presetSpinner = newSettingsSpinner();
    presetSpinner.setAdapter(new ArrayAdapter<>(this,
        android.R.layout.simple_spinner_dropdown_item,
        getResources().getStringArray(R.array.res_preset_labels)));
    presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
            int[] wh = resolvePreset(pos);
            if (wh == null) return;
            widthInput.setText(String.valueOf(wh[0]));
            heightInput.setText(String.valueOf(wh[1]));
        }
        @Override
        public void onNothingSelected(AdapterView<?> parent) {}
    });
    root.addView(presetSpinner);

    root.addView(widthInput);
    root.addView(heightInput);

    TextView hint = new TextView(this);
    hint.setText(R.string.resolution_hint);
    hint.setTextSize(12);
    hint.setTextColor(getColor(R.color.settings_text_secondary));
    hint.setPadding(0, dp(4), 0, 0);
    root.addView(hint);
    }

    private void addScreenOrientationSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        TextView title = new TextView(this);
        title.setText(R.string.section_screen_orientation);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(24), 0, dp(8));
        root.addView(title);

        Spinner orientationSpinner = newSettingsSpinner();
        orientationSpinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,
            getResources().getStringArray(R.array.screen_orientation_labels)));

        String current = prefs.getString(KEY_SCREEN_ORIENTATION, SCREEN_ORIENTATIONS[0]);
        int selected = 0;
        for (int i = 0; i < SCREEN_ORIENTATIONS.length; i++) {
            if (SCREEN_ORIENTATIONS[i].equals(current)) {
                selected = i;
                break;
            }
        }
        orientationSpinner.setSelection(selected);
        orientationSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit()
                    .putString(KEY_SCREEN_ORIENTATION, SCREEN_ORIENTATIONS[position])
                    .apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(orientationSpinner);

        TextView hint = new TextView(this);
        hint.setText(R.string.screen_orientation_hint);
        hint.setTextSize(12);
        hint.setTextColor(getColor(R.color.settings_text_secondary));
        hint.setPadding(0, dp(4), 0, 0);
        root.addView(hint);
    }

    private void addDisplayCutoutSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        TextView label = new TextView(this);
        label.setText(R.string.display_cutout_mode_label);
        label.setTextSize(16);
        label.setPadding(0, dp(24), 0, dp(8));
        root.addView(label);

        Spinner modeSpinner = newSettingsSpinner();
        modeSpinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,
            getResources().getStringArray(R.array.display_cutout_mode_labels)));

        String current = DisplayCutoutMode.get(prefs);
        int selected = 0;
        for (int i = 0; i < DisplayCutoutMode.VALUES.length; i++) {
            if (DisplayCutoutMode.VALUES[i].equals(current)) {
                selected = i;
                break;
            }
        }
        modeSpinner.setSelection(selected);
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putString(DisplayCutoutMode.KEY,
                    DisplayCutoutMode.VALUES[position]).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(modeSpinner);
    }

    private void addPipSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        Switch pipSwitch = new Switch(this);
        pipSwitch.setText(R.string.pip_mode_switch);
        pipSwitch.setTextSize(14);
        pipSwitch.setPadding(0, dp(24), 0, 0);
        pipSwitch.setChecked(prefs.getBoolean(KEY_PIP_MODE, false));
        pipSwitch.setOnCheckedChangeListener((v, checked) ->
            prefs.edit().putBoolean(KEY_PIP_MODE, checked).apply());
        root.addView(pipSwitch);

        TextView hint = new TextView(this);
        hint.setText(R.string.pip_mode_hint);
        hint.setTextSize(12);
        hint.setTextColor(getColor(R.color.settings_text_secondary));
        hint.setPadding(0, dp(4), 0, 0);
        root.addView(hint);
    }

    // Maps a res_preset_labels index to {width, height}, or null for the index-0
    // placeholder. "Screen ×" presets are derived from the live panel size.
    private int[] resolvePreset(int pos) {
        switch (pos) {
            case 1: return new int[]{0, 0};
            case 2: return new int[]{3840, 2160};
            case 3: return new int[]{2560, 1440};
            case 4: return new int[]{1920, 1080};
            case 5: return new int[]{1280, 720};
            case 6: return new int[]{854, 480};
            case 7: return scaleScreen(1.0f);
            case 8: return scaleScreen(0.8f);
            case 9: return scaleScreen(0.75f);
            case 10: return scaleScreen(0.5f);
            case 11: return scaleScreen(0.25f);
            default: return null;
        }
    }

    // Scales the device panel by `f`, normalised to landscape (long side = width)
    // and rounded down to even dimensions, which compositors/encoders expect.
    private int[] scaleScreen(float f) {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Rect b = wm.getMaximumWindowMetrics().getBounds();
        int longSide = Math.max(b.width(), b.height());
        int shortSide = Math.min(b.width(), b.height());
        int w = Math.round(longSide * f) & ~1;
        int h = Math.round(shortSide * f) & ~1;
        return new int[]{w, h};
    }

    /* A labelled latency picker that persists the selected preset (ms) under `key`. */
    private View makeLatencySpinner(String label, final String key, SharedPreferences prefs) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, 0);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(14);
        box.addView(tv);

        Spinner sp = newSettingsSpinner();
        sp.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,
            getResources().getStringArray(R.array.latency_labels)));

        int cur = prefs.getInt(key, 0);
        int idx = 0;
        for (int i = 0; i < LATENCY_MS.length; i++) {
            if (LATENCY_MS[i] == cur) { idx = i; break; }
        }
        sp.setSelection(idx);

        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putInt(key, LATENCY_MS[pos]).apply();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        box.addView(sp);
        return box;
    }

    private void startListening() {
        if (isListening) return;
        isListening = true;
        bindButton.setText(getString(R.string.listening_countdown, 5));

        listenTimer = new CountDownTimer(5000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                bindButton.setText(getString(R.string.listening_countdown,
                    (int) (millisUntilFinished / 1000)));
            }

            @Override
            public void onFinish() {
                finishListening(UNBOUND);
            }
        }.start();
    }

    private void finishListening(int keycode) {
        isListening = false;
        listenTimer.cancel();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putInt(KEY_BOUND_KEYCODE, keycode).apply();

        bindButton.setText(R.string.bind_key_button);
        updateStatus();
    }

    private void updateStatus() {
        if (statusText == null) return;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int bound = prefs.getInt(KEY_BOUND_KEYCODE, UNBOUND);
        if (bound == UNBOUND) {
            statusText.setText(R.string.status_current_none);
        } else {
            int nameRes = KEY_NAME_RES.get(bound);
            String name = nameRes != 0
                ? getString(nameRes)
                : getString(R.string.keycode_unknown, bound);
            statusText.setText(getString(R.string.status_current, name));
        }
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
        // setText flows through the editor's TextWatcher, which persists + validates.
        if (layoutInput != null) layoutInput.setText(text);
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

    // Reflect the validity of the custom layout JSON inline under the editor.
    private void updateLayoutStatus(TextView status, String json) {
        if (json == null || json.trim().isEmpty()) {
            status.setText(R.string.layout_status_default);
            status.setTextColor(getColor(R.color.settings_text_secondary));
            return;
        }
        String err = ExtraKeysBar.validateLayout(json);
        if (err == null) {
            status.setText(R.string.layout_status_valid);
            status.setTextColor(getColor(R.color.settings_success));
        } else {
            status.setText(getString(R.string.layout_status_invalid, err));
            status.setTextColor(getColor(R.color.settings_error));
        }
    }

    private Spinner newSettingsSpinner() {
        Spinner spinner = new Spinner(this, Spinner.MODE_DROPDOWN);
        spinner.setPopupBackgroundResource(R.color.settings_dropdown_background);
        return spinner;
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
