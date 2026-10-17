package com.kazaf.mirrorbackup.core

/** 备份模式（4 种） */
enum class BackupMode(val key: String, val label: String, val desc: String) {
    ALL("all", "全部备份", "扫到多少备份多少，含 super 与引导分区"),
    NORMAL("normal", "普通备份", "跳过 super 与大于 10GB 的分区"),
    MANUAL("manual", "手动备份", "自行勾选需要备份的分区"),
    PRIVATE("private", "私有分区备份", "仅 efs/nv/modem 等硬件私有分区")
}

/** CPU 检测：自动识别核心数并把备份线程拉满 */
object CpuDetector {
    /** 可用 CPU 核心数（含大小核） */
    val cores: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 32)

    /** 满核线程数（2~16，避免小设备把 IO 打满反而变慢） */
    val maxThreads: Int = cores.coerceIn(2, 16)

    /** 大核数量（从 max_freq 推断，用于展示） */
    val bigCores: Int by lazy {
        try {
            val cpus = java.io.File("/sys/devices/system/cpu")
                .listFiles { f -> f.name.matches(Regex("cpu[0-9]+")) } ?: return@lazy 0
            val freqs = cpus.mapNotNull { c ->
                java.io.File(c, "cpufreq/cpuinfo_max_freq").takeIf { it.exists() }
                    ?.readText()?.trim()?.toLongOrNull()
            }
            if (freqs.isEmpty()) 0
            else {
                val max = freqs.max()
                freqs.count { it == max }
            }
        } catch (e: Exception) {
            0
        }
    }

    val label: String
        get() = buildString {
            append("检测到 $cores 核")
            if (bigCores in 1 until cores) append("（大核 $bigCores）")
            append(" → 满核 $maxThreads 线程")
        }
}

/** 备份线程模式（多线程 = 自动满核） */
enum class ThreadMode(val label: String, val fixedCount: Int) {
    SINGLE("单线程", 1),
    DUAL("双线程", 2),
    MULTI("多线程·满核", 0);

    /** 实际线程数：MULTI 为 CPU 满核 */
    val count: Int get() = if (fixedCount > 0) fixedCount else CpuDetector.maxThreads

    /** 展示用标签 */
    val displayLabel: String get() = if (fixedCount > 0) label else "$label(${CpuDetector.maxThreads})"
}

fun modeLabelOf(key: String): String =
    BackupMode.entries.firstOrNull { it.key == key }?.label ?: key
