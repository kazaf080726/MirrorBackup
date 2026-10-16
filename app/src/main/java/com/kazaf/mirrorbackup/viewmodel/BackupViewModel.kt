package com.kazaf.mirrorbackup.viewmodel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kazaf.mirrorbackup.core.BackupEngine
import com.kazaf.mirrorbackup.core.BackupMode
import com.kazaf.mirrorbackup.core.BackupRecord
import com.kazaf.mirrorbackup.core.LogBuffer
import com.kazaf.mirrorbackup.core.PartitionInfo
import com.kazaf.mirrorbackup.core.PartitionRules
import com.kazaf.mirrorbackup.core.PartitionScanner
import com.kazaf.mirrorbackup.core.RecordsStore
import com.kazaf.mirrorbackup.core.SuManager
import com.kazaf.mirrorbackup.core.ThreadMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 注意：所有 mutableStateOf 的写入必须发生在主线程（或状态已在主线程初始化之后）。
 * IO 结果一律通过 withContext 回到主线程再赋值，避免 Compose 快照竞态崩溃。
 */
class BackupViewModel(app: Application) : AndroidViewModel(app) {

    private val su = SuManager
    val log = LogBuffer().also {
        it.attachFile(java.io.File(app.filesDir, "logs/app.log"))
    }
    private val engine = BackupEngine(app, su, log)
    val progress = engine.progress
    val packPrompt = engine.packPrompt

    var mode by mutableStateOf(BackupMode.ALL)
        private set
    var manualSelection by mutableStateOf<Set<String>>(emptySet())
        private set
    var threadMode by mutableStateOf(ThreadMode.MULTI) // 默认多线程
        private set
    var sparse by mutableStateOf(false)
        private set
    var running by mutableStateOf(false)
        private set
    var rootCheck by mutableStateOf<SuManager.SuCheck?>(null)
        private set
    var partitions by mutableStateOf<List<PartitionInfo>>(emptyList())
        private set
    var records by mutableStateOf<List<BackupRecord>>(emptyList())
        private set

    private var manualPresetDone = false

    val rootOk: Boolean
        get() = rootCheck?.rootOk == true

    val manualPartitions: List<PartitionInfo>
        get() = partitions.filterNot { PartitionRules.isBlacklisted(it.name) }

    val selectedTargets: List<PartitionInfo>
        get() = breakdown.targets

    /** 模式排除明细（扫描总数 / 黑名单 / super / 大容量 / 目标） */
    val breakdown: PartitionRules.ModeBreakdown
        get() = PartitionRules.analyze(partitions, mode, manualSelection)

    fun chooseMode(m: BackupMode) {
        if (!running) mode = m
    }

    fun toggleManual(name: String, on: Boolean) {
        if (!running && !PartitionRules.isBlacklisted(name)) {
            manualSelection = if (on) manualSelection + name else manualSelection - name
        }
    }

    fun setManualAll(on: Boolean) {
        if (!running) {
            manualSelection = if (on) manualPartitions.map { it.name }.toSet() else emptySet()
        }
    }

    fun chooseThreadMode(t: ThreadMode) {
        if (!running) threadMode = t
    }

    fun chooseSparse(s: Boolean) {
        if (!running) sparse = s
    }

    /** 检测 ROOT（支持 Magisk/KernelSU/APatch 分支）并枚举分区 */
    fun refreshEnvironment() {
        viewModelScope.launch {
            val (check, parts, recs) = withContext(Dispatchers.IO) {
                val c = su.checkRoot()
                val p = if (c.rootOk) PartitionScanner.scan(su, log) else emptyList()
                val r = RecordsStore.load(getApplication())
                Triple(c, p, r)
            }
            rootCheck = check
            partitions = parts
            if (check.rootOk) {
                val black = parts.count { PartitionRules.isBlacklisted(it.name) }
                log.info("✅ su 可用: ${check.path}（${check.detail}）")
                log.info("✅ 扫描完成: /dev/block/by-name 共 ${parts.size} 个分区，其中黑名单 $black 个按安全规则排除")
            } else {
                log.warn("⚠ ROOT 校验失败: ${check.detail}（请在 KernelSU/Magisk/APatch 管理器授权后点击右上角状态重新检测）")
            }
            if (!manualPresetDone && check.rootOk && parts.isNotEmpty()) {
                manualSelection = parts.filter { PartitionRules.isGreen(it.name) }.map { it.name }.toSet()
                manualPresetDone = true
            }
            records = recs
        }
    }

    fun refreshRecords() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { RecordsStore.load(getApplication()) }
            records = list
        }
    }

    fun start() {
        if (running) return
        running = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                engine.run(mode, manualSelection, threadMode, sparse)
            } finally {
                withContext(Dispatchers.Main) {
                    running = false
                    if (engine.progress.value.phase == BackupEngine.Phase.DONE) {
                        delay(3000)
                        engine.resetProgress() // 自动重置
                    }
                }
                refreshRecords()
            }
        }
    }

    /** 打包选择弹窗回调：uri=null 表示跳过打包 */
    fun answerPack(uri: Uri?) {
        engine.answerPack(uri)
    }

    fun stop() {
        engine.cancelled = true
    }
}
