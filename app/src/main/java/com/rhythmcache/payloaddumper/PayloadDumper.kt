package com.rhythmcache.payloaddumper

/**
 * payload.bin 解包器封装 —— 复用 payload-dumper 原生库（Rust + xz，JNI 接口）。
 * 原生库提供：listLocalPartitions(path) 返回分区清单 JSON；
 * extractLocalPartition(path, partitionName, outputPath, sourceDir, callback) 解出单个分区，
 * 每处理一个 extent 回调一次进度（含百分比、状态、告警）。
 */
class PayloadDumper private constructor() {

    interface ProgressCallback {
        fun onProgress(
            partitionName: String,
            currentOperation: Long,
            totalOperations: Long,
            percentage: Double,
            status: Int,
            warningOpIndex: Int,
            warningMessage: String
        ): Boolean
    }

    external fun listLocalPartitions(path: String): String

    external fun extractLocalPartition(
        path: String,
        partitionName: String,
        outputPath: String,
        sourceDir: String?,
        callback: ProgressCallback?
    )

    companion object {
        val INSTANCE: PayloadDumper by lazy { PayloadDumper() }

        init {
            System.loadLibrary("payload_dumper")
        }
    }
}
