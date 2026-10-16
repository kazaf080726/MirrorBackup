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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import com.kazaf.mirrorbackup.ui.components.CircularProgressRing
import com.kazaf.mirrorbackup.ui.components.FloatingDock
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

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

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
                        "尚未导入备份包。\n点击底部悬浮栏【导入ZIP备份包】，选择本应用导出的压缩文件。",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

        // ── 底部悬浮操作栏 ──
        FloatingDock(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = 108.dp)
                .fillMaxWidth()
        ) {
            val busy = vm.busy
            if (busy) {
                MiuiButton(
                    text = "■ 停止任务",
                    onClick = { vm.stop() },
                    danger = true,
                    modifier = Modifier.weight(1f)
                )
            } else {
                val l = loaded
                if (l == null) {
                    MiuiButton(
                        text = "📥 导入ZIP备份包",
                        onClick = { openLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    val blockedByBlack = l.blacklistedNames.isNotEmpty() || l.invalidNames.isNotEmpty()
                    val allOk = l.previews.isNotEmpty() && l.previews.all { it.shaOk == true && it.sizeOk }
                    MiuiButton(
                        text = "① 校验完整性",
                        onClick = { vm.verify() },
                        enabled = !blockedByBlack,
                        modifier = Modifier.weight(1f)
                    )
                    MiuiButton(
                        text = "② 执行恢复",
                        onClick = { vm.restore() },
                        enabled = allOk && l.fingerprintMatch && !blockedByBlack,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
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
