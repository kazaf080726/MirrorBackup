package com.kazaf.mirrorbackup.core.pure

/**
 * 刷入脚本生成（1:1 复刻 Scene / ActivityImg 的写入语义，源码见 theme-ref/scene-apk）。
 *
 * Scene 的写入流程（`res/raw/install_image` 内的 flash_image 函数）：
 *   1. 镜像以 .gz 结尾 → `gzip -d < 镜像`，否则 `cat 镜像`
 *   2. 目标是块设备：
 *        - 镜像大小 > 分区大小（stat -c %s vs blockdev --getsize64）→ 返回 1
 *        - `blockdev --setrw` 后仍为只读（--getro == 1）→ 返回 2
 *        - 写入：镜像 | cat - /dev/zero > 分区   ← 镜像写完后用 0 填满整个分区
 *   3. 目标是字符设备：flash_eraseall + nandwrite -p
 *   4. 其他（普通文件）：直接写入
 *   5. 返回 0
 * Scene 调用：`flash_image '<镜像路径>' '<分区节点>'`。
 *
 * 我们保持同样的命令、同样的顺序、同样的返回码（0 成功 / 1 镜像过大 / 2 只读），
 * 只是把脚本改成我们自己的写法（不逐字拷贝），并额外把返回码 echo 出来便于解析。
 */
object FlashScript {

    /** Scene bootdevice.sh 的分区目录优先级（后命中的覆盖先命中的，与 Scene 一致） */
    val DEVICE_DIRS = listOf(
        "/dev/block/bootdevice/by-name",
        "/dev/block/by-name",
        "/dev/block/platform/bootdevice/by-name"
    )

    /** 与 Scene 相同：目录列表里最后一个存在的目录生效 */
    fun resolveDeviceRoot(existingDirs: Collection<String>): String? =
        DEVICE_DIRS.lastOrNull { it in existingDirs }

    /**
     * flash_image 函数体（语义与 Scene 的 install_image 一致）：
     * $1 = 镜像路径，$2 = 目标节点；返回 0/1/2。
     */
    fun functionBody(): String = """
        flash_image() {
          img="${'$'}1"
          dst="${'$'}2"
          case "${'$'}img" in
            *.gz) SRCFMT="gzip -dc < '${'$'}img' 2>/dev/null" ;;
            *)    SRCFMT="cat '${'$'}img'" ;;
          esac
          if [ -b "${'$'}dst" ]; then
            isz=$(stat -c '%s' "${'$'}img")
            dsz=$(blockdev --getsize64 "${'$'}dst")
            if [ "${'$'}isz" -gt "${'$'}dsz" ]; then
              echo "FLASH_ERROR=too_large img=${'$'}isz dev=${'$'}dsz" >&2
              return 1
            fi
            blockdev --setrw "${'$'}dst"
            ro=$(blockdev --getro "${'$'}dst")
            if [ "${'$'}ro" -eq 1 ]; then
              echo "FLASH_ERROR=read_only" >&2
              return 2
            fi
            eval "${'$'}SRCFMT" | cat - /dev/zero > "${'$'}dst" 2>/dev/null
          elif [ -c "${'$'}dst" ]; then
            flash_eraseall "${'$'}dst" >&2
            eval "${'$'}SRCFMT" | nandwrite -p "${'$'}dst" - >&2
          else
            echo "- Not block or char device, storing image"
            eval "${'$'}SRCFMT" > "${'$'}dst" 2>/dev/null
          fi
          return 0
        }
    """.trimIndent()

    /** 安全引号：只允许 /dev/block/... 与常规路径字符 */
    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun safeImagePath(path: String): Boolean =
        path.length in 2..4096 && !path.contains('\n') && !path.contains('\r') && !path.contains("'")

    /**
     * 完整刷入脚本：函数 + 调用 + 返回码。
     * 返回 null 表示路径不安全（拒绝生成）。
     */
    fun flashScript(imagePath: String, target: FlashPartition): String? {
        if (!FlashPlanner.safeDevicePath(target.path)) return null
        if (!safeImagePath(imagePath)) return null
        val f = functionBody()
        // 末尾 echo FLASH_EXIT 便于解析；exit 保留真实返回码（su 的 exit code 即 0/1/2）
        return """
            $f
            flash_image ${q(imagePath)} ${q(target.path)}
            __rc=${'$'}?
            echo "FLASH_EXIT=${'$'}__rc"
            exit ${'$'}__rc
        """.trimIndent()
    }

    /**
     * 执行前自检：确认生成的脚本确实会写到「选定分区」，且用的是约定算法。
     * 抓：写错分区 / 多路径 / 混入读指令 / 缺少 zero 填充 / 缺少 setrw。
     */
    fun verifyFlashScript(script: String, imagePath: String, target: FlashPartition): CommandCheck {
        val reasons = mutableListOf<String>()
        val paths = Regex("/dev/block/[A-Za-z0-9_./-]+").findAll(script).map { it.value }.toList()
        when {
            paths.isEmpty() -> reasons += "脚本里没有设备路径"
            paths.size > 1 -> reasons += "脚本里出现多个设备路径：${paths.joinToString()}"
            paths[0] != target.path -> reasons += "脚本目标 ${paths[0]} 与选定分区 ${target.path} 不一致"
        }
        if (!script.contains("flash_image")) reasons += "脚本缺少 flash_image 调用"
        if (!script.contains(imagePath)) reasons += "脚本里找不到镜像路径"
        if (!script.contains("blockdev --setrw")) reasons += "缺少 blockdev --setrw（未按 Scene 逻辑解除只读）"
        if (!script.contains("cat - /dev/zero")) reasons += "缺少 cat - /dev/zero（未按 Scene 逻辑零填充）"
        if (!script.contains("blockdev --getsize64")) reasons += "缺少分区大小检查"
        if (!script.contains("stat -c '%s'")) reasons += "缺少镜像大小检查"
        return CommandCheck(reasons.isEmpty(), reasons)
    }

