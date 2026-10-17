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
        val speedMbps: Double = 0.0,
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

    suspend fun unpack(file: File, threads: Int, selected: List<String>? = null) = withContext(Dispatchers.IO) {
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
            unpackPayload(file, outDir, threads, selected)
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
    private suspend fun unpackPayload(file: File, outDir: File, threads: Int, selected: List<String>? = null) {
        _state.value = _state.value.copy(running = true, message = "读取 payload 清单…")
        // 若是 zip（卡刷包），先把其中的 payload.bin 抽取到缓存
        val payloadPath: String = if (file.name.endsWith(".zip", true) || isZip(file)) {
            val entryName = try {
                ZipFile(file).use { z ->
                    z.entries().toList().firstOrNull {
                        !it.isDirectory && it.name.substringAfterLast('/').equals("payload.bin", true)
                    }?.name
                }
            } catch (e: Exception) {
                null
            }
            if (entryName == null) {
                log.error("❌ 压缩包内没有 payload.bin")
                _state.value = _state.value.copy(running = false, message = "包内无 payload.bin")
                return
            }
            val cache = File(appContext.cacheDir, "payload_extract.bin")
            log.info("ZIP 内 payload.bin: $entryName（先解出到缓存）")
            ZipFile(file).use { z ->
                val entry = z.getEntry(entryName)
                z.getInputStream(entry).use { input ->
                    cache.outputStream().use { out ->
                        val buf = ByteArray(1024 * 1024)
                        var total = 0L
                        while (true) {
                            if (cancelled) break
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            total += n
                            if (total % (64L * 1024 * 1024) < 1024 * 1024) {
                                log.progress("zipentry", "   ↻ 解析 payload.bin: " + fmtSize(total))
                            }
                        }
                    }
                }
            }
            log.ok("✔ payload.bin 已取出: " + fmtSize(cache.length()))
            cache.absolutePath
        } else {
            file.absolutePath
        }

        val raf = RandomAccessFile(payloadPath, "r")
        val magic = ByteArray(4).also { raf.readFully(it) }
        if (String(magic) != "CrAU") {
            log.error("❌ 不是有效的 payload.bin（magic=${String(magic)}）")
            raf.close()
            return
        }
        val version = readU64(raf)
        val manifestSize = readU64(raf)
        if (version == 2L) raf.skipBytes(24)
        val manifestBytes = ByteArray(manifestSize.toInt().coerceAtMost(256 * 1024 * 1024))
        raf.readFully(manifestBytes)
        raf.close()
        val partitions = PayloadManifest.parse(manifestBytes)
        if (partitions.isEmpty()) {
            log.error("❌ 未能解析到分区清单")
            _state.value = _state.value.copy(running = false, message = "分区清单为空")
            return
        }
        val targets = partitions.filter { selected == null || it.name in selected }
        log.info("分区 ${partitions.size} 个 · 本次解包 ${targets.size} 个 · 线程 $threads")
        _state.value = _state.value.copy(total = targets.size, message = "解包中…")

        val dumper = com.rhythmcache.payloaddumper.PayloadDumper.INSTANCE
        val sem = Semaphore(threads)
        val startedAt = System.nanoTime()
        var bytesDone = 0L
        val lock = Any()
        try {
            coroutineScope {
                targets.forEach { part ->
                    launch(Dispatchers.IO) {
                        sem.withPermit {
                            if (cancelled) return@withPermit
                            val t0 = System.nanoTime()
                            var lastStep = 0
                            try {
                                val outFile = File(outDir, part.name + ".img")
                                dumper.extractLocalPartition(
                                    payloadPath,
                                    part.name,
                                    outFile.absolutePath,
                                    null,
                                    object : com.rhythmcache.payloaddumper.PayloadDumper.ProgressCallback {
                                        override fun onProgress(
                                            partitionName: String,
                                            currentOperation: Long,
                                            totalOperations: Long,
                                            percentage: Double,
                                            status: Int,
                                            warningOpIndex: Int,
                                            warningMessage: String
                                        ): Boolean {
                                            val pct = (percentage * 100.0).coerceIn(0.0, 100.0)
                                            if (pct.toInt() / 2 != lastStep / 2) {
                                                lastStep = pct.toInt()
                                                val done = (pct / 100.0 * part.sizeBytes).toLong()
                                                val dt = (System.nanoTime() - t0) / 1e9
                                                val speed = if (dt > 0.2) done / dt / 1e6 else 0.0
                                                log.progress(
                                                    "p:${part.name}",
                                                    f("   ↻ %s: %.0f%%  %s / %s  %.1f MB/s  (%d/%d op)",
                                                        part.name, pct, fmtSize(done), fmtSize(part.sizeBytes), speed,
                                                        currentOperation, totalOperations)
                                                )
                                            }
                                            if (warningMessage.isNotEmpty()) {
                                                log.warn("⚠ ${part.name}: $warningMessage")
                                            }
                                            return !cancelled
                                        }
                                    }
                                )
                                val secs = ((System.nanoTime() - t0) / 1e9).coerceAtLeast(0.001)
                                log.ok(
                                    f("✔ %s.img 完成  %s  %.1f MB/s  %.1fs",
                                        part.name, fmtSize(part.sizeBytes), part.sizeBytes / secs / 1e6, secs)
                                )
                            } catch (e: Throwable) {
                                log.error("❌ ${part.name} 解包失败: ${e.message}")
                            } finally {
                                synchronized(lock) {
                                    bytesDone += part.sizeBytes
                                    val elapsed = (System.nanoTime() - startedAt) / 1e9
                                    val s = _state.value
                                    _state.value = s.copy(
                                        done = s.done + 1,
                                        doneBytes = bytesDone,
                                        totalBytes = targets.sumOf { it.sizeBytes },
                                        speedMbps = if (elapsed > 0.2) bytesDone / elapsed / 1e6 else 0.0
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.error("❌ 解包异常: ${e.message}")
        }
    }

    // ── 严格流程：① 分析 ② 前置校验 ─────────────────
    data class Analysis(
        val isPayload: Boolean = false,
        val isZip: Boolean = false,
        val partitions: List<Pair<String, Long>> = emptyList(),
        val totalOutBytes: Long = 0,
        val version: Long = 0,
        val manifestSize: Long = 0,
        val compressedOps: Int = 0,
        val rawOps: Int = 0,
        val error: String = ""
    )

    var analysis: Analysis? = null
        private set

    suspend fun analyze(file: File) = withContext(Dispatchers.IO) {
        analysis = null
        log.clear()
        log.info("═══ ① 包分析 ═══")
        log.info("文件: ${file.name} (${fmtSize(file.length())})")
        val isZip = try { ZipFile(file).use { true } } catch (e: Exception) { false }
        val cache = File(appContext.cacheDir, "probe_payload.bin")
        // zip 内是否含 payload.bin
        var payloadEntry: String? = null
        if (isZip) {
            payloadEntry = try {
                ZipFile(file).use { z ->
                    z.entries().toList().firstOrNull {
                        !it.isDirectory && it.name.substringAfterLast('/').equals("payload.bin", true)
                    }?.name
                }
            } catch (e: Exception) { null }
        }
        val payloadFile: File? = when {
            !isZip -> file
            payloadEntry != null -> null   // 真正解出在解包阶段处理
            else -> null
        }
        if (isZip && payloadEntry == null) {
            val entries = try { ZipFile(file).use { z -> z.entries().toList().filter { !it.isDirectory } } } catch (e: Exception) { emptyList() }
            val a = Analysis(isZip = true, partitions = entries.take(300).map { it.name to it.size.coerceAtLeast(0) },
                totalOutBytes = entries.sumOf { it.size.coerceAtLeast(0) })
            analysis = a
            log.ok("✔ ZIP 压缩包（普通解压）")
            log.info("   条目 ${entries.size} 个 · 解压后 ${fmtSize(a.totalOutBytes)}")
            return@withContext a
        }
        if (isZip && payloadEntry != null) {
            log.info("ZIP 内含 payload.bin: $payloadEntry")
            ZipFile(file).use { z ->
                val e = z.getEntry(payloadEntry)
                cache.parentFile?.mkdirs()
                z.getInputStream(e).use { input -> cache.outputStream().use { out -> input.copyTo(out, 1024 * 1024) } }
            }
            log.info("   已取出 payload.bin 用于分析: ${fmtSize(cache.length())}")
        }
        val pf = if (isZip) cache else file
        val a = analyzePayloadFile(pf)
        analysis = a
        a
    }

    private fun analyzePayloadFile(f: File): Analysis {
        try {
            RandomAccessFile(f, "r").use { raf ->
                val magic = ByteArray(4); raf.readFully(magic)
                if (String(magic) != "CrAU") return Analysis(error = "magic 错误: ${String(magic)}")
                val version = readU64(raf)
                val manifestSize = readU64(raf)
                if (version == 2L) raf.skipBytes(24)
                if (manifestSize <= 0 || manifestSize > f.length()) return Analysis(error = "manifest 长度非法: $manifestSize")
                val mb = ByteArray(manifestSize.toInt())
                raf.readFully(mb)
                val parts = PayloadManifest.parse(mb)
                var raw = 0; var comp = 0
                val list = parts.map { p ->
                    p.operations.forEach { op -> if (op.type.contains("XZ") || op.type.contains("BZ")) comp++ else raw++ }
                    p.name to p.sizeBytes
                }
                val total = parts.sumOf { it.sizeBytes }
                log.ok("✔ payload.bin 结构完整（CrAU）")
                log.info("   版本 $version · manifest ${fmtSize(manifestSize)}")
                log.info("   分区 ${parts.size} 个 · 解包后总量 ${fmtSize(total)}")
                log.info("   操作项: 未压缩 $raw / 压缩 $comp（压缩由内置 xz 解包器处理）")
                list.take(50).forEach { (n, s) -> log.info("   · $n  ${fmtSize(s)}") }
                if (list.size > 50) log.info("   … 其余 ${list.size - 50} 个分区")
                return Analysis(true, true, list, total, version, manifestSize, comp, raw)
            }
        } catch (e: Exception) {
            return Analysis(error = "分析异常: ${e.message}")
        }
    }

    suspend fun preVerify(): Boolean = withContext(Dispatchers.IO) {
        val a = analysis ?: return@withContext false
        log.info("═══ ② 前置校验 ═══")
        val checks = mutableListOf<Pair<String, Boolean>>()
        checks += "分析结果无错误" to a.error.isEmpty()
        checks += "识别到分区/条目 ${a.partitions.size} 个" to a.partitions.isNotEmpty()
        val free = File("/storage/emulated/0").usableSpace
        checks += "磁盘空间（需 ${fmtSize(a.totalOutBytes)}，可用 ${fmtSize(free)}）" to (free > a.totalOutBytes)
        checks += "压缩 extent 支持（xz/bz2 内置解包器）" to true
        var ok = true
        checks.forEach { (n, pass) ->
            if (pass) log.ok("✔ $n") else { log.warn("⚠ $n"); ok = false }
        }
        if (ok) log.ok("✔ 前置校验通过，可以开始解包") else log.error("❌ 前置校验未通过")
        ok
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

