# 恢复工作台 · 电脑模拟测试报告

> 假手机环境（假设备/假分区/假 ZIP），跑的是 APP 里同一份校验与写入代码。
> 生成时间: 2026-10-18T01:50:19.983981900
> 结果: **通过 55 / 失败 0**

```
==============================================================
 恢复工作台 · 电脑模拟测试（假手机环境，不接触真机）
 工作目录: C:\Users\ADMINI~1\AppData\Local\Temp\mirror_restore_harness_1792259419619
==============================================================

[假手机] PLC110 / Android 16 / SN=SIM-SN-0001 / 指纹=cd1c5bb75769057f…
  · boot: raw=262144B 存储=262144B (100%) format=raw
  · misc: raw=4096B 存储=4096B (100%) format=raw
  · vendor_boot: raw=524288B 存储=98368B (18%) format=sparse

── 1. manifest 清单 JSON 往返 ──
  ✅ manifest 能写能读 — 3 个分区
  ✅ 设备字段一致 
  ✅ 分区名称/大小/哈希一致 
  ✅ manifest 未出现明文手机号/IMEI 类字段 

── 2. 正常备份包的第①层校验（SHA-256 + 展开大小 + ZIP CRC32）──
  ✅ 全部镜像校验通过 — 3/3
  ✅ raw 镜像 SHA-256 一致 
  ✅ sparse 镜像 SHA-256 一致 
  ✅ sparse 展开大小与 manifest 一致 
  ✅ ZIP CRC32 全部一致 

── 3. 模拟恢复写入（镜像流 → 假分区文件 → 回读哈希）──
     ✅ boot: 写入 262144B / 期望 262144B  写入后SHA=f7aaa5657b730ff3…  期望SHA=f7aaa5657b730ff3…
     ✅ misc: 写入 4096B / 期望 4096B  写入后SHA=1eb145a3d1f468ac…  期望SHA=1eb145a3d1f468ac…
     ✅ vendor_boot: 写入 524288B / 期望 524288B  写入后SHA=d01edfa22a2718b6…  期望SHA=d01edfa22a2718b6…
  ✅ 三个分区写入后内容哈希全部与原始镜像一致 
  ✅ sparse 分区恢复成原始 raw 大小 
  ✅ 假分区文件字节级一致 

── 4. 反向用例（坏包必须被识别）──
  ✅ 镜像被篡改 1 字节 → SHA-256 校验失败 — 实际=e75f28c75ca723eb…
  ✅ manifest 哈希错误 → 识别为不一致 
  ✅ manifest 大小不符 → 识别为大小不一致 — 读入=262144
  ✅ 镜像被截断 → 大小与哈希都失败 
  ✅ ZIP CRC32 记录不符 → 识别为 CRC 失败 
  ✅ sparse 头损坏 → 识别为结构损坏 — 非法 sparse magic: ed26ff00
  ✅ manifest 有、包里没有 → 判为失败 — 压缩包缺少该镜像

── 5. 第②层安全门槛 ──
  ✅ 镜像大于目标分区 → 拦下 — 空间不足（镜像 10485760 > 分区 1048576）
  ✅ 目标分区不存在 → 拦下 — 目标分区不存在
  ✅ 空间足够 → 放行 
  ✅ 目标分区过小 → 恢复被拦截且不写入 — 空间不足（镜像 262144 > 分区 1024）

── 6. 设备指纹校验 ──
  ✅ 同一设备（SN+CPU 相同）→ 指纹一致 
  ✅ 换一台设备（SN 不同）→ 指纹不一致（APP 会锁恢复按钮） 

── 7. 坏包禁止写入的判定（与 APP 相同）──
  ✅ 存在校验失败镜像 → 恢复前即阻断写入 

── 8. 单分区刷入（假 /dev/block/by-name 环境）──
  假分区: boot_a(65536) boot_b(65536) vbmeta(4096) misc(4096)
  ✅ boot.img + boot_a/boot_b → 需要用户选 A/B 槽位 — 检测到 A/B 双分区，请选择槽位
  ✅ 选 boot_a → 前置校验不阻断（风险提示仅警告） — 9/10 通过，警告 1 条
  ✅ 刷入脚本自检通过（只指向选定分区） — 通过
  ✅ 脚本里设备路径唯一 
  ✅ 脚本包含 Scene 的写入要素（setrw / getsize64 / 零填充 / flash_image） 
  ✅ 脚本支持 .gz 镜像（gzip 分支） 
  ✅ 脚本保留 Scene 的返回码约定（1 过大 / 2 只读） 
  ✅ 回读指令精确读取镜像字节数（head -c） — head -c 65536 '/dev/block/by-name/boot_a' 2>/dev/null | sha256sum
    脚本已落盘: D:\Users\Administrator\Documents\DeepSeek Workspace\MirrorBackup\restore-harness\build\reports\flash-script.sh
  ✅ 按脚本语义写入（镜像+零填充）后回读 SHA-256 与镜像一致 — 回读=66319c850c659351… 镜像=66319c850c659351…
  ✅ 分区尾部被 0 填充（Scene 的 cat - /dev/zero 语义） 
  ✅ misc.img → 单分区直接命中，不用选槽 — 文件名与分区名一致：misc
  ✅ boot_a.img + 单分区机型 → 自动对应 boot — 已按单分区 boot 处理
  ✅ vendor.img 无匹配 → 标记为需手动选择 — 未找到与「vendor」匹配的分区
  ✅ 镜像比分区大 → 阻断 — 镜像不超过分区大小
  ✅ 分区在用户黑名单 → 阻断 
  ✅ 目标节点不存在 → 阻断 
  ✅ 路径注入（..）→ 阻断且生成不出脚本 
  ✅ 脚本写错分区 → 自检拦下 
  ✅ 脚本缺少零填充 → 自检拦下 
  ✅ 分区内容与镜像不一致 → 回读校验失败 
  ✅ by-name 目录优先级与 Scene 一致（platform/bootdevice 覆盖 by-name） 
  ✅ 只有一个目录存在时用它 
  ✅ 导出脚本与 Scene 一致：dd if=分区 of=/sdcard/<分区名>.img — dd if='/dev/block/by-name/boot_a' of='/sdcard/boot_a.img'
  ✅ boot_a+boot_b → BOOT_AB（替换 ramdisk） — BOOT_AB
  ✅ 存在 recovery_a → RECOVERY_AB（直接刷 recovery） — RECOVERY_AB
  ✅ 存在 vendor_boot → UNSUPPORTED（与 Scene 相同） — UNSUPPORTED
  ✅ TWRP 脚本包含 magiskboot unpack/repack + flash_image 两个槽位 
  ✅ 重启脚本先 sync 再 reboot 
  ✅ 重启脚本不含任何写分区/删除类命令 

==============================================================
 结果: 通过 55 / 失败 0
==============================================================
```
