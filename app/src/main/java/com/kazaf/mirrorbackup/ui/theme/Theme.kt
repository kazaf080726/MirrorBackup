package com.kazaf.mirrorbackup.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
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

private fun lightColors(accent: Color) = lightColorScheme(
    primary = accent,
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

private fun darkColors(accent: Color, amoled: Boolean) = darkColorScheme(
    primary = accent,
    onPrimary = Color.White,
    secondary = MiuiColors.Green,
    onSecondary = Color.White,
    tertiary = MiuiColors.Orange,
    background = if (amoled) Color(0xFF000000) else MiuiColors.DarkBg,
    onBackground = MiuiColors.DarkText,
    surface = if (amoled) Color(0xFF0C0D10) else MiuiColors.DarkCard,
    onSurface = MiuiColors.DarkText,
    onSurfaceVariant = MiuiColors.DarkSub,
    error = MiuiColors.Red,
    outline = Color(0xFF2E3642)
)

/**
 * 主题入口：colorMode（system/light/dark/amoled）、Monet 动态取色、
 * 强调色自定义。跟随 KernelSU / SukiSU 管理器主题设置体系。
 */
@Composable
fun MirrorBackupTheme(
    colorMode: String = "system",
    monetEnabled: Boolean = false,
    accent: Color = MiuiColors.Blue,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (colorMode) {
        "light" -> false
        "dark" -> true
        "amoled" -> true
        else -> systemDark
    }
    val amoled = colorMode == "amoled"
    val context = androidx.compose.ui.platform.LocalContext.current
    val scheme = when {
        monetEnabled && android.os.Build.VERSION.SDK_INT >= 31 && !amoled -> {
            // Monet 动态取色（Material You）
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> if (dark) darkColors(accent, amoled) else lightColors(accent)
    }
    MaterialTheme(
        colorScheme = scheme,
        content = content
    )
}
