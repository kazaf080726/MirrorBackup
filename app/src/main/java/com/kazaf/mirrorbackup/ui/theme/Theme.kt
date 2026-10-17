package com.kazaf.mirrorbackup.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.kazaf.mirrorbackup.core.ThemeConfig
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme

/** MIUI-X 风格配色（固定语义色，用于状态/日志等） */
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

/**
 * 颜色模式 —— 完整照搬 KernelSU Theme.kt 的 ColorMode 枚举（含 Monet 变体）。
 */
enum class ColorMode(val value: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2),
    MONET_SYSTEM(3),
    MONET_LIGHT(4),
    MONET_DARK(5),
    DARK_AMOLED(6);

    companion object {
        fun fromValue(value: Int) = entries.find { it.value == value } ?: SYSTEM
    }

    val isSystem: Boolean get() = value == 0 || value == 3
    val isDark: Boolean get() = value == 2 || value == 5 || value == 6
    val isAmoled: Boolean get() = value == 6
    val isMonet: Boolean get() = value >= 3

    fun toNonMonetMode(): ColorMode = when (this) {
        MONET_SYSTEM -> SYSTEM
        MONET_LIGHT -> LIGHT
        MONET_DARK, DARK_AMOLED -> DARK
        else -> this
    }

    fun toMonetMode(): ColorMode = when (this) {
        SYSTEM -> MONET_SYSTEM
        LIGHT -> MONET_LIGHT
        DARK -> MONET_DARK
        else -> this
    }
}

/** materialkolor PaletteStyle 中文名 */
val PaletteStyle.displayName: String
    get() = when (this) {
        PaletteStyle.TonalSpot -> "色调均衡 (Tonal Spot)"
        PaletteStyle.Neutral -> "中性 (Neutral)"
        PaletteStyle.Vibrant -> "鲜艳 (Vibrant)"
        PaletteStyle.Expressive -> "表现力 (Expressive)"
        PaletteStyle.Fidelity -> "保真 (Fidelity)"
        PaletteStyle.Content -> "内容 (Content)"
        PaletteStyle.Monochrome -> "单色 (Monochrome)"
        PaletteStyle.Rainbow -> "彩虹 (Rainbow)"
        PaletteStyle.FruitSalad -> "水果沙拉 (Fruit Salad)"
        else -> name
    }

val PaletteStyle.supportsSpec2025: Boolean
    get() = this == PaletteStyle.TonalSpot ||
        this == PaletteStyle.Neutral ||
        this == PaletteStyle.Vibrant ||
        this == PaletteStyle.Expressive

fun ColorSpec.SpecVersion.effectiveFor(style: PaletteStyle): ColorSpec.SpecVersion =
    if (this == ColorSpec.SpecVersion.SPEC_2025 && !style.supportsSpec2025) {
        ColorSpec.SpecVersion.SPEC_2021
    } else {
        this
    }

/** AMOLED：纯黑表面（照搬 KSU ThemeExt.amoledBackground） */
fun ColorScheme.amoledBackground(amoled: Boolean): ColorScheme =
    if (!amoled) this
    else copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color.Black,
        surfaceContainer = Color.Black,
        surfaceContainerHigh = Color.Black,
        surfaceContainerHighest = Color.Black
    )

/**
 * 完整照搬 KSU ThemeExt.rememberKernelSUColorScheme：
 * keyColor = 0 → 系统动态取色（Android 12+ dynamicLight/DarkColorScheme）
 * 否则 → materialkolor 生成（PaletteStyle + ColorSpec）
 */
@Composable
fun rememberKernelSUColorScheme(
    seedColor: Color,
    isDark: Boolean,
    isAmoled: Boolean,
    paletteStyle: PaletteStyle,
    colorSpec: ColorSpec.SpecVersion,
): ColorScheme {
    val context = LocalContext.current
    val seed = if (seedColor == Color.Unspecified) {
        (if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).primary
    } else {
        seedColor
    }
    return rememberDynamicColorScheme(
        seedColor = seed,
        isDark = isDark,
        isAmoled = isAmoled,
        style = paletteStyle,
        specVersion = colorSpec.effectiveFor(paletteStyle)
    ).amoledBackground(isAmoled)
}

