package com.dvstrip.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfCommandsTest {

    @Test
    fun `probe streams command matches validated form`() {
        assertEquals(
            listOf(
                "-v", "quiet", "-print_format", "json",
                "-show_format", "-show_streams", "in.mkv"
            ),
            FfCommands.probeStreams("in.mkv")
        )
    }

    @Test
    fun `probe frames command decodes only first frames`() {
        assertEquals(
            listOf(
                "-v", "quiet", "-print_format", "json",
                "-select_streams", "v:0", "-read_intervals", "%+#8",
                "-show_entries", "frame_side_data=side_data_type", "in.mkv"
            ),
            FfCommands.probeFrames("in.mkv")
        )
    }

    @Test
    fun `network probe includes rw_timeout before input`() {
        val cmd = FfCommands.probeStreams("http://x/a.mkv", network = true)
        val i = cmd.indexOf("-rw_timeout")
        assertTrue(i in 0 until cmd.lastIndex)
        assertEquals("20000000", cmd[i + 1])
        assertEquals("http://x/a.mkv", cmd.last())
        assertTrue(FfCommands.probeFrames("http://x/a.mkv", network = true).contains("-rw_timeout"))
    }

    @Test
    fun `local probe has no rw_timeout`() {
        assertFalse(FfCommands.probeStreams("/sdcard/a.mkv").contains("-rw_timeout"))
    }

    @Test
    fun `strip command uses dovi_rpu bsf with map 0 copy`() {
        val cmd = FfCommands.strip("in.mkv", "out.mkv", dropSubs = false)
        assertEquals(
            listOf(
                "-y", "-hide_banner", "-i", "in.mkv", "-map", "0",
                "-c", "copy", "-bsf:v", "dovi_rpu=strip=1", "out.mkv"
            ),
            cmd
        )
    }

    @Test
    fun `strip retry variant drops subtitle and data streams`() {
        val cmd = FfCommands.strip("in.mkv", "out.mkv", dropSubs = true)
        assertTrue(cmd.containsAll(listOf("-sn", "-dn")))
        assertTrue(cmd.contains("dovi_rpu=strip=1"))
    }

    @Test
    fun `legacy strip fallback removes nal unit 62`() {
        val cmd = FfCommands.stripLegacy("in.mkv", "out.mkv")
        assertTrue(cmd.contains("filter_units=remove_types=62"))
        assertFalse(cmd.contains("dovi_rpu=strip=1"))
    }

    @Test
    fun `pipe command muxes streamable matroska`() {
        val cmd = FfCommands.stripToPipe("http://example.com/a.mkv", "/pipe1", dropSubs = false)
        val i = cmd.indexOf("-f")
        assertEquals("matroska", cmd[i + 1])
        assertEquals("/pipe1", cmd.last())
        assertTrue(cmd.contains("dovi_rpu=strip=1"))
        assertFalse(cmd.contains("-y"))
    }

    @Test
    fun `output extension follows container`() {
        assertEquals("mp4", FfCommands.outputExtension("mov,mp4,m4a,3gp,3g2,mj2"))
        assertEquals("mkv", FfCommands.outputExtension("matroska,webm"))
        assertEquals("mkv", FfCommands.outputExtension("mpegts"))
        assertEquals("mkv", FfCommands.outputExtension(null))
    }
}
