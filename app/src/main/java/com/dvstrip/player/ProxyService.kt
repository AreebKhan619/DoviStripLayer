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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Localhost media proxy with two modes:
 *
 * PATCH (default for direct MP4/MKV sources): serves the source byte-identical with full
 * HTTP Range support — the player seeks natively and sees the real duration — while a small
 * set of same-size byte patches (computed up front by [Mp4DvPatcher]/[MkvDvPatcher])
 * neutralizes the container's Dolby Vision signaling in flight. Lossless, zero CPU.
 *
 * HLS (fallback for adaptive inputs like m3u8/DASH): one continuous FFmpeg copy-remux
 * strips DV into a rolling local HLS window. Live-window semantics, limited seeking.
 */
class ProxyService : Service() {

    companion object {
        const val PORT = 46836
        const val EXTRA_SOURCE = "source"
        const val EXTRA_MODE = "mode"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_PATCHES = "patches"
        const val EXTRA_LENGTH = "length"
        const val EXTRA_EXT = "ext"
        const val EXTRA_MKV_FIRST_CLUSTER = "mkvFirstCluster"
        const val EXTRA_MKV_VIDEO_TRACK = "mkvVideoTrack"
        const val EXTRA_MKV_NAL_LEN = "mkvNalLen"
        const val MODE_PATCH = "patch"
        const val MODE_HLS = "hls"
        const val ACTION_STOP = "com.dvstrip.player.STOP_PROXY"
        private const val CHANNEL_ID = "dvstrip_proxy"
        private const val IDLE_TIMEOUT_MS = 10L * 60 * 1000
        private const val TAG = "DVStrip"

        fun hlsRoot(context: Context): File = File(context.cacheDir, "hls")

        fun playlistFile(context: Context, sessionId: String): File =
            File(File(hlsRoot(context), sessionId), "index.m3u8")

        fun playlistUrl(sessionId: String): String = "http://127.0.0.1:$PORT/$sessionId/index.m3u8"

        fun mediaUrl(ext: String): String = "http://127.0.0.1:$PORT/media.$ext"

        fun startHls(context: Context, sourceUri: Uri, sessionId: String) {
            val i = Intent(context, ProxyService::class.java)
                .putExtra(EXTRA_MODE, MODE_HLS)
                .putExtra(EXTRA_SOURCE, sourceUri.toString())
                .putExtra(EXTRA_SESSION_ID, sessionId)
            startFg(context, i)
        }

        fun startPatch(
            context: Context,
            sourceUri: Uri,
            patches: List<Patch>,
            length: Long,
            ext: String,
            mkvMeta: MkvMeta?
        ) {
            val i = Intent(context, ProxyService::class.java)
                .putExtra(EXTRA_MODE, MODE_PATCH)
                .putExtra(EXTRA_SOURCE, sourceUri.toString())
                .putExtra(EXTRA_PATCHES, encodePatches(patches))
                .putExtra(EXTRA_LENGTH, length)
                .putExtra(EXTRA_EXT, ext)
            if (mkvMeta != null) {
                i.putExtra(EXTRA_MKV_FIRST_CLUSTER, mkvMeta.firstClusterOffset)
                    .putExtra(EXTRA_MKV_VIDEO_TRACK, mkvMeta.videoTrackNumber)
                    .putExtra(EXTRA_MKV_NAL_LEN, mkvMeta.nalLengthSize)
            }
            startFg(context, i)
        }

        private fun startFg(context: Context, i: Intent) {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun encodePatches(patches: List<Patch>): ByteArray {
            val bos = ByteArrayOutputStream()
            DataOutputStream(bos).use { out ->
                out.writeInt(patches.size)
                for (p in patches) {
                    out.writeLong(p.offset)
                    out.writeInt(p.bytes.size)
                    out.write(p.bytes)
                }
            }
            return bos.toByteArray()
        }

        fun decodePatches(blob: ByteArray): List<Patch> =
            DataInputStream(ByteArrayInputStream(blob)).use { ins ->
                List(ins.readInt()) {
                    val offset = ins.readLong()
                    val bytes = ByteArray(ins.readInt())
                    ins.readFully(bytes)
                    Patch(offset, bytes)
                }
            }
    }

    private class PatchState(
        val source: StreamableSource,
        val patches: List<Patch>,
        val length: Long,
        val ext: String,
        val mkvMeta: MkvMeta?
    )

    private var server: ProxyServer? = null
    @Volatile private var patchState: PatchState? = null
    private var ffmpegSession: FFmpegSession? = null
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleCheck = object : Runnable {
        override fun run() {
            val s = server
            val idle = s == null ||
                (s.activeStreams.get() == 0 && System.currentTimeMillis() - s.lastRequest > IDLE_TIMEOUT_MS)
            if (idle) {
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
        startInForeground()

        // Reset whatever the previous playback session was doing.
        ffmpegSession?.cancel()
        ffmpegSession = null
        patchState = null
        hlsRoot(this).deleteRecursively()

        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_PATCH -> {
                val patches = decodePatches(intent.getByteArrayExtra(EXTRA_PATCHES) ?: ByteArray(4))
                val firstCluster = intent.getLongExtra(EXTRA_MKV_FIRST_CLUSTER, -1)
                val mkvMeta = if (firstCluster >= 0) MkvMeta(
                    patches,
                    firstCluster,
                    intent.getLongExtra(EXTRA_MKV_VIDEO_TRACK, -1),
                    intent.getIntExtra(EXTRA_MKV_NAL_LEN, 4)
                ) else null
                patchState = PatchState(
                    Sources.forUri(this, Uri.parse(source)),
                    patches,
                    intent.getLongExtra(EXTRA_LENGTH, -1),
                    intent.getStringExtra(EXTRA_EXT) ?: "mkv",
                    mkvMeta
                )
                Log.i(TAG, "patch proxy: ${patches.size} patches, length=${patchState!!.length}, rpuRewrite=${mkvMeta != null}")
                selfCheck(patchState!!.ext)
            }
            MODE_HLS -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return START_NOT_STICKY
                val sessionDir = File(hlsRoot(this), sessionId).apply { mkdirs() }
                val input = MediaProbe.ffmpegInput(this, Uri.parse(source))
                val args = FfCommands.stripToHls(
                    input,
                    File(sessionDir, "seg%05d.ts").absolutePath,
                    File(sessionDir, "index.m3u8").absolutePath
                )
                Log.i(TAG, "hls proxy ffmpeg start: session=$sessionId")
                ffmpegSession = FFmpegKit.executeWithArgumentsAsync(args.toTypedArray()) { s ->
                    Log.i(TAG, "hls proxy ffmpeg finished: rc=${s.returnCode}")
                }
            }
            else -> return START_NOT_STICKY
        }

        if (server == null) {
            server = ProxyServer().also {
                it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                Log.i(TAG, "proxy server listening on $PORT")
            }
        }
        idleHandler.removeCallbacks(idleCheck)
        idleHandler.postDelayed(idleCheck, 60_000)
        return START_STICKY
    }

    /**
     * End-to-end verification: probe the proxy's OWN output with the bundled ffprobe and log
     * whether any Dolby Vision data survives in the exact bytes the player receives.
     */
    private fun selfCheck(ext: String) {
        Thread {
            try {
                Thread.sleep(3_000) // let the player's own connections settle first
                val url = mediaUrl(ext)
                val framesJson = com.antonkarpenko.ffmpegkit.FFprobeKit.executeWithArguments(
                    FfCommands.probeFrames(url, network = true).toTypedArray()
                ).output ?: ""
                val dvLeft = ProbeParser.framesHaveDovi(framesJson)
                val streamsJson = com.antonkarpenko.ffmpegkit.FFprobeKit.executeWithArguments(
                    FfCommands.probeStreams(url, network = true).toTypedArray()
                ).output ?: ""
                val configLeft = ProbeParser.parseStreams(streamsJson).hasDolbyVision
                Log.i(TAG, "SELF-CHECK proxy output: frameRPUs=${if (dvLeft) "STILL PRESENT" else "none"} " +
                    "dvConfig=${if (configLeft) "STILL PRESENT" else "none"}")
            } catch (e: Exception) {
                Log.w(TAG, "self-check failed", e)
            }
        }.start()
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

    private inner class ProxyServer : NanoHTTPD("127.0.0.1", PORT) {

        @Volatile var lastRequest: Long = System.currentTimeMillis()
        val activeStreams = AtomicInteger(0)

        override fun serve(session: IHTTPSession): Response {
            lastRequest = System.currentTimeMillis()
            val path = session.uri.trimStart('/')
            return when {
                path.startsWith("media.") -> servePatched(session)
                path.matches(Regex("[A-Za-z0-9_\\-]+/[A-Za-z0-9_\\-]+\\.(m3u8|ts)")) && !path.contains("..") ->
                    serveHlsFile(path)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
        }

        private fun servePatched(session: IHTTPSession): Response {
            val state = patchState
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no session")
            val total = state.length
            val mime = if (state.ext == "mp4") "video/mp4" else "video/x-matroska"

            var start = 0L
            var end = total - 1
            var partial = false
            val range = session.headers["range"]
            if (range != null && range.startsWith("bytes=") && total > 0) {
                val spec = range.removePrefix("bytes=").substringBefore(',').trim()
                val dash = spec.indexOf('-')
                if (dash > 0) {
                    start = spec.substring(0, dash).toLongOrNull() ?: 0
                    spec.substring(dash + 1).toLongOrNull()?.let { end = it }
                } else if (dash == 0) {
                    val suffix = spec.substring(1).toLongOrNull() ?: 0
                    start = (total - suffix).coerceAtLeast(0)
                }
                end = end.coerceAtMost(total - 1)
                if (start > end) {
                    return newFixedLengthResponse(
                        Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "bad range"
                    ).apply { addHeader("Content-Range", "bytes */$total") }
                }
                partial = true
            }
            val count = end - start + 1
            Log.i(TAG, "serve patched: range=$start-$end/$total partial=$partial")

            // Static container patches first, then in-flight RPU NAL rewriting for MKV.
            var stream: InputStream =
                PatchedInputStream(LimitedInputStream(state.source.openAt(start), count), start, state.patches)
            state.mkvMeta?.let { stream = MkvRpuTransformer(stream, start, it) { msg -> Log.i(TAG, msg) } }
            val body: InputStream = object : FilterInputStream(stream) {
                init { activeStreams.incrementAndGet() }
                override fun close() {
                    super.close()
                    activeStreams.decrementAndGet()
                    lastRequest = System.currentTimeMillis()
                }
            }
            val status = if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK
            return newFixedLengthResponse(status, mime, body, count).apply {
                if (state.source.supportsRanges) addHeader("Accept-Ranges", "bytes")
                if (partial) addHeader("Content-Range", "bytes $start-$end/$total")
            }
        }

        private fun serveHlsFile(path: String): Response {
            val file = File(hlsRoot(this@ProxyService), path)
            if (!file.exists()) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
            val mime = if (path.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp2t"
            return newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), file.length())
                .apply { if (path.endsWith(".m3u8")) addHeader("Cache-Control", "no-cache, no-store") }
        }
    }
}

/** Caps a stream at [limit] bytes — a range response must not run past its declared end. */
class LimitedInputStream(base: InputStream, private var limit: Long) : FilterInputStream(base) {
    override fun read(): Int {
        if (limit <= 0) return -1
        val b = super.read()
        if (b >= 0) limit--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (limit <= 0) return -1
        val n = super.read(b, off, minOf(len.toLong(), limit).toInt())
        if (n > 0) limit -= n
        return n
    }
}
