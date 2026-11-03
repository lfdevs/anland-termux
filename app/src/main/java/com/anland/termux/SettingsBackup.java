package com.anland.termux;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Versioned, typed backups of all app preferences, including unlisted/legacy keys. */
final class SettingsBackup {
    private static final String FORMAT = "AnlandSettingsBackup";
    private static final int VERSION = 1;
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final Map<String, String> KNOWN_TYPES = knownTypes();

    private SettingsBackup() {}

    static byte[] exportSettings(SharedPreferences prefs) throws JSONException, IOException {
        JSONObject entries = new JSONObject();
        for (Map.Entry<String, ?> entry : new TreeMap<>(prefs.getAll()).entrySet()) {
            Object value = entry.getValue();
            String type = typeOf(value);
            Object encoded = value;
            if (value instanceof Number) {
                // Strings retain exact long/float values across JSON implementations.
                encoded = value.toString();
            } else if (value instanceof Set<?>) {
                JSONArray values = new JSONArray();
                for (Object item : (Set<?>) value) {
                    if (!(item instanceof String)) throw new JSONException("Invalid string set");
                    values.put(item);
                }
                encoded = values;
            }
            entries.put(entry.getKey(), new JSONObject().put("type", type).put("value", encoded));
        }
        byte[] bytes = new JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("settings", entries)
            .toString(2).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Backup exceeds size limit");
        return bytes;
    }

    static Map<String, Object> readSettings(InputStream input) throws IOException, JSONException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int count;
        while ((count = input.read(chunk)) != -1) {
            if (buffer.size() + count > MAX_BYTES) throw new IOException("Backup exceeds size limit");
            buffer.write(chunk, 0, count);
        }
        JSONTokener tokener = new JSONTokener(buffer.toString(StandardCharsets.UTF_8.name()));
        Object parsed = tokener.nextValue();
        if (!(parsed instanceof JSONObject) || tokener.nextClean() != 0)
            throw new JSONException("Invalid backup document");
        JSONObject root = (JSONObject) parsed;
        if (!FORMAT.equals(root.get("format")) || !Integer.valueOf(VERSION).equals(root.get("version")))
            throw new JSONException("Unsupported backup format or version");
        JSONObject entries = root.getJSONObject("settings");
        Map<String, Object> settings = new LinkedHashMap<>();
        java.util.Iterator<String> keys = entries.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject entry = entries.getJSONObject(key);
            Object type = entry.get("type");
            Object raw = entry.get("value");
            if (!(type instanceof String)) throw new JSONException("Invalid preference type");
            Object value;
            try {
                switch ((String) type) {
                    case "boolean":
                        if (!(raw instanceof Boolean)) throw new JSONException("Invalid boolean");
                        value = raw;
                        break;
                    case "int": value = Integer.parseInt(requireString(raw)); break;
                    case "long": value = Long.parseLong(requireString(raw)); break;
                    case "float":
                        float number = Float.parseFloat(requireString(raw));
                        if (!Float.isFinite(number)) throw new JSONException("Invalid float");
                        value = number;
                        break;
                    case "string": value = requireString(raw); break;
                    case "string_set":
                        if (!(raw instanceof JSONArray)) throw new JSONException("Invalid string set");
                        Set<String> values = new LinkedHashSet<>();
                        JSONArray array = (JSONArray) raw;
                        for (int i = 0; i < array.length(); i++) values.add(requireString(array.get(i)));
                        value = values;
                        break;
                    default: throw new JSONException("Unsupported preference type");
                }
            } catch (NumberFormatException e) {
                throw new JSONException("Invalid numeric preference");
            }
            String expected = KNOWN_TYPES.get(key);
            // Older app versions stored cutout handling as a boolean.
            boolean legacyCutout = DisplayCutoutMode.KEY.equals(key) && value instanceof Boolean;
            if (expected != null && !expected.equals(type) && !legacyCutout)
                throw new JSONException("Incorrect type for " + key);
            settings.put(key, value);
        }
        return settings;
    }

    static void replaceSettings(SharedPreferences prefs, Map<String, ?> settings) throws IOException {
        // Construct the entire editor before clear/commit so parsing failures never
        // overwrite current settings. Absent keys revert to the app's defaults.
        Map<String, ?> previous = prefs.getAll();
        if (!editorFor(prefs, settings).commit()) {
            editorFor(prefs, previous).commit();
            throw new IOException("Could not save settings");
        }
    }

    private static SharedPreferences.Editor editorFor(SharedPreferences prefs, Map<String, ?> settings) {
        SharedPreferences.Editor editor = prefs.edit().clear();
        for (Map.Entry<String, ?> entry : settings.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Set<?>) {
                Set<String> strings = new LinkedHashSet<>();
                for (Object item : (Set<?>) value) strings.add((String) item);
                editor.putStringSet(key, strings);
            } else throw new IllegalArgumentException("Unsupported preference value");
        }
        return editor;
    }

    private static String requireString(Object value) throws JSONException {
        if (!(value instanceof String)) throw new JSONException("Expected string");
        return (String) value;
    }

    private static String typeOf(Object value) throws JSONException {
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Integer) return "int";
        if (value instanceof Long) return "long";
        if (value instanceof Float) return "float";
        if (value instanceof String) return "string";
        if (value instanceof Set<?>) return "string_set";
        throw new JSONException("Unsupported preference value");
    }

    private static Map<String, String> knownTypes() {
        Map<String, String> types = new HashMap<>();
        for (String key : new String[]{"bound_keycode", "custom_width", "custom_height",
                "speaker_latency_ms", "mic_latency_ms", "captured_pointer_speed_factor",
                "hud_layout_slot"})
            types.put(key, "int");
        for (String key : new String[]{"mouse_speed", "scroll_speed", "touchpad_scroll_threshold",
                "touchpad_move_threshold", "touchpad_gesture_scale"}) types.put(key, "float");
        for (String key : new String[]{"socket_path", "extra_keys_layout", "screen_orientation",
                "hud_layout_profile_v2",
                "transform_captured_pointer", DisplayCutoutMode.KEY, UserActions.VOLUME_UP,
                UserActions.VOLUME_DOWN, UserActions.BACK_BUTTON, UserActions.NOTIFICATION_TAP,
                UserActions.NOTIFICATION_FIRST_BUTTON, UserActions.NOTIFICATION_SECOND_BUTTON,
                UserActions.MEDIA_KEYS}) types.put(key, "string");
        for (String key : new String[]{"use_root", "mic_enabled", "camera_enabled", "audio_keepalive",
                "accessibility_key_intercept", "extra_keys_bar", "auto_show_extra_keys",
                "keyboard_floating", "settings_notification", "pip_mode", "touchpad_mode",
                "pointer_capture", "scroll_reverse", "back_opens_extra_keys",
                "use_hud_overlay", "hud_hidden_mode"}) types.put(key, "boolean");
        return types;
    }
}
