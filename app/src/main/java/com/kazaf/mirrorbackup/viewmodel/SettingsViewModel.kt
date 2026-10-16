package com.kazaf.mirrorbackup.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kazaf.mirrorbackup.core.LogBuffer
import com.kazaf.mirrorbackup.core.ModuleHelper
import com.kazaf.mirrorbackup.core.SuManager
import com.kazaf.mirrorbackup.core.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置页：检查更新（App + 提取辅助模块协同升级）、模块状态。
 * "只装一次"：若模块与 App 均有更新，一键先后安装模块与 APK。
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val su = SuManager
    val log = LogBuffer().also {
        it.attachFile(File(app.filesDir, "logs/app.log"))
    }

    var busy by mutableStateOf(false)
        private set
    var checking by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    var latest by mutableStateOf<Updater.ReleaseInfo?>(null)
        private set
    var appUpdateAvailable by mutableStateOf(false)
        private set
    var moduleUpdateAvailable by mutableStateOf(false)
        private set

    var moduleStatus by mutableStateOf<ModuleHelper.ModuleStatus?>(null)
        private set

    var downloadProgress by mutableStateOf(0f)
        private set
    var downloadLabel by mutableStateOf("")
        private set
    var installSummary by mutableStateOf<String?>(null)
        private set

    val appVersion: String
        get() = com.kazaf.mirrorbackup.BuildConfig.VERSION_NAME

    fun refreshModuleStatus() {
        viewModelScope.launch {
            val st = withContext(Dispatchers.IO) { ModuleHelper.readStatus(su) }
            moduleStatus = st
        }
    }

    /** 检查更新（仅此时联网访问 GitHub API） */
    fun checkUpdate() {
        if (busy) return
        busy = true
        checking = true
        lastError = null
        appUpdateAvailable = false
        moduleUpdateAvailable = false
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { Updater.fetchLatest() }
                latest = info
                if (info == null) {
                    lastError = "无法获取更新信息（网络不可用或仓库不可达）"
                } else {
                    appUpdateAvailable = info.appAssetUrl != null &&
                        Updater.compareVersion(info.tag, appVersion) > 0
                    val st = withContext(Dispatchers.IO) { ModuleHelper.readStatus(su) }
                    moduleStatus = st
                    moduleUpdateAvailable = info.moduleAssetUrl != null &&
                        info.moduleVersionCode > 0 &&
                        (!st.installed || info.moduleVersionCode > st.versionCode)
                    if (!appUpdateAvailable && !moduleUpdateAvailable) {
                        lastError = "已是最新版本（App $appVersion）"
                    }
                    log.info("✅ 检查更新完成: 最新 ${info.tag}")
                    log.info("   App 更新: ${if (appUpdateAvailable) "有" else "无"} · 模块更新: ${if (moduleUpdateAvailable) "有" else "无"}")
                }
            } catch (e: Exception) {
                lastError = "检查更新失败: ${e.message}"
            } finally {
                busy = false
                checking = false
            }
        }
    }

    /**
     * 一键更新（"只装一次"）：
     * 模块有更新 → 下载并安装模块 → 再下载并安装 APK。
     */
    fun oneClickUpdate() {
        val info = latest ?: return
        if (busy) return
        busy = true
        installSummary = null
        viewModelScope.launch {
            try {
                val dir = File(getApplication<Application>().cacheDir, "updates").apply { mkdirs() }
                var moduleInstalled = false
                if (moduleUpdateAvailable && info.moduleAssetUrl != null) {
                    downloadLabel = "正在下载提取辅助模块…"
                    downloadProgress = 0f
                    val zip = File(dir, info.moduleAssetName ?: "module.zip")
                    val ok = withContext(Dispatchers.IO) {
                        Updater.download(info.moduleAssetUrl, zip) { done, total ->
                            if (total > 0) downloadProgress = (done.toFloat() / total).coerceIn(0f, 1f)
                        }
                    }
                    if (ok == null) {
                        installSummary = "❌ 模块下载失败"
                        return@launch
                    }
                    downloadLabel = "正在安装模块（重启后生效）…"
                    moduleInstalled = withContext(Dispatchers.IO) {
                        Updater.installModule(su, zip, log)
                    }
                    if (moduleInstalled) {
                        log.ok("✔ 提取辅助模块安装完成（需重启设备后生效）")
                    } else {
                        log.error("❌ 模块安装失败")
                    }
                }
                if (appUpdateAvailable && info.appAssetUrl != null) {
                    downloadLabel = "正在下载 App 新版本…"
                    downloadProgress = 0f
                    val apk = File(dir, info.appAssetName ?: "update.apk")
                    val ok = withContext(Dispatchers.IO) {
                        Updater.download(info.appAssetUrl, apk) { done, total ->
                            if (total > 0) downloadProgress = (done.toFloat() / total).coerceIn(0f, 1f)
                        }
                    }
                    if (ok == null) {
                        installSummary = if (moduleInstalled) "⚠ 模块已安装，但 App 下载失败" else "❌ App 下载失败"
                        return@launch
                    }
                    downloadLabel = "正在安装 App…"
                    val result = withContext(Dispatchers.IO) { Updater.installApk(getApplication(), su, apk) }
                    when (result) {
                        Updater.InstallResult.ROOT_OK -> {
                            log.ok("✔ App 新版本安装完成")
                            installSummary = if (moduleInstalled) {
                                "✅ 模块 + App 已全部更新（模块需重启设备生效）"
                            } else {
                                "✅ App 已更新到 ${info.tag}，重启应用即可生效"
                            }
                        }
                        Updater.InstallResult.INTENT_FALLBACK -> {
                            installSummary = "📲 已拉起系统安装器，请手动确认安装"
                        }
                        else -> {
                            installSummary = "❌ App 安装失败"
                        }
                    }
                } else if (moduleInstalled) {
                    installSummary = "✅ 模块已更新（需重启设备生效）"
                } else {
                    installSummary = "没有可执行的更新"
                }
            } catch (e: Exception) {
                installSummary = "❌ 更新失败: ${e.message}"
            } finally {
                busy = false
                downloadLabel = ""
                downloadProgress = 0f
                refreshModuleStatus()
            }
        }
    }
}
