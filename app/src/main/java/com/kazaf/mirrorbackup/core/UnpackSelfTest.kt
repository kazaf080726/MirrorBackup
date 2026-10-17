package com.kazaf.mirrorbackup.core

import android.content.Context
import android.content.Intent
import android.util.Log
import com.rhythmcache.payloaddumper.PayloadDumper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 解包器 JNI 契约自检（诊断用，只由 adb 显式触发，正常界面不会调用）。
 *
 * 用法（设备上已装好本应用、payload.bin 已取出到 cache）：
 * ```
 * adb shell am start -n com.kazaf.mirrorbackup/.MainActivity \
 *   --ez selftest true \
 *   --es parts "vbmeta_system,product" \
 *   --es variants "file|null;file|empty;dir|null;dir|empty"
 * ```
 * 结果写入 /data/data/com.kazaf.mirrorbackup/files/logs/selftest.json
 * （每个变体跑完都会落盘一次，原生库若崩溃也能看到已完成部分）。
 *
 * 变体语法：`输出形态|sourceDir形态`
 *   输出形态  file      → outputPath = <目录>/<分区>.img
 *            dir       → outputPath = <目录>
 *            dirslash  → outputPath = <目录>/
 *   sourceDir  null      → null
 *            empty     → ""
 *            dir       → <目录>
 */
object UnpackSelfTest {

    private const val TAG = "UnpackSelfTest"
    private const val DEFAULT_PARTS = "vbmeta_system,product"
    private const val DEFAULT_VARIANTS = "file|null;file|empty;dir|null;dir|empty;dirslash|null"

    private class PartInfo(val size: Long, val hash: String)

    /** 是否由本次启动的 Intent 触发了自检 */
    fun wanted(intent: Intent?): Boolean =
        intent != null && intent.getBooleanExtra("selftest", false)

