package com.vera.timebricks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Exercises the production content with fixtures; never grants access or modifies user data. */
class ScheduleAccessContentTest {
    @get:Rule val compose = createComposeRule()
    private val selfPact = ScheduleAccessEntry("com.vera.selfpact", "SelfPact", ScheduleReadStatus.GRANTED, true)

    private fun show(entries: List<ScheduleAccessEntry>?, error: String? = null,
        refresh: () -> Unit = {}, revoke: (ScheduleAccessEntry) -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ScheduleAccessContent(entries, error, null, refresh, revoke)
                }
            }
        }
    }

    @Test fun revokeRequiresConfirmationAndCancelPreservesAccess() {
        var revocations = 0
        show(listOf(selfPact), revoke = { assertEquals(selfPact, it); revocations++ })
        compose.onNodeWithText("撤销手动授权").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, revocations) }
        compose.onNodeWithText("撤销手动授权").performScrollTo().performClick()
        compose.onNodeWithText("确认撤销").performClick()
        compose.runOnIdle { assertEquals(1, revocations) }
        compose.onNodeWithText("确认撤销").assertDoesNotExist()
    }

    @Test fun signatureAccessWithoutManualRecordHasNoRevokeControl() {
        show(listOf(selfPact.copy(status = ScheduleReadStatus.SIGNATURE, hasManualGrant = false)))
        compose.onNodeWithText("同签名访问").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("撤销手动授权").assertDoesNotExist()
    }

    @Test fun expiredRecordDoesNotCountAsReadable() {
        show(listOf(selfPact.copy(status = ScheduleReadStatus.EXPIRED)))
        compose.onNodeWithText("0 个应用可读取日程 · 1 个记录待确认或重新授权").assertIsDisplayed()
        compose.onNodeWithText("需重新授权").performScrollTo().assertIsDisplayed()
    }

    @Test fun futureCapabilitiesArePlainTextAndEmptyStateHasNoGrantButton() {
        show(emptyList())
        compose.onNodeWithText("尚无授权记录").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("按日期或分类授权 · 暂不可用").performScrollTo().assertHasNoClickAction()
        compose.onNodeWithText("自定义有效期与访问记录 · 暂不可用").performScrollTo().assertHasNoClickAction()
        compose.onNodeWithText("更多应用接入 · 暂不可用").performScrollTo().assertHasNoClickAction()
        compose.onNodeWithText("撤销手动授权").assertDoesNotExist()
    }

    @Test fun failedRefreshIsNotPresentedAsNoAuthorization() {
        var refreshes = 0
        show(null, "暂时无法读取授权状态，请重试。", refresh = { refreshes++ })
        compose.onNodeWithText("暂时无法读取授权状态，请重试。").assertIsDisplayed()
        compose.onNodeWithText("暂无可见的授权应用").assertDoesNotExist()
        compose.onNodeWithText("刷新状态").performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test fun removedRecordClosesPendingConfirmation() {
        val entries = mutableStateOf(listOf(selfPact))
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ScheduleAccessContent(entries.value, null, null, {}, { error("Stale record") })
                }
            }
        }
        compose.onNodeWithText("撤销手动授权").performScrollTo().performClick()
        compose.runOnIdle { entries.value = emptyList() }
        compose.onNodeWithText("确认撤销").assertDoesNotExist()
    }
}
