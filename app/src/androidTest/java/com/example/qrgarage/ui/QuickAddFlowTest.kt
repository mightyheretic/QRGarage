package com.example.qrgarage.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.example.qrgarage.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test

/** Acceptance: quick add of a name only succeeds and is findable by prefix search immediately. */
@HiltAndroidTest
class QuickAddFlowTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(5_000) {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun addNameOnlyThenFindByPrefix() {
        waitFor(hasText("Add item"))
        compose.onNodeWithText("Add item").performClick()
        waitFor(hasText("Name *"))
        compose.onNodeWithText("Name *").performTextInput("M6 bolts")
        compose.onNodeWithText("Save").performClick()

        waitFor(hasText("Recent"))
        compose.onNodeWithContentDescription("Search").performTextInput("m6 bol")
        waitFor(hasText("Results"))
        waitFor(hasText("M6 bolts", substring = false))
    }
}
