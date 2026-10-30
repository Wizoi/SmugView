package com.smugview.app.util

/** A growable byte buffer (the `ByteArrayOutputStream` the JPEG stripper used is JVM only). */
class ByteBuilder(capacity: Int = 32) {
    private var buffer = ByteArray(maxOf(capacity, 16))
    private var size = 0

    private fun ensure(extra: Int) {
        if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
    }

    fun write(byte: Int) { ensure(1); buffer[size++] = byte.toByte() }
    fun write(bytes: ByteArray) = write(bytes, 0, bytes.size)
    fun write(bytes: ByteArray, offset: Int, length: Int) { ensure(length); bytes.copyInto(buffer, size, offset, offset + length); size += length }
    fun toByteArray(): ByteArray = buffer.copyOf(size)
}
