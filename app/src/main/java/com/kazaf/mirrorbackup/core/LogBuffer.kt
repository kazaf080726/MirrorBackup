package com.kazaf.mirrorbackup.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { INFO, OK, WARN, ERROR, PROGRESS }

data class LogLine(
    val id: Long,
    val time: String,
    val level: LogLevel,
    val text: String
)

/**
 * 实时滚动日志缓冲（TWRP 风格输出），线程安全。
 * PROGRESS 级别的行按 key 原地刷新，避免日志刷屏。
 * 可选挂接持久化日志文件（便于 ADB 实测排查）。
 */
class LogBuffer(private val maxLines: Int = 4000) {

    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var nextId = 0L
    private val progressKeys = mutableMapOf<String, Long>()

    @Volatile
    private var logFile: File? = null

    /** 挂接持久化日志文件（追加写，上限 2MB） */
    fun attachFile(file: File) {
        logFile = file
        try {
            file.parentFile?.mkdirs()
        } catch (_: Exception) {
        }
    }

    fun info(msg: String) = append(LogLevel.INFO, msg)
    fun ok(msg: String) = append(LogLevel.OK, msg)
    fun warn(msg: String) = append(LogLevel.WARN, msg)
    fun error(msg: String) = append(LogLevel.ERROR, msg)

    fun progress(key: String, msg: String) {
        val existing = progressKeys[key]
        if (existing != null) {
            _lines.value = _lines.value.map {
                if (it.id == existing) it.copy(text = msg, time = now()) else it
            }
        } else {
            val id = nextId++
            progressKeys[key] = id
            appendLine(LogLine(id, now(), LogLevel.PROGRESS, msg))
        }
    }

    fun clear() {
        _lines.value = emptyList()
        progressKeys.clear()
    }

    private fun append(level: LogLevel, msg: String) {
        appendLine(LogLine(nextId++, now(), level, msg))
    }

    private fun appendLine(line: LogLine) {
        val cur = _lines.value
        val kept = if (cur.size >= maxLines) cur.subList(cur.size - maxLines / 2, cur.size) else cur
        _lines.value = kept + line
        logFile?.let { f ->
            try {
                if (f.length() < 2L * 1024 * 1024) {
                    f.appendText("[${line.time}] ${line.text}\n")
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun now(): String = fmt.format(Date())
}
