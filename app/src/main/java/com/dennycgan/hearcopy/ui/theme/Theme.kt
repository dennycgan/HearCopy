package com.dennycgan.hearcopy.ui.theme

import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import com.dennycgan.hearcopy.R

/**
 * One light scheme; the app has no dark theme. Slots used by the screens:
 * primaryContainer is the status card, surfaceVariant the saved key box,
 * outline the borders and dividers, onSurfaceVariant the muted text.
 */
@Composable
fun HearCopyTheme(content: @Composable () -> Unit) {
    val text = colorResource(R.color.hearcopy_text)
    val colorScheme = lightColorScheme(
        primary = colorResource(R.color.hearcopy_accent),
        onPrimary = colorResource(R.color.hearcopy_white),
        secondary = colorResource(R.color.hearcopy_secondary),
        primaryContainer = colorResource(R.color.hearcopy_status_background),
        onPrimaryContainer = text,
        background = colorResource(R.color.hearcopy_white),
        onBackground = text,
        surface = colorResource(R.color.hearcopy_white),
        onSurface = text,
        surfaceVariant = colorResource(R.color.hearcopy_surface),
        onSurfaceVariant = colorResource(R.color.hearcopy_muted),
        outline = colorResource(R.color.hearcopy_border),
        outlineVariant = colorResource(R.color.hearcopy_border),
    )
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/**
 * Status and navigation bars: transparent with dark icons, also in system
 * dark mode, because the screens are always light. The default style
 * follows the system and would put light icons on the white header.
 */
fun hearCopyBarStyle(): SystemBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
