package com.kazaf.mirrorbackup.core

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class RestoreAbort(message: String) : Exception(message)

/**
 * 恢复引擎 —— 强制三层校验，顺序不可更改：
 * ① 校验压缩包内每一个镜像文件 SHA-256，损坏则禁止任何写入；
 * ② 校验目标分区物理大小 >= 镜像展开字节大小，溢出直接阻断；
 * ③ 设备指纹匹配校验，不一致时只有显式勾选"强制恢复"后才能继续。
 * 恢复前还会再次执行 su / 分区节点有效性检测；黑名单分区拒绝写入。
 */
class RestoreEngine(
    private val context: Context,
    private val su: SuManager,
    private val log: LogBuffer
) {

    @Volatile
    var cancelled = false

    enum class Phase { IDLE, LOADING, VERIFYING, RESTORING, DONE, FAILED, CANCELLED }

    data class Progress(
        val phase: Phase,
        val partitionName: String,
        val bytesDone: Long,
        val bytesTotal: Long,
        val speedMBps: Double,
        val detail: String
    )

    private val _progress = MutableStateFlow(Progress(Phase.IDLE, "", 0, 0, 0.0, ""))
    val progress: StateFlow<Progress> = _progress

    private fun setProgress(p: Progress) {
        _progress.value = p
    }

    private val NAME_RE = Regex("^[A-Za-z0-9_-]{1,64}$")

    data class PartitionPreview(
        val name: String,
        val format: String,
        val sizeBytes: Long,        // manifest 记录的分区物理字节大小
        val sha256: String,
        val targetExists: Boolean,
        val targetSize: Long,
        val shaOk: Boolean? = null, // null=未校验
        val message: String = ""
    ) {
        val sizeOk: Boolean
            get() = targetExists && targetSize >= sizeBytes
    }

    data class LoadResult(
        val manifest: BackupManifest,
        val device: DeviceInfo,
        val fingerprintMatch: Boolean,
        val previews: List<PartitionPreview>,
        val blacklistedNames: List<String>,   // 用户黑名单中的分区（拒绝写入）
        val invalidNames: List<String>,       // 非法分区名（拒绝操作）
        val riskyNames: List<String>          // 引导相关分区（仅风险提示，由用户决策）
    )

    /** 读取压缩包 manifest 并与当前设备做初步比对（不写入任何东西） */
    suspend fun load(uri: Uri): LoadResult {
        cancelled = false
        setProgress(Progress(Phase.LOADING, "", 0, 0, 0.0, "正在解析备份包…"))
        log.clear()
        log.info("═══ 恢复工作台 ═══")
        // 解析前先清临时垃圾（上次恢复留下的 dd 错误输出 / 缓存副本）
        run {
            val st = TempCleaner.Stats()
            TempCleaner.cleanRestore(context, st)
            if (!st.isEmpty()) log.info("🧹 已清理临时文件 ${st.summary()}")
        }
        log.info("正在导入并解析 ZIP 备份包…")

        var manifest: BackupManifest? = null
        val entryNames = mutableListOf<String>()
        try {
            context.contentResolver.openInputStream(uri).use { raw ->
                val zip = ZipInputStream(raw)
                var entry: ZipEntry?
                while (zip.nextEntry.also { entry = it } != null) {
                    entryNames += entry!!.name
                    if (entry!!.name == "manifest.json") {
                        val text = zip.readBytes().toString(Charsets.UTF_8)
                        manifest = BackupManifest.fromJson(text)
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            log.error("❌ 解析备份包失败: ${e.message}")
            setProgress(Progress(Phase.FAILED, "", 0, 0, 0.0, "解析备份包失败"))
            throw RestoreAbort("解析备份包失败")
        }

        val m = manifest ?: run {
            log.error("❌ 压缩包内不存在 manifest.json，不是有效的镜像备份包")
            setProgress(Progress(Phase.FAILED, "", 0, 0, 0.0, "缺少 manifest.json"))
            throw RestoreAbort("缺少 manifest.json")
        }
        if (m.partitions.isEmpty()) {
            log.error("❌ manifest 中没有任何分区记录")
            setProgress(Progress(Phase.FAILED, "", 0, 0, 0.0, "manifest 为空"))
            throw RestoreAbort("manifest 为空")
        }

        // 用户自主黑名单：黑名单内的分区拒绝写入（用户可在设置中移除）
        val userBlack = UserBlacklist.load(context)
        val blacklisted = m.partitions.map { it.name }.filter { it in userBlack }
        if (blacklisted.isNotEmpty()) {
            log.error("⛔ 以下分区在你设置的黑名单中，本应用拒绝写入（可在设置中移除）: ${blacklisted.joinToString(" ")}")
        }
        // 非法分区名：防御恶意 manifest 路径注入（技术性校验，非分区排除）
        val invalid = m.partitions.filter { !NAME_RE.matches(it.name) || it.path.contains("..") }
            .map { it.name }
        if (invalid.isNotEmpty()) {
            log.error("❌ manifest 含非法分区名 ${invalid.joinToString(" ")}，拒绝操作")
        }
        // 引导相关分区：仅风险提示，由用户自行决策
        val risky = m.partitions.map { it.name }.filter { PartitionRules.isRisky(it) }
        if (risky.isNotEmpty()) {
            log.warn("⚠ 风险提示：该包包含引导相关分区（${risky.joinToString(" ")}），请确认目标设备与备份设备一致")
        }

        // 当前设备信息与分区
        val device = DeviceInfoCollector.collect(su)
        val targets = PartitionScanner.scan(su, log).associateBy { it.name }
        val fingerprintMatch = device.fingerprint == m.fingerprint
        log.info("备份包设备型号: ${m.model} / Android ${m.androidVersion}")
        log.info("当前设备型号: ${device.model} / Android ${device.androidVersion}")
        log.info("备份包指纹: ${m.fingerprint}")
        log.info("当前设备指纹: ${device.fingerprint}")
        if (fingerprintMatch) log.ok("✔ 设备指纹一致") else log.warn("⚠ 设备指纹不一致 —— 恢复按钮将被锁定")

        val previews = m.partitions.map { p ->
            val t = targets[p.name]
            PartitionPreview(
                name = p.name,
                format = p.format,
                sizeBytes = p.sizeBytes,
                sha256 = p.sha256,
                targetExists = t != null,
                targetSize = t?.sizeBytes ?: 0L,
                message = if (t == null) "目标分区不存在" else ""
            )
        }
        previews.forEach { p ->
            if (!p.targetExists) log.error("❌ 目标分区 ${p.name} 不存在于当前设备")
            else if (!p.sizeOk) log.error("❌ 分区 ${p.name} 空间不足: 镜像 ${fmtSize(p.sizeBytes)} > 分区 ${fmtSize(p.targetSize)}")
        }

        setProgress(Progress(Phase.IDLE, "", 0, 0, 0.0, "解析完成"))
        return LoadResult(m, device, fingerprintMatch, previews, blacklisted, invalid, risky)
    }

    /** 预览校验（第①层 + 第②层），不执行任何写入 */
    suspend fun verify(uri: Uri, manifest: BackupManifest): List<PartitionPreview> {
        cancelled = false
        log.info("── 预校验（第①层 SHA-256 + 第②层 大小）──")
        val map = manifest.partitions.associateBy { it.name }
        val targets = PartitionScanner.scan(su, log).associateBy { it.name }
        val results = hashVerifyPass(uri, manifest, map, targets)
        return results
    }

    /** 正式恢复：三层校验按序强制执行后逐分区写入 */
    suspend fun restore(uri: Uri, manifest: BackupManifest, force: Boolean) {
        cancelled = false
        log.info("═══ 恢复任务开始 ═══")
        try {
            // ── 前置：su 与节点有效性 ──
            val suPath = su.findSu()
            if (suPath == null) {
                log.error("❌ 未找到 su 二进制，权限不足，任务终止")
                setProgress(Progress(Phase.FAILED, "", 0, 0, 0.0, "无ROOT权限"))
                return
            }
            val check = su.checkRoot()
            if (!check.rootOk) {
                log.error("❌ ROOT 权限校验失败: ${check.detail}")
                setProgress(Progress(Phase.FAILED, "", 0, 0, 0.0, "ROOT校验失败"))
                return
            }
            log.ok("✔ su 可用: $suPath")
            val targets = PartitionScanner.scan(su, log).associateBy { it.name }

            // ── 第①层：SHA-256 + ZIP CRC32 双重校验（只读提取，不进行任何写入） ──
            log.info("【第①层校验】压缩包内镜像双重校验（SHA-256 + ZIP CRC32，只读提取零写入）…")
            val map = manifest.partitions.associateBy { it.name }
            val previews = hashVerifyPass(uri, manifest, map, targets)
            val bad = previews.filter { it.shaOk != true }
            if (bad.isNotEmpty()) {
                log.error("❌ 检测到损坏镜像: ${bad.joinToString(" ") { it.name }}，禁止任何写入，恢复已阻断")
                setProgress(Progress(Phase.FAILED, "", 0, 0, 0.0, "镜像损坏，写入已阻断"))
                return
            }
            log.ok("✔ 第①层校验通过：全部镜像 SHA-256 与 manifest 一致")

            // ── 第②层：目标分区物理大小 >= 镜像字节大小 ──
            log.info("【第②层校验】目标分区物理大小 ≥ 镜像展开字节大小…")
            for (p in previews) {
                val t = targets[p.name]
                if (t == null) {
                    log.error("❌ 目标分区 ${p.name} 不存在，阻断操作")
                    setProgress(Progress(Phase.FAILED, p.name, 0, 0, 0.0, "目标分区不存在"))
                    return
                }
                if (p.sizeBytes > t.sizeBytes) {
                    log.error("❌ 分区 ${p.name} 溢出风险: 镜像 ${fmtSize(p.sizeBytes)} > 分区物理大小 ${fmtSize(t.sizeBytes)}，写入已阻断")
                    setProgress(Progress(Phase.FAILED, p.name, 0, 0, 0.0, "溢出风险，写入已阻断"))
                    return
                }
                log.info("  ${p.name}: 镜像 ${fmtSize(p.sizeBytes)} ≤ 分区 ${fmtSize(t.sizeBytes)} ✓")
            }
            log.ok("✔ 第②层校验通过")

            // ── 第③层：设备指纹匹配 ──
            log.info("【第③层校验】设备指纹匹配校验（SN + CPU ID）…")
            val device = DeviceInfoCollector.collect(su)
            val match = device.fingerprint == manifest.fingerprint
            if (!match) {
                if (!force) {
                    log.error("❌ 设备指纹不一致，恢复已锁定。如需继续请使用【强制恢复】（折叠隐藏区）")
                    setProgress(Progress(Phase.FAILED, "", 0, 0, 0.0, "设备指纹不一致"))
                    return
                }
                log.warn("⚠⚠⚠ 强制恢复：设备指纹不匹配！写入不匹配设备可能造成基带/硬件校准永久损坏 ⚠⚠⚠")
                log.warn("⚠ 用户已勾选风险确认，继续执行强制恢复")
            } else {
                log.ok("✔ 第③层校验通过：设备指纹一致")
            }

            // ── 用户黑名单校验：黑名单内的分区拒绝写入 ──
            val userBlack = UserBlacklist.load(context)
            val blackHits = map.keys.filter { it in userBlack }
            if (blackHits.isNotEmpty()) {
                log.error("⛔ 分区 ${blackHits.joinToString(" ")} 在你的黑名单中，写入已阻断（可在设置中移除后重试）")
                setProgress(Progress(Phase.FAILED, "", 0, 0, 0.0, "用户黑名单阻断"))
                return
            }

            // ── 写入阶段 ──
            log.info("── 开始写入分区（dd 流式写入）──")
            var index = 0
            val restoredNames = mutableListOf<String>()
            var restoredBytes = 0L
            val startMs = System.currentTimeMillis()
            context.contentResolver.openInputStream(uri).use { raw ->
                val zip = ZipInputStream(raw)
                var entry: ZipEntry?
                while (zip.nextEntry.also { entry = it } != null) {
                    val e = entry ?: continue
                    if (!e.name.startsWith("images/") || e.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    val fileName = e.name.removePrefix("images/")
                    val name = fileName.substringBeforeLast('.')
                    val part = map[name]
                    if (part == null) {
                        log.warn("⚠ 忽略 manifest 之外的条目: ${e.name}")
                        zip.closeEntry()
                        continue
                    }
                    if (cancelled) throw CancellationException("用户取消")
                    if (PartitionRules.isRisky(name)) {
                        log.warn("⚠ 风险提示：$name 为引导相关分区，已按你的选择执行恢复")
                    }
                    index++
                    log.info("▶ [${index}/${map.size}] 恢复分区: $name")
                    restorePartition(zip, part, targets[name]!!)
                    restoredNames += name
                    restoredBytes += part.sizeBytes
                    zip.closeEntry()
                }
            }
            val durationSec = (System.currentTimeMillis() - startMs) / 1000
            RecordsStore.add(
                context,
                BackupRecord(
                    time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                    type = "restore",
                    mode = modeLabelOf(manifest.mode),
                    threadMode = "单线程",
                    format = manifest.format,
                    packaged = false,
                    location = uri.toString(),
                    imageCount = restoredNames.size,
                    images = restoredNames,
                    totalBytes = restoredBytes,
                    durationSec = durationSec
                )
            )
            log.info("📋 恢复记录已写入记录表")
            log.ok("✔✔✔ 恢复完成：共写入 $index 个分区，耗时 ${durationSec}s ✔✔✔")
            setProgress(Progress(Phase.DONE, "", 0, 0, 0.0, "恢复完成"))
        } catch (ce: CancellationException) {
            log.warn("⚠ 恢复已被用户停止")
            setProgress(Progress(Phase.CANCELLED, "", 0, 0, 0.0, "已停止"))
            throw ce
        } catch (e: Exception) {
            log.error("❌ 恢复失败: ${e.message}")
            setProgress(Progress(Phase.FAILED, "", 0, 0, 0.0, e.message ?: "恢复失败"))
        } finally {
            // 收尾清理：本次恢复产生的 dd 错误输出等临时文件
            val st = TempCleaner.Stats()
            TempCleaner.cleanRestore(context, st)
            if (!st.isEmpty()) log.info("🧹 任务结束已清理临时文件 ${st.summary()}")
        }
    }

    /**
     * 第①层哈希校验通道：单遍扫描压缩包，对每个镜像条目计算 SHA-256；
     * 稀疏镜像同时做完整结构解码校验（含 CRC32 chunk 校验）并统计展开字节数。
     */
    private fun hashVerifyPass(
        uri: Uri,
        manifest: BackupManifest,
        map: Map<String, ManifestPartition>,
        targets: Map<String, PartitionInfo>
    ): List<PartitionPreview> {
        val states = mutableMapOf<String, PartitionPreview>()
        var count = 0
        context.contentResolver.openInputStream(uri).use { raw ->
            val zip = ZipInputStream(raw)
            var entry: ZipEntry?
            while (zip.nextEntry.also { entry = it } != null) {
                val e = entry ?: continue
                if (!e.name.startsWith("images/") || e.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val fileName = e.name.removePrefix("images/")
                val name = fileName.substringBeforeLast('.')
                val part = map[name]
                if (part == null) {
                    log.warn("⚠ 忽略 manifest 之外的条目: ${e.name}")
                    zip.closeEntry()
                    continue
                }
                if (cancelled) throw CancellationException("用户取消")
                setProgress(Progress(Phase.VERIFYING, name, 0, part.sizeBytes, 0.0, ""))
                log.info("  正在双重校验 ${name}（SHA-256 + ZIP CRC32，只读提取）…")

                // 校验逻辑与电脑端模拟测试共用同一份纯 JVM 实现（RestoreVerify）
                val check = com.kazaf.mirrorbackup.core.pure.RestoreVerify.verifyEntry(
                    name = name,
                    format = part.format,
                    declaredSize = part.sizeBytes,
                    declaredSha256 = part.sha256,
                    crcStored = e.crc,
                    input = zip,
                    onProgress = { done ->
                        setProgress(Progress(Phase.VERIFYING, name, done, part.sizeBytes, 0.0, ""))
                        if (done > 0 && done % (32L * 1024 * 1024) < (1024 * 1024)) {
                            log.progress("vh:$name", f("   ↻ 校验 %s: %s", name, fmtSize(done)))
                        }
                    }
                )
                val expanded = check.expandedBytes
                try {
                    zip.closeEntry()
                } catch (_: Exception) {
                }

                if (check.structureError != null) {
                    log.error("❌ ${name} 镜像结构损坏: ${check.structureError}")
                    states[name] = PartitionPreview(
                        name, part.format, part.sizeBytes, part.sha256,
                        targets.containsKey(name), targets[name]?.sizeBytes ?: 0L,
                        shaOk = false, message = "镜像结构损坏"
                    )
                    continue
                }
                if (check.ok) {
                    log.ok("✔ ${name}: 双重校验通过（SHA-256 一致 + ZIP CRC32 一致），展开 ${fmtSize(expanded)}")
                } else {
                    if (!check.shaOk) log.error("❌ ${name}: SHA-256 不一致！ manifest=${part.sha256.take(16)}… 实际=${check.actualSha256.take(16)}…")
                    if (!check.sizeOk) log.error("❌ ${name}: 展开大小 ${expanded} 与 manifest 记录 ${part.sizeBytes} 不一致")
                    if (!check.crcOk) log.error("❌ ${name}: ZIP CRC32 不一致！ 记录=${"%08x".format(check.crcStored)} 实际=${"%08x".format(check.crcActual)}")
                }
                states[name] = PartitionPreview(
                    name, part.format, part.sizeBytes, part.sha256,
                    targets.containsKey(name), targets[name]?.sizeBytes ?: 0L,
                    shaOk = check.ok,
                    message = if (check.ok) "" else "校验失败"
                )
                count++
            }
        }
        // manifest 中声明但压缩包内缺失的条目
        for (p in manifest.partitions) {
            if (!states.containsKey(p.name)) {
                log.error("❌ 压缩包缺少分区 ${p.name} 的镜像文件")
                states[p.name] = PartitionPreview(
                    p.name, p.format, p.sizeBytes, p.sha256,
                    targets.containsKey(p.name), targets[p.name]?.sizeBytes ?: 0L,
                    shaOk = false, message = "压缩包缺少该镜像"
                )
            }
        }
        log.info("哈希校验通道完成，共校验 $count 个镜像条目")
        setProgress(Progress(Phase.IDLE, "", 0, 0, 0.0, ""))
        return manifest.partitions.mapNotNull { states[it.name] }
    }

    /** 单分区恢复：镜像流 → （稀疏解码）→ dd 写入分区节点 */
    private suspend fun restorePartition(zip: ZipInputStream, part: ManifestPartition, target: PartitionInfo) {
        val devPath = "/dev/block/by-name/${part.name}"
        val errFile = File(context.cacheDir, "restore_${part.name}.err")
        val ddCmd = "dd of=${SuManager.quote(devPath)} bs=1048576 conv=fsync 2>${SuManager.quote(errFile.absolutePath)}"
        val proc = su.open(ddCmd)

        var written = 0L
        var lastReport = 0L
        try {
            val stdin = BufferedOutputStream(proc.stdin(), 1024 * 1024)
            // 写入逻辑与电脑端模拟测试共用（RestoreVerify.writeEntry）
            written = com.kazaf.mirrorbackup.core.pure.RestoreVerify.writeEntry(
                input = zip,
                out = stdin,
                format = part.format,
                onProgress = { done ->
                    if (done - lastReport >= 32L * 1024 * 1024 || done >= part.sizeBytes) {
                        lastReport = done
                        setProgress(Progress(Phase.RESTORING, part.name, done, part.sizeBytes, 0.0, ""))
                        log.progress("wr:${part.name}", f("   ↻ 写入 %s: %s/%s", part.name, fmtSize(done), fmtSize(part.sizeBytes)))
                    }
                }
            )
            if (written != part.sizeBytes) {
                throw RestoreAbort("分区 ${part.name} 写入字节数 $written 与 manifest 记录 ${part.sizeBytes} 不一致，已中断")
            }
            stdin.flush()
            proc.closeStdin()
        } catch (ex: Exception) {
            proc.destroy()
            throw ex
        }

        val exited = proc.waitForExit(120_000)
        if (!exited) {
            proc.destroy()
            log.error("❌ 分区 ${part.name} 写入超时")
            throw RestoreAbort("分区 ${part.name} 写入超时")
        }
        if (cancelled) throw CancellationException("用户取消")
        val exitCode = proc.exitCode()
        val errText = if (errFile.exists()) {
            su.exec("cat ${SuManager.quote(errFile.absolutePath)} 2>/dev/null", 10_000).stdout.take(200)
        } else {
            proc.stderrText().take(200)
        }
        if (exitCode != 0) {
            log.error("❌ 分区 ${part.name} 写入失败 (dd exit=$exitCode): $errText")
            throw RestoreAbort("分区 ${part.name} 写入失败")
        }
        // 写入后节点健康检测
        val (healthy, msg) = PartitionScanner.validatePartition(su, part.name)
        if (!healthy) {
            log.warn("⚠ 分区 ${part.name} 写入后节点读取异常: $msg")
        }
        log.ok("✔ ${part.name} 写入完成 (${fmtSize(written)} → $devPath)")
    }
}

