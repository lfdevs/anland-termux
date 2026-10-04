@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.activity.ExperimentalActivityApi::class
)

package com.anland.termux

import android.content.SharedPreferences
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.WindowCompat
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/** Installs the settings UI without changing the Java-facing Activity contract. */
object ComposeSettings {
    @JvmStatic
    fun install(activity: SettingsActivity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.setContent {
            SettingsApp(activity)
        }
    }
}

private enum class SettingsPage(val title: Int, val subtitle: Int) {
    HOME(R.string.settings_title, R.string.settings_title),
    KEYBOARD(R.string.cat_keyboard_title, R.string.cat_keyboard_subtitle),
    TOUCHPAD(R.string.cat_touchpad_title, R.string.cat_touchpad_subtitle),
    CONNECTION(R.string.section_connection, R.string.cat_connection_subtitle),
    DISPLAY(R.string.section_display, R.string.cat_display_subtitle),
    GENERAL(R.string.cat_general_title, R.string.cat_general_subtitle)
}

private val navigationPages = listOf(
    SettingsPage.KEYBOARD,
    SettingsPage.TOUCHPAD,
    SettingsPage.CONNECTION,
    SettingsPage.DISPLAY,
    SettingsPage.GENERAL
)

private const val PREFS_NAME = "anland_settings"
private const val KEY_BOUND_KEYCODE = "bound_keycode"
private const val KEY_SOCKET_PATH = "socket_path"
private const val KEY_USE_ROOT = "use_root"
private const val KEY_MIC_ENABLED = "mic_enabled"
private const val KEY_CAMERA_ENABLED = "camera_enabled"
private const val KEY_AUDIO_KEEPALIVE = "audio_keepalive"
private const val KEY_SPEAKER_LATENCY_MS = "speaker_latency_ms"
private const val KEY_MIC_LATENCY_MS = "mic_latency_ms"
private const val KEY_ACCESSIBILITY_ENABLED = "accessibility_key_intercept"
private const val KEY_EXTRA_KEYS_ENABLED = "extra_keys_bar"
private const val KEY_AUTO_SHOW_EXTRA_KEYS = "auto_show_extra_keys"
private const val KEY_EXTRA_KEYS_LAYOUT = "extra_keys_layout"
private const val KEY_KEYBOARD_FLOATING = "keyboard_floating"
private const val KEY_NOTIFICATION_ENABLED = "settings_notification"
private const val KEY_SCREEN_ORIENTATION = "screen_orientation"
private const val KEY_PIP_MODE = "pip_mode"
private const val KEY_TOUCHPAD_MODE = "touchpad_mode"
private const val KEY_MOUSE_ACCEL = "mouse_speed"
private const val KEY_POINTER_CAPTURE = "pointer_capture"
private const val KEY_TRANSFORM_CAPTURED_POINTER = "transform_captured_pointer"
private const val KEY_CAPTURED_POINTER_SPEED_FACTOR = "captured_pointer_speed_factor"
private const val KEY_SCROLL_SPEED = "scroll_speed"
private const val KEY_SCROLL_REVERSE = "scroll_reverse"
private const val KEY_SCROLL_THRESHOLD = "touchpad_scroll_threshold"
private const val KEY_MOVE_THRESHOLD = "touchpad_move_threshold"
private const val KEY_GESTURE_SCALE = "touchpad_gesture_scale"
private const val DEFAULT_SOCKET_PATH = "/data/data/com.termux/files/usr/tmp/anland/display_daemon.sock"
private val screenOrientations = listOf("auto", "portrait", "landscape", "reverse portrait", "reverse landscape")
private val pointerTransforms = listOf("no", "c", "cc", "ud", "at")
private val latencyValues = listOf(0, 1, 3, 5, 10, 20)

