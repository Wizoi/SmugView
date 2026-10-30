package com.smugview.app.data.repository

import com.smugview.app.data.api.ApiHttp
import com.smugview.app.data.api.KtorSmugMugApi
import com.smugview.app.data.db.newInMemoryDatabase
import com.smugview.app.data.security.PasswordStore
import com.smugview.app.diag.SyncReporter
import com.smugview.shared.api.readFixture
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * iOS port, Stage 1: the real [SmugMugRepository] over the real [KtorSmugMugApi] and the real Room database, with a mock engine replaying
 * recorded real SmugMug answers of the owner's public site. It runs on the JVM and on the iOS simulator: the repository has so far been
 * COMPILED for iOS, and this is the first time it RUNS there (coroutines, locks, Room, JSON, all on Kotlin/Native).
 */
class RepositoryEndToEndTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<String>()

    private fun engine() = MockEngine { request ->
        val path = request.url.encodedPath
        requests += path
        val body = when {
            path.endsWith("/user/idzifamily") -> readFixture("getUserProfile__site.json")
            path.contains("4zqWw") && path.contains("children") -> readFixture("getNodeChildren__root.json")
            else -> null
        }
        if (body == null) respond("{}", HttpStatusCode.NotFound, json) else respond(body, HttpStatusCode.OK, json)
    }

    private class MemoryPasswordStore : PasswordStore {
        private val map = HashMap<String, String>()
        override val unlockedKeys: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val revision: StateFlow<Long> = MutableStateFlow(0L)
        override val keptOnlyThisSession: StateFlow<Boolean> = MutableStateFlow(false)
        override fun getPassword(key: String?): String? = key?.let { map[it] }
        override fun savePassword(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun all(): Map<String, String> = map.toMap()
    }

    private fun repository() = SmugMugRepository(
        KtorSmugMugApi(ApiHttp(HttpClient(engine()))), newInMemoryDatabase().collectionDao(), MemoryPasswordStore(), SyncReporter.NOOP
    )

    @Test fun theRootNodeComesFromTheProfile() = runTest {
        val root = repository().getUserRootNodeId("idzifamily", "K").first().getOrThrow()
        assertEquals("4zqWw", root)
    }

    @Test fun theChildrenOfTheRootAreFetchedStoredAndThenServedFromRoom() = runTest {
        val repository = repository()
        val first = repository.getNodeChildren("idzifamily", "4zqWw", "K").toList().last().getOrThrow()
        assertEquals(20, first.size)
        assertTrue(first.any { it.nodeId == "8Tf869" && it.title == "Seattle All City Marching Band" }, "the first gallery of the real answer is there")
        val asked = requests.size

        val again = repository.getNodeChildren("idzifamily", "4zqWw", "K").toList().last().getOrThrow()
        assertEquals(first.map { it.nodeId }.toSet(), again.map { it.nodeId }.toSet())
        assertEquals(asked, requests.size, "the second read is served by Room: no new request")
    }

    @Test fun aMissingFolderIsAFailureNotACrash() = runTest {
        val result = repository().getNodeChildren("idzifamily", "nope", "K").toList().last()
        assertTrue(result.isFailure)
    }
}
