$ErrorActionPreference = 'Stop'
$enc = New-Object System.Text.UTF8Encoding($false)
$f = 'D:\Users\Administrator\Documents\DeepSeek Workspace\MirrorBackup\app\src\main\java\com\kazaf\mirrorbackup\ui\components\MiuiComponents.kt'
$c = [System.IO.File]::ReadAllText($f, [System.Text.Encoding]::UTF8)

# 1) fix broken literal backtick-n line
$broken = '    val cardGlassEnabled = LocalCardGlassEnabled.current`n    val resolvedTint = tintAlpha ?: LocalCardTintAlpha.current'
$fixed = "    val cardGlassEnabled = LocalCardGlassEnabled.current`n    val resolvedTint = tintAlpha ?: LocalCardTintAlpha.current"
if (-not $c.Contains($broken)) { throw 'broken line not found' }
$c = $c.Replace($broken, $fixed)

# 2) keep only the FIRST occurrence with && cardGlassEnabled (inside LiquidGlassCard)
$lines = $c -split "`n"
$seen = $false
for ($i = 0; $i -lt $lines.Length; $i++) {
    if ($lines[$i] -match 'if \(glass != null && cardGlassEnabled\) \{') {
        if (-not $seen) { $seen = $true }
        else { $lines[$i] = $lines[$i].Replace('if (glass != null && cardGlassEnabled) {', 'if (glass != null) {') }
    }
}
$c = ($lines -join "`n")
[System.IO.File]::WriteAllText($f, $c, $enc)
"fixed"
Select-String -Path $f -Pattern 'cardGlassEnabled' | ForEach-Object { "$($_.LineNumber): $($_.Line.Trim())" }
