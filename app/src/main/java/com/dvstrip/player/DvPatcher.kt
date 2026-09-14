package com.dvstrip.player

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Byte-level neutralization of container Dolby Vision signaling, for the pass-through proxy.
 * Players and TV pipelines engage DV based on container declarations (`dvvC`/`dvcC` boxes and
 * `dvh1`/`dvhe` sample entries in MP4; BlockAdditionMapping in Matroska) — with those bytes
 * patched out, the file is treated as plain HEVC/HDR10 and in-band RPU NALs are ignored.
 * Everything is same-size in-place edits, so byte offsets (and HTTP ranges) stay valid.
 */
class Patch(val offset: Long, val bytes: ByteArray)

interface RandomAccessSource {
    val length: Long
    /** Reads exactly [size] bytes at [offset] unless EOF truncates the tail. */
    fun readFully(offset: Long, size: Int): ByteArray
}

object Mp4DvPatcher {

    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl")
    private val DV_CONFIG_BOXES = setOf("dvcC", "dvvC", "dvwC")
    private val DV_SAMPLE_ENTRIES = mapOf("dvh1" to "hvc1", "dvhe" to "hev1")
    /** VisualSampleEntry fixed fields (incl. 8-byte box header) before child boxes start. */
    private const val VISUAL_SAMPLE_ENTRY_HEADER = 86

    fun findPatches(src: RandomAccessSource): List<Patch> {
        var pos = 0L
        while (pos + 8 <= src.length) {
            val header = src.readFully(pos, 16)
            if (header.size < 8) break
            var size = u32(header, 0)
            val type = fourcc(header, 4)
            var headerLen = 8L
            if (size == 1L) {
                if (header.size < 16) break
                size = u64(header, 8)
                headerLen = 16
            } else if (size == 0L) {
                size = src.length - pos
            }
            if (size < headerLen) break
            if (type == "moov") {
                val moov = src.readFully(pos, size.toInt())
                val patches = mutableListOf<Patch>()
                walk(moov, 8, moov.size, pos, patches)
                return patches
            }
            pos += size
        }
        return emptyList()
    }

    private fun walk(buf: ByteArray, from: Int, to: Int, absBase: Long, out: MutableList<Patch>) {
        var pos = from
        while (pos + 8 <= to) {
            var size = u32(buf, pos)
            val type = fourcc(buf, pos + 4)
            var headerLen = 8
            if (size == 1L && pos + 16 <= to) {
                size = u64(buf, pos + 8)
                headerLen = 16
            } else if (size == 0L) {
                size = (to - pos).toLong()
            }
            if (size < headerLen || pos + size > to) break
            val end = (pos + size).toInt()
            when {
                type in CONTAINERS -> walk(buf, pos + headerLen, end, absBase, out)
                type == "stsd" -> {
                    // FullBox: version/flags (4) + entry_count (4), then sample entries.
                    walkSampleEntries(buf, pos + headerLen + 8, end, absBase, out)
                }
            }
            pos = end
        }
    }

    private fun walkSampleEntries(buf: ByteArray, from: Int, to: Int, absBase: Long, out: MutableList<Patch>) {
        var pos = from
        while (pos + 8 <= to) {
            val size = u32(buf, pos)
            if (size < 8 || pos + size > to) break
            val format = fourcc(buf, pos + 4)
            DV_SAMPLE_ENTRIES[format]?.let { replacement ->
                out.add(Patch(absBase + pos + 4, replacement.toByteArray(Charsets.US_ASCII)))
            }
            // Child boxes of the visual sample entry (hvcC, dvvC, colr, …).
            var child = pos + VISUAL_SAMPLE_ENTRY_HEADER
            val entryEnd = (pos + size).toInt()
            while (child + 8 <= entryEnd) {
                val cSize = u32(buf, child)
                if (cSize < 8 || child + cSize > entryEnd) break
                if (fourcc(buf, child + 4) in DV_CONFIG_BOXES) {
                    out.add(Patch(absBase + child + 4, "free".toByteArray(Charsets.US_ASCII)))
                }
                child += cSize.toInt()
            }
            pos = entryEnd
        }
    }

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    private fun fourcc(b: ByteArray, o: Int): String = String(b, o, 4, Charsets.US_ASCII)
}

/** Everything the streaming proxy needs to neutralize DV in an MKV. */
class MkvMeta(
    val patches: List<Patch>,
    val firstClusterOffset: Long,
    val videoTrackNumber: Long,
    val nalLengthSize: Int,
    /**
     * Whether the video TrackEntry declares a Colour element (0x55B0). Without it, players
     * only learn the stream is HDR from the bitstream VUI mid-decode, and some pipelines
     * then don't switch the display to HDR mode until a codec reconfigure (seek/crop).
     */
    val hasColourElement: Boolean = false
)

