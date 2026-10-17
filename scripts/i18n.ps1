$ErrorActionPreference = 'Stop'
$enc = New-Object System.Text.UTF8Encoding($false)
$base = 'D:\Users\Administrator\Documents\DeepSeek Workspace\MirrorBackup\app\src\main\java\com\kazaf\mirrorbackup'

# ---------- MainActivity: attachBaseContext + 字符串资源化 ----------
$p = "$base\MainActivity.kt"
$c = [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)
if ($c -notmatch 'attachBaseContext') {
    $anchor = 'class MainActivity : ComponentActivity() {'
    $c = $c.Replace($anchor, @'
class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.kazaf.mirrorbackup.core.LocaleHelper.wrap(newBase))
    }
'@)
}
# imports
if ($c -notmatch 'import androidx\.compose\.ui\.res\.stringResource') {
    $c = $c.Replace('import androidx.compose.ui.text.font.FontWeight', "import androidx.compose.ui.res.stringResource`nimport androidx.compose.ui.text.font.FontWeight")
}
if ($c -notmatch 'import com\.kazaf\.mirrorbackup\.R$') {
    $c = $c.Replace('import com.kazaf.mirrorbackup.core.ThemeState', "import com.kazaf.mirrorbackup.R`nimport com.kazaf.mirrorbackup.core.ThemeState")
}
# header strings
$c = $c.Replace(@'
                Text(
                    "镜像备份",
                    fontSize = 27.sp,
'@, @'
                Text(
                    stringResource(R.string.app_name),
                    fontSize = 27.sp,
'@)
$c = $c.Replace(@'
                Text(
                    "作者：Kazaf · iOS 液态玻璃 · 多线程 · 仅存储",
                    fontSize = 11.sp,
'@, @'
                Text(
                    stringResource(R.string.header_subtitle),
                    fontSize = 11.sp,
'@)
# tab labels
$c = $c.Replace('Text("备份工作台", fontSize = 11.sp)', 'Text(stringResource(R.string.tab_backup), fontSize = 11.sp)')
$c = $c.Replace('Text("恢复工作台", fontSize = 11.sp)', 'Text(stringResource(R.string.tab_restore), fontSize = 11.sp)')
$c = $c.Replace('Text("设置", fontSize = 11.sp)', 'Text(stringResource(R.string.tab_settings), fontSize = 11.sp)')
[System.IO.File]::WriteAllText($p, $c, $enc)
"MainActivity i18n done"

# ---------- BackupScreen: 字符串资源化 ----------
$p2 = "$base\ui\BackupScreen.kt"
$c2 = [System.IO.File]::ReadAllText($p2, [System.Text.Encoding]::UTF8)
if ($c2 -notmatch 'import androidx\.compose\.ui\.res\.stringResource') {
    $c2 = $c2.Replace('import androidx.compose.ui.text.font.FontWeight', "import androidx.compose.ui.res.stringResource`nimport androidx.compose.ui.text.font.FontWeight")
}
if ($c2 -notmatch 'import com\.kazaf\.mirrorbackup\.R$') {
    $c2 = $c2.Replace('import com.kazaf.mirrorbackup.ui.components.CircularProgressRing', "import com.kazaf.mirrorbackup.R`nimport com.kazaf.mirrorbackup.ui.components.CircularProgressRing")
}
$c2 = $c2.Replace(
    'Text(' + "`n" + '                            if (rootOkNow) "工作正常" else "需要授权",',
    'Text(' + "`n" + '                            stringResource(if (rootOkNow) R.string.hero_working else R.string.hero_need_auth),')
$c2 = $c2.Replace(
    '"版本：v${com.kazaf.mirrorbackup.BuildConfig.VERSION_NAME} · $suLabel"',
    'stringResource(R.string.hero_version, com.kazaf.mirrorbackup.BuildConfig.VERSION_NAME, suLabel)')
$c2 = $c2.Replace(
    '"${vm.partitions.size} 个分区 · ${com.kazaf.mirrorbackup.core.CpuDetector.cores} 核 · 满核 ${com.kazaf.mirrorbackup.core.CpuDetector.maxThreads} 线程"',
    'stringResource(R.string.hero_stats, vm.partitions.size, com.kazaf.mirrorbackup.core.CpuDetector.cores, com.kazaf.mirrorbackup.core.CpuDetector.maxThreads)')
$c2 = $c2.Replace('text = "■ 停止任务",', 'text = stringResource(R.string.btn_stop_task),')
$c2 = $c2.Replace('text = "🚀 开始备份",', 'text = stringResource(R.string.btn_start_backup),')
$c2 = $c2.Replace('SectionTitle("选择备份模式")', 'SectionTitle(stringResource(R.string.sec_choose_mode))')
$c2 = $c2.Replace('SectionTitle("备份线程")', 'SectionTitle(stringResource(R.string.sec_threads))')
$c2 = $c2.Replace('SectionTitle("镜像存储格式")', 'SectionTitle(stringResource(R.string.sec_format))')
$c2 = $c2.Replace('SectionTitle("实时日志")', 'SectionTitle(stringResource(R.string.sec_log))')
$c2 = $c2.Replace('title = "备份记录（提取历史）",', 'title = stringResource(R.string.sec_records),')
[System.IO.File]::WriteAllText($p2, $c2, $enc)
"BackupScreen i18n done"

# ---------- SettingsScreen: 主题/存储标题资源化 + 语言选择卡 ----------
$p3 = "$base\ui\SettingsScreen.kt"
$c3 = [System.IO.File]::ReadAllText($p3, [System.Text.Encoding]::UTF8)
if ($c3 -notmatch 'import androidx\.compose\.ui\.res\.stringResource') {
    $c3 = $c3.Replace('import androidx.compose.ui.text.font.FontWeight', "import androidx.compose.ui.res.stringResource`nimport androidx.compose.ui.text.font.FontWeight")
}
if ($c3 -notmatch 'import com\.kazaf\.mirrorbackup\.R$') {
    $c3 = $c3.Replace('import com.kazaf.mirrorbackup.core.OutputStore', "import com.kazaf.mirrorbackup.R`nimport com.kazaf.mirrorbackup.core.OutputStore")
}
$c3 = $c3.Replace('Text("🎨 主题设置", fontSize = 15.sp, fontWeight = FontWeight.Bold)', 'Text(stringResource(R.string.theme_entry_title), fontSize = 15.sp, fontWeight = FontWeight.Bold)')
$c3 = $c3.Replace('"颜色模式 / Key color / Color Style / Color Spec / 液态玻璃",', 'stringResource(R.string.theme_entry_desc),')
$c3 = $c3.Replace('SectionTitle("📁 存储位置")', 'SectionTitle(stringResource(R.string.storage_title))')
# 语言选择卡：插在主题入口卡之后
$langAnchor = @'
        Spacer(Modifier.height(10.dp))

        // ── 存储位置（内部存储根目录「镜像备份」文件夹）──
'@
$langCard = @'
        Spacer(Modifier.height(10.dp))

        // ── 语言 / Language ──
        LiquidGlassCard(Modifier.fillMaxWidth()) {
            val act = LocalContext.current
            SectionTitle(stringResource(R.string.lang_title))
            var lang by remember { mutableStateOf(com.kazaf.mirrorbackup.core.LocaleHelper.current(act)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.kazaf.mirrorbackup.core.LocaleHelper.OPTIONS.forEach { tag ->
                    val label = when (tag) {
                        "" -> stringResource(R.string.lang_system)
                        "zh-CN" -> stringResource(R.string.lang_zh_cn)
                        "zh-TW" -> stringResource(R.string.lang_zh_tw)
                        else -> stringResource(R.string.lang_en)
                    }
                    val selected = lang == tag
                    Surface(
                        onClick = {
                            lang = tag
                            com.kazaf.mirrorbackup.core.LocaleHelper.setLanguage(act, tag)
                            (act as? android.app.Activity)?.recreate()
                        },
                        shape = RoundedCornerShape(999.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        )
                    ) {
                        Text(
                            label,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ── 存储位置（内部存储根目录「镜像备份」文件夹）──
'@
if (-not $c3.Contains($langAnchor)) { throw 'settings lang anchor missing' }
$c3 = $c3.Replace($langAnchor, $langCard)
[System.IO.File]::WriteAllText($p3, $c3, $enc)
"SettingsScreen i18n done"
