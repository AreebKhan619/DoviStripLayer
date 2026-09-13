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
import androidx.core.app.NotificationCompat
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFmpegSession
import fi.iki.elonen.NanoHTTPD
import java.io.FileInputStream

/**
 * Foreground service hosting a localhost HTTP server. Each incoming request spawns a fresh
 * FFmpeg copy-remux session that strips Dolby Vision and writes streamable Matroska into a
 * named pipe, which is piped straight out as the HTTP response body. Non-seekable by design.
 */
class ProxyService : Service() {

    companion object {
        const val PORT = 46836
        const val EXTRA_SOURCE = "source"
        const val ACTION_STOP = "com.dvstrip.player.STOP_PROXY"
        private const val CHANNEL_ID = "dvstrip_proxy"
        private const val IDLE_TIMEOUT_MS = 10L * 60 * 1000

        fun streamUrl(): String = "http://127.0.0.1:$PORT/stream.mkv"

        fun start(context: Context, sourceUri: Uri) {
            val i = Intent(context, ProxyService::class.java).putExtra(EXTRA_SOURCE, sourceUri.toString())
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }

    private var server: StripServer? = null
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleCheck = object : Runnable {
        override fun run() {
            val s = server
            if (s == null || (!s.hasActiveConnection && System.currentTimeMillis() - s.lastActivity > IDLE_TIMEOUT_MS)) {
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

        startInForeground()

        server?.stop()
        server = StripServer(this, Uri.parse(source)).also {
            it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }
        idleHandler.removeCallbacks(idleCheck)
        idleHandler.postDelayed(idleCheck, 60_000)
        return START_STICKY
    }

    override fun onDestroy() {
        idleHandler.removeCallbacks(idleCheck)
        server?.stop()
        server = null
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

    private inner class StripServer(
        private val context: Context,
        private val sourceUri: Uri
    ) : NanoHTTPD("127.0.0.1", PORT) {

        @Volatile var lastActivity: Long = System.currentTimeMillis()
        @Volatile var hasActiveConnection: Boolean = false
        @Volatile private var currentSession: FFmpegSession? = null

        override fun serve(session: IHTTPSession): Response {
            lastActivity = System.currentTimeMillis()

            // Only one consumer at a time: a new request supersedes the previous session.
            currentSession?.cancel()

            val input = MediaProbe.ffmpegInput(context, sourceUri)
            val pipe = FFmpegKitConfig.registerNewFFmpegPipe(context)
            val args = FfCommands.stripToPipe(input, pipe, dropSubs = false)

            currentSession = FFmpegKit.executeWithArgumentsAsync(args.toTypedArray()) {
                FFmpegKitConfig.closeFFmpegPipe(pipe)
            }

            hasActiveConnection = true
            val body = object : FileInputStream(pipe) {
                override fun close() {
                    super.close()
                    hasActiveConnection = false
                    lastActivity = System.currentTimeMillis()
                    currentSession?.cancel()
                }
            }
            val resp = newChunkedResponse(Response.Status.OK, "video/x-matroska", body)
            resp.addHeader("Accept-Ranges", "none")
            return resp
        }
    }
}
