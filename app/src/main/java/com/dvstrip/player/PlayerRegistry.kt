package com.dvstrip.player

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

data class PlayerApp(val label: String, val packageName: String, val activityName: String)

object PlayerRegistry {

    /** System placeholder handlers that render as "None" and can't actually play anything. */
    private val STUB_PACKAGES = setOf(
        "com.android.tv.frameworkpackagestubs",
        "com.google.android.tv.frameworkpackagestubs"
    )

    fun installedPlayers(context: Context): List<PlayerApp> {
        val pm = context.packageManager
        val probes = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("http://example.com/video.mkv"), "video/*"),
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///sdcard/video.mkv"), "video/*")
        )
        val flags = if (Build.VERSION.SDK_INT >= 23) PackageManager.MATCH_ALL else 0
        return probes
            .flatMap { pm.queryIntentActivities(it, flags) }
            .filter {
                it.activityInfo != null &&
                    it.activityInfo.packageName != context.packageName &&
                    it.activityInfo.packageName !in STUB_PACKAGES &&
                    !it.activityInfo.packageName.endsWith("frameworkpackagestubs")
            }
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
