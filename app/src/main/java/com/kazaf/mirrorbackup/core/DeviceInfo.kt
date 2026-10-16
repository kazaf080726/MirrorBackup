package com.kazaf.mirrorbackup.core

import java.security.MessageDigest

data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val sdkInt: String,
    val buildFingerprint: String,
    val sn: String,
    val cpuId: String
) {
    /** 设备指纹 = SHA-256(SN + CPU ID)，不采集 IMEI，规避隐私风险 */
    val fingerprint: String
        get() = sha256Text("SN=$sn|CPU=$cpuId")
}

object DeviceInfoCollector {

    fun collect(su: SuManager): DeviceInfo {
        val script = buildString {
            append("getprop ro.product.model; echo \"--\"; ")
            append("getprop ro.product.manufacturer; echo \"--\"; ")
            append("getprop ro.build.version.release; echo \"--\"; ")
            append("getprop ro.build.version.sdk; echo \"--\"; ")
            append("getprop ro.build.fingerprint; echo \"--\"; ")
            append("getprop ro.serialno; echo \"--\"; ")
            append("getprop ro.boot.serialno; echo \"--\"; ")
            append("getprop ro.boot.cpuid")
        }
        val out = su.exec(script, 15_000).stdout
        val parts = out.split("--")
        fun at(i: Int): String =
            parts.getOrElse(i) { "" }.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""

        val model = at(0)
        val manufacturer = at(1)
        val androidVersion = at(2)
        val sdk = at(3)
        val buildFp = at(4)
        var sn = at(5)
        val bootSn = at(6)
        if (sn.isBlank()) sn = bootSn

        var cpuId = at(7).trim()
        if (cpuId.isBlank()) {
            // 从 /proc/cpuinfo 的 Serial 字段读取 CPU ID
            val cpuinfo = su.exec("cat /proc/cpuinfo 2>/dev/null", 15_000).stdout
            for (line in cpuinfo.lines()) {
                val t = line.trim()
                if (t.startsWith("Serial")) {
                    cpuId = t.substringAfter(':').trim()
                    break
                }
            }
        }
        return DeviceInfo(model, manufacturer, androidVersion, sdk, buildFp, sn.trim(), cpuId)
    }
}

fun sha256Text(s: String): String = sha256Bytes(s.toByteArray(Charsets.UTF_8))

fun sha256Bytes(data: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(data).joinToString("") { "%02x".format(it) }
}
