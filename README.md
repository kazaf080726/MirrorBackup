# 镜像备份 (MirrorBackup)

> 一款运行于已 ROOT 安卓设备上的本地分区镜像备份/恢复工具
> 作者：**Kazaf** · 除检查更新外全程离线 · 无广告 · 无数据上传

[![Android](https://img.shields.io/badge/Android-13%2B-green)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-blue)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-MIUI--X%20style-blueviolet)](https://developer.android.com/jetpack/compose)

## ✨ 功能特性

### 四种备份模式
| 模式 | 说明 |
|---|---|
| 🗂️ 全部备份 | 扫到多少备份多少（含 super 与大容量分区），仅排除安全黑名单 |
| 📦 普通备份 | 跳过 super 与所有大于 10GB 的分区 |
| ✍️ 手动备份 | 自由勾选任意分区（黑名单分区置灰禁止选择） |
| 🧩 私有分区备份 | 仅 efs / nv / modem / persist / wifical / btcal / dsp / factory / keystore / tee |

### 硬核安全设计
- **多层前置校验**：su 可用性与 uid=0 完整 ROOT 校验 → 分区节点 dd 试读 / IO 异常检测 → 异常立即停止一切读写并弹窗警报
- **实时哈希备份**：`dd | tee | sha256sum` 管道，边备份边 SHA-256 校验；镜像大小与分区物理大小强比对
- **备份即校验**：每完成一个镜像立即重读比对 SHA-256；全部完成后执行**全量完整性校验**（SHA-256 + CRC32）
- **仅存储打包**：ZIP 使用 STORE 模式，**不做任何数据压缩**；支持"跳过打包"直接结束（镜像保留工作目录并写入记录表）
- **恢复三层校验（顺序强制）**：① 双重校验（SHA-256 + ZIP CRC32，只读提取零写入）→ ② 分区物理大小 ≥ 镜像字节数（防溢出）→ ③ 设备指纹匹配（SN + CPU ID，不采集 IMEI）；不匹配时恢复按钮锁定，仅折叠隐藏的【强制恢复】需勾选风险确认
- **分区黑白名单**：🔴 boot / dtbo / vbmeta / vbmeta_system / recovery（含 `_a`/`_b` 变体）永不扫描、永不备份、永不写入 —— 交给 TWRP / 内核管理器处理
- **设备指纹**：SN + CPU ID 的 SHA-256，manifest.json 记录全部元数据（路径/大小/哈希/时间戳/型号/Android 版本/模式/格式）

### 多线程备份
单线程 / 双线程 / 多线程（4 并发，**默认多线程**），Semaphore 限流 + 实时聚合进度（瞬时速度 / 已处理字节 / 百分比 / 剩余时间，TWRP 风格滚动日志）。

### ROOT 方案兼容
自动探测 su 路径：`/data/adb/su`（Magisk）、`/sbin/su`、`/system/bin/su`、`/data/adb/ksu/bin/su`（KernelSU）、`/data/adb/ap/bin/su`（APatch）等分支 + PATH 兜底。

### 镜像格式
- **RAW**：dd 原始镜像
- **SPARSE**：内置纯 Kotlin libsparse 编解码器（与 Android libsparse 格式 100% 兼容，magic `0xED26FF3A`，支持 RAW/FILL/DONTCARE/CRC32 chunk 与头部 CRC 校验），产物可直接被 fastboot / simg2img 识别

### UI（MIUI-X 风格）
液态玻璃卡片（渐变描边 + 顶部高光 + 柔和投影）、光影渐变背景与呼吸光晕、底部悬浮操作栏、圆形进度环、深浅色主题自适应、TWRP 风格终端日志。全新图标：盾牌（安全）+ 闪电（多线程）+ 数据条（镜像）。

## 📦 构建

环境要求：JDK 17+、Android SDK（platform 34 / build-tools 34.0.0）。

```bash
# 1. 生成本地签名密钥（keystore/ 已加入 .gitignore）
keytool -genkeypair -v -keystore keystore/mirrorbackup.jks \
  -alias mirrorbackup -keyalg RSA -keysize 2048 -validity 10950 \
  -storetype PKCS12 -storepass 你的密码 -keypass 你的密码 \
  -dname "CN=Kazaf, OU=MirrorBackup, O=Kazaf, C=CN"

# 2. 在 app/build.gradle.kts 的 signingConfigs.release 中填入对应密码

# 3. 构建
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

## ⚠️ 免责声明

> 本工具仅用于备份自己设备的硬件分区镜像。将镜像刷入不匹配的设备会造成基带永久损坏。所有操作风险由使用者本人承担，作者不对任何硬件损坏负责。

## 🔒 隐私与离线承诺

- 网络仅用于「检查更新 / 下载更新包」（用户主动触发时访问 GitHub API），其余功能全程离线、无埋点、无统计、无广告
- 设备指纹仅使用 SN + CPU ID，**不采集 IMEI**
- 所有镜像与 manifest 均通过 SAF 由用户选择导出位置

## 📋 记录表

每次备份/恢复自动写入记录表（`filesDir/backup_records.json`）：时间、模式、线程、格式、提取的镜像清单与数量、是否打包、导出位置、耗时。

## 版本历史

- **v2.1.0（App）/ v1.0.0（模块）**：iOS 27 液态玻璃重构（预渲染软件模糊背景 + 镜面高光 + 渐变边缘光，规避 OEM hwui 崩溃）；黑名单全面交给用户（无任何隐性排除）；提取辅助模块（静态 img2simg/simg2img + update.json 管理器内升级）；应用内检查更新与一键升级（App+模块协同）；大容量目标二次确认；预测性返回
- **v2.0.1**：修复启动闪退（Compose 快照竞态）；分区扫描与排除明细全透明（实测 OPPO PLC110：扫描 102 个分区，黑名单 8 个明示排除）
- **v2.0.0**：四种备份模式 / 多线程备份 / 双重校验 / 仅存储 ZIP / 液态玻璃 UI / 全新图标
- **v1.0.0**：初版（全量 / 私有分区两模式）

## 🧩 提取辅助模块

- 提供 AOSP libsparse 编译的 `img2simg` / `simg2img`（arm64），App 备份时自动优先使用
- 自带 `update.json`：在 Magisk / KernelSU 管理器内可直接检查并升级
- 下载：本仓库 Release 中的 `mirrorbackup-module-*.zip`

## 🔄 应用内更新

设置页可检查更新（仅此时访问 GitHub API，其余功能全程离线）：
- 仅 App 更新 → 下载并安装 APK
- 模块更新 → 先装模块再装 APK，一次完成（"只装一次"）
