package com.dvstrip.player

/**
 * FFmpeg / ffprobe argument builders. Every command here was validated against a real
 * Dolby Vision P8.1 sample (Sol Levante) with desktop FFmpeg before being baked in:
 * `-c copy -bsf:v dovi_rpu=strip=1` removes RPU NALs and the DV config record losslessly,
 * leaving the HDR10 base layer (bt2020nc/smpte2084) intact.
 */
object FfCommands {

    fun probeStreams(input: String): List<String> = listOf(
        "-v", "quiet", "-print_format", "json",
        "-show_format", "-show_streams", input
    )

    /** Decodes only the first 8 frames — container-independent RPU detection. */
    fun probeFrames(input: String): List<String> = listOf(
        "-v", "quiet", "-print_format", "json",
        "-select_streams", "v:0", "-read_intervals", "%+#8",
        "-show_entries", "frame_side_data=side_data_type", input
    )

    fun strip(input: String, output: String, dropSubs: Boolean): List<String> = buildList {
        add("-y"); add("-hide_banner")
        add("-i"); add(input)
        add("-map"); add("0")
        if (dropSubs) { add("-sn"); add("-dn") }
        add("-c"); add("copy")
        add("-bsf:v"); add("dovi_rpu=strip=1")
        add(output)
    }

    /** Fallback for FFmpeg builds without the dovi_rpu bsf: drop NAL unit type 62 (RPU). */
    fun stripLegacy(input: String, output: String): List<String> = listOf(
        "-y", "-hide_banner", "-i", input, "-map", "0",
        "-c", "copy", "-bsf:v", "filter_units=remove_types=62", output
    )

    fun stripToPipe(input: String, pipe: String, dropSubs: Boolean): List<String> = buildList {
        add("-hide_banner")
        add("-i"); add(input)
        add("-map"); add("0")
        if (dropSubs) { add("-sn"); add("-dn") }
        add("-c"); add("copy")
        add("-bsf:v"); add("dovi_rpu=strip=1")
        add("-f"); add("matroska")
        add(pipe)
    }

    /** Keep MP4 in MP4 (mov_text subs can't be copied into MKV); everything else goes to MKV. */
    fun outputExtension(containerName: String?): String =
        if (containerName != null && containerName.contains("mp4")) "mp4" else "mkv"
}
