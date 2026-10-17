package com.kazaf.mirrorbackup.harness

import com.kazaf.mirrorbackup.core.BackupManifest
import com.kazaf.mirrorbackup.core.LibSparse
import com.kazaf.mirrorbackup.core.ManifestPartition
import com.kazaf.mirrorbackup.core.pure.ImageCheck
import com.kazaf.mirrorbackup.core.pure.RestoreVerify
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/* ────────────────────────── 假手机环境 ────────────────────────── */

data class FakeDevice(
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val sdkInt: String,
    val buildFingerprint: String,
    val sn: String,
    val cpuId: String
) {
    /** 与 APP 的 DeviceInfo.fingerprint 同一算法：SHA-256(SN + CPU ID) */
    val fingerprint: String get() = sha256("SN=$sn|CPU=$cpuId".toByteArray())
}

/** 一个假分区：raw 内容 + 是否以 sparse 形式备份 */
data class SimPart(val name: String, val raw: ByteArray, val sparse: Boolean) {
    /** 压缩包里实际存的东西（sparse 时是 .simg） */
    val stored: ByteArray by lazy {
        if (sparse) {
            val tmp = File.createTempFile("simg_$name", ".img")
            LibSparse.encode(ByteArrayInputStream(raw), raw.size.toLong(), tmp) {}
            val bytes = tmp.readBytes()
            tmp.delete()
            bytes
        } else raw
    }
    /** manifest 里记录的哈希：备份时算的是「存进包里的字节」的 SHA-256 */
    val storedSha: String by lazy { sha256(stored) }
    val rawSha: String by lazy { sha256(raw) }
}

/* ────────────────────────── 工具 ────────────────────────── */

fun sha256(b: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

fun sha256(f: File): String = sha256(f.readBytes())

fun crc32(b: ByteArray): Long = CRC32().apply { update(b) }.value

/** 输出同时收集到内存，最后写一份 UTF-8 报告（控制台是 GBK 时中文会乱码） */
val OUT = mutableListOf<String>()

fun out(s: String) {
    println(s)
    OUT += s
}

/** 测试用例统计 */
class Report {    var passed = 0
        private set
    var failed = 0
        private set
    val failures = mutableListOf<String>()

    fun check(name: String, ok: Boolean, detail: String = "") {
        val line = if (ok) "  ✅ $name ${if (detail.isEmpty()) "" else "— $detail"}"
        else "  ❌ $name ${if (detail.isEmpty()) "" else "— $detail"}"
        out(line)
        if (ok) passed++ else {
            failed++
            failures += "$name ${if (detail.isEmpty()) "" else "($detail)"}"
        }
    }
}

/** 构造一个「备份包」ZIP（manifest.json + images/xxx.img，STORED 方式与 APP 打包一致） */
fun buildZip(
    out: File,
    manifest: BackupManifest,
    parts: List<SimPart>,
    omit: Set<String> = emptySet(),
    tamper: (String, ByteArray) -> ByteArray = { _, b -> b }
) {
    ZipOutputStream(out.outputStream().buffered()).use { zos ->
        fun put(name: String, data: ByteArray) {
            val e = ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = data.size.toLong()
                compressedSize = data.size.toLong()
                crc = crc32(data)
            }
            zos.putNextEntry(e)
            zos.write(data)
            zos.closeEntry()
        }
        put("manifest.json", manifest.toJson().toByteArray(Charsets.UTF_8))
        for (p in parts) {
            if (p.name in omit) continue
            put("images/${p.name}.img", tamper(p.name, p.stored))
        }
    }
}

fun manifestOf(dev: FakeDevice, parts: List<SimPart>): BackupManifest = BackupManifest(
    manifestVersion = 1,
    appName = "镜像备份",
    appVersion = "PC-HARNESS",
    mode = "full",
    createdAt = "2026-10-18T00:00:00+08:00",
    format = if (parts.any { it.sparse }) "sparse" else "raw",
    model = dev.model,
    manufacturer = dev.manufacturer,
    androidVersion = dev.androidVersion,
    sdkInt = dev.sdkInt,
    buildFingerprint = dev.buildFingerprint,
    sn = dev.sn,
    cpuId = dev.cpuId,
    fingerprint = dev.fingerprint,
    partitions = parts.map {
        ManifestPartition(
            name = it.name,
            path = "/dev/block/by-name/${it.name}",
            sizeBytes = it.raw.size.toLong(),
            sha256 = it.storedSha,
            format = if (it.sparse) "sparse" else "raw",
            backedAt = "2026-10-18T00:00:00+08:00"
        )
    }
)

