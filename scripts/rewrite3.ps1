$ErrorActionPreference = 'Stop'
$enc = New-Object System.Text.UTF8Encoding($false)

$f = 'D:\Users\Administrator\Documents\DeepSeek Workspace\MirrorBackup\app\src\main\java\com\kazaf\mirrorbackup\MainActivity.kt'
$c = [System.IO.File]::ReadAllText($f, [System.Text.Encoding]::UTF8).Replace("`r`n", "`n")
$c = $c.Replace('import top.yukonga.miuix.kmp.blur.layerBackdrop', 'import com.kyant.backdrop.backdrops.layerBackdrop')
$c = $c.Replace('import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop', 'import com.kyant.backdrop.backdrops.rememberLayerBackdrop')
[System.IO.File]::WriteAllText($f, $c, $enc)
"MainActivity imports fixed"

$g = 'D:\Users\Administrator\Documents\DeepSeek Workspace\MirrorBackup\build.gradle.kts'
$c = [System.IO.File]::ReadAllText($g, [System.Text.Encoding]::UTF8)
$c = $c.Replace('version "2.4.0"', 'version "2.2.20"')
[System.IO.File]::WriteAllText($g, $c, $enc)
"root gradle fixed"

$g2 = 'D:\Users\Administrator\Documents\DeepSeek Workspace\MirrorBackup\app\build.gradle.kts'
$c = [System.IO.File]::ReadAllText($g2, [System.Text.Encoding]::UTF8)
$c = $c.Replace('implementation("androidx.activity:activity-compose:1.13.0")', 'implementation("androidx.activity:activity-compose:1.13.0")')
$c = $c.Replace('implementation(platform("androidx.compose:compose-bom:2026.09.00"))', 'implementation(platform("androidx.compose:compose-bom:2025.08.01"))')
$c = $c.Replace([regex]::Escape('    // 液态玻璃管线（与 SukiSU-Ultra 相同的 miuix-blur 0.9.4，本地补丁版）
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-shader-android:0.9.4")
'), '    // 液态玻璃管线：内嵌 Kyant0/AndroidLiquidGlass（Apache 2.0，随 SukiSU-Ultra 同源）
')
[System.IO.File]::WriteAllText($g2, $c, $enc)
"app gradle fixed"
Select-String -Path $g2 -Pattern 'miuix|bom|activity-compose' | ForEach-Object { $_.Line.Trim() }
