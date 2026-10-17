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
import com.kazaf.mirrorbackup.core.OutputStore
import com.kazaf.mirrorbackup.core.PartitionInfo
import com.kazaf.mirrorbackup.core.PartitionRules
import com.kazaf.mirrorbackup.core.PartitionScanner
import com.kazaf.mirrorbackup.core.RecordsStore
import com.kazaf.mirrorbackup.core.SuManager
import com.kazaf.mirrorbackup.core.ThreadMode
import com.kazaf.mirrorbackup.core.UserBlacklist
import com.kazaf.mirrorbackup.core.fmtSize
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

    /** 手动模式分区搜索关键字 */
    var partitionQuery by mutableStateOf("")
        private set

    /** 用户自主黑名单（默认空 —— 应用不做任何隐性排除） */
    var userBlacklist by mutableStateOf<Set<String>>(emptySet())
        private set

    private var manualPresetDone = false

    val rootOk: Boolean
        get() = rootCheck?.rootOk == true

    val manualPartitions: List<PartitionInfo>
        get() = partitions.filterNot { it.name in userBlacklist }

    /** 搜索过滤后的可勾选分区 */
    val filteredPartitions: List<PartitionInfo>
        get() = if (partitionQuery.isBlank()) manualPartitions
        else manualPartitions.filter { it.name.contains(partitionQuery.trim(), ignoreCase = true) }

    fun updatePartitionQuery(q: String) {
        partitionQuery = q
    }

    fun deleteRecord(r: BackupRecord) {
        RecordsStore.delete(getApplication(), r)
        refreshRecords()
        log.info("已删除备份记录: ${r.time} · ${r.mode}")
    }

    val selectedTargets: List<PartitionInfo>
        get() = breakdown.targets

    /** 模式排除明细（扫描总数 / 用户黑名单 / super / 大容量 / 目标） */
    val breakdown: PartitionRules.ModeBreakdown
        get() = PartitionRules.analyze(partitions, mode, manualSelection, userBlacklist)

    fun chooseMode(m: BackupMode) {
        if (!running) {
            mode = m
            log.info("已切换备份模式: ${m.label}")
        }
    }

    fun toggleManual(name: String, on: Boolean) {
        if (!running && name !in userBlacklist) {
            manualSelection = if (on) manualSelection + name else manualSelection - name
        }
    }

    fun setManualAll(on: Boolean) {
        if (!running) {
            manualSelection = if (on) manualPartitions.map { it.name }.toSet() else emptySet()
        }
    }

    fun chooseThreadMode(t: ThreadMode) {
        if (!running) {
            threadMode = t
            log.info("已切换线程模式: ${t.label}")
        }
    }

    fun chooseSparse(s: Boolean) {
        if (!running) {
            sparse = s
            log.info("已切换镜像格式: ${if (s) "SPARSE 稀疏镜像" else "RAW 原始镜像"}")
        }
    }

    /** 用户自主黑名单：加/删（所有排除行为完全由用户决定） */
    fun toggleBlacklist(name: String) {
        val next = UserBlacklist.toggle(getApplication(), name)
        userBlacklist = next
        if (name in next) manualSelection = manualSelection - name
    }

    fun syncBlacklist() {
        userBlacklist = UserBlacklist.load(getApplication())
    }

    /** 检测 ROOT（支持 Magisk/KernelSU/APatch 分支）并枚举分区 */
    fun refreshEnvironment() {
        viewModelScope.launch {
            val (check, parts, recs, bl) = withContext(Dispatchers.IO) {
                val c = su.checkRoot()
                val p = if (c.rootOk) PartitionScanner.scan(su, log) else emptyList()
                val r = RecordsStore.load(getApplication())
                val b = UserBlacklist.load(getApplication())
                Quad(c, p, r, b)
            }
            rootCheck = check
            partitions = parts
            userBlacklist = bl
            if (check.rootOk) {
                val black = parts.count { it.name in bl }
                log.info("✅ su 可用: ${check.path}（${check.detail}）")
                log.info("✅ 扫描完成: /dev/block/by-name 共 ${parts.size} 个分区（你的黑名单 $black 个，可自行增删）")
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                engine.run(mode, manualSelection, threadMode, sparse, userBlacklist)
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

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
