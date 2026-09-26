package com.vivenotes.ui.ribbon.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.ribbon.PendingRibbonAction
import com.vivenotes.ui.ribbon.PendingRibbonNote
import com.vivenotes.ui.ribbon.RibbonBar

/** The Settings tab. Its commands are listed as the Android app has them and enabled as each is ported. */
@Composable
internal fun SettingsRibbon(onInterface: () -> Unit) {
    RibbonBar(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), spacing = 8.dp) {
        OutlinedButton(onClick = onInterface, shape = MaterialTheme.shapes.small,
            modifier = Modifier.testTag(InterfaceTags.Open)) { Text("Interface") }
        PendingRibbonAction("Appearance")
        PendingRibbonAction("Hardware")
        PendingRibbonAction("Models")
        PendingRibbonAction("Account")
        PendingRibbonAction("About")
        PendingRibbonNote()
    }
}
