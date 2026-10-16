package com.kazaf.mirrorbackup

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kazaf.mirrorbackup.core.ThemeState
import com.kazaf.mirrorbackup.ui.BackupScreen
import com.kazaf.mirrorbackup.ui.DisclaimerDialog
import com.kazaf.mirrorbackup.ui.RestoreScreen
import com.kazaf.mirrorbackup.ui.SettingsScreen
import com.kazaf.mirrorbackup.ui.components.LocalGlassCorner
import com.kazaf.mirrorbackup.ui.components.LocalGlassSpecular
import com.kazaf.mirrorbackup.ui.components.LocalGlassTintAlpha
import com.kazaf.mirrorbackup.ui.components.LocalLiquidGlassState
import com.kazaf.mirrorbackup.ui.glass.liquidGlassHost
import com.kazaf.mirrorbackup.ui.glass.rememberLiquidGlassState
import com.kazaf.mirrorbackup.ui.port.BlurredBar
import com.kazaf.mirrorbackup.ui.port.FloatingBottomBar
import com.kazaf.mirrorbackup.ui.port.FloatingBottomBarItem
import com.kazaf.mirrorbackup.ui.port.rememberBlurBackdrop
import com.kazaf.mirrorbackup.ui.theme.MiuiColors
import com.kazaf.mirrorbackup.ui.theme.MirrorBackupTheme
import com.kazaf.mirrorbackup.viewmodel.BackupViewModel
import com.kazaf.mirrorbackup.viewmodel.RestoreViewModel
import com.kazaf.mirrorbackup.viewmodel.SettingsViewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 镜像备份 —— 作者：Kazaf
 * UI 骨架照搬 SukiSU-Ultra：渐变背景 + 内容区模糊捕获（LayerBackdrop）
 * + 顶栏纹理模糊 + iOS 液态玻璃悬浮底栏（blur/vibrancy/lens/重力高光）+ 预测性返回。
 * 结构要点：玻璃表面（顶栏/底栏）必须是 backdrop 内容层的兄弟节点，
 * 否则会形成渲染树自引用循环（RenderThread 栈溢出）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeState.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            val theme = ThemeState.config
            MirrorBackupTheme(
                colorMode = theme.colorMode,
                monetEnabled = theme.monetEnabled,
                accent = Color(theme.accent)
            ) {
                App()
            }
        }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mirror_backup_prefs", Context.MODE_PRIVATE) }
    var agreed by remember { mutableStateOf(prefs.getBoolean("disclaimer_accepted", false)) }

    if (!agreed) {
        DisclaimerDialog(onAgree = {
            prefs.edit().putBoolean("disclaimer_accepted", true).apply()
            agreed = true
        })
        return
    }

    val backupVm: BackupViewModel = viewModel()
    val restoreVm: RestoreViewModel = viewModel()
    val settingsVm: SettingsViewModel = viewModel()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // 预测性返回：非首页返回首页
    BackHandler(enabled = tab != 0) { tab = 0 }

    LaunchedEffect(Unit) {
        backupVm.refreshEnvironment()
        settingsVm.refreshModuleStatus()
    }

    val themeCfg = ThemeState.config
    val glass = rememberLiquidGlassState()
    val density = LocalDensity.current.density

    // 位图玻璃强度
    LaunchedEffect(themeCfg.glassStrength) {
        glass.blurPx = when (themeCfg.glassStrength) {
            0 -> 1
            1 -> 3
            else -> 5
        }
        glass.refreshBackdrop()
    }

    // 全屏模糊捕获（玻璃表面采样的数据源）
    val enableBlur = themeCfg.glassEnabled
    val backdrop = rememberBlurBackdrop(enableBlur)
    val fallbackBackdrop = rememberLayerBackdrop { }

    CompositionLocalProvider(
        LocalLiquidGlassState provides glass,
        LocalGlassTintAlpha provides themeCfg.glassTintAlpha,
        LocalGlassSpecular provides themeCfg.specularStrength,
        LocalGlassCorner provides themeCfg.cornerRadius.dp
    ) {
        Box(Modifier.fillMaxSize()) {
            // ── 背景：渐变 + 呼吸光晕 ──
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

            // ── 主体：顶栏占位 + 内容区（内容区渲染进 backdrop 层）──
            Column(
                Modifier
                    .fillMaxSize()
                    .liquidGlassHost(glass, isSystemInDarkTheme(), density)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                Spacer(Modifier.height(84.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                ) {
                    when (tab) {
                        0 -> BackupScreen(backupVm, onOpenRestore = { tab = 1 })
                        1 -> RestoreScreen(restoreVm)
                        else -> SettingsScreen(settingsVm, backupVm)
                    }
                }
            }

            // ── 顶栏（兄弟节点采样 backdrop，安全无循环）──
            BlurredBar(
                backdrop,
                blurActive = enableBlur && backdrop != null,
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
                ),
            ) {
                Header(backupVm)
            }

            // ── iOS 液态玻璃悬浮底栏（照搬 SukiSU FloatingBottomBar）──
            FloatingBottomBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                selectedIndex = tab,
                onSelected = { tab = it },
                backdrop = backdrop ?: fallbackBackdrop,
                tabsCount = 3,
                isBlurEnabled = enableBlur && backdrop != null,
                accentColor = MaterialTheme.colorScheme.primary,
                tabContentColor = MaterialTheme.colorScheme.onSurface,
                surfaceContainer = MaterialTheme.colorScheme.surface,
                content = { activateTab ->
                    FloatingBottomBarItem(
                        selected = tab == 0,
                        onClick = { activateTab(0) },
                    ) {
                        Spacer(Modifier.size(4.dp))
                        Text("📤", fontSize = 22.sp)
                        Text("备份工作台", fontSize = 11.sp)
                    }
                    FloatingBottomBarItem(
                        selected = tab == 1,
                        onClick = { activateTab(1) },
                    ) {
                        Spacer(Modifier.size(4.dp))
                        Text("📥", fontSize = 22.sp)
                        Text("恢复工作台", fontSize = 11.sp)
                    }
                    FloatingBottomBarItem(
                        selected = tab == 2,
                        onClick = { activateTab(2) },
                    ) {
                        Spacer(Modifier.size(4.dp))
                        Text("⚙️", fontSize = 22.sp)
                        Text("设置", fontSize = 11.sp)
                    }
                }
            )
        }
    }
}

@Composable
private fun Header(backupVm: BackupViewModel) {
    val rc = backupVm.rootCheck
    Column(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "镜像备份",
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "作者：Kazaf · iOS 液态玻璃 · 多线程 · 仅存储",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val ok = rc?.rootOk == true
            val statusColor = when {
                rc == null -> MiuiColors.Orange
                ok -> MiuiColors.Green
                else -> MiuiColors.Red
            }
            Surface(
                onClick = { backupVm.refreshEnvironment() },
                shape = RoundedCornerShape(999.dp),
                color = statusColor.copy(alpha = 0.14f),
                border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.45f))
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(statusColor, CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            rc == null -> "ROOT 检测中…"
                            ok -> "ROOT ✓ · ${shortSuPath(rc.path)}"
                            else -> "ROOT ✗ 未授权"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor
                    )
                }
            }
        }
    }
}

private fun shortSuPath(path: String?): String {
    if (path == null) return "su"
    return when {
        path.contains("ksu") -> "KernelSU"
        path.contains("/ap/") || path.contains("apd") -> "APatch"
        path.contains("magisk") || path == "/data/adb/su" -> "Magisk"
        else -> path.removePrefix("/system/").removePrefix("/sbin/")
    }
}
