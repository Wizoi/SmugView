package com.smugview.app.data.repository

import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.CachedAlbum
import com.smugview.app.data.db.CachedNode
import com.smugview.app.data.db.CollectionDao
import com.smugview.app.data.db.TestDb
import com.smugview.app.data.security.FakePasswordStore
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Phase 2 step 2-2: which node holds the password for a node, read from `node/{id}!parents`.
 * SmugMug never sends SecurityType "Inherited" (findings #19): a sub-folder of a password folder is
 * "None" with EffectiveSecurityType "Password", so walking up "while Inherited" stopped at School.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PasswordRootResolutionTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: CollectionDao
    private val server = FakeSmugMugServer()
    private val store = FakePasswordStore(mapOf("2sDN5x" to "secret", "LCdk7F" to "secret"))

    @Before fun setUp() {
        db = TestDb.inMemory()
        dao = db.collectionDao()
    }

    @After fun tearDown() = db.close()

    private fun repo() = SmugMugRepository(
        server.api(), dao, store,
        org.mockito.Mockito.mock(android.content.Context::class.java)
    )

    private fun resolve(id: String) = runBlocking { repo().resolvePasswordRootNodeId(id, "k") }

    @Test fun rootOfSchool_isFamily_notSchool() {
        // School is SecurityType "None"; the old walk stopped there and returned "P4BKB".
        assertEquals(RootResolution.Resolved("2sDN5x"), resolve("P4BKB"))
    }

    @Test fun rootOfGalleryNodeId_isFamily() {
        assertEquals(RootResolution.Resolved("2sDN5x"), resolve("LCdk7F"))
    }

    @Test fun rootOfFamilyItself_isFamily() {
        assertEquals(RootResolution.Resolved("2sDN5x"), resolve("2sDN5x"))
    }

    @Test fun publicNodes_areNotProtected() {
        assertEquals(RootResolution.NotProtected, resolve("sXQz4G"))
        assertEquals(RootResolution.NotProtected, resolve("3BxbFF"))
    }

    @Test fun albumKey_isMappedToItsNodeId_viaTheIndexRow() {
        runBlocking {
            dao.upsertAlbums(
                listOf(
                    CachedAlbum(
                        albumKey = "FfHCms", nodeId = "LCdk7F", name = "New School Year", securityType = null,
                        passwordHint = null, uri = "/api/v2/album/FfHCms", webUri = null, urlPath = null,
                        imageCount = null, dateModified = null, galleryStyle = null, highlightImageUrl = null,
                        nickname = "idzifamily"
                    )
                )
            )
        }
        assertEquals(RootResolution.Resolved("2sDN5x"), resolve("FfHCms"))
        assertEquals(listOf("GET node/LCdk7F!parents"), server.requestsTo("!parents"))
    }

    @Test fun albumKey_withNoIndexRow_isMappedViaTheAlbumEndpoint() {
        assertEquals(RootResolution.Resolved("2sDN5x"), resolve("FfHCms"))
        assertEquals(
            listOf("GET node/FfHCms!parents", "GET node/LCdk7F!parents"), server.requestsTo("!parents")
        )
        assertEquals(listOf("GET album/FfHCms"), server.requestsTo("album/"))
    }

    private fun failing(code: Int?) = { req: okhttp3.Request ->
        if (code == null) throw IOException("offline")
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body("{}".toResponseBody("application/json".toMediaTypeOrNull())).build()
    }

    @Test fun unreadableLineage_is_Unknown_on503_andOnIoError() {
        server.parentsOverride = failing(503)
        assertEquals(RootResolution.Unknown, resolve("P4BKB"))
        server.parentsOverride = failing(null)
        assertEquals(RootResolution.Unknown, resolve("P4BKB"))
    }

    // Step 3-11: the Boolean unlockInheritedPasswordRoot is gone; its job is UnlockManager.ensureSession
    // (background paths) and UnlockManager.submit (the prompt, the only place a saved password is deleted).
    private fun school() = CachedNode(
        nodeId = "P4BKB", parentNodeId = "2sDN5x", type = "Folder", title = "School", description = null,
        access = "None", passwordHint = null, uri = "/api/v2/node/P4BKB", childNodesUri = null, albumUri = null
    )

    @Test fun unlock_goesToTheRealRoot_andOnlyThere() {
        val result = runBlocking { repo().unlocks.ensureSession("P4BKB", "k", "secret") }
        assertEquals(SmugMugRepository.UnlockResult.Success, result)
        assertEquals(listOf("POST node/2sDN5x!unlock"), server.requests.filter { it.startsWith("POST") })
    }

    @Test fun unlock_whenLineageUnknown_makesNoUnlockCall_andKeepsEveryPassword() {
        server.parentsOverride = failing(503)
        val result = runBlocking { repo().unlocks.ensureSession("P4BKB", "k", "secret") }
        assertEquals(SmugMugRepository.UnlockResult.Transient, result)
        assertEquals(emptyList<String>(), server.requests.filter { it.startsWith("POST") })
        assertEquals("secret", store.getPassword("2sDN5x"))
        assertEquals("secret", store.getPassword("LCdk7F"))
        assertEquals(setOf("2sDN5x", "LCdk7F"), store.all().keys)
    }

    @Test fun unlock_whenRootRejects401_atThePrompt_removesThePasswordsForThatRoot() {
        // The R-21 rule, now owned by the prompt path: only an explicit 401 to the user's attempt deletes.
        server.unlockCode = 401
        val result = runBlocking { repo().unlocks.submit(school(), "secret", "k") }
        assertEquals(UnlockManager.Submit.Rejected, result)
        assertEquals(null, store.getPassword("2sDN5x"))
    }

    @Test fun unlock_whenRootRejects401_inTheBackground_keepsTheSavedPassword() {
        // The old unlockInheritedPasswordRoot deleted here too; UnlockManager marks the root Invalid
        // and leaves the deleting to the prompt (step 3-6), so a transient mistake cannot cost a password.
        server.unlockCode = 401
        val repo = repo()
        val result = runBlocking { repo.unlocks.ensureSession("P4BKB", "k", "secret") }
        assertEquals(SmugMugRepository.UnlockResult.Rejected, result)
        assertEquals("secret", store.getPassword("2sDN5x"))
    }
}
