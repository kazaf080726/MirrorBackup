package com.kazaf.mirrorbackup.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 主题配置 —— 完整照搬 KernelSU / SukiSU 管理器主题设置体系：
 * - Theme（颜色模式）：跟随系统 / 浅色 / 深色 / AMOLED 黑 × Monet 自动取色（wallpaper）
 * - Key color：0 = 跟随壁纸（系统动态取色），其余 = materialkolor 种子色
 * - Color Style：materialkolor PaletteStyle（TONAL_SPOT / VIBRANT / EXPRESSIVE ...）
 * - Color Spec：ColorSpec.SpecVersion（SPEC_2021 / SPEC_2025）
 * - 液态玻璃：总开关、玻璃强度、着色透明度、镜面高光强度、圆角、背景光晕、卡片玻璃
 */
data class ThemeConfig(
    // ── KSU/SukiSU 原生主题字段 ──
    val colorMode: Int = COLOR_MODE_SYSTEM,     // 0..6，见 ColorMode 枚举（KSU 同款）
    val keyColor: Int = 0,                      // 0 = 跟随壁纸；其余 ARGB
    val paletteStyle: String = "TonalSpot",     // materialkolor PaletteStyle 名
    val colorSpec: String = "SPEC_2025",        // ColorSpec.SpecVersion 名
    // ── 液态玻璃（本应用增强，KSU 的 Blur / 液态玻璃对应项）──
    val glassEnabled: Boolean = true,           // 液态玻璃总开关（顶/底栏）
    val glassStrength: Int = 2,                 // 0=弱 1=中 2=强
    val glassTintAlpha: Float = 0.52f,          // 着色透明度 0.30~0.72
    val specularStrength: Float = 1.0f,         // 镜面高光 0.3~1.6
    val cardGlassEnabled: Boolean = true,       // 全部卡片应用液态玻璃
    val cardTintAlpha: Float = 0.42f,           // 卡片玻璃着色透明度
    val cornerRadius: Int = 22,                 // 圆角 14/18/22/28
    val glowEnabled: Boolean = true,            // 背景光晕
    val predictiveBackEnabled: Boolean = true,  // 预测性返回（KSU 同款开关）
    val dynamicEffects: Boolean = true,         // UI 动效开关
    val compactLayout: Boolean = false,         // 自适应紧凑排版（窄屏防重叠）
    val uiStyle: String = "material"            // UI 风格：material（KSU 蓝绿）/ maple（紫色玻璃）
) {
    companion object {
        const val COLOR_MODE_SYSTEM = 0
        const val COLOR_MODE_LIGHT = 1
        const val COLOR_MODE_DARK = 2
        const val COLOR_MODE_MONET_SYSTEM = 3
        const val COLOR_MODE_MONET_LIGHT = 4
        const val COLOR_MODE_MONET_DARK = 5
        const val COLOR_MODE_DARK_AMOLED = 6
    }
}

object ThemePrefs {

    private const val PREFS = "theme_config"

    private inline fun <T> safe(block: () -> T, fallback: T): T = try {
        block()
    } catch (e: Exception) {
        fallback
    }

