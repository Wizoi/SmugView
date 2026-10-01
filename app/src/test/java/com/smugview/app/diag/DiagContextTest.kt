package com.smugview.app.diag

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagContextTest {
    @Test fun newActionId_isKindHashCounter_andUnique() {
        val a = DiagContext.newActionId("folder")
        val b = DiagContext.newActionId("folder")
        assertNotEquals(a, b)
        assertTrue(a, Regex("""folder#\d+""").matches(a))
    }

    @Test fun element_setsTheThreadLocalInsideTheBlockOnly() = runBlocking {
        assertNull(DiagContext.currentActionId())
        withContext(DiagContext.element("t#7")) { assertEquals("t#7", DiagContext.currentActionId()) }
        assertNull(DiagContext.currentActionId())
    }
}
