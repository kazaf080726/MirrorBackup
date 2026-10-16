package com.kazaf.mirrorbackup.core

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupAbort(message: String) : Exception(message)

/**
 * 备份引擎 v2 —— 多线程并行备份。
 *
 * 硬性安全规则：
 * 1) su 可用性 + 完整 root 权限校验（uid=0），失败立即终止；
 * 2) 分区节点有效性 / IO 检测（dd 试读），异常立即停止一切读写；
 * 3) dd | tee | sha256sum 管道 —— 边备份边实时 SHA-256；
 * 4) 每备份一个镜像立即做一次完整性校验（大小 + 哈希重读比对）；
 * 5) 全部完成后做全量完整性校验（SHA-256 + CRC32 重读比对）；
 * 6) 用户选择是否打包：ZIP 仅存储（STORE，绝不压缩）/ 跳过打包；
 * 7) 无论是否打包均写入备份记录表；任务结束自动重置。
 * 黑名单分区（boot/dtbo/vbmeta/vbmeta_system/recovery）永不触碰。
 */
class BackupEngine(
    private val context: Context,
    private val su: SuManager,
    private val log: LogBuffer
) {

    @Volatile
    var cancelled = false

    enum class Phase {
        IDLE, PREFLIGHT, BACKING_UP, VERIFYING, WAITING_CHOICE, PACKING, DONE, FAILED, CANCELLED
    }

    data class Progress(
        val phase: Phase,
        val partitionName: String,
        val bytesDone: Long,
        val bytesTotal: Long,
        val speedMBps: Double,
        val etaSeconds: Long,
        val detail: String
    )

    data class PackPrompt(val totalBytes: Long, val imageCount: Int)

    private val _progress = MutableStateFlow(Progress(Phase.IDLE, "", 0, 0, 0.0, 0, ""))
    val progress: StateFlow<Progress> = _progress

    private val _packPrompt = MutableStateFlow<PackPrompt?>(null)
    val packPrompt: StateFlow<PackPrompt?> = _packPrompt

    @Volatile
    private var packAnswer: Uri? = null

    /** UI 在打包选择弹窗中调用：uri=null 表示跳过打包 */
    fun answerPack(uri: Uri?) {
        packAnswer = uri
        _packPrompt.value = null
    }

    fun resetProgress() {
        _progress.value = Progress(Phase.IDLE, "", 0, 0, 0.0, 0, "")
    }

    private val workDir: File
        get() = File(context.filesDir, "backup_work")

    private fun setProgress(p: Progress) {
        _progress.value = p
    }

    private data class ImageResult(
        val name: String,
        val file: File,
        val originalBytes: Long,
        val sha256: String,
        val crc32: Long,
        val format: String,
        val backedAt: String,
        val rawSha256: String?
    )

    suspend fun run(
        mode: BackupMode,
        manualSelection: Set<String>,
        threadMode: ThreadMode,
        sparse: Boolean,
        userBlacklist: Set<String>
    ) {
        cancelled = false
        _packPrompt.value = null
        packAnswer = null
        log.clear()
        setProgress(Progress(Phase.PREFLIGHT, "", 0, 0, 0.0, 0, "安全前置校验中…"))
        log.info("═══ 镜像备份任务开始 ═══")
        log.info("备份模式: ${mode.label} | 线程: ${threadMode.label}(${threadMode.count}) | 镜像格式: ${if (sparse) "SPARSE 稀疏镜像" else "RAW 原始镜像"}")
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
        val startMs = System.currentTimeMillis()
        try {
            // ── 规则1-1：su 与 ROOT 权限校验 ──
            val suPath = su.findSu()
            if (suPath == null) {
                log.error("❌ 未找到 su 二进制（Magisk/KernelSU/APatch 路径均未命中），权限不足，任务终止")
                setProgress(Progress(Phase.FAILED, "", 0, 0, 0.0, 0, "未找到 su 二进制"))
                return
            }
            val check = su.checkRoot()
            if (!check.rootOk) {
                log.error("❌ ROOT 权限校验失败: ${check.detail}，任务终止")
                setProgress(Progress(Phase.FAILED, "", 0, 0, 0.0, 0, "ROOT 权限校验失败"))
                return
            }
            log.ok("✔ su 可用: $suPath   ${check.detail}")

            // ── 设备信息与指纹 ──
            val device = DeviceInfoCollector.collect(su)
            log.info("设备型号: ${device.model} (${device.manufacturer}) | Android ${device.androidVersion} / SDK ${device.sdkInt}")
            log.info("设备指纹(SN+CPU ID): ${device.fingerprint}")
            if (device.sn.isBlank()) log.warn("⚠ 未获取到 SN，指纹将以 CPU ID 为主")
            if (device.cpuId.isBlank()) log.warn("⚠ 未获取到 CPU ID，恢复时指纹匹配可能受限")

            // ── 规则3：分区枚举 + 模式筛选（排除仅来自用户黑名单，无任何隐性排除） ──
            log.info("正在扫描 /dev/block/by-name …")
            val all = PartitionScanner.scan(su, log)
            if (all.isEmpty()) {
                log.error("❌ 未枚举到任何分区节点，任务终止")
                setProgress(Progress(Phase.FAILED, "", 0, 0, 0.0, 0, "分区枚举失败"))
                return
            }
            val targets = PartitionRules.select(all, mode, manualSelection, userBlacklist)
            val breakdown = PartitionRules.analyze(all, mode, manualSelection, userBlacklist)
            log.info("✅ 扫描完成: /dev/block/by-name 共 ${all.size} 个分区")
            if (breakdown.userBlacklisted.isNotEmpty()) {
                log.info("⛔ 按你的黑名单排除 ${breakdown.userBlacklisted.size} 个: ${breakdown.userBlacklisted.joinToString(" ") { it.name }}")
            }
            val risky = breakdown.riskyTargets
            if (risky.isNotEmpty()) {
                log.warn("⚠ 风险提示：本次目标包含引导相关分区（${risky.joinToString(" ") { it.name }}），镜像切勿刷入不匹配的设备")
            }
            if (mode == BackupMode.NORMAL) {
                val skipped = breakdown.superParts + breakdown.largeParts
                if (skipped.isNotEmpty()) {
                    log.info("普通备份模式已跳过 ${skipped.size} 个（super 或 >10GB）: ${skipped.joinToString(" ") { "${it.name}(${fmtSize(it.sizeBytes)})" }}")
                }
            }
            if (targets.isEmpty()) {
                log.error("❌ 没有可备份的目标分区，任务终止")
                setProgress(Progress(Phase.FAILED, "", 0, 0, 0.0, 0, "无目标分区"))
                return
            }
            log.info("本次备份目标 (${targets.size} 个): ${targets.joinToString(" ") { "${it.name}(${fmtSize(it.sizeBytes)})" }}")

            // ── 规则1-2：分区节点有效性 / IO 检测 ──
            log.info("正在执行分区节点有效性检测（dd 试读）…")
            for (t in targets) {
                val (ok, msg) = PartitionScanner.validatePartition(su, t.name)
                if (!ok) {
                    log.error("❌ 分区 ${t.name} 节点无法正常打开 / IO 异常，立即停止一切读写")
                    log.error("   $msg")
                    setProgress(Progress(Phase.FAILED, t.name, 0, t.sizeBytes, 0.0, 0, "分区IO异常"))
                    return
                }
            }
            log.ok("✔ 全部目标分区节点有效，可正常打开")

            // ── 工作目录 ──
            val dir = workDir
            if (dir.exists()) {
                log.warn("⚠ 清理上次遗留的工作目录（未打包的镜像将被删除）")
                dir.deleteRecursively()
            }
            dir.mkdirs()
            val totalBytes = targets.sumOf { it.sizeBytes }
            log.info("工作目录: ${dir.absolutePath} | 可用空间 ${fmtSize(dir.usableSpace)} | 预估镜像总量 ${fmtSize(totalBytes)}")
            if (dir.usableSpace < totalBytes) {
                log.warn("⚠ 可用空间小于镜像总量，任务可能因空间不足失败")
            }

            // ── 多线程并行备份（每镜像完成后立即完整性校验） ──
            log.info("── 开始并行备份（${threadMode.count} 线程）──")
            val results = backupParallel(targets, threadMode.count, sparse, ts)

            // ── 全量完整性校验（SHA-256 + CRC32） ──
            val verified = fullVerify(results, threadMode.count)

            // ── manifest.json ──
            log.info("正在生成 manifest.json 清单文件…")
            val manifest = BackupManifest(
                manifestVersion = 1,
                appName = "镜像备份",
                appVersion = "2.0.0",
                mode = mode.key,
                createdAt = ts.format(Date()),
                format = if (sparse) "sparse" else "raw",
                model = device.model,
                manufacturer = device.manufacturer,
                androidVersion = device.androidVersion,
                sdkInt = device.sdkInt,
                buildFingerprint = device.buildFingerprint,
                sn = device.sn,
                cpuId = device.cpuId,
                fingerprint = device.fingerprint,
                partitions = verified.values.map {
                    ManifestPartition(
                        name = it.name,
                        path = "/dev/block/by-name/${it.name}",
                        sizeBytes = it.originalBytes,
                        sha256 = it.sha256,
                        format = it.format,
                        backedAt = it.backedAt
                    )
                }
            )
            val manifestFile = File(dir, "manifest.json")
            manifestFile.writeText(manifest.toJson())
            log.ok("✔ manifest.json 已生成")

            // ── 打包选择（用户决定） ──
            val finalBytes = manifestFile.length() + verified.values.sumOf { it.file.length() }
            _packPrompt.value = PackPrompt(finalBytes, verified.size)
            setProgress(Progress(Phase.WAITING_CHOICE, "", 0, 0, 0.0, 0, "等待选择打包方式…"))
            log.info("等待用户选择：打包为 ZIP（仅存储）或跳过打包…")
            val uri = awaitPack()

            var packaged = false
            var location = "未打包（镜像保留于 ${dir.absolutePath}）"
            if (uri != null) {
                log.info("正在打包 ZIP（仅存储 STORE，不进行任何压缩）并写入导出位置…")
                packZipStore(manifestFile, verified, uri)
                packaged = true
                location = uri.toString()
                log.ok("✔ ZIP 打包完成（仅存储模式）")
            } else {
                log.info("已跳过打包，任务直接结束")
            }

            // ── 写入备份记录表 ──
            val durationSec = (System.currentTimeMillis() - startMs) / 1000
            RecordsStore.add(
                context,
                BackupRecord(
                    time = ts.format(Date()),
                    type = "backup",
                    mode = mode.label,
                    threadMode = threadMode.label,
                    format = if (sparse) "sparse" else "raw",
                    packaged = packaged,
                    location = location,
                    imageCount = verified.size,
                    images = verified.values.map { it.name },
                    totalBytes = finalBytes,
                    durationSec = durationSec
                )
            )
            log.info("📋 备份记录已写入（累计记录见备份工作台记录表）")

            log.ok("✔✔✔ 备份完成：${verified.size} 个镜像提取成功，耗时 ${durationSec}s ✔✔✔")
            setProgress(Progress(Phase.DONE, "", 0, 0, 0.0, 0, "任务完成，已自动重置"))
        } catch (ce: CancellationException) {
            log.warn("⚠ 任务已被用户停止")
            _packPrompt.value = null
            setProgress(Progress(Phase.CANCELLED, "", 0, 0, 0.0, 0, "已停止"))
            throw ce
        } catch (e: Exception) {
            log.error("❌ 任务失败: ${e.message}")
            _packPrompt.value = null
            setProgress(Progress(Phase.FAILED, "", 0, 0, 0.0, 0, e.message ?: "任务失败"))
        } finally {
            if (packagedSafe()) {
                try {
                    workDir.deleteRecursively()
                } catch (_: Exception) {
                }
            }
        }
    }

    @Volatile
    private var packagedDone = false

    private fun packagedSafe(): Boolean = packagedDone

    /** 等待用户在打包弹窗中做出选择 */
    private suspend fun awaitPack(): Uri? {
        while (_packPrompt.value != null) {
            delay(200)
            if (cancelled) throw CancellationException("用户取消")
        }
        return packAnswer
    }

    /**
     * 多线程并行备份：每分区一个协程（Semaphore 限流），
     * dd 管道实时哈希 → 单镜像完整性校验 → （可选）稀疏转换。
     */
    private suspend fun backupParallel(
        targets: List<PartitionInfo>,
        threads: Int,
        sparse: Boolean,
        ts: SimpleDateFormat
    ): Map<String, ImageResult> {
        val results = ConcurrentHashMap<String, ImageResult>()
        val partBytes = ConcurrentHashMap<String, Long>()
        val active = ConcurrentHashMap.newKeySet<String>()
        targets.forEach { partBytes[it.name] = 0L }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val poller = scope.launch {
                var last = 0L
                var lastT = System.nanoTime()
                while (isActive) {
                    delay(300)
                    val total = targets.sumOf { it.sizeBytes }
                    val done = targets.sumOf { minOf(partBytes[it.name] ?: 0L, it.sizeBytes) }
                    val dt = (System.nanoTime() - lastT) / 1e9
                    val speed = if (dt > 0.05) (done - last) / dt else 0.0
                    last = done
                    lastT = System.nanoTime()
                    val eta = if (speed > 0) ((total - done) / speed).toLong() else -1
                    setProgress(
                        Progress(
                            Phase.BACKING_UP,
                            active.toList().sorted().joinToString(" "),
                            done, total, speed / 1e6, eta, ""
                        )
                    )
                }
            }
            val sem = Semaphore(threads)
            val jobs = targets.map { t ->
                scope.launch {
                    sem.withPermit {
                        active += t.name
                        try {
                            results[t.name] = backupPartition(t, partBytes, sparse, ts)
                        } finally {
                            partBytes[t.name] = t.sizeBytes
                            active -= t.name
                        }
                    }
                }
            }
            jobs.joinAll()
            poller.cancel()
            if (cancelled) throw CancellationException("用户取消")
        } finally {
            scope.cancel()
        }

        val failed = targets.filter { !results.containsKey(it.name) }
        if (failed.isNotEmpty()) {
            log.error("❌ 以下分区备份失败: ${failed.joinToString(" ") { it.name }}")
            throw BackupAbort("分区备份失败: ${failed.joinToString(" ") { it.name }}")
        }
        log.ok("✔ 并行备份阶段完成（${results.size}/${targets.size} 个分区）")
        return results.toMap()
    }

    /** 单分区工作协程：dd 管道 + 实时哈希 + 单镜像完整性校验 + 可选稀疏转换 */
    private suspend fun backupPartition(
        part: PartitionInfo,
        partBytes: ConcurrentHashMap<String, Long>,
        sparse: Boolean,
        ts: SimpleDateFormat
    ): ImageResult {
        val dir = workDir
        val rawFile = File(dir, "${part.name}.img")
        val errFile = File(dir, "${part.name}.dd.err")
        val hashFile = File(dir, "${part.name}.sha256")
        val src = "/dev/block/by-name/${part.name}"
        log.info("▶ [线程] 开始备份: ${part.name} (${fmtSize(part.sizeBytes)}) ← $src")

        // 优先使用提取辅助模块 mb_dd（单进程 dd + 内置 SHA-256，替代 shell 管道链）
        val hasMbdd = try {
            su.exec("command -v mb_dd 2>/dev/null", 8_000).stdout.trim().startsWith("/")
        } catch (e: Exception) {
            false
        }
        val proc = if (hasMbdd) {
            log.info("   ⚡ 使用提取辅助模块 mb_dd（内置 SHA-256 实时哈希）")
            su.open(
                "umask 022; mb_dd if=${SuManager.quote(src)} of=${SuManager.quote(rawFile.absolutePath)} " +
                    "bs=1048576 hash=${SuManager.quote(hashFile.absolutePath)} 2>${SuManager.quote(errFile.absolutePath)}"
            )
        } else {
            val pipeline = "set -o pipefail; " +
                "dd if=${SuManager.quote(src)} bs=1048576 2>${SuManager.quote(errFile.absolutePath)} | " +
                "tee ${SuManager.quote(rawFile.absolutePath)} | " +
                "sha256sum | cut -d ' ' -f1 > ${SuManager.quote(hashFile.absolutePath)}"
            su.open(pipeline)
        }
        var lastBytes = 0L
        var lastNanos = System.nanoTime()
        while (proc.isAlive()) {
            delay(500)
            if (cancelled) {
                proc.destroy()
                break
            }
            val cur = rawFile.length()
            partBytes[part.name] = cur
            val dt = (System.nanoTime() - lastNanos) / 1e9
            val speed = if (dt > 0.05) (cur - lastBytes) / dt else 0.0
            lastBytes = cur
            lastNanos = System.nanoTime()
            val pct = if (part.sizeBytes > 0) cur * 100.0 / part.sizeBytes else 0.0
            val eta = if (speed > 0) ((part.sizeBytes - cur) / speed).toLong() else -1
            log.progress(
                "part:${part.name}",
                f("   ↻ %s: %s/%s (%.1f%%)  %.1f MB/s  剩余 %s", part.name, fmtSize(cur), fmtSize(part.sizeBytes), pct, speed / 1e6, fmtEta(eta))
            )
        }
        val exited = proc.waitForExit(8_000)
        if (!exited) proc.destroy()
        if (cancelled) throw CancellationException("用户取消")
        val exitCode = if (exited) proc.exitCode() else -1
        if (exitCode != 0) {
            val err = if (errFile.exists()) errFile.readText().take(200) else proc.stderrText().take(200)
            log.error("❌ 分区 ${part.name} 读取失败 (dd exit=$exitCode): $err")
            throw BackupAbort("分区 ${part.name} 读取失败，检测到 IO 异常")
        }

        // 规则1-3：镜像大小必须与分区物理大小一致（截断即损坏）
        val actual = rawFile.length()
        if (actual != part.sizeBytes) {
            log.error("❌ 分区 ${part.name} 镜像大小异常: 期望 ${part.sizeBytes}, 实际 $actual（IO 异常/截断）")
            throw BackupAbort("分区 ${part.name} 镜像截断，已中止任务")
        }
        su.exec("chmod 644 ${SuManager.quote(rawFile.absolutePath)} ${SuManager.quote(hashFile.absolutePath)} ${SuManager.quote(errFile.absolutePath)} 2>/dev/null", 10_000)
        val pipeSha = if (hashFile.exists()) hashFile.readText().trim().takeIf { it.length == 64 } else null
        if (pipeSha == null) {
            log.error("❌ 分区 ${part.name} 实时 SHA-256 计算失败")
            throw BackupAbort("分区 ${part.name} SHA-256 计算失败")
        }

        // ── 单镜像完整性校验（备份一次校验一次：重读文件比对哈希） ──
        log.info("  ⟳ ${part.name} 完整性校验中（SHA-256 重读比对）…")
        val re = su.exec("sha256sum ${SuManager.quote(rawFile.absolutePath)} 2>&1", 3_600_000)
        val reSha = re.stdout.trim().substringBefore(' ').takeIf { it.length == 64 }
        if (reSha == null || !reSha.equals(pipeSha, ignoreCase = true)) {
            log.error("❌ 分区 ${part.name} 完整性校验失败：管道哈希 ${pipeSha.take(12)}… ≠ 重读哈希 ${reSha?.take(12) ?: "计算失败"}")
            throw BackupAbort("分区 ${part.name} 完整性校验失败")
        }
        log.ok("✔ ${part.name} 完整性校验通过（大小一致 + SHA-256 一致: ${pipeSha.take(16)}…）")

        // ── 可选：SPARSE 稀疏转换 ──
        var finalFile = rawFile
        var finalSha = pipeSha
        var rawSha: String? = null
        var format = "raw"
        if (sparse) {
            if (cancelled) throw CancellationException("用户取消")
            val simgFile = File(dir, "${part.name}.simg")
            convertToSparse(rawFile, simgFile, part.sizeBytes)
            rawFile.delete()
            finalFile = simgFile
            su.exec("chmod 644 ${SuManager.quote(simgFile.absolutePath)} 2>/dev/null", 10_000)
            val r = su.exec("sha256sum ${SuManager.quote(simgFile.absolutePath)} 2>&1", 3_600_000)
            finalSha = r.stdout.trim().substringBefore(' ').takeIf { it.length == 64 }
                ?: throw BackupAbort("分区 ${part.name} 稀疏镜像 SHA-256 计算失败")
            rawSha = pipeSha
            format = "sparse"
            val ratio = if (part.sizeBytes > 0) finalFile.length() * 100.0 / part.sizeBytes else 0.0
            log.ok(f("   ✔ %s SPARSE 完成: %s（原始 %s 的 %.1f%%）", part.name, fmtSize(finalFile.length()), fmtSize(part.sizeBytes), ratio))
        }
        log.ok("✔ ${part.name} 备份完成  SHA256=${finalSha.take(16)}…")
        return ImageResult(part.name, finalFile, part.sizeBytes, finalSha, 0L, format, ts.format(Date()), rawSha)
    }

    /** 稀疏转换：提取辅助模块静态 img2simg 优先，内置 Kotlin libsparse 兜底 */
    private suspend fun convertToSparse(rawFile: File, simgFile: File, rawSize: Long) {
        val hasBin = try {
            su.exec("command -v img2simg 2>/dev/null", 8_000).stdout.trim().startsWith("/")
        } catch (e: Exception) {
            false
        }
        if (hasBin) {
            log.info("   ⇄ ${rawFile.nameWithoutExtension} 使用提取辅助模块 img2simg 转换（静态 libsparse）…")
            val proc = su.open(
                "img2simg ${SuManager.quote(rawFile.absolutePath)} ${SuManager.quote(simgFile.absolutePath)} 2>&1"
            )
            while (proc.isAlive()) {
                delay(500)
                if (cancelled) {
                    proc.destroy()
                    break
                }
                val cur = simgFile.length()
                val pct = if (rawSize > 0) (cur.toDouble() / rawSize * 100).coerceAtMost(99.0) else 0.0
                log.progress(
                    "conv:${rawFile.nameWithoutExtension}",
                    f("   ↻ 模块转换 %s: %.1f%% (%s)", rawFile.nameWithoutExtension, pct, fmtSize(cur))
                )
            }
            val exited = proc.waitForExit(8_000)
            if (!exited) proc.destroy()
            if (cancelled) throw CancellationException("用户取消")
            val exit = if (exited) proc.exitCode() else -1
            if (exit == 0 && isSparseFile(simgFile)) {
                log.ok(f("   ✔ 模块 img2simg 转换完成: %s", fmtSize(simgFile.length())))
                return
            }
            log.warn(f("   ⚠ 模块 img2simg 失败 (exit=$exit)，回退内置 Kotlin libsparse"))
            simgFile.delete()
        }
        log.info("   ⇄ ${rawFile.nameWithoutExtension} 使用内置 Kotlin libsparse 转换…")
        var lastReport = 0L
        FileInputStream(rawFile).use { fis ->
            LibSparse.encode(fis, rawSize, simgFile) { done ->
                if (done - lastReport >= 64L * 1024 * 1024 || done >= rawSize) {
                    lastReport = done
                    val pct = if (rawSize > 0) done * 100.0 / rawSize else 0.0
                    log.progress(
                        "conv:${rawFile.nameWithoutExtension}",
                        f("   ↻ 稀疏转换 %s: %.1f%%", rawFile.nameWithoutExtension, pct)
                    )
                }
            }
        }
    }

    /** 校验文件是否为 libsparse 稀疏镜像（magic 0xED26FF3A） */
    private fun isSparseFile(f: File): Boolean {
        if (!f.exists() || f.length() < 28) return false
        return try {
            FileInputStream(f).use { fis ->
                val h = ByteArray(4)
                if (fis.read(h) < 4) return false
                val magic = (h[0].toLong() and 0xFF) or ((h[1].toLong() and 0xFF) shl 8) or
                    ((h[2].toLong() and 0xFF) shl 16) or ((h[3].toLong() and 0xFF) shl 24)
                magic == LibSparse.MAGIC
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 全量完整性校验：重读全部镜像，SHA-256 与 CRC32 同时计算，
     * SHA-256 与单镜像校验结果比对；CRC32 供后续 ZIP 仅存储打包使用。
     */
    private suspend fun fullVerify(results: Map<String, ImageResult>, threads: Int): Map<String, ImageResult> {
        val items = results.values.toList()
        val total = items.sumOf { it.file.length() }
        log.info("── 全量完整性校验开始（重读全部镜像：SHA-256 + CRC32）──")
        setProgress(Progress(Phase.VERIFYING, "全量完整性校验", 0, total, 0.0, 0, ""))
        val doneBytes = AtomicLong(0)
        val out = ConcurrentHashMap<String, ImageResult>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val sem = Semaphore(threads)
            val jobs = items.map { r ->
                scope.launch {
                    sem.withPermit {
                        try {
                            log.info("  ⟳ 全量校验 ${r.name} (${fmtSize(r.file.length())})…")
                            val md = MessageDigest.getInstance("SHA-256")
                            val crc = CRC32()
                            FileInputStream(r.file).use { fis ->
                                val buf = ByteArray(1024 * 1024)
                                var lastReport = 0L
                                while (true) {
                                    val n = fis.read(buf)
                                    if (n < 0) break
                                    md.update(buf, 0, n)
                                    crc.update(buf, 0, n)
                                    val now = doneBytes.addAndGet(n.toLong())
                                    if (now - lastReport >= 64L * 1024 * 1024) {
                                        lastReport = now
                                        setProgress(Progress(Phase.VERIFYING, r.name, now, total, 0.0, 0, ""))
                                        log.progress("fv:${r.name}", f("   ↻ 全量校验 %s: %s/%s", r.name, fmtSize(now), fmtSize(total)))
                                    }
                                }
                            }
                            val sha = md.digest().joinToString("") { "%02x".format(it) }
                            if (!sha.equals(r.sha256, ignoreCase = true)) {
                                log.error("❌ ${r.name} 全量校验失败：SHA-256 不一致（${r.sha256.take(12)}… ≠ ${sha.take(12)}…）")
                                throw BackupAbort("${r.name} 全量校验失败")
                            }
                            out[r.name] = r.copy(crc32 = crc.value)
                            log.ok("✔ ${r.name} 全量校验通过  SHA256=${sha.take(16)}…  CRC32=%08x".format(crc.value))
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            log.error("❌ ${r.name} 全量校验异常: ${e.message}")
                        }
                    }
                }
            }
            jobs.joinAll()
            if (cancelled) throw CancellationException("用户取消")
        } finally {
            scope.cancel()
        }
        val failed = items.filter { !out.containsKey(it.name) }
        if (failed.isNotEmpty()) {
            log.error("❌ 全量完整性校验失败: ${failed.joinToString(" ") { it.name }}，已阻断后续操作")
            throw BackupAbort("全量完整性校验失败: ${failed.joinToString(" ") { it.name }}")
        }
        log.ok("✔ 全量完整性校验完成：${out.size} 个镜像全部一致")
        return out.toMap()
    }

    /** ZIP 仅存储打包（STORE，无任何压缩；CRC32 来自全量校验阶段，零额外读取） */
    private fun packZipStore(manifestFile: File, results: Map<String, ImageResult>, uri: Uri) {
        val items = results.values.toList()
        val total = manifestFile.length() + items.sumOf { it.file.length() }
        var done = 0L
        var lastBytes = 0L
        var lastNanos = System.nanoTime()
        setProgress(Progress(Phase.PACKING, "ZIP", 0, total, 0.0, 0, "仅存储打包中…"))

        // manifest 的 CRC32（体积小，直接计算）
        val manifestCrc = CRC32()
        val manifestBytes = manifestFile.readBytes()
        manifestCrc.update(manifestBytes)

        val resolver = context.contentResolver
        val rawOut = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: Exception) {
            resolver.openOutputStream(uri)
        } ?: throw BackupAbort("无法打开导出目标文件")

        ZipOutputStream(BufferedOutputStream(rawOut, 1024 * 1024)).use { zip ->
            fun addEntry(name: String, file: File, crc: Long) {
                val entry = ZipEntry(name)
                entry.method = ZipEntry.STORED
                entry.size = file.length()
                entry.compressedSize = file.length()
                entry.crc = crc
                zip.putNextEntry(entry)
                FileInputStream(file).use { fis ->
                    val buf = ByteArray(1024 * 1024)
                    while (true) {
                        val n = fis.read(buf)
                        if (n < 0) break
                        zip.write(buf, 0, n)
                        done += n
                        val dt = (System.nanoTime() - lastNanos) / 1e9
                        val speed = if (dt > 0.2) (done - lastBytes) / dt else 0.0
                        lastBytes = done
                        lastNanos = System.nanoTime()
                        val pct = if (total > 0) done * 100.0 / total else 0.0
                        setProgress(Progress(Phase.PACKING, "ZIP", done, total, speed / 1e6, 0, ""))
                        log.progress("zip", f("   ↻ 打包(仅存储): %.1f%% (%s/%s)  %.1f MB/s", pct, fmtSize(done), fmtSize(total), speed / 1e6))
                    }
                }
                zip.closeEntry()
            }
            addEntry("manifest.json", manifestFile, manifestCrc.value)
            for (r in items) {
                if (cancelled) throw CancellationException("用户取消")
                addEntry("images/${r.file.name}", r.file, r.crc32)
            }
        }
        packagedDone = true
        log.ok("✔ ZIP 打包完成（仅存储模式，未做任何压缩）")
    }
}
