package com.kazaf.mirrorbackup.core

data class PartitionInfo(
    val name: String,
    val path: String,
    val sizeBytes: Long
)

/**
 * 分区规则。
 * —— 应用不做任何隐性排除 ——
 * 全量备份 = 扫到的全部分区（含 super 与 boot/dtbo/vbmeta 等引导相关分区）。
 * 唯一的排除来源是【用户自己加入的黑名单】。
 * boot/dtbo/vbmeta/vbmeta_system/recovery 仅作为"风险提示"（⚠）标记，供用户自行决策。
 */
object PartitionRules {

    val GREEN = listOf(
        "efs", "nv", "modem", "persist", "wifical",
        "btcal", "dsp", "factory", "keystore", "tee"
    )

    val YELLOW = listOf("super", "userdata")

    /** 风险提示分区（仅 UI 提示，绝不自动排除） */
    val RISKY = listOf("boot", "dtbo", "vbmeta", "vbmeta_system", "recovery")

    /** 普通备份模式：大于 10GB 的分区不备份 */
    const val LARGE_THRESHOLD = 10L * 1024 * 1024 * 1024

    fun isRisky(name: String): Boolean {
        if (name in RISKY) return true
        for (b in RISKY) if (name == "${b}_a" || name == "${b}_b") return true
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
     * 按备份模式选择目标分区。排除项仅来自用户黑名单：
     * ALL     —— 全部（扫到多少备份多少）
     * NORMAL  —— 跳过 super 与大于 10GB 的分区
     * MANUAL  —— 仅用户勾选的分区
     * PRIVATE —— 仅硬件私有分区
     */
    fun select(
        all: List<PartitionInfo>,
        mode: BackupMode,
        manualSelection: Set<String> = emptySet(),
        userBlacklist: Set<String> = emptySet()
    ): List<PartitionInfo> = analyze(all, mode, manualSelection, userBlacklist).targets

    /** 模式排除明细：扫描总数 / 用户黑名单 / super / 大容量 / 最终目标 */
    data class ModeBreakdown(
        val scanned: List<PartitionInfo>,
        val userBlacklisted: List<PartitionInfo>,
        val superParts: List<PartitionInfo>,
        val largeParts: List<PartitionInfo>,
        val targets: List<PartitionInfo>
    ) {
        val scannedCount: Int get() = scanned.size
        val targetCount: Int get() = targets.size
        val riskyTargets: List<PartitionInfo> get() = targets.filter { isRisky(it.name) }
    }

    fun analyze(
        all: List<PartitionInfo>,
        mode: BackupMode,
        manualSelection: Set<String> = emptySet(),
        userBlacklist: Set<String> = emptySet()
    ): ModeBreakdown {
        val black = all.filter { it.name in userBlacklist }
        val clean = all.filterNot { it.name in userBlacklist }
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
     * 枚举分区：模块 mb_scan（接口扫描，适配不同机型路径）→ mb_parts → shell 兜底。
     */
    fun scan(su: SuManager, log: LogBuffer): List<PartitionInfo> {
        // 1) 模块接口扫描器 mb_scan（v2.0+）：分区 + 设备环境一次拿全
        val hasMbScan = try {
            su.exec("command -v mb_scan 2>/dev/null", 8_000).stdout.trim().startsWith("/")
        } catch (e: Exception) {
            false
        }
        if (hasMbScan) {
            val r = su.exec("mb_scan", 20_000)
            if (r.exitCode == 0) {
                val envs = r.stdout.lineSequence()
                    .filter { it.startsWith("ENV|") }
                    .map { it.removePrefix("ENV|").split("|") }
                    .filter { it.size >= 2 }
                    .associate { it[0] to it[1] }
                val partLines = r.stdout.lineSequence()
                    .filter { it.startsWith("PART|") }
                    .map { it.removePrefix("PART|").split("|") }
                    .filter { it.size >= 3 }
                    .toList()
                if (partLines.isNotEmpty()) {
                    val parts = partLines.mapNotNull { cols ->
                        val name = cols[0]
                        val path = cols[1]
                        val bytes = cols[2].toLongOrNull() ?: -1L
                        if (bytes <= 0) {
                            log.warn("⚠ 分区 $name 尺寸未知（$path），已跳过")
                            null
                        } else PartitionInfo(name, path, bytes)
                    }.sortedBy { it.name }
                    log.info("⚡ 接口扫描（模块 mb_scan v2.0）: ${parts.size} 个分区")
                    envs["by_name_dir"]?.let { dir ->
                        if (dir != "none") log.info("   分区目录: $dir")
                    }
                    envs["cpu_cores"]?.let { log.info("   CPU 核心: $it 核 · 页大小 ${envs["page_size"] ?: "?"}B") }
                    if (envs["super"] == "1") log.info("   super 动态分区: 检测到")
                    if (envs["mapper"] == "1") log.info("   device-mapper: 已启用（dm 分区自动解析）")
                    if (envs["ab_slot"].let { it != null && it != "none" }) {
                        log.info("   A/B 槽位: ${envs["ab_slot"]}")
                    }
                    envs["proc_partitions"]?.let { n ->
                        val extra = (n.toIntOrNull() ?: 0) - parts.size
                        if (extra > 0) log.info("   /proc/partitions 可见 ${n} 个块设备（含未命名 ${extra} 个）")
                    }
                    return parts
                }
            }
            log.warn("mb_scan 执行异常 (exit=${r.exitCode}, outLen=${r.stdout.length}, errLen=${r.stderr.length}, err=${r.stderr.take(60)})，回退 mb_parts")
        }
        // 2) mb_parts 极速枚举
        val hasMbParts = try {
            su.exec("command -v mb_parts 2>/dev/null", 8_000).stdout.trim().startsWith("/")
        } catch (e: Exception) {
            false
        }
        if (hasMbParts) {
            val r = su.exec("mb_parts", 20_000)
            if (r.exitCode == 0) {
                val parts = parse(r.stdout)
                if (parts.isNotEmpty()) {
                    log.info("⚡ 分区枚举（提取辅助模块 mb_parts）: ${parts.size} 个")
                    return parts
                }
            }
            log.warn("mb_parts 执行异常，回退 shell 枚举: ${r.stderr.take(100)}")
        }
        for (dir in BY_NAME_DIRS) {
            val script = buildString {
                append("found=0; for d in $dir; do ")
                append("[ -d \"\$d\" ] || continue; found=1; ")
                append("for p in \"\$d\"/*; do n=\${p##*/}; ")
                append("t=\$(readlink -f \"\$p\" 2>/dev/null); [ -n \"\$t\" ] || t=\"\$p\"; b=\${t##*/}; ")
                append("sz=\$(cat /sys/class/block/\$b/size 2>/dev/null); ")
                append("if [ -z \"\$sz\" ]; then mn=\$(stat -c '%t:%T' \"\$t\" 2>/dev/null); sz=\$(cat /sys/dev/block/\$mn/size 2>/dev/null); fi; ")
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
