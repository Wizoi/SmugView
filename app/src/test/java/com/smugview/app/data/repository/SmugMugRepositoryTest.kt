package com.smugview.app.data.repository

import com.smugview.app.data.api.*
import com.smugview.app.data.db.*
import com.smugview.app.data.security.FakePasswordStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import okhttp3.ResponseBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SmugMugRepositoryTest {

    // Real Room (in-memory, Robolectric): REPLACE semantics, the real recursive-CTE SQL and the
    // real 30-day date handling, instead of a hand-written fake (review R-63).
    private lateinit var db: AppDatabase
    private lateinit var dao: CollectionDao

    @Before
    fun setUpDb() {
        db = TestDb.inMemory()
        dao = db.collectionDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun mockContext(): android.content.Context {
        val mockContext = org.mockito.Mockito.mock(android.content.Context::class.java)
        val mockPrefs = org.mockito.Mockito.mock(android.content.SharedPreferences::class.java)
        org.mockito.Mockito.`when`(mockContext.getSharedPreferences(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyInt())).thenReturn(mockPrefs)
        return mockContext
    }

    private fun createMockApi(interceptor: Interceptor): SmugMugApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
            
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.smugmug.com/api/v2/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            
        return retrofit.create(SmugMugApi::class.java)
    }

    @Test
    fun testGetAlbumsInScopeOrchestration() = runBlocking {
        
        val mockInterceptor = Interceptor { chain ->
            val url = chain.request().url.toString()
            val json = if (url.contains("node/folder1!children")) {
                """
                {
                  "Response": {
                    "Uri": "/api/v2/node/folder1!children",
                    "Locator": "Node",
                    "LocatorType": "Objects",
                    "Node": [
                      {
                        "Uri": "/api/v2/node/folder2",
                        "NodeID": "folder2",
                        "Type": "Folder",
                        "Name": "Subfolder",
                        "DateModified": "2026-07-15T06:07:19+00:00",
                        "Uris": {
                          "ChildNodes": "/api/v2/node/folder2!children"
                        }
                      },
                      {
                        "Uri": "/api/v2/node/album1",
                        "NodeID": "album1",
                        "Type": "Album",
                        "Name": "My Gallery",
                        "DateModified": "2026-07-15T06:07:19+00:00",
                        "Uris": {
                          "Album": "/api/v2/album/album1"
                        }
                      }
                    ]
                  },
                  "Code": 200,
                  "Message": "Ok"
                }
                """.trimIndent()
            } else {
                """
                {
                  "Response": {
                    "Uri": "/api/v2/node/folder2!children",
                    "Locator": "Node",
                    "LocatorType": "Objects",
                    "Node": []
                  },
                  "Code": 200,
                  "Message": "Ok"
                }
                """.trimIndent()
            }
            
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }
        
        val api = createMockApi(mockInterceptor)
        val repository = SmugMugRepository(api, dao, FakePasswordStore(), mockContext()).apply { maxPagesPerFetch = 2 }

        // 1. Initially database cache is empty
        val initialAlbums = repository.getAlbumsInScope("folder1")
        assertTrue(initialAlbums.isEmpty())

        // 2. Trigger remote fetch for scope folder1
        val fetched = repository.fetchAlbumsInScopeRemote("testUser", "folder1", "dummy_key")
        
        // Should have found 1 album directly under folder1
        assertEquals(1, fetched.size)
        assertEquals("album1", fetched[0].nodeId)
        
        // 3. Verify they are now cached in the database
        val cachedAlbums = repository.getAlbumsInScope("folder1")
        assertEquals(1, cachedAlbums.size)
        assertEquals("album1", cachedAlbums[0].nodeId)
    }

    @Test
    fun testGetAlbumKeywordsOrchestration() = runBlocking {
        
        val mockInterceptor = Interceptor { chain ->
            val json = """
            {
              "Response": {
                "Uri": "/api/v2/album/album1",
                "Locator": "Album",
                "LocatorType": "Object",
                "Album": {
                  "AlbumKey": "album1",
                  "Uri": "/api/v2/album/album1"
                }
              },
              "Expansions": {
                "/api/v2/album/album1": {
                  "AlbumKeywords": {
                    "Keywords": ["nature", "sunset"]
                  }
                }
              },
              "Code": 200,
              "Message": "Ok"
            }
            """.trimIndent()
            
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }
        
        val api = createMockApi(mockInterceptor)
        val repository = SmugMugRepository(api, dao, FakePasswordStore(), mockContext()).apply { maxPagesPerFetch = 2 }

        // Call repository keywords batch method
        val response = repository.getAlbumKeywords(listOf("album1"), "dummy_key")
        assertNotNull(response.expansions)
        
        val keywords = response.expansions?.get("/api/v2/album/album1")?.albumKeywords?.keywords
        assertNotNull(keywords)
        assertEquals(2, keywords?.size)
        assertTrue(keywords!!.contains("nature"))
        assertTrue(keywords.contains("sunset"))
    }

    @Test
    fun testMockVisibilityAnonymousState() = runBlocking {
        var callCount = 0
        
        val mockInterceptor = Interceptor { chain ->
            callCount++
            val json = if (callCount == 1) {
                // Anonymous: empty expansions
                """
                {
                  "Response": {
                    "Uri": "/api/v2/album/album1",
                    "Locator": "Album",
                    "LocatorType": "Object"
                  },
                  "Expansions": {},
                  "Code": 200,
                  "Message": "Ok"
                }
                """.trimIndent()
            } else {
                // Unlocked: full expansions
                """
                {
                  "Response": {
                    "Uri": "/api/v2/album/album1",
                    "Locator": "Album",
                    "LocatorType": "Object"
                  },
                  "Expansions": {
                    "/api/v2/album/album1": {
                      "AlbumKeywords": {
                        "Keywords": ["nature", "sunset"]
                      }
                    }
                  },
                  "Code": 200,
                  "Message": "Ok"
                }
                """.trimIndent()
            }
            
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }
        
        val api = createMockApi(mockInterceptor)
        val repository = SmugMugRepository(api, dao, FakePasswordStore(), mockContext()).apply { maxPagesPerFetch = 2 }

        // 1. Simulate anonymous/locked state
        val response1 = repository.getAlbumKeywords(listOf("album1"), "dummy_key")
        assertTrue(response1.expansions == null || response1.expansions!!.isEmpty())

        // 2. Simulate unlocked state
        val response2 = repository.getAlbumKeywords(listOf("album1"), "dummy_key")
        assertNotNull(response2.expansions)
        val keywords = response2.expansions?.get("/api/v2/album/album1")?.albumKeywords?.keywords
        assertNotNull(keywords)
        assertEquals(2, keywords?.size)
    }

    @Test
    fun testSearchImagesOrchestrationAndPaging() = runBlocking {
        val gson = com.google.gson.Gson()
        
        val mockInterceptor = Interceptor { chain ->
            val url = chain.request().url.toString()
            val images = if (url.contains("start=251")) {
                (251..300).map { i -> mapOf("ImageKey" to "img_$i", "Title" to "Image $i") }
            } else {
                (1..250).map { i -> mapOf("ImageKey" to "img_$i", "Title" to "Image $i") }
            }
            
            val nextField = if (url.contains("start=251")) null else "/api/v2/image!search?start=251"
            
            val payload = mapOf(
                "Response" to mapOf(
                    "Image" to images,
                    "Pages" to mapOf(
                        "Start" to (if (url.contains("start=251")) 251 else 1),
                        "Count" to images.size,
                        "Total" to 300,
                        "Next" to nextField
                    )
                ),
                "Code" to 200,
                "Message" to "Ok"
            )
            
            val json = gson.toJson(payload)
            
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }
        
        val api = createMockApi(mockInterceptor)
        val repository = SmugMugRepository(api, dao, FakePasswordStore(), mockContext()).apply { maxPagesPerFetch = 2 }

        // Call performBackgroundSearchImages
        repository.performBackgroundSearchImages(
            nickname = "testUser",
            scopeUri = "/api/v2/node/folder1",
            scopeKey = "folder1",
            query = "testQuery",
            apiKey = "dummyKey"
        )

        // Verify they were inserted in search_results table in Fake Dao
        val dbResults = dao.getSearchResults("testQuery", "folder1", "Photo")
        assertEquals(300, dbResults.size)
        assertEquals("img_1", dbResults.first().itemKey)
    }

    @Test
    fun testGetImagesByKeywordRepositoryMapping() = runBlocking {
        var apiScope: String? = null
        var apiText: String? = null
        
        val mockInterceptor = Interceptor { chain ->
            val url = chain.request().url
            apiScope = url.queryParameter("Scope")
            apiText = url.queryParameter("Text")
            
            val json = """
            {
              "Response": {
                "Image": [
                  {
                    "ImageKey": "res1",
                    "Title": "Result Image"
                  }
                ],
                "Pages": {
                  "Start": 1,
                  "Count": 1,
                  "Total": 1
                }
              },
              "Code": 200,
              "Message": "Ok"
            }
            """.trimIndent()
            
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }
        
        val api = createMockApi(mockInterceptor)
        val repository = SmugMugRepository(api, dao, FakePasswordStore(), mockContext()).apply { maxPagesPerFetch = 2 }

        val result = repository.getImagesByKeyword(
            scope = "/api/v2/user/testUser",
            keywords = "clara,idzi",
            apiKey = "dummyKey",
            count = 100,
            start = 5
        )

        assertEquals(1, result.size)
        assertEquals("res1", result[0].imageKey)
        assertEquals("/api/v2/user/testUser", apiScope)
        assertEquals("clara idzi", apiText) // Verify comma is replaced with space
    }

    @Test
    fun testSearchResultMappingPreservesKeywords() {
        val originalImage = AlbumImageData(
            imageKey = "test_key",
            title = "Test Title",
            caption = "Test Caption",
            keywords = "tahoma, marching, band",
            keywordArray = listOf("tahoma", "marching", "band")
        )
        val searchResult = originalImage.toSearchResult("query", "scope", 1)
        assertEquals("tahoma, marching, band", searchResult.keywords)
        
        val mappedBack = searchResult.toAlbumImageData()
        assertEquals("tahoma, marching, band", mappedBack.keywordsString)
    }

    @Test
    fun testResolveAndCacheAlbumLineageSafeForRoot() = runBlocking {
        val mockInterceptor = Interceptor { chain ->
            if (chain.request().url.toString().contains("node/root")) {
                throw RuntimeException("Should not request root node from API")
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .body("".toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }
        val api = createMockApi(mockInterceptor)
        val repository = SmugMugRepository(api, dao, FakePasswordStore(), mockContext()).apply { maxPagesPerFetch = 2 }
        
        dao.insertNodes(listOf(
            CachedNode(
                nodeId = "child_node",
                parentNodeId = "root",
                type = "Album",
                title = "Child Node",
                description = null,
                access = "Public",
                passwordHint = null,
                uri = "/api/v2/album/child_node",
                childNodesUri = null,
                albumUri = "/api/v2/album/child_node"
            )
        ))
        
        val lineage = repository.resolveAndCacheAlbumLineage("child_node", "dummyKey")
        assertEquals(0, lineage.size)
    }

    // testActiveUpdatesDetectionBubblingAndClearing seeded cached_nodes.dateModified; the dot reads the
    // gallery index now (phase 2 step 2-10). Replaced by DotFlowTest and CollectionDaoTest.

    @Test
    fun testSearchPublicSites() = runBlocking {
        
        val mockInterceptor = Interceptor { chain ->
            val url = chain.request().url.toString()
            val json = when {
                url.contains("user!search") -> {
                    """
                    {
                      "Response": {
                        "Uri": "/api/v2/user!search",
                        "Locator": "User",
                        "LocatorType": "Objects",
                        "User": [
                          {
                            "NickName": "glenncampbell",
                            "Name": "Glenn Campbell",
                            "WebUri": "https://glenncampbell.smugmug.com",
                            "Uris": {
                              "UserAlbums": "/api/v2/user/glenncampbell!albums"
                            }
                          },
                          {
                            "NickName": "uphs",
                            "Name": "Union Pacific Historical Society",
                            "WebUri": "https://uphs.smugmug.com",
                            "Uris": {
                              "UserAlbums": "/api/v2/user/uphs!albums"
                            }
                          }
                        ]
                      },
                      "Code": 200,
                      "Message": "Ok"
                    }
                    """.trimIndent()
                }
                url.contains("glenncampbell!recentimages") -> {
                    """
                    {
                      "Response": {
                        "Uri": "/api/v2/user/glenncampbell!recentimages",
                        "Locator": "Image",
                        "LocatorType": "Objects",
                        "Image": [
                          {
                            "ImageKey": "img1",
                            "Title": "Sunset",
                            "Caption": "Nice sunset",
                            "ThumbnailUrl": "https://photos.smugmug.com/img1-th.jpg",
                            "WebUri": "https://glenncampbell.smugmug.com/Nature/Sunset/i-img1"
                          },
                          {
                            "ImageKey": "img2",
                            "Title": "Forest",
                            "Caption": "Deep forest",
                            "ThumbnailUrl": "https://photos.smugmug.com/img2-th.jpg",
                            "WebUri": "https://glenncampbell.smugmug.com/Nature/Forest/i-img2"
                          }
                        ]
                      },
                      "Code": 200,
                      "Message": "Ok"
                    }
                    """.trimIndent()
                }
                url.contains("uphs!recentimages") -> {
                    """
                    {
                      "Response": {
                        "Uri": "/api/v2/user/uphs!recentimages",
                        "Locator": "Image",
                        "LocatorType": "Objects",
                        "Image": [
                          {
                            "ImageKey": "img3",
                            "Title": "Depot History",
                            "Caption": "Union Pacific Depot",
                            "ThumbnailUrl": "https://photos.smugmug.com/img3-th.jpg",
                            "WebUri": "https://uphs.smugmug.com/UP-Facilities/1922-UP-Depots/i-img3"
                          }
                        ]
                      },
                      "Code": 200,
                      "Message": "Ok"
                    }
                    """.trimIndent()
                }
                else -> """{"Code": 404, "Message": "Not found"}"""
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }
        
        val mockApi = createMockApi(mockInterceptor)
        val repo = SmugMugRepository(mockApi, dao, FakePasswordStore(), mockContext()).apply { maxPagesPerFetch = 2 }
        
        val result = repo.searchPublicSites("nature", "fakeApiKey").first()
        val sites = result.getOrThrow()
        assertEquals(2, sites.size)
        
        val glenn = sites.find { it.nickname == "glenncampbell" }
        assertNotNull(glenn)
        assertEquals(2, glenn!!.previewPhotos.size)
        assertEquals("https://glenncampbell.smugmug.com", glenn.webUri)
        
        val uphs = sites.find { it.nickname == "uphs" }
        assertNotNull(uphs)
        assertEquals(1, uphs!!.previewPhotos.size)
        assertEquals("https://uphs.smugmug.com", uphs.webUri)
    }

    @Test
    fun testUnlockingFolderMakesItsGalleriesSearchable() = runBlocking {
        // Regression test for: after unlocking a password-protected folder, its galleries stayed
        // invisible to search forever because getNodeChildren only wrote them into cached_nodes,
        // never into the flat gallery index (cached_albums / albumsCache) that gallery search reads.

        val mockInterceptor = Interceptor { chain ->
            val json = """
            {
              "Response": {
                "Uri": "/api/v2/node/folder1!children",
                "Locator": "Node",
                "LocatorType": "Objects",
                "Node": [
                  {
                    "Uri": "/api/v2/node/albumX",
                    "NodeID": "albumX",
                    "Type": "Album",
                    "Name": "Family Photos",
                    "DateModified": "2026-07-20T10:00:00+00:00",
                    "Uris": {
                      "Album": "/api/v2/album/albumX",
                      "ParentNode": "/api/v2/node/decoyParent"
                    }
                  }
                ]
              },
              "Code": 200,
              "Message": "Ok"
            }
            """.trimIndent()

            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }

        val api = createMockApi(mockInterceptor)
        val repository = SmugMugRepository(api, dao, FakePasswordStore(), mockContext()).apply { maxPagesPerFetch = 2 }

        // Before unlock: gallery search (which filters repository.albumsCache.value by title) finds nothing.
        assertTrue(repository.albumsCache.value.isEmpty())

        // Simulate the unlock flow's post-unlock prefetch (SmugViewModel.apiTestFetch calls
        // getNodeChildren(node.nodeId, apiKey, forceRefresh = true, password) on success).
        val result = repository.getNodeChildren("testUser", "folder1", "dummy_key", forceRefresh = true, password = "gallery").first()
        assertTrue(result.isSuccess)

        // The revealed gallery must now be in the searchable in-memory index...
        val indexed = repository.albumsCache.value.find { it.title == "Family Photos" }
        assertNotNull("Unlocked gallery should be merged into the searchable album index", indexed)
        assertEquals("albumX", indexed?.nodeId)

        // ...and persisted, so it survives process death / the next cache load.
        val persisted = dao.getAlbumIndex("testUser").find { it.albumKey == "albumX" }
        assertNotNull("Unlocked gallery should be persisted into cached_albums", persisted)
    }

    @Test
    fun testGallerySync_neverTakesAParentFromUrisParentNode_andNeverEvictsAFolderListingOnAGuess() = runBlocking {
        // Phase 2 step 2-3 (review R-01, R-05). This test used to pin "a gallery whose user!albums row
        // carries Uris.ParentNode=/node/folder1 evicts folder1's cached listing". That cannot happen
        // live: user!albums has no ParentNode at all, and a node's ParentNode is its OWN !parent link,
        // not its parent. The fixture invented a payload SmugMug never sends. The real invalidation is
        // the changed-parent relist of Phase 2 steps 2-8/2-9. A sync must not guess a parent.
        // Step 2-8: the crawl KEEPS the row's existing parentNodeId (the resolver, step 2-9, owns it),
        // so the decoy ParentNode below must change nothing.

        // Baseline: album already indexed with an older LastUpdated (as if from a previous sync).
        dao.upsertAlbums(listOf(
            CachedAlbum(
                albumKey = "albumX",
                nodeId = "albumX",
                name = "Family Photos",
                securityType = "Public",
                passwordHint = null,
                uri = "/api/v2/album/albumX",
                webUri = null,
                urlPath = null,
                imageCount = 10,
                dateModified = "2026-01-01T00:00:00+00:00",
                galleryStyle = null,
                highlightImageUrl = null,
                sortIndex = 0,
                nickname = "testuser",
                parentNodeId = "folder1"
            )
        ))
        // Baseline: folder1's children are already cached from a previous browse (this is the
        // stale listing that should get evicted once we learn albumX changed).
        dao.insertNodes(listOf(
            CachedNode(
                nodeId = "albumX",
                parentNodeId = "folder1",
                type = "Album",
                title = "Family Photos",
                description = null,
                access = "Public",
                passwordHint = null,
                uri = "/api/v2/album/albumX",
                childNodesUri = null,
                albumUri = "/api/v2/album/albumX"
            )
        ))

        val mockInterceptor = Interceptor { chain ->
            val json = """
            {
              "Response": {
                "Album": [
                  {
                    "Uri": "/api/v2/album/albumX",
                    "AlbumKey": "albumX",
                    "NodeID": "albumX",
                    "Name": "Family Photos",
                    "LastUpdated": "2026-07-20T10:00:00+00:00",
                    "Uris": {
                      "ParentNode": "/api/v2/node/folder1"
                    }
                  }
                ],
                "Pages": { "Total": 1, "Start": 1, "Count": 1 }
              },
              "Code": 200,
              "Message": "Ok"
            }
            """.trimIndent()

            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }

        val api = createMockApi(mockInterceptor)
        val repository = SmugMugRepository(api, dao, FakePasswordStore(), mockContext()).apply { maxPagesPerFetch = 2 }

        // Sanity: folder1's stale listing is present before the sync.
        assertEquals(1, dao.getAllCachedNodes().count { it.parentNodeId == "folder1" })

        repository.buildInMemoryGalleryCache("testuser", "dummy_key")

        // The gallery index picked up the new LastUpdated...
        val updated = dao.getAlbumIndex("testuser").find { it.albumKey == "albumX" }
        assertEquals("2026-07-20T10:00:00+00:00", updated?.dateModified)

        // ...the index row does not take a parent from ParentNode (R-01); it keeps what it had...
        assertEquals("folder1", updated?.parentNodeId)

        // ...and folder1's cached listing is left alone: a sync has no real parent to evict by.
        assertEquals(
            "A sync must not evict a listing on the strength of a ParentNode it cannot trust",
            1,
            dao.getAllCachedNodes().count { it.parentNodeId == "folder1" }
        )
    }

    @Test
    fun testUnlockAndIndexSubtreeFindsGalleriesNestedUnderSubfolders() = runBlocking {
        // Regression test for: unlocking a folder only merged its DIRECT children into the search
        // index. Real sites commonly nest galleries under sub-folders (locked "family" ->
        // "school" -> the actual gallery), so a single-level fetch left search still blind to
        // anything deeper even though the folder itself showed as unlocked.

        val mockInterceptor = Interceptor { chain ->
            val url = chain.request().url.toString()
            val json = if (url.contains("node/family!children")) {
                """
                {
                  "Response": {
                    "Uri": "/api/v2/node/family!children",
                    "Node": [
                      {
                        "Uri": "/api/v2/node/school",
                        "NodeID": "school",
                        "Type": "Folder",
                        "Name": "School",
                        "Uris": { "ChildNodes": "/api/v2/node/school!children" }
                      }
                    ]
                  },
                  "Code": 200,
                  "Message": "Ok"
                }
                """.trimIndent()
            } else if (url.contains("node/school!children")) {
                """
                {
                  "Response": {
                    "Uri": "/api/v2/node/school!children",
                    "Node": [
                      {
                        "Uri": "/api/v2/node/graduationAlbum",
                        "NodeID": "graduationAlbum",
                        "Type": "Album",
                        "Name": "Graduation Day",
                        "DateModified": "2026-07-20T10:00:00+00:00",
                        "Uris": {
                          "Album": "/api/v2/album/graduationAlbum",
                          "ParentNode": "/api/v2/node/school"
                        }
                      }
                    ]
                  },
                  "Code": 200,
                  "Message": "Ok"
                }
                """.trimIndent()
            } else {
                """{"Response": {"Node": []}, "Code": 200, "Message": "Ok"}"""
            }

            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }

        val api = createMockApi(mockInterceptor)
        val repository = SmugMugRepository(api, dao, FakePasswordStore(), mockContext()).apply { maxPagesPerFetch = 2 }

        assertTrue(repository.albumsCache.value.isEmpty())

        repository.unlockAndIndexSubtree("testUser", "family", "dummy_key", "gallery")

        // Fetching "family"'s direct children alone would only see the "school" sub-folder, not
        // the gallery underneath it. The recursive walk must have descended into "school" too.
        val indexed = repository.albumsCache.value.find { it.title == "Graduation Day" }
        assertNotNull(
            "Gallery nested two levels under the unlocked folder should be indexed for search",
            indexed
        )
        assertEquals("graduationAlbum", indexed?.nodeId)
    }

    @Test
    fun testIsIndexingSubtreeReflectsInProgressState() = runBlocking {
        // Regression coverage for the "search reads incomplete data mid-background-sync" fix:
        // SearchController waits on this flag before trusting repository.albumsCache.value, so it
        // must actually flip true while unlockAndIndexSubtree is running and back to false once done.
        val mockInterceptor = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    """{"Response": {"Node": []}, "Code": 200, "Message": "Ok"}"""
                        .toResponseBody("application/json".toMediaTypeOrNull())
                )
                .build()
        }
        val api = createMockApi(mockInterceptor)
        val repository = SmugMugRepository(api, dao, FakePasswordStore(), mockContext()).apply { maxPagesPerFetch = 2 }

        assertFalse("Should be idle before any indexing starts", repository.isIndexingSubtree.value)

        val job = launch { repository.unlockAndIndexSubtree("testUser", "folderA", "dummy_key", "pw") }
        yield() // let the job run up to its first suspension point
        assertTrue("Flag should be true while indexing is in progress", repository.isIndexingSubtree.value)

        job.join()
        assertFalse("Should be idle again after indexing completes", repository.isIndexingSubtree.value)
    }

    @Test
    fun testIsIndexingSubtreeStaysTrueUntilAllOverlappingCallsFinish() = runBlocking {
        // Two folders unlocked back-to-back must not have the FIRST call's completion
        // prematurely clear the flag while the SECOND is still walking its subtree. "shortFolder"
        // has no children (one BFS iteration); "longFolder" has a chain of nested sub-folders
        // (several iterations, each with its own internal delay), so it deterministically finishes
        // later than shortFolder — relying on two independent real-time delays racing each other
        // under runBlocking's single-threaded event loop is not reliable (verified: flaked because
        // both ~200ms delays resolved close enough together that completion order wasn't
        // guaranteed).
        fun childrenResponse(nextNodeId: String?): String {
            val nodesJson = if (nextNodeId == null) "[]" else """
                [{
                    "Uri": "/api/v2/node/$nextNodeId",
                    "NodeID": "$nextNodeId",
                    "Type": "Folder",
                    "Name": "Sub",
                    "Uris": { "ChildNodes": "/api/v2/node/$nextNodeId!children" }
                }]
            """.trimIndent()
            return """{"Response": {"Node": $nodesJson}, "Code": 200, "Message": "Ok"}"""
        }
        val mockInterceptor = Interceptor { chain ->
            val url = chain.request().url.toString()
            val json = when {
                url.contains("node/longFolder!children") -> childrenResponse("longFolderSub1")
                url.contains("node/longFolderSub1!children") -> childrenResponse("longFolderSub2")
                url.contains("node/longFolderSub2!children") -> childrenResponse("longFolderSub3")
                else -> childrenResponse(null) // shortFolder and the end of longFolder's chain
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }
        val api = createMockApi(mockInterceptor)
        val repository = SmugMugRepository(api, dao, FakePasswordStore(), mockContext()).apply { maxPagesPerFetch = 2 }

        val shortJob = launch { repository.unlockAndIndexSubtree("testUser", "shortFolder", "dummy_key", "pw1") }
        val longJob = launch { repository.unlockAndIndexSubtree("testUser", "longFolder", "dummy_key", "pw2") }
        yield()
        assertTrue(repository.isIndexingSubtree.value)

        shortJob.join()
        assertTrue("longJob is still running — flag must stay true", repository.isIndexingSubtree.value)

        longJob.join()
        assertFalse("Both jobs finished — flag must clear", repository.isIndexingSubtree.value)
    }

    @Test
    fun testUnlockRoot_isUnlockedByNodeId_andNeverInsertedIntoTheTree() = runBlocking {
        // Phase 2 step 2-3: this used to assert the unlock root was cached with access "Password" (not
        // privacy). The insert was the R-04 bug (a row under a parent nobody knew), so the root is now
        // held in memory only; what matters is that the right node is unlocked and the tree is untouched.
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        val mockNodeData = com.smugview.app.data.api.NodeData(
            uri = "/api/v2/node/testNodeId",
            nodeId = "testNodeId",
            type = "Folder",
            name = "Test Locked Folder",
            description = null,
            securityType = "Password",
            privacy = "Public",
            passwordHint = "Hint",
            webUri = "https://test.weburi",
            uris = com.smugview.app.data.api.NodeUris(
                parentNode = "/api/v2/node/root"
            ),
            dateModified = null
        )
        
        val mockInterceptor = Interceptor { chain ->
            val request = chain.request()
            seen += "${request.method} ${request.url.encodedPath.removePrefix("/api/v2/")}"
            val json = if (request.method == "POST") {
                "{}"
            } else if (request.url.encodedPath.endsWith("!parents")) {
                parentsDoc("testNodeId", "Password")
            } else {
                com.google.gson.Gson().toJson(com.smugview.app.data.api.SingleNodeResponse(com.smugview.app.data.api.SingleNodePayload(mockNodeData)))
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }
        
        val api = createMockApi(mockInterceptor)
        val repository = SmugMugRepository(api, dao, FakePasswordStore(), mockContext()).apply { maxPagesPerFetch = 2 }
        
        val ok = repository.unlockInheritedPasswordRoot("testNodeId", "dummy_key", "password")

        assertTrue(ok)
        assertEquals(listOf("POST node/testNodeId!unlock"), seen.filter { it.startsWith("POST") })
        assertEquals("the unlock root must not be inserted under an unknown parent", null, dao.getNodeById("testNodeId"))
        assertEquals(emptyList<CachedNode>(), dao.getAllCachedNodes())
    }

    // R-21: a saved password was deleted on ANY failed unlock (offline, 429, 5xx, the synthetic 504),
    // and the user was told it was "no longer valid". Only an explicit 401 means the password is wrong
    // (verified live 2026-09-30: wrong password -> HTTP 401 "Invalid password.").
    private fun unlockRootWith(unlockBehaviour: (okhttp3.Request) -> Response): Pair<Boolean, FakePasswordStore> = runBlocking {
        val store = FakePasswordStore(mapOf("2sDN5x" to "secret"))
        val folder = NodeData(
            uri = "/api/v2/node/2sDN5x", nodeId = "2sDN5x", type = "Folder", name = "Family",
            description = null, securityType = "Password", privacy = "Public", passwordHint = null,
            webUri = "https://x.smugmug.com/Family",
            uris = NodeUris(parentNode = "/api/v2/node/2sDN5x!parent"), dateModified = null
        )
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            if (req.method == "POST") {
                unlockBehaviour(req)
            } else if (req.url.encodedPath.endsWith("!parents")) {
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(parentsDoc("2sDN5x", "Password").toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            } else {
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(com.google.gson.Gson().toJson(SingleNodeResponse(SingleNodePayload(folder)))
                        .toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            }
        }
        val repository = SmugMugRepository(createMockApi(interceptor), dao, store, mockContext())
        val ok = repository.unlockInheritedPasswordRoot("2sDN5x", "dummy_key", "secret")
        ok to store
    }

    /** `node/{id}!parents` for a single self-first node (SmugMug never sends "Inherited", findings #19). */
    private fun parentsDoc(id: String, security: String) =
        """{"Response":{"Node":[{"Uri":"/api/v2/node/$id","NodeID":"$id","Type":"Folder","SecurityType":"$security"}]},"Code":200}"""

    private fun codeResponse(req: okhttp3.Request, code: Int) =
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body("{}".toResponseBody("application/json".toMediaTypeOrNull())).build()

    @Test
    fun failedUnlock_onNetworkError_keepsSavedPassword() {
        val (ok, store) = unlockRootWith { throw java.io.IOException("offline") }
        assertFalse(ok)
        assertEquals("secret", store.getPassword("2sDN5x"))
    }

    @Test
    fun failedUnlock_on429And5xxAnd504_keepsSavedPassword() {
        for (code in listOf(429, 500, 503, 504)) {
            val (ok, store) = unlockRootWith { codeResponse(it, code) }
            assertFalse(ok)
            assertEquals("password deleted on HTTP $code", "secret", store.getPassword("2sDN5x"))
        }
    }

    @Test
    fun failedUnlock_on401_removesSavedPassword() {
        val (ok, store) = unlockRootWith { codeResponse(it, 401) }
        assertFalse(ok)
        assertNull(store.getPassword("2sDN5x"))
    }
}
