package com.kazaf.mirrorbackup.core

import java.util.Locale

/** 免责声明（首次启动弹窗 + 恢复页底部永久显示） */
const val DISCLAIMER_TEXT =
    "【免责声明：本工具仅用于备份自己设备的硬件分区镜像。将镜像刷入不匹配的设备会造成基带永久损坏。" +
        "所有操作风险由使用者本人承担，作者不对任何硬件损坏负责。】"

internal fun f(format: String, vararg args: Any?): String =
    String.format(Locale.US, format, *args)

/** 人性化字节大小 */
fun fmtSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> f("%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> f("%.1f MB", bytes / 1024.0 / 1024)
    else -> f("%.2f GB", bytes / 1024.0 / 1024 / 1024)
}

/** 剩余时间人性化 */
fun fmtEta(seconds: Long): String = when {
    seconds < 0 -> "--"
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m${seconds % 60}s"
    else -> "${seconds / 3600}h${(seconds % 3600) / 60}m"
}
