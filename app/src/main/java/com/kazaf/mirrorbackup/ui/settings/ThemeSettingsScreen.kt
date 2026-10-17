package com.kazaf.mirrorbackup.ui.settings

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kazaf.mirrorbackup.R
import com.kazaf.mirrorbackup.core.KeyColorOptions
import com.kazaf.mirrorbackup.core.ThemeConfig
import com.kazaf.mirrorbackup.core.ThemeState
import com.kazaf.mirrorbackup.ui.components.LiquidGlassCard
import com.kazaf.mirrorbackup.ui.components.SectionTitle
import com.kazaf.mirrorbackup.ui.theme.ColorMode
import com.kazaf.mirrorbackup.ui.theme.MiuiColors
import com.kazaf.mirrorbackup.ui.theme.displayName
import com.kazaf.mirrorbackup.ui.theme.rememberKernelSUColorScheme
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec

/**
 * 主题设置 —— 完整照搬 KernelSU / SukiSU Manager「主题设置」(ColorPaletteScreen)：
 * 实时预览卡、Key color 壁纸/15 色板、颜色模式（含 Monet 变体）、Color Style、Color Spec、
 * 预测性返回开关；并扩展液态玻璃（顶/底栏 + 卡片）、圆角、光晕、动效、自适应排版。
 */
