package com.kazaf.mirrorbackup.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kazaf.mirrorbackup.core.BackupRecord
import com.kazaf.mirrorbackup.core.LogLevel
import com.kazaf.mirrorbackup.core.LogLine
import com.kazaf.mirrorbackup.core.f
import com.kazaf.mirrorbackup.ui.glass.LiquidGlassState
import com.kazaf.mirrorbackup.ui.glass.liquidGlass
import com.kazaf.mirrorbackup.ui.theme.MiuiColors

/** 液态玻璃状态注入（由 MainActivity 的玻璃宿主提供） */
val LocalLiquidGlassState = staticCompositionLocalOf<LiquidGlassState?> { null }

/** 主题：玻璃着色透明度（0.30~0.72） */
val LocalGlassTintAlpha = staticCompositionLocalOf { 0.52f }

/** 主题：镜面高光强度（0.3~1.6） */
val LocalGlassSpecular = staticCompositionLocalOf { 1.0f }

/** 主题：圆角基准值 */
val LocalGlassCorner = staticCompositionLocalOf { 22.dp }
val LocalCardTintAlpha = staticCompositionLocalOf { 0.42f }
val LocalCardGlassEnabled = staticCompositionLocalOf { true }
val LocalCompactLayout = staticCompositionLocalOf { false }
val LocalUiStyle = staticCompositionLocalOf { "material" }

/** 液态玻璃卡片：背景折射 + 镜面高光 + 渐变边缘光（iOS 27 风格，随主题配置） */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    corner: Dp? = null,
    tintAlpha: Float? = null,
    specular: Color = Color.White,
    content: @Composable ColumnScope.() -> Unit
) {
    val glass = LocalLiquidGlassState.current
    val dark = isSystemInDarkTheme()
    val resolvedCorner = corner ?: LocalGlassCorner.current
    val cardGlassEnabled = LocalCardGlassEnabled.current
    val uiStyle = LocalUiStyle.current
    val resolvedTint = when {
        tintAlpha != null -> tintAlpha
        uiStyle == "maple" -> (LocalCardTintAlpha.current * 0.85f)
        else -> LocalCardTintAlpha.current
    }
    val specularStrength = LocalGlassSpecular.current
    val shape = RoundedCornerShape(resolvedCorner)
    val m = modifier.shadow(
        16.dp, shape,
        spotColor = Color.Black.copy(alpha = if (dark) 0.4f else 0.12f)
    )
    if (glass != null && cardGlassEnabled) {
        Box(
            m.liquidGlass(
                glass, shape, resolvedTint, specular, specularStrength, dark, resolvedCorner
            )
        ) {
            Column(Modifier.padding(if (LocalCompactLayout.current) 12.dp else 16.dp), content = content)
        }
    } else {
        Surface(
            m, shape = shape,
            color = if (uiStyle == "maple")
                (if (dark) Color(0xFF1E1730) else Color(0xFFF7F2FF)).copy(alpha = if (dark) 0.75f else 0.82f)
            else MaterialTheme.colorScheme.surface.copy(alpha = if (dark) 0.72f else 0.8f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = if (dark) 0.15f else 0.5f))
        ) {
            Column(Modifier.padding(if (LocalCompactLayout.current) 12.dp else 16.dp), content = content)
        }
    }
}

/** 兼容旧名 */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    LiquidGlassCard(modifier = modifier, content = content)
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

