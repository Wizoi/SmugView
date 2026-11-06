package com.smugview.app.screen

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tester feedback: in "Save to collection" the "New Collection Name..." hint sat low in its box and was cut off. A Material text field is at
 * least 56 dp tall (its label slot and padding need that); the row forced it to 48 dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NewCollectionRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `the name field is tall enough to show its hint`() {
        compose.setContent { com.smugview.app.ui.component.NewCollectionRow(name = "", onNameChange = {}, onCreate = {}) }
        val heightDp = compose.onNode(hasSetTextAction()).fetchSemanticsNode().size.height / compose.density.density
        assertTrue("the field is $heightDp dp tall; a Material text field needs at least 56 dp to show its hint without clipping", heightDp >= 56f)
    }
}
