package com.vivenotes.ui.ribbon.document

import com.vivenotes.localization.Strings
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.canvas.SelectionColorPicker
import com.vivenotes.ui.components.TooltipIconButton
import com.vivenotes.ui.icons.ObjectSymbols

internal object PictureObjectTags {
    const val Background = "object-picture-background"
}

/**
 * Picture-specific extension of the Prime Object toolkit: a colour behind the pixels, which shows
 * wherever the picture is transparent. The shape fill's palette in its own words and glyphs.
 * [background] is null when the held pictures have none or disagree; a choice then sets them all.
 */
@Composable
internal fun PictureObjectTools(background: Int?, onBackground: (Int?) -> Unit) {
    SelectionColorPicker(onColor = onBackground, onNoFill = { onBackground(null) }, selectedColor = background,
        noFillLabel = Strings.no_background, noFillIcon = ObjectSymbols.BackgroundNone) { open ->
        TooltipIconButton(Strings.change_background_color, onClick = open, tooltipPosition = TooltipAnchorPosition.Above,
            modifier = Modifier.size(32.dp).testTag(PictureObjectTags.Background)) {
            Icon(if (background == null) ObjectSymbols.BackgroundNone else ObjectSymbols.BackgroundReplace,
                contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}
