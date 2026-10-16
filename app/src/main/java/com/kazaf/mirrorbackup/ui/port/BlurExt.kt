// Adapted from SukiSU-Ultra (Apache 2.0) — blur backdrop helpers, using vendored Kyant0/AndroidLiquidGlass.
package com.kazaf.mirrorbackup.ui.port

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls

/** 创建捕获整屏内容的模糊背景层 */
@Composable
fun rememberBlurBackdrop(enableBlur: Boolean): LayerBackdrop? {
    if (!enableBlur) return null
    val surfaceColor = MaterialTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

/** 顶栏模糊（blur 25px + 表面色混合 + 轻微饱和） */
@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    blurActive: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val surfaceTint = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
    Box(
        modifier = modifier.then(
            if (blurActive && backdrop != null) {
                Modifier.drawBackdrop(
                    backdrop = backdrop,
                    shape = { RectangleShape },
                    effects = {
                        blur(25f)
                        colorControls(
                            brightness = 0f,
                            contrast = 1f,
                            saturation = 1.5f,
                        )
                    },
                )
            } else {
                Modifier
            }
        ),
    ) {
        if (blurActive && backdrop != null) {
            // matchParentSize：只覆盖顶栏自身范围，绝不外溢到全屏
            Box(
                Modifier
                    .matchParentSize()
                    .background(surfaceTint)
            )
        }
        content()
    }
}
