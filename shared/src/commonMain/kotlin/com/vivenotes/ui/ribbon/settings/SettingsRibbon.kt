package com.vivenotes.ui.ribbon.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.ribbon.PendingRibbonAction
import com.vivenotes.ui.ribbon.PendingRibbonNote
import com.vivenotes.ui.ribbon.RibbonBar

/** The Settings tab. Its commands are listed as the Android app has them and enabled as each is ported. */
@Composable
internal fun SettingsRibbon() {
    RibbonBar(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), spacing = 8.dp) {
        PendingRibbonAction("Appearance")
        PendingRibbonAction("Hardware")
        PendingRibbonAction("Models")
        PendingRibbonAction("Account")
        PendingRibbonAction("About")
        PendingRibbonNote()
    }
}