@Composable
private fun SettingsApp(activity: SettingsActivity) {
    val prefs = remember { activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var page by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    var detailPage by rememberSaveable { mutableStateOf(SettingsPage.KEYBOARD) }
    val configuration = LocalConfiguration.current
    val wide = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val openPage: (SettingsPage) -> Unit = { nextPage ->
        detailPage = nextPage
        page = nextPage
    }

    val onBack: () -> Unit = {
        if (activity.isKeyBindingListening()) {
            // Keep the legacy binding behavior: Back is a bindable key while
            // the five-second capture window is active.
            activity.onKeyDown(
                KeyEvent.KEYCODE_BACK,
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
            )
        } else {
            page = SettingsPage.HOME
        }
    }

    // PredictiveBackHandler keeps the settings stack inside the system Back
    // dispatcher, so Android 14/15 can begin the gesture animation before the
    // destination changes. The page changes only after the gesture completes.
    PredictiveBackHandler(enabled = page != SettingsPage.HOME) { progress ->
        try {
            progress.collect { }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        onBack()
    }

    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(activity)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(activity)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(colorScheme = colorScheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (wide) {
                LandscapeSettingsContent(
                    activity = activity,
                    prefs = prefs,
                    page = page,
                    detailPage = detailPage,
                    onPageSelected = openPage,
                    onBack = onBack
                )
            } else {
                AnimatedContent(
                    targetState = page,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        if (targetState == SettingsPage.HOME) {
                            (fadeIn() + slideInHorizontally { -it / 4 }) togetherWith
                                (fadeOut() + slideOutHorizontally { it / 4 })
                        } else {
                            (fadeIn() + slideInHorizontally { it / 4 }) togetherWith
                                (fadeOut() + slideOutHorizontally { -it / 4 })
                        }
                    },
                    label = "settings-page-transition"
                ) { targetPage ->
                    if (targetPage == SettingsPage.HOME) {
                        HomeContent(onPageSelected = openPage)
                    } else {
                        SettingsDetail(activity, prefs, targetPage, onBack = onBack)
                    }
                }
            }
        }
    }
}

@Composable
private fun LandscapeSettingsContent(
    activity: SettingsActivity,
    prefs: SharedPreferences,
    page: SettingsPage,
    detailPage: SettingsPage,
    onPageSelected: (SettingsPage) -> Unit,
    onBack: () -> Unit
) {
    val leftFraction by animateFloatAsState(
        targetValue = if (page == SettingsPage.HOME) 1f else 0.3f,
        label = "settings-pane-width"
    )
    val showDetailPane = page != SettingsPage.HOME || leftFraction < 0.999f

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val splitLeftWidth = maxWidth * 0.3f
        val detailLeft = maxWidth * leftFraction
        val homeWidth = if (page == SettingsPage.HOME) maxWidth else splitLeftWidth
        // Both panes keep fixed layout widths throughout the transition. Only the
        // detail pane's position changes, so its content is never remeasured at
        // intermediate responsive widths.
        val detailWidth = maxWidth - splitLeftWidth - 1.dp

        HomeContent(
            modifier = Modifier.width(homeWidth).fillMaxHeight(),
            onPageSelected = onPageSelected
        )
        if (showDetailPane) {
            // Fill the moving gap with the app background while the fixed-width
            // detail pane is sliding into or out of its final position.
            Box(
                Modifier
                    .offset(x = detailLeft)
                    .width((maxWidth - detailLeft).coerceAtLeast(0.dp))
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.background)
            )
            Box(
                Modifier
                    .offset(x = detailLeft)
                    .width(1.dp)
                    .fillMaxHeight()
            ) {
                VerticalDivider()
            }
            Box(
                Modifier
                    .offset(x = detailLeft + 1.dp)
                    .width(detailWidth)
                    .fillMaxHeight()
            ) {
                AnimatedContent(
                    targetState = detailPage,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        (fadeIn() + slideInHorizontally { it / 4 }) togetherWith
                            (fadeOut() + slideOutHorizontally { -it / 4 })
                    },
                    label = "settings-detail-transition"
                ) { targetPage ->
                    SettingsDetail(activity, prefs, targetPage, onBack = onBack)
                }
            }
        }
    }
}

@Composable
private fun CategoryList(
    modifier: Modifier,
    contentPadding: PaddingValues,
    onPageSelected: (SettingsPage) -> Unit
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(navigationPages) { page ->
            CategoryItem(
                page = page,
                onClick = { onPageSelected(page) }
            )
        }
        item {
            Text(
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun CategoryItem(page: SettingsPage, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(stringResource(page.title)) },
            supportingContent = { Text(stringResource(page.subtitle)) }
        )
    }
}

