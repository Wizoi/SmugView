package com.smugview.app.data.offline

import com.smugview.app.data.db.CachedNode
import com.smugview.app.data.db.inTransaction
import com.smugview.app.data.db.newInMemoryDatabase
import com.smugview.app.util.Md5
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A new, empty directory for one test (each platform makes it its own way). */
expect fun newTempDir(): String

/**
 * iOS port, Stage 1: the file operations, the MD5 and the transaction helper that the shared offline code stands on. They run on the JVM
 * (the old `java.io` code) and on the iOS simulator (`NSFileManager`, POSIX, plain-Kotlin MD5): the same assertions on both.
 */
class FileOpsTest {
    private val fs = createPlatformFileOps()

    private fun write(path: String, text: String, append: Boolean = false) {
        val bytes = text.encodeToByteArray()
        val w = fs.writer(path, append)
        try { w.write(bytes, 0, bytes.size) } finally { w.close() }
    }

    private fun hexOf(text: String, chunk: Int = Int.MAX_VALUE): String {
        val md5 = Md5(); val bytes = text.encodeToByteArray()
        var i = 0
        while (i < bytes.size) { val n = minOf(chunk, bytes.size - i); md5.update(bytes, i, n); i += n }
        return md5.hex()
    }

    @Test fun md5MatchesTheRfc1321Vectors() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", hexOf(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", hexOf("abc"))
        assertEquals("f96b697d7cb7938d525a2f31aaf161d0", hexOf("message digest"))
        assertEquals("c3fcd3d76192e4007dfb496cca67e13b", hexOf("abcdefghijklmnopqrstuvwxyz"))
        assertEquals("57edf4a22be3c955ac49da2e2107b67a", hexOf("1234567890".repeat(8)))
    }

    @Test fun md5DoesNotDependOnHowTheBytesAreCut() {
        val text = "The quick brown fox jumps over the lazy dog. ".repeat(500)
        val whole = hexOf(text)
        assertEquals(whole, hexOf(text, chunk = 1))
        assertEquals(whole, hexOf(text, chunk = 63))
        assertEquals(whole, hexOf(text, chunk = 64))
        assertEquals(whole, hexOf(text, chunk = 65))
    }

    @Test fun aWrittenFileIsReadBackAndHashed() {
        val dir = newTempDir()
        val path = join(dir, "a.bin")
        assertFalse(fs.isFile(path))
        write(path, "hello ")
        write(path, "world", append = true)
        assertTrue(fs.isFile(path))
        assertEquals(11L, fs.length(path))
        assertEquals("5eb63bbbe01eeed093cb22bb8f5acdc3", fs.md5Hex(path))
        assertEquals("world", fs.readAt(path, 6, 100).decodeToString())
        assertEquals("hel", fs.readAt(path, 0, 3).decodeToString())
        assertEquals(0, fs.readAt(path, 50, 10).size)
        fs.sync(path)
    }

    @Test fun listingDeletingAndAtomicReplace() {
        val dir = newTempDir()
        write(join(dir, "one"), "1"); write(join(dir, "two"), "22")
        fs.mkdirs(join(dir, "sub"))
        assertEquals(setOf("one", "two"), fs.listFiles(dir).toSet())

        write(join(dir, "new"), "NEW")
        fs.moveReplacing(join(dir, "new"), join(dir, "two"))
        assertFalse(fs.isFile(join(dir, "new")))
        assertEquals("NEW", fs.readAt(join(dir, "two"), 0, 10).decodeToString())

        assertTrue(fs.delete(join(dir, "one")))
        assertFalse(fs.delete(join(dir, "one")))
        assertEquals(0L, fs.length(join(dir, "one")))
        assertTrue(fs.usableSpace(dir) > 0L)
        assertEquals(emptyList(), fs.listFiles(join(dir, "missing")))
    }

    @Test fun aPathThatEscapesTheDirectoryIsNotItsChild() {
        val dir = newTempDir()
        write(join(dir, "inside"), "x")
        fs.mkdirs(join(dir, "sub")); write(join(dir, "sub/deep"), "y")
        assertTrue(fs.isDirectChild(dir, join(dir, "inside")))
        assertFalse(fs.isDirectChild(dir, join(dir, "sub/deep")))
        assertFalse(fs.isDirectChild(dir, join(dir, "sub/../../outside")))
        assertTrue(fs.isDirectChild(dir, join(dir, "sub/../inside")))
    }

    @Test fun aTransactionRollsBackWhenItThrowsAndKeepsWhatItWroteWhenItDoesNot() = runTest {
        val dao = newInMemoryDatabase().let { db ->
            fun node(id: String) = CachedNode(
                nodeId = id, parentNodeId = null, type = "Folder", title = id, description = null, access = "Public", passwordHint = null,
                uri = "/api/v2/node/$id", childNodesUri = null, albumUri = null, nickname = "n"
            )
            assertFailsWith<IllegalStateException> {
                db.inTransaction { db.collectionDao().insertNodes(listOf(node("gone"))); error("boom") }
            }
            assertNull(db.collectionDao().getNodeById("gone"), "rolled back")
            db.inTransaction { db.collectionDao().insertNodes(listOf(node("kept"))) }
            db.collectionDao()
        }
        assertNotNull(dao.getNodeById("kept"))
    }
}