    /** 在后台线程跑变体矩阵，结果落盘为 JSON。不阻塞界面。 */
    fun run(ctx: Context, intent: Intent) {
        // 基准模式：验证「线程数真的能加速」
        if (intent.getBooleanExtra("bench", false)) {
            runBench(ctx, intent)
            return
        }
        val payloadPath = intent.getStringExtra("payload")
            ?: File(ctx.cacheDir, "probe_payload.bin").takeIf { it.exists() }?.absolutePath
        val parts = split(intent.getStringExtra("parts") ?: DEFAULT_PARTS, ',')
        val variants = split(intent.getStringExtra("variants") ?: DEFAULT_VARIANTS, ';')
        // 默认写到应用私有目录，避免污染用户可见的「解包工具箱」输出目录
        val baseDir = File(intent.getStringExtra("outbase") ?: File(ctx.cacheDir, "selftest").absolutePath)
        val outFile = File(ctx.filesDir, "logs/selftest.json")

        Thread {
            val root = JSONObject()
            val t0 = System.currentTimeMillis()
            try {
                root.put("startedAt", t0)
                root.put("abi", android.os.Build.SUPPORTED_ABIS.joinToString(","))
                root.put("payload", payloadPath ?: "")
                root.put("payloadSize", payloadPath?.let { File(it).length() } ?: -1L)

                val declared = LinkedHashMap<String, PartInfo>()
                if (payloadPath != null) {
                    runCatching {
                        val json = PayloadDumper.INSTANCE.listLocalPartitions(payloadPath)
                        val arr = JSONObject(json).optJSONArray("partitions") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val n = o.optString("name")
                            if (n.isNotEmpty()) {
                                declared[n] = PartInfo(o.optLong("size_bytes"), o.optString("hash"))
                            }
                        }
                        root.put("listJson", json)
                    }.onFailure {
                        root.put("listError", "${it.javaClass.simpleName}: ${it.message}")
                    }
                }
                root.put("declaredCount", declared.size)

                val results = JSONArray()
                root.put("results", results)
                for (v in variants) {
                    val mode = v.substringBefore('|').trim()
                    val srcMode = v.substringAfter('|', "null").trim()
                    for (p in parts) {
                        val r = runVariant(baseDir, mode, srcMode, p, payloadPath, declared[p])
                        results.put(r)
                        Log.i(TAG, "variant=$v part=$p verdict=${r.optString("verdict")}")
                        // 每跑完一个变体就落盘，原生库崩溃时已完成的结果仍在
                        writeResult(outFile, root, null)
                    }
                }
                root.put("elapsedMs", System.currentTimeMillis() - t0)
                writeResult(outFile, root, null)
            } catch (t: Throwable) {
                Log.e(TAG, "selftest failed", t)
                writeResult(outFile, root, "${t.javaClass.simpleName}: ${t.message}")
            }
        }.apply { name = "unpack-selftest" }.start()
    }

    private fun writeResult(file: File, root: JSONObject, error: String?) {
        try {
            root.put("ok", error == null)
            if (error != null) root.put("error", error)
            file.parentFile?.mkdirs()
            file.writeText(root.toString(2))
        } catch (t: Throwable) {
            Log.e(TAG, "write failed: $t")
        }
    }

    private fun runVariant(
        baseDir: File,
        mode: String,
        srcMode: String,
        part: String,
        payloadPath: String?,
        expected: PartInfo?
    ): JSONObject {
        val out = JSONObject()
        out.put("variant", "$mode|$srcMode")
        out.put("part", part)
        if (payloadPath == null) {
            out.put("verdict", "NO-PAYLOAD")
            return out
        }
        val vdir = File(baseDir, "${mode}_$srcMode")
        runCatching {
            vdir.mkdirs()
            vdir.listFiles()?.forEach { it.deleteRecursively() }
        }
        val outPath = when (mode) {
            "dir" -> vdir.absolutePath
            "dirslash" -> vdir.absolutePath + "/"
            else -> File(vdir, "$part.img").absolutePath
        }
        val sourceDir: String? = when (srcMode) {
            "empty" -> ""
            "dir" -> vdir.absolutePath
            else -> null
        }
        out.put("outPath", outPath)
        out.put("sourceDir", sourceDir ?: "null")

        var calls = 0
        var lastStatus = -1
        var lastPct = -1.0
        var lastCur = -1L
        var lastTotal = -1L
        var warn = ""
        val statuses = sortedSetOf<Int>()
        val started = System.nanoTime()
        var err: String? = null
        try {
            PayloadDumper.INSTANCE.extractLocalPartition(
                payloadPath, part, outPath, sourceDir,
                object : PayloadDumper.ProgressCallback {
                    override fun onProgress(
                        partitionName: String, currentOperation: Long, totalOperations: Long,
                        percentage: Double, status: Int, warningOpIndex: Int, warningMessage: String
                    ): Boolean {
                        calls++
                        lastStatus = status
                        lastPct = percentage
                        lastCur = currentOperation
                        lastTotal = totalOperations
                        statuses.add(status)
                        if (warningMessage.isNotEmpty()) warn = warningMessage
                        return true
                    }
                }
            )
        } catch (t: Throwable) {
            err = "${t.javaClass.name}: ${t.message}"
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        out.put("ok", err == null)
        out.put("error", err ?: "")
        out.put("elapsedMs", ms)
        out.put("calls", calls)
        out.put("lastStatus", lastStatus)
        out.put("lastPct", lastPct)
        out.put("lastCur", lastCur)
        out.put("lastTotal", lastTotal)
        out.put("statuses", JSONArray(statuses.toList()))
        out.put("warning", warn)

        // 变体目录里到底落下了什么
        val outputs = JSONArray()
        var candidate: File? = null
        runCatching {
            vdir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                val row = JSONObject().put("name", f.name).put("size", f.length()).put("dir", f.isDirectory)
                if (f.isFile) {
                    row.put("sha256", sha256(f))
                    if (f.name == "$part.img") candidate = f
                }
                outputs.put(row)
            }
        }
        out.put("outputs", outputs)

        val cand = candidate
        if (expected != null) {
            out.put("expectSize", expected.size)
            out.put("expectHash", expected.hash)
        }
        if (cand != null) {
            out.put("actualSize", cand.length())
            out.put("actualHash", sha256(cand))
            out.put("sizeMatch", expected == null || cand.length() == expected.size)
            out.put("hashMatch", expected == null || sha256(cand).equals(expected.hash, ignoreCase = true))
        }
        out.put(
            "verdict", when {
                err != null -> "ERROR"
                cand == null -> "NO-OUTPUT"
                expected != null && cand.length() != expected.size -> "SIZE-MISMATCH"
                expected != null && !sha256(cand).equals(expected.hash, ignoreCase = true) -> "HASH-MISMATCH"
                else -> "PASS"
            }
        )
        return out
    }

    private fun split(s: String, sep: Char): List<String> =
        s.split(sep).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 并发基准：同一批分区分别用 N 个线程解出，比较实际吞吐，验证「线程数=真加速」。
     * ```
     * adb shell am start -n com.kazaf.mirrorbackup/.MainActivity --ez bench true \
     *   --es payload /data/user/0/com.kazaf.mirrorbackup/cache/probe_payload.bin \
     *   --es parts "system,vendor,odm,my_product" --es threads "1,2,4,8"
     * ```
     */
    fun runBench(ctx: Context, intent: Intent) {
        val payload = intent.getStringExtra("payload")
            ?: File(ctx.cacheDir, "probe_payload.bin").takeIf { it.exists() }?.absolutePath
        val names = split(intent.getStringExtra("parts") ?: "system,vendor,odm,my_product", ',')
        val threadCounts = split(intent.getStringExtra("threads") ?: "1,2,4,8", ',').mapNotNull { it.toIntOrNull() }
        val out = File(ctx.filesDir, "logs/bench.json")
        Thread {
            val root = JSONObject()
            try {
                root.put("payload", payload ?: "")
                root.put("parts", JSONArray(names))
                if (payload == null) {
                    root.put("error", "no payload")
                    writeJson(out, root)
                    return@Thread
                }
                val declared = LinkedHashMap<String, Long>()
                runCatching {
                    val json = PayloadDumper.INSTANCE.listLocalPartitions(payload)
                    val arr = JSONObject(json).optJSONArray("partitions") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        declared[o.optString("name")] = o.optLong("size_bytes")
                    }
                }
                val targets = names.filter { declared.containsKey(it) }
                root.put("targets", JSONArray(targets))
                root.put("targetBytes", targets.sumOf { declared[it] ?: 0L })

                val rows = JSONArray()
                root.put("results", rows)
                for (n in threadCounts) {
                    val dir = File(ctx.cacheDir, "bench_$n")
                    dir.deleteRecursively(); dir.mkdirs()
                    val t0 = System.nanoTime()
                    val queue = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, Long>>()
                    targets.forEach { queue.add(it to (declared[it] ?: 0L)) }
                    val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
                    val worker = Runnable {
                        while (true) {
                            val p = queue.poll() ?: break
                            try {
                                PayloadDumper.INSTANCE.extractLocalPartition(
                                    payload, p.first, File(dir, "${p.first}.img").absolutePath, null, null
                                )
                            } catch (t: Throwable) {
                                errors.add("${p.first}: ${t.javaClass.simpleName}: ${t.message}")
                            }
                        }
                    }
                    val threads = (1..n).map { Thread(worker, "bench-$it") }
                    threads.forEach { it.start() }
                    threads.forEach { it.join() }
                    val secs = (System.nanoTime() - t0) / 1e9
                    val bytes = dir.listFiles()?.sumOf { it.length() } ?: 0L
                    dir.deleteRecursively()
                    val row = JSONObject()
                        .put("threads", n)
                        .put("seconds", secs)
                        .put("bytes", bytes)
                        .put("mbps", if (secs > 0) bytes / secs / 1e6 else 0.0)
                        .put("errors", JSONArray(errors.toList()))
                    rows.put(row)
                    Log.i(TAG, "bench threads=$n -> ${"%.1f".format(row.optDouble("mbps"))} MB/s in ${"%.2f".format(secs)}s")
                    writeJson(out, root)
                }
                root.put("ok", true)
            } catch (t: Throwable) {
                Log.e(TAG, "bench failed", t)
                runCatching { root.put("error", "${t.javaClass.simpleName}: ${t.message}") }
            }
            writeJson(out, root)
        }.apply { name = "unpack-bench" }.start()
    }

    private fun writeJson(file: File, root: JSONObject) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(root.toString(2))
        } catch (t: Throwable) {
            Log.e(TAG, "write bench failed: $t")
        }
    }

    private fun sha256(file: File): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { f("%02x", it) }
    } catch (t: Throwable) {
        "ERR:${t.message}"
    }
}
