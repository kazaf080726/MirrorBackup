package com.kazaf.mirrorbackup.core

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * su 探测与 root 命令执行。
 *
 * 规则1-1：自动探测 su 路径，支持 Magisk / KernelSU / APatch 等主流方案分支，
 * 最后以 PATH 兜底。所有特权命令均通过 su 执行；本应用自身不自动 ROOT，只做检测。
 */
object SuManager {

    data class SuCheck(
        val found: Boolean,
        val path: String?,
        val rootOk: Boolean,
        val detail: String
    )

    private val candidates = listOf(
        "/data/adb/su",           // Magisk
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/su/bin/su",
        "/data/adb/ksu/bin/su",   // KernelSU
        "/data/adb/ksu/bin/ksu",  // KernelSU(部分版本)
        "/data/adb/ap/bin/su",    // APatch
        "/data/adb/apd/bin/su",   // APatch(旧布局)
        "/data/adb/ksud/bin/su"   // KernelSU(分支)
    )

    fun findSu(): String? {
        for (p in candidates) {
            val f = File(p)
            if (f.exists() && f.canExecute()) return p
        }
        return findSuViaPath()
    }

    /** PATH 兜底探测（部分 root 方案仅将 su 挂载到 PATH） */
    private fun findSuViaPath(): String? {
        return try {
            val p = ProcessBuilder("which", "su").start()
            val out = p.inputStream.bufferedReader().use { it.readText().trim() }
            p.waitFor(5, TimeUnit.SECONDS)
            out.lines().firstOrNull { it.startsWith("/") && File(it).canExecute() }
        } catch (e: Exception) {
            null
        }
    }

    /** 规则1-1：校验 su 可用且拥有完整 root 权限（uid=0） */
    fun checkRoot(): SuCheck {
        val path = findSu()
        if (path == null) {
            return SuCheck(false, null, false, "未找到 su 二进制（已探测 Magisk/KernelSU/APatch 常见路径与 PATH）")
        }
        return try {
            val r = exec("id", 8_000)
            val uid0 = r.exitCode == 0 && r.stdout.contains("uid=0")
            SuCheck(true, path, uid0, r.stdout.replace("\n", " ").trim())
        } catch (e: Exception) {
            SuCheck(true, path, false, "su 执行异常: ${e.message}")
        }
    }

    class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)

    /** 执行单次命令并捕获输出（并发消费 stdout/stderr，避免管道死锁） */
    fun exec(cmd: String, timeoutMs: Long = 30_000): ShellResult {
        val proc = open(cmd)
        val finished = proc.waitForExit(timeoutMs)
        if (!finished) {
            proc.destroy()
            return ShellResult(-100, "", "命令超时(${timeoutMs}ms)")
        }
        return ShellResult(proc.exitCode(), proc.stdoutText(), proc.stderrText())
    }

    /** 打开长驻 shell 进程（用于流式 dd / 进度轮询 / stdin 写入） */
    fun open(cmd: String): ShellProcess {
        val su = findSu() ?: throw IllegalStateException("su 不可用")
        val pb = ProcessBuilder(su, "-c", cmd)
        val p = pb.start()
        return ShellProcess(p)
    }

    class ShellProcess(private val p: Process) {

        private val outBuf = StringBuilder()
        private val errBuf = StringBuilder()
        private val t1 = readerThread(p.inputStream, outBuf)
        private val t2 = readerThread(p.errorStream, errBuf)

        private fun readerThread(s: InputStream, sb: StringBuilder): Thread = Thread {
            try {
                s.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        sb.append(buf, 0, n)
                    }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }

        init {
            t1.start()
            t2.start()
        }

        fun isAlive(): Boolean = p.isAlive

        fun exitCode(): Int = if (p.isAlive) -1 else p.exitValue()

        /** 等待读取线程把管道残留数据全部读尽（避免输出被截断） */
        private fun joinReaders() {
            try {
                t1.join(2000)
            } catch (_: Exception) {
            }
            try {
                t2.join(2000)
            } catch (_: Exception) {
            }
        }

        fun stdoutText(): String {
            joinReaders()
            return outBuf.toString()
        }

        fun stderrText(): String {
            joinReaders()
            return errBuf.toString()
        }

        fun stdin(): java.io.OutputStream = p.outputStream

        fun closeStdin() {
            try {
                p.outputStream.close()
            } catch (_: Exception) {
            }
        }

        fun waitForExit(timeoutMs: Long): Boolean {
            return try {
                if (p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    t1.join(500)
                    t2.join(500)
                    true
                } else {
                    false
                }
            } catch (e: InterruptedException) {
                false
            }
        }

        fun destroy() {
            try {
                p.destroy()
            } catch (_: Exception) {
            }
        }
    }

    /** shell 单引号安全转义 */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
