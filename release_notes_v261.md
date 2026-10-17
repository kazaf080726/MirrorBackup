## 备份/解包工具箱 v2.6.1 —— 解包模块收尾（真机实跑通过）

### 🔧 原生解包器彻底打通
- 固定 `libpayload_dumper.so` 的 JNI 调用约定：`outputPath` 传**目标 `.img` 文件路径**
  （传目录会报 `Is a directory (os error 21)`）；全量包 `sourceDir` 传 **null**
- 修复「选择分区解包」选中 OTA 压缩包时会**整包解压**的 bug，现在必定走 payload 分区解包
- 新增解包后**逐分区 SHA-256 校验**（与 payload 清单 hash 比对）+ 实际大小比对，
  日志直接给出「校验通过 / 不一致」
- `payload.bin` **缓存复用**：同一个卡刷包第二次分析/解包不再重拷 6~8GB
  （用 zip 路径 + CRC + 大小做旁车标记，换包自动失效）
- 停止按钮：取消时不再误计为失败，状态显示「已停止」
- 新增诊断自检入口（adbd 触发，正常使用无影响）：
  `adb shell am start -n com.kazaf.mirrorbackup/.MainActivity --ez selftest true`

### ✅ 真机实测（OPPO PLC110 · KernelSU · 一加 Ace 5 至尊版 16.0.10.500 卡刷包）

| 分区 | 输出大小 | SHA-256 | 结果 |
|---|---|---|---|
| vbmeta_system | 4,096 B | `941e1456…992a9` | 与清单一致 ✔ |
| product | 3,026,944 B | `68824f1c…131e0` | 与清单一致 ✔ |
| boot | 67,108,864 B | `c664a0ad…e2eec` | 与清单一致 ✔ |

- `boot`（64 MB）：**0.57 秒 / 117.7 MB/s**（xz 解压 + 落盘），32 个 extent 共 34 次进度回调
- 设备侧 `sha256sum` 独立复核，三个镜像全部对上

SHA-256 见附件 SHA256SUMS.txt
