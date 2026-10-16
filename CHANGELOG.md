# 更新日志

## App v2.2.0 / 模块 v1.1.0（2026-10）

### 🎨 主题设置体系（照搬 KernelSU / SukiSU 管理器）
- Theme 颜色模式：跟随系统 / 浅色 / 深色 / AMOLED 黑
- 启用 Monet 配色（Material You 动态取色，Android 12+）
- Key color：15 色强调色板 + 自定义色调滑块（关闭 Monet 时生效）
- 液态玻璃：总开关 + 强度（弱/中/强）+ 透明度 + 镜面高光强度（双重高光：主光 + 斜向次光，对齐 SukiSU AGSL 着色器效果）
- 圆角大小：14 / 18 / 22 / 28；背景光晕开关
- 全部设置即时生效并持久化

### 🧩 提取辅助模块 v1.1.0（二次升级）
- 新增 mb_parts：单进程极速分区枚举（realpath 解析多层符号链接，102/102 无遗漏）
- 新增 mb_dd：单进程 dd + 内置 SHA-256 实时哈希（替代 shell 管道链，更快更稳）
- 保留 img2simg / simg2img（AOSP libsparse）
- App 检测到模块后自动优先使用 mb_parts 扫描与 mb_dd 提取
- update.json 同步至 v1.1.0-110，可在管理器内直接升级

### 修复
- mb_parts 对 dm-mapper 分区（by-name → mapper → dm-N 多层链接）尺寸解析

## App v2.1.x / 模块 v1.0.0
- iOS 27 液态玻璃（预渲染软件模糊）、用户自主黑名单、应用内检查更新/一键升级（只装一次）、提取辅助模块 v1.0.0

### 🎨 iOS 27 液态玻璃重构
- 真实背景折射（GraphicsLayer + RenderEffect 实时高斯模糊快照）+ 镜面高光 + 渐变边缘光
- 光影渐变背景 + 呼吸光晕；卡片/悬浮栏/选项卡全面玻璃化
- 按压回弹动效；预测性返回手势（enableOnBackInvokedCallback + BackHandler）

### ⛔ 黑名单全面交给用户
- 移除一切隐性排除：全量备份 = 扫到的全部分区（含 super 与 boot/dtbo/vbmeta 等引导分区）
- 新增「我的黑名单」：由用户在手动列表或设置页自行添加/移除
- 引导相关分区仅显示 ⚠ 风险提示，是否备份由用户决策

### 🧩 提取辅助模块（Magisk / KernelSU / APatch）
- AOSP libsparse 编译的 img2simg / simg2img（arm64），备份时自动优先使用
- 自带 update.json，可在管理器内直接检查并升级

### 📦 应用内检查更新 / 一键升级
- 用户点击时访问 GitHub Release（其余全程离线）
- App 更新只装 APK；模块更新先装模块再装 APK，一次完成

### 修复
- 启动闪退（Compose 快照竞态）与分区扫描显示问题

## App v2.0.1
- 修复启动闪退；分区扫描与排除明细透明化

## App v2.0.0
- 四种备份模式 / 多线程 / 双重校验 / 仅存储 ZIP / 液态玻璃 UI

## App v1.0.0
- 初版
