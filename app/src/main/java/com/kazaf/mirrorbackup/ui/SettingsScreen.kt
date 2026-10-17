package com.kazaf.mirrorbackup.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import com.kazaf.mirrorbackup.R
import com.kazaf.mirrorbackup.core.OutputStore
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
fun SettingsScreen(settingsVm: SettingsViewModel, backupVm: BackupViewModel, onOpenTheme: () -> Unit = {}) {
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
        settingsVm.loadDeviceEnv()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        // ── 主题与外观（照搬 KernelSU / SukiSU 管理器主题设置） ──
        LiquidGlassCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenTheme)
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(stringResource(R.string.theme_entry_title), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.theme_entry_desc),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))

        // ── 语言 / Language ──
        LiquidGlassCard(Modifier.fillMaxWidth()) {
            val act = LocalContext.current
            SectionTitle(stringResource(R.string.lang_title))
            var lang by remember { mutableStateOf(com.kazaf.mirrorbackup.core.LocaleHelper.current(act)) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                com.kazaf.mirrorbackup.core.LocaleHelper.OPTIONS.chunked(2).forEach { rowTags ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowTags.forEach { tag ->
                    val label = when (tag) {
                        "" -> stringResource(R.string.lang_system)
                        "zh-CN" -> stringResource(R.string.lang_zh_cn)
                        "zh-TW" -> stringResource(R.string.lang_zh_tw)
                        else -> stringResource(R.string.lang_en)
                    }
                    val selected = lang == tag
                    Surface(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            lang = tag
                            com.kazaf.mirrorbackup.core.LocaleHelper.setLanguage(act, tag)
                            (act as? android.app.Activity)?.recreate()
                        },
                        shape = RoundedCornerShape(999.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        )
                    ) {
                        Text(
                            label,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ── 存储位置（内部存储根目录「镜像备份」文件夹）──
        LiquidGlassCard(Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.storage_title))
            val ctx = LocalContext.current
            val allFiles = remember { mutableStateOf(OutputStore.hasAllFilesAccess()) }
            InfoLine(stringResource(R.string.storage_backup_dir), OutputStore.rootPath)
            InfoLine(stringResource(R.string.storage_session_dir), stringResource(R.string.storage_session_desc))
            Spacer(Modifier.height(4.dp))
            if (allFiles.value) {
                StatusBadge(stringResource(R.string.storage_granted), MiuiColors.Green)
            } else {
                StatusBadge(stringResource(R.string.storage_not_granted), MiuiColors.Orange)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiuiButton(
                    text = stringResource(if (allFiles.value) R.string.btn_recheck else R.string.btn_grant),
                    onClick = {
                        if (Environment.isExternalStorageManager()) {
                            allFiles.value = true
                        } else {
                            try {
                                ctx.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${ctx.packageName}")
                                    )
                                )
                            } catch (e: Exception) {
                                try {
                                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                } catch (_: Exception) {
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                MiuiButton(
                    text = stringResource(R.string.btn_open_folder),
                    onClick = {
                        try {
                            val uri = DocumentsContract.buildDocumentUri(
                                "com.android.externalstorage.documents",
                                "primary:${OutputStore.ROOT_NAME}"
                            )
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                    } catch (e: Exception) {
                        // 文件管理器不支持时打开系统「所有文件」设置页兜底
                        try {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        } catch (_: Exception) {
                        }
                    }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            MiuiButton(
                text = stringResource(R.string.btn_verify_latest),
                onClick = { settingsVm.verifyLatestBackup() },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(10.dp))

        // ── 设备环境（模块 mb_info 提供）──
        val env = settingsVm.deviceEnv
        if (env != null) {
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("📟 设备环境")
                InfoLine("设备型号", "${env["ro.product.manufacturer"] ?: ""} ${env["ro.product.model"] ?: "?"}")
                InfoLine("系统版本", "Android ${env["ro.build.version.release"] ?: "?"} / SDK ${env["ro.build.version.sdk"] ?: "?"}")
                InfoLine("安全补丁", env["ro.build.version.security_patch"] ?: "?")
                InfoLine("内核版本", env["kernel"] ?: "?")
                InfoLine("CPU / 架构", "${env["cpu_cores"] ?: "?"} 核 · ${env["arch"] ?: "?"}")
                InfoLine("A/B 槽位", env["ro.boot.slot_suffix"] ?: "none")
                InfoLine("数据来源", env["_src"] ?: "shell")
            }
            Spacer(Modifier.height(10.dp))
        }

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
