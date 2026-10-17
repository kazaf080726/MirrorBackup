package com.kazaf.mirrorbackup.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 单线条黑白图标体系：全部为 24×24 网格设计、纯描边（无填充）、圆头圆角、
 * 颜色取 currentColor（默认跟随主题前置色），每个功能一个独立图形。
 */
private val iconColor: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface

@Composable
private fun Mono(
    size: Dp,
    color: Color,
    draw: DrawScope.(u: Float, w: Float, c: Color) -> Unit
) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val w = u * 1.9f
        draw(u, w, color)
    }
}

private fun DrawScope.ln(x1: Float, y1: Float, x2: Float, y2: Float, u: Float, w: Float, c: Color) =
    drawLine(c, Offset(u * x1, u * y1), Offset(u * x2, u * y2), strokeWidth = w, cap = StrokeCap.Round)

private fun DrawScope.pth(w: Float, c: Color, build: Path.() -> Unit) {
    val p = Path().apply { build() }
    drawPath(p, color = c, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.rc(x: Float, y: Float, wp: Float, hp: Float, u: Float, w: Float, c: Color) =
    drawRoundRect(
        color = c,
        topLeft = Offset(u * x, u * y),
        size = Size(u * wp, u * hp),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(u * 3f),
        style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

@Composable
fun IconBackup(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    ln(12f, 16f, 12f, 4f, u, w, c); pth(w, c) { moveTo(u * 8f, u * 8f); lineTo(u * 12f, u * 4f); lineTo(u * 16f, u * 8f) }
    pth(w, c) { moveTo(u * 4f, u * 14f); lineTo(u * 4f, u * 18f); lineTo(u * 20f, u * 18f); lineTo(u * 20f, u * 14f) }
}

@Composable
fun IconRestore(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    ln(12f, 4f, 12f, 16f, u, w, c); pth(w, c) { moveTo(u * 8f, u * 12f); lineTo(u * 12f, u * 16f); lineTo(u * 16f, u * 12f) }
    ln(4f, 20f, 20f, 20f, u, w, c)
}

@Composable
fun IconSettings(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    drawCircle(c, radius = u * 3.2f, center = Offset(u * 12f, u * 12f), style = Stroke(width = w))
    drawCircle(c, radius = u * 8.2f, center = Offset(u * 12f, u * 12f), style = Stroke(width = w))
    for (i in 0 until 8) {
        val a = Math.PI * 2 * i / 8
        val r1 = 8.2f; val r2 = 10.4f
        ln(
            (12f + r1 * Math.cos(a)).toFloat(), (12f + r1 * Math.sin(a)).toFloat(),
            (12f + r2 * Math.cos(a)).toFloat(), (12f + r2 * Math.sin(a)).toFloat(), u, w, c
        )
    }
}

@Composable
fun IconUnpack(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    rc(3f, 10f, 18f, 10f, u, w, c)
    ln(3f, 14f, 21f, 14f, u, w, c)
    ln(12f, 3f, 12f, 10f, u, w, c)
    pth(w, c) { moveTo(u * 9f, u * 6f); lineTo(u * 12f, u * 3f); lineTo(u * 15f, u * 6f) }
}

@Composable
fun IconLayers(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    pth(w, c) { moveTo(u * 12f, u * 3f); lineTo(u * 21f, u * 8f); lineTo(u * 12f, u * 13f); lineTo(u * 3f, u * 8f); close() }
    pth(w, c) { moveTo(u * 3f, u * 13f); lineTo(u * 12f, u * 18f); lineTo(u * 21f, u * 13f) }
}

@Composable
fun IconCopy(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    rc(4f, 4f, 11f, 14f, u, w, c)
    pth(w, c) { moveTo(u * 9f, u * 20f); lineTo(u * 19f, u * 20f); lineTo(u * 19f, u * 8f); lineTo(u * 15f, u * 8f) }
}

@Composable
fun IconHand(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    pth(w, c) {
        moveTo(u * 8f, u * 13f); lineTo(u * 8f, u * 6f)
        moveTo(u * 11f, u * 13f); lineTo(u * 11f, u * 4f)
        moveTo(u * 14f, u * 13f); lineTo(u * 14f, u * 5f)
        moveTo(u * 17f, u * 14f); lineTo(u * 17f, u * 9f)
        moveTo(u * 8f, u * 13f); cubicTo(u * 6f, u * 15f, u * 8f, u * 18f, u * 10f, u * 20f)
        lineTo(u * 15f, u * 20f); lineTo(u * 17f, u * 14f)
    }
}

@Composable
fun IconLock(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    rc(4f, 10f, 16f, 10f, u, w, c)
    pth(w, c) { moveTo(u * 8f, u * 10f); lineTo(u * 8f, u * 7f); cubicTo(u * 8f, u * 3f, u * 16f, u * 3f, u * 16f, u * 7f); lineTo(u * 16f, u * 10f) }
    drawCircle(c, radius = u * 1.4f, center = Offset(u * 12f, u * 14f), style = Stroke(width = w))
}

@Composable
fun IconCpu(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    rc(5f, 5f, 14f, 14f, u, w, c)
    rc(9f, 9f, 6f, 6f, u, w, c)
    for (p in listOf(8f, 12f, 16f)) { ln(p, 2f, p, 5f, u, w, c); ln(p, 19f, p, 22f, u, w, c); ln(2f, p, 5f, p, u, w, c); ln(19f, p, 22f, p, u, w, c) }
}

@Composable
fun IconDisc(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    drawCircle(c, radius = u * 8.5f, center = Offset(u * 12f, u * 12f), style = Stroke(width = w))
    drawCircle(c, radius = u * 2f, center = Offset(u * 12f, u * 12f), style = Stroke(width = w))
    pth(w, c) { moveTo(u * 15f, u * 6f); lineTo(u * 15f, u * 12f) }
}

@Composable
fun IconList(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    for (y in listOf(6f, 12f, 18f)) { drawCircle(c, radius = u * 1.1f, center = Offset(u * 5f, u * y), style = Stroke(width = w)); ln(9f, y, 20f, y, u, w, c) }
}

@Composable
fun IconTerminal(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    rc(3f, 4f, 18f, 16f, u, w, c)
    pth(w, c) { moveTo(u * 7f, u * 9f); lineTo(u * 10f, u * 12f); lineTo(u * 7f, u * 15f) }
    ln(13f, 15f, 17f, 15f, u, w, c)
}

@Composable
fun IconPalette(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    pth(w, c) { moveTo(u * 12f, u * 3f); cubicTo(u * 19f, u * 3f, u * 12f, u * 21f, u * 12f, u * 21f); cubicTo(u * 5f, u * 21f, u * 5f, u * 3f, u * 12f, u * 3f) }
    drawCircle(c, radius = u * 1.1f, center = Offset(u * 12f, u * 8f), style = Stroke(width = w))
    drawCircle(c, radius = u * 1.1f, center = Offset(u * 12f, u * 16f), style = Stroke(width = w))
}

@Composable
fun IconGlobe(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    drawCircle(c, radius = u * 8.5f, center = Offset(u * 12f, u * 12f), style = Stroke(width = w))
    pth(w, c) { moveTo(u * 3.5f, u * 12f); lineTo(u * 20.5f, u * 12f) }
    pth(w, c) { moveTo(u * 12f, u * 3.5f); cubicTo(u * 6f, u * 8f, u * 6f, u * 16f, u * 12f, u * 20.5f); cubicTo(u * 18f, u * 16f, u * 18f, u * 8f, u * 12f, u * 3.5f) }
}

@Composable
fun IconFolder(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    pth(w, c) { moveTo(u * 3f, u * 7f); lineTo(u * 3f, u * 19f); lineTo(u * 21f, u * 19f); lineTo(u * 21f, u * 8f); lineTo(u * 12f, u * 8f); lineTo(u * 10f, u * 5f); lineTo(u * 3f, u * 5f); close() }
}

@Composable
fun IconInfo(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    drawCircle(c, radius = u * 8.5f, center = Offset(u * 12f, u * 12f), style = Stroke(width = w))
    ln(12f, 11f, 12f, 17f, u, w, c)
    drawCircle(c, radius = u * 1.1f, center = Offset(u * 12f, u * 7.5f), style = Stroke(width = w))
}

@Composable
fun IconDownload(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    ln(12f, 3f, 12f, 15f, u, w, c)
    pth(w, c) { moveTo(u * 8f, u * 11f); lineTo(u * 12f, u * 15f); lineTo(u * 16f, u * 11f) }
    ln(4f, 20f, 20f, 20f, u, w, c)
}

@Composable
fun IconCheck(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    pth(w, c) { moveTo(u * 5f, u * 13f); lineTo(u * 10f, u * 18f); lineTo(u * 19f, u * 6f) }
}

@Composable
fun IconWarn(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    pth(w, c) { moveTo(u * 12f, u * 3f); lineTo(u * 21f, u * 20f); lineTo(u * 3f, u * 20f); close() }
    ln(12f, 9f, 12f, 14f, u, w, c)
    drawCircle(c, radius = u * 1f, center = Offset(u * 12f, u * 17f), style = Stroke(width = w))
}

@Composable
fun IconSearch(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    drawCircle(c, radius = u * 6.5f, center = Offset(u * 10.5f, u * 10.5f), style = Stroke(width = w))
    ln(15.5f, 15.5f, 20f, 20f, u, w, c)
}

@Composable
fun IconTrash(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    ln(5f, 7f, 19f, 7f, u, w, c)
    pth(w, c) { moveTo(u * 7f, u * 7f); lineTo(u * 8f, u * 20f); lineTo(u * 16f, u * 20f); lineTo(u * 17f, u * 7f) }
    pth(w, c) { moveTo(u * 10f, u * 7f); lineTo(u * 10f, u * 4f); lineTo(u * 14f, u * 4f); lineTo(u * 14f, u * 7f) }
}

@Composable
fun IconShield(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    pth(w, c) {
        moveTo(u * 12f, u * 3f); lineTo(u * 20f, u * 6f); lineTo(u * 20f, u * 12f)
        cubicTo(u * 20f, u * 17f, u * 16f, u * 20f, u * 12f, u * 21f)
        cubicTo(u * 8f, u * 20f, u * 4f, u * 17f, u * 4f, u * 12f)
        lineTo(u * 4f, u * 6f); close()
    }
}

@Composable
fun IconBox(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    pth(w, c) { moveTo(u * 12f, u * 3f); lineTo(u * 21f, u * 8f); lineTo(u * 21f, u * 16f); lineTo(u * 12f, u * 21f); lineTo(u * 3f, u * 16f); lineTo(u * 3f, u * 8f); close() }
    ln(3f, 8f, 12f, 13f, u, w, c)
    ln(12f, 13f, 21f, 8f, u, w, c)
    ln(12f, 13f, 12f, 21f, u, w, c)
}

@Composable
fun IconPuzzle(size: Dp = 22.dp, color: Color = iconColor) = Mono(size, color) { u, w, c ->
    pth(w, c) {
        moveTo(u * 4f, u * 6f); lineTo(u * 11f, u * 6f)
        cubicTo(u * 11f, u * 4f, u * 13f, u * 4f, u * 13f, u * 6f)
        lineTo(u * 20f, u * 6f); lineTo(u * 20f, u * 13f)
        cubicTo(u * 22f, u * 13f, u * 22f, u * 15f, u * 20f, u * 15f)
        lineTo(u * 20f, u * 20f); lineTo(u * 4f, u * 20f); close()
    }
}
