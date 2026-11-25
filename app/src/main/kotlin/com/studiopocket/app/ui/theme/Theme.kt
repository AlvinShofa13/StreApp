package com.studiopocket.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Latar = Color(0xFFF6F7F9)
val Kartu = Color(0xFFFFFFFF)
val Teks = Color(0xFF1F2933)
val TeksSekunder = Color(0xFF5B6670)
val Aksen = Color(0xFF3D6FA5)
val Rekam = Color(0xFFC0504D)
val Berhasil = Color(0xFF4F8A6B)
val Peringatan = Color(0xFFC98A2B)
val Garis = Color(0xFFE2E6EA)

private val skemaTerang = lightColorScheme(
    primary = Aksen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7EFF8),
    onPrimaryContainer = Color(0xFF203A56),
    secondary = TeksSekunder,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEF1F4),
    onSecondaryContainer = Teks,
    error = Rekam,
    onError = Color.White,
    errorContainer = Color(0xFFF9E9E8),
    onErrorContainer = Color(0xFF5A1F1E),
    background = Latar,
    onBackground = Teks,
    surface = Kartu,
    onSurface = Teks,
    surfaceVariant = Color(0xFFF0F2F5),
    onSurfaceVariant = TeksSekunder,
    outline = Garis,
    outlineVariant = Color(0xFFEDF0F3),
)

private val bentuk = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun StreAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = skemaTerang,
        typography = Typography(),
        shapes = bentuk,
        content = content,
    )
}
