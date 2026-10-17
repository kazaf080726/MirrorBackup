package com.kazaf.mirrorbackup.core

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/** 语言偏好：跟随系统 / 简体中文 / 繁體中文 / English */
object LocaleHelper {

    private const val PREFS = "mirror_backup_prefs"
    private const val KEY = "language"

    /** 可选语言：""=跟随系统 */
    val OPTIONS = listOf("", "zh-CN", "zh-TW", "en")

    fun current(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun setLanguage(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
    }

    fun displayName(tag: String): String = when (tag) {
        "zh-CN" -> "简体中文"
        "zh-TW" -> "繁體中文"
        "en" -> "English"
        else -> "跟随系统 / System"
    }

    /** 生成应用了语言偏好的新 Context */
    fun wrap(context: Context): Context {
        val tag = current(context)
        if (tag.isEmpty()) return context
        val locale = when (tag) {
            "zh-CN" -> Locale.SIMPLIFIED_CHINESE
            "zh-TW" -> Locale.TRADITIONAL_CHINESE
            "en" -> Locale.ENGLISH
            else -> return context
        }
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(locale))
        return context.createConfigurationContext(config)
    }
}
