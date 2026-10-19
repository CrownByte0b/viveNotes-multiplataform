package com.vivenotes.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vivenotes.localization.Strings

/** The zoom indicator's action, which is also its tooltip. */
internal val ResetZoomLabel get() = Strings.reset_zoom_to_100

/** Android's wording, for a page whose stored body could not be decoded. */
internal val UnreadablePageMessage =
    Strings.this_page_could_not_be_read_so_editing_is_disabled_to

/**
 * Tells the user that notes storage failed, for as long as it keeps failing; the next read or
 * write that succeeds takes it away. Motion comes from the theme, like the panes'.
 */
@Composable
internal fun StorageErrorBanner(message: String?) {
    // Kept through the exit animation, which still has to show what it is taking away.
    var shown by remember { mutableStateOf(message.orEmpty()) }
    if (message != null) shown = message
    AnimatedVisibility(
        visible = message != null,
        enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
            fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
        exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
            fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
    ) {
        StatusBanner(shown, Modifier.fillMaxWidth().testTag(WorkspaceTestTags.StorageError))
    }
}

@Composable
internal fun StatusBanner(message: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            // One announcement, read out when it appears or changes.
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
