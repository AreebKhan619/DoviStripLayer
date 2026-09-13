package com.dvstrip.player

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

data class PlayerApp(val label: String, val packageName: String, val activityName: String)

object PlayerRegistry {

    fun installedPlayers(context: Context): List<PlayerApp> {
        val pm = context.packageManager
        val probes = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("http://example.com/video.mkv"), "video/*"),
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///sdcard/video.mkv"), "video/*")
        )
        val flags = if (Build.VERSION.SDK_INT >= 23) PackageManager.MATCH_ALL else 0
        return probes
            .flatMap { pm.queryIntentActivities(it, flags) }
            .filter { it.activityInfo != null && it.activityInfo.packageName != context.packageName }
            .map {
                PlayerApp(
                    it.loadLabel(pm).toString(),
                    it.activityInfo.packageName,
                    it.activityInfo.name
                )
            }
            .distinctBy { "${it.packageName}/${it.activityName}" }
            .sortedBy { it.label.lowercase() }
    }
}