/** 悬浮操作栏（液态玻璃 + 主色光晕投影） */
@Composable
fun FloatingDock(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val glass = LocalLiquidGlassState.current
    val dark = isSystemInDarkTheme()
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(LocalGlassCorner.current + 4.dp)
    val m = modifier.shadow(
        20.dp, shape,
        spotColor = cs.primary.copy(alpha = 0.22f),
        ambientColor = Color.Black.copy(alpha = if (dark) 0.4f else 0.12f)
    )
    if (glass != null) {
        Box(
            m.liquidGlass(
                glass, shape, LocalGlassTintAlpha.current + 0.08f,
                Color.White, LocalGlassSpecular.current, dark, LocalGlassCorner.current + 4.dp
            )
        ) {
            Row(
                Modifier
                    .padding(10.dp)
                    .defaultMinSize(minHeight = 54.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
    } else {
        Surface(m, shape = shape, color = cs.surface.copy(alpha = if (dark) 0.88f else 0.94f)) {
            Row(
                Modifier
                    .padding(10.dp)
                    .defaultMinSize(minHeight = 54.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
    }
}

/** 圆形进度环（圆圈加载动画），中心显示百分比与副标题 */
@Composable
fun CircularProgressRing(
    pct: Float,
    modifier: Modifier = Modifier,
    size: Dp = 92.dp,
    stroke: Dp = 8.dp,
    sub: String = "",
    indeterminate: Boolean = false
) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (indeterminate) {
            CircularProgressIndicator(
                modifier = Modifier.fillMaxSize(),
                strokeWidth = stroke,
                color = cs.primary,
                trackColor = cs.outline.copy(alpha = 0.25f)
            )
        } else {
            CircularProgressIndicator(
                progress = { pct.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxSize(),
                strokeWidth = stroke,
                color = cs.primary,
                trackColor = cs.outline.copy(alpha = 0.25f)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (indeterminate) "…" else f("%.0f%%", pct.coerceIn(0f, 1f) * 100),
                fontSize = (size.value * 0.24f).sp,
                fontWeight = FontWeight.Bold,
                color = cs.onSurface
            )
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    fontSize = (size.value * 0.085f).sp,
                    color = cs.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/** 日志窗内的进度条：渐变填充 + 流光动效 + 速度/剩余时间 */
@Composable
private fun TerminalProgressBar(fraction: Float, label: String, detail: String) {
    val animFraction by androidx.compose.animation.core.animateFloatAsState(
        targetValue = fraction,
        animationSpec = androidx.compose.animation.core.tween(420),
        label = "log_progress"
    )
    // 有限流光（避免无限动画阻塞无障碍/测试）
    val shimmer = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        repeat(5) {
            shimmer.animateTo(1f, androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.LinearEasing))
            shimmer.snapTo(0f)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.03f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MiuiColors.TerminalInfo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${(animFraction * 100).toInt()}%",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MiuiColors.TerminalProgress
            )
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(animFraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF3E8BFF), Color(0xFF6FC3FF), Color(0xFF9AE6FF))
                        )
                    )
            ) {
                // 流光扫过
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.35f)
                        .offset(x = with(androidx.compose.ui.platform.LocalDensity.current) { (shimmer.value * 420).dp })
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent)
                            )
                        )
                )
            }
        }
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                detail,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF6B7C90),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** TWRP 风格终端日志窗口（带标题栏 + 进度条动效，自动滚动到底部） */
@Composable
fun TerminalLog(
    lines: List<LogLine>,
    modifier: Modifier = Modifier,
    height: Dp = 300.dp,
    progressFraction: Float? = null,
    progressLabel: String = "",
    progressDetail: String = ""
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    val dark = isSystemInDarkTheme()
    Surface(
        modifier = modifier
            .height(height)
            .shadow(14.dp, RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = if (dark) 0.5f else 0.15f)),
        shape = RoundedCornerShape(18.dp),
        color = MiuiColors.TerminalBg,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.04f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TerminalDot(Color(0xFFFF5F57))
                Spacer(Modifier.width(5.dp))
                TerminalDot(Color(0xFFFFBD2E))
                Spacer(Modifier.width(5.dp))
                TerminalDot(Color(0xFF28C840))
                Spacer(Modifier.width(10.dp))
                Text(
                    "REALTIME LOG · TWRP STYLE",
                    fontSize = 9.sp,
                    letterSpacing = 1.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF5A6B7E)
                )
            }
            // ── 进度条（阶段 + 百分比 + 速度 + 动效流光）──
            if (progressFraction != null) {
                TerminalProgressBar(
                    fraction = progressFraction.coerceIn(0f, 1f),
                    label = progressLabel,
                    detail = progressDetail
                )
            }
            Box(Modifier.weight(1f)) {
                if (lines.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "— 日志输出将显示在这里 —",
                            color = Color(0xFF3A4A5A),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    LazyColumn(state = listState, contentPadding = PaddingValues(12.dp)) {
                        items(lines, key = { it.id }) { line ->
                            Text(
                                text = "[${line.time}] ${line.text}",
                                color = when (line.level) {
                                    LogLevel.OK -> MiuiColors.TerminalOk
                                    LogLevel.WARN -> MiuiColors.TerminalWarn
                                    LogLevel.ERROR -> MiuiColors.TerminalErr
                                    LogLevel.PROGRESS -> MiuiColors.TerminalProgress
                                    LogLevel.INFO -> MiuiColors.TerminalText
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalDot(color: Color) {
    Box(
        Modifier
            .size(8.dp)
            .background(color, CircleShape)
    )
}

/** 液态玻璃分段导航（支持任意数量 Tab） */
@Composable
fun SegmentedTab(
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalLiquidGlassState.current
    val dark = isSystemInDarkTheme()
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(LocalGlassCorner.current - 4.dp)
    val m = modifier
        .fillMaxWidth()
        .shadow(12.dp, shape, spotColor = cs.primary.copy(alpha = 0.16f))
    if (glass != null) {
        Box(
            m.liquidGlass(
                glass, shape, LocalGlassTintAlpha.current - 0.02f,
                Color.White, LocalGlassSpecular.current, dark, LocalGlassCorner.current - 4.dp
            )
        ) {
            Row(Modifier.padding(4.dp)) {
                items.forEachIndexed { index, text ->
                    TabItem(text, index, selected, onSelect, Modifier.weight(1f))
                }
            }
        }
    } else {
        Surface(m, shape = shape, color = cs.surface.copy(alpha = if (dark) 0.8f else 0.9f)) {
            Row(Modifier.padding(4.dp)) {
                items.forEachIndexed { index, text ->
                    TabItem(text, index, selected, onSelect, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TabItem(
    text: String,
    index: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(), label = "tab")
    Surface(
        onClick = { onSelect(index) },
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale },
        interactionSource = interaction,
        shape = RoundedCornerShape(14.dp),
        color = if (selected == index) cs.primary else Color.Transparent
    ) {
        Box(Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                fontSize = 13.sp,
                fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal,
                color = if (selected == index) Color.White else cs.onSurfaceVariant
            )
        }
    }
}

/** 主按钮（渐变玻璃质感 + 按压回弹） */
@Composable
fun MiuiButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false
) {
    val cs = MaterialTheme.colorScheme
    val bg = if (danger) MiuiColors.Red else cs.primary
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(), label = "btn")
    Button(
        onClick = onClick,
        modifier = modifier
            .height(48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = bg,
            contentColor = Color.White,
            disabledContainerColor = bg.copy(alpha = 0.35f),
            disabledContentColor = Color.White.copy(alpha = 0.8f)
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 5.dp,
            pressedElevation = 1.dp
        )
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 状态徽章（玻璃胶囊） */
@Composable
fun StatusBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 备份模式选择卡片（2×2 网格单元，玻璃 + 按压回弹） */
@Composable
fun ModeCard(
    selected: Boolean,
    emoji: String,
    title: String,
    desc: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalLiquidGlassState.current
    val dark = isSystemInDarkTheme()
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(LocalGlassCorner.current)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.965f else 1f, spring(), label = "mode")
    val m = modifier
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .shadow(
            if (selected) 12.dp else 4.dp,
            shape,
            spotColor = accent.copy(alpha = 0.3f)
        )
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)

    if (glass != null) {
        Box(
            m.liquidGlass(
                glass, shape,
                tintAlpha = if (selected) LocalGlassTintAlpha.current + 0.08f else LocalGlassTintAlpha.current - 0.10f,
                specular = if (selected) accent else Color.White,
                specularStrength = LocalGlassSpecular.current,
                dark = dark,
                corner = LocalGlassCorner.current
            )
        ) {
            ModeCardContent(selected, emoji, title, desc, accent, cs)
            if (selected) {
                Box(
                    Modifier
                        .matchParentSize()
                        .border(1.8.dp, accent, shape)
                )
            }
        }
    } else {
        Surface(
            m, shape = shape,
            color = if (selected) accent.copy(alpha = 0.14f) else cs.surface.copy(alpha = 0.7f),
            border = BorderStroke(if (selected) 1.8.dp else 1.dp, if (selected) accent else cs.outline.copy(alpha = 0.35f))
        ) {
            ModeCardContent(selected, emoji, title, desc, accent, cs)
        }
    }
}

@Composable
private fun ModeCardContent(
    selected: Boolean,
    emoji: String,
    title: String,
    desc: String,
    accent: Color,
    cs: androidx.compose.material3.ColorScheme
) {
    Box {

        Box(Modifier.padding(14.dp)) {
        if (selected) {
            Surface(
                shape = CircleShape,
                color = accent,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Text(
                    "✓",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }
        Column {
            Text(emoji, fontSize = 24.sp)
            Text(
                text = title,
                modifier = Modifier.padding(top = 8.dp),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) accent else cs.onSurface
            )
            Text(
                text = desc,
                modifier = Modifier.padding(top = 3.dp),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = cs.onSurfaceVariant
            )
        }
        }
    }
}

/** 小选项卡片（格式 / 线程选择） */
@Composable
fun FormatChip(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalLiquidGlassState.current
    val dark = isSystemInDarkTheme()
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(), label = "chip")
    val m = modifier
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    if (glass != null) {
        Box(
            m.liquidGlass(
                glass, shape,
                tintAlpha = if (selected) 0.55f else 0.38f,
                specular = if (selected) cs.primary else Color.White,
                dark = dark,
                corner = 14.dp
            )
        ) {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) cs.primary else cs.onSurface
                )
                Text(desc, fontSize = 10.sp, color = cs.onSurfaceVariant)
            }
        }
    } else {
        Surface(
            m, shape = shape,
            color = if (selected) cs.primary.copy(alpha = 0.10f) else cs.surface.copy(alpha = 0.8f),
            border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) cs.primary else cs.outline.copy(alpha = 0.4f))
        ) {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) cs.primary else cs.onSurface
                )
                Text(desc, fontSize = 10.sp, color = cs.onSurfaceVariant)
            }
        }
    }
}

