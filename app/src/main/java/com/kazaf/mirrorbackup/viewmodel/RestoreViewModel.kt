package com.kazaf.mirrorbackup.viewmodel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kazaf.mirrorbackup.core.BackupRecord
import com.kazaf.mirrorbackup.core.ImageFlasher
import com.kazaf.mirrorbackup.core.LogBuffer
import com.kazaf.mirrorbackup.core.RecordsStore
import com.kazaf.mirrorbackup.core.RestoreEngine
import com.kazaf.mirrorbackup.core.SuManager
import com.kazaf.mirrorbackup.core.fmtSize
import com.kazaf.mirrorbackup.core.pure.FlashCheck
import com.kazaf.mirrorbackup.core.pure.FlashMatch
import com.kazaf.mirrorbackup.core.pure.FlashMatchKind
import com.kazaf.mirrorbackup.core.pure.FlashPartition
import com.kazaf.mirrorbackup.core.pure.FlashPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 注意：所有 mutableStateOf 的写入必须发生在主线程（或状态已在主线程初始化之后）。
 * IO 结果一律通过 withContext 回到主线程再赋值，避免 Compose 快照竞态崩溃。
 */
class RestoreViewModel(app: Application) : AndroidViewModel(app) {

    private val su = SuManager
    val log = LogBuffer().also {
        it.attachFile(java.io.File(app.filesDir, "logs/app.log"))
    }
    private val engine = RestoreEngine(app, su, log)
    val progress = engine.progress

    var loaded by mutableStateOf<RestoreEngine.LoadResult?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var forceChecked by mutableStateOf(false)
        private set
    var currentUri by mutableStateOf<Uri?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var records by mutableStateOf<List<BackupRecord>>(emptyList())
        private set

    init {
        // 预加载记录表（主线程发起，IO 结果回到主线程赋值）
        refreshRecords()
    }

    fun setForce(v: Boolean) {
        forceChecked = v
    }

