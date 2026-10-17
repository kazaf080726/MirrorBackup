package com.kazaf.mirrorbackup.core.pure

/**
 * 单分区刷入（.img）的纯逻辑：分区匹配、前置校验、指令拼装与自检、写入后校验。
 * 零 Android 依赖 —— APP 与电脑端模拟测试（restore-harness）跑的是同一份代码。
 */

/** 扫描到的可写分区（来自 /dev/block/by-name 等位置） */
data class FlashPartition(val name: String, val path: String, val sizeBytes: Long)

/** 待刷镜像信息（raw；若是稀疏镜像，调用方先展开再传进来） */
data class ImageMeta(
    val fileName: String,
    val bytes: Long,
    val sha256: String,
    val sparse: Boolean = false
) {
    /** boot.img → boot；boot_a.img → boot_a（大小写不敏感） */
    val base: String
        get() {
            val n = fileName.replace('\\', '/').substringAfterLast('/')
            return if (n.lowercase().endsWith(".img")) n.dropLast(4) else n.substringBeforeLast('.')
        }
}

enum class FlashMatchKind { SINGLE, NEED_SLOT, NONE }

data class FlashMatch(
    val kind: FlashMatchKind,
    val candidates: List<FlashPartition> = emptyList(),
    val note: String = ""
) {
    val single: FlashPartition? get() = if (kind == FlashMatchKind.SINGLE) candidates.firstOrNull() else null
}

/** 一项前置校验 */
data class FlashCheck(
    val label: String,
    val ok: Boolean,
    /** false = 仅风险提示，不阻止刷入 */
    val fatal: Boolean = true,
    val detail: String = ""
)

data class Preflight(val checks: List<FlashCheck>) {
    val blocking: Boolean get() = checks.any { !it.ok && it.fatal }
    val warnings: List<FlashCheck> get() = checks.filter { !it.ok && !it.fatal }
    val passed: Int get() = checks.count { it.ok }
}

/** 指令自检 / 写入后校验的结果 */
data class CommandCheck(val ok: Boolean, val reasons: List<String> = emptyList()) {
    fun summary(): String = if (ok) "通过" else reasons.joinToString("；")
}

object FlashPlanner {

    private val NAME_RE = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val SLOT_RE = Regex("^(.+)_([ab])$")
    private val SAFE_PATH_RE = Regex("^/dev/block/[A-Za-z0-9_./-]+$")

    fun validPartitionName(name: String): Boolean = NAME_RE.matches(name)

    /** 只允许 /dev/block/ 下的设备节点，且不含 ..（防路径注入） */
    fun safeDevicePath(path: String): Boolean = SAFE_PATH_RE.matches(path) && !path.contains("..")

    /**
     * 依据镜像文件名在分区表里找目标：
     * - 文件名与分区名完全一致 → SINGLE（不用问）
     * - 文件名是基名、分区表里有 _a 和 _b → NEED_SLOT（问用户刷哪个槽）
     * - 只找到 _a 或 _b 其中一个 → SINGLE（按找到的那个）
     * - boot_a.img 但分区表里只有 boot → SINGLE（单分区机型）
     * - 都找不到 → NONE（UI 让用户手动选）
     */
    fun match(image: ImageMeta, partitions: List<FlashPartition>): FlashMatch {
        val base = image.base
        val byName = partitions.associateBy { it.name }
        byName[base]?.let {
            return FlashMatch(FlashMatchKind.SINGLE, listOf(it), "文件名与分区名一致：${it.name}")
        }
        val a = byName["${base}_a"]
        val b = byName["${base}_b"]
        if (a != null && b != null) {
            return FlashMatch(FlashMatchKind.NEED_SLOT, listOf(a, b), "检测到 A/B 双分区，请选择槽位")
        }
        (a ?: b)?.let {
            return FlashMatch(FlashMatchKind.SINGLE, listOf(it), "只找到 ${it.name}，按单分区处理")
        }
        SLOT_RE.matchEntire(base)?.let { m ->
            byName[m.groupValues[1]]?.let {
                return FlashMatch(FlashMatchKind.SINGLE, listOf(it), "已按单分区 ${it.name} 处理")
            }
        }
        return FlashMatch(FlashMatchKind.NONE, emptyList(), "未找到与「$base」匹配的分区")
    }

