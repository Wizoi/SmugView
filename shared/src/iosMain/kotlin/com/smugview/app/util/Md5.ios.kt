package com.smugview.app.util

/** MD5 in plain Kotlin (RFC 1321): iOS has no `MessageDigest`, and CommonCrypto would need a cinterop for one function. */
actual class Md5 actual constructor() {
    private var a = 0x67452301
    private var b = 0xefcdab89L.toInt()
    private var c = 0x98badcfeL.toInt()
    private var d = 0x10325476
    private val block = ByteArray(64)
    private var filled = 0
    private var total = 0L

    actual fun update(bytes: ByteArray, offset: Int, length: Int) {
        total += length
        var i = offset
        val end = offset + length
        while (i < end) {
            val take = minOf(64 - filled, end - i)
            bytes.copyInto(block, filled, i, i + take)
            filled += take
            i += take
            if (filled == 64) { transform(block); filled = 0 }
        }
    }

    actual fun hex(): String {
        val bits = total * 8
        val pad = ByteArray(if (filled < 56) 56 - filled else 120 - filled)
        pad[0] = 0x80.toByte()
        val saved = total
        update(pad, 0, pad.size)
        val length = ByteArray(8) { ((bits ushr (8 * it)) and 0xff).toByte() }
        update(length, 0, 8)
        total = saved
        return listOf(a, b, c, d).joinToString("") { word -> (0 until 4).joinToString("") { n -> ((word ushr (8 * n)) and 0xff).toString(16).padStart(2, '0') } }
    }

    private fun transform(buf: ByteArray) {
        val x = IntArray(16) { n ->
            (buf[n * 4].toInt() and 0xff) or ((buf[n * 4 + 1].toInt() and 0xff) shl 8) or
                ((buf[n * 4 + 2].toInt() and 0xff) shl 16) or ((buf[n * 4 + 3].toInt() and 0xff) shl 24)
        }
        var aa = a; var bb = b; var cc = c; var dd = d
        for (i in 0 until 64) {
            val f: Int; val g: Int
            when (i / 16) {
                0 -> { f = (bb and cc) or (bb.inv() and dd); g = i }
                1 -> { f = (dd and bb) or (dd.inv() and cc); g = (5 * i + 1) % 16 }
                2 -> { f = bb xor cc xor dd; g = (3 * i + 5) % 16 }
                else -> { f = cc xor (bb or dd.inv()); g = (7 * i) % 16 }
            }
            val temp = dd
            dd = cc
            cc = bb
            bb += Integer.rotl(aa + f + K[i] + x[g], S[i])
            aa = temp
        }
        a += aa; b += bb; c += cc; d += dd
    }

    private companion object {
        val S = intArrayOf(
            7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
            4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
        )
        val K = IntArray(64) { (kotlin.math.abs(kotlin.math.sin((it + 1).toDouble())) * 4294967296.0).toLong().toInt() }
    }
}

private object Integer {
    fun rotl(v: Int, s: Int): Int = (v shl s) or (v ushr (32 - s))
}
