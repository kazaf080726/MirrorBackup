$ErrorActionPreference = 'Stop'
$enc = New-Object System.Text.UTF8Encoding($false)
$base = 'D:\Users\Administrator\Documents\DeepSeek Workspace\MirrorBackup\app\src\main\java\com\kazaf\mirrorbackup'

function ScanBlock($text, $startMarker, $endMarker) {
    $i = $text.IndexOf($startMarker)
    if ($i -lt 0) { throw "start marker not found: $startMarker" }
    $j = $text.IndexOf($endMarker, $i)
    if ($j -lt 0) { throw "end marker not found: $endMarker" }
    return @($i, $j)
}

# ============ 1) BackupScreen: 用 KSU 状态卡替换旧英雄卡 ============
$p = "$base\ui\BackupScreen.kt"
$c = [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)
$range = ScanBlock $c "            // ── 状态大卡（KSU 风格：大图标 + 工作状态 + 版本/方案/分区数）──" "            if (progress.phase == BackupEngine.Phase.DONE) {"
$newCard = @'
            // ── KSU 状态卡（完整复刻 KernelSU Manager StatusCard）──
            val suLabelNow = when {
                vm.rootCheck == null -> "检测中…"
                vm.rootCheck?.detail?.contains("ksu") == true -> "KernelSU"
                vm.rootCheck?.detail?.contains("apatch") == true -> "APatch"
                vm.rootCheck?.detail?.contains("magisk") == true -> "Magisk"
                vm.rootCheck?.path?.contains("ksu") == true -> "KernelSU"
                else -> "ROOT"
            }
            com.kazaf.mirrorbackup.ui.components.KsuStatusCard(
                working = vm.rootOk,
                title = if (vm.rootOk) "工作中" else "未授权",
                versionLine = "版本：v" + com.kazaf.mirrorbackup.BuildConfig.VERSION_NAME,
                modeLabel = suLabelNow,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

'@
$c = $c.Substring(0, $range[0]) + $newCard + $c.Substring($range[1])
[System.IO.File]::WriteAllText($p, $c, $enc)
"BackupScreen: KSU card wired"

# ============ 2) MainActivity: 删除右上角 ROOT 芯片 + 无 ROOT 弹窗 ============
$p2 = "$base\MainActivity.kt"
$c2 = [System.IO.File]::ReadAllText($p2, [System.Text.Encoding]::UTF8)
$r2 = ScanBlock $c2 "@Composable`nprivate fun Header(" "private fun shortSuPath"
$newHeader = @'
@Composable
private fun Header(backupVm: BackupViewModel) {
    Column(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp)
    ) {
        Text(
            stringResource(R.string.app_name),
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            stringResource(R.string.header_subtitle),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

'@
$c2 = $c2.Substring(0, $r2[0]) + $newHeader + $c2.Substring($r2[1])
# 无 ROOT 弹窗：在 App() 内 rootCheck 就绪后提示
$anchor = @'
    LaunchedEffect(Unit) {
        backupVm.refreshEnvironment()
        settingsVm.refreshModuleStatus()
    }
'@
if (-not $c2.Contains($anchor)) { throw 'main activity effect anchor missing' }
$newEffect = @'
    LaunchedEffect(Unit) {
        backupVm.refreshEnvironment()
        settingsVm.refreshModuleStatus()
    }

    // 检测不到 ROOT 时弹窗提示（不再显示右上角芯片）
    val rcNow = backupVm.rootCheck
    if (rcNow != null && !rcNow.rootOk) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { },
            title = { Text("未检测到 ROOT 权限") },
            text = {
                Text(
                    "请在 KernelSU / Magisk / APatch 管理器中授权「镜像备份」，" +
                        "授权后点击下方按钮重新检测。\n\n当前状态：" + rcNow.detail
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { backupVm.refreshEnvironment() }) {
                    Text("重新检测")
                }
            }
        )
    }
'@
$c2 = $c2.Replace($anchor, $newEffect)
# 呼吸光晕改为持续循环
$glowOld = @'
                if (themeCfg.glowEnabled) {
                    repeat(7) {
                        glowAlpha.animateTo(0.24f, tween(6500, easing = LinearEasing))
                        glowAlpha.animateTo(0.12f, tween(6500, easing = LinearEasing))
                    }
                    glowAlpha.animateTo(0.16f, tween(1500))
                } else {
                    glowAlpha.snapTo(0f)
                }
'@
$glowNew = @'
                if (themeCfg.glowEnabled) {
                    // 持续呼吸（光影光晕常驻）
                    while (true) {
                        glowAlpha.animateTo(0.26f, tween(6000, easing = LinearEasing))
                        glowAlpha.animateTo(0.10f, tween(6000, easing = LinearEasing))
                    }
                } else {
                    glowAlpha.snapTo(0f)
                }
'@
if ($c2.Contains($glowOld)) { $c2 = $c2.Replace($glowOld, $glowNew); "glow continuous" } else { "glow anchor MISSING" }
[System.IO.File]::WriteAllText($p2, $c2, $enc)
"MainActivity updated"

# ============ 3) ModeCard 选中覆盖修复 ============
$p3 = "$base\ui\components\MiuiComponents.kt"
$c3 = [System.IO.File]::ReadAllText($p3, [System.Text.Encoding]::UTF8)
$bad = @'
        if (selected) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(accent.copy(alpha = 0.10f))
            )
        }
'@
if ($c3.Contains($bad)) { $c3 = $c3.Replace($bad, ''); "accent fill removed" } else { "accent fill not found" }
$oldGlass = @'
        ) {
            ModeCardContent(selected, emoji, title, desc, accent, cs)
        }
    } else {
'@
$newGlass = @'
        ) {
            ModeCardContent(selected, emoji, title, desc, accent, cs)
            if (selected) {
                Box(
                    Modifier
                        .matchParentSize()
                        .border(1.8.dp, accent, shape)
                )
            }
        }
    } else {
'@
if ($c3.Contains($oldGlass)) { $c3 = $c3.Replace($oldGlass, $newGlass); "selection border overlay added" } else { "glass branch anchor missing" }
[System.IO.File]::WriteAllText($p3, $c3, $enc)
"MiuiComponents updated"
"ALL DONE"
