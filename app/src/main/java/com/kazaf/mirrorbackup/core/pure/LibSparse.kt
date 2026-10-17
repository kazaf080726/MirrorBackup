package com.kazaf.mirrorbackup.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * 内置 libsparse —— 纯 Kotlin 静态实现，与 Android libsparse 稀疏镜像格式 100% 兼容。
 *
 * 文件头 (28B)：magic 0xED26FF3A | major=1 | minor=0 | file_hdr_sz=28 | chunk_hdr_sz=12
 *             | block_size | total_blocks | total_chunks | header CRC32
 * chunk 头 (12B)：type u16 | reserved u16 | chunk_sz(blocks) u32 | total_sz(bytes) u32
 * chunk 类型：0xCAC1 RAW | 0xCAC2 FILL | 0xCAC3 DONTCARE | 0xCAC4 CRC32
 *
 * 生成的镜像可被 fastboot / simg2img 直接识别；也支持解码还原 RAW。
 */
object LibSparse {

    const val MAGIC = 0xED26FF3AL
    const val CHUNK_RAW = 0xCAC1
    const val CHUNK_FILL = 0xCAC2
    const val CHUNK_DONT_CARE = 0xCAC3
    const val CHUNK_CRC32 = 0xCAC4

    const val HEADER_SIZE = 28
    const val CHUNK_HEADER_SIZE = 12
    const val MAX_CHUNK_BLOCKS = 65535L // u16 上限

    class SparseHeader(
        val blockSize: Int,
        val totalBlocks: Long,
        val totalChunks: Long,
        val fileHeaderSize: Int,
        val chunkHeaderSize: Int
    ) {
        val imageSize: Long get() = totalBlocks * blockSize
    }

