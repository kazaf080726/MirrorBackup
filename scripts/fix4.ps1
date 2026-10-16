$ErrorActionPreference = 'Stop'
$enc = New-Object System.Text.UTF8Encoding($false)
$app = 'D:\Users\Administrator\Documents\DeepSeek Workspace\MirrorBackup\app\src\main\java'

# 1. Fix literal \r\n artifacts in all ported/kyant files
$fixed = 0
Get-ChildItem "$app\com\kazaf\mirrorbackup\ui\port", "$app\com\kyant" -Recurse -Filter "*.kt" -ErrorAction SilentlyContinue | ForEach-Object {
    $c = [System.IO.File]::ReadAllText($_.FullName, [System.Text.Encoding]::UTF8)
    if ($c.Contains('\r\n')) {
        $c = $c.Replace('\r\n', "`n")
        [System.IO.File]::WriteAllText($_.FullName, $c, $enc)
        $fixed++
        "fixed literal crlf: $($_.Name)"
    }
}
"total fixed: $fixed"

# 2. MainActivity: liquidGlassHost dark param
$f = "$app\com\kazaf\mirrorbackup\MainActivity.kt"
$c = [System.IO.File]::ReadAllText($f, [System.Text.Encoding]::UTF8)
$c = $c.Replace('.liquidGlassHost(glass, MaterialTheme.colorScheme.surface, density)', '.liquidGlassHost(glass, isSystemInDarkTheme(), density)')
[System.IO.File]::WriteAllText($f, $c, $enc)
"MainActivity host fixed"

# 3. BlurExt: shape lambda + capture color
$f2 = "$app\com\kazaf\mirrorbackup\ui\port\BlurExt.kt"
$c = [System.IO.File]::ReadAllText($f2, [System.Text.Encoding]::UTF8)
$c = $c.Replace('shape = RectangleShape,', 'shape = { RectangleShape },')
$c = $c.Replace('drawRect(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))', 'drawRect(surfaceTint)')
$c = $c.Replace('    Box(', '    val surfaceTint = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)' + "`n" + '    Box(')
[System.IO.File]::WriteAllText($f2, $c, $enc)
"BlurExt fixed"
