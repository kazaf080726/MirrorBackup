package com.kazaf.mirrorbackup.core

import android.content.Context
import android.net.Uri
import com.kazaf.mirrorbackup.core.pure.CommandCheck
import com.kazaf.mirrorbackup.core.pure.FlashPartition
import com.kazaf.mirrorbackup.core.pure.FlashPlanner
import com.kazaf.mirrorbackup.core.pure.ImageMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * 单分区刷入（.img）—— 安卓层。
 * 流程：读镜像（算 SHA-256，稀疏镜像先展开）→ 扫描分区（PartitionScanner）
 *      → 由 FlashPlanner 匹配/选槽 → 前置校验 → 指令自检 → dd 写入 → 回读哈希校验。
 * 所有判定逻辑都在 core/pure/FlashPlanner，电脑端模拟测试跑的是同一份。
 */
class ImageFlasher(
    private val context: Context,
    private val su: SuManager,
    private val log: LogBuffer
) {

    data class PreparedImage(val file: File, val meta: ImageMeta)

    data class NodeProbe(val exists: Boolean, val block: Boolean, val writable: Boolean)

    private val dir: File get() = File(context.cacheDir, "flash")

    /** 清掉上一次刷入留下的临时镜像 */
    fun cleanup() {
        runCatching { dir.listFiles()?.forEach { it.deleteRecursively() } }
    }

    /** 把选中的 .img 落到临时目录并算 SHA-256；稀疏镜像（simg）会先展开成 raw */
    suspend fun prepare(uri: Uri, onStep: (String) -> Unit = {}): PreparedImage? = withContext(Dispatchers.IO) {
        try {
            cleanup()
            dir.mkdirs()
            val name = queryName(uri) ?: "image.img"
            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val tmp = File(dir, safeName)
            onStep("正在读取镜像…")
            val md = MessageDigest.getInstance("SHA-256")
            var total = 0L
            var lastStep = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(4 * 1024 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        total += n
                        if (total - lastStep >= 32L * 1024 * 1024) {
                            lastStep = total
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
            var sha = md.digest().joinToString("") { "%02x".format(it) }
            var bytes = total
            var sparse = false

            // 稀疏镜像（simg）：展开成 raw 再刷，避免把稀疏头写进分区
            val head = ByteArray(4)
            tmp.inputStream().use { it.read(head) }
            val isSparse = head[0] == 0x3A.toByte() && head[1] == 0xFF.toByte() &&
                head[2] == 0x26.toByte() && head[3] == 0xED.toByte()
            var rawFile = tmp
            if (isSparse) {
                sparse = true
                onStep("检测到稀疏镜像，正在展开…")
                rawFile = File(dir, safeName + ".raw")
                val expanded = rawFile.outputStream().use { out ->
                    LibSparse.decode(tmp.inputStream(), out) { done ->
                        onStep("正在展开稀疏镜像：${fmtSize(done)}")
                    }
                }
                tmp.delete()
                bytes = expanded
                sha = fileSha256(rawFile)
                log.info("✔ 稀疏镜像已展开：${fmtSize(bytes)}")
            }
            log.ok("✔ 镜像就绪：$name  ${fmtSize(bytes)}  SHA-256=${sha.take(16)}…")
            PreparedImage(rawFile, ImageMeta(name, bytes, sha, sparse))
        } catch (e: Exception) {
            log.error("❌ 读取镜像失败: ${e.message}")
            null
        }
    }

    /** 扫描分区（/dev/block/by-name 等） */
    suspend fun scan(): List<FlashPartition> = withContext(Dispatchers.IO) {
        PartitionScanner.scan(su, log).map { FlashPartition(it.name, it.path, it.sizeBytes) }
    }

    /** 探测目标节点：存在 / 块设备 / 可写 */
    suspend fun probe(target: FlashPartition): NodeProbe = withContext(Dispatchers.IO) {
        val p = target.path
        if (!FlashPlanner.safeDevicePath(p)) return@withContext NodeProbe(false, false, false)
        val r = su.exec("[ -e '$p' ] && echo E; [ -b '$p' ] && echo B; [ -w '$p' ] && echo W", 10_000)
        NodeProbe(r.stdout.contains("E"), r.stdout.contains("B"), r.stdout.contains("W"))
    }

    /**
     * 前置校验（读节点状态后交给 FlashPlanner 判定）
     */
    suspend fun preflight(image: PreparedImage, target: FlashPartition): com.kazaf.mirrorbackup.core.pure.Preflight =
        withContext(Dispatchers.IO) {
            val probe = probe(target)
            val black = UserBlacklist.load(context)
            FlashPlanner.preflight(
                image = image.meta,
                target = target,
                blacklist = black,
                nodeExists = probe.exists,
                nodeWritable = probe.writable,
                risky = PartitionRules.isRisky(target.name),
                isBlockDevice = probe.block
            )
        }

    /**
     * 执行刷入：指令自检 → dd → 回读哈希校验
     */
    suspend fun flash(
        image: PreparedImage,
        target: FlashPartition,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): CommandCheck = withContext(Dispatchers.IO) {
        val meta = image.meta
        val cmd = FlashPlanner.writeCommand(target)
            ?: return@withContext CommandCheck(false, listOf("目标路径不合法：${target.path}"))

        // 最后一道：执行前静态自检指令，确认没有发错
        val guard = FlashPlanner.verifyWriteCommand(cmd, target)
        if (!guard.ok) {
            log.error("❌ 指令自检未通过，已中止：${guard.summary()}")
            return@withContext guard
        }
        log.info("🧭 指令自检通过")
        log.info("   将执行：$cmd")
        log.info("   目标分区：${target.name}  ${target.path}  ${fmtSize(target.sizeBytes)}")
        log.info("   镜像：${meta.fileName}  ${fmtSize(meta.bytes)}  SHA-256 ${meta.sha256.take(16)}…")

        var proc: SuManager.ShellProcess? = null
        try {
            proc = su.open(cmd)
            val stdin = BufferedOutputStream(proc.stdin(), 1024 * 1024)
            image.file.inputStream().use { input ->
                val buf = ByteArray(1024 * 1024)
                var written = 0L
                var last = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    stdin.write(buf, 0, n)
                    written += n
                    if (written - last >= 8L * 1024 * 1024 || written >= meta.bytes) {
                        last = written
                        onProgress(written, meta.bytes)
                    }
                }
                stdin.flush()
                proc.closeStdin()
            }
        } catch (e: Exception) {
            runCatching { proc?.destroy() }
            log.error("❌ 写入异常: ${e.message}")
            return@withContext CommandCheck(false, listOf("写入异常: ${e.message}"))
        }

        val exited = proc.waitForExit(600_000)
        if (!exited) {
            runCatching { proc.destroy() }
            log.error("❌ 写入超时")
            return@withContext CommandCheck(false, listOf("写入超时"))
        }
        val exit = proc.exitCode()
        val errText = proc.stderrText().take(200)
        if (exit != 0) {
            log.error("❌ dd 写入失败 (exit=$exit): $errText")
            return@withContext CommandCheck(false, listOf("dd 写入失败 (exit=$exit): $errText"))
        }
        log.ok("✔ 写入完成，开始回读校验…")

        // 回读校验：读前 bytes 字节算 SHA-256，与镜像比对
        val readCmd = FlashPlanner.readBackCommand(target, meta.bytes)
            ?: return@withContext CommandCheck(false, listOf("回读指令生成失败"))
        val r = su.exec(readCmd, 900_000)
        val actual = r.stdout.trim().split(Regex("\\s+")).firstOrNull { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }
        if (actual == null) {
            log.error("❌ 回读哈希失败: ${r.stdout.take(80)} ${r.stderr.take(80)}")
            return@withContext CommandCheck(false, listOf("回读哈希失败"))
        }
        val verdict = FlashPlanner.verifyWritten(meta, meta.bytes, actual)
        if (verdict.ok) {
            log.ok("✔✔✔ 回读校验通过：分区前 ${fmtSize(meta.bytes)} 的 SHA-256 与镜像一致")
            log.ok("   SHA-256: $actual")
        } else {
            log.error("❌ 回读校验失败：${verdict.summary()}")
        }
        verdict
    }

    /** 刷完/取消后清临时镜像 */
    fun finish() {
        cleanup()
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
