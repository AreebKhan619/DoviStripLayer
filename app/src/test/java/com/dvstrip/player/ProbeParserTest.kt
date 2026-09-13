package com.dvstrip.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    // --- stream-level parsing ---

    @Test
    fun `plain file has no dolby vision`() {
        val info = ProbeParser.parseStreams(fixture("streams_plain.json"))
        assertFalse(info.hasDolbyVision)
        assertEquals(null, info.profile)
        assertEquals("h264", info.videoCodec)
    }

    @Test
    fun `stripped output has no dolby vision`() {
        val info = ProbeParser.parseStreams(fixture("streams_stripped.json"))
        assertFalse(info.hasDolbyVision)
    }

    @Test
    fun `duration and codec are extracted`() {
        val info = ProbeParser.parseStreams(fixture("streams_dv_mkv.json"))
        assertEquals("hevc", info.videoCodec)
        assertNotNull(info.durationSec)
        assertTrue(info.durationSec!! > 4.0 && info.durationSec!! < 6.0)
        assertTrue(info.container!!.contains("matroska"))
    }

    @Test
    fun `dovi configuration record in stream side data is detected with profile`() {
        // Synthesized from the documented ffprobe structure for a container-signaled DV file
        val json = """{"streams":[{"index":0,"codec_type":"video","codec_name":"hevc",
            "side_data_list":[{"side_data_type":"DOVI configuration record",
            "dv_version_major":1,"dv_version_minor":0,"dv_profile":8,"dv_level":6,
            "dv_bl_signal_compatible_id":1}]}],"format":{"format_name":"mov,mp4","duration":"60.0"}}"""
        val info = ProbeParser.parseStreams(json)
        assertTrue(info.hasDolbyVision)
        assertEquals(8, info.profile)
    }

    @Test
    fun `profile 5 config record is detected`() {
        val json = """{"streams":[{"index":0,"codec_type":"video","codec_name":"hevc",
            "side_data_list":[{"side_data_type":"DOVI configuration record","dv_profile":5}]}],
            "format":{"format_name":"mov,mp4"}}"""
        val info = ProbeParser.parseStreams(json)
        assertTrue(info.hasDolbyVision)
        assertEquals(5, info.profile)
    }

    @Test
    fun `real dolby p5 sample is detected as profile 5`() {
        val info = ProbeParser.parseStreams(fixture("streams_real_p5.json"))
        assertTrue(info.hasDolbyVision)
        assertEquals(5, info.profile)
        assertEquals("hevc", info.videoCodec)
    }

    @Test
    fun `real dolby p81 sample is detected as profile 8`() {
        val info = ProbeParser.parseStreams(fixture("streams_real_p81.json"))
        assertTrue(info.hasDolbyVision)
        assertEquals(8, info.profile)
        assertNotNull(info.sizeBytes)
    }

    @Test
    fun `malformed json yields safe default`() {
        val info = ProbeParser.parseStreams("not json at all")
        assertFalse(info.hasDolbyVision)
        assertEquals(null, info.profile)
    }

    // --- frame-level fallback ---

    @Test
    fun `frames with rpu side data are detected`() {
        assertTrue(ProbeParser.framesHaveDovi(fixture("frames_dv.json")))
    }

    @Test
    fun `plain frames have no rpu`() {
        assertFalse(ProbeParser.framesHaveDovi(fixture("frames_plain.json")))
    }

    @Test
    fun `stripped frames have no rpu`() {
        assertFalse(ProbeParser.framesHaveDovi(fixture("frames_stripped.json")))
    }

    // --- profile heuristic when only frame-level RPUs are found ---

    @Test
    fun `rpu with hdr10 vui guesses profile 8`() {
        assertEquals(8, ProbeParser.guessProfile(fixture("streams_dv_mkv.json")))
    }

    @Test
    fun `rpu without hdr10 vui guesses profile 5`() {
        val json = """{"streams":[{"index":0,"codec_type":"video","codec_name":"hevc",
            "color_transfer":"smpte2084","color_space":"ipt-c2"}],"format":{}}"""
        assertEquals(5, ProbeParser.guessProfile(json))
    }
}
