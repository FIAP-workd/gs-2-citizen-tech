package com.chavesegura.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF0B5CAD),
    onPrimary = Color.White,
    secondary = Color(0xFF2E6B30),
    error = Color(0xFFB71C1C),
    background = Color(0xFFF7F9FC),
    surface = Color.White,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8CC4FF),
    onPrimary = Color(0xFF00213F),
    secondary = Color(0xFF9CD69C),
    error = Color(0xFFFFB4AB),
)

/** Letras maiores que o padrão (diretrizes de acessibilidade do README §3.1). */
private val AccessibleType = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontSize = 30.sp, fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontSize = 19.sp, lineHeight = 27.sp),
        bodyMedium = bodyMedium.copy(fontSize = 17.sp, lineHeight = 24.sp),
        labelLarge = labelLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    )
}

val MonoStyle = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 20.sp)

@Composable
fun ChaveSeguraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AccessibleType,
        content = content,
    )
}
