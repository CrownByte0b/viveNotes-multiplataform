// Ported from the Android app's `ui/closed/ClosedNotebooksScreen.kt`: the same two groups, rows,
// buttons, colours and test tags. The desktop differences are presentation only — the full-window
// `DestinationFrame` instead of a `Scaffold`, Escape for system Back, a hover tooltip on the one
// icon-only button, and no Move to cloud in a build without accounts.
package com.vivenotes.ui.ribbon.file

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vivenotes.data.db.ClosedNotebook
import com.vivenotes.localization.Strings
import com.vivenotes.ui.components.DestinationFrame
import com.vivenotes.ui.components.HoverTooltip
import com.vivenotes.ui.icons.AccountSymbols
import com.vivenotes.ui.icons.rememberNotebookGlyph
import com.vivenotes.workspace.FileActions
import com.vivenotes.workspace.FilePaneState

object ClosedNotebooksTags {
    const val Screen = "closed-notebooks-screen"
    const val Back = "closed-notebooks-back"
    const val Empty = "closed-notebooks-empty"
    const val Loading = "closed-notebooks-loading"
    const val OnDeviceHeading = "closed-notebooks-on-device-heading"
    const val InCloudHeading = "closed-notebooks-in-cloud-heading"
    const val Message = "closed-notebooks-message"

    fun row(notebookId: String) = "closed-notebook-$notebookId"
    fun icon(notebookId: String) = "closed-notebook-icon-$notebookId"
    fun open(notebookId: String) = "closed-notebook-open-$notebookId"
    fun moveToCloud(notebookId: String) = "closed-notebook-to-cloud-$notebookId"
    fun bringBack(notebookId: String) = "closed-notebook-back-$notebookId"
    fun busy(notebookId: String) = "closed-notebook-busy-$notebookId"
}

/**
 * The wavy ring a downloading row wears, sized around the 40 dp icon button inside it: at 64 dp and
 * this amplitude the opening in the middle clears the button and the wave still reads as a wave.
 */
private val BringBackRingSize = 64.dp
private const val BringBackWaveAmplitude = 0.85f

/** So a cloud row and a device row are the same height: [BringBackRingSize] plus the padding. */
private val RowHeight = 80.dp

/**
 * Android's colours for the two cloud actions, which the desktop scheme has no slot for: azure to
 * fetch, its complement orange to send away, so which way a row's button moves the notebook is
 * legible before its label is. Both themes carry the same orange because it is a fill.
 */
private val MoveToCloudFill = Color(0xFFFF8000)
private val MoveToCloudLabel = Color(0xFF1F1F1F)
private val BringBackFillDark = Color(0xFF00478E)
private val BringBackIconDark = Color(0xFFB3D9FF)
private val BringBackFillLight = Color(0xFFB3D9FF)
private val BringBackIconLight = Color(0xFF001C39)

/** File → Closed Notebooks, over the session's File pane state; Back and Escape close it. */
@Composable
internal fun ClosedNotebooksDestination(state: FilePaneState, actions: FileActions) {
    ClosedNotebooksScreen(
        notebooks = state.closedNotebooks,
        onBack = actions::closePane,
        onOpen = actions::reopenNotebook,
        onMoveToCloud = actions::moveNotebookToCloud,
        onBringBack = actions::bringNotebookBack,
        accountConnected = actions.cloudConnected?.collectAsState()?.value == true,
        cloudAvailable = actions.cloudConnected != null,
        busyNotebookId = state.cloudBusyNotebookId,
        reopening = state.busy,
        loading = state.loading,
        message = state.error,
    )
}

/**
 * The shelf: notebooks that are not in the notebook pane, and what can be done with them.
 *
 * Two sections rather than one list with a badge, because the two states differ in what they can do
 * and not merely in where their bytes are: a notebook on the device opens at once, while one in the
 * cloud has to be downloaded first and cannot be searched until it is.
 *
 * Presentational: the session runs the operations, so a cloud move outlives Back, and they arrive
 * here as [busyNotebookId], [reopening] and [message].
 */
