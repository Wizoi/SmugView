package com.smugview.app.scenario

import com.smugview.app.data.api.canonicalImageKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 4-9 (design 3.5, R-30, P5): `image/{key}` and `image/{key}!metadata` answer `301` to `{key}-0` with
 * `Cache-Control: private, no-store`, so the redirect is never cacheable and a photo's details and EXIF
 * failed offline even after being viewed. The app asks for `{key}-0` directly (the 200 is stored under it).
 * The production client runs over the fake on a real socket; `image/{key}` 301s like the live API.
 * The key is the photo's `ImageKey` (no serial), as the live album listing gives it (P4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ImageDetailsOfflineTest {
    private lateinit var rig: ScenarioRig
    private val key = "N74KSKi001"

    @Before fun setUp() { rig = ScenarioRig(httpCache = true) }
    @After fun tearDown() = rig.close()

    /** Red on the old code: the cached thing was a 301 with no-store, so offline answered OkHttp's synthetic 504. */
    @Test fun `a photo viewed online has its details served offline`() = runBlocking {
        val online = rig.repository.getImage(key, "test-key").first()
        assertTrue("online: ${online.exceptionOrNull()}", online.isSuccess)

        rig.loopback!!.online = false
        val offline = rig.repository.getImage(key, "test-key").first()

        assertTrue("offline details: ${offline.exceptionOrNull()}", offline.isSuccess)
        assertEquals(key, offline.getOrNull()?.imageKey)
    }

    @Test fun `a photo's EXIF viewed online is served offline`() = runBlocking {
        val online = rig.repository.getImageExif(key, "test-key").first()
        assertTrue("online: ${online.exceptionOrNull()}", online.isSuccess)

        rig.loopback!!.online = false
        val offline = rig.repository.getImageExif(key, "test-key").first()

        assertTrue("offline EXIF: ${offline.exceptionOrNull()}", offline.isSuccess)
        assertEquals("Canon", offline.getOrNull()?.make)
    }

    /** The requests go straight to `-0`: no 301 round trip on the wire. */
    @Test fun `details and EXIF are requested at the canonical -0 path with no redirect`() = runBlocking {
        rig.repository.getImage(key, "test-key").first()
        rig.repository.getImageExif(key, "test-key").first()

        assertEquals(
            listOf("GET image/$key-0", "GET image/$key-0!metadata"),
            rig.server.requestsTo("image/").map { it.substringBefore('?') }
        )
    }

    /** Never viewed: still nothing offline, and the failure is the error the screens already handle. */
    @Test fun `a photo never viewed has no details offline`() = runBlocking {
        rig.loopback!!.online = false

        val offline = rig.repository.getImage(key, "test-key").first()

        assertNotNull(offline.exceptionOrNull())
    }

    @Test fun `the canonical key adds -0 once and leaves a serial alone`() {
        assertEquals("XVRvVTM-0", canonicalImageKey("XVRvVTM"))
        assertEquals("XVRvVTM-0", canonicalImageKey("XVRvVTM-0"))
        assertEquals("XVRvVTM-1", canonicalImageKey("XVRvVTM-1"))
    }
}
