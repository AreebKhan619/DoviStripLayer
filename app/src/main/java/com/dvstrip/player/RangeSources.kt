package com.dvstrip.player

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** A [RandomAccessSource] that can also open a continuous stream at an offset for serving. */
interface StreamableSource : RandomAccessSource {
    val supportsRanges: Boolean
    fun openAt(offset: Long): InputStream
}

internal fun skipFully(ins: InputStream, count: Long) {
    var remaining = count
    while (remaining > 0) {
        val skipped = ins.skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else {
            if (ins.read() < 0) throw IOException("EOF while skipping")
            remaining--
        }
    }
}

class HttpRangeSource(private val urlString: String) : StreamableSource {

    override var supportsRanges: Boolean = false
        private set

    override val length: Long by lazy {
        val conn = open()
        conn.setRequestProperty("Range", "bytes=0-0")
        try {
            when (conn.responseCode) {
                206 -> {
                    supportsRanges = true
                    conn.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1L
                }
                200 -> conn.contentLengthLong
                else -> throw IOException("HTTP ${conn.responseCode} for $urlString")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(): HttpURLConnection =
        (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DVStripPlayer/1.0")
        }

    override fun readFully(offset: Long, size: Int): ByteArray {
        val conn = open()
        conn.setRequestProperty("Range", "bytes=$offset-${offset + size - 1}")
        try {
            val ins = conn.inputStream
            if (conn.responseCode == 200 && offset > 0) skipFully(ins, offset)
            val buf = ByteArray(size)
            var n = 0
            while (n < size) {
                val r = ins.read(buf, n, size - n)
                if (r <= 0) break
                n += r
            }
            return if (n == size) buf else buf.copyOf(n)
        } finally {
            conn.disconnect()
        }
    }

    override fun openAt(offset: Long): InputStream {
        val conn = open()
        if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
        val ins = conn.inputStream
        if (offset > 0 && conn.responseCode == 200) skipFully(ins, offset)
        return object : FilterInputStream(ins) {
            override fun close() {
                super.close()
                conn.disconnect()
            }
        }
    }
}

class FileRangeSource(private val path: String) : StreamableSource {
    override val supportsRanges: Boolean = true
    override val length: Long get() = java.io.File(path).length()

    override fun readFully(offset: Long, size: Int): ByteArray =
        RandomAccessFile(path, "r").use { raf ->
            raf.seek(offset)
            val buf = ByteArray(size)
            var n = 0
            while (n < size) {
                val r = raf.read(buf, n, size - n)
                if (r <= 0) break
                n += r
            }
            if (n == size) buf else buf.copyOf(n)
        }

    override fun openAt(offset: Long): InputStream =
        FileInputStream(path).also { it.channel.position(offset) }
}

class ContentRangeSource(private val context: Context, private val uri: Uri) : StreamableSource {
    override val supportsRanges: Boolean = true

    override val length: Long by lazy {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    }

    override fun readFully(offset: Long, size: Int): ByteArray =
        openAt(offset).use { ins ->
            val buf = ByteArray(size)
            var n = 0
            while (n < size) {
                val r = ins.read(buf, n, size - n)
                if (r <= 0) break
                n += r
            }
            if (n == size) buf else buf.copyOf(n)
        }

    override fun openAt(offset: Long): InputStream {
        // File-backed providers give a seekable descriptor; pipes fall back to skipping.
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")!!
            val fis = FileInputStream(pfd.fileDescriptor)
            fis.channel.position(offset)
            object : FilterInputStream(fis) {
                override fun close() {
                    super.close()
                    pfd.close()
                }
            }
        } catch (e: Exception) {
            val ins = context.contentResolver.openInputStream(uri)
                ?: throw IOException("cannot open $uri")
            skipFully(ins, offset)
            ins
        }
    }
}

object Sources {
    fun forUri(context: Context, uri: Uri): StreamableSource = when (uri.scheme) {
        "http", "https" -> HttpRangeSource(uri.toString())
        "file" -> FileRangeSource(uri.path!!)
        "content" -> ContentRangeSource(context, uri)
        else -> throw IOException("unsupported scheme ${uri.scheme}")
    }
}
