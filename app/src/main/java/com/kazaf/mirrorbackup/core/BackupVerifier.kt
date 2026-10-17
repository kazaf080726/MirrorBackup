package com.kazaf.mirrorbackup.core

import java.io.File
import java.security.MessageDigest

/**
 * 已有备份校验 —— 校验「镜像备份」文件夹中最近一次（或指定）备份会话：
 * 逐个镜像重算 SHA-256（经 su / 模块），与 manifest.json 中记录比对，
 * 同时校验文件大小与清单完整性。纯只读，不写入任何数据。
 */
object BackupVerifier {

    data class Result(
        val dir: File,
        val total: Int,
        val ok: Int,
        val failed: List<String>,
        val missing: List<String>
    ) {
        val allOk: Boolean get() = failed.isEmpty() && missing.isEmpty() && total > 0
    }

    /** 取最新的备份会话目录（外部「镜像备份」文件夹优先，否则私有目录） */
    fun latestSession(): File? {
        val external = OutputStore.listSessions()
        if (external.isNotEmpty()) return external.first()
        return null
    }

    /**
     * 校验会话目录：解析 manifest.json → 逐个镜像 su sha256sum 比对。
     */
    fun verify(su: SuManager, dir: File, log: LogBuffer): Result {
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.exists()) {
            log.error("❌ 目录中没有 manifest.json：${dir.absolutePath}")
            return Result(dir, 0, 0, emptyList(), emptyList())
        }
        val manifest = try {
            BackupManifest.fromJson(manifestFile.readText())
        } catch (e: Exception) {
            log.error("❌ manifest.json 解析失败（文件可能损坏）: ${e.message}")
            return Result(dir, 0, 0, emptyList(), emptyList())
        }
        log.info("🔍 校验备份: ${dir.name}（${manifest.partitions.size} 个镜像，创建于 ${manifest.createdAt}）")
        var ok = 0
        val failed = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for (p in manifest.partitions) {
            val img = File(dir, "${p.name}.img").takeIf { it.exists() }
                ?: File(dir, "${p.name}.simg").takeIf { it.exists() }
            if (img == null) {
                missing += p.name
                log.error("❌ 缺少镜像文件: ${p.name}")
                continue
            }
            if (img.length() != p.sizeBytes) {
                failed += p.name
                log.error("❌ ${p.name} 大小不符: 期望 ${fmtSize(p.sizeBytes)} 实际 ${fmtSize(img.length())}")
                continue
            }
            val r = su.exec("sha256sum ${SuManager.quote(img.absolutePath)} 2>&1", 3_600_000)
            val sha = r.stdout.trim().substringBefore(' ').lowercase()
            if (sha.length == 64 && sha == p.sha256.lowercase()) {
                ok++
                log.ok("✔ ${p.name} SHA-256 一致 (${sha.take(16)}…)")
            } else {
                failed += p.name
                log.error("❌ ${p.name} SHA-256 不一致: 记录=${p.sha256.take(16)}… 实际=${sha.take(16)}…")
            }
        }
        val res = Result(dir, manifest.partitions.size, ok, failed, missing)
        if (res.allOk) {
            log.ok("✔✔✔ 校验完成：${res.total} 个镜像全部一致，备份完好可恢复")
        } else {
            log.error("❌ 校验完成：${res.total} 个镜像中 ${res.failed.size} 个损坏、${res.missing.size} 个缺失")
        }
        return res
    }
}
