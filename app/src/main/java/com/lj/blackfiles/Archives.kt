package com.lj.blackfiles

import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Extracts .zip, .tar, .tar.gz and .tgz. Tar is read by hand (ustar, GNU long names and pax paths). */
object Archives {
    private val EXTENSIONS = listOf(".zip", ".tar.gz", ".tgz", ".tar")

    fun isArchive(file: File) = EXTENSIONS.any { file.name.lowercase().endsWith(it) }

    /** Extracts into a new folder next to the archive, named after it. Returns that folder. */
    fun extract(archive: File): File {
        val lower = archive.name.lowercase()
        val ext = EXTENSIONS.first { lower.endsWith(it) }
        val stem = archive.name.dropLast(ext.length).ifEmpty { "archive" }
        val dest = Storage.freeName(archive.parentFile!!, stem, isDir = true)
        check(dest.mkdirs()) { "can't create folder" }
        try {
            if (ext == ".zip") {
                try {
                    archive.inputStream().buffered().use { unzip(it, dest, Charsets.UTF_8) }
                } catch (e: IllegalArgumentException) {
                    // Old Windows zips store names in CP437, which fails to decode as UTF-8.
                    dest.listFiles()?.forEach { it.deleteRecursively() }
                    archive.inputStream().buffered().use { unzip(it, dest, Charset.forName("IBM437")) }
                }
            } else {
                archive.inputStream().buffered().use { raw ->
                    untar(if (ext == ".tar") raw else GZIPInputStream(raw, 64 * 1024), dest)
                }
            }
        } catch (e: Exception) {
            dest.deleteRecursively()
            throw e
        }
        return dest
    }

    private fun unzip(input: InputStream, dest: File, charset: Charset) {
        val zip = ZipInputStream(input, charset)
        while (true) {
            val entry = zip.nextEntry ?: break
            val out = safeFile(dest, entry.name)
            if (entry.isDirectory) out.mkdirs()
            else write(out, entry.time) { zip.copyTo(it) }
        }
    }

    private fun untar(input: InputStream, dest: File) {
        val header = ByteArray(512)
        var nextName: String? = null
        while (readBlock(input, header)) {
            if (header.all { it == 0.toByte() }) break
            val size = number(header, 124, 12)
            val name = nextName ?: run {
                val base = text(header, 0, 100)
                val prefix = if (text(header, 257, 5) == "ustar") text(header, 345, 155) else ""
                if (prefix.isEmpty()) base else "$prefix/$base"
            }
            nextName = null
            when (header[156].toInt().toChar()) {
                // GNU long name / pax header: they name the entry that follows.
                'L' -> nextName = String(readBytes(input, size), Charsets.UTF_8).trimEnd('\u0000')
                'x' -> nextName = paxPath(String(readBytes(input, size), Charsets.UTF_8))
                '5' -> {
                    safeFile(dest, name).mkdirs()
                    skip(input, size)
                }
                '0', '\u0000', '7' -> write(safeFile(dest, name), number(header, 136, 12) * 1000) {
                    copy(input, it, size)
                }
                else -> skip(input, size) // links, devices, global pax headers
            }
            skip(input, (512 - size % 512) % 512)
        }
    }

    /** Resolves an entry name inside [dest], refusing names like "../../x" that would escape it. */
    private fun safeFile(dest: File, name: String): File {
        val root = dest.canonicalPath
        val f = File(dest, name.trimStart('/'))
        val path = f.canonicalPath
        require(path == root || path.startsWith(root + File.separator)) { "unsafe path in archive: $name" }
        return f
    }

    private fun write(out: File, time: Long, body: (OutputStream) -> Unit) {
        out.parentFile?.mkdirs()
        out.outputStream().buffered().use(body)
        if (time > 0) out.setLastModified(time)
    }

    private fun paxPath(records: String): String? =
        records.lineSequence()
            .map { it.substringAfter(' ') }
            .firstOrNull { it.startsWith("path=") }
            ?.removePrefix("path=")

    private fun text(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    /** Octal, or GNU base-256 when the high bit is set (files over 8 GB). */
    private fun number(b: ByteArray, off: Int, len: Int): Long {
        if (b[off].toInt() and 0x80 != 0) {
            var v = (b[off].toLong() and 0x7F)
            for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xFF)
            return v
        }
        return text(b, off, len).trim().ifEmpty { "0" }.toLong(8)
    }

    private fun readBlock(input: InputStream, block: ByteArray): Boolean {
        var read = 0
        while (read < block.size) {
            val n = input.read(block, read, block.size - read)
            if (n < 0) {
                if (read == 0) return false
                throw EOFException("truncated archive")
            }
            read += n
        }
        return true
    }

    private fun readBytes(input: InputStream, size: Long): ByteArray =
        ByteArray(size.toInt()).also { if (size > 0 && !readBlock(input, it)) throw EOFException("truncated archive") }

    private fun copy(input: InputStream, out: OutputStream?, size: Long) {
        val buffer = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("truncated archive")
            out?.write(buffer, 0, n)
            left -= n
        }
    }

    private fun skip(input: InputStream, size: Long) = copy(input, null, size)
}
