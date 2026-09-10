package com.anland.termux;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.hardware.display.DisplayManager;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.RoundedCorner;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.util.DisplayMetrics;   // ADDED

import java.nio.charset.StandardCharsets;


public class MainActivity extends Activity
        implements SurfaceHolder.Callback, SystemIME.Host {
    private static final String TAG = "Anland";

    private SurfaceView surfaceView;
    private boolean surfaceReady = false;
    // System-clipboard bridge; also the target for the native clipboard callbacks.
    private Clipboard clipboard;
    private static final String PREFS_NAME = "anland_settings";
    private int customScreenWidth = 0;
    private int customScreenHeight = 0;
    private int viewWidth = 0;
    private int viewHeight = 0;
    private static final String KEY_BOUND_KEYCODE = "bound_keycode";
    private static final String KEY_SOCKET_PATH = "socket_path";
    private static final String KEY_USE_ROOT = "use_root";
    private static final String KEY_MIC_ENABLED = "mic_enabled";
    private static final String KEY_CAMERA_ENABLED = "camera_enabled";
    // Latency presets in ms; 0 = engine default. Shared with SettingsActivity.
    static final String KEY_SPEAKER_LATENCY_MS = "speaker_latency_ms";
    static final String KEY_MIC_LATENCY_MS = "mic_latency_ms";
    // Audio keep-alive toggle. Shared with SettingsActivity.
    static final String KEY_AUDIO_KEEPALIVE = "audio_keepalive";
    private static final int REQ_RECORD_AUDIO = 1001;
    private static final int REQ_CAMERA = 1002;
    // Camera service fds/threads are created once and persist across reconnects;
    // this guards that one-time init (see applyCameraState).
    private boolean cameraInited = false;
    private ICompatibleBridge compatibleBridge;
    private boolean compatibleFdReady = false;
    private boolean compatibleReceiverRegistered = false;
    // Media audio focus keeps volume controls and Android audio policy aligned with
    // the AAudio playback stream while this window is in the foreground.
    private AudioManager mAudioManager;
    private AudioFocusRequest mAudioFocusRequest;
    private static final String DEFAULT_SOCKET_PATH = "/data/data/com.termux/files/usr/tmp/anland/display_daemon.sock";
    private static final String KEY_ACCESSIBILITY_ENABLED = "accessibility_key_intercept";
    private static final String KEY_EXTRA_KEYS_ENABLED = "extra_keys_bar";
    private static final String KEY_AUTO_SHOW_EXTRA_KEYS = "auto_show_extra_keys";
    private static final String KEY_EXTRA_KEYS_LAYOUT = "extra_keys_layout";
    // Linux input-event-codes.h: KEY_BACK (the browser-back key).
    private static final int EVDEV_BROWSER_BACK = 158;
    // When on, the IME and extra-keys bar float over the display instead of
    // shrinking it: the bar rides up with the keyboard but the surface keeps
    // its full size. See relayout() and buildExtraKeysBar().
    private static final String KEY_KEYBOARD_FLOATING = "keyboard_floating";
    private boolean mKeyboardFloating = true;
    // Persistent "tap to open Settings" notification, toggleable in Settings > General.
    private static final String KEY_NOTIFICATION_ENABLED = "settings_notification";
    private static final String KEY_SCREEN_ORIENTATION = "screen_orientation";
    private static final String[] SCREEN_ORIENTATIONS = {
        "auto", "portrait", "landscape", "reverse portrait", "reverse landscape"
    };
    private static final String KEY_PIP_MODE = "pip_mode";
    private static final String KEY_POINTER_CAPTURE = "pointer_capture";
    private static final String KEY_TRANSFORM_CAPTURED_POINTER = "transform_captured_pointer";
    private static final String KEY_CAPTURED_POINTER_SPEED_FACTOR = "captured_pointer_speed_factor";
    private static final String KEY_SCROLL_SPEED = "scroll_speed";
    private static final String KEY_SCROLL_REVERSE = "scroll_reverse";
    private static final String KEY_SCROLL_THRESHOLD = "touchpad_scroll_threshold";
    private static final String KEY_MOVE_THRESHOLD = "touchpad_move_threshold";
    private static final String KEY_GESTURE_SCALE = "touchpad_gesture_scale";
    // System soft-keyboard bridge: hidden input, text forwarding and toggle.
    private SystemIME systemIme;
    private int mImeBottom = 0;   // last IME bottom inset
    private int mBarHeight = 0;   // extra-keys bar height in px
    private ExtraKeysBar extraKeysBar;
    private FrameLayout mRoot;    // content root, host of the extra-keys bar
    private float mDensity = 1f;
    private boolean mPointerCaptureEnabled = false;
    private String mCapturedPointerTransform = "no";
    private float mCapturedPointerSpeedFactor = 1f;
    private float mPointerX = 0f;
    private float mPointerY = 0f;
    private boolean mPointerPositionKnown = false;
    private final float[] mTransformedPointerDelta = new float[2];
    private final int[] mSurfaceLocationInWindow = new int[2];
    private Touchpad mCapturedTouchpad;
    private int mCapturedTouchpadDeviceId = -1;
    private boolean mCapturedTouchpadBaselineValid = false;
    private int mCapturedTouchpadBaselinePointers = 0;
    private float mCapturedTouchpadLastCentroidX = 0f;
    private float mCapturedTouchpadLastCentroidY = 0f;
    private final float[] mCapturedTouchpadResolvedDelta = new float[2];
    private final SparseArray<Float> mButtonDragLastX = new SparseArray<>();
    private final SparseArray<Float> mButtonDragLastY = new SparseArray<>();
    // Single-button clickpads report every physical press as BUTTON_PRIMARY. Keep
    // the resolved Linux button latched for the duration of that press.
    private int mLastTouchpadButtonPressed = 0;
    private String mDisplayCutoutMode = DisplayCutoutMode.HIDE_ALL;
    private boolean mPipTransitionPending = false;
    private boolean mVirtualKeyboardVisibleBeforePip = false;
    // Layout JSON the current bar was built from; used to detect edits on resume.
    private String mAppliedLayoutJson = "";

    private final BroadcastReceiver compatibleBridgeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!CompatibleBridge.ACTION_START.equals(intent.getAction()))
                return;

            Bundle bundle = intent.getBundleExtra(null);
            IBinder binder = bundle == null ? null : bundle.getBinder(null);
            if (binder == null)
                return;

            ICompatibleBridge bridge = ICompatibleBridge.Stub.asInterface(binder);
            boolean newBridge = compatibleBridge == null
                || !compatibleBridge.asBinder().isBinderAlive();
            if (newBridge) {
                compatibleBridge = bridge;
                compatibleFdReady = false;
                try {
                    binder.linkToDeath(() -> runOnUiThread(() -> {
                        if (compatibleBridge != null
                                && compatibleBridge.asBinder() == binder) {
                            compatibleBridge = null;
                            compatibleFdReady = false;
                        }
                    }), 0);
                } catch (RemoteException ignored) {
                }
            }

            // Wait until the Surface exists before asking the bridge to connect to
            // the daemon. The daemon reads the consumer hello synchronously, so a
            // connection opened before nativeStart() would temporarily block it.
            if (surfaceReady)
                attachCompatibleFd();
        }
    };

    public static MainActivity sInstance;

    // ADDED: VirtualKeyboardView instance
    private VirtualKeyboardView virtualKeyboardView;

    // ==================== 触摸板相关设置 ====================
    public static final String KEY_TOUCHPAD_MODE = "touchpad_mode";
    public static final String KEY_MOUSE_ACCEL = "mouse_speed"; // 名称仍为 speed，实际控制加速度强度

    // Routing gate: when on, non-mouse touches go to the virtual touchpad.
    private boolean isTouchpadMode = true;
    // Finger-gesture touchpad (relative motion, taps, drag, two-finger scroll).
    private VirtualTouchpad virtualTouchpad;

    static {
        // Loads the single shared .so backing MainActivity, Native and
        // CameraServices; the last two only declare their natives.
        System.loadLibrary("anland_consumer");
    }
    // Forwards the current display refresh rate to the daemon so KWin can repace
    // its RenderLoop. Re-fires on every onDisplayChanged (e.g. 60/90/120 switch).
    private final DisplayManager.DisplayListener displayListener =
        new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) {}
            @Override public void onDisplayRemoved(int displayId) {}
            @Override public void onDisplayChanged(int displayId) {
                Display d = getDisplay();
                if (d != null && d.getDisplayId() == displayId)
                    pushRefreshRate();
            }
        };

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && clipboard != null) {
            clipboard.pushClipboard();
        }
    }

    private void pushRefreshRate() {
        Display d = getDisplay();
        if (d != null)
            Native.nativeSetRefreshRate(d.getRefreshRate());
    }

    // Push the current connection settings (socket path / root mode) to native
    // before (re)connecting. Compatible builds receive the daemon fd from the
    // Termux-side Binder bridge instead of calling connect() from this UID.
    private void applyConnectionConfig() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String sock = prefs.getString(KEY_SOCKET_PATH, DEFAULT_SOCKET_PATH);
        if (sock == null || sock.trim().isEmpty())
            sock = DEFAULT_SOCKET_PATH;
        boolean useRoot = prefs.getBoolean(KEY_USE_ROOT, false);
        String helperPath = getApplicationInfo().nativeLibraryDir + "/libfdhelper.so";
        String bridgePath = getCacheDir().getAbsolutePath() + "/anland_fdbridge.sock";
        Native.nativeSetCompatibleMode(BuildConfig.COMPATIBLE);
        Native.nativeConfigure(sock.trim(), useRoot, helperPath, bridgePath);
        if (BuildConfig.COMPATIBLE)
            attachCompatibleFd();
        int customW = prefs.getInt("custom_width", 0);
        int customH = prefs.getInt("custom_height", 0);
        customScreenWidth = prefs.getInt("custom_width", 0);
        customScreenHeight = prefs.getInt("custom_height", 0);
        Native.nativeSetCustomResolution(customW, customH);
    }

    private void registerCompatibleReceiver() {
        if (!BuildConfig.COMPATIBLE || compatibleReceiverRegistered)
            return;

        IntentFilter filter = new IntentFilter(CompatibleBridge.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(compatibleBridgeReceiver, filter, Context.RECEIVER_EXPORTED);
        else
            registerReceiver(compatibleBridgeReceiver, filter);
        compatibleReceiverRegistered = true;
    }

    private void attachCompatibleFd() {
        if (!BuildConfig.COMPATIBLE || compatibleBridge == null || compatibleFdReady)
            return;

        ParcelFileDescriptor pfd = null;
        int fd = -1;
        try {
            pfd = compatibleBridge.getConnection();
            if (pfd == null)
                return;
            fd = pfd.detachFd();
            Native.nativeSetCompatibleFd(fd);
            compatibleFdReady = true;
        } catch (Exception e) {
            Log.e(TAG, "failed to receive compatible daemon socket fd", e);
        } finally {
            if (fd < 0 && pfd != null) {
                try {
                    pfd.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void stopNative() {
        Native.nativeStop();
        compatibleFdReady = false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setupMediaAudio();
        applyScreenOrientation();

        sInstance = this;
        clipboard = new Clipboard(this);
        registerCompatibleReceiver();
        mDisplayCutoutMode = DisplayCutoutMode.get(
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE));

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        // Take over inset handling: the IME insets are dispatched to our
        // OnApplyWindowInsetsListener (so we can resize the surface) instead of
        // the system auto-panning the fullscreen window.
        getWindow().setDecorFitsSystemWindows(false);

        surfaceView = new SurfaceView(this);
        surfaceView.setFocusable(true);
        surfaceView.setFocusableInTouchMode(true);
        systemIme = new SystemIME(this, this);

        FrameLayout root = new FrameLayout(this) {
            @Override
            public boolean dispatchCapturedPointerEvent(MotionEvent event) {
                if (handleCapturedPointerEvent(event))
                    return true;
                return super.dispatchCapturedPointerEvent(event);
            }

            @Override
            public void dispatchPointerCaptureChanged(boolean hasCapture) {
                super.dispatchPointerCaptureChanged(hasCapture);
                if (!hasCapture) {
                    releaseAllMouseButtons();
                    resetCapturedTouchpadGesture();
                }
            }
        };
        root.setBackgroundColor(Color.BLACK);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        // 1x1 so the IME target never overlaps the surface and steals touches.
        root.addView(systemIme.getInputView(), new FrameLayout.LayoutParams(1, 1));

        // Bottom extra-keys bar (Termux-style). Hidden by default; toggled by the
        // settings switch and synced in onResume. The layout (and thus the row
        // count / height) comes from the user's JSON config; see buildExtraKeysBar.
        mRoot = root;
        mDensity = getResources().getDisplayMetrics().density;
        mCapturedTouchpad = new Touchpad(this, new CapturedTouchpadOutput(), false);
        mKeyboardFloating = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_KEYBOARD_FLOATING, true);
        buildExtraKeysBar();

        // ADDED: Create VirtualKeyboardView (hidden initially)
        virtualKeyboardView = new VirtualKeyboardView(this);
        virtualKeyboardView.setVisibility(View.GONE);
        virtualKeyboardView.setOnKeyEventListener(new VirtualKeyboardView.OnKeyEventListener() {
            @Override
            public void onKeyDown(int scanCode) {
                Native.nativeSendKey(0, scanCode);
            }
            @Override
            public void onKeyUp(int scanCode) {
                Native.nativeSendKey(1, scanCode);
            }
        });
        // Add to root with no gravity – we will position manually.
        root.addView(virtualKeyboardView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.NO_GRAVITY
        ));
        // Reposition the virtual keyboard when the root layout size changes
        // (e.g. freeform / small-window mode resize).
        root.addOnLayoutChangeListener((v, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            int newW = right - left;
            int newH = bottom - top;
            int oldW = oldRight - oldLeft;
            int oldH = oldBottom - oldTop;
            Log.d("VirtualKeyboard", "root layout changed: " + newW + "x" + newH
                    + " (was " + oldW + "x" + oldH + ")");
            if (newW != oldW || newH != oldH) {
                if (virtualKeyboardView != null
                        && virtualKeyboardView.getVisibility() == View.VISIBLE) {
                    positionVirtualKeyboard();
                }
            }
        });
        // Positioning happens lazily the first time the keyboard is shown
        // (see toggleVirtualKeyboard). Positioning it here would spin forever:
        // the view starts GONE and a GONE view is never measured.

        setContentView(root);
        // Establish a focused descendant after attachment so the DecorView routes
        // input through the surface even while the extra-keys bar is hidden.
        surfaceView.requestFocus();
        surfaceView.getHolder().addCallback(this);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            // When the IME hides by any means (toggle, system back, or the IME's
            // own close button), release the hidden input so its focus state
            // stays in sync — otherwise reopening needs a second press.
            // Ignore the initial hidden-inset dispatch while a show request is
            // settling. That dispatch can arrive between requestFocus() and
            // showSoftInput(), and disabling the target there makes the first
            // bound-key press appear to do nothing.
            boolean imeWasVisible = mImeBottom > 0;
            if (!insets.isVisible(WindowInsets.Type.ime()) && imeWasVisible) {
                View focused = getCurrentFocus();
                systemIme.releaseHiddenInput();
                if (focused == systemIme.getInputView() || getCurrentFocus() == null)
                    surfaceView.requestFocus();
            }
            applyDisplaySafeInsets(insets);
            applyImeInset(insets);
            return v.onApplyWindowInsets(insets);
        });

        setupFullscreen();
        setupCursorHiding();

        // ===== 加载触摸板设置 =====
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        isTouchpadMode = prefs.getBoolean(KEY_TOUCHPAD_MODE, false);
        virtualTouchpad = new VirtualTouchpad(this);
        virtualTouchpad.setAccelStrength(prefs.getFloat(KEY_MOUSE_ACCEL, 1.0f));
        reloadPointerCapturePreferences();
    }

    private static final String NOTIFICATION_CHANNEL = "anland_channel";
    private static final int NOTIFICATION_ID = 1;

    private void showSettingsNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL, getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_desc));
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(channel);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Notification.Builder builder = new Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setShowWhen(false);

        PendingIntent tapIntent = createNotificationIntent(
            UserActions.getResponse(prefs, UserActions.NOTIFICATION_TAP), 0);
        if (tapIntent != null)
            builder.setContentIntent(tapIntent);

        addNotificationAction(builder, prefs, UserActions.NOTIFICATION_FIRST_BUTTON, 1);
        addNotificationAction(builder, prefs, UserActions.NOTIFICATION_SECOND_BUTTON, 2);

        nm.notify(NOTIFICATION_ID, builder.build());
    }

    private void addNotificationAction(Notification.Builder builder, SharedPreferences prefs,
            String action, int requestCode) {
        String response = UserActions.getResponse(prefs, action);
        PendingIntent intent = createNotificationIntent(response, requestCode);
        int titleRes = notificationActionTitle(response);
        if (intent != null && titleRes != 0)
            builder.addAction(0, getString(titleRes), intent);
    }

    private PendingIntent createNotificationIntent(String response, int requestCode) {
        if (UserActions.NO_ACTION.equals(response))
            return null;

        if (UserActions.OPEN_PREFERENCES.equals(response)) {
            Intent intent = new Intent(this, SettingsActivity.class);
            intent.setAction(Intent.ACTION_MAIN);
            return PendingIntent.getActivity(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        Intent intent = new Intent(this, UserActionReceiver.class);
        intent.putExtra(UserActionReceiver.EXTRA_RESPONSE, response);
        return PendingIntent.getBroadcast(this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private int notificationActionTitle(String response) {
        switch (response) {
            case UserActions.TOGGLE_SOFT_KEYBOARD:
                return R.string.notification_action_toggle_ime;
            case UserActions.TOGGLE_ADDITIONAL_KEY_BAR:
                return R.string.notification_action_toggle_extra_keys;
            case UserActions.OPEN_PREFERENCES:
                return R.string.notification_action_preferences;
            case UserActions.RELEASE_POINTER_AND_KEYBOARD_CAPTURE:
                return R.string.notification_action_release_captures;
            case UserActions.RESTART_ACTIVITY:
                return R.string.notification_action_restart;
            case UserActions.EXIT:
                return R.string.notification_action_exit;
            case UserActions.TOGGLE_TOUCHPAD_MODE:
                return R.string.notification_action_touchpad_mode;
            case UserActions.TOGGLE_SCREEN_ORIENTATION:
                return R.string.notification_action_screen_orientation;
            default:
                return 0;
        }
    }

    // ADDED: Helper to position virtual keyboard at bottom-center
    private void positionVirtualKeyboard() {
        if (virtualKeyboardView == null) return;
        int w = virtualKeyboardView.getMeasuredWidth();
        int h = virtualKeyboardView.getMeasuredHeight();
        if (w <= 0 || h <= 0) {
            // Only retry while the keyboard is actually visible. A GONE view is
            // never measured (width/height stay 0), so reposting unconditionally
            // would re-queue this Runnable on the main thread every frame forever
            // and cause global jank/卡顿 even while the keyboard is hidden.
            if (virtualKeyboardView.getVisibility() == View.VISIBLE) {
                virtualKeyboardView.post(this::positionVirtualKeyboard);
            }
            return;
        }
        // Use the root layout's dimensions instead of DisplayMetrics so that
        // positioning is correct in freeform / small-window mode.
        int parentW = mRoot.getWidth();
        int parentH = mRoot.getHeight();
        if (parentW <= 0 || parentH <= 0) {
            // Root not laid out yet — retry next frame.
            if (virtualKeyboardView.getVisibility() == View.VISIBLE) {
                virtualKeyboardView.post(this::positionVirtualKeyboard);
            }
            return;
        }
        float x = (parentW - w) / 2f;
        float y = parentH - h - dpToPx(50);
        // Clamp to visible area.
        x = Math.max(0, Math.min(x, parentW - w));
        y = Math.max(0, Math.min(y, parentH - h));
        virtualKeyboardView.setX(x);
        virtualKeyboardView.setY(y);
        Log.d("VirtualKeyboard", "positionVirtualKeyboard: x=" + x + ", y=" + y
                + " parent=" + parentW + "x" + parentH + " view=" + w + "x" + h);
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void setupFullscreen() {
        WindowInsetsController ctrl = getWindow().getInsetsController();
        if (ctrl != null) {
            ctrl.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            ctrl.setSystemBarsBehavior(
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.layoutInDisplayCutoutMode = DisplayCutoutMode.hidesCutout(mDisplayCutoutMode)
            ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
        getWindow().setAttributes(attrs);
    }

    private void applyDisplaySafeInsets(WindowInsets insets) {
        Insets cutout = Insets.NONE;
        // Android 15+ forces full-screen apps targeting API 35+ into cutout areas,
        // even when NEVER is requested. Recreate Termux:X11's safe layout manually.
        if (!DisplayCutoutMode.hidesCutout(mDisplayCutoutMode)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
            cutout = insets.getInsets(WindowInsets.Type.displayCutout());

        int roundedCornerTop = 0;
        int roundedCornerBottom = 0;
        int roundedCornerLeft = 0;
        int roundedCornerRight = 0;
        if (!DisplayCutoutMode.hidesRoundedCorners(mDisplayCutoutMode)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Keep full-width strips clear of the rounded corners, matching the
            // rectangular safe area used below edge-to-edge input methods.
            RoundedCorner topLeft = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT);
            RoundedCorner topRight = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT);
            RoundedCorner bottomLeft = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT);
            RoundedCorner bottomRight = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT);
            if (topLeft != null) {
                roundedCornerTop = topLeft.getRadius();
                roundedCornerLeft = topLeft.getRadius();
            }
            if (topRight != null) {
                roundedCornerTop = Math.max(roundedCornerTop, topRight.getRadius());
                roundedCornerRight = topRight.getRadius();
            }
            if (bottomLeft != null) {
                roundedCornerBottom = bottomLeft.getRadius();
                roundedCornerLeft = Math.max(roundedCornerLeft, bottomLeft.getRadius());
            }
            if (bottomRight != null) {
                roundedCornerBottom = Math.max(roundedCornerBottom, bottomRight.getRadius());
                roundedCornerRight = Math.max(roundedCornerRight, bottomRight.getRadius());
            }
        }

        Insets safeInsets;
        boolean avoidAllInLandscape = DisplayCutoutMode.HIDE_NONE.equals(mDisplayCutoutMode)
            && getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        if (avoidAllInLandscape) {
            // The phone's physical top and bottom become the short left and
            // right edges in landscape and reverse-landscape orientations.
            safeInsets = Insets.of(Math.max(cutout.left, roundedCornerLeft), 0,
                Math.max(cutout.right, roundedCornerRight), 0);
        } else {
            safeInsets = Insets.of(cutout.left, Math.max(cutout.top, roundedCornerTop),
                cutout.right, Math.max(cutout.bottom, roundedCornerBottom));
        }

        // The raised desktop ends directly above the IME. Keeping the display's
        // bottom safe inset there would leave a black strip between the desktop
        // (or extra-keys bar) and the keyboard. Use this dispatch's IME state,
        // rather than mImeBottom, because it has not been updated yet.
        if (!mKeyboardFloating && insets.isVisible(WindowInsets.Type.ime())) {
            safeInsets = Insets.of(safeInsets.left, safeInsets.top, safeInsets.right, 0);
        }

        if (mRoot.getPaddingLeft() != safeInsets.left || mRoot.getPaddingTop() != safeInsets.top
                || mRoot.getPaddingRight() != safeInsets.right
                || mRoot.getPaddingBottom() != safeInsets.bottom) {
            mRoot.setPadding(safeInsets.left, safeInsets.top, safeInsets.right, safeInsets.bottom);
        }
    }

    private void setupCursorHiding() {
        surfaceView.setPointerIcon(PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL));
    }

    /* Bind hardware volume controls and media focus to the same usage as the
     * AAudio output stream. This prevents Android audio policy from throttling
     * short Linux UI sounds while the desktop window is foregrounded. */
    private void setupMediaAudio() {
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        mAudioManager = getSystemService(AudioManager.class);
        if (mAudioManager == null)
            return;

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        mAudioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(change ->
                        Log.i(TAG, "audio focus change: " + change))
                .build();
    }

    private void requestMediaAudioFocus() {
        if (mAudioManager == null || mAudioFocusRequest == null)
            return;
        int result = mAudioManager.requestAudioFocus(mAudioFocusRequest);
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            Log.w(TAG, "media audio focus not granted: " + result);
    }

    private void abandonMediaAudioFocus() {
        if (mAudioManager != null && mAudioFocusRequest != null)
            mAudioManager.abandonAudioFocusRequest(mAudioFocusRequest);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mPipTransitionPending = false;

        requestMediaAudioFocus();

        String displayCutoutMode = DisplayCutoutMode.get(
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE));
        if (!mDisplayCutoutMode.equals(displayCutoutMode)) {
            // Display safe-area changes invalidate Surface and inset dimensions; rebuild the
            // activity before restarting the native consumer, as Termux:X11 does.
            recreate();
            return;
        }

        applyScreenOrientation();

        // Show settings notification while in foreground, unless disabled in Settings.
        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_NOTIFICATION_ENABLED, true)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1003);
            } else {
                showSettingsNotification();
            }
        } else {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFICATION_ID);
        }

        // Re-check accessibility service state on resume
        KeyInterceptor.recheck();

        // If the user edited the layout JSON in Settings, rebuild the bar so the
        // change takes effect on return to the desktop.
        String layoutJson = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_EXTRA_KEYS_LAYOUT, "");
        if (!layoutJson.equals(mAppliedLayoutJson))
            rebuildExtraKeysBar();

        // Pick up a Keyboard-floating toggle made in Settings: update the bar's
        // backdrop and re-run the layout so the surface margin tracks the new mode.
        mKeyboardFloating = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_KEYBOARD_FLOATING, true);
        if (extraKeysBar != null)
            extraKeysBar.setFloating(mKeyboardFloating);
        relayout();

        // Sync extra-keys bar visibility with the settings switches. With auto-show
        // ON the bar tracks the keyboard (hidden now if the IME isn't up); with it
        // OFF the master switch decides. See shouldShowBar.
        setExtraKeysBarVisible(shouldShowBar(systemIme.isImeVisible()));

        setupFullscreen();
        DisplayManager dm = getSystemService(DisplayManager.class);
        if (dm != null)
            dm.registerDisplayListener(displayListener, null);
        // Bring the camera service up (or confirm it disabled) BEFORE nativeStart, so
        // the render thread's do_connect() sees a settled camera_service_is_ready()
        // and registers SERVICE_TYPE_CAMERA on the very first connect rather than a
        // later reconnect. Idempotent, so safe to call on every resume.
        applyCameraState();
        if (surfaceReady) {
            stopNative();
            applyConnectionConfig();
            Native.nativeStart(surfaceView.getHolder().getSurface(), clipboard);
            pushRefreshRate();
            applyMicState();
            applyAudioLatency();
            applyAudioKeepalive();
        }

        // ===== 重新读取触摸板设置 =====
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        isTouchpadMode = prefs.getBoolean(KEY_TOUCHPAD_MODE, false);
        virtualTouchpad.setAccelStrength(prefs.getFloat(KEY_MOUSE_ACCEL, 1.0f));
        reloadPointerCapturePreferences();
    }

    private void applyScreenOrientation() {
        String orientation = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_SCREEN_ORIENTATION, "auto");
        int requestedOrientation;
        switch (orientation) {
            case "portrait":
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                break;
            case "landscape":
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                break;
            case "reverse portrait":
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
                break;
            case "reverse landscape":
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                break;
            default:
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
                break;
        }

        if (getRequestedOrientation() != requestedOrientation)
            setRequestedOrientation(requestedOrientation);
    }

    void performUserAction(String response) {
        if (response == null)
            return;

        switch (response) {
            case UserActions.TOGGLE_SOFT_KEYBOARD:
                systemIme.toggleSystemKeyboard();
                break;
            case UserActions.TOGGLE_ADDITIONAL_KEY_BAR:
                toggleExtraKeysBar();
                break;
            case UserActions.OPEN_PREFERENCES:
                startActivity(new Intent(this, SettingsActivity.class));
                break;
            case UserActions.RELEASE_POINTER_AND_KEYBOARD_CAPTURE:
                if (surfaceView.hasPointerCapture())
                    surfaceView.releasePointerCapture();
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(KEY_ACCESSIBILITY_ENABLED, false).apply();
                KeyInterceptor.releaseCapture();
                break;
            case UserActions.RESTART_ACTIVITY:
                recreate();
                break;
            case UserActions.EXIT:
                finishAndRemoveTask();
                break;
            case UserActions.TOGGLE_TOUCHPAD_MODE:
                toggleTouchpadMode();
                break;
            case UserActions.TOGGLE_SCREEN_ORIENTATION:
                toggleScreenOrientation();
                break;
            default:
                break;
        }
    }

    private void toggleTouchpadMode() {
        isTouchpadMode = !isTouchpadMode;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(KEY_TOUCHPAD_MODE, isTouchpadMode).apply();
        Toast.makeText(this, isTouchpadMode
            ? R.string.toast_touchpad_mode_relative
            : R.string.toast_touchpad_mode_absolute, Toast.LENGTH_SHORT).show();
    }

    private void toggleScreenOrientation() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String current = prefs.getString(KEY_SCREEN_ORIENTATION, SCREEN_ORIENTATIONS[0]);
        int next = 0;
        for (int i = 0; i < SCREEN_ORIENTATIONS.length; i++) {
            if (SCREEN_ORIENTATIONS[i].equals(current)) {
                next = (i + 1) % SCREEN_ORIENTATIONS.length;
                break;
            }
        }
        prefs.edit().putString(KEY_SCREEN_ORIENTATION, SCREEN_ORIENTATIONS[next]).apply();
        String label = getResources().getStringArray(R.array.screen_orientation_labels)[next];
        Toast.makeText(this, getString(R.string.toast_screen_orientation, label),
            Toast.LENGTH_SHORT).show();
        applyScreenOrientation();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (surfaceView.hasPointerCapture())
            surfaceView.releasePointerCapture();
        releaseAllMouseButtons();
        resetCapturedTouchpadGesture();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        DisplayManager dm = getSystemService(DisplayManager.class);
        if (dm != null)
            dm.unregisterDisplayListener(displayListener);
        if (!mPipTransitionPending && !isInPictureInPictureMode())
            stopNative();
        abandonMediaAudioFocus();
    }

    private boolean hasPipPermission() {
        AppOpsManager appOps = getSystemService(AppOpsManager.class);
        return appOps != null && appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
            android.os.Process.myUid(), getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        boolean pipEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_PIP_MODE, false);
        if (pipEnabled
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
                && hasPipPermission()) {
            PictureInPictureParams params = new PictureInPictureParams.Builder().build();
            mPipTransitionPending = enterPictureInPictureMode(params);
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode,
            Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        mPipTransitionPending = false;

        if (isInPictureInPictureMode) {
            setExtraKeysBarVisible(false);
            mVirtualKeyboardVisibleBeforePip = virtualKeyboardView != null
                && virtualKeyboardView.getVisibility() == View.VISIBLE;
            if (mVirtualKeyboardVisibleBeforePip)
                virtualKeyboardView.setVisibility(View.GONE);
        } else {
            setExtraKeysBarVisible(shouldShowBar(systemIme.isImeVisible()));
            if (mVirtualKeyboardVisibleBeforePip && virtualKeyboardView != null) {
                virtualKeyboardView.setVisibility(View.VISIBLE);
                virtualKeyboardView.post(this::positionVirtualKeyboard);
            }
            mVirtualKeyboardVisibleBeforePip = false;
        }
    }

    @Override
    protected void onDestroy() {
        abandonMediaAudioFocus();
        stopNative();
        if (compatibleReceiverRegistered) {
            unregisterReceiver(compatibleBridgeReceiver);
            compatibleReceiverRegistered = false;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        if (cameraInited) {
            CameraServices.nativeDestroyCameraService();
            cameraInited = false;
        }
        if (sInstance == this)
            sInstance = null;
        super.onDestroy();
    }

    /*
     * Bring the camera service up only when the user enabled it AND CAMERA is
     * granted. The native fds/threads are created once and persist across transport
     * restarts, so this is idempotent (guarded by cameraInited). When the toggle is
     * off we never init, so do_connect() never registers SERVICE_TYPE_CAMERA and the
     * producer never sees it. Request the permission if enabled but not yet granted;
     * onRequestPermissionsResult finishes the init.
     */
    private void applyCameraState() {
        boolean want = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_CAMERA_ENABLED, false);
        if (!want || cameraInited)
            return;
        if (checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            CameraServices.nativeInitCameraService(this);
            cameraInited = true;
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    /*
     * Forward the mic only when the user enabled it AND RECORD_AUDIO is granted.
     * If enabled but not yet granted, request it; onRequestPermissionsResult applies
     * the result. Safe to call after every nativeStart (re)connect.
     */
    /* Push the speaker/mic latency presets to native (which forwards them to the
     * producer's PipeWire nodes). Safe to call after every (re)connect and whenever
     * the user changes a preset. */
    private void applyAudioLatency() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int speakerMs = prefs.getInt(KEY_SPEAKER_LATENCY_MS, 0);
        int micMs = prefs.getInt(KEY_MIC_LATENCY_MS, 0);
        Native.nativeSetAudioLatency(speakerMs, micMs);
    }

    private void applyAudioKeepalive() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Native.nativeSetAudioKeepalive(prefs.getBoolean(KEY_AUDIO_KEEPALIVE, false));
    }

    private void applyMicState() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean want = prefs.getBoolean(KEY_MIC_ENABLED, false);
        if (!want) {
            Native.nativeSetMicEnabled(false);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            Native.nativeSetMicEnabled(true);
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                               REQ_RECORD_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Native.nativeSetMicEnabled(granted);
        } else if (requestCode == REQ_CAMERA) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && !cameraInited && getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getBoolean(KEY_CAMERA_ENABLED, false)) {
                CameraServices.nativeInitCameraService(this);
                cameraInited = true;
            }
        } else if (requestCode == 1003) {
            if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getBoolean(KEY_NOTIFICATION_ENABLED, true)) {
                showSettingsNotification();
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surfaceChanged: " + width + "x" + height);
        viewWidth = width;
        viewHeight = height;
        surfaceReady = true;
        // Same ordering guarantee as onResume: camera service settled before connect.
        applyCameraState();
        stopNative();
        applyConnectionConfig();
        Native.nativeStart(holder.getSurface(), clipboard);
        pushRefreshRate();
        applyMicState();
        applyAudioLatency();
        applyAudioKeepalive();

        // ===== 更新屏幕尺寸并重置平滑状态 =====
        virtualTouchpad.onSurfaceChanged();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        releaseAllMouseButtons();
        resetCapturedTouchpadGesture();
        stopNative();
    }


    // Shrink the surface to the area above the keyboard (and the extra-keys bar,
    // if shown) by giving it a bottom margin. The size change flows through
    // surfaceChanged -> nativeStart and the producer's resize path, so the
    // focused window relayouts into the upper region instead of hiding behind
    // the keyboard. Reset when the IME goes away.
    private void applyImeInset(WindowInsets insets) {
        int newImeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
        boolean imeVisible = newImeBottom > 0;
        boolean wasImeVisible = mImeBottom > 0;

        mImeBottom = newImeBottom;

        if (imeVisible != wasImeVisible)
            syncExtraKeysBarWithIme(imeVisible);

        relayout();
    }

    // Desired extra-keys bar visibility for the current keyboard state. The two
    // switches are independent: with "auto-show" ON the bar tracks the keyboard
    // (regardless of the master switch), so it appears whenever the IME opens —
    // including via the bound virtual-keyboard key, the app's only other opener.
    // With "auto-show" OFF the master switch sets the lifecycle baseline; IME
    // changes then preserve any temporary visibility chosen by a user action.
    private boolean shouldShowBar(boolean imeVisible) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean autoShow = prefs.getBoolean(KEY_AUTO_SHOW_EXTRA_KEYS, true);
        if (autoShow)
            return imeVisible;
        return prefs.getBoolean(KEY_EXTRA_KEYS_ENABLED, false);
    }

    // IME changes only control the bar in auto-show mode. Otherwise a user action
    // may temporarily show or hide the bar without the next IME callback undoing it.
    private void syncExtraKeysBarWithIme(boolean imeVisible) {
        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_AUTO_SHOW_EXTRA_KEYS, true))
            setExtraKeysBarVisible(imeVisible);
    }

    // Recompute the surface bottom margin and the bar position from the current
    // IME inset and bar visibility. The surface ends above the bar, which sits
    // directly on top of the IME: "surface / extra-keys bar / IME" bottom-up.
    private void relayout() {
        boolean barVisible = extraKeysBar != null && extraKeysBar.getVisibility() == View.VISIBLE;
        int barH = barVisible ? mBarHeight : 0;
        // Floating mode: keyboard + bar overlay the display, so the surface keeps
        // its full size (target 0). Default mode: shrink the surface above both.
        int target = mKeyboardFloating ? 0 : (mImeBottom + barH);

        FrameLayout.LayoutParams lp =
            (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (lp.bottomMargin != target) {       // skip redundant surface restart
            lp.bottomMargin = target;
            surfaceView.setLayoutParams(lp);
        }
        if (extraKeysBar != null)
            extraKeysBar.setTranslationY(-mImeBottom);
    }

    // Show/hide the extra-keys bar and re-apply the layout so the display area
    // is compressed (shown) or restored (hidden).
    private void setExtraKeysBarVisible(boolean visible) {
        if (extraKeysBar == null) return;
        boolean cur = extraKeysBar.getVisibility() == View.VISIBLE;
        if (cur == visible) return;
        extraKeysBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) extraKeysBar.reset();
        relayout();
    }

    // Construct the extra-keys bar from the user's saved JSON layout and add it to
    // the content root (hidden). The bar height mirrors Termux at 37.5dp/row and
    // scales with the parsed row count. Records the layout JSON it was built from.
    private void buildExtraKeysBar() {
        extraKeysBar = new ExtraKeysBar(this, new ExtraKeysBar.Sender() {
            @Override public void key(int action, int evdev) { Native.nativeSendKey(action, evdev); }
            @Override public void text(String s) {
                if (!s.isEmpty()) Native.nativeSendTextInput(s.getBytes(StandardCharsets.UTF_8));
            }
            // Tapping the ⌨ key keeps the original behaviour: toggle the system IME.
            @Override public void toggleKeyboard() { systemIme.toggleSystemKeyboard(); }
            // Pulling up on the ⌨ key toggles the floating virtual keyboard.
            @Override public void toggleVirtualKeyboard() {
                if (virtualKeyboardView.getVisibility() == View.VISIBLE) {
                    virtualKeyboardView.setVisibility(View.GONE);
                } else {
                    Log.d("VirtualKeyboard", "toggle: showing keyboard, mRoot="
                            + mRoot.getWidth() + "x" + mRoot.getHeight());
                    virtualKeyboardView.setVisibility(View.VISIBLE);
                    virtualKeyboardView.bringToFront();
                    // Re-position it (in case screen size changed)
                    positionVirtualKeyboard();
                    // Hide the system IME to avoid overlap with the floating keyboard.
                    InputMethodManager imm = getSystemService(InputMethodManager.class);
                    if (imm != null && getCurrentFocus() != null) {
                        imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
                    }
                }
            }
            @Override public void openSettings() {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        mBarHeight = Math.round(37.5f * mDensity * extraKeysBar.getRowCount());
        // Keep the virtual screen height even when the bar is visible.
        if ((mBarHeight & 1) != 0)
            mBarHeight++;
        extraKeysBar.setFloating(mKeyboardFloating);
        extraKeysBar.setVisibility(View.GONE);
        mRoot.addView(extraKeysBar, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, mBarHeight, Gravity.BOTTOM));
        mAppliedLayoutJson = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_EXTRA_KEYS_LAYOUT, "");
    }

    // Replace the bar with a freshly-parsed one after the user edits the layout in
    // Settings. Called from onResume when the saved JSON no longer matches what the
    // current bar was built from.
    private void rebuildExtraKeysBar() {
        if (mRoot == null) return;
        if (extraKeysBar != null) {
            extraKeysBar.reset();
            mRoot.removeView(extraKeysBar);
        }
        buildExtraKeysBar();
        setExtraKeysBarVisible(shouldShowBar(systemIme.isImeVisible()));
        relayout();
    }

    // Toggle the extra-keys bar on its own (e.g. from the Back key), independent of
    // the soft keyboard. Showing it just compresses the display area above the bar.
    private void toggleExtraKeysBar() {
        boolean visible = extraKeysBar != null
            && extraKeysBar.getVisibility() == View.VISIBLE;
        setExtraKeysBarVisible(!visible);
    }

    /**
     * Handle the user-bound soft-keyboard toggle in every key dispatch path.
     * Accessibility interception runs before the Activity, so keeping this in
     * one helper keeps the setting consistent for both routes.
     */
    private boolean handleSoftKeyboardToggleKey(KeyEvent event) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int boundKeycode = prefs.getInt(KEY_BOUND_KEYCODE, -1);
        if (boundKeycode == -1 || event.getKeyCode() != boundKeycode)
            return false;

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
            systemIme.toggleSystemKeyboard();
        // Consume both the press and release. Once the IME owns focus, allowing
        // the release to continue through the normal dispatch path can send a
        // stray key-up to Linux and leave its key state stuck.
        return true;
    }

    // ---- SystemIME.Host ----

    @Override
    public ExtraKeysBar getExtraKeysBar() {
        return extraKeysBar;
    }

    // The IME was shown/hidden via SystemIME's toggle. In freeform mode the inset
    // callback may not fire, so explicitly apply the auto-show behavior here.
    @Override
    public void onImeVisibilityChanged(boolean visible) {
        syncExtraKeysBarWithIme(visible);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !surfaceView.isFocused())
            surfaceView.requestFocus();
        if (event.getActionMasked() == MotionEvent.ACTION_UP && mPointerCaptureEnabled)
            surfaceView.requestPointerCapture();

        // Activity events use window coordinates, while producer input starts
        // at the SurfaceView origin. Display-safe padding can move that origin.
        surfaceView.getLocationInWindow(mSurfaceLocationInWindow);
        float offsetX = mSurfaceLocationInWindow[0];
        float offsetY = mSurfaceLocationInWindow[1];
        event.offsetLocation(-offsetX, -offsetY);
        try {
            // ===== 触摸板模式优先处理（仅针对非鼠标触摸事件） =====
            if (isTouchpadMode && !isMouseEvent(event)) {
                return virtualTouchpad.onTouch(event);
            }

            if (isMouseEvent(event)) {
                int cls = event.getClassification();
                if (cls == CLASSIFICATION_TWO_FINGER_SWIPE)
                    return handleTouchpadScroll(event);
                if (cls == CLASSIFICATION_MULTI_FINGER_SWIPE || cls == CLASSIFICATION_PINCH)
                    return handleTouchEvent(event);
                return handleMouseEvent(event);
            }
            return handleTouchEvent(event);
        } finally {
            event.offsetLocation(offsetX, offsetY);
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (isMouseEvent(event)) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_MOVE) {
                surfaceView.getLocationInWindow(mSurfaceLocationInWindow);
                float surfaceX = event.getX() - mSurfaceLocationInWindow[0];
                float surfaceY = event.getY() - mSurfaceLocationInWindow[1];

                // Масштабирование
                float scaleX = (customScreenWidth > 0 && viewWidth > 0) ?
                        (float)customScreenWidth / viewWidth : 1.0f;
                float scaleY = (customScreenHeight > 0 && viewHeight > 0) ?
                        (float)customScreenHeight / viewHeight : 1.0f;

                Native.nativeSendMouseMotion(surfaceX * scaleX, surfaceY * scaleY,
                                      event.getAxisValue(MotionEvent.AXIS_RELATIVE_X),
                                      event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y));
                mPointerX = surfaceX * scaleX;
                mPointerY = surfaceY * scaleY;
                mPointerPositionKnown = true;
                return true;
            }
            if (action == MotionEvent.ACTION_SCROLL) {
                float vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                float hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
                if (vScroll != 0)
                    Native.nativeSendMouseScroll(0, -vScroll * 10);
                if (hScroll != 0)
                    Native.nativeSendMouseScroll(1, hScroll * 10);
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (handleSoftKeyboardToggleKey(event))
            return true;

        Boolean actionHandled = handleConfiguredUserAction(event);
        if (actionHandled != null)
            return actionHandled || super.onKeyDown(keyCode, event);

        if (event.getRepeatCount() > 0)
            return true;

        forwardKeyToLinux(event);
        return true;
    }

    // Some OEM ROMs (notably Xiaomi/HyperOS) dispatch Back via onBackPressed()
    // instead of onKeyDown().  Without this override the default Activity
    // onBackPressed() calls finish() — the app just exits.
    // Keep this empty (same approach as Termux-X11): the actual Back key
    // handling lives in onKeyDown(); this override simply prevents the
    // system from finishing the activity via gesture navigation.
    @Override
    public void onBackPressed() {
    }

    // Called from KeyInterceptor (accessibility service) to handle keys that
    // the normal onKeyDown/onKeyUp might miss (e.g. Fn combos).
    public boolean handleAccessibilityKey(KeyEvent event) {
        if (handleSoftKeyboardToggleKey(event))
            return true;

        // Some tablet keyboard layouts expose their physical Esc key as
        // Android Back (Linux KEY_BACK / Browser Back). Convert it only on
        // the accessibility-interception path so the normal Android Back and
        // configured user-action behaviour is unchanged when interception is off.
        if (shouldConvertBackToEscape(event)) {
            if (event.getRepeatCount() > 0)
                return true;
            return forwardKeyToLinux(event, true);
        }

        Boolean actionHandled = handleConfiguredUserAction(event);
        if (actionHandled != null)
            return actionHandled;

        if (event.getRepeatCount() > 0)
            return true;

        boolean handled = forwardKeyToLinux(event, true);
        releasePointerCaptureOnEscape(event);
        return handled;
    }

    private boolean forwardKeyToLinux(KeyEvent event) {
        return forwardKeyToLinux(event, false);
    }

    private boolean forwardKeyToLinux(KeyEvent event, boolean convertBackToEscape) {
        int keyCode = event.getKeyCode();
        int action = event.getAction() == KeyEvent.ACTION_DOWN ? 0 : 1;
        int evdev = -1;

        if (convertBackToEscape && shouldConvertBackToEscape(event))
            evdev = KeyCodeMapper.getScanCode(KeyEvent.KEYCODE_ESCAPE);

        // Reserved Android keys may carry vendor scan codes that Linux does not
        // recognize, so prefer their explicit evdev mapping.
        if (evdev == -1 && shouldPreferMappedKey(keyCode))
            evdev = KeyCodeMapper.getScanCode(keyCode);

        if (evdev == -1 && event.getScanCode() != 0)
            evdev = event.getScanCode();

        if (evdev == -1)
            evdev = KeyCodeMapper.getScanCode(keyCode);

        if (evdev == -1)
            return false;

        Native.nativeSendKey(action, evdev);
        return true;
    }

    private static boolean shouldConvertBackToEscape(KeyEvent event) {
        return event.getKeyCode() == KeyEvent.KEYCODE_BACK
                || event.getScanCode() == EVDEV_BROWSER_BACK;
    }

    private static boolean shouldPreferMappedKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_META_LEFT
                || keyCode == KeyEvent.KEYCODE_META_RIGHT
                || keyCode == KeyEvent.KEYCODE_SEARCH
                || keyCode == KeyEvent.KEYCODE_ASSIST
                || (keyCode >= KeyEvent.KEYCODE_F13 && keyCode <= KeyEvent.KEYCODE_F24);
    }

    public boolean isAccessibilityInterceptEnabled() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_ACCESSIBILITY_ENABLED, false);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (handleSoftKeyboardToggleKey(event))
            return true;

        Boolean actionHandled = handleConfiguredUserAction(event);
        if (actionHandled != null)
            return actionHandled || super.onKeyUp(keyCode, event);

        forwardKeyToLinux(event);
        releasePointerCaptureOnEscape(event);
        return true;
    }

    // Returns null when the event is not one of the configurable user actions.
    private Boolean handleConfiguredUserAction(KeyEvent event) {
        String action;
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            action = UserActions.VOLUME_UP;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            action = UserActions.VOLUME_DOWN;
        } else if (keyCode == KeyEvent.KEYCODE_BACK) {
            action = UserActions.BACK_BUTTON;
        } else if (isMediaSessionKey(keyCode)) {
            action = UserActions.MEDIA_KEYS;
        } else {
            return null;
        }

        String response = UserActions.getResponse(
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE), action);
        if (UserActions.NO_ACTION.equals(response))
            return false;

        if (UserActions.SEND_VOLUME_UP.equals(response)
                || UserActions.SEND_VOLUME_DOWN.equals(response)
                || UserActions.SEND_MEDIA_ACTION.equals(response)) {
            forwardKeyToLinux(event);
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
            performUserAction(response);
        return true;
    }

    private static boolean isMediaSessionKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_RECORD:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return true;
            default:
                return false;
        }
    }

    private void releasePointerCaptureOnEscape(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP
                && event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE
                && event.hasNoModifiers() && surfaceView.hasPointerCapture())
            surfaceView.releasePointerCapture();
    }

    private static final int CLASSIFICATION_TWO_FINGER_SWIPE = 3;
    private static final int CLASSIFICATION_MULTI_FINGER_SWIPE = 4;
    private static final int CLASSIFICATION_PINCH = 5;

    private int savedBS = 0;

    private static final int[][] BUTTON_MAP = {
        {MotionEvent.BUTTON_PRIMARY,   0x110}, // BTN_LEFT
        {MotionEvent.BUTTON_SECONDARY, 0x111}, // BTN_RIGHT
        {MotionEvent.BUTTON_TERTIARY,  0x112}, // BTN_MIDDLE
        {MotionEvent.BUTTON_BACK,      0x113}, // BTN_SIDE
        {MotionEvent.BUTTON_FORWARD,   0x114}, // BTN_EXTRA
    };

    private boolean isMouseEvent(MotionEvent event) {
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN)
            return false;
        if ((source & InputDevice.SOURCE_MOUSE) != InputDevice.SOURCE_MOUSE)
            return false;
        int toolType = event.getToolType(event.getActionIndex());
        return toolType == MotionEvent.TOOL_TYPE_MOUSE
            || toolType == MotionEvent.TOOL_TYPE_FINGER;
    }

    private boolean handleMouseEvent(MotionEvent event) {
        float dx = 0f;
        float dy = 0f;

        // Масштабирование
        float scaleX = (customScreenWidth > 0 && viewWidth > 0) ?
                   (float)customScreenWidth / viewWidth : 1.0f;
        float scaleY = (customScreenHeight > 0 && viewHeight > 0) ?
                   (float)customScreenHeight / viewHeight : 1.0f;

        if (event.getHistorySize() > 0) {
            int last = event.getHistorySize() - 1;
            dx = (event.getX() - event.getHistoricalX(0, last))*scaleX;
            dy = (event.getY() - event.getHistoricalY(0, last))*scaleY;
        }
        mPointerX = event.getX() * scaleX;
        mPointerY = event.getY() * scaleY;
        mPointerPositionKnown = true;
        Native.nativeSendMouseMotion(mPointerX, mPointerY, dx, dy);

        updateMouseButtons(event);
        return true;
    }

    private void reloadPointerCapturePreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        mPointerCaptureEnabled = prefs.getBoolean(KEY_POINTER_CAPTURE, false);
        mCapturedPointerTransform = prefs.getString(KEY_TRANSFORM_CAPTURED_POINTER, "no");
        if (mCapturedPointerTransform == null)
            mCapturedPointerTransform = "no";
        int speedPercent = prefs.getInt(KEY_CAPTURED_POINTER_SPEED_FACTOR, 100);
        mCapturedPointerSpeedFactor = Math.max(1, Math.min(300, speedPercent)) / 100f;
        mCapturedTouchpad.setScrollSpeed(prefs.getFloat(
            KEY_SCROLL_SPEED, Touchpad.DEFAULT_SCROLL_SPEED));
        mCapturedTouchpad.setScrollReversed(
            prefs.getBoolean(KEY_SCROLL_REVERSE, false));
        mCapturedTouchpad.setGestureThresholds(
            prefs.getFloat(KEY_SCROLL_THRESHOLD,
                Touchpad.DEFAULT_SCROLL_THRESHOLD_FACTOR),
            prefs.getFloat(KEY_MOVE_THRESHOLD,
                Touchpad.DEFAULT_MOVE_THRESHOLD_FACTOR));
        mCapturedTouchpad.setGestureScale(prefs.getFloat(
            KEY_GESTURE_SCALE, Touchpad.DEFAULT_GESTURE_SCALE));

        if (!mPointerCaptureEnabled && surfaceView.hasPointerCapture())
            surfaceView.releasePointerCapture();
    }

    private final class CapturedTouchpadOutput implements Touchpad.Output {
        @Override
        public void onMotion(float dx, float dy) {
            // Raw relative axes drive the cursor so motion is not applied twice.
        }

        @Override
        public void onScroll(int axis, float value) {
            Native.nativeSendMouseScroll(axis, value);
        }

        @Override
        public void onButton(int button, boolean pressed) {
            Native.nativeSendMouseButton(button, pressed);
        }

        @Override
        public void onTouch(int action, int pointerId, float x, float y) {
            Native.nativeSendTouch(action,
                x * capturedPointerScaleX(), y * capturedPointerScaleY(), pointerId);
        }

        @Override
        public void onTouchFrame() {
            Native.nativeSendTouchFrame();
        }

        @Override
        public float cursorX() {
            ensureCapturedPointerPosition();
            return mPointerX / capturedPointerScaleX();
        }

        @Override
        public float cursorY() {
            ensureCapturedPointerPosition();
            return mPointerY / capturedPointerScaleY();
        }
    }

    /** Handle relative mice and hardware touchpads delivered through pointer capture. */
    private boolean handleCapturedPointerEvent(MotionEvent event) {
        boolean isTouchpad = event.isFromSource(InputDevice.SOURCE_TOUCHPAD);
        boolean isRelativeMouse = !isTouchpad
            && event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE);
        if (!isTouchpad && !isRelativeMouse)
            return false;

        if (isTouchpad)
            return handleCapturedTouchpadEvent(event);

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE) {
            for (int i = 0; i < event.getHistorySize(); i++)
                processCapturedRelativeMouseSample(event, i);
            processCapturedRelativeMouseSample(event, -1);
        } else if (action == MotionEvent.ACTION_SCROLL) {
            for (int i = 0; i < event.getHistorySize(); i++)
                sendCapturedScrollAxes(event, i);
            sendCapturedScrollAxes(event, -1);
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            mLastTouchpadButtonPressed = 0;
            releaseAllMouseButtons();
        } else {
            updateTouchpadButtonStateFromEvent(event);
        }
        return true;
    }

    private void processCapturedRelativeMouseSample(MotionEvent event, int historyPos) {
        InputDevice device = event.getDevice();
        boolean hasRelativeX = device != null
            && device.getMotionRange(MotionEvent.AXIS_RELATIVE_X) != null;
        boolean hasRelativeY = device != null
            && device.getMotionRange(MotionEvent.AXIS_RELATIVE_Y) != null;
        float dx = hasRelativeX
            ? capturedAxis(event, MotionEvent.AXIS_RELATIVE_X, 0, historyPos)
            : capturedCoordinate(event, true, 0, historyPos);
        float dy = hasRelativeY
            ? capturedAxis(event, MotionEvent.AXIS_RELATIVE_Y, 0, historyPos)
            : capturedCoordinate(event, false, 0, historyPos);
        moveCapturedPointerBy(dx, dy, event.getSource());
    }

    private boolean handleCapturedTouchpadEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int pointerCount = event.getPointerCount();
        boolean hasButton = event.getButtonState() != 0
            || action == MotionEvent.ACTION_BUTTON_PRESS
            || action == MotionEvent.ACTION_BUTTON_RELEASE;
        boolean canceled = action == MotionEvent.ACTION_CANCEL
            || ((action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP)
                && (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0);
        boolean explicitScroll = (action == MotionEvent.ACTION_MOVE
            || action == MotionEvent.ACTION_HOVER_MOVE)
            && hasCapturedTouchpadScrollAxes(event);
        boolean scrollEvent = action == MotionEvent.ACTION_SCROLL || explicitScroll;
        boolean leftOrRightHeld = (effectiveButtonState(event)
            & (MotionEvent.BUTTON_PRIMARY | MotionEvent.BUTTON_SECONDARY)) != 0;

        if (!leftOrRightHeld) {
            mButtonDragLastX.clear();
            mButtonDragLastY.clear();
        }

        if (canceled) {
            mCapturedTouchpad.cancel();
            mCapturedTouchpadBaselineValid = false;
        } else if (scrollEvent) {
            mCapturedTouchpad.cancel();
            for (int i = 0; i < event.getHistorySize(); i++)
                sendCapturedScrollAxes(event, i);
            sendCapturedScrollAxes(event, -1);
            mCapturedTouchpadBaselineValid = false;
        } else if (hasButton) {
            mCapturedTouchpad.cancel();
        } else {
            updateCapturedTouchpadBounds(event);
            mCapturedTouchpad.onTouch(event);
        }

        if (!canceled && !scrollEvent && !mCapturedTouchpad.isForwardingTouch()) {
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    setCapturedTouchpadBaseline(event, -1);
                    mButtonDragLastX.clear();
                    mButtonDragLastY.clear();
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_POINTER_UP:
                    mCapturedTouchpadBaselineValid = false;
                    mButtonDragLastX.clear();
                    mButtonDragLastY.clear();
                    break;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_HOVER_MOVE:
                    if (pointerCount == 1) {
                        for (int i = 0; i < event.getHistorySize(); i++)
                            processCapturedTouchpadMotionSample(event, i);
                        processCapturedTouchpadMotionSample(event, -1);
                    } else if (leftOrRightHeld) {
                        processCapturedTouchpadButtonDrag(event);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    mCapturedTouchpadBaselineValid = false;
                    mButtonDragLastX.clear();
                    mButtonDragLastY.clear();
                    break;
                default:
                    break;
            }
        }

        if (action == MotionEvent.ACTION_CANCEL)
            releaseAllMouseButtons();
        else
            updateMouseButtonStateFromEvent(event);
        return true;
    }

    private void processCapturedTouchpadMotionSample(MotionEvent event, int historyPos) {
        if (event.getPointerCount() != 1)
            return;
        float dx = capturedAxis(event, MotionEvent.AXIS_RELATIVE_X, 0, historyPos);
        float dy = capturedAxis(event, MotionEvent.AXIS_RELATIVE_Y, 0, historyPos);
        float[] resolved = applyCapturedTouchpadAbsoluteFallback(
            event, historyPos, dx, dy);
        moveCapturedPointerBy(resolved[0], resolved[1], event.getSource());
    }

    private void processCapturedTouchpadButtonDrag(MotionEvent event) {
        int pointerCount = event.getPointerCount();
        if (pointerCount < 2)
            return;
        float scaleX = capturedTouchpadCoordinateScale(
            event, MotionEvent.AXIS_X, true);
        float scaleY = capturedTouchpadCoordinateScale(
            event, MotionEvent.AXIS_Y, false);

        for (int i = mButtonDragLastX.size() - 1; i >= 0; i--) {
            int id = mButtonDragLastX.keyAt(i);
            if (event.findPointerIndex(id) < 0) {
                mButtonDragLastX.removeAt(i);
                mButtonDragLastY.delete(id);
            }
        }

        float bestDx = 0f;
        float bestDy = 0f;
        float bestMagnitude = -1f;
        for (int i = 0; i < pointerCount; i++) {
            int id = event.getPointerId(i);
            float lastX = mButtonDragLastX.get(id, Float.NaN);
            float lastY = mButtonDragLastY.get(id, Float.NaN);
            if (Float.isNaN(lastX) || Float.isNaN(lastY))
                continue;
            float dx = (event.getX(i) - lastX) * scaleX;
            float dy = (event.getY(i) - lastY) * scaleY;
            float magnitude = dx * dx + dy * dy;
            if (magnitude > bestMagnitude) {
                bestMagnitude = magnitude;
                bestDx = dx;
                bestDy = dy;
            }
        }

        for (int i = 0; i < pointerCount; i++) {
            int id = event.getPointerId(i);
            mButtonDragLastX.put(id, event.getX(i));
            mButtonDragLastY.put(id, event.getY(i));
        }

        if (bestMagnitude > 0f)
            moveCapturedPointerBy(bestDx, bestDy, event.getSource());
    }

    private void updateCapturedTouchpadBounds(MotionEvent event) {
        int width = capturedPointerViewWidth();
        int height = capturedPointerViewHeight();
        mCapturedTouchpad.setOutputSize(width, height);
        if (event.getDeviceId() == mCapturedTouchpadDeviceId)
            return;
        InputDevice.MotionRange xRange = capturedPadRange(event, MotionEvent.AXIS_X);
        InputDevice.MotionRange yRange = capturedPadRange(event, MotionEvent.AXIS_Y);
        if (xRange == null || yRange == null
                || xRange.getRange() <= 0f || yRange.getRange() <= 0f)
            return;
        mCapturedTouchpadDeviceId = event.getDeviceId();
        mCapturedTouchpad.setInputBounds(xRange.getMin(), yRange.getMin(),
            xRange.getRange(), yRange.getRange());
    }

    private InputDevice.MotionRange capturedPadRange(MotionEvent event, int axis) {
        InputDevice device = event.getDevice();
        if (device == null)
            return null;
        InputDevice.MotionRange range =
            device.getMotionRange(axis, InputDevice.SOURCE_TOUCHPAD);
        return range != null ? range : device.getMotionRange(axis);
    }

    private float[] applyCapturedTouchpadAbsoluteFallback(
            MotionEvent event, int historyPos, float dx, float dy) {
        int pointerCount = event.getPointerCount();
        float centroidX = capturedTouchpadCentroid(event, historyPos, true);
        float centroidY = capturedTouchpadCentroid(event, historyPos, false);
        if (dx == 0f && dy == 0f
                && mCapturedTouchpadBaselineValid
                && mCapturedTouchpadBaselinePointers == pointerCount) {
            dx = (centroidX - mCapturedTouchpadLastCentroidX)
                * capturedTouchpadCoordinateScale(event, MotionEvent.AXIS_X, true);
            dy = (centroidY - mCapturedTouchpadLastCentroidY)
                * capturedTouchpadCoordinateScale(event, MotionEvent.AXIS_Y, false);
        }
        mCapturedTouchpadLastCentroidX = centroidX;
        mCapturedTouchpadLastCentroidY = centroidY;
        mCapturedTouchpadBaselinePointers = pointerCount;
        mCapturedTouchpadBaselineValid = true;
        mCapturedTouchpadResolvedDelta[0] = dx;
        mCapturedTouchpadResolvedDelta[1] = dy;
        return mCapturedTouchpadResolvedDelta;
    }

    private float capturedTouchpadCoordinateScale(
            MotionEvent event, int axis, boolean xAxis) {
        InputDevice.MotionRange range = capturedPadRange(event, axis);
        float span = range == null ? 0f : range.getRange();
        int size = xAxis ? capturedPointerViewWidth() : capturedPointerViewHeight();
        return span > 0f && size > 0 ? size / span : 0f;
    }

    private void sendCapturedScrollAxes(MotionEvent event, int historyPos) {
        if (event.getPointerCount() <= 0)
            return;
        float vScroll = capturedAxis(
            event, MotionEvent.AXIS_VSCROLL, 0, historyPos);
        float hScroll = capturedAxis(
            event, MotionEvent.AXIS_HSCROLL, 0, historyPos);
        if (vScroll != 0f || hScroll != 0f) {
            if (vScroll != 0f)
                Native.nativeSendMouseScroll(0, -vScroll * 10f);
            if (hScroll != 0f)
                Native.nativeSendMouseScroll(1, hScroll * 10f);
            return;
        }

        float gestureX = capturedAxis(event,
            MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE, 0, historyPos);
        float gestureY = capturedAxis(event,
            MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE, 0, historyPos);
        if (gestureY != 0f)
            Native.nativeSendMouseScroll(0, gestureY);
        if (gestureX != 0f)
            Native.nativeSendMouseScroll(1, -gestureX);
    }

    private boolean hasCapturedTouchpadScrollAxes(MotionEvent event) {
        if (event.getPointerCount() <= 0)
            return false;
        for (int i = 0; i < event.getHistorySize(); i++) {
            if (hasCapturedScrollAxesAt(event, i))
                return true;
        }
        return hasCapturedScrollAxesAt(event, -1);
    }

    private boolean hasCapturedScrollAxesAt(MotionEvent event, int historyPos) {
        return capturedAxis(event, MotionEvent.AXIS_VSCROLL, 0, historyPos) != 0f
            || capturedAxis(event, MotionEvent.AXIS_HSCROLL, 0, historyPos) != 0f
            || capturedAxis(event, MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE,
                0, historyPos) != 0f
            || capturedAxis(event, MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE,
                0, historyPos) != 0f;
    }

    private float capturedAxis(
            MotionEvent event, int axis, int pointerIndex, int historyPos) {
        return historyPos >= 0
            ? event.getHistoricalAxisValue(axis, pointerIndex, historyPos)
            : event.getAxisValue(axis, pointerIndex);
    }

    private float capturedCoordinate(
            MotionEvent event, boolean xAxis, int pointerIndex, int historyPos) {
        if (historyPos >= 0) {
            return xAxis
                ? event.getHistoricalX(pointerIndex, historyPos)
                : event.getHistoricalY(pointerIndex, historyPos);
        }
        return xAxis ? event.getX(pointerIndex) : event.getY(pointerIndex);
    }

    private float capturedTouchpadCentroid(
            MotionEvent event, int historyPos, boolean xAxis) {
        int pointerCount = event.getPointerCount();
        if (pointerCount <= 0)
            return 0f;
        float total = 0f;
        for (int i = 0; i < pointerCount; i++)
            total += capturedCoordinate(event, xAxis, i, historyPos);
        return total / pointerCount;
    }

    private void setCapturedTouchpadBaseline(MotionEvent event, int historyPos) {
        mCapturedTouchpadLastCentroidX =
            capturedTouchpadCentroid(event, historyPos, true);
        mCapturedTouchpadLastCentroidY =
            capturedTouchpadCentroid(event, historyPos, false);
        mCapturedTouchpadBaselinePointers = event.getPointerCount();
        mCapturedTouchpadBaselineValid = mCapturedTouchpadBaselinePointers > 0;
    }

    private void resetCapturedTouchpadGesture() {
        if (mCapturedTouchpad != null)
            mCapturedTouchpad.cancel();
        mCapturedTouchpadDeviceId = -1;
        mCapturedTouchpadBaselineValid = false;
        mCapturedTouchpadBaselinePointers = 0;
        mCapturedTouchpadLastCentroidX = 0f;
        mCapturedTouchpadLastCentroidY = 0f;
        mButtonDragLastX.clear();
        mButtonDragLastY.clear();
        mLastTouchpadButtonPressed = 0;
    }

    private int capturedPointerViewWidth() {
        return viewWidth > 0 ? viewWidth : surfaceView.getWidth();
    }

    private int capturedPointerViewHeight() {
        return viewHeight > 0 ? viewHeight : surfaceView.getHeight();
    }

    private float capturedPointerScaleX() {
        int width = capturedPointerViewWidth();
        return customScreenWidth > 0 && width > 0
            ? (float) customScreenWidth / width : 1f;
    }

    private float capturedPointerScaleY() {
        int height = capturedPointerViewHeight();
        return customScreenHeight > 0 && height > 0
            ? (float) customScreenHeight / height : 1f;
    }

    private void ensureCapturedPointerPosition() {
        int outputWidth = customScreenWidth > 0
            ? customScreenWidth : capturedPointerViewWidth();
        int outputHeight = customScreenHeight > 0
            ? customScreenHeight : capturedPointerViewHeight();
        if (!mPointerPositionKnown) {
            mPointerX = Math.max(0, outputWidth) / 2f;
            mPointerY = Math.max(0, outputHeight) / 2f;
            mPointerPositionKnown = true;
        }
        mPointerX = clamp(mPointerX, 0f, Math.max(0, outputWidth));
        mPointerY = clamp(mPointerY, 0f, Math.max(0, outputHeight));
    }

    private void moveCapturedPointerBy(float dx, float dy, int source) {
        if (!Float.isFinite(dx) || !Float.isFinite(dy)
                || (dx == 0f && dy == 0f))
            return;
        transformCapturedPointerDelta(dx, dy, source);
        dx = mTransformedPointerDelta[0]
            * mCapturedPointerSpeedFactor * mDensity * capturedPointerScaleX();
        dy = mTransformedPointerDelta[1]
            * mCapturedPointerSpeedFactor * mDensity * capturedPointerScaleY();

        ensureCapturedPointerPosition();
        int outputWidth = customScreenWidth > 0
            ? customScreenWidth : capturedPointerViewWidth();
        int outputHeight = customScreenHeight > 0
            ? customScreenHeight : capturedPointerViewHeight();
        mPointerX = clamp(mPointerX + dx, 0f, Math.max(0, outputWidth));
        mPointerY = clamp(mPointerY + dy, 0f, Math.max(0, outputHeight));
        Native.nativeSendMouseMotion(mPointerX, mPointerY, dx, dy);
    }

    private void transformCapturedPointerDelta(float x, float y, int source) {
        String transform = mCapturedPointerTransform;
        if ("at".equals(transform)) {
            if ((source & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) {
                Display display = getDisplay();
                int rotation = display != null ? display.getRotation() : Surface.ROTATION_0;
                if (rotation == Surface.ROTATION_90)
                    transform = "cc";
                else if (rotation == Surface.ROTATION_180)
                    transform = "ud";
                else if (rotation == Surface.ROTATION_270)
                    transform = "c";
                else
                    transform = "no";
            } else {
                transform = "no";
            }
        }

        float temp;
        switch (transform) {
            case "c":
                temp = x;
                x = -y;
                y = temp;
                break;
            case "cc":
                temp = x;
                x = y;
                y = -temp;
                break;
            case "ud":
                x = -x;
                y = -y;
                break;
            default:
                break;
        }
        mTransformedPointerDelta[0] = x;
        mTransformedPointerDelta[1] = y;
    }

    private void updateMouseButtonState(int currentBS) {
        for (int[] btn : BUTTON_MAP) {
            boolean wasDown = (savedBS & btn[0]) != 0;
            boolean isDown  = (currentBS & btn[0]) != 0;
            if (wasDown != isDown)
                Native.nativeSendMouseButton(btn[1], isDown);
        }
        savedBS = currentBS;
    }

    private static int effectiveButtonState(MotionEvent event) {
        int buttonState = event.getButtonState();
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_BUTTON_PRESS)
            buttonState |= event.getActionButton();
        else if (action == MotionEvent.ACTION_BUTTON_RELEASE)
            buttonState &= ~event.getActionButton();
        return buttonState;
    }

    private void updateMouseButtonStateFromEvent(MotionEvent event) {
        updateMouseButtonState(effectiveButtonState(event));
    }

    /** Resolve clickpad primary presses to left/right from the slowest contact. */
    private void updateTouchpadButtonStateFromEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int buttonState = event.getButtonState();
        if (action == MotionEvent.ACTION_BUTTON_PRESS) {
            int button = mCapturedTouchpad != null
                    ? mCapturedTouchpad.clickpadButton(event) : 0x110;
            mLastTouchpadButtonPressed = button == 0x111
                    ? MotionEvent.BUTTON_SECONDARY : MotionEvent.BUTTON_PRIMARY;
            buttonState &= ~(MotionEvent.BUTTON_PRIMARY | MotionEvent.BUTTON_SECONDARY);
            buttonState |= mLastTouchpadButtonPressed;
        } else if (action == MotionEvent.ACTION_BUTTON_RELEASE) {
            // Release the button chosen at press time even if the finger drifted.
            buttonState &= ~(MotionEvent.BUTTON_PRIMARY | MotionEvent.BUTTON_SECONDARY);
            mLastTouchpadButtonPressed = 0;
        } else if (mLastTouchpadButtonPressed != 0) {
            buttonState &= ~(MotionEvent.BUTTON_PRIMARY | MotionEvent.BUTTON_SECONDARY);
            buttonState |= mLastTouchpadButtonPressed;
        } else {
            buttonState = effectiveButtonState(event);
        }
        updateMouseButtonState(buttonState);
    }

    private void updateMouseButtons(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL)
            releaseAllMouseButtons();
        else
            updateMouseButtonStateFromEvent(event);
    }

    private void releaseAllMouseButtons() {
        if (savedBS == 0)
            return;
        for (int[] btn : BUTTON_MAP) {
            if ((savedBS & btn[0]) != 0)
                Native.nativeSendMouseButton(btn[1], false);
        }
        savedBS = 0;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean handleTouchpadScroll(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float scrollX = event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE);
            float scrollY = event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE);
            if (scrollY != 0)
                Native.nativeSendMouseScroll(0, scrollY);
            if (scrollX != 0)
                Native.nativeSendMouseScroll(1, -scrollX);
        }
        return true;
    }

    // 原有 handleTouchEvent 一字未改
    private boolean handleTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int pointerIdx = event.getActionIndex();
        int pointerId = event.getPointerId(pointerIdx);

        // Масштабирование
        float scaleX = (customScreenWidth > 0 && viewWidth > 0) ?
                       (float)customScreenWidth / viewWidth : 1.0f;
        float scaleY = (customScreenHeight > 0 && viewHeight > 0) ?
                       (float)customScreenHeight / viewHeight : 1.0f;

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                Native.nativeSendTouch(0,
                    event.getX(pointerIdx) * scaleX,
                    event.getY(pointerIdx) * scaleY,
                    pointerId);
                Native.nativeSendTouchFrame();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                Native.nativeSendTouch(1,
                    event.getX(pointerIdx) * scaleX,
                    event.getY(pointerIdx) * scaleY,
                    pointerId);
                Native.nativeSendTouchFrame();
                return true;

            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    Native.nativeSendTouch(2,
                        event.getX(i) * scaleX,
                        event.getY(i) * scaleY,
                        event.getPointerId(i));
                }
                Native.nativeSendTouchFrame();
                return true;

            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    Native.nativeSendTouch(1,
                        event.getX(i) * scaleX,
                        event.getY(i) * scaleY,
                        event.getPointerId(i));
                }
                Native.nativeSendTouchFrame();
                return true;
        }
        return false;
    }

}
