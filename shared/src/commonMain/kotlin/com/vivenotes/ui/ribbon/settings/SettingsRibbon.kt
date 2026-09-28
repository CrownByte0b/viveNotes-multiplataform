package com.vivenotes.ui.ribbon.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.ribbon.PendingRibbonAction
import com.vivenotes.ui.ribbon.PendingRibbonNote
import com.vivenotes.ui.ribbon.RibbonBar
import com.vivenotes.ui.theme.LocalDesktopColors

internal const val LINK_PREVIEWS_TAG = "settings-link-previews"

/**
 * The Settings tab. Its commands are listed as the Android app has them and enabled as each is
 * ported. Hardware opens and closes its docked pane, and is shown pressed while the pane is open.
 */
@Composable
internal fun SettingsRibbon(onInterface: () -> Unit, hardwareOpen: Boolean, onHardware: () -> Unit,
    linkPreviews: Boolean, onLinkPreviewsChange: (Boolean) -> Unit) {
    RibbonBar(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), spacing = 8.dp) {
        OutlinedButton(onClick = onInterface, shape = MaterialTheme.shapes.small,
            modifier = Modifier.testTag(InterfaceTags.Open)) { Text("Interface") }
        PendingRibbonAction("Appearance")
        OutlinedButton(onClick = { onLinkPreviewsChange(!linkPreviews) }, shape = MaterialTheme.shapes.small,
            colors = if (linkPreviews) ButtonDefaults.outlinedButtonColors(
                containerColor = LocalDesktopColors.current.selection) else ButtonDefaults.outlinedButtonColors(),
            modifier = Modifier.testTag(LINK_PREVIEWS_TAG).semantics { selected = linkPreviews }) {
            Text("Link previews")
        }
        OutlinedButton(onClick = onHardware, shape = MaterialTheme.shapes.small,
            colors = if (hardwareOpen) ButtonDefaults.outlinedButtonColors(
                containerColor = LocalDesktopColors.current.selection) else ButtonDefaults.outlinedButtonColors(),
            modifier = Modifier.testTag(HardwareTags.Open).semantics { selected = hardwareOpen }) { Text("Hardware") }
        PendingRibbonAction("Models")
        PendingRibbonAction("Account")
        PendingRibbonAction("About")
        PendingRibbonNote()
    }
}