/**
 * 模拟 APP 恢复前的第①层校验通道：单遍扫描 ZIP，逐条校验并汇总。
 * （与 RestoreEngine.hashVerifyPass 同样的判定：sha + 展开大小 + ZIP CRC32）
 */
fun verifyZip(zipFile: File, manifest: BackupManifest): List<ImageCheck> {
    val map = manifest.partitions.associateBy { it.name }
    val rows = LinkedHashMap<String, ImageCheck>()
    ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
        while (true) {
            val e = zip.nextEntry ?: break
            if (e.isDirectory || !e.name.startsWith("images/")) {
                zip.closeEntry(); continue
            }
            val name = e.name.removePrefix("images/").substringBeforeLast('.')
            val part = map[name]
            if (part == null) {
                zip.closeEntry(); continue
            }
            rows[name] = RestoreVerify.verifyEntry(
                name = name,
                format = part.format,
                declaredSize = part.sizeBytes,
                declaredSha256 = part.sha256,
                crcStored = e.crc,
                input = zip
            )
            zip.closeEntry()
        }
    }
    // manifest 声明了但包里没有 → 与 APP 一致判为失败
    for (p in manifest.partitions) {
        if (!rows.containsKey(p.name)) {
            rows[p.name] = ImageCheck(
                name = p.name, format = p.format, declaredSize = p.sizeBytes,
                declaredSha256 = p.sha256, actualSha256 = "", expandedBytes = 0,
                crcStored = -1, crcActual = 0, structureError = "压缩包缺少该镜像"
            )
        }
    }
    return manifest.partitions.mapNotNull { rows[it.name] }
}

/**
 * 模拟恢复写入：把镜像流写进「假分区文件」（APP 里是 dd 写入分区节点）。
 * 返回 name -> 写入结果
 */
fun simulateRestore(
    zipFile: File,
    manifest: BackupManifest,
    targetDir: File,
    targetSizes: Map<String, Long>
): Map<String, RestoreOutcome> {
    val map = manifest.partitions.associateBy { it.name }
    val out = LinkedHashMap<String, RestoreOutcome>()
    ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
        while (true) {
            val e = zip.nextEntry ?: break
            if (e.isDirectory || !e.name.startsWith("images/")) {
                zip.closeEntry(); continue
            }
            val name = e.name.removePrefix("images/").substringBeforeLast('.')
            val part = map[name]
            if (part == null) {
                zip.closeEntry(); continue
            }
            // 第②层：空间门槛（与 APP 相同判断）
            val targetSize = targetSizes[name]
            val block = RestoreVerify.targetBlockReason(part.sizeBytes, targetSize != null, targetSize ?: 0L)
            if (block != null) {
                out[name] = RestoreOutcome(false, 0, null, block)
                zip.closeEntry(); continue
            }
            val target = File(targetDir, "$name.img")
            var written = 0L
            try {
                FileOutputStream(target).use { fos ->
                    written = RestoreVerify.writeEntry(zip, fos, part.format)
                }
            } catch (ex: Exception) {
                out[name] = RestoreOutcome(false, written, null, "写入异常: ${ex.message}")
                zip.closeEntry(); continue
            }
            zip.closeEntry()
            val hash = sha256(target)
            val sizeOk = written == part.sizeBytes
            out[name] = RestoreOutcome(sizeOk, written, hash, if (sizeOk) null else "写入字节 $written ≠ manifest ${part.sizeBytes}")
        }
    }
    return out
}

data class RestoreOutcome(
    val ok: Boolean,
    val written: Long,
    val writtenSha: String?,
    val error: String?
)

/* ────────────────────────── 测试主流程 ────────────────────────── */

