package com.vivenotes.ui.drop

import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.geometry.Offset

/**
 * Whether this drag carries files. Asked while it is still being dragged, when the platform tells
 * only what kinds of data it carries: the files themselves are handed over at the drop.
 */
internal expect fun DragAndDropEvent.carriesFiles(): Boolean

/** The files this drop delivers, in the order the source lists them; empty when it brings none. */
internal expect fun DragAndDropEvent.droppedFiles(): List<DroppedFile>

/** Where this event is, in the window root's pixels. */
internal expect val DragAndDropEvent.rootPosition: Offset
