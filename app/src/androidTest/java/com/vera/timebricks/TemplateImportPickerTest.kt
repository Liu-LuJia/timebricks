package com.vera.timebricks

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Renders the production picker in the Compose test host; never opens user storage. */
class TemplateImportPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyListOpensAndCancelRemainsAvailable() {
        var closed = false
        compose.setContent {
            MaterialTheme {
                TemplateImportPicker(emptyList(), false, false, "", { closed = true }) { _, _ ->
                    error("Empty list cannot import")
                }
            }
        }
        compose.onNodeWithText("暂无模板").assertIsDisplayed()
        compose.onNodeWithText("导入").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test fun selectedTemplateImportsWithoutReplacement() {
        var request: Pair<String, Boolean>? = null
        compose.setContent {
            MaterialTheme {
                TemplateImportPicker(listOf("t" to "工作日"), false, false, "", {}) { id, replace ->
                    request = id to replace
                }
            }
        }
        compose.onNodeWithText("工作日").performClick()
        compose.onNodeWithText("导入").performClick()
        compose.runOnIdle { assertEquals("t" to false, request) }
    }

    @Test fun longListScrollsAndKeepsActionsVisible() {
        var request: Pair<String, Boolean>? = null
        compose.setContent {
            MaterialTheme {
                TemplateImportPicker((1..80).map { "id-$it" to "模板 $it" }, true, false, "", {}) { id, replace ->
                    request = id to replace
                }
            }
        }
        compose.onNodeWithText("模板 80").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("追加").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("id-80" to false, request) }
        compose.onNodeWithText("替换").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("id-80" to true, request) }
    }

    @Test fun busyStateDisablesImportAndCancel() {
        compose.setContent {
            MaterialTheme {
                TemplateImportPicker(listOf("t" to "工作日"), true, true, "", {}) { _, _ ->
                    error("Busy picker cannot import")
                }
            }
        }
        compose.onNodeWithText("追加").assertIsNotEnabled()
        compose.onNodeWithText("替换").assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsNotEnabled()
    }
}
