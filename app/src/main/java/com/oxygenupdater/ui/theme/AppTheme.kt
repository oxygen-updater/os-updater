package com.oxygenupdater.ui.theme

import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import com.oxygenupdater.ui.Theme
import java.util.Calendar

val LocalTheme = staticCompositionLocalOf { Theme.System }

/**
 * @param theme is forwarded to the static composition [LocalTheme]
 */
@Composable
fun AppTheme(
    theme: Theme,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = theme.dark ?: if (theme == Theme.System) isSystemInDarkTheme() else remember {
        // Avoid a potentially expensive call
        Calendar.getInstance()[Calendar.HOUR_OF_DAY].let { hour ->
            if (hour in 19..23 || hour in 0..6) true
            else context.getSystemService<PowerManager>()?.isPowerSaveMode == true
        }
    }

    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) DarkColorScheme else LightColorScheme

    CompositionLocalProvider(LocalTheme providesDefault theme) {
        MaterialTheme(
            colorScheme = colorScheme.run {
                copy(
                    // Used by dividers; modified to reduce contrast
                    outlineVariant = surfaceContainer,
                )
            },
            typography = remember { appTypography() },
            content = content,
        )
    }
}

/** Only for Compose [Preview] or androidTest use. It wraps around [AppTheme] with suitable default values. */
@Composable
fun PreviewAppTheme(content: @Composable () -> Unit) {
    AppTheme(theme = Theme.System, content = { Surface(content = content) })
}

val PreviewGetPrefStr: (key: String, default: String) -> String = { _, default -> default }
val PreviewGetPrefBool: (key: String, default: Boolean) -> Boolean = { _, default -> default }
val PreviewGetPrefInt: (key: String, default: Int) -> Int = { _, default -> default }

val PreviewWindowSize
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    @Composable
    get() = with(LocalConfiguration.current) {
        WindowSizeClass.calculateFromSize(DpSize(screenWidthDp.dp, screenHeightDp.dp))
    }

@Preview("Light", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview("Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview("SizeMedium", device = "spec:width=600dp,height=480dp", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview("SizeExpanded", device = "spec:width=840dp,height=900dp", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class PreviewThemes
