package com.dvstrip.player

import android.content.Context
import android.net.Uri
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.ReturnCode
import java.io.File
import java.security.MessageDigest

/**
 * Lossless temp-file remux with progress. Strategy chain, each attempt only on failure of
 * the previous: dovi_rpu bsf → dovi_rpu without subs/data → filter_units NAL-62 fallback.
 * After success the output is quickly re-probed; DV still present counts as failure.
 */
object StripEngine {

    fun cacheDir(context: Context): File = File(context.cacheDir, "stripped").apply { mkdirs() }

    fun cacheFile(context: Context, uri: Uri, sizeBytes: Long?, ext: String): File {
        val key = MessageDigest.getInstance("MD5")
            .digest("$uri|$sizeBytes".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir(context), "$key.$ext")
    }

    fun okMarker(out: File): File = File(out.parentFile, out.name + ".ok")

    fun cleanupCache(context: Context, keep: File? = null, maxAgeMs: Long = 24L * 3600 * 1000) {
        val now = System.currentTimeMillis()
        cacheDir(context).listFiles()?.forEach { f ->
            val isKept = keep != null && (f == keep || f.name == keep.name + ".ok")
            if (!isKept && now - f.lastModified() > maxAgeMs) f.delete()
        }
    }

    interface Listener {
        fun onProgress(percent: Int)
        fun onDone(success: Boolean, message: String?)
    }

    class Job internal constructor(@Volatile internal var session: FFmpegSession?) {
        @Volatile internal var cancelled = false
        fun cancel() {
            cancelled = true
            session?.cancel()
        }
    }

    fun remuxToFile(
        context: Context,
        uri: Uri,
        output: File,
        durationSec: Double?,
        listener: Listener
    ): Job {
        val job = Job(null)
        val attempts = listOf<(String) -> List<String>>(
            { input -> FfCommands.strip(input, output.absolutePath, dropSubs = false) },
            { input -> FfCommands.strip(input, output.absolutePath, dropSubs = true) },
            { input -> FfCommands.stripLegacy(input, output.absolutePath) }
        )

        fun run(attemptIndex: Int) {
            if (job.cancelled) {
                output.delete()
                listener.onDone(false, "cancelled")
                return
            }
            // content:// SAF parameters are single-use — regenerate per attempt.
            val input = MediaProbe.ffmpegInput(context, uri)
            job.session = FFmpegKit.executeWithArgumentsAsync(
                attempts[attemptIndex](input).toTypedArray(),
                { session ->
                    val ok = ReturnCode.isSuccess(session.returnCode)
                    when {
                        job.cancelled -> {
                            output.delete()
                            listener.onDone(false, "cancelled")
                        }
                        ok && outputIsClean(output) -> {
                            okMarker(output).createNewFile()
                            listener.onDone(true, null)
                        }
                        attemptIndex + 1 < attempts.size -> {
                            output.delete()
                            run(attemptIndex + 1)
                        }
                        else -> {
                            output.delete()
                            listener.onDone(false, session.failStackTrace ?: "FFmpeg failed")
                        }
                    }
                },
                { /* log callback unused */ },
                { stats ->
                    if (durationSec != null && durationSec > 0) {
                        val pct = ((stats.time / 1000.0) / durationSec * 100).toInt()
                        listener.onProgress(pct.coerceIn(0, 100))
                    }
                }
            )
        }
        run(0)
        return job
    }

    /** Post-strip safety net: stream-level probe of the output must show no DV config. */
    private fun outputIsClean(output: File): Boolean {
        val json = com.antonkarpenko.ffmpegkit.FFprobeKit.executeWithArguments(
            FfCommands.probeStreams(output.absolutePath).toTypedArray()
        ).output ?: return true
        return !ProbeParser.parseStreams(json).hasDolbyVision
    }
}
