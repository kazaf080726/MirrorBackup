$ErrorActionPreference = 'Stop'
$mb = 'D:\Users\Administrator\Documents\DeepSeek Workspace\MirrorBackup\app\src\main\java'
$files = Get-ChildItem $mb -Recurse -Filter '*.kt'
$report = @()
foreach ($f in $files) {
    $text = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    $lines = $text -split "`n"
    $bad = @()
    for ($i = 0; $i -lt $lines.Length; $i++) {
        if ($lines[$i] -match '鈹|銆|锛|鍒|鏃|缂|鈿|璁|绾|绔|鍔|緇|鏂|鎵|鍏|娓|灏|鍑|锟|锜|鏈|璁|澶|鎵ц|鍒嗗尯|閮ㄥ垎') {
            $bad += ($i + 1)
        }
    }
    if ($bad.Count -gt 0) {
        $first = ($bad | Select-Object -First 5) -join ','
        $report += ("{0}: {1} lines (first {2})" -f $f.Name, $bad.Count, $first)
    }
}
$report
