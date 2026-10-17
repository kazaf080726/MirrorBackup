## 镜像备份 v2.5.0 + 提取辅助模块 v2.1.0

### 🟢 KSU 绿色状态卡（源码级复刻）
- 照搬 KernelSU Manager StatusCard：底色 #DFFAE4、110dp 圆环右下出血（offset 27,31）、标题 22sp SemiBold、版本 15sp、模式 16sp、按压 Tilt（Q弹）
- 圆环随状态变化：正常=绿勾环，异常=红叉环
- 删除右上角 ROOT 芯片；无 ROOT 弹窗提示

### 🎨 UI 与主题
- 双风格可切换：KSU 蓝绿 / Maple 紫（淡紫渐变+紫罗兰光晕+紫白玻璃卡）
- Monet 变体语义修复；背景光晕常驻呼吸
- 120dp 大进度圆环（12dp 描边、字号自适应）
- 模式卡选中改为圆角描边环（修复右侧覆盖不全）

### 🌐 三语国际化
- 简体中文 / 繁體中文 / English + 跟随系统，应用内切换（2×2 等宽网格，不跳动变形）
- 覆盖：主界面、状态卡、模式卡、存储卡、主题入口、语言卡

### 📦 存储与双重校验
- 备份写入 /storage/emulated/0/镜像备份/，含说明文件 / manifest / sha256
- 每镜像 SHA-256 重读 + 全量 SHA-256 & CRC32 复核
- 已有备份校验（逐镜像比对）、记录删除、分区搜索、空间预检

### 🧩 模块 v2.1.0
- 新增 mb_info（设备/Root 环境）、mb_verify（SHA-256 校验）
- 二进制：mb_scan / mb_info / mb_verify / mb_dd / mb_parts / img2simg / simg2img
- 设置页「设备环境」卡接入（实测 OnePlus PLC110 / Android 16 / 8核 aarch64 / 槽位 _a）

校验值见附件 SHA256SUMS.txt