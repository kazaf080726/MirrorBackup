package com.kazaf.mirrorbackup.ui.glass

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement

/**
 * iOS 27 液态玻璃（Liquid Glass）—— 纯 Compose + android.graphics 实现，零第三方依赖。
 *
 * 稳定性设计：不依赖 GraphicsLayer 嵌套录制 / GPU RenderEffect
 * （个别 OEM hwui 在这些路径上存在原生崩溃）。
 * 背景（渐变 + 光晕）由 android.graphics.Canvas 预渲染为 1/8 尺寸位图，
 * 再经纯软件盒式模糊处理；玻璃表面实时截取位图对应区域放大绘制，
 * 叠加半透明着色、顶部镜面高光与渐变边缘光 —— 与普通图片渲染同一路径。
 */
@Stable
class LiquidGlassState {

    @Volatile
    var backdrop: ImageBitmap? = null

    @Volatile
    var hostCoordinates: LayoutCoordinates? = null

    @Volatile
    var hostSize: IntSize = IntSize.Zero

    /** 背景位图缩小倍数（越大越省，模糊等效半径越大） */
    val blurScale: Int = 8

    var darkTint: Color = Color(0xFF12161E)
    var lightTint: Color = Color(0xFFF6F9FF)

    private var lastDark: Boolean? = null

    /** 预渲染模糊背景（纯 android.graphics，主线程执行仅数毫秒） */
    fun rebuildBackdrop(widthPx: Int, heightPx: Int, dark: Boolean, density: Float) {
        if (widthPx <= 0 || heightPx <= 0) return
        val bw = maxOf(1, widthPx / blurScale)
        val bh = maxOf(1, heightPx / blurScale)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)

        // 1. 垂直渐变背景
        val top = if (dark) 0xFF1B2A4A.toInt() else 0xFFDCEBFF.toInt()
        val bottom = if (dark) 0xFF10141A.toInt() else 0xFFF4F6FA.toInt()
        c.drawRect(
            0f, 0f, bw.toFloat(), bh.toFloat(),
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, bh.toFloat(), top, bottom, Shader.TileMode.CLAMP
                )
            }
        )

        // 2. 光晕（与 Compose 布局坐标一致，按 1/scale 缩放）
        fun glow(centerXPx: Float, centerYPx: Float, radiusPx: Float, color: Int) {
            val s = 1f / blurScale
            c.drawCircle(
                centerXPx * s, centerYPx * s, radiusPx * s,
                Paint().apply {
                    shader = RadialGradient(
                        centerXPx * s, centerYPx * s, radiusPx * s,
                        color, 0x00000000, Shader.TileMode.CLAMP
                    )
                }
            )
        }
        val glowAlpha = 0x3D // ~0.24
        glow(20f * density, 40f * density, 160f * density, (glowAlpha shl 24) or 0x1677FF)
        glow(
            widthPx - 60f * density, heightPx - 80f * density, 180f * density,
            (glowAlpha shl 24) or 0x7B5CFF
        )
        glow(
            70f * density, heightPx / 2f + 250f * density, 150f * density,
            ((glowAlpha * 3 / 5) shl 24) or 0x00B578
        )

        // 3. 软件盒式模糊（两轮，半径 2）
        boxBlur(bmp, 2)
        boxBlur(bmp, 2)
        backdrop = bmp.asImageBitmap()
        lastDark = dark
    }

    fun ensureBackdrop(widthPx: Int, heightPx: Int, dark: Boolean, density: Float) {
        if (backdrop == null || lastDark != dark ||
            hostSize.width != widthPx || hostSize.height != heightPx
        ) {
            hostSize = IntSize(widthPx, heightPx)
            rebuildBackdrop(widthPx, heightPx, dark, density)
        }
    }

    private fun boxBlur(bmp: Bitmap, radius: Int) {
        val w = bmp.width
        val h = bmp.height
        val pix = IntArray(w * h)
        bmp.getPixels(pix, 0, w, 0, 0, w, h)
        val tmp = IntArray(pix.size)

        // 水平
        for (y in 0 until h) {
            for (x in 0 until w) {
                var a = 0; var r = 0; var g = 0; var b = 0; var n = 0
                for (dx in -radius..radius) {
                    val xx = (x + dx).coerceIn(0, w - 1)
                    val c = pix[y * w + xx]
                    a += (c ushr 24) and 0xFF
                    r += (c ushr 16) and 0xFF
                    g += (c ushr 8) and 0xFF
                    b += c and 0xFF
                    n++
                }
                tmp[y * w + x] = (a / n shl 24) or (r / n shl 16) or (g / n shl 8) or (b / n)
            }
        }
        // 垂直（回写 pix）
        for (y in 0 until h) {
            for (x in 0 until w) {
                var a = 0; var r = 0; var g = 0; var b = 0; var n = 0
                for (dy in -radius..radius) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    val c = tmp[yy * w + x]
                    a += (c ushr 24) and 0xFF
                    r += (c ushr 16) and 0xFF
                    g += (c ushr 8) and 0xFF
                    b += c and 0xFF
                    n++
                }
                pix[y * w + x] = (a / n shl 24) or (r / n shl 16) or (g / n shl 8) or (b / n)
            }
        }
        bmp.setPixels(pix, 0, w, 0, 0, w, h)
    }
}

@Composable
fun rememberLiquidGlassState(): LiquidGlassState = remember { LiquidGlassState() }

