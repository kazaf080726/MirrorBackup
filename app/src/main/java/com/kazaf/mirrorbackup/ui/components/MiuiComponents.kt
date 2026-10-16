package com.kazaf.mirrorbackup.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kazaf.mirrorbackup.core.BackupRecord
import com.kazaf.mirrorbackup.core.LogLevel
import com.kazaf.mirrorbackup.core.LogLine
import com.kazaf.mirrorbackup.core.f
import com.kazaf.mirrorbackup.ui.theme.MiuiColors

/**
 * 液态玻璃卡片：半透明表面 + 渐变描边 + 顶部高光 + 柔和投影，
 * 光影效果随系统深浅色主题自动调整。
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 22.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(corner)
    Surface(
        modifier = modifier.shadow(
            elevation = 14.dp,
            shape = shape,
            spotColor = Color.Black.copy(alpha = if (dark) 0.35f else 0.10f)
        ),
        shape = shape,
        color = Color.Transparent
    ) {
        Box {
            // 玻璃底
            Box(
                Modifier
                    .matchParentSize()
                    .background(cs.surface.copy(alpha = if (dark) 0.72f else 0.66f), shape)
            )
            // 渐变描边
            Box(
                Modifier
                    .matchParentSize()
                    .border(
                        1.dp,
                        Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = if (dark) 0.18f else 0.85f),
                                Color.White.copy(alpha = 0.03f),
                                Color.White.copy(alpha = if (dark) 0.08f else 0.45f)
                            )
                        ),
                        shape
                    )
            )
            // 顶部高光
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (dark) 0.05f else 0.13f),
                                Color.Transparent
                            )
                        ),
                        shape
                    )
            )
            Column(Modifier.padding(16.dp), content = content)
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

/** 悬浮操作栏：悬浮于内容之上，玻璃质感 + 主色光晕投影 */
@Composable
fun FloatingDock(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(26.dp)
    Surface(
        modifier = modifier.shadow(
            elevation = 18.dp,
            shape = shape,
            spotColor = cs.primary.copy(alpha = 0.20f),
            ambientColor = Color.Black.copy(alpha = if (dark) 0.4f else 0.12f)
        ),
        shape = shape,
        color = cs.surface.copy(alpha = if (dark) 0.86f else 0.92f),
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = if (dark) 0.22f else 0.9f),
                    Color.White.copy(alpha = 0.04f),
                    Color.White.copy(alpha = if (dark) 0.10f else 0.5f)
                )
            )
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
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = cs.onSurface
            )
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    fontSize = 9.sp,
                    color = cs.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/** TWRP 风格终端日志窗口（带标题栏，自动滚动到底部） */
@Composable
fun TerminalLog(
    lines: List<LogLine>,
    modifier: Modifier = Modifier,
    height: Dp = 300.dp
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    Surface(
        modifier = modifier.height(height),
        shape = RoundedCornerShape(18.dp),
        color = MiuiColors.TerminalBg,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Column {
            // 标题栏（信号灯风格）
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

/** 双 Tab 分段导航（玻璃悬浮） */
@Composable
fun SegmentedTab(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(18.dp), spotColor = cs.primary.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(18.dp),
        color = cs.surface.copy(alpha = if (dark) 0.8f else 0.9f),
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = if (dark) 0.18f else 0.8f),
                    Color.White.copy(alpha = 0.04f)
                )
            )
        )
    ) {
        Row(Modifier.padding(4.dp)) {
            TabItem("📤 备份工作台", 0, selected, onSelect, Modifier.weight(1f))
            TabItem("📥 恢复工作台", 1, selected, onSelect, Modifier.weight(1f))
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
    Surface(
        onClick = { onSelect(index) },
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (selected == index) cs.primary else Color.Transparent
    ) {
        Box(Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal,
                color = if (selected == index) Color.White else cs.onSurfaceVariant
            )
        }
    }
}

/** 主按钮（MIUI 风格圆角，支持危险红色态） */
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
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = bg,
            contentColor = Color.White,
            disabledContainerColor = bg.copy(alpha = 0.35f),
            disabledContentColor = Color.White.copy(alpha = 0.8f)
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 4.dp,
            pressedElevation = 1.dp
        )
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 状态徽章 */
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

/** 备份模式选择卡片（2×2 网格单元） */
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
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.shadow(
            if (selected) 10.dp else 3.dp,
            shape,
            spotColor = accent.copy(alpha = 0.25f)
        ),
        shape = shape,
        color = if (selected) accent.copy(alpha = 0.14f) else cs.surface.copy(alpha = 0.7f),
        border = BorderStroke(
            if (selected) 1.8.dp else 1.dp,
            if (selected) accent else cs.outline.copy(alpha = 0.35f)
        )
    ) {
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
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) cs.primary.copy(alpha = 0.10f) else cs.surface.copy(alpha = 0.8f),
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) cs.primary else cs.outline.copy(alpha = 0.4f)
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
}

/** 备份/恢复记录卡片 */
@Composable
fun RecordsCard(
    title: String,
    records: List<BackupRecord>,
    modifier: Modifier = Modifier
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
                        }
                    }
                }
            }
        }
    }
}

/** 通用圆角形状辅助 */
val GlassShape: Shape = RoundedCornerShape(22.dp)