@Composable
private fun HomeContent(
    modifier: Modifier = Modifier,
    onPageSelected: (SettingsPage) -> Unit
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }
    ) { padding ->
        CategoryList(
            modifier = Modifier.fillMaxSize().padding(padding).widthIn(max = 760.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            onPageSelected = onPageSelected
        )
    }
}

@Composable
private fun SettingsDetail(
    activity: SettingsActivity,
    prefs: SharedPreferences,
    page: SettingsPage,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(page.title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ChevronLeft,
                            contentDescription = stringResource(R.string.nav_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (page) {
                SettingsPage.KEYBOARD -> keyboardPage(activity, prefs)
                SettingsPage.TOUCHPAD -> touchpadPage(prefs)
                SettingsPage.CONNECTION -> connectionPage(activity, prefs)
                SettingsPage.DISPLAY -> displayPage(activity, prefs)
                SettingsPage.GENERAL -> generalPage(prefs)
                SettingsPage.HOME -> Unit
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.keyboardPage(
    activity: SettingsActivity, prefs: SharedPreferences
) {
    item { KeyBindingSetting(activity, prefs) }
    item {
        val floating = rememberBooleanPref(prefs, KEY_KEYBOARD_FLOATING, true)
        SettingCard(contentPadding = 0.dp, itemSpacing = 0.dp) {
            SwitchSetting(R.string.raise_desktop_for_soft_keyboard,
                R.string.raise_desktop_for_soft_keyboard_hint, !floating.value) {
                floating.value = !it
                prefs.edit().putBoolean(KEY_KEYBOARD_FLOATING, !it).apply()
            }
        }
    }
    item {
        val enabled = rememberBooleanPref(prefs, KEY_ACCESSIBILITY_ENABLED, false)
        SettingCard(contentPadding = 0.dp, itemSpacing = 0.dp) {
            SwitchSetting(R.string.accessibility_switch, R.string.accessibility_hint, enabled.value) {
                enabled.value = it
                prefs.edit().putBoolean(KEY_ACCESSIBILITY_ENABLED, it).apply()
                if (it) KeyInterceptor.launch(activity) else KeyInterceptor.shutdown(false)
            }
        }
    }
    item {
        val extraKeysEnabled = rememberBooleanPref(prefs, KEY_EXTRA_KEYS_ENABLED, false)
        val autoShowExtraKeys = rememberBooleanPref(prefs, KEY_AUTO_SHOW_EXTRA_KEYS, true)
        SettingCard(contentPadding = 0.dp, itemSpacing = 0.dp) {
            SwitchSetting(R.string.extra_keys_switch, R.string.extra_keys_hint, extraKeysEnabled.value) {
                extraKeysEnabled.value = it
                prefs.edit().putBoolean(KEY_EXTRA_KEYS_ENABLED, it).apply()
            }
            SwitchSetting(R.string.auto_show_switch, R.string.auto_show_hint, autoShowExtraKeys.value) {
                autoShowExtraKeys.value = it
                prefs.edit().putBoolean(KEY_AUTO_SHOW_EXTRA_KEYS, it).apply()
            }
        }
    }
    item { CustomLayoutSetting(activity, prefs) }
}

@Composable
private fun KeyBindingSetting(activity: SettingsActivity, prefs: SharedPreferences) {
    var bound by remember { mutableIntStateOf(prefs.getInt(KEY_BOUND_KEYCODE, -1)) }
    var listening by remember { mutableStateOf(activity.isKeyBindingListening()) }
    var remainingSeconds by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_BOUND_KEYCODE) {
                bound = prefs.getInt(KEY_BOUND_KEYCODE, -1)
                listening = activity.isKeyBindingListening()
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(listening) {
        if (!listening) {
            remainingSeconds = 0
            return@LaunchedEffect
        }

        var remaining = 5
        remainingSeconds = remaining
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
            if (!activity.isKeyBindingListening()) {
                listening = false
                return@LaunchedEffect
            }
            remainingSeconds = remaining
        }
        // The Activity timer owns the actual timeout. Reset the Compose state
        // here as well so the button cannot remain stuck on the last second if
        // the preference value did not change.
        listening = false
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.section_virtual_keyboard),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (bound == -1) stringResource(R.string.status_current_none)
                    else stringResource(R.string.status_current, keyName(bound)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = {
                activity.startKeyBindingFromCompose()
                listening = true
            }) {
                Text(if (listening) stringResource(
                    R.string.listening_countdown, remainingSeconds.coerceAtLeast(1)
                )
                else stringResource(R.string.bind_key_button))
            }
        }
    }
}

private fun keyName(keyCode: Int): String =
    KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace('_', ' ')

@Composable
private fun CustomLayoutSetting(activity: SettingsActivity, prefs: SharedPreferences) {
    val defaultLayout = remember { ExtraKeysBar.defaultLayoutJson() }
    var text by remember {
        mutableStateOf(prefs.getString(KEY_EXTRA_KEYS_LAYOUT, "").orEmpty().ifEmpty { defaultLayout })
    }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_EXTRA_KEYS_LAYOUT) text = prefs.getString(key, "").orEmpty().ifEmpty { defaultLayout }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SettingCard {
        SectionTitle(R.string.section_custom_layout)
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                prefs.edit().putString(KEY_EXTRA_KEYS_LAYOUT, it).apply()
            },
            modifier = Modifier.fillMaxWidth().height(190.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
        )
        val error = if (text.trim().isEmpty()) null else ExtraKeysBar.validateLayout(text)
        Text(
            when {
                text.trim().isEmpty() -> stringResource(R.string.layout_status_default)
                error == null -> stringResource(R.string.layout_status_valid)
                else -> stringResource(R.string.layout_status_invalid, error)
            },
            color = when {
                text.trim().isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                error == null -> Color(0xFF2E7D32)
                else -> MaterialTheme.colorScheme.error
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = {
                text = defaultLayout
                prefs.edit().putString(KEY_EXTRA_KEYS_LAYOUT, defaultLayout).apply()
            }) { Text(stringResource(R.string.btn_load_default)) }
            OutlinedButton(onClick = activity::pickLayoutFileFromCompose) {
                Text(stringResource(R.string.btn_load_file))
            }
        }
        Text(stringResource(R.string.layout_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun SettingCard(
    contentPadding: androidx.compose.ui.unit.Dp = 16.dp,
    itemSpacing: androidx.compose.ui.unit.Dp = 8.dp,
    content: @Composable () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(itemSpacing)
        ) {
            content()
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.touchpadPage(prefs: SharedPreferences) {
    item {
        val enabled = rememberBooleanPref(prefs, KEY_TOUCHPAD_MODE, false)
        SettingCard(contentPadding = 0.dp) {
            SwitchSetting(R.string.touchpad_mode_switch, R.string.touchpad_hint, enabled.value) {
                enabled.value = it; prefs.edit().putBoolean(KEY_TOUCHPAD_MODE, it).apply()
            }
            Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                FloatSliderSetting(prefs, KEY_MOUSE_ACCEL, R.string.mouse_sensitivity_label,
                    R.string.mouse_accel_value, 0.5f, 10f, 0.05f, 1f)
            }
        }
    }
    item {
        val enabled = rememberBooleanPref(prefs, KEY_POINTER_CAPTURE, false)
        val transform = rememberStringPref(prefs, KEY_TRANSFORM_CAPTURED_POINTER, pointerTransforms[0])
        val speed = rememberIntPref(prefs, KEY_CAPTURED_POINTER_SPEED_FACTOR, 100)
        SettingCard(contentPadding = 0.dp) {
            SwitchSetting(R.string.pointer_capture_switch, R.string.pointer_capture_hint, enabled.value) {
                enabled.value = it; prefs.edit().putBoolean(KEY_POINTER_CAPTURE, it).apply()
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                SettingDropdown(R.string.transform_captured_pointer_label,
                    transform.value, pointerTransforms,
                    stringArrayResource(R.array.captured_pointer_transform_labels).toList(), enabled.value) {
                    transform.value = it; prefs.edit().putString(KEY_TRANSFORM_CAPTURED_POINTER, it).apply()
                }
            }
            Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                IntSliderSetting(R.string.captured_pointer_speed_label, R.string.captured_pointer_speed_value,
                    speed.value, 1, 300, enabled.value) {
                    speed.value = it; prefs.edit().putInt(KEY_CAPTURED_POINTER_SPEED_FACTOR, it).apply()
                }
            }
        }
    }
    item {
        val reverse = rememberBooleanPref(prefs, KEY_SCROLL_REVERSE, false)
        SettingCard(contentPadding = 0.dp, itemSpacing = 0.dp) {
            SwitchSetting(R.string.scroll_reverse_switch, R.string.scroll_reverse_hint, reverse.value) {
                reverse.value = it; prefs.edit().putBoolean(KEY_SCROLL_REVERSE, it).apply()
            }
        }
    }
    item {
        SettingCard {
            FloatSliderSetting(prefs, KEY_SCROLL_SPEED, R.string.scroll_speed_label,
                R.string.scroll_speed_value, 0.05f, 3f, 0.05f, Touchpad.DEFAULT_SCROLL_SPEED)
            FloatSliderSetting(prefs, KEY_SCROLL_THRESHOLD, R.string.scroll_threshold_label,
                R.string.threshold_factor_value, 0.05f, 3f, 0.05f, Touchpad.DEFAULT_SCROLL_THRESHOLD_FACTOR,
                R.string.scroll_threshold_hint)
            FloatSliderSetting(prefs, KEY_MOVE_THRESHOLD, R.string.move_threshold_label,
                R.string.threshold_factor_value, 0.1f, 8f, 0.05f, Touchpad.DEFAULT_MOVE_THRESHOLD_FACTOR,
                R.string.move_threshold_hint)
            FloatSliderSetting(prefs, KEY_GESTURE_SCALE, R.string.gesture_scale_label,
                R.string.gesture_scale_value, 100f, 3000f, 20f, Touchpad.DEFAULT_GESTURE_SCALE,
                R.string.gesture_scale_hint)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.connectionPage(
    activity: SettingsActivity, prefs: SharedPreferences
) {
    item {
        val path = rememberStringPref(prefs, KEY_SOCKET_PATH, DEFAULT_SOCKET_PATH)
        SettingCard(contentPadding = 0.dp) {
            OutlinedTextField(path.value, { path.value = it; prefs.edit().putString(KEY_SOCKET_PATH, it.trim()).apply() },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                label = { Text(stringResource(R.string.socket_path_label)) }, singleLine = true)
            BooleanPreference(prefs, KEY_USE_ROOT, false, R.string.root_switch, R.string.root_hint)
        }
    }
    item {
        SettingCard(contentPadding = 0.dp, itemSpacing = 0.dp) {
            BooleanPreference(prefs, KEY_MIC_ENABLED, false, R.string.mic_switch, R.string.mic_hint)
            BooleanPreference(prefs, KEY_CAMERA_ENABLED, false, R.string.camera_switch, R.string.camera_hint)
        }
    }
    item {
        SettingCard(contentPadding = 0.dp, itemSpacing = 0.dp) {
            BooleanPreference(prefs, KEY_AUDIO_KEEPALIVE, false,
                R.string.audio_keepalive_switch, R.string.audio_keepalive_hint)
        }
    }
    item {
        SettingCard {
            SectionTitle(R.string.audio_latency_title)
            LatencySetting(prefs, KEY_SPEAKER_LATENCY_MS, R.string.latency_speaker_label)
            LatencySetting(prefs, KEY_MIC_LATENCY_MS, R.string.latency_mic_label)
            Text(stringResource(R.string.latency_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BooleanPreference(prefs: SharedPreferences, key: String, default: Boolean,
    title: Int, hint: Int) {
    val state = rememberBooleanPref(prefs, key, default)
    SwitchSetting(title, hint, state.value) {
        state.value = it; prefs.edit().putBoolean(key, it).apply()
    }
}

@Composable
private fun LatencySetting(prefs: SharedPreferences, key: String, label: Int) {
    val state = rememberIntPref(prefs, key, 0)
    SettingDropdown(label, state.value, latencyValues,
        stringArrayResource(R.array.latency_labels).toList()) {
        state.value = it; prefs.edit().putInt(key, it).apply()
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.displayPage(
    activity: SettingsActivity, prefs: SharedPreferences
) {
    item {
        val width = rememberIntPref(prefs, "custom_width", 0)
        val height = rememberIntPref(prefs, "custom_height", 0)
        var preset by remember { mutableIntStateOf(0) }
        val labels = stringArrayResource(R.array.res_preset_labels).toList()
        val orientation = rememberStringPref(prefs, KEY_SCREEN_ORIENTATION, screenOrientations[0])
        val cutout = rememberStringPref(prefs, DisplayCutoutMode.KEY, DisplayCutoutMode.get(prefs))
        SettingCard {
            SectionTitle(R.string.section_screen_layout)
            SettingDropdown(R.string.section_resolution, preset, labels.indices.toList(), labels) {
                preset = it
                resolvePreset(activity, it)?.let { (w, h) ->
                    width.value = w; height.value = h
                    prefs.edit().putInt("custom_width", w).putInt("custom_height", h).apply()
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(width.value.toString(), {
                    width.value = it.toIntOrNull() ?: 0
                    prefs.edit().putInt("custom_width", width.value).apply()
                }, modifier = Modifier.weight(1f), label = { Text(stringResource(R.string.width_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                OutlinedTextField(height.value.toString(), {
                    height.value = it.toIntOrNull() ?: 0
                    prefs.edit().putInt("custom_height", height.value).apply()
                }, modifier = Modifier.weight(1f), label = { Text(stringResource(R.string.height_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            }
            Text(stringResource(R.string.resolution_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            SettingDropdown(R.string.section_screen_orientation, orientation.value, screenOrientations,
                stringArrayResource(R.array.screen_orientation_labels).toList()) {
                orientation.value = it; prefs.edit().putString(KEY_SCREEN_ORIENTATION, it).apply()
            }
            Text(stringResource(R.string.screen_orientation_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            SettingDropdown(R.string.display_cutout_mode_label, cutout.value, DisplayCutoutMode.VALUES.toList(),
                stringArrayResource(R.array.display_cutout_mode_labels).toList()) {
                cutout.value = it; prefs.edit().putString(DisplayCutoutMode.KEY, it).apply()
            }
        }
    }
    item {
        SettingCard(contentPadding = 0.dp, itemSpacing = 0.dp) {
            BooleanPreference(prefs, KEY_PIP_MODE, false, R.string.pip_mode_switch, R.string.pip_mode_hint)
        }
    }
}

private fun resolvePreset(activity: SettingsActivity, index: Int): Pair<Int, Int>? = when (index) {
    1 -> 0 to 0
    2 -> 3840 to 2160
    3 -> 2560 to 1440
    4 -> 1920 to 1080
    5 -> 1280 to 720
    6 -> 854 to 480
    7 -> scaleScreen(activity, 1f)
    8 -> scaleScreen(activity, .8f)
    9 -> scaleScreen(activity, .75f)
    10 -> scaleScreen(activity, .5f)
    11 -> scaleScreen(activity, .25f)
    else -> null
}

private fun scaleScreen(activity: SettingsActivity, factor: Float): Pair<Int, Int> {
    val bounds: Rect = activity.windowManager.maximumWindowMetrics.bounds
    val longSide = maxOf(bounds.width(), bounds.height())
    val shortSide = minOf(bounds.width(), bounds.height())
    return ((longSide * factor).roundToInt() and -2) to ((shortSide * factor).roundToInt() and -2)
}

private fun androidx.compose.foundation.lazy.LazyListScope.generalPage(prefs: SharedPreferences) {
    item {
        SettingCard(contentPadding = 0.dp, itemSpacing = 0.dp) {
            BooleanPreference(prefs, KEY_NOTIFICATION_ENABLED, true,
                R.string.notification_switch, R.string.notification_hint)
        }
    }
    val actions = listOf(
        UserActions.VOLUME_UP to R.string.user_action_volume_up,
        UserActions.VOLUME_DOWN to R.string.user_action_volume_down,
        UserActions.BACK_BUTTON to R.string.user_action_back_button,
        UserActions.NOTIFICATION_TAP to R.string.user_action_notification_tap,
        UserActions.NOTIFICATION_FIRST_BUTTON to R.string.user_action_notification_first_button,
        UserActions.NOTIFICATION_SECOND_BUTTON to R.string.user_action_notification_second_button,
        UserActions.MEDIA_KEYS to R.string.user_action_media_keys
    )
    item {
        SettingCard {
            SectionTitle(R.string.section_user_actions)
            actions.forEach { (action, title) -> UserActionSetting(prefs, action, title) }
        }
    }
}

@Composable
private fun UserActionSetting(prefs: SharedPreferences, action: String, title: Int) {
    val responses = UserActions.responsesFor(action).toList()
    val labels = responses.map { stringResource(responseLabelResource(it)) }
    val current = rememberStringPref(prefs, action, UserActions.getResponse(prefs, action))
    SettingDropdown(title, current.value, responses, labels) {
        current.value = it; prefs.edit().putString(action, it).apply()
    }
}

private fun responseLabelResource(response: String): Int = when (response) {
    UserActions.TOGGLE_SOFT_KEYBOARD -> R.string.user_response_toggle_soft_keyboard
    UserActions.TOGGLE_ADDITIONAL_KEY_BAR -> R.string.user_response_toggle_additional_key_bar
    UserActions.OPEN_PREFERENCES -> R.string.user_response_open_preferences
    UserActions.RELEASE_POINTER_AND_KEYBOARD_CAPTURE -> R.string.user_response_release_captures
    UserActions.RESTART_ACTIVITY -> R.string.user_response_restart_activity
    UserActions.EXIT -> R.string.user_response_exit
    UserActions.TOGGLE_TOUCHPAD_MODE -> R.string.user_response_toggle_touchpad_mode
    UserActions.TOGGLE_SCREEN_ORIENTATION -> R.string.user_response_toggle_screen_orientation
    UserActions.SEND_VOLUME_UP -> R.string.user_response_send_volume_up
    UserActions.SEND_VOLUME_DOWN -> R.string.user_response_send_volume_down
    UserActions.SEND_MEDIA_ACTION -> R.string.user_response_send_media_action
    else -> R.string.user_response_no_action
}

@Composable
private fun SectionTitle(@androidx.annotation.StringRes title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SwitchSetting(title: Int, hint: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(hint)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) }
    )
}

@Composable
private fun <T> SettingDropdown(
    label: Int,
    selected: T,
    options: List<T>,
    labels: List<String>,
    enabled: Boolean = true,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val index = options.indexOf(selected).coerceAtLeast(0)
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedButton(enabled = enabled, onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(labels.getOrElse(index) { selected.toString() }, modifier = Modifier.weight(1f))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true)) {
                options.forEachIndexed { optionIndex, option ->
                    DropdownMenuItem(text = { Text(labels.getOrElse(optionIndex) { option.toString() }) },
                        onClick = { expanded = false; onSelected(option) })
                }
            }
        }
    }
}

@Composable
private fun FloatSliderSetting(
    prefs: SharedPreferences, key: String, label: Int, valueFormat: Int,
    min: Float, max: Float, step: Float, default: Float, hint: Int? = null
) {
    val state = rememberFloatPref(prefs, key, default)
    val value = state.value.coerceIn(min, max)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(label))
            Text(stringResource(valueFormat, value), color = MaterialTheme.colorScheme.primary)
        }
        Slider(value, { next ->
            val rounded = (next / step).roundToInt() * step
            state.value = rounded; prefs.edit().putFloat(key, rounded).apply()
        }, valueRange = min..max, steps = ((max - min) / step).roundToInt() - 1)
        hint?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun IntSliderSetting(label: Int, valueFormat: Int, value: Int, min: Int, max: Int,
    enabled: Boolean, onValueChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(label))
            Text(stringResource(valueFormat, value), color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.toFloat(), onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = min.toFloat()..max.toFloat(), enabled = enabled)
    }
}

@Composable
private fun rememberBooleanPref(prefs: SharedPreferences, key: String, default: Boolean): MutableState<Boolean> =
    remember(key) { mutableStateOf(prefs.getBoolean(key, default)) }

@Composable
private fun rememberStringPref(prefs: SharedPreferences, key: String, default: String): MutableState<String> =
    remember(key) { mutableStateOf(prefs.getString(key, default) ?: default) }

@Composable
private fun rememberIntPref(prefs: SharedPreferences, key: String, default: Int): MutableState<Int> =
    remember(key) { mutableIntStateOf(prefs.getInt(key, default)) }

@Composable
private fun rememberFloatPref(prefs: SharedPreferences, key: String, default: Float): MutableState<Float> =
    remember(key) { mutableFloatStateOf(prefs.getFloat(key, default)) }
