$ErrorActionPreference = 'Stop'
$enc = New-Object System.Text.UTF8Encoding($false)
$f = 'D:\Users\Administrator\Documents\DeepSeek Workspace\MirrorBackup\app\src\main\java\com\kazaf\mirrorbackup\core\UnpackEngine.kt'
$c = [System.IO.File]::ReadAllText($f, [System.Text.Encoding]::UTF8)
$nl = if ($c.Contains("`r`n")) { "`r`n" } else { "`n" }

# 1) 分区数据模型 + JNI 分区表
$anchor = '    var analysis: Analysis? = null'
$add = @'
    data class JPart(val name: String, val size: Long, val ops: Int, val compression: String, val hash: String)

    var jniPartitions: List<JPart> = emptyList()
        private set

    var jniPayloadPath: String = ""
        private set

    /** 用本机原生库读取分区清单（zip 会先取出 payload.bin） */
    private fun jniList(path: String): List<JPart> {
        return try {
            val json = com.rhythmcache.payloaddumper.PayloadDumper.INSTANCE.listLocalPartitions(path)
            val arr = org.json.JSONObject(json).optJSONArray("partitions") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                JPart(
                    name = o.optString("name"),
                    size = o.optLong("size_bytes"),
                    ops = o.optInt("operations_count"),
                    compression = o.optString("compression_type"),
                    hash = o.optString("hash")
                )
            }.filter { it.name.isNotEmpty() }
        } catch (e: Throwable) {
            log.error("❌ 原生清单读取失败: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

'@
if (-not $c.Contains($anchor)) { throw 'analysis anchor missing' }
$c = $c.Replace($anchor, $add + $anchor)

# 2) analyzePayloadFile 改为优先 JNI
$oldStart = '    private fun analyzePayloadFile(f: File): Analysis {'
$oldBodyStart = $c.IndexOf($oldStart)
if ($oldBodyStart -lt 0) { throw 'analyzePayloadFile missing' }
$oldBodyEnd = $c.IndexOf('    suspend fun preVerify', $oldBodyStart)
$newFn = @'
    private fun analyzePayloadFile(f: File): Analysis {
        // 优先使用本机原生库（完整支持 xz，且给出精确分区/hash）
        val jni = jniList(f.absolutePath)
        if (jni.isNotEmpty()) {
            jniPartitions = jni
            jniPayloadPath = f.absolutePath
            val total = jni.sumOf { it.size }
            log.ok("✔ payload.bin 结构完整（原生解析）")
            log.info("   分区 ${jni.size} 个 · 解包后总量 ${fmtSize(total)}")
            val comp = jni.count { it.compression.isNotEmpty() && it.compression != "none" }
            log.info("   压缩类型: xz $comp 个 · 已带 SHA-256 校验值 ${jni.count { it.hash.isNotEmpty() }} 个")
            jni.take(60).forEach { p ->
                log.info("   · ${p.name}  ${fmtSize(p.size)}  ${p.ops} op  ${p.compression}")
            }
            if (jni.size > 60) log.info("   … 其余 ${jni.size - 60} 个分区")
            return Analysis(
                isPayload = true, isZip = true,
                partitions = jni.map { it.name to it.size },
                totalOutBytes = total,
                version = 2, manifestSize = 0, compressedOps = comp, rawOps = jni.sumOf { it.ops } - comp
            )
        }
        // 兜底：本地 Kotlin 解析
        return try {
            RandomAccessFile(f, "r").use { raf ->
                val magic = ByteArray(4); raf.readFully(magic)
                if (String(magic) != "CrAU") return Analysis(error = "magic 错误: ${String(magic)}")
                val version = readU64(raf)
                val manifestSize = readU64(raf)
                if (version == 2L) raf.skipBytes(24)
                val mb = ByteArray(manifestSize.toInt().coerceAtMost(256 * 1024 * 1024))
                raf.readFully(mb)
                val parts = PayloadManifest.parse(mb)
                Analysis(true, true, parts.map { it.name to it.sizeBytes }, parts.sumOf { it.sizeBytes }, version, manifestSize)
            }
        } catch (e: Exception) {
            Analysis(error = "分析异常: ${e.message}")
        }
    }

'@
$c = $c.Substring(0, $oldBodyStart) + $newFn + $c.Substring($oldBodyEnd)

# 3) unpackPayload 改为 JNI 提取
$s = $c.IndexOf('    private suspend fun unpackPayload(file: File, outDir: File, threads: Int, selected: List<String>? = null) {')
$e2 = $c.IndexOf('    // ── 严格流程', $s)
if ($s -lt 0 -or $e2 -lt 0) { throw 'unpackPayload block missing' }
$newUnpack = @'
    private suspend fun unpackPayload(file: File, outDir: File, threads: Int, selected: List<String>? = null) {
        _state.value = _state.value.copy(running = true, message = "准备解包…")
        // 取得 payload 路径（zip 先取 payload.bin）
        val payloadPath: String = if (jniPayloadPath.isNotEmpty()) {
            jniPayloadPath
        } else if (isZip(file)) {
            val entryName = try {
                ZipFile(file).use { z ->
                    z.entries().toList().firstOrNull {
                        !it.isDirectory && it.name.substringAfterLast('/').equals("payload.bin", true)
                    }?.name
                }
            } catch (e: Exception) { null }
            if (entryName == null) {
                log.error("❌ 压缩包内没有 payload.bin")
                _state.value = _state.value.copy(running = false)
                return
            }
            val cache = File(appContext.cacheDir, "payload_extract.bin")
            if (!cache.exists() || cache.length() == 0L) {
                log.info("ZIP 内 payload.bin: $entryName（取出中）")
                ZipFile(file).use { z ->
                    val entry = z.getEntry(entryName)
                    z.getInputStream(entry).use { input ->
                        cache.outputStream().use { out -> input.copyTo(out, 4 * 1024 * 1024) }
                    }
                }
            }
            log.ok("✔ payload.bin: " + fmtSize(cache.length()))
            jniPayloadPath = cache.absolutePath
            cache.absolutePath
        } else {
            file.absolutePath
        }

        val parts = if (jniPartitions.isEmpty()) jniList(payloadPath) else jniPartitions
        if (parts.isEmpty()) {
            log.error("❌ 无法读取分区清单")
            _state.value = _state.value.copy(running = false)
            return
        }
        val targets = parts.filter { selected == null || it.name in selected }
        val totalBytes = targets.sumOf { it.size }
        log.info("═══ 开始解包（" + (if (selected == null) "全量" else "部分") + "：${targets.size} 个分区 · $threads 线程）═══")
        _state.value = _state.value.copy(total = targets.size, done = 0, totalBytes = totalBytes, doneBytes = 0, message = "解包中…")

        val dumper = com.rhythmcache.payloaddumper.PayloadDumper.INSTANCE
        val sem = Semaphore(threads)
        val startedAt = System.nanoTime()
        var bytesDone = 0L
        var okCount = 0
        var failCount = 0
        val lock = Any()
        try {
            coroutineScope {
                targets.forEach { part ->
                    launch(Dispatchers.IO) {
                        sem.withPermit {
                            if (cancelled) return@withPermit
                            val t0 = System.nanoTime()
                            var lastPct = 0.0
                            val outFile = File(outDir, part.name + ".img")
                            try {
                                dumper.extractLocalPartition(
                                    payloadPath,
                                    part.name,
                                    outFile.absolutePath,
                                    null,
                                    object : com.rhythmcache.payloaddumper.PayloadDumper.ProgressCallback {
                                        override fun onProgress(
                                            partitionName: String, currentOperation: Long, totalOperations: Long,
                                            percentage: Double, status: Int, warningOpIndex: Int, warningMessage: String
                                        ): Boolean {
                                            val pct = (percentage * 100.0).coerceIn(0.0, 100.0)
                                            if (pct - lastPct >= 2.0 || pct >= 100.0) {
                                                lastPct = pct
                                                val done = (pct / 100.0 * part.size).toLong()
                                                val dt = (System.nanoTime() - t0) / 1e9
                                                val sp = if (dt > 0.2) done / dt / 1e6 else 0.0
                                                log.progress("j:${part.name}", f("   ↻ %s: %.0f%%  %s / %s  %.1f MB/s", part.name, pct, fmtSize(done), fmtSize(part.size), sp))
                                            }
                                            if (warningMessage.isNotEmpty()) log.warn("⚠ ${part.name}: $warningMessage")
                                            return !cancelled
                                        }
                                    }
                                )
                                val secs = ((System.nanoTime() - t0) / 1e9).coerceAtLeast(0.001)
                                val realSize = outFile.length()
                                log.ok(f("✔ %s.img 完成  %s  实际 %s  %.1f MB/s  %.1fs", part.name, fmtSize(part.size), fmtSize(realSize), part.size / secs / 1e6, secs))
                                synchronized(lock) { okCount++ }
                            } catch (e: Throwable) {
                                log.error("❌ ${part.name} 失败: ${e.javaClass.simpleName}: ${e.message}")
                                synchronized(lock) { failCount++ }
                            } finally {
                                synchronized(lock) {
                                    bytesDone += part.size
                                    val elapsed = (System.nanoTime() - startedAt) / 1e9
                                    val s2 = _state.value
                                    _state.value = s2.copy(done = s2.done + 1, doneBytes = bytesDone, speedMbps = if (elapsed > 0.2) bytesDone / elapsed / 1e6 else 0.0)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.error("❌ 解包异常: ${e.message}")
        }
        if (!cancelled) {
            val totalSecs = (System.nanoTime() - startedAt) / 1e9
            log.ok(f("✔✔✔ 解包结束：成功 %d 个 / 失败 %d 个 · 总耗时 %.1fs · 输出 %s", okCount, failCount, totalSecs, outDir.absolutePath))
            _state.value = _state.value.copy(running = false, message = if (failCount == 0) "解包完成" else "部分失败")
        }
    }

'@
$c = $c.Substring(0, $s) + $newUnpack + $c.Substring($e2)
[System.IO.File]::WriteAllText($f, $c, $enc)
"engine rewritten to JNI extraction"
