$ErrorActionPreference = 'Stop'
$enc = New-Object System.Text.UTF8Encoding($false)
$base = 'D:\Users\Administrator\Documents\DeepSeek Workspace\MirrorBackup\app\src\main\java\com\kazaf\mirrorbackup'

# ============ BackupScreen ============
$p = "$base\ui\BackupScreen.kt"
$c = [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)

# 1) 状态大卡：插到 Spacer(4.dp) 之后（模式选择前）
$anchorHero = @'
            Spacer(Modifier.height(4.dp))

            if (progress.phase == BackupEngine.Phase.DONE) {
'@
$heroCard = @'
            Spacer(Modifier.height(4.dp))

            // ── 状态大卡（KSU 风格：大图标 + 工作状态 + 版本/方案/分区数）──
            val rootOkNow = vm.rootOk
            val statusColor = if (rootOkNow) MiuiColors.Green else MiuiColors.Red
            val suLabel = when {
                vm.rootCheck == null -> "检测中…"
                vm.rootCheck?.path?.contains("ksu") == true -> "KernelSU"
                vm.rootCheck?.path?.contains("/ap/") == true -> "APatch"
                vm.rootCheck?.path?.contains("magisk") == true -> "Magisk"
                else -> "SU"
            }
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(66.dp)
                            .background(statusColor.copy(alpha = 0.16f), androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (rootOkNow) "✓" else "!",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (rootOkNow) "工作正常" else "需要授权",
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "版本：v${com.kazaf.mirrorbackup.BuildConfig.VERSION_NAME} · $suLabel",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            "${vm.partitions.size} 个分区 · ${com.kazaf.mirrorbackup.core.CpuDetector.cores} 核 · 满核 ${com.kazaf.mirrorbackup.core.CpuDetector.maxThreads} 线程",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (progress.phase == BackupEngine.Phase.DONE) {
'@
if (-not $c.Contains($anchorHero)) { throw 'hero anchor missing' }
$c = $c.Replace($anchorHero, $heroCard)

# 2) 日志上方插入操作区（开始备份 / 停止）
$anchorLog = @'
            // ── 日志（含实时进度条 + 动效）──
            SectionTitle("实时日志")
'@
$actionRow = @'
            // ── 操作区（按你的要求放在日志上方，不再使用悬浮栏按钮）──
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (running || progress.phase == BackupEngine.Phase.WAITING_CHOICE) {
                    MiuiButton(
                        text = "■ 停止任务",
                        onClick = { vm.stop() },
                        danger = true,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    val canStart = vm.rootOk && (vm.mode != BackupMode.MANUAL || vm.manualSelection.isNotEmpty())
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
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── 日志（含实时进度条 + 动效）──
            SectionTitle("实时日志")
'@
if (-not $c.Contains($anchorLog)) { throw 'log anchor missing' }
$c = $c.Replace($anchorLog, $actionRow)

# 3) 删除底部悬浮 dock（含重复的恢复工作台按钮）
$dockStart = $c.IndexOf('        // ── 底部悬浮操作栏 ──')
if ($dockStart -lt 0) { throw 'dock start missing' }
# 稳健：从 dock 注释开始，找到匹配的 "    }`n}`n}" 结束 —— 用括号配平扫描
$depth = 0
$i = $dockStart
$end = -1
while ($i -lt $c.Length) {
    $ch = $c[$i]
    if ($ch -eq '{') { $depth++ }
    elseif ($ch -eq '}') {
        $depth--
        if ($depth -eq 0 -and $i -gt $dockStart) {
            # FloatingDock( ... ) { ... } 的外层；跳过后续空白
            $end = $i + 1
            break
        }
    }
    $i++
}
if ($end -lt 0) { throw 'dock end not found' }
# 保留换行
$c = $c.Substring(0, $dockStart) + "        // （底部悬浮操作栏已移除：操作按钮移至日志上方）`n" + $c.Substring($end)
[System.IO.File]::WriteAllText($p, $c, $enc)
"BackupScreen updated"

# ============ RestoreScreen: 删除重复的底部悬浮栏 ============
$p2 = "$base\ui\RestoreScreen.kt"
$c2 = [System.IO.File]::ReadAllText($p2, [System.Text.Encoding]::UTF8)
$dockPos = $c2.IndexOf('        // ── 底部悬浮操作栏 ──')
if ($dockPos -lt 0) { $dockPos = $c2.IndexOf('FloatingDock(') }
if ($dockPos -ge 0) {
    $depth2 = 0; $j = $dockPos; $end2 = -1
    while ($j -lt $c2.Length) {
        $ch = $c2[$j]
        if ($ch -eq '{') { $depth2++ }
        elseif ($ch -eq '}') { $depth2--; if ($depth2 -eq 0 -and $j -gt $dockPos) { $end2 = $j + 1; break } }
        $j++
    }
    if ($end2 -gt 0) {
        $c2 = $c2.Substring(0, $dockPos) + "        // （重复的底部悬浮栏已移除：操作入口见页面内按钮）`n" + $c2.Substring($end2)
        [System.IO.File]::WriteAllText($p2, $c2, $enc)
        "RestoreScreen dock removed"
    } else { throw 'restore dock end not found' }
} else { "RestoreScreen: no dock found" }
