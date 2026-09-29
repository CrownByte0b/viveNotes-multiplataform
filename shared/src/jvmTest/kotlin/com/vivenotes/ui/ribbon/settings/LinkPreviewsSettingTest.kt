package com.vivenotes.ui.ribbon.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class LinkPreviewsSettingTest {
    @Test fun linkPreviewControlChangesDevicePreference() = runDesktopComposeUiTest {
        setContent {
            var enabled by remember { mutableStateOf(true) }
            ViveNotesTheme {
                SettingsRibbon(onInterface = {}, hardwareOpen = false, onHardware = {},
                    linkPreviews = enabled, onLinkPreviewsChange = { enabled = it }, onAbout = {})
            }
        }
        onNodeWithTag(LINK_PREVIEWS_TAG).assertIsSelected().performClick()
        onNodeWithTag(LINK_PREVIEWS_TAG).assertIsNotSelected()
    }
}
