package com.dvstrip.player

import java.io.EOFException
import java.io.InputStream

/**
 * Streams an MKV byte range with every Dolby Vision RPU NAL (HEVC NAL type 62) in the video
 * track rewritten IN PLACE into a same-size filler NAL (type 38), which decoders discard.
 * Some TV SoCs auto-engage their DV pipeline purely from in-band RPUs, ignoring container
 * signaling — neutralizing the NAL bytes themselves is the only stream-level fix. Byte
 * lengths never change, so HTTP Range offsets remain valid and seeking is untouched.
 *
 * Works by buffering one Cluster at a time (players seek to Cluster boundaries via Cues, so
 * a mid-file range lands exactly on a Cluster start). Anything unparseable — unknown-size
 * clusters, oversized clusters, laced video blocks — passes through unmodified rather than
 * corrupting the stream.
 */
class MkvRpuTransformer(
    private val base: InputStream,
    private var absPos: Long,
    private val meta: MkvMeta,
    /** Diagnostics sink — Android Log in production, no-op in JVM tests. */
    private val log: (String) -> Unit = {}
) : InputStream() {

    companion object {
        private const val MAX_CLUSTER_BUFFER = 96 * 1024 * 1024
        private const val RPU_NAL_TYPE = 62
    }

    private var out: ByteArray = ByteArray(0)
    private var outPos = 0
    private var eof = false
    /** True once structure tracking is lost — remaining bytes pass through untouched. */
    private var passThrough = false

    var clustersParsed = 0
        private set
    var rpusRewritten = 0
        private set

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (outPos >= out.size) {
            if (eof) return -1
            step()
        }
        val n = minOf(len, out.size - outPos)
        System.arraycopy(out, outPos, b, off, n)
        outPos += n
        return n
    }

    override fun close() {
        log("transformer closed: clusters=$clustersParsed rpusRewritten=$rpusRewritten passThrough=$passThrough")
        base.close()
    }

    private fun emit(bytes: ByteArray) {
        out = bytes
        outPos = 0
    }

    private fun step() {
        if (passThrough) {
            val buf = ByteArray(64 * 1024)
            val n = base.read(buf)
            if (n <= 0) {
                eof = true
            } else {
                absPos += n
                emit(if (n == buf.size) buf else buf.copyOf(n))
            }
            return
        }
        if (absPos < meta.firstClusterOffset) {
            // Header region (already covered by static patches upstream) — pass through.
            val want = minOf(meta.firstClusterOffset - absPos, 64L * 1024).toInt()
            val buf = ByteArray(want)
            val n = base.read(buf)
            if (n <= 0) {
                eof = true
            } else {
                absPos += n
                emit(if (n == buf.size) buf else buf.copyOf(n))
            }
            return
        }
        stepElement()
    }

    /** Parse one top-level element (Cluster or other) and emit it, transformed if a Cluster. */
    private fun stepElement() {
        val header = ByteArray(12)
        val first = base.read(header, 0, 1)
        if (first <= 0) {
            eof = true
            return
        }
        var have = 1

        fun need(count: Int): Boolean {
            while (have < count) {
                val n = base.read(header, have, count - have)
                if (n <= 0) return false
                have += n
            }
            return true
        }

        val idLen = vintLength(header[0].toInt() and 0xFF, idMode = true)
        if (idLen == 0 || !need(idLen + 1)) {
            giveUp(header, have)
            return
        }
        var id = 0L
        for (i in 0 until idLen) id = (id shl 8) or (header[i].toLong() and 0xFF)

        val sizeLen = vintLength(header[idLen].toInt() and 0xFF, idMode = false)
        if (sizeLen == 0 || !need(idLen + sizeLen)) {
            giveUp(header, have)
            return
        }
        var size = (header[idLen].toLong() and ((1L shl (8 - sizeLen)) - 1))
        for (i in idLen + 1 until idLen + sizeLen) size = (size shl 8) or (header[i].toLong() and 0xFF)
        val unknownSize = size == (1L shl (7 * sizeLen)) - 1

        val headerBytes = header.copyOf(idLen + sizeLen)
        if (unknownSize || size > MAX_CLUSTER_BUFFER) {
            // Can't safely frame this element — emit what we have and stop transforming.
            log("transformer -> passThrough at $absPos: id=0x${id.toString(16)} unknownSize=$unknownSize size=$size")
            passThrough = true
            absPos += headerBytes.size
            emit(headerBytes)
            return
        }

        val content = ByteArray(size.toInt())
        var n = 0
        while (n < content.size) {
            val r = base.read(content, n, content.size - n)
            if (r <= 0) break
            n += r
        }
        if (n < content.size) {
            // Truncated tail (EOF or client-limited range) — emit as-is, untransformed.
            eof = true
            absPos += headerBytes.size + n
            emit(headerBytes + content.copyOf(n))
            return
        }

        if (id == MkvDvPatcher.ID_CLUSTER) {
            patchCluster(content)
            clustersParsed++
            if (clustersParsed == 1 || clustersParsed % 100 == 0) {
                log("transformer: clusters=$clustersParsed rpusRewritten=$rpusRewritten")
            }
        }
        absPos += headerBytes.size + content.size
        emit(headerBytes + content)
    }

    private fun giveUp(pending: ByteArray, have: Int) {
        log("transformer -> passThrough (unparseable element header) at $absPos")
        passThrough = true
        if (have > 0) {
            absPos += have
            emit(pending.copyOf(have))
        } else {
            eof = true
        }
    }

    // --- in-buffer cluster parsing ---

    private fun patchCluster(buf: ByteArray) {
        var pos = 0
        while (pos < buf.size - 1) {
            val idLen = vintLength(buf[pos].toInt() and 0xFF, idMode = true)
            if (idLen == 0 || pos + idLen >= buf.size) return
            var id = 0L
            for (i in 0 until idLen) id = (id shl 8) or (buf[pos + i].toLong() and 0xFF)
            val sizeLen = vintLength(buf[pos + idLen].toInt() and 0xFF, idMode = false)
            if (sizeLen == 0 || pos + idLen + sizeLen > buf.size) return
            var size = (buf[pos + idLen].toLong() and ((1L shl (8 - sizeLen)) - 1))
            for (i in pos + idLen + 1 until pos + idLen + sizeLen) {
                size = (size shl 8) or (buf[i].toLong() and 0xFF)
            }
            val contentStart = pos + idLen + sizeLen
            val contentEnd = contentStart + size.toInt()
            if (size < 0 || contentEnd > buf.size) return

            when (id) {
                0xA3L -> patchBlock(buf, contentStart, contentEnd)          // SimpleBlock
                0xA0L -> patchBlockGroup(buf, contentStart, contentEnd)     // BlockGroup
            }
            pos = contentEnd
        }
    }

    private fun patchBlockGroup(buf: ByteArray, from: Int, to: Int) {
        var pos = from
        while (pos < to - 1) {
            val idLen = vintLength(buf[pos].toInt() and 0xFF, idMode = true)
            if (idLen == 0 || pos + idLen >= to) return
            var id = 0L
            for (i in 0 until idLen) id = (id shl 8) or (buf[pos + i].toLong() and 0xFF)
            val sizeLen = vintLength(buf[pos + idLen].toInt() and 0xFF, idMode = false)
            if (sizeLen == 0 || pos + idLen + sizeLen > to) return
            var size = (buf[pos + idLen].toLong() and ((1L shl (8 - sizeLen)) - 1))
            for (i in pos + idLen + 1 until pos + idLen + sizeLen) {
                size = (size shl 8) or (buf[i].toLong() and 0xFF)
            }
            val contentStart = pos + idLen + sizeLen
            val contentEnd = contentStart + size.toInt()
            if (size < 0 || contentEnd > to) return
            if (id == 0xA1L) patchBlock(buf, contentStart, contentEnd)      // Block
            pos = contentEnd
        }
    }

    private fun patchBlock(buf: ByteArray, from: Int, to: Int) {
        var pos = from
        // Track number vint (value-decoded).
        val first = buf[pos].toInt() and 0xFF
        val tnLen = vintLength(first, idMode = false)
        if (tnLen == 0 || pos + tnLen + 3 > to) return
        var trackNum = (first and ((1 shl (8 - tnLen)) - 1)).toLong()
        for (i in pos + 1 until pos + tnLen) trackNum = (trackNum shl 8) or (buf[i].toLong() and 0xFF)
        if (meta.videoTrackNumber > 0 && trackNum != meta.videoTrackNumber) return
        pos += tnLen
        pos += 2 // block timestamp
        val flags = buf[pos].toInt() and 0xFF
        pos += 1
        if ((flags shr 1) and 0x03 != 0) return // laced video: pass through untouched

        // Length-prefixed NAL walk.
        val lenSize = meta.nalLengthSize
        while (pos + lenSize <= to) {
            var nalLen = 0L
            for (i in 0 until lenSize) nalLen = (nalLen shl 8) or (buf[pos + i].toLong() and 0xFF)
            val nalStart = pos + lenSize
            val nalEnd = nalStart + nalLen.toInt()
            if (nalLen < 2 || nalEnd > to) return
            val type = (buf[nalStart].toInt() and 0xFF) shr 1 and 0x3F
            if (type == RPU_NAL_TYPE && nalLen >= 3) {
                // Same-size filler NAL (type 38): header 0x4C 0x01, 0xFF padding, rbsp stop.
                buf[nalStart] = 0x4C
                buf[nalStart + 1] = 0x01
                for (i in nalStart + 2 until nalEnd - 1) buf[i] = 0xFF.toByte()
                buf[nalEnd - 1] = 0x80.toByte()
                rpusRewritten++
            }
            pos = nalEnd
        }
    }

    /** EBML vint length from the first byte; IDs keep the marker, sizes are 1–8 bytes. */
    private fun vintLength(first: Int, idMode: Boolean): Int {
        val max = if (idMode) 4 else 8
        for (i in 0 until max) {
            if (first and (0x80 shr i) != 0) return i + 1
        }
        return 0
    }
}