fun main(args: Array<String>) {
    val work = File(System.getProperty("java.io.tmpdir"), "mirror_restore_harness_${System.currentTimeMillis()}")
    work.mkdirs()
    val rep = Report()

    out("==============================================================")
    out(" 恢复工作台 · 电脑模拟测试（假手机环境，不接触真机）")
    out(" 工作目录: ${work.absolutePath}")
    out("==============================================================")

    // ── 假设备：与真机同型号，但 SN/CPU 都是假的 ──
    val dev = FakeDevice(
        model = "PLC110", manufacturer = "OnePlus", androidVersion = "16", sdkInt = "36",
        buildFingerprint = "OnePlus/PLC110/SIM:16/SIM/eng.test:user/release-keys",
        sn = "SIM-SN-0001", cpuId = "SIM-CPU-AAAA"
    )
    out("\n[假手机] ${dev.model} / Android ${dev.androidVersion} / SN=${dev.sn} / 指纹=${dev.fingerprint.take(16)}…")

    // ── 造数据：两个 raw 分区 + 一个 sparse 分区（含大量零，能被压成稀疏）──
    val rnd = Random(20261018)
    val bootRaw = ByteArray(256 * 1024).also { rnd.nextBytes(it) }
    val miscRaw = ByteArray(4096).also { rnd.nextBytes(it) }
    val vendorRaw = ByteArray(512 * 1024).also { arr ->
        // 头尾各塞一段随机数据，中间留大量 0 → sparse 才有意义
        val head = ByteArray(64 * 1024).also { rnd.nextBytes(it) }
        val tail = ByteArray(32 * 1024).also { rnd.nextBytes(it) }
        System.arraycopy(head, 0, arr, 0, head.size)
        System.arraycopy(tail, 0, arr, arr.size - tail.size, tail.size)
    }
    val parts = listOf(
        SimPart("boot", bootRaw, sparse = false),
        SimPart("misc", miscRaw, sparse = false),
        SimPart("vendor_boot", vendorRaw, sparse = true)
    )
    parts.forEach { p ->
        out("  · ${p.name}: raw=${p.raw.size}B 存储=${p.stored.size}B (${p.stored.size * 100 / p.raw.size}%) format=${if (p.sparse) "sparse" else "raw"}")
    }

    // ── 1. manifest JSON 往返（真实 BackupManifest.toJson/fromJson）──
    out("\n── 1. manifest 清单 JSON 往返 ──")
    val manifest = manifestOf(dev, parts)
    val json = manifest.toJson()
    val parsed = BackupManifest.fromJson(json)
    rep.check("manifest 能写能读", parsed.partitions.size == parts.size, "${parsed.partitions.size} 个分区")
    rep.check("设备字段一致", parsed.model == dev.model && parsed.sn == dev.sn && parsed.fingerprint == dev.fingerprint)
    rep.check("分区名称/大小/哈希一致", parsed.partitions.all { p ->
        val src = parts.first { it.name == p.name }
        p.sizeBytes == src.raw.size.toLong() && p.sha256.equals(src.storedSha, true) &&
            p.format == (if (src.sparse) "sparse" else "raw")
    })
    rep.check("manifest 未出现明文手机号/IMEI 类字段", !json.contains("imei", true) && !json.contains("phone", true))

    // ── 2. 正常备份包：校验通道 ──
    out("\n── 2. 正常备份包的第①层校验（SHA-256 + 展开大小 + ZIP CRC32）──")
    val goodZip = File(work, "backup_good.zip")
    buildZip(goodZip, manifest, parts)
    val goodChecks = verifyZip(goodZip, manifest)
    goodChecks.forEach { println("     ${it.summary()}") }
    rep.check("全部镜像校验通过", goodChecks.all { it.ok }, "${goodChecks.count { it.ok }}/${goodChecks.size}")
    rep.check("raw 镜像 SHA-256 一致", goodChecks.filter { it.format == "raw" }.all { it.shaOk })
    rep.check("sparse 镜像 SHA-256 一致", goodChecks.filter { it.format == "sparse" }.all { it.shaOk })
    rep.check("sparse 展开大小与 manifest 一致", goodChecks.filter { it.format == "sparse" }.all { it.sizeOk && it.expandedBytes > 0 })
    rep.check("ZIP CRC32 全部一致", goodChecks.all { it.crcOk })

    // ── 3. 模拟恢复写入：写入「假分区」后回读哈希 ──
    out("\n── 3. 模拟恢复写入（镜像流 → 假分区文件 → 回读哈希）──")
    val targetDir = File(work, "fake_partitions").apply { mkdirs() }
    val targetSizes = mapOf("boot" to 256L * 1024, "misc" to 4096L, "vendor_boot" to 512L * 1024)
    val restored = simulateRestore(goodZip, manifest, targetDir, targetSizes)
    var restoreAllOk = true
    for (p in parts) {
        val r = restored[p.name]
        val expectedHash = p.rawSha
        val ok = r != null && r.ok && r.writtenSha.equals(expectedHash, true)
        restoreAllOk = restoreAllOk && ok
        out("     ${if (ok) "✅" else "❌"} ${p.name}: 写入 ${r?.written ?: 0}B / 期望 ${p.raw.size}B  写入后SHA=${r?.writtenSha?.take(16) ?: "-"}…  期望SHA=${expectedHash.take(16)}…")
    }
    rep.check("三个分区写入后内容哈希全部与原始镜像一致", restoreAllOk)
    rep.check("sparse 分区恢复成原始 raw 大小", restored["vendor_boot"]?.written == vendorRaw.size.toLong())
    rep.check("假分区文件字节级一致", parts.all { p ->
        val f = File(targetDir, "${p.name}.img")
        f.exists() && f.readBytes().contentEquals(p.raw)
    })

    // ── 4. 反向用例：篡改/损坏的包必须被拦下 ──
    out("\n── 4. 反向用例（坏包必须被识别）──")

    // 4.1 包里镜像被改 1 个字节
    val tamperedZip = File(work, "backup_bitflip.zip")
    buildZip(tamperedZip, manifest, parts, tamper = { name, data ->
        if (name == "boot") data.copyOf().also { it[1000] = (it[1000].toInt() xor 0xFF).toByte() } else data
    })
    val bitflip = verifyZip(tamperedZip, manifest).first { it.name == "boot" }
    rep.check("镜像被篡改 1 字节 → SHA-256 校验失败", !bitflip.shaOk, "实际=${bitflip.actualSha256.take(16)}…")

    // 4.2 manifest 里哈希写错
    val wrongHashManifest = manifest.copy(
        partitions = manifest.partitions.map {
            if (it.name == "misc") it.copy(sha256 = "0".repeat(64)) else it
        }
    )
    val wrongHashZip = File(work, "backup_wronghash.zip")
    buildZip(wrongHashZip, wrongHashManifest, parts)
    val wrongHash = verifyZip(wrongHashZip, wrongHashManifest).first { it.name == "misc" }
    rep.check("manifest 哈希错误 → 识别为不一致", !wrongHash.shaOk)

    // 4.3 manifest 里大小写错
    val wrongSizeManifest = manifest.copy(
        partitions = manifest.partitions.map {
            if (it.name == "boot") it.copy(sizeBytes = it.sizeBytes + 4096) else it
        }
    )
    val wrongSizeZip = File(work, "backup_wrongsize.zip")
    buildZip(wrongSizeZip, wrongSizeManifest, parts)
    val wrongSize = verifyZip(wrongSizeZip, wrongSizeManifest).first { it.name == "boot" }
    rep.check("manifest 大小不符 → 识别为大小不一致", !wrongSize.sizeOk, "读入=${wrongSize.expandedBytes}")

    // 4.4 镜像被截断
    val truncatedZip = File(work, "backup_truncated.zip")
    buildZip(truncatedZip, manifest, parts, tamper = { name, data ->
        if (name == "boot") data.copyOf(data.size - 8192) else data
    })
    val truncated = verifyZip(truncatedZip, manifest).first { it.name == "boot" }
    rep.check("镜像被截断 → 大小与哈希都失败", !truncated.sizeOk && !truncated.shaOk)

    // 4.5 CRC 记录对不上（模拟 ZIP 头损坏）
    val badCrcCheck = RestoreVerify.verifyEntry(
        name = "boot", format = "raw", declaredSize = bootRaw.size.toLong(),
        declaredSha256 = sha256(bootRaw), crcStored = 0x12345678L,
        input = ByteArrayInputStream(bootRaw)
    )
    rep.check("ZIP CRC32 记录不符 → 识别为 CRC 失败", !badCrcCheck.crcOk && badCrcCheck.shaOk)

    // 4.6 sparse 结构损坏
    val brokenSparse = parts.first { it.sparse }.stored.copyOf().also { it[0] = 0 }
    val brokenSparseCheck = RestoreVerify.verifyEntry(
        name = "vendor_boot", format = "sparse",
        declaredSize = vendorRaw.size.toLong(),
        declaredSha256 = sha256(brokenSparse), crcStored = -1,
        input = ByteArrayInputStream(brokenSparse)
    )
    rep.check("sparse 头损坏 → 识别为结构损坏", brokenSparseCheck.structureError != null, brokenSparseCheck.structureError ?: "")

    // 4.7 包里少一个镜像
    val missingZip = File(work, "backup_missing.zip")
    buildZip(missingZip, manifest, parts, omit = setOf("misc"))
    val missing = verifyZip(missingZip, manifest).first { it.name == "misc" }
    rep.check("manifest 有、包里没有 → 判为失败", !missing.ok, missing.structureError ?: "")

    // ── 5. 第②层：空间 / 目标分区门槛 ──
    out("\n── 5. 第②层安全门槛 ──")
    val tooSmall = RestoreVerify.targetBlockReason(10L * 1024 * 1024, targetExists = true, targetSize = 1024L * 1024)
    rep.check("镜像大于目标分区 → 拦下", tooSmall != null, tooSmall ?: "")
    val noTarget = RestoreVerify.targetBlockReason(1024, targetExists = false, targetSize = 0)
    rep.check("目标分区不存在 → 拦下", noTarget != null, noTarget ?: "")
    val fits = RestoreVerify.targetBlockReason(1024, targetExists = true, targetSize = 4096)
    rep.check("空间足够 → 放行", fits == null)
    // 用小目标模拟真实写入被拦
    val blockedRestore = simulateRestore(goodZip, manifest, File(work, "blocked").apply { mkdirs() },
        mapOf("boot" to 1024L, "misc" to 4096L, "vendor_boot" to 512L * 1024))
    rep.check("目标分区过小 → 恢复被拦截且不写入", blockedRestore["boot"]?.ok == false && blockedRestore["boot"]?.written == 0L,
        blockedRestore["boot"]?.error ?: "")

    // ── 6. 设备指纹校验 ──
    out("\n── 6. 设备指纹校验 ──")
    val sameFp = dev.fingerprint == manifest.fingerprint
    val otherDev = dev.copy(sn = "SIM-SN-9999")
    val diffFp = otherDev.fingerprint == manifest.fingerprint
    rep.check("同一设备（SN+CPU 相同）→ 指纹一致", sameFp)
    rep.check("换一台设备（SN 不同）→ 指纹不一致（APP 会锁恢复按钮）", !diffFp)

    // ── 7. 坏包时「禁止写入」的判定 ──
    out("\n── 7. 坏包禁止写入的判定（与 APP 相同）──")
    val badChecks = verifyZip(tamperedZip, manifest)
    val wouldBlock = badChecks.any { it.shaOk != true }
    rep.check("存在校验失败镜像 → 恢复前即阻断写入", wouldBlock)

    // ── 汇总 ──
    out("\n==============================================================")
    out(" 结果: 通过 ${rep.passed} / 失败 ${rep.failed}")
    if (rep.failures.isNotEmpty()) {
        out(" 失败项:")
        rep.failures.forEach { out("   - $it") }
    }
    out("==============================================================")

    // 写一份 UTF-8 报告（控制台码页是 GBK 时中文会乱码，看这个文件更清楚）
    val reportFile = File(args.firstOrNull { it.startsWith("--out=") }?.removePrefix("--out=")
        ?: "build/reports/restore-harness-report.md")
    reportFile.parentFile?.mkdirs()
    reportFile.writeText(
        "# 恢复工作台 · 电脑模拟测试报告\n\n" +
            "> 假手机环境（假设备/假分区/假 ZIP），跑的是 APP 里同一份校验与写入代码。\n" +
            "> 生成时间: ${java.time.LocalDateTime.now()}\n" +
            "> 结果: **通过 ${rep.passed} / 失败 ${rep.failed}**\n\n```\n" +
            OUT.joinToString("\n") + "\n```\n",
        Charsets.UTF_8
    )
    println("REPORT: ${reportFile.absolutePath}")
    println("RESULT: ${rep.passed} passed / ${rep.failed} failed")

    // 清理临时目录（保留可查：--keep 参数）
    if (!args.contains("--keep")) work.deleteRecursively()

    if (rep.failed > 0) kotlin.system.exitProcess(1)
}
