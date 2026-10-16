package com.kazaf.mirrorbackup.core

import android.content.Context

/**
 * 用户自主黑名单。
 * 应用不做任何隐性排除 —— 全量备份包含所有分区（含 super、boot 等引导分区）。
 * 是否排除、排除哪些分区，完全由用户在本工具中显式添加/移除。
 */
object UserBlacklist {

    private const val PREFS = "user_blacklist"
    private const val KEY = "names"

    fun load(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun save(context: Context, names: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY, names).apply()
    }

    fun add(context: Context, name: String) {
        save(context, load(context) + name)
    }

    fun remove(context: Context, name: String) {
        save(context, load(context) - name)
    }

    fun toggle(context: Context, name: String): Set<String> {
        val cur = load(context)
        val next = if (name in cur) cur - name else cur + name
        save(context, next)
        return next
    }
}
