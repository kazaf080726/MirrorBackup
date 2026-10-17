package com.kazaf.mirrorbackup.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role

/**
 * KSU 状态卡 —— 完整复刻 KernelSU Manager (HomeMiuix.StatusCard)：
 * 底色 浅绿 #DFFAE4 / 深色 #1A3825；110dp 圆环勾选图标右下出血 offset(27,31)；
 * 标题 22sp SemiBold；版本 15sp；工作模式（LKM）16sp Medium 左下；
 * 按压 Tilt 反馈（缩放 + 倾角，Q 弹）。
 */
@Composable
fun KsuStatusCard(
    working: Boolean,
    title: String,
    versionLine: String,
    modeLabel: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val container = if (dark) Color(0xFF1A3825) else Color(0xFFDFFAE4)
    val content = if (dark) Color(0xFFE8F5EA) else Color(0xFF0E2A16)
    val ringColor = if (working) Color(0xFF36D167) else Color(0xFFE5484D)

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.32f, stiffness = 780f),
        label = "ksu_card_scale"
    )
    val tilt by animateFloatAsState(
        targetValue = if (pressed) -1.4f else 0f,
        animationSpec = spring(dampingRatio = 0.3f, stiffness = 620f),
        label = "ksu_card_tilt"
    )
    val shape = RoundedCornerShape(24.dp)

    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                rotationZ = tilt
            }
            .shadow(6.dp, shape, spotColor = Color.Black.copy(alpha = 0.12f))
            .clip(shape)
            .background(container)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
    ) {
        Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // 右下角出血大圆环（110dp，offset(27,31)）
            Box(
                Modifier
                    .fillMaxSize()
                    .offset(27.dp, 31.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                KsuStatusRing(ok = working, size = 110.dp, color = ringColor)
            }
            // 左下：工作模式
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp, 10.dp),
                contentAlignment = Alignment.BottomStart
            ) {
                Text(
                    modeLabel,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = content
                )
            }
            // 左上：标题 + 版本
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp, 14.dp),
                contentAlignment = Alignment.TopStart
            ) {
                Column {
                    Text(
                        title,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = content
                    )
                    Spacer(Modifier.height(1.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            versionLine,
                            fontSize = 15.sp,
                            color = content.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        }
    }
}

/** 圆环勾选图标（复刻 CheckCircleOutline：粗圆环 + 圆头对勾；失败时画叉） */
@Composable
fun KsuStatusRing(
    ok: Boolean,
    size: Dp,
    color: Color
) {
    Canvas(Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.085f
        val r = this.size.minDimension / 2f - stroke / 2f
        drawCircle(
            color = color,
            radius = r,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        if (ok) {
            // 对勾
            val path = Path().apply {
                val s = this@Canvas.size.minDimension
                moveTo(s * 0.30f, s * 0.52f)
                lineTo(s * 0.44f, s * 0.66f)
                lineTo(s * 0.72f, s * 0.36f)
            }
            drawPath(path, color = color, style = Stroke(width = stroke, cap = StrokeCap.Round))
        } else {
            // 交叉
            val s = this.size.minDimension
            drawLine(color, androidx.compose.ui.geometry.Offset(s * 0.34f, s * 0.34f), androidx.compose.ui.geometry.Offset(s * 0.66f, s * 0.66f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color, androidx.compose.ui.geometry.Offset(s * 0.66f, s * 0.34f), androidx.compose.ui.geometry.Offset(s * 0.34f, s * 0.66f), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}
