package com.kazaf.mirrorbackup.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kazaf.mirrorbackup.core.DISCLAIMER_TEXT
import com.kazaf.mirrorbackup.core.RestoreEngine
import com.kazaf.mirrorbackup.core.fmtSize
import com.kazaf.mirrorbackup.core.modeLabelOf
import com.kazaf.mirrorbackup.core.pure.FlashMatchKind
import com.kazaf.mirrorbackup.core.pure.FlashPartition
import com.kazaf.mirrorbackup.ui.components.CircularProgressRing
import com.kazaf.mirrorbackup.ui.components.FloatingDock
import com.kazaf.mirrorbackup.ui.components.FormatChip
import com.kazaf.mirrorbackup.ui.components.LiquidGlassCard
import com.kazaf.mirrorbackup.ui.components.MiuiButton
import com.kazaf.mirrorbackup.ui.components.RecordsCard
import com.kazaf.mirrorbackup.ui.components.SectionTitle
import com.kazaf.mirrorbackup.ui.components.StatusBadge
import com.kazaf.mirrorbackup.ui.components.TerminalLog
import com.kazaf.mirrorbackup.ui.theme.MiuiColors
import com.kazaf.mirrorbackup.viewmodel.RestoreViewModel

/** Tab2：📥 恢复工作台（双重校验只读提取 + 悬浮操作栏） */
@Composable
fun RestoreScreen(vm: RestoreViewModel) {
    val context = LocalContext.current
    val progress by vm.progress.collectAsState()
    val logLines by vm.log.lines.collectAsState()
    val loaded = vm.loaded
    var forceExpanded by remember { mutableStateOf(false) }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            vm.import(uri)
        }
    }

    // 单分区刷入：选 .img
    val imgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            vm.pickImage(uri)
        }
    }

    // TWRP 注入：选 twrp.img
    val twrpLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            vm.pickTwrp(uri)
        }
    }

    LaunchedEffect(Unit) { vm.detectTwrpSupport() }

    // 恢复中止警报弹窗
    var alertDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(progress.phase) {
        if (progress.phase != RestoreEngine.Phase.FAILED) alertDismissed = false
    }
    if (progress.phase == RestoreEngine.Phase.FAILED && !alertDismissed) {
        AlertDialog(
            onDismissRequest = { alertDismissed = true },
            title = { Text("⚠ 恢复操作警报") },
            text = {
                Text(progress.detail.ifBlank { "安全校验未通过，写入已阻断。详细原因见日志窗口。" })
            },
            confirmButton = {
                TextButton(onClick = { alertDismissed = true }) { Text("确定") }
            }
        )
    }

    // 写入完成 → 询问是否重启（刷入 / TWRP 注入 / 恢复 成功后都会弹）
    if (vm.rebootPrompt) {
        AlertDialog(
            onDismissRequest = { vm.dismissRebootPrompt() },
            title = { Text("✔ 写入完成") },
            text = {
                Text(
                    "分区已写入并完成校验。\n现在重启设备让修改生效吗？\n（选择「稍后」也可以随时点页面上的「重启设备」）",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.rebootNow() }) {
                    Text("立即重启", color = MiuiColors.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissRebootPrompt() }) { Text("稍后") }
            }
        )
    }

    // 手动重启前的二次确认
    if (vm.rebootConfirm) {
        AlertDialog(
            onDismissRequest = { vm.dismissRebootConfirm() },
            title = { Text("🔁 重启设备") },
            text = { Text("确定要立即重启设备吗？\n当前若有正在进行的任务会被中断。", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { vm.rebootNow() }) {
                    Text("重启", color = MiuiColors.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissRebootConfirm() }) { Text("取消") }
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

            // ── 单分区刷入（.img）──
            FlashCard(vm, onPick = { imgLauncher.launch(arrayOf("*/*")) })
            Spacer(Modifier.height(12.dp))

            // ── TWRP 注入（A/B）──
            TwrpCard(vm, onPickTwrp = { twrpLauncher.launch(arrayOf("*/*")) })
            Spacer(Modifier.height(12.dp))

            vm.loadError?.let { err ->
                LiquidGlassCard {
                    Text("❌ 导入失败: $err", color = MiuiColors.Red, fontSize = 13.sp)
                }
                Spacer(Modifier.height(12.dp))
            }

            loaded?.let { l ->
                val blockedByBlack = l.blacklistedNames.isNotEmpty() || l.invalidNames.isNotEmpty()

                // ── 备份包信息 ──
                LiquidGlassCard {
                    Text("📦 备份包信息", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    InfoLine("备份模式", modeLabelOf(l.manifest.mode))
                    InfoLine("镜像格式", if (l.manifest.format == "sparse") "SPARSE 稀疏镜像" else "RAW 原始镜像")
                    InfoLine("创建时间", l.manifest.createdAt)
                    InfoLine("设备型号", "${l.manifest.model} · Android ${l.manifest.androidVersion}")
                    InfoLine("SN", l.manifest.sn.ifBlank { "（空）" })
                    InfoLine("CPU ID", l.manifest.cpuId.ifBlank { "（空）" })
                    Text(
                        "设备指纹: ${l.manifest.fingerprint}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    if (l.fingerprintMatch) {
                        StatusBadge("✅ 设备指纹匹配", MiuiColors.Green)
                    } else {
                        StatusBadge("❌ 设备指纹不一致 —— 恢复按钮已锁定", MiuiColors.Red)
                    }
                    if (blockedByBlack) {
                        Spacer(Modifier.height(6.dp))
                        if (l.blacklistedNames.isNotEmpty()) {
                            Text(
                                "⛔ 以下分区在你的黑名单中，本应用拒绝写入（可在设置页移除）: ${l.blacklistedNames.joinToString(" ")}",
                                color = MiuiColors.Red,
                                fontSize = 12.sp
                            )
                        }
                        if (l.invalidNames.isNotEmpty()) {
                            Text(
                                "🚫 manifest 含非法分区名（${l.invalidNames.joinToString(" ")}），已拒绝操作",
                                color = MiuiColors.Red,
                                fontSize = 12.sp
                            )
                        }
                    }
                    if (l.riskyNames.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "⚠ 风险提示：该包包含引导相关分区（${l.riskyNames.joinToString(" ")}），恢复前请确认目标设备与备份设备一致（由你决策）",
                            color = MiuiColors.Orange,
                            fontSize = 12.sp
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                // ── 分区校验状态 ──
                LiquidGlassCard {
                    Text("🧾 分区校验状态（${l.previews.size} 个）", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        "第①层双重校验：SHA-256 + ZIP CRC32 · 只读提取，不进行任何写入",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    l.previews.forEach { PartitionRow(it) }
                }
                Spacer(Modifier.height(12.dp))

                // ── 折叠隐藏的【强制恢复】危险区 ──
                if (!l.fingerprintMatch) {
                    TextButton(onClick = { forceExpanded = !forceExpanded }) {
                        Text(
                            if (forceExpanded) "▴ 收起【强制恢复】" else "⚠ 显示【强制恢复】危险选项",
                            color = MiuiColors.Red,
                            fontSize = 13.sp
                        )
                    }
                    AnimatedVisibility(visible = forceExpanded) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MiuiColors.Red.copy(alpha = 0.08f),
                            border = BorderStroke(1.5.dp, MiuiColors.Red)
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text("🚨 高风险操作警告", color = MiuiColors.Red, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "设备指纹不匹配。强制恢复可能将镜像写入不匹配的设备，造成基带、硬件校准永久损坏，且不可逆转。",
                                    color = MiuiColors.Red,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { vm.setForce(!vm.forceChecked) }
                                ) {
                                    Checkbox(checked = vm.forceChecked, onCheckedChange = { vm.setForce(it) })
                                    Text("我已阅读并理解风险，确认执行强制恢复（风险自负）", fontSize = 13.sp)
                                }
                                Spacer(Modifier.height(10.dp))
                                val allOk = l.previews.isNotEmpty() && l.previews.all { it.shaOk == true && it.sizeOk }
                                MiuiButton(
                                    text = "🚨 强制恢复",
                                    onClick = { vm.restore() },
                                    enabled = vm.forceChecked && allOk && !vm.busy && !blockedByBlack,
                                    danger = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // ── 进度环 ──
                if (progress.phase == RestoreEngine.Phase.VERIFYING ||
                    progress.phase == RestoreEngine.Phase.RESTORING ||
                    progress.phase == RestoreEngine.Phase.LOADING
                ) {
                    LiquidGlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val pct = if (progress.bytesTotal > 0) {
                                (progress.bytesDone.toFloat() / progress.bytesTotal).coerceIn(0f, 1f)
                            } else 0f
                            CircularProgressRing(
                                pct = pct,
                                size = 84.dp,
                                indeterminate = progress.phase == RestoreEngine.Phase.LOADING
                            )
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(
                                    when (progress.phase) {
                                        RestoreEngine.Phase.LOADING -> "解析备份包"
                                        RestoreEngine.Phase.VERIFYING -> "🔍 双重校验（只读提取）"
                                        RestoreEngine.Phase.RESTORING -> "💾 恢复写入"
                                        else -> "处理中"
                                    } + (if (progress.partitionName.isNotBlank()) " · ${progress.partitionName}" else ""),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = MiuiColors.Blue
                                )
                                Spacer(Modifier.height(4.dp))
                                if (progress.bytesTotal > 0) {
                                    Text("${fmtSize(progress.bytesDone)} / ${fmtSize(progress.bytesTotal)}", fontSize = 12.sp)
                                } else {
                                    Text("请稍候…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // ── 恢复记录 ──
                RecordsCard(
                    title = "恢复记录",
                    records = vm.records.filter { it.type == "restore" }
                )
                Spacer(Modifier.height(12.dp))
            } ?: run {
                LiquidGlassCard {
                    Text(
                        "尚未导入备份包。\n点下方按钮选择本应用导出的 ZIP 备份包。",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    MiuiButton(
                        text = "📦 导入 ZIP 备份包",
                        onClick = {
                            openLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            SectionTitle("恢复日志")
            TerminalLog(logLines, Modifier.fillMaxWidth(), height = 300.dp)
            Spacer(Modifier.height(14.dp))

            // ── 恢复页底部永久免责 ──
            LiquidGlassCard {
                Text(
                    DISCLAIMER_TEXT,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(96.dp))
        }

        // （重复的底部悬浮栏已移除：操作入口见页面内按钮）

    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            Modifier.width(72.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun PartitionRow(p: RestoreEngine.PartitionPreview) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                "${if (p.format == "sparse") "SPARSE" else "RAW"} · 镜像 ${fmtSize(p.sizeBytes)} · 目标分区 ${fmtSize(p.targetSize)}",
                fontSize = 11.sp,
                color = cs.onSurfaceVariant
            )
        }
        val (text, color) = when {
            p.shaOk == false -> "❌ 校验失败" to MiuiColors.Red
            p.shaOk == true && !p.sizeOk -> "❌ 空间不足" to MiuiColors.Red
            p.shaOk == true && p.sizeOk -> "✅ 双重校验通过" to MiuiColors.Green
            !p.targetExists -> "❌ 目标分区不存在" to MiuiColors.Red
            else -> "未校验" to cs.onSurfaceVariant
        }
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/* ────────── 单分区刷入（.img）────────── */

@Composable
private fun FlashCard(vm: RestoreViewModel, onPick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val img = vm.flashImage
    val match = vm.flashMatch
    val target = vm.flashTarget
    val prog by vm.flashProgress.collectAsState()

    LiquidGlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("单分区刷入（.img）", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (vm.flashBusy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "选一个 .img → 自动匹配 /dev/block/by-name 里的同名分区；A/B 双分区会让你选 A 还是 B。",
            fontSize = 11.sp,
            color = cs.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        MiuiButton(
            text = if (img == null) "选择 .img 文件" else "重新选择 .img",
            onClick = onPick,
            enabled = !vm.flashBusy,
            modifier = Modifier.fillMaxWidth()
        )

        img?.let { prepared ->
            Spacer(Modifier.height(8.dp))
            Text("${prepared.meta.fileName}  ${fmtSize(prepared.meta.bytes)}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                "SHA-256 ${prepared.meta.sha256.take(32)}…" + if (prepared.meta.sparse) "  （稀疏镜像已展开）" else "",
                fontSize = 10.sp,
                color = cs.onSurfaceVariant
            )
        }

        match?.let { m ->
            Spacer(Modifier.height(8.dp))
            when (m.kind) {
                FlashMatchKind.SINGLE -> {
                    val t = target ?: m.single
                    if (t != null) {
                        Text("🎯 目标分区", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("${t.name}  →  ${t.path}", fontSize = 12.sp)
                        Text("分区容量 ${fmtSize(t.sizeBytes)}", fontSize = 11.sp, color = cs.onSurfaceVariant)
                    }
                }
                FlashMatchKind.NEED_SLOT -> {
                    Text("🎯 A/B 双分区，请选择要刷入的槽位", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        m.candidates.forEach { p ->
                            FormatChip(
                                title = if (p.name.endsWith("_b")) "B 槽" else "A 槽",
                                desc = "${p.name} · ${fmtSize(p.sizeBytes)}",
                                selected = target?.name == p.name,
                                onClick = { vm.chooseTarget(p) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                FlashMatchKind.NONE -> {
                    Text("⚠ ${m.note}", fontSize = 12.sp, color = cs.error)
                    Spacer(Modifier.height(6.dp))
                    MiuiButton(
                        text = "从分区列表手动选择（${vm.flashPartitions.size} 个）",
                        onClick = { vm.openManualPicker() },
                        enabled = !vm.flashBusy,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        if (vm.flashChecks.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            val okCount = vm.flashChecks.count { it.ok }
            Text(
                "🧪 前置校验 ${okCount}/${vm.flashChecks.size}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (vm.flashChecks.any { !it.ok && it.fatal }) MiuiColors.Red else MiuiColors.Green
            )
            vm.flashChecks.forEach { c ->
                Text(
                    "${if (c.ok) "✅" else if (c.fatal) "❌" else "⚠"} ${c.label}" +
                        (if (c.detail.isEmpty()) "" else "（${c.detail}）"),
                    fontSize = 11.sp,
                    color = when {
                        c.ok -> cs.onSurfaceVariant
                        c.fatal -> MiuiColors.Red
                        else -> cs.onSurface
                    }
                )
            }
        }

        if (vm.flashBusy && prog.second > 0) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (prog.first.toFloat() / prog.second).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text("正在写入 ${fmtSize(prog.first)} / ${fmtSize(prog.second)}", fontSize = 11.sp, color = cs.onSurfaceVariant)
        }

        vm.flashResult?.let { r ->
            Spacer(Modifier.height(8.dp))
            Text(r, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (vm.flashResultOk) MiuiColors.Green else MiuiColors.Red)
        }

        Spacer(Modifier.height(10.dp))
        MiuiButton(
            text = "刷入到该分区",
            onClick = { vm.askFlash() },
            enabled = vm.flashCanStart,
            danger = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (target != null) {
            Spacer(Modifier.height(8.dp))
            MiuiButton(
                text = "导出该分区镜像 → /sdcard/${target.name}.img",
                onClick = { vm.exportTarget() },
                enabled = !vm.exportBusy && !vm.flashBusy,
                modifier = Modifier.fillMaxWidth()
            )
            vm.exportResult?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 11.sp, color = if (it.startsWith("✔")) MiuiColors.Green else MiuiColors.Red)
            }
        }

        Spacer(Modifier.height(8.dp))
        MiuiButton(
            text = if (vm.rebooting) "正在重启…" else "🔁 重启设备",
            onClick = { vm.askReboot() },
            enabled = !vm.rebooting && !vm.flashBusy && !vm.busy && !vm.twrpBusy,
            modifier = Modifier.fillMaxWidth()
        )
        vm.rebootResult?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = 11.sp, color = MiuiColors.Red)
        }
    }

    // ── 手动选分区 ──
    if (vm.flashManualPicker) {
        AlertDialog(
            onDismissRequest = { vm.closeManualPicker() },
            title = { Text("从分区列表选择（${vm.flashPartitions.size} 个）") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    vm.flashPartitions.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { vm.chooseTarget(p); vm.closeManualPicker() }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(p.name, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text(fmtSize(p.sizeBytes), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.closeManualPicker() }) { Text("取消") }
            }
        )
    }

    // ── 最后确认：把要执行的指令原样摆出来，确认没发错 ──
    if (vm.flashConfirm) {
        val t = target
        val prepared = img
        if (t != null && prepared != null) {
            AlertDialog(
                onDismissRequest = { vm.cancelFlash() },
                title = { Text("⚠ 确认刷入") },
                text = {
                    Column {
                        Text("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}", fontSize = 12.sp)
                        Text("目标分区：${t.name}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("设备节点：${t.path}", fontSize = 11.sp)
                        Text("分区容量：${fmtSize(t.sizeBytes)}", fontSize = 11.sp)
                        Text("镜　　像：${prepared.meta.fileName}  ${fmtSize(prepared.meta.bytes)}", fontSize = 11.sp)
                        Text("SHA-256：${prepared.meta.sha256}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text("将执行（Scene flash_image 同等逻辑）：", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "flash_image '${prepared.meta.fileName}' '${t.path}'\n" +
                                "→ 校验镜像≤分区大小 → blockdev --setrw → 写入镜像 → 0 填充到分区末尾",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (vm.flashRiskNote.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text("⚠ ${vm.flashRiskNote}", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("写错分区会导致设备无法开机，请确认机型与槽位无误。", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { vm.doFlash() }) {
                        Text("确认刷入", color = MiuiColors.Red, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { vm.cancelFlash() }) { Text("取消") }
                }
            )
        }
    }
}


/* ────────── TWRP 注入（A/B）────────── */

@Composable
private fun TwrpCard(vm: RestoreViewModel, onPickTwrp: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    LiquidGlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TWRP 注入（A/B）", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (vm.twrpBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(4.dp))
        Text("与 Scene 相同：A/B 设备把 twrp 的 ramdisk 换进 boot_a/boot_b 后分别刷入；有 recovery_a 则直接刷 recovery。", fontSize = 11.sp, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(
            "设备判定：" + vm.twrpModeText.ifEmpty { "检测中…" },
            fontSize = 11.sp,
            color = if (vm.twrpModeText.startsWith("支持")) MiuiColors.Green else cs.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        MiuiButton(
            text = if (vm.twrpFile == null) "选择 twrp.img" else "重新选择 twrp.img",
            onClick = onPickTwrp,
            enabled = !vm.twrpBusy,
            modifier = Modifier.fillMaxWidth()
        )
        vm.twrpFile?.let {
            Spacer(Modifier.height(6.dp))
            Text("${it.name}  ${fmtSize(it.length())}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(8.dp))
        MiuiButton(
            text = "开始注入 TWRP",
            onClick = { vm.injectTwrp() },
            enabled = vm.twrpFile != null && !vm.twrpBusy && vm.twrpModeText.startsWith("支持"),
            danger = true,
            modifier = Modifier.fillMaxWidth()
        )
        vm.twrpResult?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = 11.sp, color = if (vm.twrpResultOk) MiuiColors.Green else MiuiColors.Red)
        }
    }
}