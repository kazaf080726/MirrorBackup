package com.kazaf.mirrorbackup.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kazaf.mirrorbackup.core.BackupEngine
import com.kazaf.mirrorbackup.core.BackupMode
import com.kazaf.mirrorbackup.core.ThreadMode
import com.kazaf.mirrorbackup.core.f
import com.kazaf.mirrorbackup.core.fmtEta
import com.kazaf.mirrorbackup.core.fmtSize
import com.kazaf.mirrorbackup.ui.components.CircularProgressRing
import com.kazaf.mirrorbackup.ui.components.FloatingDock
import com.kazaf.mirrorbackup.ui.components.FormatChip
import com.kazaf.mirrorbackup.ui.components.LiquidGlassCard
import com.kazaf.mirrorbackup.ui.components.MiuiButton
import com.kazaf.mirrorbackup.ui.components.ModeCard
import com.kazaf.mirrorbackup.ui.components.RecordsCard
import com.kazaf.mirrorbackup.ui.components.SectionTitle
import com.kazaf.mirrorbackup.ui.components.StatusBadge
import com.kazaf.mirrorbackup.ui.components.TerminalLog
import com.kazaf.mirrorbackup.ui.theme.MiuiColors
import com.kazaf.mirrorbackup.viewmodel.BackupViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tab1：备份工作台（液态玻璃 + 悬浮操作栏 + 圆环进度） */
@Composable
fun BackupScreen(vm: BackupViewModel, onOpenRestore: () -> Unit) {
    val context = LocalContext.current
    val progress by vm.progress.collectAsState()
    val logLines by vm.log.lines.collectAsState()
    val packPrompt by vm.packPrompt.collectAsState()
    val running = vm.running
    val rootOk = vm.rootOk

    val dateFmt = remember { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()) }
    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
        }
        vm.answerPack(uri)
    }

    // 打包选择弹窗（全量校验完成后询问）
    packPrompt?.let { p ->
        AlertDialog(
            onDismissRequest = { /* 必须选择，禁止点击外部关闭 */ },
            title = { Text("📦 是否打包导出？") },
            text = {
                Text(
                    "全量完整性校验已通过（${p.imageCount} 个镜像，${fmtSize(p.totalBytes)}）。\n\n" +
                        "· 打包为 ZIP：仅存储（STORE）模式，不做任何数据压缩\n" +
                        "· 跳过打包：直接结束任务，镜像保留在工作目录，并自动写入备份记录表"
                )
            },
            confirmButton = {
                TextButton(onClick = { zipLauncher.launch("镜像备份_" + dateFmt.format(Date()) + ".zip") }) {
                    Text("📦 打包为ZIP（仅存储）")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.answerPack(null) }) {
                    Text("⏭ 跳过打包")
                }
            }
        )
    }

    // 大容量目标二次确认（>20GB，防止误触巨量备份）
    var confirmBigTotal by remember { mutableStateOf<Long?>(null) }
    confirmBigTotal?.let { total ->
        AlertDialog(
            onDismissRequest = { confirmBigTotal = null },
            title = { Text("⚠ 确认大容量备份") },
            text = {
                Text(
                    "本次备份目标预估总量 ${fmtSize(total)}（超过 20GB），将占用较多存储空间与时间。\n\n请确认是否继续？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmBigTotal = null
                    vm.start()
                }) { Text("确认备份") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBigTotal = null }) { Text("取消") }
            }
        )
    }

    // 任务中止警报弹窗
    var alertDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(progress.phase) {
        if (progress.phase != BackupEngine.Phase.FAILED) alertDismissed = false
    }
    if (progress.phase == BackupEngine.Phase.FAILED && !alertDismissed) {
        AlertDialog(
            onDismissRequest = { alertDismissed = true },
            title = { Text("⚠ 任务中止警报") },
            text = {
                Text(progress.detail.ifBlank { "检测到异常，已停止一切读写操作。详细原因见日志窗口。" })
            },
            confirmButton = {
                TextButton(onClick = { alertDismissed = true }) { Text("确定") }
            }
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            if (progress.phase == BackupEngine.Phase.DONE) {
                LiquidGlassCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusBadge("✅ 任务完成", MiuiColors.Green)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "镜像已提取并完成全量校验，状态即将自动重置",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── 四种备份模式 ──
            SectionTitle("选择备份模式")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeCard(
                    selected = vm.mode == BackupMode.ALL,
                    emoji = "🗂️",
                    title = "全部备份",
                    desc = "扫到多少备份多少\n含 super 与引导分区",
                    accent = MiuiColors.Blue,
                    onClick = { vm.chooseMode(BackupMode.ALL) },
                    modifier = Modifier.weight(1f)
                )
                ModeCard(
                    selected = vm.mode == BackupMode.NORMAL,
                    emoji = "📦",
                    title = "普通备份",
                    desc = "仅跳过 super / >10GB\n（黑名单由你决定）",
                    accent = MiuiColors.Orange,
                    onClick = { vm.chooseMode(BackupMode.NORMAL) },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeCard(
                    selected = vm.mode == BackupMode.MANUAL,
                    emoji = "✍️",
                    title = "手动备份",
                    desc = "自行勾选\n需要备份的分区",
                    accent = MiuiColors.Purple,
                    onClick = { vm.chooseMode(BackupMode.MANUAL) },
                    modifier = Modifier.weight(1f)
                )
                ModeCard(
                    selected = vm.mode == BackupMode.PRIVATE,
                    emoji = "🧩",
                    title = "私有分区备份",
                    desc = "仅 efs/nv/modem 等\n硬件私有分区",
                    accent = MiuiColors.Green,
                    onClick = { vm.chooseMode(BackupMode.PRIVATE) },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(12.dp))

            // ── 手动模式：分区勾选列表（黑名单由你在此或设置页管理） ──
            AnimatedVisibility(visible = vm.mode == BackupMode.MANUAL) {
                Column {
                    LiquidGlassCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "手动勾选分区（${vm.manualSelection.size}/${vm.manualPartitions.size} 可选）",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Row {
                                TextButton(onClick = { vm.setManualAll(true) }) { Text("全选", fontSize = 12.sp) }
                                TextButton(onClick = { vm.setManualAll(false) }) { Text("清空", fontSize = 12.sp) }
                            }
                        }
                        Text(
                            "共扫描 ${vm.partitions.size} 个分区 · 你已加入黑名单 ${vm.userBlacklist.size} 个（可在设置页管理）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LazyColumn(Modifier.height(200.dp)) {
                            items(vm.partitions) { part ->
                                val black = part.name in vm.userBlacklist
                                val risky = com.kazaf.mirrorbackup.core.PartitionRules.isRisky(part.name)
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = part.name in vm.manualSelection,
                                        onCheckedChange = { vm.toggleManual(part.name, it) },
                                        enabled = !black
                                    )
                                    Text(
                                        (if (risky) "⚠ " else "") + part.name,
                                        fontSize = 13.sp,
                                        color = if (black) MiuiColors.Red else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        if (black) "已加入黑名单" else fmtSize(part.sizeBytes),
                                        fontSize = 11.sp,
                                        color = if (black) MiuiColors.Red else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            // ── 线程模式（默认多线程） ──
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("备份线程（默认多线程）")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormatChip(
                        title = "单线程",
                        desc = "1 线程",
                        selected = vm.threadMode == ThreadMode.SINGLE,
                        onClick = { vm.chooseThreadMode(ThreadMode.SINGLE) },
                        modifier = Modifier.weight(1f)
                    )
                    FormatChip(
                        title = "双线程",
                        desc = "2 线程",
                        selected = vm.threadMode == ThreadMode.DUAL,
                        onClick = { vm.chooseThreadMode(ThreadMode.DUAL) },
                        modifier = Modifier.weight(1f)
                    )
                    FormatChip(
                        title = "多线程",
                        desc = "4 线程",
                        selected = vm.threadMode == ThreadMode.MULTI,
                        onClick = { vm.chooseThreadMode(ThreadMode.MULTI) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── 镜像格式 ──
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("镜像存储格式")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FormatChip(
                        title = "RAW 原始镜像",
                        desc = "dd 原始输出，等于分区大小",
                        selected = !vm.sparse,
                        onClick = { vm.chooseSparse(false) },
                        modifier = Modifier.weight(1f)
                    )
                    FormatChip(
                        title = "SPARSE 稀疏镜像",
                        desc = "libsparse 压缩，体积更小",
                        selected = vm.sparse,
                        onClick = { vm.chooseSparse(true) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── 目标分区预览 ──
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                Text(
                    "分区扫描与排除明细",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(6.dp))
                val bd = vm.breakdown
                when {
                    !rootOk -> Text(
                        "⚠ 未检测到完整 ROOT 权限（已探测 Magisk/KernelSU/APatch）。请授权后点击右上角状态重新检测。",
                        color = MiuiColors.Red,
                        fontSize = 13.sp
                    )
                    bd.scanned.isEmpty() -> Text(
                        "正在枚举分区…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                    else -> {
                        Text(
                            "✅ 扫描 ${bd.scannedCount} 个分区 → 本次目标 ${bd.targetCount} 个 · 预估总量 ${fmtSize(bd.targets.sumOf { it.sizeBytes })}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            bd.targets.joinToString(" · ") { "${it.name} ${fmtSize(it.sizeBytes)}" },
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        val excluded = buildList {
                            if (bd.userBlacklisted.isNotEmpty()) {
                                add(
                                    "⛔ 你的黑名单 ${bd.userBlacklisted.size} 个: " +
                                        bd.userBlacklisted.joinToString(" ") { it.name }
                                )
                            }
                            if (vm.mode == BackupMode.NORMAL) {
                                if (bd.superParts.isNotEmpty()) {
                                    add("🟡 排除 super: ${bd.superParts.joinToString(" ") { it.name }}")
                                }
                                if (bd.largeParts.isNotEmpty()) {
                                    add(
                                        "🟡 排除大容量(>10GB): " +
                                            bd.largeParts.joinToString(" ") { "${it.name}(${fmtSize(it.sizeBytes)})" }
                                    )
                                }
                            }
                            if (vm.mode == BackupMode.PRIVATE) {
                                val others = bd.scannedCount - bd.targetCount - bd.userBlacklisted.size
                                if (others > 0) add("🟡 非私有分区未选择: $others 个")
                            }
                            if (bd.riskyTargets.isNotEmpty()) {
                                add(
                                    "⚠ 风险提示：目标包含引导相关分区（" +
                                        bd.riskyTargets.joinToString(" ") { it.name } +
                                        "），镜像切勿刷入不匹配的设备"
                                )
                            }
                        }
                        excluded.forEach { line ->
                            Text(
                                line,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── 进度环 ──
            val active = progress.phase != BackupEngine.Phase.IDLE &&
                progress.phase != BackupEngine.Phase.DONE &&
                progress.phase != BackupEngine.Phase.FAILED &&
                progress.phase != BackupEngine.Phase.CANCELLED
            if (active) {
                LiquidGlassCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val pct = if (progress.bytesTotal > 0) {
                            (progress.bytesDone.toFloat() / progress.bytesTotal).coerceIn(0f, 1f)
                        } else 0f
                        CircularProgressRing(
                            pct = pct,
                            size = 84.dp,
                            indeterminate = progress.phase == BackupEngine.Phase.PREFLIGHT
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                phaseLabel(progress.phase) +
                                    (if (progress.partitionName.isNotBlank()) " · ${progress.partitionName}" else ""),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = MiuiColors.Blue
                            )
                            Spacer(Modifier.height(4.dp))
                            if (progress.bytesTotal > 0) {
                                Text(
                                    "${fmtSize(progress.bytesDone)} / ${fmtSize(progress.bytesTotal)}",
                                    fontSize = 12.sp
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    f("速度 %.1f MB/s · 剩余 %s", progress.speedMBps, fmtEta(progress.etaSeconds)),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    progress.detail.ifBlank { "处理中…" },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── 备份记录表 ──
            RecordsCard(
                title = "备份记录（提取历史）",
                records = vm.records.filter { it.type == "backup" }
            )
            Spacer(Modifier.height(12.dp))

            // ── 日志 ──
            SectionTitle("实时日志")
            TerminalLog(logLines, Modifier.fillMaxWidth(), height = 300.dp)

            // 底部留白，避免被悬浮栏遮挡
            Spacer(Modifier.height(96.dp))
        }

        // ── 底部悬浮操作栏 ──
        FloatingDock(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .fillMaxWidth()
        ) {
            if (running || progress.phase == BackupEngine.Phase.WAITING_CHOICE) {
                MiuiButton(
                    text = "■ 停止任务",
                    onClick = { vm.stop() },
                    danger = true,
                    modifier = Modifier.weight(1f)
                )
            } else {
                val canStart = rootOk && (vm.mode != BackupMode.MANUAL || vm.manualSelection.isNotEmpty())
                MiuiButton(
                    text = "🚀 开始备份",
                    onClick = {
                        val total = vm.selectedTargets.sumOf { it.sizeBytes }
                        if (total > 20L * 1024 * 1024 * 1024) {
                            confirmBigTotal = total
                        } else {
                            vm.start()
                        }
                    },
                    enabled = canStart,
                    modifier = Modifier.weight(1.3f)
                )
                TextButton(
                    onClick = onOpenRestore,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("📥 恢复工作台", fontSize = 13.sp)
                }
            }
        }
    }
}

private fun phaseLabel(phase: BackupEngine.Phase): String = when (phase) {
    BackupEngine.Phase.PREFLIGHT -> "⏳ 安全前置校验"
    BackupEngine.Phase.BACKING_UP -> "🔄 并行备份中"
    BackupEngine.Phase.VERIFYING -> "🔍 全量完整性校验"
    BackupEngine.Phase.WAITING_CHOICE -> "📦 等待选择打包方式"
    BackupEngine.Phase.PACKING -> "📦 打包导出（仅存储）"
    BackupEngine.Phase.DONE -> "✅ 完成"
    BackupEngine.Phase.CANCELLED -> "⏹ 已停止"
    BackupEngine.Phase.FAILED -> "❌ 失败"
    BackupEngine.Phase.IDLE -> "待机"
}
