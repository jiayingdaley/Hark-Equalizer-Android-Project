package com.wcy.hark.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = HarkColors.PrimaryLight,
    onPrimary = androidx.compose.ui.graphics.Color(0xFF00332C),
    primaryContainer = HarkColors.PrimaryDark,
    onPrimaryContainer = HarkColors.PrimaryContainer,
    secondary = androidx.compose.ui.graphics.Color(0xFF80CBC4),
    tertiary = HarkColors.EarBoth,
    background = HarkColors.ExperimentBg,
    surface = HarkColors.ExperimentCard
)

private val LightColorScheme = lightColorScheme(
    primary = HarkColors.Primary,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = HarkColors.PrimaryContainer,
    onPrimaryContainer = HarkColors.OnPrimaryContainer,
    secondary = androidx.compose.ui.graphics.Color(0xFF00695C),
    tertiary = HarkColors.EarBoth,
    background = HarkColors.UserBgTop
)

@Composable
fun HarkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}