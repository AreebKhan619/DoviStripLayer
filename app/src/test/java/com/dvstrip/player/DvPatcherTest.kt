package com.dvstrip.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class ByteSource(private val data: ByteArray) : RandomAccessSource {
    override val length: Long get() = data.size.toLong()
    override fun readFully(offset: Long, size: Int): ByteArray {
        val end = minOf(data.size.toLong(), offset + size).toInt()
        return data.copyOfRange(offset.toInt(), end)
    }
}

class DvPatcherTest {

    private fun fixtureBytes(name: String): ByteArray =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readBytes()

    private fun applyPatches(data: ByteArray, patches: List<Patch>): ByteArray {
        val out = data.copyOf()
        for (p in patches) for (i in p.bytes.indices) out[(p.offset + i).toInt()] = p.bytes[i]
        return out
    }

    // --- MP4 ---

    @Test
    fun `dvh1 mp4 gets sample entry renamed and dvvC freed`() {
        val data = fixtureBytes("p81_dvh1.mp4")
        val patches = Mp4DvPatcher.findPatches(ByteSource(data))
        assertEquals(2, patches.size)

        val renames = patches.filter { String(it.bytes) == "hvc1" }
        val frees = patches.filter { String(it.bytes) == "free" }
        assertEquals(1, renames.size)
        assertEquals(1, frees.size)
        // Each patch must land exactly on the original magic it replaces.
        assertEquals("dvh1", String(data, renames[0].offset.toInt(), 4))
        assertEquals("dvvC", String(data, frees[0].offset.toInt(), 4))

        val patched = applyPatches(data, patches)
        assertTrue(String(patched).let { !it.contains("dvh1") && !it.contains("dvvC") })
        File("build/patched").mkdirs()
        File("build/patched/p81_patched.mp4").writeBytes(patched)
    }

    @Test
    fun `hev1 mp4 with dvvC only frees the config box`() {
        val data = fixtureBytes("p81_hev1_prefix.bin")
        val patches = Mp4DvPatcher.findPatches(ByteSource(data))
        assertEquals(1, patches.size)
        assertEquals("free", String(patches[0].bytes))
        assertEquals("dvvC", String(data, patches[0].offset.toInt(), 4))
    }

    @Test
    fun `plain mp4 without dv yields no patches`() {
        // The stripped fixture from Task 1 has no DV config — reuse the mkv-plain instead:
        val data = fixtureBytes("p81_dvh1.mp4")
        val prePatched = applyPatches(data, Mp4DvPatcher.findPatches(ByteSource(data)))
        assertEquals(0, Mp4DvPatcher.findPatches(ByteSource(prePatched)).size)
    }

    // --- MKV ---

    @Test
    fun `analyze detects the video track colour element`() {
        // The fixture was muxed by ffmpeg, which writes a Colour element from the HDR VUI.
        val meta = MkvDvPatcher.analyze(ByteSource(fixtureBytes("p81_dv.mkv")))!!
        assertTrue(meta.hasColourElement)
        assertFalse(meta.colourInjected) // already present -> nothing to inject
    }

    @Test
    fun `no-colour DV mkv gets a same-length colour element injected`() {
        val data = fixtureBytes("p81_nocolour.mkv")
        val meta = MkvDvPatcher.analyze(ByteSource(data))!!
        assertFalse(meta.hasColourElement)
        assertTrue(meta.colourInjected)

        val patched = applyPatches(data, meta.patches)
        assertEquals("injection must not change file length", data.size, patched.size)

        // Re-analyze the patched bytes: Colour now present, DV mapping gone.
        val after = MkvDvPatcher.analyze(ByteSource(patched))!!
        assertTrue(after.hasColourElement)
        assertFalse(after.colourInjected)

        File("build/patched").mkdirs()
        File("build/patched/p81_injected.mkv").writeBytes(patched)
    }

    @Test
    fun `injection is idempotent - already-colour file is untouched`() {
        val data = fixtureBytes("p81_dv.mkv")
        val meta = MkvDvPatcher.analyze(ByteSource(data))!!
        assertFalse(meta.colourInjected)
        // The DV mapping is still voided (container DV signaling removed).
        assertTrue(meta.patches.isNotEmpty())
    }