    // ---------- 小端读写 ----------

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun putLe16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun putLe32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun readFully(s: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = s.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    // ---------- 解析 ----------

    fun readHeader(s: InputStream): SparseHeader {
        val hdr = ByteArray(HEADER_SIZE)
        if (!readFully(s, hdr)) throw IllegalArgumentException("文件过短，不是有效的稀疏镜像")
        val magic = le32(hdr, 0)
        if (magic != MAGIC) throw IllegalArgumentException("非法 sparse magic: %08x".format(magic))
        val major = le16(hdr, 4)
        if (major != 1) throw IllegalArgumentException("不支持的 sparse 版本: $major")
        val fileHdrSz = le16(hdr, 8)
        val chunkHdrSz = le16(hdr, 10)
        if (fileHdrSz < HEADER_SIZE) throw IllegalArgumentException("非法 file_hdr_sz: $fileHdrSz")
        if (chunkHdrSz < CHUNK_HEADER_SIZE) throw IllegalArgumentException("非法 chunk_hdr_sz: $chunkHdrSz")
        // 校验头部 CRC32
        val expectedCrc = le32(hdr, 24)
        val crc = CRC32()
        crc.update(hdr, 0, 24)
        if (crc.value != expectedCrc) throw IllegalArgumentException("稀疏镜像头部 CRC32 校验失败")
        val blockSize = le32(hdr, 12).toInt()
        if (blockSize < 512 || blockSize > 64 * 1024 * 1024 || blockSize % 4 != 0) {
            throw IllegalArgumentException("非法 block_size: $blockSize")
        }
        if (fileHdrSz > HEADER_SIZE) skipFully(s, fileHdrSz - HEADER_SIZE)
        return SparseHeader(blockSize, le32(hdr, 16), le32(hdr, 20), fileHdrSz, chunkHdrSz)
    }

    private fun skipFully(s: InputStream, len: Int) {
        var left = len
        while (left > 0) {
            val n = s.skip(left.toLong())
            if (n <= 0) {
                if (s.read() < 0) throw IllegalArgumentException("数据不完整")
                left--
            } else {
                left -= n.toInt()
            }
        }
    }

    /**
     * 解码稀疏镜像。out 可为 null（仅做结构校验并统计展开字节数）。
     * @return 展开后的 RAW 字节总数
     */
    fun decode(s: InputStream, out: OutputStream?, onProgress: (Long) -> Unit = {}): Long {
        val header = readHeader(s)
        val chunkHdrSz = header.chunkHeaderSize
        var expanded = 0L
        var chunks = 0L
        var lastReport = 0L
        val buf = ByteArray(256 * 1024)
        val patternBuf = ByteArray(minOf(header.blockSize, 1024 * 1024))
        val zeroBuf = ByteArray(minOf(header.blockSize, 1024 * 1024))

        fun report() {
            if (expanded - lastReport >= 16L * 1024 * 1024) {
                onProgress(expanded)
                lastReport = expanded
            }
        }

        while (chunks < header.totalChunks) {
            val ch = ByteArray(chunkHdrSz)
            if (!readFully(s, ch)) throw IllegalArgumentException("稀疏镜像 chunk $chunks 数据不完整")
            val type = le16(ch, 0)
            val chunkBlocks = le32(ch, 4)
            val totalBytes = le32(ch, 8).toInt()

            when (type) {
                CHUNK_RAW -> {
                    copyBytes(s, out, totalBytes.toLong(), buf)
                    expanded += totalBytes
                }

                CHUNK_FILL -> {
                    val pat = ByteArray(4)
                    if (!readFully(s, pat)) throw IllegalArgumentException("FILL chunk 数据不完整")
                    for (i in patternBuf.indices) patternBuf[i] = pat[i % 4]
                    val total = chunkBlocks * header.blockSize
                    if (out != null) {
                        var left = total
                        while (left > 0) {
                            val n = minOf(left, patternBuf.size.toLong()).toInt()
                            out.write(patternBuf, 0, n)
                            left -= n
                        }
                    }
                    expanded += total
                }

                CHUNK_DONT_CARE -> {
                    val total = chunkBlocks * header.blockSize
                    if (out != null) {
                        var left = total
                        while (left > 0) {
                            val n = minOf(left, zeroBuf.size.toLong()).toInt()
                            out.write(zeroBuf, 0, n)
                            left -= n
                        }
                    }
                    expanded += total
                }

                CHUNK_CRC32 -> {
                    val crcBytes = ByteArray(4)
                    if (!readFully(s, crcBytes)) throw IllegalArgumentException("CRC chunk 数据不完整")
                    val expectedCrc = le32(crcBytes, 0)
                    val crc = CRC32()
                    var remain = totalBytes
                    while (remain > 0) {
                        val n = s.read(buf, 0, minOf(remain, buf.size))
                        if (n < 0) throw IllegalArgumentException("CRC chunk 数据不完整")
                        crc.update(buf, 0, n)
                        if (out != null) out.write(buf, 0, n)
                        remain -= n
                    }
                    if (crc.value != expectedCrc) throw IllegalArgumentException("稀疏镜像 CRC32 校验失败（数据损坏）")
                    expanded += totalBytes
                }

                else -> throw IllegalArgumentException("未知 sparse chunk 类型: %04x".format(type))
            }
            chunks++
            report()
        }
        onProgress(expanded)
        return expanded
    }

    private fun copyBytes(s: InputStream, out: OutputStream?, len: Long, buf: ByteArray) {
        var remain = len
        while (remain > 0) {
            val n = s.read(buf, 0, minOf(remain, buf.size.toLong()).toInt())
            if (n < 0) throw IllegalArgumentException("RAW chunk 数据不完整")
            if (out != null) out.write(buf, 0, n)
            remain -= n
        }
    }

    // ---------- 编码（RAW → SPARSE） ----------

    /**
     * 将 RAW 镜像流编码为稀疏镜像文件。
     * blockSize 自适应：镜像大小能被 4096 整除时用 4096，否则退回 512。
     * 全零块 → DONTCARE chunk；数据块 → RAW chunk（每 chunk ≤ 16MB，内存有界）。
     * @return 稀疏镜像文件长度
     */
    fun encode(input: InputStream, inputLength: Long, outFile: File, onProgress: (Long) -> Unit = {}): Long {
        require(inputLength > 0) { "镜像长度必须大于 0" }
        val blockSize = when {
            inputLength % 4096L == 0L -> 4096
            inputLength % 512L == 0L -> 512
            else -> 512
        }
        val totalBlocks = (inputLength + blockSize - 1) / blockSize
        val block = ByteArray(blockSize)
        val zeroBlock = ByteArray(blockSize)
        val maxDataBuf = 16 * 1024 * 1024
        val dataBuf = ByteArrayOutputStream(maxDataBuf)
        var dataBlocks = 0L
        var zeroRun = 0L
        var chunks = 0L
        var consumed = 0L

        val raf = RandomAccessFile(outFile, "rw")
        try {
            raf.setLength(0)
            raf.write(ByteArray(HEADER_SIZE)) // 占位，结束后回填

            fun flushZeroRun() {
                if (zeroRun <= 0) return
                var left = zeroRun
                while (left > 0) {
                    val n = minOf(left, MAX_CHUNK_BLOCKS)
                    writeChunkHeader(raf, CHUNK_DONT_CARE, n, n * blockSize)
                    left -= n
                    chunks++
                }
                zeroRun = 0
            }

            fun flushData() {
                if (dataBlocks <= 0) return
                writeChunkHeader(raf, CHUNK_RAW, dataBlocks, dataBlocks * blockSize)
                raf.write(dataBuf.toByteArray())
                dataBuf.reset()
                dataBlocks = 0
                chunks++
            }

            while (consumed < totalBlocks * blockSize) {
                var off = 0
                while (off < blockSize) {
                    val n = input.read(block, off, blockSize - off)
                    if (n < 0) break
                    off += n
                }
                while (off < blockSize) block[off++] = 0 // 末尾零填充
                consumed += blockSize

                if (block.contentEquals(zeroBlock)) {
                    flushData()
                    zeroRun++
                } else {
                    flushZeroRun()
                    if (dataBlocks >= MAX_CHUNK_BLOCKS || dataBuf.size() + blockSize > maxDataBuf) {
                        flushData()
                    }
                    dataBuf.write(block)
                    dataBlocks++
                }
                if (consumed % (64L * 1024 * 1024) == 0L) onProgress(minOf(consumed, inputLength))
            }
            flushZeroRun()
            flushData()

            // 回填文件头
            val header = ByteArray(HEADER_SIZE)
            putLe32(header, 0, MAGIC)
            putLe16(header, 4, 1)          // major
            putLe16(header, 6, 0)          // minor
            putLe16(header, 8, HEADER_SIZE)
            putLe16(header, 10, CHUNK_HEADER_SIZE)
            putLe32(header, 12, blockSize.toLong())
            putLe32(header, 16, totalBlocks)
            putLe32(header, 20, chunks)
            val crc = CRC32()
            crc.update(header, 0, 24)
            putLe32(header, 24, crc.value)
            raf.seek(0)
            raf.write(header)

            onProgress(inputLength)
            return raf.length()
        } finally {
            raf.close()
        }
    }

    private fun writeChunkHeader(raf: RandomAccessFile, type: Int, blocks: Long, bytes: Long) {
        val h = ByteArray(CHUNK_HEADER_SIZE)
        putLe16(h, 0, type)
        putLe16(h, 2, 0) // reserved
        putLe32(h, 4, blocks)
        putLe32(h, 8, bytes)
        raf.write(h)
    }
}
