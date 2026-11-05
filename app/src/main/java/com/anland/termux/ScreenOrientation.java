package com.anland.termux;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;

/** Applies the display orientation preference to desktop and editor windows. */
final class ScreenOrientation {
    static final String KEY = "screen_orientation";

    private ScreenOrientation() {}

    static void apply(Activity activity, SharedPreferences prefs) {
        String orientation = prefs.getString(KEY, "auto");
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
        if (activity.getRequestedOrientation() != requestedOrientation)
            activity.setRequestedOrientation(requestedOrientation);
    }
}