@Composable
fun ThemeSettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val cfg = ThemeState.config
    fun update(t: ThemeConfig.() -> ThemeConfig) = ThemeState.update(context, t)
    val haptic = LocalHapticFeedback.current
    val configuration = LocalConfiguration.current
    val narrow = configuration.screenWidthDp < 400

    val mode = ColorMode.fromValue(cfg.colorMode)
    val isDark = mode.isDark
    val paletteStyle = runCatching { PaletteStyle.valueOf(cfg.paletteStyle) }
        .getOrDefault(PaletteStyle.TonalSpot)
    val colorSpec = runCatching { ColorSpec.SpecVersion.valueOf(cfg.colorSpec) }
        .getOrDefault(ColorSpec.SpecVersion.SPEC_2025)

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (narrow) 12.dp else 16.dp)
                .padding(top = 8.dp)
        ) {
            // ── 实时预览卡（KSU ThemePreviewCard）──
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                SectionTitle(stringResource(R.string.theme_entry_title))
                ThemePreviewCard(
                    keyColor = cfg.keyColor,
                    isDark = isDark,
                    isAmoled = mode.isAmoled,
                    paletteStyle = paletteStyle,
                    colorSpec = colorSpec
                )
            }
            Spacer(Modifier.height(10.dp))

            // ── Key color 色板 ──
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("Key color（强调色）")
                Text(
                    "第一个为「跟随壁纸」系统动态取色",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    contentPadding = PaddingValues(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        KeyColorButton(
                            color = Color.Unspecified,
                            isSelected = cfg.keyColor == 0,
                            isDark = isDark,
                            paletteStyle = paletteStyle,
                            colorSpec = colorSpec,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                update { copy(keyColor = 0, colorMode = monetModeFor(colorMode, true)) }
                            }
                        )
                    }
                    items(KeyColorOptions.LIST) { color ->
                        KeyColorButton(
                            color = Color(color),
                            isSelected = cfg.keyColor == color,
                            isDark = isDark,
                            paletteStyle = paletteStyle,
                            colorSpec = colorSpec,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                update { copy(keyColor = color, colorMode = monetModeFor(colorMode, false)) }
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            // ── UI 风格（KSU 蓝绿 / Maple 紫色玻璃）──
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("UI 风格")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("material" to "KSU 蓝绿", "maple" to "Maple 紫").forEach { (tag, label) ->
                        val selected = cfg.uiStyle == tag
                        Surface(
                            onClick = { update { copy(uiStyle = tag) } },
                            shape = RoundedCornerShape(999.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                            )
                        ) {
                            Text(
                                label,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Maple 紫 = 淡紫渐变背景 + 紫色光晕（参考 Maple检测 视觉）",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))

            // ── 颜色模式（含 Monet 变体，KSU ColorMode 全 7 项）──
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("Theme（颜色模式）")
                val options = listOf(
                    listOf(ColorMode.SYSTEM, ColorMode.MONET_SYSTEM) to "跟随系统",
                    listOf(ColorMode.LIGHT, ColorMode.MONET_LIGHT) to "浅色",
                    listOf(ColorMode.DARK, ColorMode.MONET_DARK) to "深色",
                    listOf(ColorMode.DARK_AMOLED) to "AMOLED"
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    options.forEach { (modes, label) ->
                        val checked = mode in modes
                        val icons = mapOf(
                            "跟随系统" to "🌗", "浅色" to "☀️", "深色" to "🌙", "AMOLED" to "⬛"
                        )
                        GlassChoiceButton(
                            label = label,
                            glyph = icons[label] ?: "•",
                            selected = checked,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                update { copy(colorMode = modes.first().value) }
                            }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (cfg.keyColor == 0) "当前：跟随壁纸动态取色（Monet）"
                    else "当前：Key color 种子色（materialkolor）",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))

            // ── Color Style / Color Spec（KSU 下拉项）──
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("配色规格")
                GlassDropdown(
                    title = "Color Style（配色风格）",
                    value = paletteStyle.displayName,
                    options = PaletteStyle.entries.map { it.displayName },
                    selectedIndex = PaletteStyle.entries.indexOf(paletteStyle).coerceAtLeast(0),
                    onSelect = { idx -> update { copy(paletteStyle = PaletteStyle.entries[idx].name) } }
                )
                Spacer(Modifier.height(8.dp))
                GlassDropdown(
                    title = "Color Spec（色板规格）",
                    value = colorSpec.name,
                    options = ColorSpec.SpecVersion.entries.map { it.name },
                    selectedIndex = ColorSpec.SpecVersion.entries.indexOf(colorSpec).coerceAtLeast(0),
                    onSelect = { idx -> update { copy(colorSpec = ColorSpec.SpecVersion.entries[idx].name) } }
                )
                if (ColorSpec.SpecVersion.entries.any { it.name == "SPEC_2025" }) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "SPEC_2025 仅对 均衡/中性/鲜艳/表现力 风格生效",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            // ── 液态玻璃（顶/底栏）──
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("液态玻璃（悬浮栏）")
                ToggleRow(
                    title = "液态玻璃效果",
                    subtitle = "顶栏模糊 + iOS 液态玻璃悬浮底栏",
                    checked = cfg.glassEnabled,
                    onCheckedChange = { update { copy(glassEnabled = it) } }
                )
                if (cfg.glassEnabled) {
                    Spacer(Modifier.height(6.dp))
                    Text("玻璃强度", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("弱", "中", "强").forEachIndexed { i, label ->
                            GlassChip(label, cfg.glassStrength == i) { update { copy(glassStrength = i) } }
                        }
                    }
                    LabeledSlider(
                        label = "着色透明度 ${"%.0f".format(cfg.glassTintAlpha * 100)}%",
                        value = cfg.glassTintAlpha,
                        range = 0.30f..0.72f,
                        onChange = { v -> update { copy(glassTintAlpha = v) } }
                    )
                    LabeledSlider(
                        label = "镜面高光 ${"%.0f".format(cfg.specularStrength * 100)}%",
                        value = cfg.specularStrength,
                        range = 0.3f..1.6f,
                        onChange = { v -> update { copy(specularStrength = v) } }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            // ── 卡片液态玻璃 ──
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("卡片液态玻璃")
                ToggleRow(
                    title = "全部卡片应用液态玻璃",
                    subtitle = "模式卡 / 记录 / 日志框 / 设置卡全部玻璃化",
                    checked = cfg.cardGlassEnabled,
                    onCheckedChange = { update { copy(cardGlassEnabled = it) } }
                )
                if (cfg.cardGlassEnabled) {
                    Spacer(Modifier.height(6.dp))
                    LabeledSlider(
                        label = "卡片着色透明度 ${"%.0f".format(cfg.cardTintAlpha * 100)}%",
                        value = cfg.cardTintAlpha,
                        range = 0.15f..0.65f,
                        onChange = { v -> update { copy(cardTintAlpha = v) } }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            // ── 圆角 / 光晕 / 动效 / 排版 ──
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("外观")
                Text("圆角大小", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(14, 18, 22, 28).forEach { r ->
                        GlassChip("${r}dp", cfg.cornerRadius == r) { update { copy(cornerRadius = r) } }
                    }
                }
                Spacer(Modifier.height(4.dp))
                ToggleRow(
                    title = "背景光晕",
                    subtitle = "渐变背景呼吸光斑",
                    checked = cfg.glowEnabled,
                    onCheckedChange = { update { copy(glowEnabled = it) } }
                )
                ToggleRow(
                    title = "动态效果",
                    subtitle = "主题渐变过渡 / 卡片入退场 / 进度动画",
                    checked = cfg.dynamicEffects,
                    onCheckedChange = { update { copy(dynamicEffects = it) } }
                )
                ToggleRow(
                    title = "紧凑排版",
                    subtitle = "窄屏自适应，防止元素重叠错位",
                    checked = cfg.compactLayout,
                    onCheckedChange = { update { copy(compactLayout = it) } }
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ToggleRow(
                        title = "预测性返回手势",
                        subtitle = "Android 14+ 返回动画（KSU 同款）",
                        checked = cfg.predictiveBackEnabled,
                        onCheckedChange = { update { copy(predictiveBackEnabled = it) } }
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun monetModeFor(current: Int, monet: Boolean): Int {
    val mode = ColorMode.fromValue(current)
    return if (monet) mode.toMonetMode().value else mode.toNonMonetMode().value
}

/** 实时预览卡 —— 照搬 KSU ThemePreviewCard（迷你手机样本） */
@Composable
private fun ThemePreviewCard(
    keyColor: Int,
    isDark: Boolean,
    isAmoled: Boolean,
    paletteStyle: PaletteStyle,
    colorSpec: ColorSpec.SpecVersion,
) {
    val configuration = LocalConfiguration.current
    val screenRatio = configuration.screenWidthDp.toFloat() /
        configuration.screenHeightDp.toFloat().coerceAtLeast(1f)
    val scheme = rememberKernelSUColorScheme(
        seedColor = if (keyColor == 0) Color.Unspecified else Color(keyColor),
        isDark = isDark,
        isAmoled = isAmoled,
        paletteStyle = paletteStyle,
        colorSpec = colorSpec
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.42f)
                .aspectRatio(screenRatio.coerceIn(0.42f, 0.62f)),
            color = scheme.surfaceContainer,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, scheme.outlineVariant)
        ) {
            Column {
                // 顶栏
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .background(scheme.surfaceContainerHigh),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        "镜像备份",
                        modifier = Modifier.padding(start = 8.dp),
                        fontSize = 8.sp,
                        color = scheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                // 内容
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 5.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(26.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(scheme.secondaryContainer)
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(scheme.surfaceBright)
                    )
                }
                // 底栏（悬浮胶囊示意）
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .background(scheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        repeat(3) { i ->
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (i == 0) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.35f))
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Key color 按钮 —— 照搬 KSU ColorButtonMaterial（双色弧 + 选中动效） */
@Composable
private fun KeyColorButton(
    color: Color,
    isSelected: Boolean,
    isDark: Boolean,
    paletteStyle: PaletteStyle,
    colorSpec: ColorSpec.SpecVersion,
    onClick: () -> Unit,
) {
    val scheme = rememberKernelSUColorScheme(
        seedColor = color,
        isDark = isDark,
        isAmoled = false,
        paletteStyle = paletteStyle,
        colorSpec = colorSpec
    )
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.08f else 1f,
        animationSpec = spring(),
        label = "keycolor_scale"
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = scheme.surfaceContainer,
        modifier = Modifier.size(62.dp)
    ) {
        Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(40.dp)) {
                drawArc(
                    color = scheme.primaryContainer,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = true
                )
                drawArc(
                    color = scheme.tertiaryContainer,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = true
                )
            }
            AnimatedVisibility(
                visible = isSelected,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f)
            ) {
                Box(
                    Modifier
                        .size(46.dp)
                        .border(2.dp, scheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(scheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✓", fontSize = 10.sp, color = scheme.onPrimary)
                    }
                }
            }
            if (!isSelected) {
                Box(
                    Modifier
                        .size(14.dp)
                        .background(scheme.primary, CircleShape)
                )
            }
        }
    }
}

/** 玻璃态选项按钮（颜色模式） */
@Composable
private fun GlassChoiceButton(
    label: String,
    glyph: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.96f,
        animationSpec = spring(),
        label = "choice_scale"
    )
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) cs.primary.copy(alpha = 0.18f) else cs.surface.copy(alpha = 0.45f)
            )
            .border(
                1.dp,
                if (selected) cs.primary else cs.outline.copy(alpha = 0.35f),
                RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(glyph, fontSize = 17.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) cs.primary else cs.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun GlassChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) cs.primary.copy(alpha = 0.16f) else cs.surface.copy(alpha = 0.45f))
            .border(1.dp, if (selected) cs.primary else cs.outline.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) cs.primary else cs.onSurfaceVariant
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Slider(value = value, onValueChange = onChange, valueRange = range)
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 玻璃态下拉选择 */
@Composable
private fun GlassDropdown(
    title: String,
    value: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Column {
        Text(title, fontSize = 12.sp, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(cs.surface.copy(alpha = 0.5f))
                    .border(1.dp, cs.outline.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(value, fontSize = 13.sp, color = cs.onSurface)
                Text("▾", fontSize = 13.sp, color = cs.onSurfaceVariant)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { i, opt ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                opt,
                                fontSize = 13.sp,
                                fontWeight = if (i == selectedIndex) FontWeight.Bold else FontWeight.Normal,
                                color = if (i == selectedIndex) cs.primary else cs.onSurface
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(i)
                        }
                    )
                }
            }
        }
    }
}
