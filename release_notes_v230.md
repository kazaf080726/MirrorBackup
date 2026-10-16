## 镜像备份 v2.3.0 —— UI 源码级照搬 SukiSU / KernelSU 管理器

### 液态玻璃（与 SukiSU-Ultra 完全同源）
- **iOS 液态玻璃悬浮底栏**：完整移植 SukiSU 的 FloatingBottomBar，
  内嵌 Kyant0/AndroidLiquidGlass（Apache 2.0）效果管线：
  - drawBackdrop 实时模糊采样 + vibrancy 饱和度增强
  - lens 镜头折射（折射高度 / 折射量 / 深度效果 / 色差）
  - 镜面高光随**重力感应**实时转动（加速度计驱动）
  - DampedDrag 阻尼拖拽 + AGSL 按压光斑 + 指示器弹性动画
- **顶栏纹理模糊**（blur 25px + 表面混合）
- 蓝紫渐变背景 + 呼吸光晕 + 预测性返回手势

### 关键修复
- 修复 ColorOS hwui 渲染树循环导致 RenderThread 栈溢出崩溃：
  玻璃表面改为 backdrop 内容层的**兄弟节点**采样（与 SukiSU 结构一致）
- 工程升级：Kotlin 2.2.20 / Compose 1.9.3 / AGP 8.13 / compileSdk 36

### 真机实测（OPPO PLC110 · KernelSU）
- 标签切换 / 滑动 / 主题切换 / 玻璃开关全程零崩溃
- 亮色玻璃对比 238:161，暗色 187:105，悬浮质感正常
- 提取辅助模块 v1.1.0 继续有效（mb_parts / mb_dd / img2simg / simg2img）

SHA-256：见附件 SHA256SUMS.txt