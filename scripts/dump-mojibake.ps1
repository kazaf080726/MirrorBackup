$ErrorActionPreference = 'Stop'
$mb = 'D:\Users\Administrator\Documents\DeepSeek Workspace\MirrorBackup\app\src\main\java'
# 典型 UTF8->GBK 误解码产生的字符集
$chars = '鈹|銆|锛|鍒|鏃|缂|鈿|璁|绾|绔|鍔|緇|鏂|鎵|鍏|娓|灏|鍑|锜|鏆|棤|闀|滃|儚|鏈|澶|閮|鍒|鎻|鍙|鐨|涓|鐐|鏄|杩|缁|鍚|鍔|鏄剧|寮|鍚|绋|璁板|鏃ュ|鎻愬|寮€|缁撴|鍚庣|宸|鍖|閿|鍙|鍙戝|鏇|鏈|鏂囦|娓呯|绌|鍒犻|闄|璇|鎶|鍐|缁|鍒|鐨勭|閫|鏄惧|鍚屾|鍚|琛|鍒|鍐|鍔|鍚|鍒|鏄|缁|鍙|鍔|鍚|鏃|鍒|鍚|鍔|鍒|鍚|鍔|鍚|鍒|鍚|鍔|鍚|鍒|鍚|鍔'
$pattern = '[\u9000-\u9bff]|锟'
$report = @()
foreach ($f in (Get-ChildItem $mb -Recurse -Filter '*.kt')) {
    $text = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    $lines = $text -split "`n"
    for ($i = 0; $i -lt $lines.Length; $i++) {
        if ($lines[$i] -match $pattern) {
            $report += ("{0}|{1}|{2}" -f $f.Name, ($i + 1), $lines[$i].Trim())
        }
    }
}
$report -join "`n"
"TOTAL: " + $report.Count
