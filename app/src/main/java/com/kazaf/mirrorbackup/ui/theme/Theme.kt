package com.kazaf.mirrorbackup.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** MIUI-X 风格配色 */
object MiuiColors {
    val Blue = Color(0xFF1677FF)
    val BlueDeep = Color(0xFF0B5FD9)
    val Green = Color(0xFF00B578)
    val Red = Color(0xFFF53F3F)
    val Orange = Color(0xFFFF8F1F)
    val Purple = Color(0xFF7B5CFF)

    val LightBg = Color(0xFFF4F6FA)
    val LightCard = Color(0xFFFFFFFF)
    val LightText = Color(0xFF1D2129)
    val LightSub = Color(0xFF86909C)

    val DarkBg = Color(0xFF10141A)
    val DarkCard = Color(0xFF1A2029)
    val DarkText = Color(0xFFE8EAEF)
    val DarkSub = Color(0xFF8B93A1)

    // TWRP 风格终端配色
    val TerminalBg = Color(0xFF0B0F16)
    val TerminalInfo = Color(0xFF8FC9FF)
    val TerminalText = Color(0xFFC9D6E4)
    val TerminalOk = Color(0xFF3EE66F)
    val TerminalWarn = Color(0xFFFFC53D)
    val TerminalErr = Color(0xFFFF5C5C)
    val TerminalProgress = Color(0xFF6FC3FF)
}

private val LightColors = lightColorScheme(
    primary = MiuiColors.Blue,
    onPrimary = Color.White,
    secondary = MiuiColors.Green,
    onSecondary = Color.White,
    tertiary = MiuiColors.Orange,
    background = MiuiColors.LightBg,
    onBackground = MiuiColors.LightText,
    surface = MiuiColors.LightCard,
    onSurface = MiuiColors.LightText,
    onSurfaceVariant = MiuiColors.LightSub,
    error = MiuiColors.Red,
    outline = Color(0xFFE5E6EB)
)

private val DarkColors = darkColorScheme(
    primary = MiuiColors.Blue,
    onPrimary = Color.White,
    secondary = MiuiColors.Green,
    onSecondary = Color.White,
    tertiary = MiuiColors.Orange,
    background = MiuiColors.DarkBg,
    onBackground = MiuiColors.DarkText,
    surface = MiuiColors.DarkCard,
    onSurface = MiuiColors.DarkText,
    onSurfaceVariant = MiuiColors.DarkSub,
    error = MiuiColors.Red,
    outline = Color(0xFF2E3642)
)

/** MIUI-X 主题：跟随系统深色/浅色自动切换 */
@Composable
fun MirrorBackupTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}
