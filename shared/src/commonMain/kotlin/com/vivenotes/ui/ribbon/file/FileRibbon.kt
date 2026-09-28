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
import com.vivenotes.ui.icons.versionHistoryGlyph
import com.vivenotes.ui.icons.deletedItemsGlyph
import com.vivenotes.ui.icons.deleteNotebookGlyph
import com.vivenotes.ui.icons.NavigationSymbols
import com.vivenotes.ui.icons.ViewSymbols
import com.vivenotes.ui.ribbon.PendingRibbonAction
import com.vivenotes.ui.ribbon.RibbonBar
import com.vivenotes.ui.ribbon.RibbonCommand
import com.vivenotes.ui.ribbon.RibbonDivider
import com.vivenotes.workspace.FileActions
import com.vivenotes.workspace.FilePane

/** Android's `FileTags`, for the commands that are ported. */
object FileRibbonTags {
    const val ExportNotebook = "file-export-notebook"
    const val ImportNotebook = "file-import-notebook"
    const val TransferDialog = "file-transfer-dialog"
    const val TransferBackdrop = "file-transfer-backdrop"
    const val TransferOk = "file-transfer-ok"
    const val VersionHistory = "file-version-history"
    const val DeletedItems = "file-deleted-items"
    const val ClosedNotebooks = "file-closed-notebooks"
    const val CloseNotebook = "file-close-notebook"
    const val DeleteNotebook = "file-delete-notebook"
}

/**
 * The File tab in Android's order. PDF output will be added with the renderer later.
 */
@Composable
internal fun FileRibbon(
    /** Whether a section is open, and with it the notebook Export Notebook writes. */
    notebookOpen: Boolean,
    pageOpen: Boolean,
    /** A transfer is under way: another waits until it ends. */
    transferRunning: Boolean,
    actions: FileActions?,
    onCloseNotebook: () -> Unit,
    onDeleteNotebook: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val accent = if (colors.surface.luminance() >= 0.5f) Color(0xFF1B6FA8) else Color(0xFF3B9ADC)
    val neutral = colors.onSurfaceVariant
    val exportIcon = remember(neutral, accent) { exportNotebookGlyph(neutral, accent) }
    val importIcon = remember(neutral, accent) { importNotebookGlyph(neutral, accent) }
    val historyIcon = remember(neutral, accent) { versionHistoryGlyph(neutral, accent) }
    val recoveredIcon = remember(neutral) { deletedItemsGlyph(neutral, Color(0xFF2A9D62)) }
    val deleteIcon = remember(neutral) { deleteNotebookGlyph(neutral, Color(0xFFD53B3B)) }
    RibbonBar(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), spacing = 8.dp) {
        PendingRibbonAction("Export PDF")
        RibbonDivider()
        FileCommand("Version History", historyIcon, FileRibbonTags.VersionHistory,
            actions != null && pageOpen && !transferRunning, twoTone = true) { actions?.openPane(FilePane.VersionHistory) }
        FileCommand("Deleted Items", recoveredIcon, FileRibbonTags.DeletedItems,
            actions != null && !transferRunning, twoTone = true) { actions?.openPane(FilePane.DeletedItems) }
        FileCommand("Closed Notebooks", NavigationSymbols.Book, FileRibbonTags.ClosedNotebooks,
            actions != null && !transferRunning) { actions?.openPane(FilePane.ClosedNotebooks) }
        RibbonDivider()
        FileCommand("Close Notebook", ViewSymbols.Close, FileRibbonTags.CloseNotebook,
            actions != null && notebookOpen && !transferRunning, onClick = onCloseNotebook)
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
        FileCommand("Delete Notebook", deleteIcon, FileRibbonTags.DeleteNotebook,
            actions != null && notebookOpen && !transferRunning, twoTone = true, onClick = onDeleteNotebook)
    }
}

@Composable
private fun FileCommand(label: String, icon: ImageVector, tag: String, enabled: Boolean,
    twoTone: Boolean = false, onClick: () -> Unit) {
    RibbonCommand(label = label, onClick = onClick, enabled = enabled,
        modifier = Modifier.testTag(tag)) {
        Icon(icon, contentDescription = null,
            tint = if (twoTone) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TwoToneIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(18.dp))
}