@Composable
fun ClosedNotebooksScreen(
    notebooks: List<ClosedNotebook>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit = {},
    onMoveToCloud: (String) -> Unit = {},
    onBringBack: (String) -> Unit = {},
    /** Whether a server is connected. Without one there is no cloud to move anything to. */
    accountConnected: Boolean = false,
    /** Whether this build has accounts at all; without them a device row offers only Open. */
    cloudAvailable: Boolean = true,
    /** The one notebook a cloud operation is running for, so only its row shows a spinner. */
    busyNotebookId: String? = null,
    /** A reopen is being stored, which holds every button still as a cloud operation does. */
    reopening: Boolean = false,
    loading: Boolean = false,
    /** The last outcome worth saying out loud — a refusal, or a failure. */
    message: String? = null,
    modifier: Modifier = Modifier,
) {
    val onDevice = notebooks.filter { it.contentOnDevice }
    val inCloud = notebooks.filterNot { it.contentOnDevice }
    val anyBusy = busyNotebookId != null || reopening

    DestinationFrame(
        title = Strings.closed_notebooks,
        onBack = onBack,
        screenTag = ClosedNotebooksTags.Screen,
        backTag = ClosedNotebooksTags.Back,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            message?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag(ClosedNotebooksTags.Message),
                )
            }

            if (loading && notebooks.isEmpty()) {
                Text(
                    text = Strings.loading_closed_notebooks,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp).testTag(ClosedNotebooksTags.Loading),
                )
            } else if (notebooks.isEmpty()) {
                Text(
                    text = Strings.closed_notebooks_empty,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp).testTag(ClosedNotebooksTags.Empty),
                )
            }

            if (onDevice.isNotEmpty()) {
                SectionHeading(Strings.closed_notebooks_on_device, ClosedNotebooksTags.OnDeviceHeading)
                onDevice.forEach { entry ->
                    ClosedNotebookRow(entry, busyNotebookId == entry.notebook.id, anyBusy, accountConnected,
                        cloudAvailable, onOpen, onMoveToCloud, onBringBack)
                }
            }

            if (inCloud.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                // The one place the screen explains itself, and only when there is something under it
                // to explain. Without a server the sentence changes rather than the buttons quietly
                // doing nothing.
                SectionHeading(Strings.closed_notebooks_in_cloud, ClosedNotebooksTags.InCloudHeading,
                    supporting = if (accountConnected) Strings.closed_notebooks_in_cloud_supporting
                    else Strings.closed_notebooks_no_account)
                inCloud.forEach { entry ->
                    ClosedNotebookRow(entry, busyNotebookId == entry.notebook.id, anyBusy, accountConnected,
                        cloudAvailable, onOpen, onMoveToCloud, onBringBack)
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String, tag: String, supporting: String? = null) {
    Column(Modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(tag))
        supporting?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * One shelved notebook.
 *
 * [anyBusy] disables every other row's buttons while one operation runs: a move and a restore both
 * take the sync mutex and would queue behind each other anyway.
 *
 * The two sections take opposite shapes on purpose. A device row carries two labelled buttons —
 * Open, tonal, and Move to cloud, the irreversible-feeling one, in orange. A cloud row carries one
 * icon button: there is exactly one thing to do with a notebook that is not here. "In the cloud" is
 * device-relative: it covers an account-wide cloud-only notebook and a closed notebook this
 * installation deliberately has not downloaded.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ClosedNotebookRow(
    entry: ClosedNotebook,
    busy: Boolean,
    anyBusy: Boolean,
    accountConnected: Boolean,
    cloudAvailable: Boolean,
    onOpen: (String) -> Unit,
    onMoveToCloud: (String) -> Unit,
    onBringBack: (String) -> Unit,
) {
    val notebook = entry.notebook
    val inCloud = !entry.contentOnDevice
    Card(
        modifier = Modifier.fillMaxWidth().testTag(ClosedNotebooksTags.row(notebook.id)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.heightIn(min = RowHeight).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A notebook, not a colour chip: the glyph says which shelf this row is on before its
            // heading is read, and carries the notebook's own colour. Unspecified, because the glyph
            // is already coloured and a tint would flatten the page tabs into the body.
            Icon(
                imageVector = rememberNotebookGlyph(Color(notebook.colorArgb), inCloud),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(32.dp).testTag(ClosedNotebooksTags.icon(notebook.id)),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = notebook.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Counts, not bytes. A cloud-only notebook keeps its sections and pages, so these
                // stay true after a move and say what would come back.
                Text(
                    text = Strings.value_sections_value_pages(entry.sectionCount, entry.pageCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))

            if (inCloud) {
                val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                Box(Modifier.size(BringBackRingSize), contentAlignment = Alignment.Center) {
                    // Drawn around the button rather than in place of it, so the thing that was
                    // pressed stays where it was pressed and the row does not reflow.
                    if (busy) {
                        CircularWavyProgressIndicator(
                            modifier = Modifier.size(BringBackRingSize).testTag(ClosedNotebooksTags.busy(notebook.id)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            amplitude = BringBackWaveAmplitude,
                        )
                    }
                    HoverTooltip(Strings.closed_notebook_bring_back) {
                        FilledTonalIconButton(
                            onClick = { onBringBack(notebook.id) },
                            enabled = accountConnected && !anyBusy,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (dark) BringBackFillDark else BringBackFillLight,
                                contentColor = if (dark) BringBackIconDark else BringBackIconLight,
                            ),
                            modifier = Modifier.testTag(ClosedNotebooksTags.bringBack(notebook.id)),
                        ) {
                            Icon(AccountSymbols.CloudDownload, contentDescription = Strings.closed_notebook_bring_back)
                        }
                    }
                }
            } else if (busy) {
                LoadingIndicator(Modifier.size(24.dp).testTag(ClosedNotebooksTags.busy(notebook.id)))
            } else {
                FilledTonalButton(
                    onClick = { onOpen(notebook.id) },
                    enabled = !anyBusy,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier.testTag(ClosedNotebooksTags.open(notebook.id)),
                ) {
                    Text(Strings.closed_notebook_open)
                }
                if (cloudAvailable) {
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onMoveToCloud(notebook.id) },
                        enabled = accountConnected && !anyBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = MoveToCloudFill,
                            contentColor = MoveToCloudLabel),
                        modifier = Modifier.testTag(ClosedNotebooksTags.moveToCloud(notebook.id)),
                    ) {
                        Text(Strings.closed_notebook_move_to_cloud)
                    }
                }
            }
        }
    }
}
