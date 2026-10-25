package com.vivenotes.ui.drop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.data.PictureLibrary
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.localization.Strings
import com.vivenotes.ui.icons.ShellSymbols
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Semantics identifiers for dropping files on the page. */
object FileDropTags {
    /** The canvas's highlight while files are dragged over it. */
    const val Highlight = "file-drop-highlight"
}

/**
 * What the page takes when files are dropped on it, in the order each file is offered to them.
 * Today only pictures; another kind of file — a document, say — is one more handler in this list.
 */
@Composable
internal fun rememberFileDropHandlers(
    pictures: PictureLibrary?,
    onStateChange: ((WorkspaceState) -> WorkspaceState) -> Unit,
    log: DebugLog,
): List<FileDropHandler> {
    val currentStateChange by rememberUpdatedState(onStateChange)
    return remember(pictures, log) {
        listOfNotNull(
            pictures?.let { library ->
                PictureDrop(library, place = { at, placed ->
                    currentStateChange { it.dropPictures(at.pageId, placed, at.x, at.y) }
                }, log = log)
            },
        )
    }
}

/**
 * Files dragged onto the page canvas from outside the app. A drag that carries files is welcome
 * while [pageId] names a page that may change; [hovering] tells when one is over the canvas, for
 * its highlight. At the drop, each file goes to the first of [handlers] that takes it, at the page
 * point under the pointer, and the drop is refused when none takes any.
 *
 * Built once per canvas; [pageId], [handlers] and [toPage] are brought up to date by every
 * composition, because a drag that has begun keeps talking to the target it began with.
 */
internal class PageFileDrop(private val scope: CoroutineScope, private val log: DebugLog) {
    /** The page that takes dropped files, or null while the open page may not change. */
    var pageId: String? = null
    var handlers: List<FileDropHandler> = emptyList()
    /** From the canvas's own pixels to page dp. */
    var toPage: (Offset) -> Offset = { it }

    var hovering by mutableStateOf(false)
        private set

    private var coordinates: LayoutCoordinates? = null

    private fun welcomes(event: DragAndDropEvent): Boolean =
        pageId != null && handlers.isNotEmpty() && event.carriesFiles()

    private val target = object : DragAndDropTarget {
        override fun onEntered(event: DragAndDropEvent) { hovering = true }
        override fun onExited(event: DragAndDropEvent) { hovering = false }
        override fun onEnded(event: DragAndDropEvent) { hovering = false }
        override fun onDrop(event: DragAndDropEvent): Boolean {
            hovering = false
            val page = pageId ?: return false
            val canvas = coordinates?.takeIf { it.isAttached } ?: return false
            val root = event.rootPosition.takeIf { it.isSpecified } ?: return false
            val point = toPage(canvas.localPositionOf(canvas.findRootCoordinates(), root))
            return drop(event.droppedFiles(), DropPoint(page, point.x, point.y))
        }
    }

    /** Shares [files] out among the handlers at [at]; false when none of them takes any file. */
    internal fun drop(files: List<DroppedFile>, at: DropPoint): Boolean {
        val routing = handlers.route(files)
        // Kinds, not names: a file's name can say as much as what is in it.
        val refused = routing.refused.joinToString("") { " .${it.extension}" }
        log.event("drop") { "${files.size} files dropped, ${files.size - routing.refused.size} taken" + if (refused.isEmpty()) "" else ", refused$refused" }
        for ((handler, mine) in routing.deliveries) scope.launch { handler.receive(mine, at) }
        return routing.accepted
    }

    /** On the canvas box itself, so the drop point is in the same pixels as its pointer input. */
    val modifier: Modifier = Modifier
        .onPlaced { coordinates = it }
        .dragAndDropTarget(shouldStartDragAndDrop = ::welcomes, target = target)
}

@Composable
internal fun rememberPageFileDrop(log: DebugLog): PageFileDrop {
    val scope = rememberCoroutineScope()
    return remember(scope, log) { PageFileDrop(scope, log) }
}

/**
 * GNOME's drop target look over the canvas while files are dragged across it: an accent ring and
 * tint, and a pill saying what a drop does.
 */
@Composable
internal fun FileDropHighlight(visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .testTag(FileDropTags.Highlight)
            .background(accent.copy(alpha = 0.06f))
            .border(2.dp, accent.copy(alpha = 0.7f), MaterialTheme.shapes.small),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 2.dp,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(ShellSymbols.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(Strings.drop_to_add_to_page, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
