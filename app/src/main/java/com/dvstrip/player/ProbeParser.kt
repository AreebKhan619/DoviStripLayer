package com.dvstrip.player

import org.json.JSONObject

data class DvInfo(
    val hasDolbyVision: Boolean,
    val profile: Int?,
    val durationSec: Double?,
    val videoCodec: String?,
    val container: String?,
    val sizeBytes: Long?
)

/**
 * Pure parsing of ffprobe JSON output. No Android dependencies so it is unit-testable
 * on the JVM against fixtures captured from a real ffprobe run.
 */
object ProbeParser {

    fun parseStreams(json: String): DvInfo {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return DvInfo(false, null, null, null, null, null)

        var profile: Int? = null
        var hasDv = false
        var videoCodec: String? = null

        val streams = root.optJSONArray("streams")
        if (streams != null) {
            for (s in 0 until streams.length()) {
                val stream = streams.optJSONObject(s) ?: continue
                if (stream.optString("codec_type") != "video") continue
                if (videoCodec == null) videoCodec = stream.optString("codec_name").ifEmpty { null }

                val sideData = stream.optJSONArray("side_data_list") ?: continue
                for (i in 0 until sideData.length()) {
                    val sd = sideData.optJSONObject(i) ?: continue
                    if (sd.optString("side_data_type").startsWith("DOVI configuration")) {
                        hasDv = true
                        if (sd.has("dv_profile")) profile = sd.optInt("dv_profile")
                    }
                }
            }
        }

        val format = root.optJSONObject("format")
        val duration = format?.optString("duration")?.toDoubleOrNull()
        val container = format?.optString("format_name")?.ifEmpty { null }
        val size = format?.optString("size")?.toLongOrNull()

        return DvInfo(hasDv, profile, duration, videoCodec, container, size)
    }

    fun framesHaveDovi(json: String): Boolean {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return false
        val frames = root.optJSONArray("frames") ?: return false
        for (f in 0 until frames.length()) {
            val sideData = frames.optJSONObject(f)?.optJSONArray("side_data_list") ?: continue
            for (i in 0 until sideData.length()) {
                val type = sideData.optJSONObject(i)?.optString("side_data_type") ?: continue
                if (type.contains("Dolby Vision")) return true
            }
        }
        return false
    }

    /**
     * Heuristic when RPUs were found in frames but the container carries no DV config record:
     * a proper HDR10 base layer (bt2020nc + PQ) means profile 8 semantics (strippable);
     * anything else (IPT-C2 / unspecified colorimetry) is treated as profile 5.
     */
    fun guessProfile(streamsJson: String): Int {
        val root = runCatching { JSONObject(streamsJson) }.getOrNull() ?: return 5
        val streams = root.optJSONArray("streams") ?: return 5
        for (s in 0 until streams.length()) {
            val stream = streams.optJSONObject(s) ?: continue
            if (stream.optString("codec_type") != "video") continue
            val transfer = stream.optString("color_transfer")
            val space = stream.optString("color_space")
            return if (transfer == "smpte2084" && space == "bt2020nc") 8 else 5
        }
        return 5
    }
}
