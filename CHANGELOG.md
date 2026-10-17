# 更新日志
## App v2.6.1（2026-10-17 解包模块收尾）

### 🔧 解包：JNI 原生解包实跑打通
- 确认并固定原生库 `libpayload_dumper.so` 调用约定：`outputPath` 必须是**目标 .img 文件路径**
  （传目录会抛 `Is a directory (os error 21)`），全量包 `sourceDir` 传 **null** 即可
- 修复「选择分区解包」选 OTA 压缩包时**错误走整包解压**的问题（现在强制走 payload 分区解包）
- 解包后逐分区 **SHA-256 校验**（对比 payload 清单 hash）+ 实际大小比对，日志明示通过/不一致
- payload.bin **缓存复用**：二次分析/解包不再重拷 6~8GB（zip 路径+CRC+大小 旁车标记校验）
- 停止按钮：取消时不再误记为失败，状态显示「已停止」
- 诊断入口：`adb shell am start -n com.kazaf.mirrorbackup/.MainActivity --ez selftest true`
  可跑 JNI 契约自检（结果写应用私有目录 `files/logs/selftest.json`）

### ✅ 真机实测（OPPO PLC110 · KernelSU · 一加 Ace 5 至尊版 16.0.10.500 卡刷包）
- `vbmeta_system`：4096 B，SHA-256 `941e1456…992a9` —— 与清单完全一致
- `product`：3,026,944 B，SHA-256 `68824f1c…131e0` —— 与清单完全一致
- `boot`：67,108,864 B（64 MB），**0.57 s / 117.7 MB/s**（xz 解压+落盘），34 次进度回调，hash 一致
- 磁盘侧 `sha256sum` 独立复核通过

## App v2.5.1（2026-10-17 补丁）

### ⭕ 全圆角化
- 所有卡片统一圆角：日志窗口圆角改为跟随主题圆角设置（14/18/22/28dp）
- KSU 状态卡圆角 24dp → **26dp**（更圆润）
- 圆环图标：描边 + 对勾/叉笔画全部改为**圆头 + 圆角连接**（StrokeCap.Round / StrokeJoin.Round），没有尖角

## App v2.5.0 / 模块 v2.1.0（2026-10）

### 🟢 KSU 绿色状态卡（源码级复刻）
- 完全照搬 KernelSU Manager HomeMiuix.StatusCard 参数：
  底色 #DFFAE4（深色 #1A3825）、110dp 圆环图标右下出血 offset(27,31)、
  标题 22sp SemiBold、版本行 15sp、工作模式 16sp Medium 左下
- 圆环状态可变：正常 = 绿色对勾环（#36D167），异常 = 红色叉环
- 按压 Tilt 反馈（缩放 0.97 + 倾角 1.4°，Q 弹）
- 删除右上角 ROOT 芯片；检测不到 ROOT 时改为弹窗提示

### 🎨 主题与 UI
- UI 双风格可切换：KSU 蓝绿 / Maple 紫（淡紫渐变背景 + 紫罗兰光晕 + 紫白半透明卡）
- Monet 变体语义修复（MONET_SYSTEM/LIGHT/DARK 真正强制壁纸动态取色）
- 背景光晕改为持续呼吸（常驻光影）
- 120dp 大进度圆环（12dp 描边、百分比字号自适应）
- 模式卡选中改为沿圆角描边环（修复右侧覆盖不全）

### 🌐 三语国际化
- 跟随系统 / 简体中文 / 繁體中文 / English（应用内切换，2×2 等宽网格，不换行不跳大小）
- 已覆盖：主界面标题与标签、状态卡、四种备份模式卡、存储位置卡、主题入口、语言卡片

### 📦 存储与校验
- 备份写入 /storage/emulated/0/镜像备份/（含说明文件、manifest、每镜像 sha256）
- 双重校验：每镜像 SHA-256 重读比对 + 全量 SHA-256 & CRC32 复核
- 已有备份校验按钮（逐镜像 SHA-256 与 manifest 比对）
- 记录删除、分区搜索、剩余空间预检

### 🧩 模块 v2.1.0（versionCode 210）
- 新增 mb_info（设备与 Root 环境信息）、mb_verify（单文件 SHA-256 校验）
- 完整二进制：mb_scan / mb_info / mb_verify / mb_dd / mb_parts / img2simg / simg2img
- App 设置页「设备环境」卡接入 mb_info（实测 OnePlus PLC110 / Android 16 / 内核 / 8核 aarch64 / 槽位 _a）

### 其它
- 线程自动满核（CPU 核心检测，8 核 → 8 线程）
- 备份完成通知、日志进度条 + 流行动效
- 工程：Kotlin 2.2.20 / Compose 1.9.3 / AGP 8.13 / compileSdk 36