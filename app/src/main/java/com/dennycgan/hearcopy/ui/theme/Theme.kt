package com.dennycgan.hearcopy.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import com.dennycgan.hearcopy.R

@Composable
fun HearCopyTheme(content: @Composable () -> Unit) {
    val colorScheme = lightColorScheme(
        primary = colorResource(R.color.hearcopy_accent),
        secondary = colorResource(R.color.hearcopy_secondary),
        background = colorResource(R.color.hearcopy_white),
        surface = colorResource(R.color.hearcopy_white),
    )
    MaterialTheme(colorScheme = colorScheme, content = content)
}
