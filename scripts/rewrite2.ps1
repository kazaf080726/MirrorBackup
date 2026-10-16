$ErrorActionPreference = 'Stop'
$enc = New-Object System.Text.UTF8Encoding($false)
$port = 'D:\Users\Administrator\Documents\DeepSeek Workspace\MirrorBackup\app\src\main\java\com\kazaf\mirrorbackup\ui\port'

Remove-Item -Recurse -Force "$port\miuix\effect" -ErrorAction SilentlyContinue

# 1. InteractiveHighlight: miuix shader guard -> android RuntimeShader direct
$f = "$port\miuix\animation\InteractiveHighlight.kt"
$c = [System.IO.File]::ReadAllText($f, [System.Text.Encoding]::UTF8).Replace("`r`n", "`n")
$c = $c.Replace("import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported`n", '')
$c = $c.Replace("import org.intellij.lang.annotations.Language`n", '')
$old = @'
    @Language("AGSL")
    private val shader =
        if (!isRuntimeShaderSupported()) null else RuntimeShader(
            """
    uniform float2 size;
    layout(color) uniform half4 color;
    uniform float radius;
    uniform float2 position;
    
    half4 main(float2 coord) {
        float dist = distance(coord, position);
        float intensity = smoothstep(radius, radius * 0.5, dist);
        return color * intensity;
    }"""
        )
'@
$new = @'
    // minSdk 33：AGSL RuntimeShader 始终可用
    private val shader: RuntimeShader = RuntimeShader(
        """
    uniform float2 size;
    layout(color) uniform half4 color;
    uniform float radius;
    uniform float2 position;

    half4 main(float2 coord) {
        float dist = distance(coord, position);
        float intensity = smoothstep(radius, radius * 0.5, dist);
        return color * intensity;
    }"""
    )
'@
$c = $c.Replace($old, $new)
$old2 = @'
                if (shader != null) {
                    shader.apply {
                        setFloatUniform("size", size.width, size.height)
                        setColorUniform("color", spotColor.toArgb())
                        setFloatUniform("radius", size.minDimension * 1.2f)
                        setFloatUniform("position", spotX, spotY)
                    }
                    drawRect(
                        ShaderBrush(shader),
                        blendMode = BlendMode.Plus
                    )
                } else {
                    // Pre-Android 13: no AGSL, use a radial gradient instead.
                    drawRect(
                        Brush.radialGradient(
                            0f to spotColor,
                            0.5f to spotColor,
                            1f to Color.Transparent,
                            center = Offset(spotX, spotY),
                            radius = radius,
                        ),
                        blendMode = BlendMode.Plus
                    )
                }
'@
$new2 = @'
                shader.apply {
                    setFloatUniform("size", size.width, size.height)
                    setColorUniform("color", spotColor.toArgb())
                    setFloatUniform("radius", size.minDimension * 1.2f)
                    setFloatUniform("position", spotX, spotY)
                }
                drawRect(
                    ShaderBrush(shader),
                    blendMode = BlendMode.Plus
                )
'@
$c = $c.Replace($old2, $new2)
[System.IO.File]::WriteAllText($f, $c, $enc)
"InteractiveHighlight OK"

# 2. BlurExt: textureBlur -> drawBackdrop + blur + colorControls
$f2 = "$port\BlurExt.kt"
$blurExt = @'
// Adapted from SukiSU-Ultra (Apache 2.0) — blur backdrop helpers, using vendored Kyant0/AndroidLiquidGlass.
package com.kazaf.mirrorbackup.ui.port

import androidx.compose.foundation.layout.Box
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
    content: @Composable () -> Unit,
) {
    Box(
        modifier = if (blurActive && backdrop != null) {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = RectangleShape,
                effects = {
                    blur(25f)
                    colorControls(
                        brightness = 0f,
                        contrast = 1f,
                        saturation = 1.5f,
                    )
                    drawRect(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                },
            )
        } else {
            Modifier
        },
    ) {
        content()
    }
}
'@
[System.IO.File]::WriteAllText($f2, $blurExt, $enc)
"BlurExt OK"

