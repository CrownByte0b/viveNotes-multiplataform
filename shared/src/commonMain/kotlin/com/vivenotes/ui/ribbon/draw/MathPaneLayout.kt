package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivenotes.ui.components.ToolPaneTags
import com.vivenotes.ui.components.TooltipIconButton
import com.vivenotes.ui.components.swipeRight
import com.vivenotes.ui.icons.ViewSymbols
import io.ratex.compose.RaTeX
import io.ratex.compose.rememberBlockingRaTeXDisplayList
import io.ratex.measure

internal val MathPaneWidth = 320.dp

internal object MathPaneTags {
    const val Source = "draw-math-source"
    const val Copy = "draw-math-copy"
    const val Copied = "draw-math-copied"
    const val Preview = "draw-math-preview"
    const val Interpretation = "draw-math-interpretation"
    const val Result = "draw-math-result"
    const val Decimal = "draw-math-decimal"
    const val Error = "draw-math-error"
    fun action(id: String) = "draw-math-action-$id"
}

@Composable
internal fun MathPaneFrame(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DrawToolkitTheme {
        Column(Modifier.width(MathPaneWidth).fillMaxHeight().background(MaterialTheme.colorScheme.surface)
            .swipeRight(onClose).testTag(ToolPaneTags.Pane)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Recognition", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f))
                TooltipIconButton("Close Recognition", onClick = onClose, modifier = Modifier.size(34.dp).testTag(ToolPaneTags.Close)) {
                    Icon(ViewSymbols.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp), content = content)
        }
    }
}

@Composable
internal fun ColumnScope.MathSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(10.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(4.dp))
    content()
    Spacer(Modifier.height(6.dp))
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
internal fun MathPanelButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                             mathAction: Boolean = false, content: @Composable RowScope.() -> Unit) {
    Button(onClick, enabled = enabled, shape = RoundedCornerShape(50),
        colors = if (mathAction) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary) else ButtonDefaults.buttonColors(),
        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.ExtraSmallContainerHeight),
        modifier = modifier.heightIn(min = ButtonDefaults.ExtraSmallContainerHeight), content = content)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MathDecimalToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    ToggleButton(checked, onChange, shapes = ToggleButtonDefaults.shapesFor(ButtonDefaults.ExtraSmallContainerHeight),
        colors = ToggleButtonDefaults.toggleButtonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer, checkedContainerColor = MaterialTheme.colorScheme.tertiary,
            checkedContentColor = MaterialTheme.colorScheme.onTertiary),
        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.ExtraSmallContainerHeight),
        modifier = Modifier.padding(top = 8.dp).heightIn(min = ButtonDefaults.ExtraSmallContainerHeight)
            .testTag(MathPaneTags.Decimal).semantics { contentDescription = "Show decimal" }) {
        Text("≈", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun MathError(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag(MathPaneTags.Error))
}

@Composable
internal fun MathEquationPreview(latex: String, tag: String, scale: Float = 1f) {
    val display = rememberBlockingRaTeXDisplayList(latex, color = MaterialTheme.colorScheme.onSurface).getOrNull()
    val density = LocalDensity.current
    val fontSize = 24.sp * scale
    val measured = display?.measure(with(density) { fontSize.toPx() })
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fit = if (measured != null && constraints.hasBoundedWidth)
            ((constraints.maxWidth - with(density) { 16.dp.toPx() }) / measured.widthPx.coerceAtLeast(1f)).coerceIn(.4f, 1f)
        else 1f
        val height = measured?.let { with(density) { (it.totalHeightPx * fit).toDp() } + 32.dp * scale }
            ?.coerceAtLeast(96.dp * scale) ?: (96.dp * scale)
        val width = measured?.let { with(density) { (it.widthPx * fit).toDp() } + 16.dp }
            ?.coerceAtLeast(maxWidth) ?: maxWidth
        Box(Modifier.fillMaxWidth().height(height).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .testTag(tag).semantics { contentDescription = latex }.horizontalScroll(rememberScrollState())) {
            Box(Modifier.width(width).fillMaxHeight(), contentAlignment = Alignment.Center) {
                if (latex.isBlank()) Text("No formula", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (display == null) Text("Unable to render this equation", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                else RaTeX(displayList = display, fontSize = fontSize * fit)
            }
        }
    }
}
