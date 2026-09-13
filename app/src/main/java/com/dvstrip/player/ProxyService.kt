package com.dvstrip.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegSession
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

/**
 * Foreground service that strips Dolby Vision from a source in realtime into a rolling local
 * HLS window (one continuous FFmpeg session), served by a stateless localhost file server.
 * Serving plain files makes player connection habits (probe + reopen + parallel requests)
 * harmless — unlike a pipe, nothing restarts when a connection closes.
 */
class ProxyService : Service() {

    companion object {
        const val PORT = 46836
        const val EXTRA_SOURCE = "source"
        const val EXTRA_SESSION_ID = "sessionId"
        const val ACTION_STOP = "com.dvstrip.player.STOP_PROXY"
        private const val CHANNEL_ID = "dvstrip_proxy"
        private const val IDLE_TIMEOUT_MS = 10L * 60 * 1000
        private const val TAG = "DVStrip"

        fun hlsRoot(context: Context): File = File(context.cacheDir, "hls")

        fun playlistFile(context: Context, sessionId: String): File =
            File(File(hlsRoot(context), sessionId), "index.m3u8")

        fun playlistUrl(sessionId: String): String = "http://127.0.0.1:$PORT/$sessionId/index.m3u8"

        fun start(context: Context, sourceUri: Uri, sessionId: String) {
            val i = Intent(context, ProxyService::class.java)
                .putExtra(EXTRA_SOURCE, sourceUri.toString())
                .putExtra(EXTRA_SESSION_ID, sessionId)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }

    private var server: HlsServer? = null
    private var ffmpegSession: FFmpegSession? = null
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleCheck = object : Runnable {
        override fun run() {
            val s = server
            if (s == null || System.currentTimeMillis() - s.lastRequest > IDLE_TIMEOUT_MS) {
                Log.i(TAG, "proxy idle, stopping")
                stopSelf()
            } else {
                idleHandler.postDelayed(this, 60_000)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val source = intent?.getStringExtra(EXTRA_SOURCE) ?: return START_NOT_STICKY
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return START_NOT_STICKY

        startInForeground()

        // Fresh state: previous ffmpeg + all old session dirs go away.
        ffmpegSession?.cancel()
        hlsRoot(this).deleteRecursively()
        val sessionDir = File(hlsRoot(this), sessionId).apply { mkdirs() }

        val input = MediaProbe.ffmpegInput(this, Uri.parse(source))
        val args = FfCommands.stripToHls(
            input,
            File(sessionDir, "seg%05d.ts").absolutePath,
            File(sessionDir, "index.m3u8").absolutePath
        )
        Log.i(TAG, "proxy ffmpeg start: session=$sessionId")
        ffmpegSession = FFmpegKit.executeWithArgumentsAsync(args.toTypedArray()) { s ->
            Log.i(TAG, "proxy ffmpeg finished: rc=${s.returnCode}")
        }

        if (server == null) {
            server = HlsServer().also {
                it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                Log.i(TAG, "proxy server listening on $PORT")
            }
        }
        idleHandler.removeCallbacks(idleCheck)
        idleHandler.postDelayed(idleCheck, 60_000)
        return START_STICKY
    }

    override fun onDestroy() {
        idleHandler.removeCallbacks(idleCheck)
        ffmpegSession?.cancel()
        server?.stop()
        server = null
        hlsRoot(this).deleteRecursively()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.proxy_notification_title))
            .setContentText(getString(R.string.proxy_notification_text))
            .setOngoing(true)
            .addAction(0, getString(R.string.cancel), stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, notification)
        }
    }

    private inner class HlsServer : NanoHTTPD("127.0.0.1", PORT) {

        @Volatile var lastRequest: Long = System.currentTimeMillis()

        override fun serve(session: IHTTPSession): Response {
            lastRequest = System.currentTimeMillis()
            val path = session.uri.trimStart('/')
            if (path.contains("..") || !path.matches(Regex("[A-Za-z0-9_\\-]+/[A-Za-z0-9_\\-]+\\.(m3u8|ts)"))) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
            val file = File(hlsRoot(this@ProxyService), path)
            if (!file.exists()) {
                Log.w(TAG, "proxy 404: $path")
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
            val mime = if (path.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp2t"
            val resp = newFixedLengthResponse(
                Response.Status.OK, mime, FileInputStream(file), file.length()
            )
            // The playlist mutates as the window rolls — never let the player cache it.
            if (path.endsWith(".m3u8")) resp.addHeader("Cache-Control", "no-cache, no-store")
            return resp
        }
    }
}
