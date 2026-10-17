package com.kazaf.mirrorbackup.core

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份输出位置 —— 内部存储根目录「镜像备份」文件夹（可直接在文件管理器中看到）。
 * 未授予「所有文件访问权限」时回退到应用私有目录，并在日志中明确提示。
 */
object OutputStore {

    const val ROOT_NAME = "镜像备份"
    val rootPath: String get() = "/storage/emulated/0/$ROOT_NAME"

    fun rootDir(): File = File(rootPath)

    /** 是否已授予 MANAGE_EXTERNAL_STORAGE（Android 11+） */
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    /**
     * 准备本次备份会话目录：
     * - 已授权 → /storage/emulated/0/镜像备份/备份_yyyyMMdd_HHmmss/
     * - 未授权 → files/backup_work/（私有，日志告警）
     * 同时在根目录写一份说明文件，标明这是镜像备份文件夹。
     */
    fun prepareSession(context: Context, log: LogBuffer): File {
        if (hasAllFilesAccess()) {
            val root = rootDir()
            try {
                if (!root.exists()) root.mkdirs()
                ensureReadme(root)
                val session = File(root, "备份_${stamp()}")
                if (!session.exists()) session.mkdirs()
                if (session.isDirectory) {
                    log.ok("📁 备份目录: ${session.absolutePath}")
                    log.info("   已写入内部存储「$ROOT_NAME」文件夹（可用文件管理器直接查看）")
                    return session
                }
                log.warn("⚠ 无法创建会话目录，回退应用私有目录")
            } catch (e: Exception) {
                log.warn("⚠ 外部目录不可写（${e.message}），回退应用私有目录")
            }
        } else {
            log.warn("⚠ 未授予「所有文件访问权限」——本次备份写入应用私有目录")
            log.warn("   如需写入内部存储「$ROOT_NAME」文件夹，请到设置 → 存储位置 → 授予全部文件访问权限")
        }
        val fallback = File(File(context.filesDir, "backup_work"), stamp())
        fallback.mkdirs()
        log.info("📁 备份目录（私有）: ${fallback.absolutePath}")
        return fallback
    }

    /** 根目录说明文件：标明这是什么文件夹 */
    private fun ensureReadme(root: File) {
        val readme = File(root, "说明_这是什么文件夹.txt")
        if (readme.exists() && readme.length() > 0) return
        readme.writeText(
            """
            【镜像备份 · 备份数据文件夹】
            
            本文件夹由「镜像备份」App（作者：Kazaf）自动创建，
            用于存放 Android 设备分区的原始镜像备份（.img / .simg）。
            
            目录结构：
              备份_日期_时间/
                ├─ 分区名.img        原始镜像（RAW）
                ├─ 分区名.simg       稀疏镜像（SPARSE，仅存储模式）
                ├─ 分区名.sha256     每个镜像的 SHA-256 校验值
                └─ manifest.json     备份清单（设备指纹 / 分区表 / 哈希）
            
            安全说明：
              · 本文件夹内的镜像仅限用于你自己的同型号设备恢复。
              · 将镜像刷入不匹配的设备会造成基带/硬件永久损坏。
              · 请勿手动修改或删除 manifest.json 与 .sha256 文件。
            
            恢复方式：打开「镜像备份」App → 恢复工作台 → 选择本文件夹中
            的备份 ZIP 包或镜像目录，App 会执行 SHA-256 + CRC32 双重校验。
            """.trimIndent()
        )
    }

    /** 列出已有备份会话（最新在前） */
    fun listSessions(): List<File> {
        val root = rootDir()
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("备份_") }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }

    /** 备份是否在外部目录 */
    fun isExternal(dir: File): Boolean = dir.absolutePath.startsWith(rootPath)
}
