package com.kazaf.mirrorbackup

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kazaf.mirrorbackup.ui.BackupScreen
import com.kazaf.mirrorbackup.ui.DisclaimerDialog
import com.kazaf.mirrorbackup.ui.RestoreScreen
import com.kazaf.mirrorbackup.ui.components.SegmentedTab
import com.kazaf.mirrorbackup.ui.theme.MiuiColors
import com.kazaf.mirrorbackup.ui.theme.MirrorBackupTheme
import com.kazaf.mirrorbackup.viewmodel.BackupViewModel
import com.kazaf.mirrorbackup.viewmodel.RestoreViewModel

/**
 * 镜像备份 —— 作者：Kazaf
 * 已 ROOT 安卓设备本地分区镜像备份/恢复工具。全程离线，无任何联网权限。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MirrorBackupTheme {
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

    // 首次启动：免责声明弹窗，必须勾选同意才可进入
    if (!agreed) {
        DisclaimerDialog(onAgree = {
            prefs.edit().putBoolean("disclaimer_accepted", true).apply()
            agreed = true
        })
        return
    }

    val backupVm: BackupViewModel = viewModel()
    val restoreVm: RestoreViewModel = viewModel()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        backupVm.refreshEnvironment()
    }

    val dark = isSystemInDarkTheme()

    // 光影背景：渐变 + 两个缓慢呼吸的彩色光晕
    val glow = rememberInfiniteTransition(label = "glow")
    val glowAlpha by glow.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(tween(6500, easing = LinearEasing), RepeatMode.Reverse),
        label = "glowA"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        if (dark) Color(0xFF1B2A4A) else Color(0xFFDCEBFF),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // 左上蓝色光晕
        Box(
            Modifier
                .size(320.dp)
                .offset(x = (-140).dp, y = (-120).dp)
                .graphicsLayer { alpha = glowAlpha }
                .background(
                    Brush.radialGradient(listOf(MiuiColors.Blue.copy(alpha = 0.55f), Color.Transparent)),
                    CircleShape
                )
        )
        // 右下紫色光晕
        Box(
            Modifier
                .size(360.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 120.dp, y = 100.dp)
                .graphicsLayer { alpha = glowAlpha }
                .background(
                    Brush.radialGradient(listOf(MiuiColors.Purple.copy(alpha = 0.45f), Color.Transparent)),
                    CircleShape
                )
        )

        Column(Modifier.fillMaxSize()) {
            Header(backupVm)
            SegmentedTab(tab, { tab = it }, Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.size(10.dp))
            Box(Modifier.weight(1f)) {
                if (tab == 0) {
                    BackupScreen(backupVm, onOpenRestore = { tab = 1 })
                } else {
                    RestoreScreen(restoreVm)
                }
            }
        }
    }
}

@Composable
private fun Header(backupVm: BackupViewModel) {
    val rc = backupVm.rootCheck
    val dark = isSystemInDarkTheme()
    Column(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 10.dp)
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
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "作者：Kazaf · 离线 · 多线程 · 仅存储",
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
