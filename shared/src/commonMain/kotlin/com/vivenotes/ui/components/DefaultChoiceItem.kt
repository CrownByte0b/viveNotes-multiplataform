package com.vivenotes.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.icons.ViewSymbols

/** A normal click chooses the option; a long press makes it the new app default. */
@Composable
internal fun DefaultChoiceItem(
    label: String,
    selected: Boolean,
    isDefault: Boolean,
    tag: String,
    onChoose: () -> Unit,
    onSetDefault: () -> Unit,
) {
    Row(
        Modifier.width(220.dp).heightIn(min = 40.dp)
            .combinedClickable(role = Role.Button, onClick = onChoose, onLongClick = onSetDefault,
                onLongClickLabel = "Make $label the default")
            .semantics {
                this.selected = selected
                stateDescription = listOfNotNull(
                    "Selected".takeIf { selected }, "Default".takeIf { isDefault }).joinToString(", ")
            }
            .testTag(tag).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) Icon(ViewSymbols.Check, contentDescription = null,
            modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        else Spacer(Modifier.size(18.dp))
        Text(label, modifier = Modifier.padding(start = 8.dp).weight(1f),
            style = MaterialTheme.typography.bodyMedium)
        if (isDefault) Text("Default", modifier = Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
