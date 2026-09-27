package com.vivenotes.ui.ribbon.file

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.icons.exportNotebookGlyph
import com.vivenotes.ui.icons.importNotebookGlyph
import com.vivenotes.ui.ribbon.PendingRibbonAction
import com.vivenotes.ui.ribbon.PendingRibbonNote
import com.vivenotes.ui.ribbon.RibbonBar
import com.vivenotes.ui.ribbon.RibbonCommand
import com.vivenotes.ui.ribbon.RibbonDivider
import com.vivenotes.workspace.FileActions

/** Android's `FileTags`, for the commands that are ported. */
object FileRibbonTags {
    const val ExportNotebook = "file-export-notebook"
    const val ImportNotebook = "file-import-notebook"
    const val TransferDialog = "file-transfer-dialog"
    const val TransferBackdrop = "file-transfer-backdrop"
    const val TransferOk = "file-transfer-ok"
}

/**
 * The File tab, in the Android tab's order and with its labels: Export PDF; the notebook's history,
 * deleted items and closed notebooks; Close Notebook, Export Notebook and Import; and last, behind its
 * own divider, the one command that takes something away. Commands not yet ported are shown
 * disabled. Export Notebook and Import need [actions], the window's `.vive` file dialogs.
 */
@Composable
internal fun FileRibbon(
    /** Whether a section is open, and with it the notebook Export Notebook writes. */
    notebookOpen: Boolean,
    /** A transfer is under way: another waits until it ends. */
    transferRunning: Boolean,
    actions: FileActions?,
) {
    val colors = MaterialTheme.colorScheme
    val accent = if (colors.surface.luminance() >= 0.5f) Color(0xFF1B6FA8) else Color(0xFF3B9ADC)
    val neutral = colors.onSurfaceVariant
    val exportIcon = remember(neutral, accent) { exportNotebookGlyph(neutral, accent) }
    val importIcon = remember(neutral, accent) { importNotebookGlyph(neutral, accent) }
    RibbonBar(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), spacing = 8.dp) {
        PendingRibbonAction("Export PDF")
        RibbonDivider()
        PendingRibbonAction("Version History")
        PendingRibbonAction("Deleted Items")
        PendingRibbonAction("Closed Notebooks")
        RibbonDivider()
        PendingRibbonAction("Close Notebook")
        RibbonCommand(
            label = "Export Notebook",
            onClick = { actions?.exportNotebook() },
            enabled = actions != null && notebookOpen && !transferRunning,
            modifier = Modifier.testTag(FileRibbonTags.ExportNotebook),
        ) { TwoToneIcon(exportIcon) }
        RibbonCommand(
            label = "Import",
            onClick = { actions?.importNotebook() },
            enabled = actions != null && !transferRunning,
            modifier = Modifier.testTag(FileRibbonTags.ImportNotebook),
        ) { TwoToneIcon(importIcon) }
        RibbonDivider()
        PendingRibbonAction("Delete Notebook")
        PendingRibbonNote()
    }
}

@Composable
private fun TwoToneIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(18.dp))
}
