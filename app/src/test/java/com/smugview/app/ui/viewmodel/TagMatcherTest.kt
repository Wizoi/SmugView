package com.smugview.app.ui.viewmodel

import com.smugview.app.data.api.AlbumImageData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-60: the tag filter, with the exclusion side that no other test covered. */
class TagMatcherTest {
    private fun photo(title: String? = null, caption: String? = null, fileName: String? = null, keywords: String? = null) =
        AlbumImageData(imageKey = "k", title = title, caption = caption, fileName = fileName, keywords = keywords ?: "", keywordArray = emptyList())

    @Test fun anIncludedTagMatchesTitleCaptionFileNameOrKeywords() {
        val m = TagMatcher(included = listOf("dancing"), excluded = emptyList())
        assertTrue(m.matches(photo(title = "Dancing Queen")))
        assertTrue(m.matches(photo(caption = "a dancing crowd")))
        assertTrue(m.matches(photo(fileName = "IMG-dancing_01.jpg")))
        assertTrue(m.matches(photo(keywords = "stage; dancing")))
        assertFalse(m.matches(photo(title = "Singing")))
    }

    @Test fun aTagMustHaveAllItsWordsAndAllIncludedTagsMustMatch() {
        val m = TagMatcher(included = listOf("fourth of july", "fireworks"), excluded = emptyList())
        assertTrue(m.matches(photo(title = "Fireworks on the Fourth of July")))
        assertFalse("one tag missing", m.matches(photo(title = "Fourth of July parade")))
        assertFalse("part of a multi-word tag is not enough", m.matches(photo(title = "July fireworks")))
    }

    @Test fun anExcludedTagRemovesAPhotoThatHasAllItsWords() {
        val m = TagMatcher(included = listOf("dancing"), excluded = listOf("school play"))
        assertTrue(m.matches(photo(title = "Dancing at the gala")))
        assertFalse(m.matches(photo(title = "Dancing in the school play")))
        assertTrue("only one word of the excluded tag", m.matches(photo(title = "Dancing at school")))
    }
}
