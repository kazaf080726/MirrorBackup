## 备份/解包工具箱 v2.10.0 —— 完全解包 Scene.apk，刷入逻辑 1:1 复刻

### 🔬 Scene.apk 逆向结果（完整解包）
- jadx 反编译 **2906 个类** + apktool/baksmali；刷入模块定位在 `com.omarea.vtools.activities.ActivityImg`
- **写入核心** = `res/raw/install_image`（被 AndResGuard 改名成 `res/CY`）里的 `flash_image` 函数
  - 调用点在 `a/cb`（jadx 解码失败，改用 smali 还原）：`<raw脚本> + "\nflash_image '<镜像>' '<分区>'\necho $?"`
- **分区目录优先级** = `assets/kr-script/img/bootdevice.sh`
  （`/dev/block/bootdevice/by-name` → `/dev/block/by-name` → `/dev/block/platform/bootdevice/by-name`，**后者覆盖前者**）
- **分区镜像导出** = `a/ab`：`dd if='<分区>' of='/sdcard/<分区名>.img'`
- **TWRP 注入** = `twrp_mode.sh`（判定 boot_ab / recovery_ab / unsupported）+ `twrp_install.sh`（magiskboot 换 ramdisk → flash_image 写两个槽位）
- **VAB 槽位** = `vab_slot.sh`：`getprop ro.boot.slot_suffix`
- 结论：Scene 的「备份还原」= 导出分区镜像（dd）+ 镜像刷入（flash_image）+ TWRP 注入 + 设备/槽位信息

### ⚡ 写入逻辑改成 1:1（原来的 dd 写法已移除）
| Scene 行为 | 现在的实现 |
|---|---|
| `.gz` 镜像 → `gzip -d` 流式解压 | 同（准备阶段也会解开以便算 SHA-256） |
| 块设备：`stat -c %s` vs `blockdev --getsize64`，镜像大 → **返回 1** | 同，提示「镜像过大无法写入分区」 |
| `blockdev --setrw`，`--getro` 仍为 1 → **返回 2** | 同，提示「目标分区是只读的」 |
| 写入：镜像 + `cat - /dev/zero` → **0 填充到分区末尾** | 同（原 dd 写法缺的就是这一步） |
| 字符设备：`flash_eraseall` + `nandwrite -p` | 同 |
| 普通文件：直接写 | 同 |
| 分区目录优先级（bootdevice.sh） | 同，路径按最后命中的目录解析 |
| 分区镜像导出 `dd if=… of=/sdcard/<名>.img` | 同，新增「导出该分区镜像」按钮 |
| TWRP 注入（boot_ab / recovery_ab） | 同（脚本我们重写，命令与返回码一致） |

保留我们的安全增强：前置校验 9 项、A/B 槽位选择、确认弹窗、**刷后回读 SHA-256**。

### 🐞 PC 实测查出并修掉的 bug
- 第一版 `.gz` 判断写成 `"$img" != "$img%.gz"`（缺大括号 → 永远走 gzip 分支），在 WSL bash 跑生成脚本时表现为「raw 镜像写出 0 字节」
- 改成 Scene 同款 `case "$img" in *.gz)` 后重跑：**raw 65536B 字节一致、gz 65536B 字节一致、返回码 0**

### 🖥️ PC 模拟测试（不碰真机）
- `gradle :restore-harness:run` → **53 项全通过**
  （脚本要素：setrw / getsize64 / 零填充 / flash_image / 返回码 1、2；路径唯一；目录优先级；导出脚本；TWRP 模式判定与脚本结构；刷后回读）
- 生成的刷入脚本用 **WSL bash 真跑**：raw / gz 均字节一致
- 块设备分支（setrw / 只读 / 零填充）在 PC 上无法建 loop 设备（WSL 非 root），因此**没有在真机上试刷**

### 📦 附件
- `MirrorBackup-v2.10.0.apk`
- `mirrorbackup-module-v2.2.0-220.zip`
- `SHA256SUMS.txt`
