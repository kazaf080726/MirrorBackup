package com.kazaf.mirrorbackup.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * 解包引擎（备份/解包工具箱）：
 * - 支持 ZIP 压缩包并行解压（按用户选择的线程数 1 / 2 / 5 / 满核）
 * - 支持 Android OTA payload.bin 解析（CrAU 头部 + 分区清单 + REPLACE/ZERO 操作）
 * 所有输出写入 /storage/emulated/0/解包工具箱/ 下。
 */
class UnpackEngine(private val appContext: android.content.Context) {

    data class State(
        val running: Boolean = false,
        val file: String = "",
        val total: Int = 0,
        val done: Int = 0,
        val doneBytes: Long = 0,
        val totalBytes: Long = 0,
        val message: String = ""
    )

    val log = LogBuffer()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    @Volatile
    var cancelled = false

    fun reset() {
        cancelled = false
        _state.value = State()
    }

    suspend fun unpack(file: File, threads: Int) = withContext(Dispatchers.IO) {
        cancelled = false
        val outRoot = File("/storage/emulated/0/解包工具箱")
        outRoot.mkdirs()
        val name = file.nameWithoutExtension
        val outDir = File(outRoot, "${name}_${System.currentTimeMillis() / 1000}")
        outDir.mkdirs()
        log.clear()
        log.info("═══ 解包任务开始 ═══")
        log.info("源文件: ${file.absolutePath} (${fmtSize(file.length())})")
        log.info("输出目录: ${outDir.absolutePath}")
        if (file.name.endsWith(".zip", ignoreCase = true)) {
            unpackZip(file, outDir, threads)
        } else if (file.name.contains("payload", ignoreCase = true)) {
            unpackPayload(file, outDir, threads)
        } else {
            // 尝试按 ZIP 处理
            if (isZip(file)) unpackZip(file, outDir, threads)
            else log.error("❌ 不支持的文件类型（仅支持 ZIP / payload.bin）")
        }
        if (!cancelled) log.ok("✔ 解包完成")
        _state.value = _state.value.copy(running = false)
    }

    private fun isZip(f: File): Boolean = try {
        ZipFile(f).use { true }
    } catch (e: Exception) {
        false
    }

    private suspend fun unpackZip(file: File, outDir: File, threads: Int) {
        _state.value = State(running = true, file = file.name, message = "读取 ZIP 目录…")
        val zip = try {
            ZipFile(file)
        } catch (e: Exception) {
            log.error("❌ 无法打开 ZIP: ${e.message}")
            return
        }
        val entries = zip.entries().toList().filter { !it.isDirectory }
        val totalBytes = entries.sumOf { it.size.coerceAtLeast(0) }
        log.info("ZIP 条目: ${entries.size} 个文件 · 解压后总量 ${fmtSize(totalBytes)} · 线程 $threads")
        _state.value = _state.value.copy(total = entries.size, totalBytes = totalBytes, message = "并行解压中…")
        val sem = Semaphore(threads)
        coroutineScope {
            entries.map { entry ->
                launch(Dispatchers.IO) {
                    sem.withPermit {
                        if (cancelled) return@withPermit
                        try {
                            val target = File(outDir, entry.name)
                            target.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                target.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
                            }
                            log.ok("✔ ${entry.name}  (${fmtSize(entry.size)})")
                        } catch (e: Exception) {
                            log.error("❌ ${entry.name} 解压失败: ${e.message}")
                        } finally {
                            synchronized(_state) {
                                _state.value = _state.value.copy(
                                    done = _state.value.done + 1,
                                    doneBytes = _state.value.doneBytes + entry.size.coerceAtLeast(0)
                                )
                            }
                        }
                    }
                }
            }
        }
        zip.close()
    }

    // ── payload.bin ──────────────────────────────────────────────
    private suspend fun unpackPayload(file: File, outDir: File, threads: Int) {
        _state.value = State(running = true, file = file.name, message = "解析 payload 头部…")
        val raf = RandomAccessFile(file, "r")
        val magic = ByteArray(4).also { raf.readFully(it) }
        if (String(magic) != "CrAU") {
            log.error("❌ 不是有效的 payload.bin（magic=${String(magic)}）")
            raf.close()
            return
        }
        val version = readU64(raf)
        val manifestSize = readU64(raf)
        if (version == 2L) raf.skipBytes(4 + 4 + 4 + 4 + 4 + 4) // manifest signature metadata
        val manifestStart = raf.filePointer
        val manifestBytes = ByteArray(manifestSize.toInt().coerceAtMost(256 * 1024 * 1024))
        raf.readFully(manifestBytes)
        log.info("payload 版本 $version · manifest ${fmtSize(manifestSize)}")

        val partitions = PayloadManifest.parse(manifestBytes)
        if (partitions.isEmpty()) {
            log.error("❌ 未能解析到分区（payload 清单为空或格式不支持）")
            raf.close()
            return
        }
        log.info("分区: ${partitions.size} 个 · 线程 $threads")
        val totalOps = partitions.sumOf { it.operations.size }
        _state.value = _state.value.copy(total = partitions.size, message = "解包分区中…")

        val sem = Semaphore(threads)
        coroutineScope {
            partitions.forEachIndexed { idx, part ->
                launch(Dispatchers.IO) {
                    sem.withPermit {
                        if (cancelled) return@withPermit
                        try {
                            val target = File(outDir, part.name + ".img")
                            RandomAccessFile(target, "rw").use { outRaf ->
                                outRaf.setLength(part.sizeBytes)
                                for (op in part.operations) {
                                    if (cancelled) break
                                    when (op.type) {
                                        "REPLACE" -> {
                                            raf.seek(op.dataOffset)
                                            val buf = ByteArray(256 * 1024)
                                            var remaining = op.dataSize
                                            var dst = op.dstStart * 4096L
                                            while (remaining > 0) {
                                                val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                                if (n <= 0) break
                                                outRaf.seek(dst)
                                                outRaf.write(buf, 0, n)
                                                dst += n
                                                remaining -= n
                                            }
                                        }
                                        "ZERO", "REPLACE_ZERO" -> {
                                            // 已是 setLength 全零
                                        }
                                        else -> {
                                            log.warn("⚠ ${part.name}: 跳过压缩操作 ${op.type}（需要 XZ/BZ2 解压支持）")
                                        }
                                    }
                                }
                            }
                            log.ok("✔ ${part.name}.img (${fmtSize(part.sizeBytes)})")
                        } catch (e: Exception) {
                            log.error("❌ ${part.name} 解包失败: ${e.message}")
                        } finally {
                            synchronized(_state) {
                                _state.value = _state.value.copy(done = _state.value.done + 1)
                            }
                        }
                    }
                }
            }
        }
        raf.close()
        val compressed = partitions.flatMap { it.operations }.count {
            it.type.contains("XZ") || it.type.contains("BZ")
        }
        if (compressed > 0) {
            log.warn("⚠ 共有 $compressed 个压缩 extent（xz/bz2），当前版本仅支持未压缩 extent")
        }
    }

    private fun readU64(raf: RandomAccessFile): Long {
        val b = ByteArray(8)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).long
    }

    private fun readU32(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).int
    }
}

