package com.vivenotes.ui.ribbon.file

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.ribbon.PendingRibbonAction
import com.vivenotes.ui.ribbon.PendingRibbonNote
import com.vivenotes.ui.ribbon.RibbonBar

/** The File tab. Its commands are listed as the Android app has them and enabled as each is ported. */
@Composable
internal fun FileRibbon() {
    RibbonBar(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), spacing = 8.dp) {
        PendingRibbonAction("Import")
        PendingRibbonAction("Export")
        PendingRibbonAction("Print")
        PendingRibbonAction("History")
        PendingRibbonNote()
    }
}
