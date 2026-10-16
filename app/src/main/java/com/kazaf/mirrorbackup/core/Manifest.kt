package com.kazaf.mirrorbackup.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * manifest.json 清单（每个备份 ZIP 内自动生成）。
 * 包含：设备指纹（SN + CPU ID，不采集 IMEI）、分区名、/dev/block/by-name 原始路径、
 * 分区物理字节大小、SHA256、备份时间戳、设备型号、Android 版本、备份模式、格式标记。
 */
data class ManifestPartition(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
    val format: String,   // "raw" | "sparse"
    val backedAt: String
)

data class BackupManifest(
    val manifestVersion: Int,
    val appName: String,
    val appVersion: String,
    val mode: String,           // "full" | "private"
    val createdAt: String,
    val format: String,         // "raw" | "sparse"
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val sdkInt: String,
    val buildFingerprint: String,
    val sn: String,
    val cpuId: String,
    val fingerprint: String,
    val partitions: List<ManifestPartition>
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("manifestVersion", manifestVersion)
        root.put("app", JSONObject()
            .put("name", appName)
            .put("version", appVersion))
        root.put("device", JSONObject()
            .put("model", model)
            .put("manufacturer", manufacturer)
            .put("androidVersion", androidVersion)
            .put("sdkInt", sdkInt)
            .put("buildFingerprint", buildFingerprint))
        root.put("identity", JSONObject()
            .put("sn", sn)
            .put("cpuId", cpuId)
            .put("fingerprint", fingerprint))
        root.put("backup", JSONObject()
            .put("mode", mode)
            .put("createdAt", createdAt)
            .put("format", format))
        val arr = JSONArray()
        for (p in partitions) {
            arr.put(JSONObject()
                .put("name", p.name)
                .put("path", p.path)
                .put("sizeBytes", p.sizeBytes)
                .put("sha256", p.sha256)
                .put("format", p.format)
                .put("backedAt", p.backedAt))
        }
        root.put("partitions", arr)
        return root.toString(2)
    }

    companion object {
        fun fromJson(text: String): BackupManifest {
            val root = JSONObject(text)
            val app = root.optJSONObject("app") ?: JSONObject()
            val dev = root.optJSONObject("device") ?: JSONObject()
            val idn = root.optJSONObject("identity") ?: JSONObject()
            val bak = root.optJSONObject("backup") ?: JSONObject()
            val parts = mutableListOf<ManifestPartition>()
            val arr = root.optJSONArray("partitions") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                parts += ManifestPartition(
                    name = o.optString("name"),
                    path = o.optString("path"),
                    sizeBytes = o.optLong("sizeBytes"),
                    sha256 = o.optString("sha256"),
                    format = o.optString("format"),
                    backedAt = o.optString("backedAt")
                )
            }
            return BackupManifest(
                manifestVersion = root.optInt("manifestVersion"),
                appName = app.optString("name"),
                appVersion = app.optString("version"),
                mode = bak.optString("mode"),
                createdAt = bak.optString("createdAt"),
                format = bak.optString("format"),
                model = dev.optString("model"),
                manufacturer = dev.optString("manufacturer"),
                androidVersion = dev.optString("androidVersion"),
                sdkInt = dev.optString("sdkInt"),
                buildFingerprint = dev.optString("buildFingerprint"),
                sn = idn.optString("sn"),
                cpuId = idn.optString("cpuId"),
                fingerprint = idn.optString("fingerprint"),
                partitions = parts
            )
        }
    }
}
