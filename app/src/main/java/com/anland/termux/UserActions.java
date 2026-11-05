package com.anland.termux;

import android.content.SharedPreferences;

final class UserActions {
    static final String VOLUME_UP = "volume_up_action";
    static final String VOLUME_DOWN = "volume_down_action";
    static final String BACK_BUTTON = "back_button_action";
    static final String NOTIFICATION_TAP = "notification_tap_action";
    static final String NOTIFICATION_FIRST_BUTTON = "notification_first_button_action";
    static final String NOTIFICATION_SECOND_BUTTON = "notification_second_button_action";
    static final String MEDIA_KEYS = "media_keys_action";

    static final String NO_ACTION = "no action";
    static final String TOGGLE_SOFT_KEYBOARD = "toggle soft keyboard";
    static final String TOGGLE_ADDITIONAL_KEY_BAR = "toggle additional key bar";
    static final String TOGGLE_FLOATING_HUD = "toggle floating HUD";
    static final String OPEN_PREFERENCES = "open preferences";
    static final String RELEASE_POINTER_AND_KEYBOARD_CAPTURE =
        "release pointer and keyboard capture";
    static final String RESTART_ACTIVITY = "restart activity";
    static final String EXIT = "exit";
    static final String TOGGLE_TOUCHPAD_MODE = "toggle touchpad mode";
    static final String TOGGLE_SCREEN_ORIENTATION = "toggle screen orientation";
    static final String SEND_VOLUME_UP = "send volume up";
    static final String SEND_VOLUME_DOWN = "send volume down";
    static final String SEND_MEDIA_ACTION = "send media action";

    private static final String LEGACY_BACK_OPENS_EXTRA_KEYS = "back_opens_extra_keys";

    private static final String[] BASE_RESPONSES = {
        NO_ACTION,
        TOGGLE_SOFT_KEYBOARD,
        TOGGLE_ADDITIONAL_KEY_BAR,
        TOGGLE_FLOATING_HUD,
        OPEN_PREFERENCES,
        RELEASE_POINTER_AND_KEYBOARD_CAPTURE,
        RESTART_ACTIVITY,
        EXIT,
        TOGGLE_TOUCHPAD_MODE,
        TOGGLE_SCREEN_ORIENTATION
    };

    private UserActions() {}

    static String getResponse(SharedPreferences prefs, String action) {
        if (prefs.contains(action)) {
            String response = prefs.getString(action, defaultResponse(action));
            return response != null ? response : defaultResponse(action);
        }

        // Preserve the old Back-key setting until the user chooses a new response.
        if (BACK_BUTTON.equals(action)) {
            return prefs.getBoolean(LEGACY_BACK_OPENS_EXTRA_KEYS, true)
                ? TOGGLE_ADDITIONAL_KEY_BAR : NO_ACTION;
        }
        return defaultResponse(action);
    }

    static String defaultResponse(String action) {
        if (NOTIFICATION_TAP.equals(action)
                || NOTIFICATION_FIRST_BUTTON.equals(action))
            return OPEN_PREFERENCES;
        if (NOTIFICATION_SECOND_BUTTON.equals(action))
            return EXIT;
        if (BACK_BUTTON.equals(action))
            return TOGGLE_ADDITIONAL_KEY_BAR;
        return NO_ACTION;
    }

    static String[] responsesFor(String action) {
        String extra = null;
        if (VOLUME_UP.equals(action))
            extra = SEND_VOLUME_UP;
        else if (VOLUME_DOWN.equals(action))
            extra = SEND_VOLUME_DOWN;
        else if (MEDIA_KEYS.equals(action))
            extra = SEND_MEDIA_ACTION;

        if (extra == null)
            return BASE_RESPONSES.clone();

        String[] responses = new String[BASE_RESPONSES.length + 1];
        System.arraycopy(BASE_RESPONSES, 0, responses, 0, BASE_RESPONSES.length);
        responses[BASE_RESPONSES.length] = extra;
        return responses;
    }
}