    @Test
    fun `mkv block addition mapping is voided`() {
        val data = fixtureBytes("p81_dv.mkv")
        val patches = MkvDvPatcher.findPatches(ByteSource(data))
        assertTrue(patches.isNotEmpty())

        val patched = applyPatches(data, patches)
        // Void header starts with 0xEC at each element start.
        for (p in patches) assertEquals(0xEC.toByte(), patched[p.offset.toInt()])
        assertEquals(0, MkvDvPatcher.findPatches(ByteSource(patched)).size)
        File("build/patched").mkdirs()
        File("build/patched/p81_patched.mkv").writeBytes(patched)
    }

    @Test
    fun `mkv without signaling yields no patches`() {
        val data = fixtureBytes("p81_dv.mkv")
        val cleaned = applyPatches(data, MkvDvPatcher.findPatches(ByteSource(data)))
        assertEquals(0, MkvDvPatcher.findPatches(ByteSource(cleaned)).size)
    }

    // --- MKV analyze + RPU transformer ---

    @Test
    fun `analyze finds video track, nal length and first cluster`() {
        val data = fixtureBytes("p81_dv.mkv")
        val meta = MkvDvPatcher.analyze(ByteSource(data))!!
        assertTrue(meta.videoTrackNumber > 0)
        assertEquals(4, meta.nalLengthSize)
        assertTrue(meta.firstClusterOffset > 0)
        // First-cluster offset must point exactly at a Cluster element ID.
        assertEquals(0x1F, data[meta.firstClusterOffset.toInt()].toInt() and 0xFF)
        assertEquals(0x43, data[meta.firstClusterOffset.toInt() + 1].toInt() and 0xFF)
        assertTrue(meta.patches.isNotEmpty())
    }

    @Test
    fun `transformer rewrites rpu nals without changing length`() {
        val data = fixtureBytes("p81_dv.mkv")
        val meta = MkvDvPatcher.analyze(ByteSource(data))!!
        val patched = applyPatches(data, meta.patches)

        val out = MkvRpuTransformer(ByteArrayInputStream(patched), 0, meta).readBytes()
        assertEquals(patched.size, out.size)
        assertTrue(!out.contentEquals(patched)) // RPUs were rewritten

        // Idempotent: no RPUs left to rewrite on a second pass.
        val again = MkvRpuTransformer(ByteArrayInputStream(out), 0, meta).readBytes()
        assertTrue(again.contentEquals(out))

        File("build/patched").mkdirs()
        File("build/patched/p81_transformed.mkv").writeBytes(out)
    }

    @Test
    fun `transformer handles mid-file range starting at cluster boundary`() {
        val data = fixtureBytes("p81_dv.mkv")
        val meta = MkvDvPatcher.analyze(ByteSource(data))!!
        val start = meta.firstClusterOffset.toInt()
        val tail = data.copyOfRange(start, data.size)
        val out = MkvRpuTransformer(ByteArrayInputStream(tail), start.toLong(), meta).readBytes()
        assertEquals(tail.size, out.size)
        assertTrue(!out.contentEquals(tail)) // this file's RPUs live in the clusters
    }

    // --- PatchedInputStream ---

    @Test
    fun `patched stream overlays bytes at absolute offsets across read boundaries`() {
        val data = ByteArray(100) { it.toByte() }
        val patches = listOf(Patch(10, byteArrayOf(-1, -1, -1)), Patch(50, byteArrayOf(0x7F)))
        // Simulate serving from offset 8 (mid-file range request).
        val stream = PatchedInputStream(ByteArrayInputStream(data, 8, 92), 8, patches)
        val out = ByteArray(92)
        var read = 0
        while (read < 92) {
            val n = stream.read(out, read, minOf(7, 92 - read)) // odd chunks straddle patches
            if (n <= 0) break
            read += n
        }
        assertEquals(92, read)
        assertEquals(9.toByte(), out[1])
        assertEquals((-1).toByte(), out[2])  // abs 10
        assertEquals((-1).toByte(), out[4])  // abs 12
        assertEquals(13.toByte(), out[5])    // abs 13 untouched
        assertEquals(0x7F.toByte(), out[42]) // abs 50
    }
}
