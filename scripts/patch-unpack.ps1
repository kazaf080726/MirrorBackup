$ErrorActionPreference = 'Stop'
$enc = New-Object System.Text.UTF8Encoding($false)
$f = 'D:\Users\Administrator\Documents\DeepSeek Workspace\MirrorBackup\app\src\main\java\com\kazaf\mirrorbackup\core\UnpackEngine.kt'
$lines = [System.IO.File]::ReadAllText($f, [System.Text.Encoding]::UTF8) -split "`n"
# 保留 1..119 行（index 0..118），替换 unpackPayload（index 119..201），保留 index 202 起
$head = $lines[0..118]
$tail = $lines[202..($lines.Length - 1)]
$body = @'
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
'@
$newLines = @()
$newLines += $head
$newLines += ($body -split "`n")
$newLines += $tail
[System.IO.File]::WriteAllLines($f, $newLines, $enc)
"unpackPayload replaced"
# unpack() 签名支持 selected
$c = [System.IO.File]::ReadAllText($f, [System.Text.Encoding]::UTF8)
$c = $c.Replace('    suspend fun unpack(file: File, threads: Int) = withContext(Dispatchers.IO) {', '    suspend fun unpack(file: File, threads: Int, selected: List<String>? = null) = withContext(Dispatchers.IO) {')
$c = $c.Replace('            unpackPayload(file, outDir, threads)', '            unpackPayload(file, outDir, threads, selected)')
[System.IO.File]::WriteAllText($f, $c, $enc)
"signature updated"
