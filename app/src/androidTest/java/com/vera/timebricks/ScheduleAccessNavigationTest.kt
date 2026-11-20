package com.vera.timebricks

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

class ScheduleAccessNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun settingsDetailSurvivesRecreationAndReturnsToDataManagement() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        check(app.packageName == "com.vera.timebricks.scheduleapitest") { "Use -PscheduleApiCheck=true" }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithText("数据管理").performScrollTo().performClick()
            compose.onNodeWithText("日程读取授权").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("com.vera.selfpact").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("当前授权状态").assertIsDisplayed()
            scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("com.vera.selfpact").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("当前授权状态").assertIsDisplayed()
            instrumentation.uiAutomation.takeScreenshot().useBitmap { screenshot ->
                File(app.getExternalFilesDir(null), "schedule-access.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            pressBack()
            compose.onNodeWithText("导出备份").assertIsDisplayed()
            compose.onNodeWithText("日程读取授权").performScrollTo().performClick()
            compose.onNodeWithText("返回").performClick()
            compose.onNodeWithText("导出备份").assertIsDisplayed()
        }
    }

    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) {
        try { block(this) } finally { recycle() }
    }
}
