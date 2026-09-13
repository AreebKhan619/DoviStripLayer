package com.dvstrip.player

import android.content.Context
import android.net.Uri
import com.antonkarpenko.ffmpegkit.FFprobeKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MediaProbe {

    /**
     * Resolve an intent URI into something FFmpeg can open:
     * plain file path, http(s) URL, or an ffmpeg-kit SAF url for content:// URIs.
     */
    fun ffmpegInput(context: Context, uri: Uri): String = when (uri.scheme) {
        "file" -> uri.path!!
        "content" -> com.antonkarpenko.ffmpegkit.FFmpegKitConfig.getSafParameterForRead(context, uri)
        else -> uri.toString()
    }

    suspend fun probe(context: Context, uri: Uri): DvInfo = withContext(Dispatchers.IO) {
        val input = ffmpegInput(context, uri)
        val streamsJson = FFprobeKit.executeWithArguments(
            FfCommands.probeStreams(input).toTypedArray()
        ).output ?: ""
        var info = ProbeParser.parseStreams(streamsJson)

        // No config record in the container? Decode a few frames and look for in-band RPUs.
        if (!info.hasDolbyVision) {
            // SAF parameters are single-use; content URIs need a fresh one per ffmpeg run.
            val frameInput = if (uri.scheme == "content") ffmpegInput(context, uri) else input
            val framesJson = FFprobeKit.executeWithArguments(
                FfCommands.probeFrames(frameInput).toTypedArray()
            ).output ?: ""
            if (ProbeParser.framesHaveDovi(framesJson)) {
                info = info.copy(
                    hasDolbyVision = true,
                    profile = ProbeParser.guessProfile(streamsJson)
                )
            }
        }
        info
    }
}
