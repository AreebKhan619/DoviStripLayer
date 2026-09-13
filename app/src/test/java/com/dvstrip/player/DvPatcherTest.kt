package com.dvstrip.player

import org.junit.Assert.assertEquals
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
