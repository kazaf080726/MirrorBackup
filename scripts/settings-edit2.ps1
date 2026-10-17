$ErrorActionPreference = 'Stop'
$enc = New-Object System.Text.UTF8Encoding($false)
$f = 'D:\Users\Administrator\Documents\DeepSeek Workspace\MirrorBackup\app\src\main\java\com\kazaf\mirrorbackup\ui\SettingsScreen.kt'
$c = [System.IO.File]::ReadAllText($f, [System.Text.Encoding]::UTF8).Replace("`r`n", "`n")

# 1. signature
$c = $c.Replace(
    'fun SettingsScreen(settingsVm: SettingsViewModel, backupVm: BackupViewModel) {',
    'fun SettingsScreen(settingsVm: SettingsViewModel, backupVm: BackupViewModel, onOpenTheme: () -> Unit = {}) {'
)

# 2. replace ThemeSection() call line with entry card (keep ASCII anchor: the call line + following blank line)
$callPattern = "        ThemeSection()`n"
if (-not $c.Contains($callPattern)) { throw 'ThemeSection call not found' }
$entry = @'
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


'@
$c = $c.Replace($callPattern, $entry)

# 3. delete trailing ThemeSection + ThemeChip block
$anchor = "@Composable`nprivate fun ThemeSection()"
$idx = $c.IndexOf($anchor)
if ($idx -lt 0) { throw 'ThemeSection body not found' }
$cut = $c.LastIndexOf("`n`n", $idx)
if ($cut -lt 0) { $cut = $idx }
$c = $c.Substring(0, $cut).TrimEnd() + "`n"

[System.IO.File]::WriteAllText($f, $c, $enc)
"OK"
(Select-String -Path $f -Pattern 'ThemeSection|ThemeChip|onOpenTheme').Count
