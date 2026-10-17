$ErrorActionPreference = 'Stop'
$enc = New-Object System.Text.UTF8Encoding($false)
$base = 'D:\Users\Administrator\Documents\DeepSeek Workspace\MirrorBackup\app\src\main\java\com\kazaf\mirrorbackup'

# A) VM imports
$p = "$base\viewmodel\BackupViewModel.kt"
$c = [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)
if ($c -notmatch 'import com\.kazaf\.mirrorbackup\.core\.OutputStore') {
    $c = $c.Replace('import com.kazaf.mirrorbackup.core.PartitionInfo', "import com.kazaf.mirrorbackup.core.OutputStore`nimport com.kazaf.mirrorbackup.core.PartitionInfo")
}
if ($c -notmatch 'import com\.kazaf\.mirrorbackup\.core\.fmtSize') {
    $c = $c.Replace('import com.kazaf.mirrorbackup.core.UserBlacklist', "import com.kazaf.mirrorbackup.core.UserBlacklist`nimport com.kazaf.mirrorbackup.core.fmtSize")
}
[System.IO.File]::WriteAllText($p, $c, $enc)

# B) BackupScreen: fix invalid chained background call + ensure imports
$p2 = "$base\ui\BackupScreen.kt"
$c = [System.IO.File]::ReadAllText($p2, [System.Text.Encoding]::UTF8)
$bad = @'
                            modifier = Modifier
                                .fillMaxWidth()
                                .androidx.compose.foundation.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 9.dp),
'@
$good = @'
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 9.dp),
'@
if (-not $c.Contains($bad)) { throw 'search field modifier not found' }
$c = $c.Replace($bad, $good)
# ensure imports present
$needed = @(
    'import androidx.compose.foundation.text.BasicTextField',
    'import androidx.compose.ui.text.TextStyle'
)
$add = @()
foreach ($n in $needed) { if ($c -notmatch [regex]::Escape($n)) { $add += $n } }
if ($add.Count -gt 0) {
    $c = $c.Replace('import androidx.compose.foundation.layout.Arrangement', ($add -join "`n") + "`n" + 'import androidx.compose.foundation.layout.Arrangement')
}
[System.IO.File]::WriteAllText($p2, $c, $enc)
"FIXES DONE"
