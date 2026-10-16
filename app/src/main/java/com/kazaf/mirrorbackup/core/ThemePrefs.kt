package com.kazaf.mirrorbackup.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 主题配置 —— 照搬 KernelSU / SukiSU 管理器主题设置体系：
 * - Color Spec：Monet 动态取色 / 自定义 Key color
 * - Theme：跟随系统 / 浅色 / 深色 / AMOLED 黑
 * - Blur + 液态玻璃：开关、玻璃强度、透明度、镜面高光强度
 * - 圆角大小、背景光晕
 */
data class ThemeConfig(
    val colorMode: String = "system",        // system | light | dark | amoled
    val monetEnabled: Boolean = false,       // 启用 Monet 配色（Material You 动态取色）
    val accent: Long = 0xFF1677FF,           // ARGB 强调色（Key color，Monet 关闭时生效）
    val glassEnabled: Boolean = true,        // 液态玻璃总开关
    val glassStrength: Int = 2,              // 0=弱 1=中 2=强
    val glassTintAlpha: Float = 0.52f,       // 玻璃着色透明度 0.30~0.72
    val specularStrength: Float = 1.0f,      // 镜面高光强度 0.3~1.6
    val cornerRadius: Int = 22,              // dp：14 / 18 / 22 / 28
    val glowEnabled: Boolean = true          // 背景光晕
)

object ThemePrefs {

    private const val PREFS = "theme_config"

    fun load(context: Context): ThemeConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ThemeConfig(
            colorMode = p.getString("colorMode", "system") ?: "system",
            monetEnabled = p.getBoolean("monetEnabled", false),
            accent = p.getLong("accent", 0xFF1677FF),
            glassEnabled = p.getBoolean("glassEnabled", true),
            glassStrength = p.getInt("glassStrength", 2),
            glassTintAlpha = p.getFloat("glassTintAlpha", 0.52f),
            specularStrength = p.getFloat("specularStrength", 1.0f),
            cornerRadius = p.getInt("cornerRadius", 22),
            glowEnabled = p.getBoolean("glowEnabled", true)
        )
    }

    fun save(context: Context, cfg: ThemeConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("colorMode", cfg.colorMode)
            .putBoolean("monetEnabled", cfg.monetEnabled)
            .putLong("accent", cfg.accent)
            .putBoolean("glassEnabled", cfg.glassEnabled)
            .putInt("glassStrength", cfg.glassStrength)
            .putFloat("glassTintAlpha", cfg.glassTintAlpha)
            .putFloat("specularStrength", cfg.specularStrength)
            .putInt("cornerRadius", cfg.cornerRadius)
            .putBoolean("glowEnabled", cfg.glowEnabled)
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

    /** 玻璃强度 → 采样半径（像素） */
    fun glassBlurRadius(cfg: ThemeConfig): Float = when (cfg.glassStrength) {
        0 -> 10f
        1 -> 16f
        else -> 22f
    }
}

/** 强调色预设（对齐 KernelSU / SukiSU 管理器主题色板） */
object AccentPresets {
    data class Preset(val name: String, val argb: Long)

    val LIST = listOf(
        Preset("默认蓝", 0xFF1677FF),
        Preset("KSU蓝", 0xFF2196F3),
        Preset("青", 0xFF00BCD4),
        Preset("绿", 0xFF4CAF50),
        Preset("薄荷", 0xFF00B578),
        Preset("橙", 0xFFFF9800),
        Preset("琥珀", 0xFFFFC107),
        Preset("红", 0xFFF44336),
        Preset("粉", 0xFFE91E63),
        Preset("紫", 0xFF9C27B0),
        Preset("深紫", 0xFF7B5CFF),
        Preset("靛蓝", 0xFF3F51B5),
        Preset("棕", 0xFF795548),
        Preset("蓝灰", 0xFF607D8B),
        Preset("AMOLED橙", 0xFFFF6D00)
    )
}
