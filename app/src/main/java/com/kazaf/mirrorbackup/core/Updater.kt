package com.kazaf.mirrorbackup.core

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 应用内检查更新 / 升级（GitHub Release 源）。
 * 仅在用户主动点击"检查更新"时联网；其余功能保持离线。
 *
 * 更新策略（用户规定）：
 * - 仅 App 更新 → 只下载并安装 APK
 * - 模块更新 → 先安装模块 zip，再同步安装 APK（"只装一次"）
 */
object Updater {

    const val REPO = "kazaf080726/MirrorBackup"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    data class ReleaseInfo(
        val tag: String,
        val name: String,
        val notes: String,
        val appAssetUrl: String?,
        val appAssetName: String?,
        val moduleAssetUrl: String?,
        val moduleAssetName: String?,
        val moduleVersionCode: Int
    )

    data class UpdateState(
        val appUpdateAvailable: Boolean = false,
        val moduleUpdateAvailable: Boolean = false,
        val latest: ReleaseInfo? = null,
        val message: String = ""
    )

    suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(API).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "MirrorBackup-Android")
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            if (conn.responseCode != 200) return@withContext null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val root = JSONObject(text)
            var appUrl: String? = null
            var appName: String? = null
            var modUrl: String? = null
            var modName: String? = null
            var modCode = 0
            val assets = root.optJSONArray("assets") ?: org.json.JSONArray()
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val n = a.optString("name")
                val u = a.optString("browser_download_url")
                when {
                    n.startsWith("MirrorBackup-") && n.endsWith(".apk") -> {
                        appUrl = u; appName = n
                    }
                    n.startsWith("mirrorbackup-module-") && n.endsWith(".zip") -> {
                        modUrl = u; modName = n
                        // 从文件名解析模块 versionCode：mirrorbackup-module-v1.0.0(100).zip 或约定格式
                        modCode = parseModuleCodeFromName(n)
                    }
                }
            }
            ReleaseInfo(
                tag = root.optString("tag_name"),
                name = root.optString("name"),
                notes = root.optString("body"),
                appAssetUrl = appUrl,
                appAssetName = appName,
                moduleAssetUrl = modUrl,
                moduleAssetName = modName,
                moduleVersionCode = modCode
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseModuleCodeFromName(name: String): Int {
        // 命名约定：mirrorbackup-module-vX.Y.Z-CODE.zip
        return try {
            val m = Regex("-(\\d+)\\.zip$").find(name)
                ?: Regex("\\((\\d+)\\)").find(name)
            m?.groupValues?.get(1)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /** 语义化版本比较：a>b → 1, a==b → 0, a<b → -1（非语义化版本按字符串比较） */
    fun compareVersion(a: String, b: String): Int {
        fun norm(s: String): List<Int> =
            s.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val va = norm(a)
        val vb = norm(b)
        val n = maxOf(va.size, vb.size)
        for (i in 0 until n) {
            val x = va.getOrElse(i) { 0 }
            val y = vb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** 下载文件（带进度回调），返回目标文件 */
    suspend fun download(
        url: String,
        dest: File,
        onProgress: (done: Long, total: Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            dest.parentFile?.mkdirs()
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "MirrorBackup-Android")
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            if (conn.responseCode != 200) return@withContext null
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            conn.disconnect()
            dest
        } catch (e: IOException) {
            null
        }
    }

    enum class InstallResult { ROOT_OK, INTENT_FALLBACK, FAILED }

    /** 安装 APK：root 设备走 pm install；否则跳系统安装器（用户手动确认） */
    suspend fun installApk(context: Context, su: SuManager, apk: File): InstallResult =
        withContext(Dispatchers.IO) {
            try {
                if (su.findSu() != null) {
                    val r = su.exec("pm install -r -d ${SuManager.quote(apk.absolutePath)} 2>&1", 180_000)
                    if (r.exitCode == 0 && (r.stdout.contains("Success") || r.stderr.contains("Success"))) {
                        return@withContext InstallResult.ROOT_OK
                    }
                }
            } catch (_: Exception) {
            }
            // 兜底：系统安装器
            try {
                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    context.packageName + ".fileprovider",
                    apk
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                intent.setDataAndType(uri, "application/vnd.android.package-archive")
                intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return@withContext InstallResult.INTENT_FALLBACK
            } catch (e: Exception) {
                InstallResult.FAILED
            }
        }

    /** 安装模块：优先 magisk --install-module；否则解压到 /data/adb/modules/（Magisk/KSU 通用） */
    suspend fun installModule(su: SuManager, zip: File, log: LogBuffer): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val hasMagisk = su.exec("command -v magisk 2>/dev/null", 8_000)
                    .stdout.trim().startsWith("/")
                if (hasMagisk) {
                    log.info("检测到 Magisk，使用 magisk --install-module 安装模块…")
                    val r = su.exec("magisk --install-module ${SuManager.quote(zip.absolutePath)} 2>&1", 120_000)
                    if (r.exitCode == 0) return@withContext true
                    log.warn("magisk --install-module 失败，回退手动解压: ${r.stderr.take(120)}")
                }
                // 手动解压（Magisk / KernelSU / APatch 通用路径）
                val dir = "/data/adb/modules/${ModuleHelper.MODULE_ID}"
                val script = buildString {
                    append("rm -rf $dir; mkdir -p $dir; ")
                    append("unzip -o ${SuManager.quote(zip.absolutePath)} -d $dir >/dev/null 2>&1; ")
                    append("chmod -R 755 $dir/system/bin 2>/dev/null; ")
                    append("[ -f $dir/module.prop ] && echo OK || echo FAIL")
                }
                val r = su.exec(script, 120_000)
                r.stdout.contains("OK")
            } catch (e: Exception) {
                false
            }
        }
}

/** 提取辅助模块检测 */
object ModuleHelper {
    const val MODULE_ID = "mirrorbackup_module"

    data class ModuleStatus(
        val installed: Boolean,
        val version: String,
        val versionCode: Int,
        val binariesReady: Boolean
    )

    fun readStatus(su: SuManager): ModuleStatus {
        return try {
            val propPath = "/data/adb/modules/$MODULE_ID/module.prop"
            val r = su.exec("cat $propPath 2>/dev/null", 8_000)
            val text = r.stdout
            if (!text.contains("id=$MODULE_ID") && !text.contains("id=")) {
                return ModuleStatus(false, "", 0, false)
            }
            val version = text.lines().firstOrNull { it.startsWith("version=") }
                ?.substringAfter('=')?.trim() ?: ""
            val code = text.lines().firstOrNull { it.startsWith("versionCode=") }
                ?.substringAfter('=')?.trim()?.toIntOrNull() ?: 0
            val bin = su.exec("command -v img2simg 2>/dev/null && command -v mb_dd 2>/dev/null", 8_000)
                .stdout.trim().startsWith("/")
            ModuleStatus(true, version, code, bin)
        } catch (e: Exception) {
            ModuleStatus(false, "", 0, false)
        }
    }
}