    /** 分区镜像导出（Scene：dd if='<分区>' of='/sdcard/<分区名>.img'） */
    fun exportScript(target: FlashPartition, outPath: String): String? {
        if (!FlashPlanner.safeDevicePath(target.path)) return null
        if (!safeImagePath(outPath)) return null
        return """
            dd if=${q(target.path)} of=${q(outPath)}
            __rc=${'$'}?
            sync
            echo "EXPORT_EXIT=${'$'}__rc"
            exit ${'$'}__rc
        """.trimIndent()
    }

    /** Scene 的 TWRP 注入模式判定（twrp_mode.sh） */
    fun twrpMode(partitionNames: Set<String>): TwrpMode = when {
        "recovery_a" in partitionNames -> TwrpMode.RECOVERY_AB
        "vendor_boot" in partitionNames -> TwrpMode.UNSUPPORTED
        "boot_a" in partitionNames && "boot_b" in partitionNames -> TwrpMode.BOOT_AB
        else -> TwrpMode.UNSUPPORTED
    }

    /**
     * TWRP 注入脚本（复刻 twrp_install.sh 的 boot_ab / recovery_ab）：
     * - boot_ab：magiskboot 解包 twrp 与 boot_a/boot_b，把 twrp 的 ramdisk 换进 boot，repack 后各刷一次
     * - recovery_ab：把 twrp.img 直接刷进 recovery_a / recovery_b
     * 任何一步返回码 1/2 都会打印与 Scene 相同的中文错误。
     */
    fun twrpScript(
        twrpPath: String,
        deviceRoot: String,
        mode: TwrpMode,
        tempDir: String,
        magiskboot: String = "/data/adb/magisk/magiskboot"
    ): String? {
        if (!safeImagePath(twrpPath)) return null
        if (!deviceRoot.startsWith("/dev/block/")) return null
        val f = functionBody()
        val common = """
            $f
            TWRP=${q(twrpPath)}
            ROOT=${q(deviceRoot)}
            MB=${q(magiskboot)}
            TMP=${q(tempDir)}
        """.trimIndent()

        val body = when (mode) {
            TwrpMode.BOOT_AB -> """
                patch_boot() {
                  slot=${'$'}1
                  blk="${'$'}ROOT/${'$'}slot"
                  [ -b "${'$'}blk" ] || { echo "missing ${'$'}blk" >&2; return 3; }
                  wd="${'$'}TMP/twrp-patch/${'$'}slot"
                  rm -rf "${'$'}wd"; mkdir -p "${'$'}wd/twrp" "${'$'}wd/${'$'}slot
                  cp "${'$'}TWRP" "${'$'}wd/twrp/twrp.img"
                  cat "${'$'}blk" > "${'$'}wd/${'$'}slot/${'$'}slot.img"
                  ( cd "${'$'}wd/twrp" && "${'$'}MB" unpack twrp.img >&2 ) || return 3
                  ( cd "${'$'}wd/${'$'}slot" && "${'$'}MB" unpack ${'$'}slot.img >&2 ) || return 3
                  tr_ram=$(ls "${'$'}wd/twrp" | grep ramdisk | head -n1)
                  bt_ram=$(ls "${'$'}wd/${'$'}slot" | grep ramdisk | head -n1)
                  [ -n "${'$'}tr_ram" ] && [ -n "${'$'}bt_ram" ] || { echo '错误: ramdisk 未找到!' >&2; return 3; }
                  cp "${'$'}wd/twrp/${'$'}tr_ram" "${'$'}wd/${'$'}slot/${'$'}bt_ram"
                  ( cd "${'$'}wd/${'$'}slot" && "${'$'}MB" repack ${'$'}slot.img ${'$'}slot""_twrp.img" >&2 ) || return 3
                  flash_image "${'$'}wd/${'$'}slot/${'$'}slot""_twrp.img" "${'$'}blk"
                  return ${'$'}?
                }
                patch_boot boot_a || exit ${'$'}?
                patch_boot boot_b || exit ${'$'}?
                rm -rf "${'$'}TMP/twrp-patch" 2>/dev/null
            """.trimIndent()

            TwrpMode.RECOVERY_AB -> """
                for slot in recovery_a recovery_b; do
                  blk="${'$'}ROOT/${'$'}slot"
                  [ -b "${'$'}blk" ] || { echo "missing ${'$'}blk" >&2; exit 3; }
                  flash_image "${'$'}TWRP" "${'$'}blk"
                  rc=${'$'}?
                  if [ "${'$'}rc" -eq 0 ]; then echo "flash ${'$'}slot success."
                  elif [ "${'$'}rc" -eq 1 ]; then echo '镜像过大无法写入分区' >&2; exit 1
                  elif [ "${'$'}rc" -eq 2 ]; then echo '目标分区是只读的' >&2; exit 2
                  else echo '写入失败' >&2; exit 3; fi
                done
            """.trimIndent()

            TwrpMode.UNSUPPORTED -> return null
        }
        return "$common\n$body\necho \"TWRP_EXIT=${'$'}?\""
    }
}

enum class TwrpMode { BOOT_AB, RECOVERY_AB, UNSUPPORTED }