# 3. MainActivity: remove BgEffectBackground -> gradient + glow
$f3 = 'D:\Users\Administrator\Documents\DeepSeek Workspace\MirrorBackup\app\src\main\java\com\kazaf\mirrorbackup\MainActivity.kt'
$c3 = [System.IO.File]::ReadAllText($f3, [System.Text.Encoding]::UTF8).Replace("`r`n", "`n")
$c3 = $c3.Replace("import com.kazaf.mirrorbackup.ui.port.BgEffectBackground`n", '')
$c3 = $c3.Replace('import androidx.compose.foundation.background', "import androidx.compose.foundation.background`nimport androidx.compose.foundation.isSystemInDarkTheme")
$c3 = $c3.Replace('import androidx.compose.foundation.layout.Arrangement', "import androidx.compose.animation.core.Animatable`nimport androidx.compose.animation.core.LinearEasing`nimport androidx.compose.animation.core.animateTo`nimport androidx.compose.animation.core.tween`nimport androidx.compose.foundation.layout.Arrangement")
$c3 = $c3.Replace('import androidx.compose.foundation.layout.navigationBars', "import androidx.compose.foundation.layout.navigationBars`nimport androidx.compose.foundation.layout.offset")
$c3 = $c3.Replace('import androidx.compose.foundation.shape.CircleShape', "import androidx.compose.foundation.shape.CircleShape")
$c3 = $c3.Replace('import androidx.compose.ui.graphics.Color', "import androidx.compose.ui.graphics.Brush`nimport androidx.compose.ui.graphics.Color`nimport androidx.compose.ui.graphics.graphicsLayer")
$oldBg = @'
                BgEffectBackground(
                    dynamicBackground = themeCfg.glowEnabled,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .liquidGlassHost(glass, MaterialTheme.colorScheme.surface, density)
                                .windowInsetsPadding(WindowInsets.safeDrawing)
                        ) {
                            BlurredBar(backdrop, blurActive = enableBlur && backdrop != null) {
                                Header(backupVm)
                            }
                            Box(Modifier.weight(1f)) {
                                when (tab) {
                                    0 -> BackupScreen(backupVm, onOpenRestore = { tab = 1 })
                                    1 -> RestoreScreen(restoreVm)
                                    else -> SettingsScreen(settingsVm, backupVm)
                                }
                            }
                        }
                    }
                }
'@
$newBg = @'
                Box(Modifier.fillMaxSize()) {
                    // 光影渐变背景 + 呼吸光晕
                    val darkBg = isSystemInDarkTheme()
                    val glowAlpha = remember { Animatable(if (themeCfg.glowEnabled) 0.12f else 0f) }
                    LaunchedEffect(themeCfg.glowEnabled) {
                        if (themeCfg.glowEnabled) {
                            repeat(7) {
                                glowAlpha.animateTo(0.24f, tween(6500, easing = LinearEasing))
                                glowAlpha.animateTo(0.12f, tween(6500, easing = LinearEasing))
                            }
                            glowAlpha.animateTo(0.16f, tween(1500))
                        } else {
                            glowAlpha.snapTo(0f)
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        if (darkBg) Color(0xFF1B2A4A) else Color(0xFFDCEBFF),
                                        MaterialTheme.colorScheme.background
                                    )
                                )
                            )
                    )
                    if (themeCfg.glowEnabled) {
                        Box(
                            Modifier
                                .size(320.dp)
                                .offset(x = (-140).dp, y = (-120).dp)
                                .graphicsLayer { alpha = glowAlpha.value }
                                .background(
                                    Brush.radialGradient(listOf(MiuiColors.Blue.copy(alpha = 0.55f), Color.Transparent)),
                                    CircleShape
                                )
                        )
                        Box(
                            Modifier
                                .size(360.dp)
                                .align(Alignment.BottomEnd)
                                .offset(x = 120.dp, y = 100.dp)
                                .graphicsLayer { alpha = glowAlpha.value }
                                .background(
                                    Brush.radialGradient(listOf(MiuiColors.Purple.copy(alpha = 0.45f), Color.Transparent)),
                                    CircleShape
                                )
                        )
                        Box(
                            Modifier
                                .size(300.dp)
                                .align(Alignment.CenterStart)
                                .offset(x = (-80).dp, y = 400.dp)
                                .graphicsLayer { alpha = glowAlpha.value }
                                .background(
                                    Brush.radialGradient(listOf(MiuiColors.Green.copy(alpha = 0.28f), Color.Transparent)),
                                    CircleShape
                                )
                        )
                    }
                    Column(
                        Modifier
                            .fillMaxSize()
                            .liquidGlassHost(glass, MaterialTheme.colorScheme.surface, density)
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                    ) {
                        BlurredBar(backdrop, blurActive = enableBlur && backdrop != null) {
                            Header(backupVm)
                        }
                        Box(Modifier.weight(1f)) {
                            when (tab) {
                                0 -> BackupScreen(backupVm, onOpenRestore = { tab = 1 })
                                1 -> RestoreScreen(restoreVm)
                                else -> SettingsScreen(settingsVm, backupVm)
                            }
                        }
                    }
                }
'@
$c3 = $c3.Replace($oldBg, $newBg)
[System.IO.File]::WriteAllText($f3, $c3, $enc)
"MainActivity OK"
