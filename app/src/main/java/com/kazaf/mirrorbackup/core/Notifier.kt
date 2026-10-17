package com.kazaf.mirrorbackup.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** 备份完成通知（Android 13+ 需 POST_NOTIFICATIONS） */
object Notifier {

    private const val CHANNEL_ID = "mirrorbackup_backup"
    private const val CHANNEL_NAME = "备份进度与结果"

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { description = "镜像备份任务完成时提醒" }
                )
            }
        }
    }

    private fun canNotify(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true

    fun notifyDone(context: Context, imageCount: Int, durationSec: Long, location: String) {
        if (!canNotify(context)) return
        try {
            ensureChannel(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pi = launch?.let {
                PendingIntent.getActivity(
                    context, 0, it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("镜像备份完成 ✓")
                .setContentText("$imageCount 个镜像提取成功 · 耗时 ${durationSec}s · 已保存到「镜像备份」文件夹")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "$imageCount 个镜像提取成功\n耗时 ${durationSec}s\n位置：$location"
                    )
                )
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(1001, n)
        } catch (_: Exception) {
        }
    }

    fun notifyFailed(context: Context, reason: String) {
        if (!canNotify(context)) return
        try {
            ensureChannel(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("镜像备份失败")
                .setContentText(reason)
                .setAutoCancel(true)
                .build()
            nm.notify(1002, n)
        } catch (_: Exception) {
        }
    }
}