/** 主题切换颜色渐变过渡（spring，照搬 KSU ThemeExt.animateAsState） */
@Composable
fun ColorScheme.animateAsState(): ColorScheme {
    @Composable
    fun animateColor(color: Color): Color = animateColorAsState(
        targetValue = color,
        animationSpec = spring(),
        label = "theme_color_animation"
    ).value

    return ColorScheme(
        primary = animateColor(primary),
        onPrimary = animateColor(onPrimary),
        primaryContainer = animateColor(primaryContainer),
        onPrimaryContainer = animateColor(onPrimaryContainer),
        inversePrimary = animateColor(inversePrimary),
        secondary = animateColor(secondary),
        onSecondary = animateColor(onSecondary),
        secondaryContainer = animateColor(secondaryContainer),
        onSecondaryContainer = animateColor(onSecondaryContainer),
        tertiary = animateColor(tertiary),
        onTertiary = animateColor(onTertiary),
        tertiaryContainer = animateColor(tertiaryContainer),
        onTertiaryContainer = animateColor(onTertiaryContainer),
        background = animateColor(background),
        onBackground = animateColor(onBackground),
        surface = animateColor(surface),
        onSurface = animateColor(onSurface),
        surfaceVariant = animateColor(surfaceVariant),
        onSurfaceVariant = animateColor(onSurfaceVariant),
        surfaceTint = animateColor(surfaceTint),
        inverseSurface = animateColor(inverseSurface),
        inverseOnSurface = animateColor(inverseOnSurface),
        error = animateColor(error),
        onError = animateColor(onError),
        errorContainer = animateColor(errorContainer),
        onErrorContainer = animateColor(onErrorContainer),
        outline = animateColor(outline),
        outlineVariant = animateColor(outlineVariant),
        scrim = animateColor(scrim),
        surfaceBright = animateColor(surfaceBright),
        surfaceDim = animateColor(surfaceDim),
        surfaceContainer = animateColor(surfaceContainer),
        surfaceContainerHigh = animateColor(surfaceContainerHigh),
        surfaceContainerHighest = animateColor(surfaceContainerHighest),
        surfaceContainerLow = animateColor(surfaceContainerLow),
        surfaceContainerLowest = animateColor(surfaceContainerLowest),
        primaryFixed = animateColor(primaryFixed),
        primaryFixedDim = animateColor(primaryFixedDim),
        onPrimaryFixed = animateColor(onPrimaryFixed),
        onPrimaryFixedVariant = animateColor(onPrimaryFixedVariant),
        secondaryFixed = animateColor(secondaryFixed),
        secondaryFixedDim = animateColor(secondaryFixedDim),
        onSecondaryFixed = animateColor(onSecondaryFixed),
        onSecondaryFixedVariant = animateColor(onSecondaryFixedVariant),
        tertiaryFixed = animateColor(tertiaryFixed),
        tertiaryFixedDim = animateColor(tertiaryFixedDim),
        onTertiaryFixed = animateColor(onTertiaryFixed),
        onTertiaryFixedVariant = animateColor(onTertiaryFixedVariant)
    )
}

/** 当前是否深色（CompositionLocal 版，照搬 KSU isInDarkTheme） */
@Composable
@ReadOnlyComposable
fun isInDarkTheme(): Boolean {
    return when (LocalColorMode.current) {
        1, 4 -> false
        2, 5, 6 -> true
        else -> isSystemInDarkTheme()
    }
}

val LocalColorMode = staticCompositionLocalOf { 0 }
val LocalEnableBlur = staticCompositionLocalOf { true }
val LocalEnableDynamicEffects = staticCompositionLocalOf { true }

/**
 * 主题入口 —— 与 KSU Manager 完全一致的参数：ColorMode + keyColor + PaletteStyle + ColorSpec，
 * 并带 spring 颜色过渡动画。
 */
@Composable
fun MirrorBackupTheme(
    colorMode: Int = ThemeConfig.COLOR_MODE_SYSTEM,
    keyColor: Int = 0,
    paletteStyleName: String = "TonalSpot",
    colorSpecName: String = "SPEC_2025",
    dynamicEffects: Boolean = true,
    content: @Composable () -> Unit
) {
    val mode = ColorMode.fromValue(colorMode)
    val systemDark = isSystemInDarkTheme()
    val dark = mode.isDark || (mode.isSystem && systemDark)
    val amoled = mode.isAmoled
    val paletteStyle = runCatching { PaletteStyle.valueOf(paletteStyleName) }
        .getOrDefault(PaletteStyle.TonalSpot)
    val colorSpec = runCatching { ColorSpec.SpecVersion.valueOf(colorSpecName) }
        .getOrDefault(ColorSpec.SpecVersion.SPEC_2025)

    // Monet 变体（3/4/5）：强制跟随壁纸动态取色，忽略自定义 Key color
    // 非 Monet：keyColor=0 时跟随壁纸，否则使用 materialkolor 生成的种子色板
    val useWallpaper = keyColor == 0 || mode.isMonet
    val scheme = rememberKernelSUColorScheme(
        seedColor = if (useWallpaper) Color.Unspecified else Color(keyColor),
        isDark = dark,
        isAmoled = amoled,
        paletteStyle = paletteStyle,
        colorSpec = colorSpec
    )
    val animated = if (dynamicEffects) scheme.animateAsState() else scheme

    androidx.compose.runtime.CompositionLocalProvider(
        LocalColorMode provides colorMode,
        LocalEnableBlur provides true,
        LocalEnableDynamicEffects provides dynamicEffects
    ) {
        MaterialTheme(
            colorScheme = animated,
            content = content
        )
    }
}