object MkvDvPatcher {

    private const val ID_SEGMENT = 0x18538067L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_CODEC_ID = 0x86L
    private const val ID_CODEC_PRIVATE = 0x63A2L
    private const val ID_BLOCK_ADDITION_MAPPING = 0x41E4L
    private const val ID_VIDEO = 0xE0L
    private const val ID_COLOUR = 0x55B0L
    const val ID_CLUSTER = 0x1F43B675L
    private val DV_FOURCCS = listOf("dvcC", "dvvC", "dvwC")

    fun findPatches(src: RandomAccessSource): List<Patch> = analyze(src)?.patches ?: emptyList()

    /**
     * Scan the header region (max ~8MB): void DV BlockAdditionMapping elements, locate the
     * HEVC video track (number + NAL length-prefix size from hvcC) and the first Cluster.
     */
    fun analyze(src: RandomAccessSource): MkvMeta? {
        val head = src.readFully(0, minOf(src.length, 8L * 1024 * 1024).toInt())
        val r = Reader(head)

        // EBML header element, then Segment.
        val ebmlId = r.readId() ?: return null
        if (ebmlId != 0x1A45DFA3L) return null
        val ebmlSize = r.readSize() ?: return null
        r.pos += ebmlSize.toInt()

        val segId = r.readId() ?: return null
        if (segId != ID_SEGMENT) return null
        r.readSize() ?: return null // often "unknown size"; children follow either way

        val patches = mutableListOf<Patch>()
        var videoTrack = -1L
        var nalLengthSize = 4
        var firstCluster = -1L
        var hasColour = false

        while (r.pos < head.size - 2) {
            val elementStart = r.pos
            val id = r.readId() ?: break
            val size = r.readSize() ?: break
            if (id == ID_CLUSTER) {
                firstCluster = elementStart.toLong()
                break
            }
            if (id == ID_TRACKS) {
                val end = minOf(r.pos + size.toInt(), head.size)
                val tr = Reader(head).apply { pos = r.pos }
                while (tr.pos < end - 2) {
                    val teId = tr.readId() ?: break
                    val teSize = tr.readSize() ?: break
                    val teStart = tr.pos
                    if (teId == ID_TRACK_ENTRY) {
                        parseTrackEntry(head, teStart, teStart + teSize.toInt(), patches)?.let { video ->
                            if (videoTrack < 0) {
                                videoTrack = video.number
                                nalLengthSize = video.nalLengthSize
                                hasColour = video.hasColour
                            }
                        }
                    }
                    tr.pos = teStart + teSize.toInt()
                }
            }
            if (size < 0 || elementStart + size > head.size) break
            r.pos += size.toInt()
        }
        if (firstCluster < 0) return null
        return MkvMeta(patches, firstCluster, videoTrack, nalLengthSize, hasColour)
    }

    class VideoTrackInfo(val number: Long, val nalLengthSize: Int, val hasColour: Boolean)

    /** Returns track info when this entry is the HEVC video track. */
    private fun parseTrackEntry(
        buf: ByteArray,
        from: Int,
        to: Int,
        out: MutableList<Patch>
    ): VideoTrackInfo? {
        val r = Reader(buf).apply { pos = from }
        var number = -1L
        var type = -1L
        var codecId = ""
        var nalLengthSize = 4
        var hasColour = false
        while (r.pos < to - 1) {
            val id = r.readId() ?: break
            val size = r.readSize() ?: break
            val contentStart = r.pos
            if (contentStart + size > to) break
            when (id) {
                ID_TRACK_NUMBER -> number = readUint(buf, contentStart, size.toInt())
                ID_TRACK_TYPE -> type = readUint(buf, contentStart, size.toInt())
                ID_CODEC_ID -> codecId = String(buf, contentStart, size.toInt(), Charsets.US_ASCII).trimEnd(' ')
                ID_CODEC_PRIVATE -> if (size >= 23) {
                    // hvcC: lengthSizeMinusOne lives in the low 2 bits of byte 21.
                    nalLengthSize = ((buf[contentStart + 21].toInt() and 0x03) + 1)
                }
                ID_VIDEO -> hasColour = containsElement(buf, contentStart, contentStart + size.toInt(), ID_COLOUR)
                ID_BLOCK_ADDITION_MAPPING -> {
                    val content = String(buf, contentStart, size.toInt(), Charsets.ISO_8859_1)
                    if (DV_FOURCCS.any { content.contains(it) }) {
                        out.add(voidPatch(buf, findElementStart(buf, contentStart, id), contentStart, size))
                    }
                }
            }
            r.pos = contentStart + size.toInt()
        }
        return if (type == 1L && codecId.startsWith("V_MPEGH/ISO/HEVC") && number > 0) {
            VideoTrackInfo(number, nalLengthSize, hasColour)
        } else null
    }