    /**
     * 前置校验（全部只读，不碰设备）：
     * 镜像有效性、命名与路径合法性、目标节点存在/可写、容量、黑名单、引导分区风险提示。
     */
    fun preflight(
        image: ImageMeta,
        target: FlashPartition,
        blacklist: Set<String> = emptySet(),
        nodeExists: Boolean = true,
        nodeWritable: Boolean = true,
        risky: Boolean = false,
        isBlockDevice: Boolean = true
    ): Preflight {
        val checks = mutableListOf<FlashCheck>()
        checks += FlashCheck("镜像文件非空", image.bytes > 0, detail = "${image.bytes} 字节")
        checks += FlashCheck("镜像 SHA-256 已计算", image.sha256.length == 64, detail = image.sha256.take(16) + "…")
        checks += FlashCheck("分区名合法", validPartitionName(target.name), detail = target.name)
        checks += FlashCheck("目标路径合法（/dev/block/…）", safeDevicePath(target.path), detail = target.path)
        checks += FlashCheck("目标分区存在", nodeExists, detail = target.path)
        checks += FlashCheck("目标为块设备", isBlockDevice, detail = target.path)
        checks += FlashCheck("节点可写（root）", nodeWritable)
        checks += FlashCheck(
            "镜像不超过分区大小",
            target.sizeBytes <= 0 || image.bytes <= target.sizeBytes,
            detail = "${image.bytes} / ${target.sizeBytes}"
        )
        checks += FlashCheck("分区不在用户黑名单", target.name !in blacklist, detail = target.name)
        if (image.sparse) {
            checks += FlashCheck("稀疏镜像已展开为 raw", true, detail = "simg → raw")
        }
        if (risky) {
            checks += FlashCheck(
                "引导相关分区（风险提示）", false, fatal = false,
                detail = "刷错会导致无法开机，请确认机型与槽位"
            )
        }
        return Preflight(checks)
    }

    /** 写入指令（镜像从 stdin 进，of= 是目标分区） */
    fun writeCommand(target: FlashPartition): String? =
        if (!safeDevicePath(target.path)) null
        else "dd of='${target.path}' bs=1048576 conv=fsync"

    /** 回读指令：精确读取前 bytes 字节并算 SHA-256（head -c 不会因非 1MB 对齐而多读） */
    fun readBackCommand(target: FlashPartition, bytes: Long): String? =
        if (!safeDevicePath(target.path) || bytes <= 0) null
        else "head -c $bytes '${target.path}' 2>/dev/null | sha256sum"

    /**
     * 「最后校验一下有没有发错指令」：执行前对拼好的指令做静态自检。
     *  - 只允许出现一个设备路径，且必须等于选定的分区路径
     *  - 必须是 of= 写指令，不能混入 if=（避免把读指令当写指令）
     *  - 必须带 conv=fsync
     *  - 指令里必须出现选定分区名
     */
    fun verifyWriteCommand(cmd: String, target: FlashPartition): CommandCheck {
        val reasons = mutableListOf<String>()
        val paths = Regex("/dev/block/[A-Za-z0-9_./-]+").findAll(cmd).map { it.value }.toList()
        when {
            paths.isEmpty() -> reasons += "指令里没有设备路径"
            paths.size > 1 -> reasons += "指令里出现多个设备路径：${paths.joinToString()}"
            paths[0] != target.path -> reasons += "指令目标 ${paths[0]} 与选定分区 ${target.path} 不一致"
        }
        if (!Regex("(^|\\s)of=").containsMatchIn(cmd)) reasons += "指令缺少 of= 写入目标"
        if (Regex("(^|\\s)if=").containsMatchIn(cmd)) reasons += "写指令里出现了 if=（读指令特征）"
        if (!cmd.contains("conv=fsync")) reasons += "缺少 conv=fsync，写入可能未落盘"
        if (!cmd.contains(target.name)) reasons += "指令里找不到分区名 ${target.name}"
        return CommandCheck(reasons.isEmpty(), reasons)
    }

    /** 写入后哈希校验：回读的 SHA-256 与镜像一致、字节数一致 */
    fun verifyWritten(image: ImageMeta, readBackBytes: Long, readBackSha256: String): CommandCheck {
        val reasons = mutableListOf<String>()
        if (readBackSha256.length != 64) reasons += "回读 SHA-256 无效（${readBackSha256.take(24)}）"
        else if (!readBackSha256.equals(image.sha256, ignoreCase = true)) {
            reasons += "回读 SHA-256 ${readBackSha256.take(16)}… ≠ 镜像 ${image.sha256.take(16)}…"
        }
        if (readBackBytes != image.bytes) reasons += "回读字节数 $readBackBytes ≠ 镜像 ${image.bytes}"
        return CommandCheck(reasons.isEmpty(), reasons)
    }
}
