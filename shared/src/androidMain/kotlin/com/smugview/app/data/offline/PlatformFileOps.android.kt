package com.smugview.app.data.offline

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

internal class PlatformFileOpsImpl : FileOps {
    override fun isFile(path: String) = File(path).isFile
    override fun length(path: String) = File(path).length()
    override fun delete(path: String) = File(path).delete()
    override fun mkdirs(path: String) { File(path).mkdirs() }
    override fun listFiles(dir: String): List<String> = File(dir).listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()

    override fun moveReplacing(from: String, to: String) {
        try {
            Files.move(Paths.get(from), Paths.get(to), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.FileSystemException) {
            throw IOException("rename to ${File(to).name} failed", e)
        }
    }

    override fun sync(path: String) { RandomAccessFile(path, "rw").use { it.fd.sync() } }
    override fun usableSpace(dir: String) = File(dir).usableSpace

    override fun isDirectChild(dir: String, path: String): Boolean =
        File(path).canonicalFile.parentFile == File(dir).canonicalFile

    override fun reader(path: String): FileReader {
        val input = FileInputStream(path)
        return object : FileReader {
            override fun read(buffer: ByteArray) = input.read(buffer)
            override fun close() = input.close()
        }
    }

    override fun writer(path: String, append: Boolean): FileWriter {
        val out = FileOutputStream(path, append)
        return object : FileWriter {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
            override fun close() { out.flush(); out.close() }
        }
    }

    override fun readAt(path: String, offset: Long, length: Int): ByteArray = RandomAccessFile(path, "r").use { f ->
        if (offset >= f.length()) return ByteArray(0)
        f.seek(offset)
        val buffer = ByteArray(minOf(length.toLong(), f.length() - offset).toInt())
        f.readFully(buffer)
        buffer
    }
}

actual fun createPlatformFileOps(): FileOps = PlatformFileOpsImpl()
