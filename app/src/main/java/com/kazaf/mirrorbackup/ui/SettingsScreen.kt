package com.kazaf.mirrorbackup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kazaf.mirrorbackup.core.AccentPresets
import com.kazaf.mirrorbackup.core.DISCLAIMER_TEXT
import com.kazaf.mirrorbackup.core.PartitionRules
import com.kazaf.mirrorbackup.core.ThemeConfig
import com.kazaf.mirrorbackup.core.ThemeState
import com.kazaf.mirrorbackup.core.fmtSize
import com.kazaf.mirrorbackup.ui.components.CircularProgressRing
import com.kazaf.mirrorbackup.ui.components.LiquidGlassCard
import com.kazaf.mirrorbackup.ui.components.MiuiButton
import com.kazaf.mirrorbackup.ui.components.SectionTitle
import com.kazaf.mirrorbackup.ui.components.StatusBadge
import com.kazaf.mirrorbackup.ui.components.TerminalLog
import com.kazaf.mirrorbackup.ui.theme.MiuiColors
import com.kazaf.mirrorbackup.viewmodel.BackupViewModel
import com.kazaf.mirrorbackup.viewmodel.SettingsViewModel

/** Tab3：⚙️ 设置（检查更新 / 提取辅助模块 / 用户黑名单 / 关于） */
@Composable
fun SettingsScreen(settingsVm: SettingsViewModel, backupVm: BackupViewModel) {
    val logLines by settingsVm.log.lines.collectAsState()
    val checking = settingsVm.checking
    val busy = settingsVm.busy
    val latest = settingsVm.latest
    val moduleStatus = settingsVm.moduleStatus
    val progress = settingsVm.downloadProgress
    val label = settingsVm.downloadLabel
    val summary = settingsVm.installSummary

    LaunchedEffect(Unit) {
        backupVm.syncBlacklist()
        settingsVm.refreshModuleStatus()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        // ── 主题与外观（照搬 KernelSU / SukiSU 管理器主题设置） ──
        ThemeSection()

        // ── 版本与更新 ──
        LiquidGlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("📦 版本与更新")
            InfoLine("当前版本", "v${settingsVm.appVersion}")
            InfoLine(
                "最新版本",
                latest?.let { it.tag + " · " + it.name } ?: "未检查"
            )
            Spacer(Modifier.height(6.dp))
            if (settingsVm.appUpdateAvailable) {
                StatusBadge("🆕 App 有新版本可用", MiuiColors.Blue)
                Spacer(Modifier.height(4.dp))
            }
            if (settingsVm.moduleUpdateAvailable) {
                StatusBadge("🧩 提取辅助模块有新版本", MiuiColors.Purple)
                Spacer(Modifier.height(4.dp))
            }
            settingsVm.lastError?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
            }
            if (busy || checking) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressRing(
                        pct = progress,
                        size = 56.dp,
                        stroke = 6.dp,
                        indeterminate = checking
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (label.isNotBlank()) label else "处理中…",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            settingsVm.installSummary?.let {
                Text(it, fontSize = 13.sp, color = MiuiColors.Blue)
                Spacer(Modifier.height(6.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiuiButton(
                    text = "🔍 检查更新",
                    onClick = { settingsVm.checkUpdate() },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                )
                if (settingsVm.appUpdateAvailable || settingsVm.moduleUpdateAvailable) {
                    MiuiButton(
                        text = "🚀 一键更新（只装一次）",
                        onClick = { settingsVm.oneClickUpdate() },
                        enabled = !busy,
                        modifier = Modifier.weight(1.4f)
                    )
                }
            }
            Text(
                "更新仅在你点击检查时访问 GitHub；App 更新只装 APK，模块更新会先装模块再装 APK（一次完成）。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))

        // ── 提取辅助模块 ──
        LiquidGlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("🧩 提取辅助模块（Magisk / KernelSU）")
            val st = moduleStatus
            if (st == null) {
                Text("正在检测…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (!st.installed) {
                Text("未安装", fontSize = 13.sp, color = MiuiColors.Orange)
            } else {
                InfoLine("模块版本", "${st.version} (${st.versionCode})")
                InfoLine(
                    "静态二进制",
                    if (st.binariesReady) "✅ img2simg / simg2img 可用（备份时自动优先使用）"
                    else "⚠ 已安装但二进制未生效（需重启设备）"
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "模块提供 AOSP libsparse 静态编译的 img2simg/simg2img，加速稀疏镜像转换。\n" +
                    "自带 update.json，可在 Magisk / KernelSU 管理器内直接检查并升级模块。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))

        // ── 用户黑名单管理 ──
        LiquidGlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("⛔ 我的黑名单（由你决定，应用不做任何隐性排除）")
            Text(
                "已加入黑名单 ${backupVm.userBlacklist.size} 个分区。勾选 = 加入黑名单；取消 = 移出黑名单。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            if (backupVm.partitions.isEmpty()) {
                Text(
                    "暂无分区数据（需 ROOT 权限）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(Modifier.height(220.dp)) {
                    items(backupVm.partitions) { part ->
                        val inBlack = part.name in backupVm.userBlacklist
                        val risky = PartitionRules.isRisky(part.name)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { backupVm.toggleBlacklist(part.name) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = inBlack,
                                onCheckedChange = { backupVm.toggleBlacklist(part.name) }
                            )
                            Text(
                                (if (risky) "⚠ " else "") + part.name,
                                fontSize = 13.sp,
                                color = if (inBlack) MiuiColors.Red else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                fmtSize(part.sizeBytes),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "⚠ 引导相关分区（boot/dtbo/vbmeta/vbmeta_system/recovery）仅作风险提示，是否备份完全由你决定。",
                fontSize = 11.sp,
                color = MiuiColors.Orange
            )
        }
        Spacer(Modifier.height(12.dp))

        // ── 关于 ──
        LiquidGlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("ℹ️ 关于")
            Text(
                "镜像备份 v${settingsVm.appVersion} · 作者 Kazaf\n" +
                    "GitHub: github.com/kazaf080726/MirrorBackup\n" +
                    "隐私：设备指纹仅用 SN + CPU ID，不采集 IMEI；除检查更新外全程离线。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                DISCLAIMER_TEXT,
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MiuiColors.Orange
            )
        }
        Spacer(Modifier.height(12.dp))

        SectionTitle("操作日志")
        TerminalLog(logLines, Modifier.fillMaxWidth(), height = 260.dp)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            Modifier.width(80.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = if (value.length > 24) FontFamily.Monospace else FontFamily.Default
        )
    }
}

/** 🎨 主题与外观 —— 照搬 KernelSU / SukiSU 管理器主题设置体系 */
@Composable
private fun ThemeSection() {
    val context = LocalContext.current
    val cfg = ThemeState.config
    fun upd(t: ThemeConfig.() -> ThemeConfig) {
        ThemeState.update(context, t)
    }
    val cs = MaterialTheme.colorScheme

    LiquidGlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("🎨 主题与外观")

        // ── Theme：颜色模式 ──
        Text("Theme（颜色模式）", fontSize = 12.sp, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChip("跟随系统", cfg.colorMode == "system") { upd { copy(colorMode = "system") } }
            ThemeChip("浅色", cfg.colorMode == "light") { upd { copy(colorMode = "light") } }
            ThemeChip("深色", cfg.colorMode == "dark") { upd { copy(colorMode = "dark") } }
            ThemeChip("AMOLED 黑", cfg.colorMode == "amoled") { upd { copy(colorMode = "amoled") } }
        }
        Spacer(Modifier.height(12.dp))

        // ── Color Spec：Monet 动态取色 ──
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("启用 Monet 配色", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Material You 动态取色（Android 12+）",
                    fontSize = 10.sp,
                    color = cs.onSurfaceVariant
                )
            }
            Switch(
                checked = cfg.monetEnabled,
                onCheckedChange = { upd { copy(monetEnabled = it) } },
                enabled = android.os.Build.VERSION.SDK_INT >= 31
            )
        }
        Spacer(Modifier.height(10.dp))

        // ── Key color：强调色板 + 自定义色调 ──
        Text("Key color（强调色）", fontSize = 12.sp, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        AccentPresets.LIST.chunked(5).forEach { rowPresets ->
            Row(
                Modifier.padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                rowPresets.forEach { preset ->
                    val selected = !cfg.monetEnabled && cfg.accent == preset.argb
                    Box(
                        Modifier
                            .size(30.dp)
                            .clickable { upd { copy(monetEnabled = false, accent = preset.argb) } },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .background(Color(preset.argb), CircleShape)
                        )
                        if (selected) {
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .border(2.dp, cs.onSurface, CircleShape)
                            )
                        }
                    }
                }
            }
        }
        val hue = remember(cfg.accent) {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(cfg.accent.toInt(), hsv)
            hsv[0]
        }
        Text(
            "自定义色调: ${"%.0f".format(hue)}°（Monet 关闭时生效）",
            fontSize = 11.sp,
            color = cs.onSurfaceVariant
        )
        Slider(
            value = hue,
            onValueChange = { h ->
                val argb = android.graphics.Color.HSVToColor(floatArrayOf(h, 0.72f, 1.0f))
                upd { copy(monetEnabled = false, accent = (0xFF000000L or (argb.toLong() and 0xFFFFFFL))) }
            },
            valueRange = 0f..360f
        )
        Spacer(Modifier.height(8.dp))

        // ── 液态玻璃 ──
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("液态玻璃", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Switch(checked = cfg.glassEnabled, onCheckedChange = { upd { copy(glassEnabled = it) } })
        }
        if (cfg.glassEnabled) {
            Text("玻璃强度（折射模糊半径）", fontSize = 11.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChip("弱", cfg.glassStrength == 0) { upd { copy(glassStrength = 0) } }
                ThemeChip("中", cfg.glassStrength == 1) { upd { copy(glassStrength = 1) } }
                ThemeChip("强", cfg.glassStrength == 2) { upd { copy(glassStrength = 2) } }
            }
            Text(
                "透明度 ${"%.0f".format(cfg.glassTintAlpha * 100)}%",
                fontSize = 11.sp,
                color = cs.onSurfaceVariant
            )
            Slider(
                value = cfg.glassTintAlpha,
                onValueChange = { upd { copy(glassTintAlpha = it) } },
                valueRange = 0.30f..0.72f
            )
            Text(
                "镜面高光 ${"%.0f".format(cfg.specularStrength * 100)}%",
                fontSize = 11.sp,
                color = cs.onSurfaceVariant
            )
            Slider(
                value = cfg.specularStrength,
                onValueChange = { upd { copy(specularStrength = it) } },
                valueRange = 0.3f..1.6f
            )
        }
        Spacer(Modifier.height(8.dp))

        // ── 圆角大小 ──
        Text("圆角大小", fontSize = 11.sp, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChip("14", cfg.cornerRadius == 14) { upd { copy(cornerRadius = 14) } }
            ThemeChip("18", cfg.cornerRadius == 18) { upd { copy(cornerRadius = 18) } }
            ThemeChip("22", cfg.cornerRadius == 22) { upd { copy(cornerRadius = 22) } }
            ThemeChip("28", cfg.cornerRadius == 28) { upd { copy(cornerRadius = 28) } }
        }
        Spacer(Modifier.height(10.dp))

        // ── 背景光晕 ──
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("背景光晕", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("蓝紫渐变光斑呼吸动画", fontSize = 10.sp, color = cs.onSurfaceVariant)
            }
            Switch(checked = cfg.glowEnabled, onCheckedChange = { upd { copy(glowEnabled = it) } })
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun ThemeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .clickable(onClick = onClick)
            .background(
                if (selected) cs.primary.copy(alpha = 0.16f) else cs.surface.copy(alpha = 0.6f),
                RoundedCornerShape(999.dp)
            )
            .border(
                1.dp,
                if (selected) cs.primary else cs.outline.copy(alpha = 0.4f),
                RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) cs.primary else cs.onSurfaceVariant
        )
    }
}
