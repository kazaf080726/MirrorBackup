package com.kazaf.mirrorbackup.core

import android.content.Context
import java.io.File

/**
 * 临时文件 / 缓存清理器 —— 备份、恢复、解包三个工作台共用。
 *
 * 原则：只删「确定是临时产物」的东西，绝不动用户真正的备份镜像与解包输出。
 * 目录只做「空目录清理」，非空目录一律保留。
 */
object TempCleaner {

    class Stats {
        var bytes = 0L
        var count = 0
            private set

        fun record(size: Long) {
            bytes += size
            count++
        }

        fun isEmpty(): Boolean = count == 0

        fun summary(): String = "$count 个临时项 / ${fmtSize(bytes)}"
    }

    fun sizeOf(f: File): Long =
        if (f.isDirectory) (f.listFiles() ?: emptyArray()).sumOf { sizeOf(it) } else f.length()

    private fun purge(f: File, stats: Stats) {
        if (!f.exists()) return
        val size = sizeOf(f)
        val ok = try {
            if (f.isDirectory) f.deleteRecursively() else f.delete()
        } catch (e: Exception) {
            false
        }
        if (ok) stats.record(size)
    }

    /** 应用缓存里的临时副本（三台共用）：恢复的 dd 错误输出、SAF 落地副本、诊断/基准产物、旧内嵌 APK */
    fun cleanAppCache(ctx: Context, stats: Stats) {
        val cache = ctx.cacheDir
        try {
            cache.listFiles()?.forEach { f ->
                when {
                    f.name.startsWith("restore_") && f.name.endsWith(".err") -> purge(f, stats)
                    f.name.startsWith("bench_") -> purge(f, stats)
                    else -> Unit
                }
            }
            (File(cache, "unpack").listFiles() ?: emptyArray()).forEach { purge(it, stats) }
            (File(cache, "flash").listFiles() ?: emptyArray()).forEach { purge(it, stats) }
            purge(File(cache, "selftest"), stats)
            purge(File(cache, "payload_dumper.apk"), stats)
        } catch (_: Exception) {
        }
    }

    /** 只清空目录（保留目录本身）：用户可见输出目录里因中断/失败留下的空会话目录 */
    fun purgeEmptyDirs(dir: File, stats: Stats) {
        if (!dir.isDirectory) return
        try {
            dir.listFiles()?.forEach { d ->
                if (d.isDirectory && (d.listFiles()?.isEmpty() != false)) purge(d, stats)
            }
        } catch (_: Exception) {
        }
    }

    /** 递归删除目录内的临时文件（dd 错误输出 / .part 半成品），镜像本体不动 */
    private fun purgeTempInside(dir: File, stats: Stats) {
        if (!dir.isDirectory) return
        try {
            dir.listFiles()?.forEach { f ->
                if (f.isFile) {
                    if (f.name.endsWith(".dd.err") || f.name.endsWith(".part")) purge(f, stats)
                } else {
                    purgeTempInside(f, stats)
                }
            }
        } catch (_: Exception) {
        }
    }

    /** 备份工作台：应用缓存 + 备份目录里的 dd 错误输出 + 空目录（镜像本体是用户数据，绝不动） */
    fun cleanBackup(ctx: Context, stats: Stats) {
        cleanAppCache(ctx, stats)
        // 私有兜底工作目录
        val work = File(ctx.filesDir, "backup_work")
        purgeTempInside(work, stats)
        purgeEmptyDirs(work, stats)
        // 公共备份目录（内部存储「镜像备份」）
        val root = OutputStore.rootDir()
        purgeTempInside(root, stats)
        purgeEmptyDirs(root, stats)
    }

    /** 恢复工作台：应用缓存（restore_*.err）+ 空目录 */
    fun cleanRestore(ctx: Context, stats: Stats) {
        cleanAppCache(ctx, stats)
    }

    /** 解包工作台：应用缓存 + 输出目录空目录（payload 临时拷贝由解包引擎按来源单独判断） */
    fun cleanUnpack(ctx: Context, stats: Stats) {
        cleanAppCache(ctx, stats)
        purgeEmptyDirs(File("/storage/emulated/0/解包工具箱"), stats)
    }
}
