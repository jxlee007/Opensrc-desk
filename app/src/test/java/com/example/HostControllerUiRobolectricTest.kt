package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.example.host.HostTelemetry
import com.example.model.InputBackendStatus
import com.example.model.InstalledAppInfo
import com.example.model.MirrorContentBounds
import com.example.model.ProjectionMode
import com.example.model.ShizukuStatus
import com.example.model.VirtualDisplayConfig
import com.example.ui.WorkstationUiState
import com.example.ui.screens.HostControllerScreen
import com.example.ui.theme.ExtendedDesktopTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class HostControllerUiRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun baseState(
        projectionMode: ProjectionMode = ProjectionMode.EXTENDED_DESKTOP,
        shizukuStatus: ShizukuStatus = ShizukuStatus.LayoutPreviewFallback,
        rightClickAsBack: Boolean = false,
        apps: List<InstalledAppInfo> = emptyList()
    ): WorkstationUiState {
        return WorkstationUiState(
            shizukuStatus = shizukuStatus,
            inputBackendStatus = if (shizukuStatus is ShizukuStatus.Authorized) {
                InputBackendStatus.BINDER_IINPUTMANAGER
            } else {
                InputBackendStatus.PREVIEW_CURSOR_ONLY
            },
            launchableApps = apps,
            hostTelemetry = HostTelemetry(
                connected = true,
                peerName = "Galaxy Tab 2",
                config = VirtualDisplayConfig(
                    targetWidth = 1280,
                    targetHeight = 800,
                    targetDpi = 160,
                    forceTabletLayout = true,
                    resolutionCeiling = com.example.model.ResolutionCeiling.P800
                ),
                projectionMode = projectionMode,
                mirrorRightClickAsBack = rightClickAsBack,
                mirrorBounds = if (projectionMode == ProjectionMode.MIRROR_HOST_SCREEN) {
                    MirrorContentBounds(0.2f, 0f, 0.8f, 1f, 1080, 2400)
                } else {
                    null
                }
            )
        )
    }

    @Test
    fun phase2SearchableAppListShowsDualButtonsAndGatesMoveToHost() {
        var launchedPkg: String? = null
        var movedBackPkg: String? = null
        val apps = listOf(
            InstalledAppInfo(
                packageName = "org.mozilla.firefox",
                label = "Firefox",
                isActiveOnVirtual = true
            ),
            InstalledAppInfo(
                packageName = "com.spotify.music",
                label = "Spotify",
                isActiveOnVirtual = false,
                isActiveOnHost = true
            )
        )

        composeTestRule.setContent {
            ExtendedDesktopTheme {
                HostControllerScreen(
                    state = baseState(shizukuStatus = ShizukuStatus.Authorized, apps = apps),
                    onTrackpadDelta = { _, _, _ -> },
                    onImeInput = { _, _ -> },
                    onUpdateDisplay = { _, _, _, _ -> },
                    onLaunchApp = { launchedPkg = it },
                    onMoveAppBackToHost = { movedBackPkg = it },
                    onRequestShizukuPermission = {},
                    onRefreshShizuku = {},
                    onDisconnect = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // Mode switcher is always visible above subtabs
        composeTestRule.onNodeWithTag("mode_toggle_extended").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mode_toggle_mirror").assertIsDisplayed()

        // Navigate to Apps tab
        composeTestRule.onNodeWithTag("subtab_apps").performClick()
        composeTestRule.waitForIdle()

        // Both apps appear initially; Move to Host enabled only for Firefox (isActiveOnVirtual = true)
        composeTestRule.onNodeWithTag("launch_app_org.mozilla.firefox").assertIsEnabled()
        composeTestRule.onNodeWithTag("move_back_host_org.mozilla.firefox").assertIsEnabled()
        composeTestRule.onNodeWithTag("move_back_host_org.mozilla.firefox").performClick()

        composeTestRule.onNodeWithTag("host_app_list_lazy_column")
            .performScrollToNode(hasTestTag("launch_app_com.spotify.music"))
        composeTestRule.onNodeWithTag("launch_app_com.spotify.music").assertIsEnabled()
        composeTestRule.onNodeWithTag("move_back_host_com.spotify.music").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("launch_app_com.spotify.music").performClick()

        composeTestRule.waitForIdle()
        assertEquals("org.mozilla.firefox", movedBackPkg)
        assertEquals("com.spotify.music", launchedPkg)

        // Search filter narrows list by app name
        composeTestRule.onNodeWithTag("host_app_search_input").performTextInput("fire")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("launch_app_org.mozilla.firefox").assertIsDisplayed()
        composeTestRule.onNodeWithTag("launch_app_com.spotify.music").assertDoesNotExist()
    }

    @Test
    fun phase3ViewOnlyMirrorDisablesControlsAndShowsAmberWarningWithoutShizuku() {
        var selectedMode: ProjectionMode? = null
        composeTestRule.setContent {
            ExtendedDesktopTheme {
                HostControllerScreen(
                    state = baseState(
                        projectionMode = ProjectionMode.MIRROR_HOST_SCREEN,
                        shizukuStatus = ShizukuStatus.NeedsStartOnApi30Plus
                    ),
                    onTrackpadDelta = { _, _, _ -> },
                    onImeInput = { _, _ -> },
                    onUpdateDisplay = { _, _, _, _ -> },
                    onLaunchApp = {},
                    onSelectProjectionMode = { selectedMode = it },
                    onRequestShizukuPermission = {},
                    onRefreshShizuku = {},
                    onDisconnect = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("mirror_view_only_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("focus_desktop_shell_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("host_ime_input").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("host_ime_enter_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("trackpad_left_click").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("trackpad_right_click").assertIsNotEnabled()

        composeTestRule.onNodeWithTag("mode_toggle_extended").performClick()
        assertEquals(ProjectionMode.EXTENDED_DESKTOP, selectedMode)
    }

    @Test
    fun phase3AuthorizedMirrorShowsActiveControlsAndRightClickBackToggleDefaultOff() {
        var toggledRightClick: Boolean? = null
        var toggledAspectFallback: Boolean? = null
        composeTestRule.setContent {
            ExtendedDesktopTheme {
                HostControllerScreen(
                    state = baseState(
                        projectionMode = ProjectionMode.MIRROR_HOST_SCREEN,
                        shizukuStatus = ShizukuStatus.Authorized,
                        rightClickAsBack = false
                    ),
                    onTrackpadDelta = { _, _, _ -> },
                    onImeInput = { _, _ -> },
                    onUpdateDisplay = { _, _, _, _ -> },
                    onLaunchApp = {},
                    onToggleMirrorRightClickAsBack = { toggledRightClick = it },
                    onToggleMirrorAspectFallback = { toggledAspectFallback = it },
                    onRequestShizukuPermission = {},
                    onRefreshShizuku = {},
                    onDisconnect = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("mirror_input_active_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mirror_right_click_back_switch").assertIsOff()
        composeTestRule.onNodeWithTag("mirror_aspect_fallback_switch").assertIsOff()
        composeTestRule.onNodeWithTag("host_ime_input").assertIsEnabled()
        composeTestRule.onNodeWithTag("trackpad_left_click").assertIsEnabled()
        composeTestRule.onNodeWithTag("trackpad_right_click").assertIsEnabled()
        composeTestRule.onNodeWithText("Right / Back").assertIsDisplayed()

        composeTestRule.onNodeWithTag("mirror_right_click_back_switch").performClick()
        composeTestRule.onNodeWithTag("mirror_aspect_fallback_switch").performClick()
        assertTrue(toggledRightClick == true)
        assertTrue(toggledAspectFallback == true)
    }
}