    private fun containsElement(buf: ByteArray, from: Int, to: Int, wanted: Long): Boolean {
        val r = Reader(buf).apply { pos = from }
        while (r.pos < to - 1) {
            val id = r.readId() ?: return false
            val size = r.readSize() ?: return false
            if (id == wanted) return true
            if (r.pos + size > to) return false
            r.pos += size.toInt()
        }
        return false
    }

    private fun readUint(buf: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        for (i in 0 until len) v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        return v
    }

    /** Element start = content start minus (id length + size-field length), recomputed exactly. */
    private fun findElementStart(buf: ByteArray, contentStart: Int, id: Long): Int {
        val idLen = when {
            id <= 0xFFL -> 1
            id <= 0xFFFFL -> 2
            id <= 0xFFFFFFL -> 3
            else -> 4
        }
        // Size field length: walk back until a valid vint of the right length yields contentStart.
        for (sizeLen in 1..8) {
            val start = contentStart - idLen - sizeLen
            if (start < 0) continue
            val r = Reader(buf).apply { pos = start }
            if (r.readId() == id) {
                val declaredAfterId = r.pos
                r.readSize()
                if (declaredAfterId + sizeLen == contentStart && r.pos == contentStart) return start
            }
        }
        throw IllegalStateException("cannot locate element start")
    }

    /**
     * Replace the element's header with an EBML Void header of identical total length; the
     * old content bytes remain in place as the Void payload (spec: readers skip it).
     */
    private fun voidPatch(buf: ByteArray, elementStart: Int, contentStart: Int, contentSize: Long): Patch {
        val headerLen = contentStart - elementStart
        val sizeFieldLen = headerLen - 1 // Void ID is 1 byte
        require(sizeFieldLen >= 1) { "element header too small to void" }
        val payload = contentSize
        val header = ByteArray(headerLen)
        header[0] = 0xEC.toByte()
        // vint: marker bit in the first size byte, big-endian value below it.
        var v = payload or (1L shl (7 * sizeFieldLen))
        for (i in sizeFieldLen downTo 1) {
            header[i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        return Patch(elementStart.toLong(), header)
    }

    private class Reader(val buf: ByteArray) {
        var pos = 0

        fun readId(): Long? {
            if (pos >= buf.size) return null
            val first = buf[pos].toInt() and 0xFF
            val len = when {
                first and 0x80 != 0 -> 1
                first and 0x40 != 0 -> 2
                first and 0x20 != 0 -> 3
                first and 0x10 != 0 -> 4
                else -> return null
            }
            if (pos + len > buf.size) return null
            var v = 0L
            for (i in 0 until len) v = (v shl 8) or (buf[pos + i].toLong() and 0xFF)
            pos += len
            return v
        }

        fun readSize(): Long? {
            if (pos >= buf.size) return null
            val first = buf[pos].toInt() and 0xFF
            var len = 0
            for (i in 7 downTo 0) {
                if (first and (1 shl i) != 0) {
                    len = 8 - i
                    break
                }
            }
            if (len == 0 || pos + len > buf.size) return null
            var v = (first and ((1 shl (8 - len)) - 1)).toLong()
            for (i in 1 until len) v = (v shl 8) or (buf[pos + i].toLong() and 0xFF)
            pos += len
            // "Unknown size" (all value bits set) — treat as zero-extent for our scan purposes.
            val allOnes = (1L shl (7 * len)) - 1
            return if (v == allOnes) 0L else v
        }
    }
}

/** Streams [base] (positioned at [startOffset] of the file) with patch bytes overlaid. */
class PatchedInputStream(
    base: InputStream,
    private var pos: Long,
    patches: List<Patch>
) : FilterInputStream(base) {

    private val patches = patches.sortedBy { it.offset }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n <= 0) return n
        for (p in patches) {
            val pEnd = p.offset + p.bytes.size
            if (pEnd <= pos || p.offset >= pos + n) continue
            var i = maxOf(p.offset, pos)
            while (i < minOf(pEnd, pos + n)) {
                b[off + (i - pos).toInt()] = p.bytes[(i - p.offset).toInt()]
                i++
            }
        }
        pos += n
        return n
    }
}
