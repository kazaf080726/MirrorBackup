package com.kazaf.mirrorbackup.core

import android.content.Context
import android.net.Uri
import com.kazaf.mirrorbackup.core.pure.CommandCheck
import com.kazaf.mirrorbackup.core.pure.FlashPartition
import com.kazaf.mirrorbackup.core.pure.FlashPlanner
import com.kazaf.mirrorbackup.core.pure.FlashScript
import com.kazaf.mirrorbackup.core.pure.ImageMeta
import com.kazaf.mirrorbackup.core.pure.TwrpMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 镜像刷入 / 分区导出 / TWRP 注入 —— 安卓层（root shell）。
 *
 * 写入算法 1:1 复刻 Scene（主题 APK 的 ActivityImg）：见 core/pure/FlashScript。
 * 通过 `su -c <脚本>` 执行，返回码沿用 Scene 的约定：0 成功 / 1 镜像过大 / 2 分区只读。
 */
class ImageFlasher(
    private val context: Context,
    private val su: SuManager,
    private val log: LogBuffer
) {

    data class PreparedImage(val file: File, val meta: ImageMeta)
    data class NodeProbe(val exists: Boolean, val block: Boolean, val writable: Boolean)

    private val dir: File get() = File(context.cacheDir, "flash")

    fun cleanup() {
        runCatching { dir.listFiles()?.forEach { it.deleteRecursively() } }
        runCatching { File(context.cacheDir, "twrp").deleteRecursively() }
    }

    fun finish() = cleanup()

    // ── 镜像准备（拷贝 + SHA-256；.gz 解压；稀疏镜像展开）────────────────
    suspend fun prepare(uri: Uri, onStep: (String) -> Unit = {}): PreparedImage? = withContext(Dispatchers.IO) {
        try {
            cleanup()
            dir.mkdirs()
            val name = queryName(uri) ?: "image.img"
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val tmp = File(dir, safe)
            onStep("正在读取镜像…")
            var total = 0L
            var last = 0L
            val md = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(4 * 1024 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        total += n
                        if (total - last >= 32L * 1024 * 1024) {
                            last = total
                            onStep("正在读取镜像：${fmtSize(total)}")
                        }
                    }
                }
            } ?: run {
                log.error("❌ 无法打开所选文件")
                return@withContext null
            }
            if (total <= 0) {
                log.error("❌ 镜像文件为空")
                return@withContext null
            }

            var work = tmp
            var bytes = total
            var sha = md.digest().joinToString("") { "%02x".format(it) }

            // .gz → 解压（Scene 的 flash_image 在写入时 gzip -d；这里先解开以便校验哈希）
            if (name.lowercase().endsWith(".gz")) {
                onStep("正在解压 .gz 镜像…")
                val raw = File(dir, safe.removeSuffix(".gz") + ".raw")
                val expanded = raw.outputStream().use { out ->
                    java.util.zip.GZIPInputStream(tmp.inputStream()).use { gz ->
                        gz.copyTo(out, 4 * 1024 * 1024)
                    }
                    raw.length()
                }
                tmp.delete()
                work = raw
                bytes = expanded
                sha = fileSha256(raw)
                log.info("✔ .gz 已解压：${fmtSize(bytes)}")
            }

            // 稀疏镜像（simg）→ 展开成 raw
            val head = ByteArray(4)
            work.inputStream().use { it.read(head) }
            val isSparse = head[0] == 0x3A.toByte() && head[1] == 0xFF.toByte() &&
                head[2] == 0x26.toByte() && head[3] == 0xED.toByte()
            if (isSparse) {
                onStep("检测到稀疏镜像，正在展开…")
                val raw = File(dir, work.name + ".raw")
                val expanded = raw.outputStream().use { out ->
                    LibSparse.decode(work.inputStream(), out) { done -> onStep("正在展开：${fmtSize(done)}") }
                }
                work.delete()
                work = raw
                bytes = expanded
                sha = fileSha256(raw)
                log.info("✔ 稀疏镜像已展开：${fmtSize(bytes)}")
            }

            log.ok("✔ 镜像就绪：$name  ${fmtSize(bytes)}  SHA-256=${sha.take(16)}…")
            PreparedImage(work, ImageMeta(name, bytes, sha, isSparse))
        } catch (e: Exception) {
            log.error("❌ 读取镜像失败: ${e.message}")
            null
        }
    }

    // ── 分区扫描（优先按 Scene 的 by-name 目录优先级解析路径）──────────
    suspend fun deviceRoot(): String? = withContext(Dispatchers.IO) {
        val existing = FlashScript.DEVICE_DIRS.filter { d ->
            su.exec("[ -d '$d' ] && echo Y", 5_000).stdout.contains("Y")
        }
        val root = FlashScript.resolveDeviceRoot(existing)
        if (root != null) log.info("📂 分区目录（Scene 优先级）：$root")
        root
    }

    private suspend fun listByRoot(root: String): Set<String> = withContext(Dispatchers.IO) {
        su.exec("ls '$root' 2>/dev/null", 15_000).stdout
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    suspend fun scan(): List<FlashPartition> = withContext(Dispatchers.IO) {
        val base = PartitionScanner.scan(su, log).map { FlashPartition(it.name, it.path, it.sizeBytes) }
        val root = deviceRoot() ?: return@withContext base
        val names = listByRoot(root)
        base.map { p -> if (p.name in names) FlashPartition(p.name, "$root/${p.name}", p.sizeBytes) else p }
    }

    suspend fun probe(target: FlashPartition): NodeProbe = withContext(Dispatchers.IO) {
        val p = target.path
        if (!FlashPlanner.safeDevicePath(p)) return@withContext NodeProbe(false, false, false)
        val r = su.exec("[ -e '$p' ] && echo E; [ -b '$p' ] && echo B; [ -w '$p' ] && echo W", 10_000)
        NodeProbe(r.stdout.contains("E"), r.stdout.contains("B"), r.stdout.contains("W"))
    }

    suspend fun preflight(image: PreparedImage, target: FlashPartition): com.kazaf.mirrorbackup.core.pure.Preflight =
        withContext(Dispatchers.IO) {
            val probe = probe(target)
            FlashPlanner.preflight(
                image = image.meta,
                target = target,
                blacklist = UserBlacklist.load(context),
                nodeExists = probe.exists,
                nodeWritable = probe.writable,
                risky = PartitionRules.isRisky(target.name),
                isBlockDevice = probe.block
            )
        }

    // ── 刷入（1:1 Scene：flash_image 脚本）──────────────────────────
    suspend fun flash(
        image: PreparedImage,
        target: FlashPartition,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): CommandCheck = withContext(Dispatchers.IO) {
        val meta = image.meta
        val script = FlashScript.flashScript(image.file.absolutePath, target)
            ?: return@withContext CommandCheck(false, listOf("目标或镜像路径不合法，拒绝生成脚本"))

        // 执行前自检：确认脚本只会写到选定分区，且带 Scene 的大小/只读/零填充逻辑
        val guard = FlashScript.verifyFlashScript(script, image.file.absolutePath, target)
        if (!guard.ok) {
            log.error("❌ 刷入脚本自检未通过，已中止：${guard.summary()}")
            return@withContext guard
        }
        log.info("🧭 刷入脚本自检通过（Scene flash_image 1:1）")
        log.info("   目标：${target.name} → ${target.path}  ${fmtSize(target.sizeBytes)}")
        log.info("   镜像：${meta.fileName}  ${fmtSize(meta.bytes)}  SHA-256 ${meta.sha256.take(16)}…")

        onProgress(0L, meta.bytes)
        val r = su.exec(script, 1_800_000)
        val out = (r.stdout + "\n" + r.stderr).trim()
        if (out.isNotEmpty()) {
            out.lineSequence().filter { it.isNotBlank() }.take(40).forEach { log.info("   │ $it") }
        }
        when (r.exitCode) {
            0 -> log.ok("✔ 写入完成（镜像已写入并用 0 填充到分区末尾）")
            1 -> {
                log.error("❌ 镜像过大无法写入分区（Scene 返回码 1）")
                return@withContext CommandCheck(false, listOf("镜像过大无法写入分区"))
            }
            2 -> {
                log.error("❌ 目标分区是只读的（Scene 返回码 2）")
                return@withContext CommandCheck(false, listOf("目标分区是只读的"))
            }
            else -> {
                log.error("❌ 写入失败（exit=${r.exitCode}）")
                return@withContext CommandCheck(false, listOf("写入失败 exit=${r.exitCode}"))
            }
        }

        // 刷完回读校验（Scene 没有这一步，按你的要求保留）：前 N 字节的 SHA-256 应等于镜像
        val readCmd = FlashPlanner.readBackCommand(target, meta.bytes)
            ?: return@withContext CommandCheck(false, listOf("回读指令生成失败"))
        val rr = su.exec(readCmd, 900_000)
        val actual = rr.stdout.trim().split(Regex("\\s+"))
            .firstOrNull { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }
        if (actual == null) {
            log.error("❌ 回读哈希失败: ${rr.stdout.take(80)}")
            return@withContext CommandCheck(false, listOf("回读哈希失败"))
        }
        val verdict = FlashPlanner.verifyWritten(meta, meta.bytes, actual)
        if (verdict.ok) {
            log.ok("✔✔✔ 回读校验通过：分区前 ${fmtSize(meta.bytes)} 的 SHA-256 与镜像一致")
        } else {
            log.error("❌ 回读校验失败：${verdict.summary()}")
        }
        verdict
    }

    // ── 分区镜像导出（1:1 Scene：dd if='分区' of='/sdcard/<分区名>.img'）──
    suspend fun exportPartition(target: FlashPartition): CommandCheck = withContext(Dispatchers.IO) {
        val out = "/sdcard/${target.name}.img"
        val script = FlashScript.exportScript(target, out)
            ?: return@withContext CommandCheck(false, listOf("分区路径不合法"))
        log.info("📤 导出分区镜像：dd if='${target.path}' of='$out'")
        val r = su.exec(script, 1_800_000)
        if (r.exitCode != 0) {
            log.error("❌ 导出失败（exit=${r.exitCode}）: ${r.stderr.take(160)}")
            return@withContext CommandCheck(false, listOf("导出失败 exit=${r.exitCode}"))
        }
        val size = su.exec("stat -c '%s' '$out' 2>/dev/null", 10_000).stdout.trim().toLongOrNull() ?: 0L
        if (size <= 0) {
            log.error("❌ 导出文件为空")
            return@withContext CommandCheck(false, listOf("导出文件为空"))
        }
        log.ok("✔ 已导出：$out  ${fmtSize(size)}")
        CommandCheck(true)
    }

    // ── TWRP 注入（1:1 Scene：twrp_mode.sh 判定 + twrp_install.sh）────────
    suspend fun twrpMode(): TwrpMode = withContext(Dispatchers.IO) {
        val root = deviceRoot() ?: return@withContext TwrpMode.UNSUPPORTED
        FlashScript.twrpMode(listByRoot(root))
    }

    suspend fun injectTwrp(twrp: File, onLine: (String) -> Unit = {}): CommandCheck = withContext(Dispatchers.IO) {
        val root = deviceRoot() ?: return@withContext CommandCheck(false, listOf("找不到分区目录（/dev/block/by-name 等）"))
        val mode = FlashScript.twrpMode(listByRoot(root))
        if (mode == TwrpMode.UNSUPPORTED) {
            return@withContext CommandCheck(false, listOf("该设备不是 A/B（或缺少 boot_a/boot_b、存在 vendor_boot），Scene 判定为不支持 TWRP 注入"))
        }
        val hasMagiskboot = su.exec("[ -f /data/adb/magisk/magiskboot ] && echo Y", 5_000).stdout.contains("Y")
        if (!hasMagiskboot) {
            return@withContext CommandCheck(false, listOf("未找到 /data/adb/magisk/magiskboot，请先安装 Magisk"))
        }
        val tmp = File(context.cacheDir, "twrp").apply { mkdirs() }
        val script = FlashScript.twrpScript(twrp.absolutePath, root, mode, tmp.absolutePath)
            ?: return@withContext CommandCheck(false, listOf("脚本生成失败"))
        log.info("🩺 TWRP 注入模式：$mode（magiskboot 可用）")
        val r = su.exec(script, 1_800_000)
        val out = (r.stdout + "\n" + r.stderr).trim()
        if (out.isNotEmpty()) {
            out.lineSequence().filter { it.isNotBlank() }.take(120).forEach {
                onLine(it)
                log.info("   │ $it")
            }
        }
        when (r.exitCode) {
            0 -> {
                log.ok("✔✔✔ TWRP 注入完成")
                CommandCheck(true)
            }
            1 -> CommandCheck(false, listOf("镜像过大无法写入分区"))
            2 -> CommandCheck(false, listOf("目标分区是只读的"))
            else -> CommandCheck(false, listOf("TWRP 注入失败 exit=${r.exitCode}"))
        }
    }

    // ── 重启（刷完 / 注入后让写入生效）──────────────────────────────
    suspend fun reboot(): CommandCheck = withContext(Dispatchers.IO) {
        log.info("🔁 正在重启设备…")
        val script = FlashScript.rebootScript()
        val r = runCatching { su.exec(script, 8_000) }.getOrNull()
        if (r != null && r.exitCode == 0) return@withContext CommandCheck(true)
        // 有的系统 reboot 需要 svc
        val r2 = runCatching { su.exec("svc power reboot", 8_000) }.getOrNull()
        if (r2 != null && r2.exitCode == 0) return@withContext CommandCheck(true)
        log.warn("⚠ 重启命令返回异常（设备可能已经开始重启）")
        CommandCheck(false, listOf("重启命令未成功（设备可能已开始重启，或需要 root）"))
    }

    private fun fileSha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun queryName(uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (e: Exception) {
        null
    }
}
