package com.kazaf.mirrorbackup.core

data class PartitionInfo(
    val name: String,
    val path: String,
    val sizeBytes: Long
)

/**
 * 规则3：分区黑白名单定义
 * 🟢 私有硬件分区（两种模式都会包含）
 * 🟡 仅全量模式才会加入（super / userdata）
 * 🔴 黑名单：永远不扫描、不备份、不写入（boot/dtbo/vbmeta/vbmeta_system/recovery）
 */
object PartitionRules {

    val GREEN = listOf(
        "efs", "nv", "modem", "persist", "wifical",
        "btcal", "dsp", "factory", "keystore", "tee"
    )

    val YELLOW = listOf("super", "userdata")

    val BLACK = listOf("boot", "dtbo", "vbmeta", "vbmeta_system", "recovery")

    /** 普通备份模式：大于 10GB 的分区不备份 */
    const val LARGE_THRESHOLD = 10L * 1024 * 1024 * 1024

    fun isBlacklisted(name: String): Boolean {
        if (name in BLACK) return true
        for (b in BLACK) if (name == "${b}_a" || name == "${b}_b") return true
        return false
    }

    fun isGreen(name: String): Boolean {
        if (name in GREEN) return true
        for (g in GREEN) if (name == "${g}_a" || name == "${g}_b") return true
        return false
    }

    fun isSuper(name: String): Boolean =
        name == "super" || name == "super_a" || name == "super_b"

    /**
     * 按备份模式选择目标分区，黑名单永远排除（二次防御）：
     * ALL     —— 全部（扫到多少备份多少，含 super 与大容量分区）
     * NORMAL  —— 跳过 super 与大于 10GB 的分区
     * MANUAL  —— 仅用户勾选的分区
     * PRIVATE —— 仅硬件私有分区
     */
    fun select(all: List<PartitionInfo>, mode: BackupMode, manualSelection: Set<String> = emptySet()): List<PartitionInfo> {
        return analyze(all, mode, manualSelection).targets
    }

    /** 模式排除明细：扫描总数 / 黑名单 / super / 大容量 / 最终目标 */
    data class ModeBreakdown(
        val scanned: List<PartitionInfo>,
        val blacklisted: List<PartitionInfo>,
        val superParts: List<PartitionInfo>,
        val largeParts: List<PartitionInfo>,
        val targets: List<PartitionInfo>
    ) {
        val scannedCount: Int get() = scanned.size
        val targetCount: Int get() = targets.size
    }

    fun analyze(all: List<PartitionInfo>, mode: BackupMode, manualSelection: Set<String> = emptySet()): ModeBreakdown {
        val black = all.filter { isBlacklisted(it.name) }
        val clean = all.filterNot { isBlacklisted(it.name) }
        val superParts = clean.filter { isSuper(it.name) }
        val largeParts = clean.filter { !isSuper(it.name) && it.sizeBytes > LARGE_THRESHOLD }
        val targets = when (mode) {
            BackupMode.ALL -> clean
            BackupMode.NORMAL -> clean.filterNot { isSuper(it.name) || it.sizeBytes > LARGE_THRESHOLD }
            BackupMode.MANUAL -> clean.filter { it.name in manualSelection }
            BackupMode.PRIVATE -> clean.filter { isGreen(it.name) }
        }.distinctBy { it.name }
        return ModeBreakdown(all, black, superParts, largeParts, targets)
    }
}

object PartitionScanner {

    private val BY_NAME_DIRS = listOf(
        "/dev/block/by-name",
        "/dev/block/platform/*/by-name",
        "/dev/block/bootdevice/by-name"
    )

    /**
     * 枚举 /dev/block/by-name 下所有分区。
     * 输出格式：名称|真实节点路径|扇区数（512B/扇区）。
     */
    fun scan(su: SuManager, log: LogBuffer): List<PartitionInfo> {
        for (dir in BY_NAME_DIRS) {
            val script = buildString {
                append("found=0; for d in $dir; do ")
                append("[ -d \"\$d\" ] || continue; found=1; ")
                append("for p in \"\$d\"/*; do n=\${p##*/}; ")
                append("t=\$(readlink -f \"\$p\" 2>/dev/null); b=\${t##*/}; ")
                append("sz=\$(cat /sys/class/block/\$b/size 2>/dev/null); ")
                append("echo \"\$n|\$t|\$sz\"; done; done; [ \"\$found\" = 1 ]")
            }
            val r = su.exec(script, 20_000)
            if (r.exitCode != 0) {
                log.warn("枚举 $dir 失败: ${r.stderr.take(120)}")
                continue
            }
            val parts = parse(r.stdout)
            if (parts.isNotEmpty()) return parts
        }
        return emptyList()
    }

    private fun parse(stdout: String): List<PartitionInfo> {
        val out = mutableListOf<PartitionInfo>()
        for (line in stdout.lines()) {
            val cols = line.split("|")
            if (cols.size < 3) continue
            val name = cols[0].trim()
            val path = cols[1].trim()
            val sectors = cols[2].trim().toLongOrNull() ?: continue
            if (name.isEmpty() || sectors <= 0 || name.contains('/')) continue
            out += PartitionInfo(name, path, sectors * 512L)
        }
        return out.sortedBy { it.name }
    }

    /**
     * 规则1-2：读取分区前校验节点有效性 —— dd 试读 1 个块，
     * 检测节点能否正常打开、是否存在 IO 异常。
     * @return (是否通过, 输出详情)
     */
    fun validatePartition(su: SuManager, name: String): Pair<Boolean, String> {
        val path = "/dev/block/by-name/$name"
        val script = "dd if=${SuManager.quote(path)} of=/dev/null bs=4096 count=1 2>&1; echo EXIT=\$?"
        val r = su.exec(script, 15_000)
        val ok = r.exitCode == 0 && r.stdout.contains("EXIT=0")
        return ok to r.stdout.trim().replace("\n", " | ")
    }
}
