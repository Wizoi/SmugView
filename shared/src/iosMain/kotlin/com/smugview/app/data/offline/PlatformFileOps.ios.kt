@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.smugview.app.data.offline

import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.io.IOException
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.stringByStandardizingPath
import platform.posix.O_RDONLY
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.fsync
import platform.posix.fwrite
import platform.posix.open
import platform.posix.rename

/** The file operations on iOS: `NSFileManager` for the questions, POSIX `rename`, `fsync` and `FILE*` for the data. */
internal class PlatformFileOpsImpl : FileOps {
    private val fm = NSFileManager.defaultManager

    override fun isFile(path: String): Boolean = memScoped {
        val isDirectory = alloc<BooleanVar>()
        fm.fileExistsAtPath(path, isDirectory.ptr) && !isDirectory.value
    }

    override fun length(path: String): Long =
        (fm.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L

    override fun delete(path: String): Boolean = isFile(path) && fm.removeItemAtPath(path, null)

    override fun mkdirs(path: String) {
        fm.createDirectoryAtPath(path, true, null, null)
    }

    override fun listFiles(dir: String): List<String> =
        (fm.contentsOfDirectoryAtPath(dir, null) ?: emptyList<Any?>()).filterIsInstance<String>().filter { isFile(join(dir, it)) }

    override fun moveReplacing(from: String, to: String) {
        if (rename(from, to) != 0) throw IOException("rename to ${to.substringAfterLast('/')} failed (errno $errno)")
    }

    override fun sync(path: String) {
        val fd = open(path, O_RDONLY)
        if (fd >= 0) {
            fsync(fd)
            close(fd)
        }
    }

    override fun usableSpace(dir: String): Long =
        (fm.attributesOfFileSystemForPath(dir, null)?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue ?: 0L

    override fun isDirectChild(dir: String, path: String): Boolean {
        val resolved = standardized(path)
        return resolved.substringBeforeLast('/') == standardized(dir).trimEnd('/')
    }

    private fun standardized(path: String): String = (path as NSString).stringByStandardizingPath

    override fun reader(path: String): FileReader {
        val file = fopen(path, "rb") ?: throw IOException("cannot open $path (errno $errno)")
        return object : FileReader {
            override fun read(buffer: ByteArray): Int {
                if (buffer.isEmpty()) return 0
                val n = buffer.usePinned { fread(it.addressOf(0), 1.convert(), buffer.size.convert(), file) }.toLong()
                return if (n == 0L) -1 else n.toInt()
            }

            override fun close() { fclose(file) }
        }
    }

    override fun writer(path: String, append: Boolean): FileWriter {
        val file = fopen(path, if (append) "ab" else "wb") ?: throw IOException("cannot open $path (errno $errno)")
        return object : FileWriter {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length == 0) return
                val n = bytes.usePinned { fwrite(it.addressOf(offset), 1.convert(), length.convert(), file) }.toLong()
                if (n != length.toLong()) throw IOException("short write to $path")
            }

            override fun close() {
                fflush(file)
                fclose(file)
            }
        }
    }

    override fun readAt(path: String, offset: Long, length: Int): ByteArray {
        val file = fopen(path, "rb") ?: return ByteArray(0)
        try {
            if (fseek(file, offset.convert(), SEEK_SET) != 0) return ByteArray(0)
            val buffer = ByteArray(length)
            val n = buffer.usePinned { fread(it.addressOf(0), 1.convert(), length.convert(), file) }.toInt()
            return if (n == length) buffer else buffer.copyOf(n)
        } finally {
            fclose(file)
        }
    }
}

actual fun createPlatformFileOps(): FileOps = PlatformFileOpsImpl()
