package com.kazaf.mirrorbackup.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 备份/恢复记录表：记录每次任务提取了多少个镜像、提取了什么镜像、
 * 模式、线程、格式、是否打包、导出位置等，持久化于应用私有目录。
 */
data class BackupRecord(
    val time: String,
    val type: String,          // "backup" | "restore"
    val mode: String,
    val threadMode: String,
    val format: String,
    val packaged: Boolean,
    val location: String,
    val imageCount: Int,
    val images: List<String>,
    val totalBytes: Long,
    val durationSec: Long
)

object RecordsStore {

    private const val MAX = 200

    private fun file(context: Context) = File(context.filesDir, "backup_records.json")

    fun load(context: Context): List<BackupRecord> {
        return try {
            val f = file(context)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val imgs = o.optJSONArray("images") ?: JSONArray()
                BackupRecord(
                    time = o.optString("time"),
                    type = o.optString("type"),
                    mode = o.optString("mode"),
                    threadMode = o.optString("threadMode"),
                    format = o.optString("format"),
                    packaged = o.optBoolean("packaged"),
                    location = o.optString("location"),
                    imageCount = o.optInt("imageCount"),
                    images = (0 until imgs.length()).map { imgs.getString(it) },
                    totalBytes = o.optLong("totalBytes"),
                    durationSec = o.optLong("durationSec")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, r: BackupRecord) {
        try {
            val list = (listOf(r) + load(context)).take(MAX)
            val arr = JSONArray()
            for (b in list) {
                arr.put(
                    JSONObject()
                        .put("time", b.time)
                        .put("type", b.type)
                        .put("mode", b.mode)
                        .put("threadMode", b.threadMode)
                        .put("format", b.format)
                        .put("packaged", b.packaged)
                        .put("location", b.location)
                        .put("imageCount", b.imageCount)
                        .put("images", JSONArray(b.images))
                        .put("totalBytes", b.totalBytes)
                        .put("durationSec", b.durationSec)
                )
            }
            file(context).writeText(arr.toString())
        } catch (_: Exception) {
        }
    }
}
