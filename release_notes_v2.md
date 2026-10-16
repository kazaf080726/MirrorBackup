## 镜像备份 v2.1.0（App）+ 提取辅助模块 v1.0.0

### 🎨 iOS 27 液态玻璃重构
- 预渲染软件模糊背景（纯 android.graphics 位图 + 盒式模糊，规避个别 OEM hwui 原生崩溃）+ 镜面高光 + 渐变边缘光 + 呼吸光晕
- 按压回弹动效、预测性返回手势

### ⛔ 黑名单全面交给用户
- 移除一切隐性排除：全量备份 = 扫描到的全部分区（含 super 与 boot/dtbo/vbmeta 等引导分区）
- 「我的黑名单」由用户自行增删（手动列表 / 设置页）；引导相关分区仅显示 ⚠ 风险提示

### 🧩 提取辅助模块（Magisk / KernelSU / APatch）
- AOSP libsparse 编译的 img2simg / simg2img（arm64），备份时自动优先使用
- module.prop 自带 updateJson，可在管理器内直接升级

### 📦 应用内检查更新 / 一键升级
- 设置页检查更新（仅此时访问 GitHub API，其余全程离线）
- 仅 App 更新只装 APK；模块更新先装模块再装 APK，一次完成

### 🔧 其他
- 大容量目标（>20GB）二次确认，防止误触巨量备份
- 启动扫描日志持久化，便于排查

### 真机实测（OPPO PLC110 · KernelSU）
- 扫描 102/102 分区、零崩溃（Tab 压力测试）
- 手动备份 5 分区 E2E：实时哈希 → 逐镜像校验 → 全量校验（SHA-256+CRC32）→ manifest → 记录表，全部通过；镜像与系统 dd 直读哈希一致
- 模块二进制往返转换（raw→sparse→raw）SHA-256 一致

### 校验
SHA256 见附件 SHA256SUMS.txt