/** 备份/恢复记录卡片 */
@Composable
fun RecordsCard(
    title: String,
    records: List<BackupRecord>,
    modifier: Modifier = Modifier,
    onDelete: ((BackupRecord) -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    LiquidGlassCard(modifier = modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📋 $title", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val totalImages = records.sumOf { it.imageCount }
                Text(
                    "共 ${records.size} 次 · $totalImages 个镜像",
                    fontSize = 11.sp,
                    color = cs.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(if (expanded) "▴" else "▾", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(8.dp))
                if (records.isEmpty()) {
                    Text("暂无记录", fontSize = 12.sp, color = cs.onSurfaceVariant)
                } else {
                    records.take(6).forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(
                                r.time,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.weight(1.5f)
                            )
                            Text(r.mode, fontSize = 10.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(
                                "${r.imageCount}镜像",
                                fontSize = 10.sp,
                                modifier = Modifier.weight(0.6f)
                            )
                            Text(
                                if (r.packaged) "已打包" else "未打包",
                                fontSize = 10.sp,
                                color = if (r.packaged) MiuiColors.Green else MiuiColors.Orange,
                                modifier = Modifier.weight(0.7f)
                            )
                            if (onDelete != null) {
                                Text(
                                    "🗑",
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .clickable { onDelete(r) }
                                        .padding(horizontal = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 通用圆角形状辅助 */
val GlassShape: Shape = RoundedCornerShape(22.dp)
