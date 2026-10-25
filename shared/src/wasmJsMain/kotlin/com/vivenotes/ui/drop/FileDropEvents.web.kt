package com.vivenotes.ui.drop

import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.geometry.Offset

// The dormant web template takes no dropped files.

internal actual fun DragAndDropEvent.carriesFiles(): Boolean = false

internal actual fun DragAndDropEvent.droppedFiles(): List<DroppedFile> = emptyList()

internal actual val DragAndDropEvent.rootPosition: Offset get() = Offset.Zero
