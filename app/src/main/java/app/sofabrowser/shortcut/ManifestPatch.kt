package app.sofabrowser.shortcut

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Swaps texts inside an APK's compiled (binary) AndroidManifest.xml. Only the string pool changes: every string
 * keeps its index, so the elements and attributes that point into it stay valid.
 */
object ManifestPatch {
    private const val XML = 0x0003
    private const val POOL = 0x0001
    private const val UTF8 = 1 shl 8

    /** [manifest] with each whole string that is a key of [replacements] replaced by its value. */
    fun replace(manifest: ByteArray, replacements: Map<String, String>): ByteArray {
        val src = ByteBuffer.wrap(manifest).order(ByteOrder.LITTLE_ENDIAN)
        require(src.getShort(0).toInt() == XML) { "not a binary manifest" }
        val pool = src.getShort(2).toInt() and 0xffff
        require(src.getShort(pool).toInt() == POOL) { "no string pool" }
        val headerSize = src.getShort(pool + 2).toInt() and 0xffff
        val poolSize = src.getInt(pool + 4)
        val count = src.getInt(pool + 8)
        require(src.getInt(pool + 12) == 0) { "styled strings are not supported" }
        val flags = src.getInt(pool + 16)
        val stringsStart = src.getInt(pool + 20)
        val utf8 = flags and UTF8 != 0

        val strings = List(count) { i ->
            val at = pool + stringsStart + src.getInt(pool + headerSize + 4 * i)
            val text = if (utf8) readUtf8(src, at) else readUtf16(src, at)
            replacements[text] ?: text
        }

        val data = ByteArrayOutputStream()
        val offsets = IntArray(count)
        strings.forEachIndexed { i, s ->
            offsets[i] = data.size()
            if (utf8) writeUtf8(data, s) else writeUtf16(data, s)
        }
        while (data.size() % 4 != 0) data.write(0)

        val newStringsStart = headerSize + 4 * count
        val newPoolSize = newStringsStart + data.size()
        val rest = manifest.size - (pool + poolSize)
        val out = ByteBuffer.allocate(pool + newPoolSize + rest).order(ByteOrder.LITTLE_ENDIAN)
        out.put(manifest, 0, pool + headerSize)
        out.putInt(4, out.capacity())
        out.putInt(pool + 4, newPoolSize)
        out.putInt(pool + 20, newStringsStart)
        out.putInt(pool + 24, 0)
        out.position(pool + headerSize)
        offsets.forEach { out.putInt(it) }
        out.put(data.toByteArray())
        out.put(manifest, pool + poolSize, rest)
        return out.array()
    }

    private fun readUtf8(b: ByteBuffer, at: Int): String {
        var p = at
        p += if (b.get(p).toInt() and 0x80 != 0) 2 else 1 // length in characters
        var n = b.get(p).toInt() and 0xff
        p++
        if (n and 0x80 != 0) { n = ((n and 0x7f) shl 8) or (b.get(p).toInt() and 0xff); p++ }
        val bytes = ByteArray(n)
        for (i in 0 until n) bytes[i] = b.get(p + i)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readUtf16(b: ByteBuffer, at: Int): String {
        var p = at
        var n = b.getShort(p).toInt() and 0xffff
        p += 2
        if (n and 0x8000 != 0) { n = ((n and 0x7fff) shl 16) or (b.getShort(p).toInt() and 0xffff); p += 2 }
        val chars = CharArray(n) { b.getChar(p + 2 * it) }
        return String(chars)
    }

    private fun writeUtf8(out: ByteArrayOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeLength8(out, s.length)
        writeLength8(out, bytes.size)
        out.write(bytes)
        out.write(0)
    }

    private fun writeLength8(out: ByteArrayOutputStream, n: Int) {
        if (n > 0x7f) out.write(0x80 or (n shr 8))
        out.write(n and 0xff)
    }

    private fun writeUtf16(out: ByteArrayOutputStream, s: String) {
        fun u16(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
        if (s.length > 0x7fff) { u16(0x8000 or (s.length shr 16)); u16(s.length and 0xffff) } else u16(s.length)
        s.forEach { u16(it.code) }
        u16(0)
    }
}
