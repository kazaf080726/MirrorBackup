plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

/**
 * 电脑端「假手机环境」下的恢复流程测试模块。
 *
 * 目的：在 PC 上验证恢复工作台的校验与写入逻辑（不接触任何真实设备）：
 *  - 校验：SHA-256 + ZIP CRC32 + 展开大小（raw / sparse 两种）
 *  - 写入：镜像流 → 假分区文件，再回读比对哈希
 *  - 反向用例：篡改字节 / 改哈希 / 改大小 / 截断 / 错 CRC / 坏 sparse / 缺镜像 / 空间不足 / 指纹不符
 *
 * 关键点：直接编译 app 里 core/pure 的源码（RestoreVerify / LibSparse / Manifest），
 * 也就是与真机 APP 跑的是同一份逻辑。
 */
kotlin {
    jvmToolchain(17)
    sourceSets {
        main {
            kotlin.srcDir("../app/src/main/java/com/kazaf/mirrorbackup/core/pure")
        }
    }
}

dependencies {
    // Android 自带 org.json，PC 上补一份同 API 的实现（Manifest.kt 用它）
    implementation("org.json:json:20240303")
}

application {
    mainClass.set("com.kazaf.mirrorbackup.harness.MainKt")
}