/** 玻璃宿主：记录布局尺寸并生成模糊背景快照 */
fun Modifier.liquidGlassHost(
    state: LiquidGlassState,
    dark: Boolean,
    density: Float
): Modifier =
    this.onGloballyPositioned { coords ->
        state.hostCoordinates = coords
        state.ensureBackdrop(coords.size.width, coords.size.height, dark, density)
    }

/** 液态玻璃表面节点参数 */
private class LiquidGlassNodeElement(
    val state: LiquidGlassState,
    val shape: Shape,
    val tintAlpha: Float,
    val specular: Color,
    val dark: Boolean,
    val corner: Dp
) : ModifierNodeElement<LiquidGlassDrawNode>() {

    @Volatile
    var node: LiquidGlassDrawNode? = null

    override fun create(): LiquidGlassDrawNode =
        LiquidGlassDrawNode(state, shape, tintAlpha, specular, dark, corner).also { node = it }

    override fun update(n: LiquidGlassDrawNode) {
        n.state = state
        n.shape = shape
        n.tintAlpha = tintAlpha
        n.specular = specular
        n.dark = dark
        n.corner = corner
    }

    override fun hashCode(): Int {
        var h = state.hashCode()
        h = 31 * h + shape.hashCode()
        h = 31 * h + tintAlpha.hashCode()
        h = 31 * h + specular.hashCode()
        h = 31 * h + dark.hashCode()
        h = 31 * h + corner.hashCode()
        return h
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LiquidGlassNodeElement) return false
        return state === other.state && shape == other.shape &&
            tintAlpha == other.tintAlpha && specular == other.specular &&
            dark == other.dark && corner == other.corner
    }
}

private class LiquidGlassDrawNode(
    var state: LiquidGlassState,
    var shape: Shape,
    var tintAlpha: Float,
    var specular: Color,
    var dark: Boolean,
    var corner: Dp
) : androidx.compose.ui.Modifier.Node(), DrawModifierNode {

    /** 由外层 onGloballyPositioned 注入（LayoutCoordinates 实时有效，滚动时无需回调） */
    @Volatile
    var ownCoordinates: LayoutCoordinates? = null

    override fun ContentDrawScope.draw() {
        val path = outlineToPath(
            shape.createOutline(size, layoutDirection, this)
        )

        // 1. 背景折射：采样预渲染模糊背景位图的对应区域
        val host = state.hostCoordinates
        val own = ownCoordinates
        val bmp = state.backdrop
        if (host != null && own != null && host.isAttached && own.isAttached && bmp != null) {
            val local = try {
                host.localPositionOf(own, Offset.Zero)
            } catch (e: Exception) {
                null
            }
            if (local != null) {
                val s = state.blurScale
                val bw = bmp.width
                val bh = bmp.height
                val srcX = (local.x / s).toInt().coerceIn(0, bw - 1)
                val srcY = (local.y / s).toInt().coerceIn(0, bh - 1)
                val srcW = ((size.width / s).toInt() + 1).coerceAtMost(bw - srcX)
                val srcH = ((size.height / s).toInt() + 1).coerceAtMost(bh - srcY)
                if (srcW > 0 && srcH > 0) {
                    clipPath(path) {
                        drawImage(
                            image = bmp,
                            srcOffset = IntOffset(srcX, srcY),
                            srcSize = IntSize(srcW, srcH),
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                            filterQuality = FilterQuality.Low
                        )
                    }
                }
            }
        }

        // 2. 半透明玻璃基色 + 3. 顶部镜面高光
        val glass = if (dark) state.darkTint else state.lightTint
        clipPath(path) {
            drawRect(glass.copy(alpha = tintAlpha), topLeft = Offset.Zero, size = size)
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (dark) 0.15f else 0.5f),
                    0.3f to Color.White.copy(alpha = 0.05f),
                    1f to Color.Transparent
                ),
                topLeft = Offset.Zero,
                size = size
            )
        }

        // 4. 渐变边缘光（上亮下暗）
        val stroke = 1.2.dp.toPx()
        drawPath(
            path = path,
            brush = Brush.linearGradient(
                listOf(
                    specular.copy(alpha = if (dark) 0.4f else 0.9f),
                    specular.copy(alpha = 0.08f),
                    specular.copy(alpha = if (dark) 0.12f else 0.3f)
                )
            ),
            style = Stroke(width = stroke)
        )

        // 5. 内容
        clipPath(path) {
            this@draw.drawContent()
        }
    }

    private fun outlineToPath(outline: Outline): Path {
        val p = Path()
        when (outline) {
            is Outline.Rectangle -> p.addRect(outline.rect)
            is Outline.Rounded -> p.addRoundRect(outline.roundRect)
            is Outline.Generic -> p.addPath(outline.path)
        }
        return p
    }
}

/**
 * 液态玻璃表面：背景折射 + 镜面高光 + 边缘光。
 * 配合 [liquidGlassHost] 使用；不在宿主内（如对话框）时自动退化为纯玻璃质感。
 */
fun Modifier.liquidGlass(
    state: LiquidGlassState,
    shape: Shape,
    tintAlpha: Float = 0.5f,
    specular: Color = Color.White,
    dark: Boolean = false,
    corner: Dp = 22.dp
): Modifier {
    val element = LiquidGlassNodeElement(state, shape, tintAlpha, specular, dark, corner)
    return this
        .then(element)
        .onGloballyPositioned { element.node?.ownCoordinates = it }
}
