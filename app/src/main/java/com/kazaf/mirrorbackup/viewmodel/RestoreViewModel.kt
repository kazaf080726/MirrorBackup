package com.kazaf.mirrorbackup.viewmodel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kazaf.mirrorbackup.core.BackupRecord
import com.kazaf.mirrorbackup.core.LogBuffer
import com.kazaf.mirrorbackup.core.RecordsStore
import com.kazaf.mirrorbackup.core.RestoreEngine
import com.kazaf.mirrorbackup.core.SuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        if (busy) return
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
        if (busy) return
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
}