/** payload.bin 清单最小解析（protobuf 手写解码） */
object PayloadManifest {
    data class Op(val type: String, val dataOffset: Long, val dataSize: Long, val dstStart: Long)
    data class Part(val name: String, val sizeBytes: Long, val operations: List<Op>)

    private class Reader(val b: ByteArray) {
        var pos = 0
        fun eof() = pos >= b.size
        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (pos < b.size) {
                val x = b[pos++].toInt() and 0xFF
                result = result or ((x and 0x7F).toLong() shl shift)
                if ((x and 0x80) == 0) break
                shift += 7
            }
            return result
        }
        fun bytes(): ByteArray {
            val len = varint().toInt()
            val out = b.copyOfRange(pos, (pos + len).coerceAtMost(b.size))
            pos += len
            return out
        }
        fun tags(): Pair<Int, Int>? {
            if (eof()) return null
            val key = varint().toInt()
            return (key ushr 3) to (key and 7)
        }
    }

    fun parse(bytes: ByteArray): List<Part> {
        val parts = mutableListOf<Part>()
        val r = Reader(bytes)
        while (!r.eof()) {
            val tag = r.tags() ?: break
            when (tag.first) {
                13 -> { // partitions
                    val body = Reader(r.bytes())
                    var name = ""
                    var size = 0L
                    val ops = mutableListOf<Op>()
                    while (!body.eof()) {
                        val t = body.tags() ?: break
                        when (t.first) {
                            1 -> name = String(body.bytes())
                            3 -> size = body.varint()
                            8 -> ops.add(parseOp(body.bytes()))
                            else -> {
                                if (t.second == 0) body.varint() else body.bytes()
                            }
                        }
                    }
                    if (name.isNotEmpty()) parts.add(Part(name, size, ops))
                }
                else -> {
                    if (tag.second == 0) r.varint() else r.bytes()
                }
            }
        }
        return parts
    }

    private fun parseOp(bytes: ByteArray): Op {
        val r = Reader(bytes)
        var type = 0L
        var dataOffset = 0L
        var dataSize = 0L
        var dstStart = 0L
        while (!r.eof()) {
            val t = r.tags() ?: break
            when (t.first) {
                1 -> type = r.varint()
                2 -> dataOffset = r.varint()
                3 -> dataSize = r.varint()
                6, 7 -> { // dst extents（取第一个）
                    val ext = Reader(r.bytes())
                    var start = 0L
                    while (!ext.eof()) {
                        val et = ext.tags() ?: break
                        when (et.first) {
                            1 -> start = ext.varint()
                            else -> if (et.second == 0) ext.varint() else ext.bytes()
                        }
                    }
                    dstStart = start
                }
                else -> if (t.second == 0) r.varint() else r.bytes()
            }
        }
        val typeName = when (type) {
            0L -> "REPLACE"
            1L -> "REPLACE_BZ"
            2L -> "REPLACE_XZ"
            3L -> "ZERO"
            4L -> "DISCARD"
            5L -> "REPLACE_ZERO"
            else -> "UNKNOWN_$type"
        }
        return Op(typeName, dataOffset, dataSize, dstStart)
    }
}
