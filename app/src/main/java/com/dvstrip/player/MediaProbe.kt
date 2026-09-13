package com.dvstrip.player

import android.content.Context
import android.net.Uri
import android.util.Log
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

    private const val TAG = "DVStrip"

    suspend fun probe(context: Context, uri: Uri): DvInfo = withContext(Dispatchers.IO) {
        val network = uri.scheme == "http" || uri.scheme == "https"
        val input = ffmpegInput(context, uri)
        Log.i(TAG, "probe start: scheme=${uri.scheme} network=$network")

        val streamsSession = FFprobeKit.executeWithArguments(
            FfCommands.probeStreams(input, network).toTypedArray()
        )
        val streamsJson = streamsSession.output ?: ""
        Log.i(TAG, "streams probe done: rc=${streamsSession.returnCode} outputLen=${streamsJson.length}")
        var info = ProbeParser.parseStreams(streamsJson)

        // No config record in the container? Decode a few frames and look for in-band RPUs.
        if (!info.hasDolbyVision) {
            // SAF parameters are single-use; content URIs need a fresh one per ffmpeg run.
            val frameInput = if (uri.scheme == "content") ffmpegInput(context, uri) else input
            val framesSession = FFprobeKit.executeWithArguments(
                FfCommands.probeFrames(frameInput, network).toTypedArray()
            )
            val framesJson = framesSession.output ?: ""
            Log.i(TAG, "frames probe done: rc=${framesSession.returnCode} outputLen=${framesJson.length}")
            if (ProbeParser.framesHaveDovi(framesJson)) {
                info = info.copy(
                    hasDolbyVision = true,
                    profile = ProbeParser.guessProfile(streamsJson)
                )
            }
        }
        Log.i(TAG, "probe result: $info")
        info
    }
}
