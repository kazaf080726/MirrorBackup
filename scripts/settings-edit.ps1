$ErrorActionPreference = 'Stop'
$enc = New-Object System.Text.UTF8Encoding($false)
$f = 'D:\Users\Administrator\Documents\DeepSeek Workspace\MirrorBackup\app\src\main\java\com\kazaf\mirrorbackup\ui\SettingsScreen.kt'
$c = [System.IO.File]::ReadAllText($f, [System.Text.Encoding]::UTF8).Replace("`r`n", "`n")

# 1. signature: add onOpenTheme param
$c = $c.Replace(
    'fun SettingsScreen(settingsVm: SettingsViewModel, backupVm: BackupViewModel) {',
    'fun SettingsScreen(settingsVm: SettingsViewModel, backupVm: BackupViewModel, onOpenTheme: () -> Unit = {}) {'
)

# 2. replace ThemeSection() call with glass entry card
$entry = @'
        // ── 主题设置入口（完整照搬 KSU/SukiSU 主题设置页）──
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
                    Text("🎨 主题设置", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "颜色模式 / Key color / Color Style / Color Spec / 液态玻璃",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))

        // ── 版本与更新 ──
'@
$c = $c.Replace("        // ── 主题与外观（照搬 KernelSU / SukiSU 管理器主题设置） ──`n        ThemeSection()`n`n        // ── 版本与更新 ──`n", $entry)

# 3. delete old ThemeSection + ThemeChip (from the marker comment to EOF)
$marker = [regex]::Escape('')  # placeholder
$idx = $c.IndexOf("/** 🎨 主题与外观")
if ($idx -lt 0) { $idx = $c.IndexOf("private fun ThemeSection()") }
if ($idx -le 0) { throw 'ThemeSection marker not found' }
# back up to the start of the preceding doc comment line
$lineStart = $c.LastIndexOf("`n", $c.LastIndexOf("`n", $idx - 1) - 1)
$c = $c.Substring(0, [Math]::Max(0, $lineStart)) + "`n"

[System.IO.File]::WriteAllText($f, $c, $enc)
"SettingsScreen updated"
$m = Select-String -Path $f -Pattern 'ThemeSection|ThemeChip|onOpenTheme' | ForEach-Object { "$($_.LineNumber): $($_.Line.Trim())" }
$m