    fun load(context: Context): ThemeConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 类型安全读取 + 旧版本迁移（colorMode 曾为字符串，accent 曾为 Long）
        val intMode = safe({ p.getInt("colorMode", Int.MIN_VALUE) }, Int.MIN_VALUE)
        val legacyMode = safe({ p.getString("colorMode", null) }, null)
        val migratedMode = if (intMode != Int.MIN_VALUE) {
            intMode.coerceIn(0, 6)
        } else when (legacyMode) {
            "light" -> 1
            "dark" -> 2
            "amoled" -> 6
            "system" -> 0
            null -> if (safe({ p.getBoolean("monetEnabled", false) }, false)) 3 else 0
            else -> 0
        }
        val intKey = safe({ p.getInt("keyColor", Int.MIN_VALUE) }, Int.MIN_VALUE)
        val migratedKey = if (intKey != Int.MIN_VALUE) {
            intKey
        } else {
            val legacyAccent = safe({ p.getLong("accent", 0L) }, 0L)
            if (legacyAccent == 0L) 0
            else if (safe({ p.getBoolean("monetEnabled", false) }, false)) 0
            else legacyAccent.toInt()
        }
        return ThemeConfig(
            colorMode = migratedMode,
            keyColor = migratedKey,
            paletteStyle = safe({ p.getString("paletteStyle", "TonalSpot") }, "TonalSpot") ?: "TonalSpot",
            colorSpec = safe({ p.getString("colorSpec", "SPEC_2025") }, "SPEC_2025") ?: "SPEC_2025",
            glassEnabled = safe({ p.getBoolean("glassEnabled", true) }, true),
            glassStrength = safe({ p.getInt("glassStrength", 2) }, 2).coerceIn(0, 2),
            glassTintAlpha = safe({ p.getFloat("glassTintAlpha", 0.52f) }, 0.52f),
            specularStrength = safe({ p.getFloat("specularStrength", 1.0f) }, 1.0f),
            cardGlassEnabled = safe({ p.getBoolean("cardGlassEnabled", true) }, true),
            cardTintAlpha = safe({ p.getFloat("cardTintAlpha", 0.42f) }, 0.42f),
            cornerRadius = safe({ p.getInt("cornerRadius", 22) }, 22),
            glowEnabled = safe({ p.getBoolean("glowEnabled", true) }, true),
            predictiveBackEnabled = safe({ p.getBoolean("predictiveBackEnabled", true) }, true),
            dynamicEffects = safe({ p.getBoolean("dynamicEffects", true) }, true),
            compactLayout = safe({ p.getBoolean("compactLayout", false) }, false),
            uiStyle = safe({ p.getString("uiStyle", "material") }, "material") ?: "material"
        )
    }

    fun save(context: Context, cfg: ThemeConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("colorMode", cfg.colorMode)
            .putInt("keyColor", cfg.keyColor)
            .putString("paletteStyle", cfg.paletteStyle)
            .putString("colorSpec", cfg.colorSpec)
            .putBoolean("glassEnabled", cfg.glassEnabled)
            .putInt("glassStrength", cfg.glassStrength)
            .putFloat("glassTintAlpha", cfg.glassTintAlpha)
            .putFloat("specularStrength", cfg.specularStrength)
            .putBoolean("cardGlassEnabled", cfg.cardGlassEnabled)
            .putFloat("cardTintAlpha", cfg.cardTintAlpha)
            .putInt("cornerRadius", cfg.cornerRadius)
            .putBoolean("glowEnabled", cfg.glowEnabled)
            .putBoolean("predictiveBackEnabled", cfg.predictiveBackEnabled)
            .putBoolean("dynamicEffects", cfg.dynamicEffects)
            .putBoolean("compactLayout", cfg.compactLayout)
            .putString("uiStyle", cfg.uiStyle)
            .apply()
    }
}

/** 全局主题状态（Compose 可观察，主线程访问） */
object ThemeState {
    var config by mutableStateOf(ThemeConfig())
        private set

    fun init(context: Context) {
        config = ThemePrefs.load(context)
    }

    fun update(context: Context, transform: (ThemeConfig) -> ThemeConfig) {
        val next = transform(config)
        config = next
        ThemePrefs.save(context, next)
    }

    /** 玻璃强度 → 位图模糊采样半径（像素） */
    fun glassBlurRadius(cfg: ThemeConfig): Float = when (cfg.glassStrength) {
        0 -> 10f
        1 -> 16f
        else -> 22f
    }
}

/** Key color 预设色板（完全照搬 KernelSU Colors.kt 的 keyColorOptions） */
object KeyColorOptions {
    val LIST: List<Int> = listOf(
        0xFFF44336.toInt(),
        0xFFE91E63.toInt(),
        0xFF9C27B0.toInt(),
        0xFF673AB7.toInt(),
        0xFF3F51B5.toInt(),
        0xFF2196F3.toInt(),
        0xFF00BCD4.toInt(),
        0xFF009688.toInt(),
        0xFF4FAF50.toInt(),
        0xFFFFEB3B.toInt(),
        0xFFFFC107.toInt(),
        0xFFFF9800.toInt(),
        0xFF795548.toInt(),
        0xFF607D8F.toInt(),
        0xFFFF9CA8.toInt()
    )
}
