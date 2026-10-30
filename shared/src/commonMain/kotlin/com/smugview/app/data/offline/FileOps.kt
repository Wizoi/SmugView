package com.smugview.app.data.offline

import com.smugview.app.util.Md5

/** Reads a file in chunks. */
interface FileReader {
    /** Fills up to [buffer].size bytes; returns how many, or -1 at the end. */
    fun read(buffer: ByteArray): Int
    fun close()
}

/** Writes a file in chunks. */
interface FileWriter {
    fun write(bytes: ByteArray, offset: Int, length: Int)
    /** Flushes and closes. */
    fun close()
}

/**
 * The file operations the offline store and downloader need, on string paths (`/` separated), so the same code runs on Android and iOS.
 * Every method either does what it says or throws an `IOException`; a missing file is false, 0 or empty, never an exception.
 */
interface FileOps {
    fun isFile(path: String): Boolean
    fun length(path: String): Long
    /** True when something was deleted. */
    fun delete(path: String): Boolean
    fun mkdirs(path: String)
    /** The names (not paths) of the regular files directly in [dir]. */
    fun listFiles(dir: String): List<String>
    /** Moves [from] over [to] in one atomic step, replacing what is there. */
    fun moveReplacing(from: String, to: String)
    /** Forces the file's data to disk (`fsync`). */
    fun sync(path: String)
    fun usableSpace(dir: String): Long
    /** True when [path] resolves to a file directly inside [dir] (no `..`, no link out): the guard before a delete. */
    fun isDirectChild(dir: String, path: String): Boolean
    fun reader(path: String): FileReader
    fun writer(path: String, append: Boolean): FileWriter

    /** The MD5 of the whole file, lowercase hex. */
    fun md5Hex(path: String): String {
        val md5 = Md5()
        val r = reader(path)
        try {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = r.read(buffer)
                if (n < 0) break
                md5.update(buffer, 0, n)
            }
        } finally {
            r.close()
        }
        return md5.hex()
    }

    /** [length] bytes starting at [offset] (fewer at the end of the file). */
    fun readAt(path: String, offset: Long, length: Int): ByteArray
}

fun join(dir: String, name: String): String = dir.trimEnd('/') + "/" + name.trimStart('/')

/** The file operations of this platform. */
expect fun createPlatformFileOps(): FileOps
