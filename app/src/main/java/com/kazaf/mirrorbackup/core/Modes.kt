package com.kazaf.mirrorbackup.core

/** 备份模式（4 种） */
enum class BackupMode(val key: String, val label: String, val desc: String) {
    ALL("all", "全部备份", "扫到多少备份多少，含 super 与引导分区"),
    NORMAL("normal", "普通备份", "跳过 super 与大于 10GB 的分区"),
    MANUAL("manual", "手动备份", "自行勾选需要备份的分区"),
    PRIVATE("private", "私有分区备份", "仅 efs/nv/modem 等硬件私有分区")
}

/** 备份线程模式（默认多线程） */
enum class ThreadMode(val label: String, val count: Int) {
    SINGLE("单线程", 1),
    DUAL("双线程", 2),
    MULTI("多线程", 4)
}

fun modeLabelOf(key: String): String =
    BackupMode.entries.firstOrNull { it.key == key }?.label ?: key
