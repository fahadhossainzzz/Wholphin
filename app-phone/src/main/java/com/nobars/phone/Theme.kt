package com.nobars.phone

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NbRed = Color(0xFFE5484D)

private val scheme =
    darkColorScheme(
        primary = NbRed,
        onPrimary = Color.White,
        secondary = Color(0xFF9AA4B2),
        background = Color(0xFF101014),
        surface = Color(0xFF16161C),
        surfaceVariant = Color(0xFF23232B),
        onBackground = Color(0xFFECECF1),
        onSurface = Color(0xFFECECF1),
        onSurfaceVariant = Color(0xFFA8A8B3),
    )

@Composable
fun NbTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
