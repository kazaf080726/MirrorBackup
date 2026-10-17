package com.kazaf.mirrorbackup.core.pure

import com.kazaf.mirrorbackup.core.LibSparse
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * 恢复工作台的镜像校验 / 写入核心（纯 JVM 实现，不含任何 Android 依赖）。
 *
 * APP 里 RestoreEngine 的第①层校验（SHA-256 + ZIP CRC32 + 展开大小）
 * 与写入分区（raw 直通 / sparse 展开）都走这里，
 * 因此可以在电脑上用「假手机环境」（假分区、假 manifest、假 ZIP）跑真流程。
 */
data class ImageCheck(
    val name: String,
    val format: String,
    val declaredSize: Long,
    val declaredSha256: String,
    /** 实际读出的字节流 SHA-256（sparse 时为 .simg 文件的哈希） */
    val actualSha256: String,
    /** 展开后的字节数（sparse 为解码后大小，raw 为原样大小） */
    val expandedBytes: Long,
    /** ZIP 条目记录里保存的 CRC32，-1 表示未知 */
    val crcStored: Long,
    /** 实际算出的 CRC32 */
    val crcActual: Long,
    /** 结构损坏（sparse 头/chunk 校验失败等）时的原因 */
    val structureError: String? = null
) {
    val shaOk: Boolean get() = actualSha256.equals(declaredSha256, ignoreCase = true)
    val sizeOk: Boolean get() = expandedBytes == declaredSize
    val crcOk: Boolean get() = crcStored == -1L || crcStored == crcActual
    val ok: Boolean get() = structureError == null && shaOk && sizeOk && crcOk

    fun summary(): String {
        val state = if (ok) "PASS" else "FAIL"
        val reasons = buildList {
            if (structureError != null) add("结构损坏($structureError)")
            if (!shaOk) add("SHA-256 不一致 manifest=${declaredSha256.take(16)}… 实际=${actualSha256.take(16)}…")
            if (!sizeOk) add("展开大小 $expandedBytes ≠ manifest $declaredSize")
            if (!crcOk) add("CRC32 记录=${"%08x".format(crcStored)} 实际=${"%08x".format(crcActual)}")
        }
        return "[$state] $name (${format}) sha=${declaredSha256.take(12)}… ${if (reasons.isEmpty()) "全部一致" else reasons.joinToString("; ")}"
    }
}

/** 边读边算 SHA-256 + CRC32 的包装流（只读，不落盘） */
class DigestCrcStream(
    private val base: InputStream,
    private val digest: MessageDigest,
    private val crc: CRC32
) : InputStream() {
    override fun read(): Int {
        val b = base.read()
        if (b >= 0) {
            digest.update(b.toByte())
            crc.update(b)
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = base.read(b, off, len)
        if (n > 0) {
            digest.update(b, off, n)
            crc.update(b, off, n)
        }
        return n
    }

    override fun available(): Int = base.available()
    override fun close() = base.close()
}

object RestoreVerify {

    const val BUF_SIZE = 1024 * 1024

    /**
     * 校验一个 ZIP 条目内的镜像流（与 APP 恢复前第①层校验完全一致）。
     * - sha：对**存储内容**做 SHA-256（sparse 即 .simg 本身，与备份时一致）
     * - size：sparse 取展开后字节数，raw 取原样字节数，与 manifest 记录比对
     * - crc：ZIP 条目记录的 CRC32 与实际读取内容的 CRC32 比对
     */
    fun verifyEntry(
        name: String,
        format: String,
        declaredSize: Long,
        declaredSha256: String,
        crcStored: Long,
        input: InputStream,
        onProgress: (Long) -> Unit = {}
    ): ImageCheck {
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        val checked = DigestCrcStream(input, digest, crc)
        var expanded = 0L
        var structureError: String? = null
        try {
            if (format == "sparse") {
                expanded = LibSparse.decode(checked, null, onProgress)
            } else {
                val buf = ByteArray(BUF_SIZE)
                var since = 0L
                while (true) {
                    val n = checked.read(buf)
                    if (n < 0) break
                    expanded += n
                    since += n
                    if (since >= 32L * 1024 * 1024) {
                        since = 0
                        onProgress(expanded)
                    }
                }
                onProgress(expanded)
            }
        } catch (ex: Exception) {
            structureError = ex.message ?: ex.javaClass.simpleName
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return ImageCheck(
            name = name,
            format = format,
            declaredSize = declaredSize,
            declaredSha256 = declaredSha256,
            actualSha256 = actual,
            expandedBytes = expanded,
            crcStored = crcStored,
            crcActual = crc.value,
            structureError = structureError
        )
    }

    /**
     * 把镜像流写进目标输出（APP 里是 dd 的 stdin，电脑上是假分区文件）。
     * sparse 先展开再写；raw 原样写。返回写入字节数。
     */
    fun writeEntry(
        input: InputStream,
        out: OutputStream,
        format: String,
        onProgress: (Long) -> Unit = {}
    ): Long {
        var written = 0L
        if (format == "sparse") {
            written = LibSparse.decode(input, out, onProgress)
        } else {
            val buf = ByteArray(BUF_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                written += n
                onProgress(written)
            }
        }
        out.flush()
        return written
    }

    /** 目标分区是否可写（空间门槛与 APP 内恢复前的第②层校验同一判断） */
    fun targetBlockReason(imageBytes: Long, targetExists: Boolean, targetSize: Long): String? = when {
        !targetExists -> "目标分区不存在"
        imageBytes > targetSize -> "空间不足（镜像 $imageBytes > 分区 $targetSize）"
        else -> null
    }
}