    fun refreshRecords() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { RecordsStore.load(getApplication()) }
            records = list
        }
    }

    fun import(uri: Uri) {
        if (busy || flashBusy) return
        currentUri = uri
        busy = true
        loadError = null
        loaded = null
        forceChecked = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = engine.load(uri)
                withContext(Dispatchers.Main) { loaded = result }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { loadError = e.message }
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    /** 预校验：第①层双重校验（SHA-256 + CRC32，只读提取）+ 第②层大小（不写入任何数据） */
    fun verify() {
        val uri = currentUri ?: return
        val cur = loaded ?: return
        if (busy) return
        busy = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val previews = engine.verify(uri, cur.manifest)
                withContext(Dispatchers.Main) {
                    loaded = cur.copy(previews = previews)
                }
            } catch (_: Exception) {
                // 错误详情已写入日志
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    /** 正式恢复：引擎内部按 ①双重校验 ②大小 ③指纹 顺序强制重新校验后写入 */
    fun restore() {
        val uri = currentUri ?: return
        val cur = loaded ?: return
        if (busy || flashBusy) return
        busy = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                engine.restore(uri, cur.manifest, forceChecked)
            } finally {
                withContext(Dispatchers.Main) { busy = false }
                refreshRecords()
            }
        }
    }

    fun stop() {
        engine.cancelled = true
    }

    // ── 单分区刷入（.img） ────────────────────────────────
    private val flasher = ImageFlasher(app, su, log)

    var flashImage by mutableStateOf<ImageFlasher.PreparedImage?>(null)
        private set
    var flashTarget by mutableStateOf<FlashPartition?>(null)
        private set
    var flashMatch by mutableStateOf<FlashMatch?>(null)
        private set
    var flashCandidates by mutableStateOf<List<FlashPartition>>(emptyList())
        private set
    var flashPartitions by mutableStateOf<List<FlashPartition>>(emptyList())
        private set
    var flashChecks by mutableStateOf<List<FlashCheck>>(emptyList())
        private set
    var flashBusy by mutableStateOf(false)
        private set
    var flashConfirm by mutableStateOf(false)
        private set
    var flashManualPicker by mutableStateOf(false)
        private set
    var flashResult by mutableStateOf<String?>(null)
        private set
    var flashResultOk by mutableStateOf(false)
        private set

    /** 写入进度（字节 done / total），StateFlow 保证跨线程安全 */
    val flashProgress = MutableStateFlow(0L to 0L)
    /** 前置校验提示（非阻断风险） */
    var flashRiskNote by mutableStateOf("")
        private set

    val flashCanStart: Boolean
        get() = flashImage != null && flashTarget != null && !flashBusy && !busy &&
            flashChecks.isNotEmpty() && flashChecks.none { !it.ok && it.fatal }

    fun pickImage(uri: Uri) {
        if (flashBusy || busy) return
        flashBusy = true
        flashResult = null
        flashResultOk = false
        flashImage = null
        flashTarget = null
        flashMatch = null
        flashChecks = emptyList()
        flashRiskNote = ""
        flashProgress.value = 0L to 0L
        viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    flasher.prepare(uri) { step -> log.progress("flash_prep", "   $step") }
                }
                if (prepared == null) {
                    flashResult = "读取镜像失败"
                    return@launch
                }
                val parts = withContext(Dispatchers.IO) { flasher.scan() }
                val m = FlashPlanner.match(prepared.meta, parts)
                flashImage = prepared
                flashPartitions = parts
                flashMatch = m
                flashCandidates = m.candidates
                log.info("🔎 分区扫描：${parts.size} 个 · ${m.note}")
                when (m.kind) {
                    FlashMatchKind.SINGLE -> m.single?.let { applyTarget(prepared, it) }
                    FlashMatchKind.NEED_SLOT -> log.warn(
                        "⚠ A/B 双分区：请选择槽位（${m.candidates.joinToString(" / ") { "${it.name} ${fmtSize(it.sizeBytes)}" }}）"
                    )
                    FlashMatchKind.NONE -> log.warn("⚠ ${m.note}，可从分区列表手动选择")
                }
            } finally {
                flashBusy = false
            }
        }
    }

    /** 用户点选槽位 / 手动选择分区 */
    fun chooseTarget(p: FlashPartition) {
        val img = flashImage ?: return
        viewModelScope.launch { applyTarget(img, p) }
    }

    private suspend fun applyTarget(img: ImageFlasher.PreparedImage, p: FlashPartition) {
        flashTarget = p
        val pf = withContext(Dispatchers.IO) { flasher.preflight(img, p) }
        flashChecks = pf.checks
        flashRiskNote = pf.warnings.joinToString("；") { it.detail.ifEmpty { it.label } }
        val passed = pf.checks.count { it.ok }
        log.info("🧪 前置校验 ${passed}/${pf.checks.size} 项通过（目标 ${p.name} → ${p.path}）")
        pf.checks.filter { !it.ok }.forEach {
            if (it.fatal) log.error("   ❌ ${it.label}${if (it.detail.isEmpty()) "" else "（${it.detail}）"}")
            else log.warn("   ⚠ ${it.label}${if (it.detail.isEmpty()) "" else "（${it.detail}）"}")
        }
        logOk(pf.blocking)
    }

    private fun logOk(blocking: Boolean) {
        if (blocking) log.error("❌ 前置校验未通过，禁止刷入")
        else log.ok("✔ 前置校验通过，可以刷入（还会让你再确认一次）")
    }

    fun openManualPicker() {
        if (flashPartitions.isNotEmpty()) flashManualPicker = true
    }

    fun closeManualPicker() {
        flashManualPicker = false
    }

    fun askFlash() {
        if (flashCanStart) flashConfirm = true
    }

    fun cancelFlash() {
        flashConfirm = false
    }

    fun doFlash() {
        val img = flashImage ?: return
        val target = flashTarget ?: return
        if (flashBusy || !flashCanStart) return
        flashConfirm = false
        flashBusy = true
        flashResult = null
        flashResultOk = false
        flashProgress.value = 0L to img.meta.bytes
        viewModelScope.launch {
            try {
                val verdict = withContext(Dispatchers.IO) {
                    flasher.flash(img, target) { done, total -> flashProgress.value = done to total }
                }
                flashResultOk = verdict.ok
                flashResult = if (verdict.ok) {
                    "✔ 刷入完成，回读 SHA-256 与镜像一致"
                } else {
                    "❌ 刷入失败：${verdict.summary()}"
                }
            } finally {
                withContext(Dispatchers.IO) { flasher.finish() }
                flashBusy = false
                flashProgress.value = 0L to 0L
            }
        }
    }

    // ── 分区镜像导出（Scene：dd if=分区 of=/sdcard/<名>.img）──────────
    var exportBusy by mutableStateOf(false)
        private set
    var exportResult by mutableStateOf<String?>(null)
        private set

    fun exportTarget() {
        val target = flashTarget ?: return
        if (exportBusy || flashBusy) return
        exportBusy = true
        exportResult = null
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { flasher.exportPartition(target) }
                exportResult = if (r.ok) "✔ 已导出到 /sdcard/${target.name}.img" else "❌ 导出失败：${r.summary()}"
            } finally {
                exportBusy = false
            }
        }
    }

    // ── TWRP 注入（Scene：twrp_mode.sh + twrp_install.sh）─────────────
    var twrpFile by mutableStateOf<File?>(null)
        private set
    var twrpModeText by mutableStateOf("")
        private set
    var twrpBusy by mutableStateOf(false)
        private set
    var twrpResult by mutableStateOf<String?>(null)
        private set
    var twrpResultOk by mutableStateOf(false)
        private set

    /** 进入恢复页时探测一次设备是否支持 TWRP 注入 */
    fun detectTwrpSupport() {
        if (twrpBusy) return
        viewModelScope.launch {
            val mode = withContext(Dispatchers.IO) { flasher.twrpMode() }
            twrpModeText = when (mode) {
                com.kazaf.mirrorbackup.core.pure.TwrpMode.BOOT_AB -> "支持：A/B（替换 boot 的 ramdisk）"
                com.kazaf.mirrorbackup.core.pure.TwrpMode.RECOVERY_AB -> "支持：A/B（直接刷入 recovery_a / recovery_b）"
                else -> "不支持：非 A/B，或存在 vendor_boot"
            }
        }
    }

    fun pickTwrp(uri: Uri) {
        if (twrpBusy) return
        twrpBusy = true
        twrpResult = null
        viewModelScope.launch {
            try {
                val f = withContext(Dispatchers.IO) {
                    val dir = java.io.File(getApplication<Application>().cacheDir, "twrp").apply { mkdirs() }
                    val name = queryDisplayName(uri) ?: "twrp.img"
                    val target = java.io.File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { out -> input.copyTo(out, 4 * 1024 * 1024) }
                    }
                    target.takeIf { it.length() > 0 }
                }
                twrpFile = f
                if (f != null) log.info("已选择 TWRP 镜像：${f.name}（${fmtSize(f.length())}）")
                detectTwrpSupport()
            } finally {
                twrpBusy = false
            }
        }
    }

    fun injectTwrp() {
        val f = twrpFile ?: return
        if (twrpBusy) return
        twrpBusy = true
        twrpResult = null
        twrpResultOk = false
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { flasher.injectTwrp(f) }
                twrpResultOk = r.ok
                twrpResult = if (r.ok) "✔ TWRP 注入完成" else "❌ 注入失败：${r.summary()}"
            } finally {
                withContext(Dispatchers.IO) { flasher.finish() }
                twrpBusy = false
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (e: Exception) {
        null
    }
}
