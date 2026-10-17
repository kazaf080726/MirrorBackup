$ErrorActionPreference = 'Stop'
$enc = New-Object System.Text.UTF8Encoding($false)
$base = 'D:\Users\Administrator\Documents\DeepSeek Workspace\MirrorBackup\app\src\main\java\com\kazaf\mirrorbackup'

function Edit-File($path, [scriptblock]$fn) {
    $c = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
    $c = & $fn $c
    [System.IO.File]::WriteAllText($path, $c, $enc)
}

# ---------- 1) BackupVerifier: fromJson throws -> runCatching ----------
Edit-File "$base\core\BackupVerifier.kt" {
    param($c)
    $old = @'
        val manifest = BackupManifest.fromJson(manifestFile.readText())
        if (manifest == null) {
            log.error("❌ manifest.json 解析失败（文件可能损坏）")
            return Result(dir, 0, 0, emptyList(), emptyList())
        }
'@
    $new = @'
        val manifest = try {
            BackupManifest.fromJson(manifestFile.readText())
        } catch (e: Exception) {
            log.error("❌ manifest.json 解析失败（文件可能损坏）: ${e.message}")
            return Result(dir, 0, 0, emptyList(), emptyList())
        }
'@
    return $c.Replace($old, $new)
}

# ---------- 2) BackupViewModel: search / delete record / space pre-check ----------
Edit-File "$base\viewmodel\BackupViewModel.kt" {
    param($c)
    $c = $c.Replace(
@'
    var records by mutableStateOf<List<BackupRecord>>(emptyList())
        private set
'@,
@'
    var records by mutableStateOf<List<BackupRecord>>(emptyList())
        private set

    /** 手动模式分区搜索关键字 */
    var partitionQuery by mutableStateOf("")
        private set
'@)
    $c = $c.Replace(
@'
    val manualPartitions: List<PartitionInfo>
        get() = partitions.filterNot { it.name in userBlacklist }
'@,
@'
    val manualPartitions: List<PartitionInfo>
        get() = partitions.filterNot { it.name in userBlacklist }

    /** 搜索过滤后的可勾选分区 */
    val filteredPartitions: List<PartitionInfo>
        get() = if (partitionQuery.isBlank()) manualPartitions
        else manualPartitions.filter { it.name.contains(partitionQuery.trim(), ignoreCase = true) }

    fun setPartitionQuery(q: String) {
        partitionQuery = q
    }

    fun deleteRecord(r: BackupRecord) {
        RecordsStore.delete(getApplication(), r)
        refreshRecords()
        log.info("已删除备份记录: ${r.time} · ${r.mode}")
    }
'@)
    $c = $c.Replace(
@'
    fun start() {
        if (running) return
        running = true
'@,
@'
    fun start() {
        if (running) return
        // 剩余空间预检：目标总量 > 可用空间时直接阻断（防止必然失败的大备份）
        val targets = breakdown.targets
        val totalBytes = targets.sumOf { it.sizeBytes }
        val probeDir = OutputStore.rootDir().let { if (it.exists()) it else getApplication<android.app.Application>().filesDir }
        val usable = probeDir.usableSpace
        if (totalBytes > 0 && usable > 0 && totalBytes > usable) {
            log.error("❌ 剩余空间不足：镜像总量 ${fmtSize(totalBytes)} > 可用 ${fmtSize(usable)}")
            log.error("   请释放空间，或在手动模式减少勾选后重试")
            return
        }
        if (totalBytes > 0 && usable > 0 && totalBytes > usable * 0.9) {
            log.warn("⚠ 剩余空间偏紧：镜像 ${fmtSize(totalBytes)} / 可用 ${fmtSize(usable)}")
        }
        running = true
'@)
    return $c
}

# ---------- 3) SettingsViewModel: verify latest backup ----------
Edit-File "$base\viewmodel\SettingsViewModel.kt" {
    param($c)
    $anchor = "    /** 检查更新（仅此时联网访问 GitHub API） */"
    if (-not $c.Contains($anchor)) { throw 'settings vm anchor missing' }
    $insert = @'
    /** 校验最近一次备份（只读：逐镜像 SHA-256 与 manifest 比对） */
    fun verifyLatestBackup() {
        if (busy) return
        busy = true
        lastError = null
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    com.kazaf.mirrorbackup.core.BackupVerifier.latestSession()
                }
                if (session == null) {
                    log.warn("⚠ 未找到「镜像备份」文件夹中的备份会话")
                    lastError = "未找到可校验的备份"
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    com.kazaf.mirrorbackup.core.BackupVerifier.verify(su, session, log)
                }
            } catch (e: Exception) {
                log.error("❌ 校验异常: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

'@
    return $c.Replace($anchor, $insert + $anchor)
}

# ---------- 4) SettingsScreen: verify button in storage card ----------
Edit-File "$base\ui\SettingsScreen.kt" {
    param($c)
    $anchor = @'
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
'@
    if (-not $c.Contains($anchor)) { throw 'settings screen storage anchor missing' }
    $insert = @'
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            MiuiButton(
                text = "校验最近一次备份（SHA-256 逐镜像比对）",
                onClick = { settingsVm.verifyLatestBackup() },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(10.dp))
'@
    return $c.Replace($anchor, $insert)
}

# ---------- 5) BackupScreen: search field + filtered list + record delete ----------
Edit-File "$base\ui\BackupScreen.kt" {
    param($c)
    # 5a 搜索框：在“共扫描 ...”说明后插入
    $anchor = @'
                        Text(
                            "共扫描 ${vm.partitions.size} 个分区 · 你已加入黑名单 ${vm.userBlacklist.size} 个（可在设置页管理）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
'@
    if (-not $c.Contains($anchor)) { throw 'backup screen list anchor missing' }
    $insert = @'
                        Text(
                            "共扫描 ${vm.partitions.size} 个分区 · 你已加入黑名单 ${vm.userBlacklist.size} 个（可在设置页管理）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        androidx.compose.foundation.text.BasicTextField(
                            value = vm.partitionQuery,
                            onValueChange = { vm.setPartitionQuery(it) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.sp
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .androidx.compose.foundation.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            decorationBox = { inner ->
                                if (vm.partitionQuery.isBlank()) {
                                    Text("🔍 搜索分区名…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                inner()
                            }
                        )
'@
    $c = $c.Replace($anchor, $insert)
    # 5b 列表使用 filteredPartitions
    $c = $c.Replace("items(vm.partitions) { part ->", "items(vm.filteredPartitions) { part ->")
    # 5c 记录卡传入删除回调
    $c = $c.Replace(
        "RecordsCard(`n                title = `"备份记录（提取历史）`",",
        "RecordsCard(`n                title = `"备份记录（提取历史）`",")
    $c = $c.Replace(
        "records = vm.records.filter { it.type == `"backup`" }",
        "records = vm.records.filter { it.type == `"backup`" },`n                onDelete = { vm.deleteRecord(it) }")
    return $c
}

# ---------- 6) MiuiComponents: LocalCompactLayout + RecordsCard onDelete ----------
Edit-File "$base\ui\components\MiuiComponents.kt" {
    param($c)
    $c = $c.Replace(
        'val LocalCardGlassEnabled = staticCompositionLocalOf { true }',
        "val LocalCardGlassEnabled = staticCompositionLocalOf { true }`nval LocalCompactLayout = staticCompositionLocalOf { false }")
    # 卡片内边距随紧凑排版
    $c = $c.Replace(
        '        Box(' + "`n" + '            m.liquidGlass(',
        '        Box(' + "`n" + '            m.liquidGlass(')
    $c = $c.Replace(
        'Column(Modifier.padding(16.dp), content = content)',
        'Column(Modifier.padding(if (LocalCompactLayout.current) 12.dp else 16.dp), content = content)')
    # RecordsCard 增加 onDelete 参数
    $c = $c.Replace(
@'
fun RecordsCard(
    title: String,
    records: List<BackupRecord>,
    modifier: Modifier = Modifier
) {
'@,
@'
fun RecordsCard(
    title: String,
    records: List<BackupRecord>,
    modifier: Modifier = Modifier,
    onDelete: ((BackupRecord) -> Unit)? = null
) {
'@)
    # 每行加删除按钮（在 packaged 状态文本后）
    $c = $c.Replace(
@'
                            Text(
                                if (r.packaged) "已打包" else "未打包",
                                fontSize = 10.sp,
                                color = if (r.packaged) MiuiColors.Green else MiuiColors.Orange,
                                modifier = Modifier.weight(0.7f)
                            )
'@,
@'
                            Text(
                                if (r.packaged) "已打包" else "未打包",
                                fontSize = 10.sp,
                                color = if (r.packaged) MiuiColors.Green else MiuiColors.Orange,
                                modifier = Modifier.weight(0.7f)
                            )
                            if (onDelete != null) {
                                Text(
                                    "🗑",
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .clickable { onDelete(r) }
                                        .padding(horizontal = 4.dp)
                                )
                            }
'@)
    return $c
}

# ---------- 7) MainActivity: provide LocalCompactLayout ----------
Edit-File "$base\MainActivity.kt" {
    param($c)
    $c = $c.Replace(
        'com.kazaf.mirrorbackup.ui.components.LocalCardGlassEnabled provides themeCfg.cardGlassEnabled',
        "com.kazaf.mirrorbackup.ui.components.LocalCardGlassEnabled provides themeCfg.cardGlassEnabled`n        com.kazaf.mirrorbackup.ui.components.LocalCompactLayout provides themeCfg.compactLayout")
    return $c
}

"ALL EDITS DONE"
