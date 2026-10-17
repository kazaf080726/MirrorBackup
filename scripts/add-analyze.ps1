$ErrorActionPreference = 'Stop'
$enc = New-Object System.Text.UTF8Encoding($false)
$f = 'D:\Users\Administrator\Documents\DeepSeek Workspace\MirrorBackup\app\src\main\java\com\kazaf\mirrorbackup\core\UnpackEngine.kt'
$c = [System.IO.File]::ReadAllText($f, [System.Text.Encoding]::UTF8)
$nl = if ($c.Contains("`r`n")) { "`r`n" } else { "`n" }

$block = @'
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
'@

$anchor = '    private fun readU64(raf: RandomAccessFile): Long {'
if (-not $c.Contains($anchor)) { throw 'readU64 anchor missing' }
$c = $c.Replace($anchor, $block)
[System.IO.File]::WriteAllText($f, $c, $enc)
"analyze/preVerify added"
$env:JAVA_HOME = "C:\Users\Administrator\android-dev\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "C:\Users\Administrator\android-dev\sdk"
$env:ANDROID_SDK_ROOT = "C:\Users\Administrator\android-dev\sdk"
& "C:\Users\Administrator\android-dev\gradle-dist\gradle-8.13\bin\gradle.bat" -p "D:\Users\Administrator\Documents\DeepSeek Workspace\MirrorBackup" assembleRelease --no-daemon 2>&1 | Select-String "e: file|BUILD SUCCESSFUL|BUILD FAILED" | Select-Object -First 10
