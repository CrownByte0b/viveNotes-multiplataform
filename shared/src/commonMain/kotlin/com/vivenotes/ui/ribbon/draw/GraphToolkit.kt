package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.vivenotes.localization.Strings
import com.vivenotes.graph.GraphArea
import com.vivenotes.graph.function
import com.vivenotes.graph.GraphTangent
import com.vivenotes.graph.window
import com.vivenotes.model.GraphBackground
import com.vivenotes.model.Outline
import com.vivenotes.ui.components.ScaledDropdownMenu
import com.vivenotes.ui.icons.GraphSymbols

internal object GraphToolkitTags {
    const val MUTATE = "object-tooltip-graph-mutate"
    const val OPTIONS = "object-tooltip-graph-options"
    fun background(background: GraphBackground) = "object-tooltip-graph-background-${background.name}"
    const val EQUATION = "object-tooltip-graph-equation"
    const val LABELS = "object-tooltip-graph-labels"
    const val ORIGINAL = "object-tooltip-graph-original"
    const val DERIVATIVE = "object-tooltip-graph-derivative"
    const val TRACE = "object-tooltip-graph-trace"
    const val AREA = "object-tooltip-graph-area"
    const val ZOOM_IN = "object-tooltip-graph-zoom-in"
    const val ZOOM_OUT = "object-tooltip-graph-zoom-out"
    const val FIT = "object-tooltip-graph-fit"
    const val RESET = "object-tooltip-graph-reset"
    const val SLIDERS = "object-tooltip-graph-sliders"
}

/**
 * Whether a formula can be drawn as a graph, which only the math engine can say.
 *
 * Two halves so the button can be right on the first frame: [known] answers from what has already
 * been asked, synchronously, and [check] asks the engine the first time a formula is seen.
 */
interface GraphableFormulas {
    fun known(latex: String): Boolean?
    suspend fun check(latex: String): Boolean

    companion object {
        /** Nothing can be graphed — where there is no engine, in previews and most tests. */
        val None: GraphableFormulas = object : GraphableFormulas {
            override fun known(latex: String): Boolean = false
            override suspend fun check(latex: String): Boolean = false
        }
    }
}

/** What the graph's half of the toolkit can do; the page supplies each one. */
internal class GraphToolkitActions(
    val update: ((Outline.Graph) -> Outline.Graph) -> Unit,
    val zoom: (Double) -> Unit,
    val fit: () -> Unit,
    val reset: () -> Unit,
    /** The Sliders dialog, for one graph with parameters. */
    val editSliders: () -> Unit = {},
)

/**
 * The graph's half of the toolkit: the mutate toggle, and one menu for the rest.
 *
 * Mutate is a button of its own because it is the one setting that changes what a touch on the graph
 * does — the diagram's "different lock" — and so has to show its state on the bar. Everything else
 * is how the graph looks, and sits in a menu so the bar stays the width of the equation's.
 *
 * Over several graphs each toggle reads as on only when it is on for all of them; choosing it sets
 * every one.
 */
@Composable
internal fun RowScope.GraphActions(graphs: List<Outline.Graph>, actions: GraphToolkitActions) {
    if (graphs.isEmpty()) return
    val mutable = graphs.all { it.mutable }
    val mutateDescription = Strings.tooltip_graph_mutate
    IconToggleButton(
        checked = mutable,
        onCheckedChange = { checked -> actions.update { it.copy(mutable = checked) } },
        colors = IconButtonDefaults.iconToggleButtonColors(
            contentColor = Color(0xFFE8EAED),
            checkedContentColor = MaterialTheme.colorScheme.onPrimary,
            checkedContainerColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier
            .size(32.dp)
            .testTag(GraphToolkitTags.MUTATE)
            .semantics { contentDescription = mutateDescription },
    ) {
        Icon(GraphSymbols.DragPan, contentDescription = null, modifier = Modifier.size(18.dp))
    }

    var open by remember { mutableStateOf(false) }
    val optionsDescription = Strings.tooltip_graph_options
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier
                .size(32.dp)
                .testTag(GraphToolkitTags.OPTIONS)
                .semantics { contentDescription = optionsDescription },
        ) {
            Icon(GraphSymbols.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        ScaledDropdownMenu(open, { open = false }) {
            val background = graphs.map { it.background }.distinct().singleOrNull()
            BackgroundItem(GraphBackground.None, Strings.graph_background_none, GraphSymbols.GridOff, background) {
                actions.update { graph -> graph.copy(background = GraphBackground.None) }
            }
            BackgroundItem(GraphBackground.Grid, Strings.graph_background_grid, GraphSymbols.BackgroundGridSmall, background) {
                actions.update { graph -> graph.copy(background = GraphBackground.Grid) }
            }
            BackgroundItem(GraphBackground.Dotted, Strings.graph_background_dotted, GraphSymbols.BackgroundDotSmall, background) {
                actions.update { graph -> graph.copy(background = GraphBackground.Dotted) }
            }
            HorizontalDivider()
            ToggleItem(Strings.graph_show_equation, GraphSymbols.Function, graphs.all { it.showEquation }, GraphToolkitTags.EQUATION) { on ->
                actions.update { it.copy(showEquation = on) }
            }
            ToggleItem(Strings.graph_show_labels, GraphSymbols.Label, graphs.all { it.showLabels }, GraphToolkitTags.LABELS) { on ->
                actions.update { it.copy(showLabels = on) }
            }
            ToggleItem(Strings.graph_show_original, GraphSymbols.History, graphs.all { it.showOriginal }, GraphToolkitTags.ORIGINAL) { on ->
                actions.update { it.copy(showOriginal = on) }
            }
            ToggleItem(Strings.graph_show_derivative, GraphSymbols.TrendingUp, graphs.all { it.showDerivative }, GraphToolkitTags.DERIVATIVE) { on ->
                actions.update { it.copy(showDerivative = on) }
            }
            // A tracer and the tangent through it: placed mid-window, then slid along the curve.
            ToggleItem(Strings.graph_trace, GraphSymbols.LineAxis, graphs.all { it.traceX != null }, GraphToolkitTags.TRACE) { on ->
                actions.update { graph ->
                    graph.copy(
                        traceX = if (on) {
                            graph.function()?.let { GraphTangent.start(it::evaluate, graph.window()) }
                        } else {
                            null
                        },
                    )
                }
            }
            // The area under the curve, starting between the zeros nearest the middle of the view.
            ToggleItem(Strings.graph_area, GraphSymbols.AreaChart, graphs.all { it.area != null }, GraphToolkitTags.AREA) { on ->
                actions.update { graph ->
                    graph.copy(
                        area = if (on) {
                            graph.function()?.let { GraphArea.start(it::evaluate, graph.window()) }
                        } else {
                            null
                        },
                    )
                }
            }
            HorizontalDivider()
            // The zoom entries leave the menu open: zooming is pressed a few times in a row.
            ActionItem(Strings.graph_zoom_in, GraphSymbols.ZoomIn, GraphToolkitTags.ZOOM_IN) { actions.zoom(ZOOM_STEP) }
            ActionItem(Strings.graph_zoom_out, GraphSymbols.ZoomOut, GraphToolkitTags.ZOOM_OUT) { actions.zoom(1 / ZOOM_STEP) }
            ActionItem(Strings.graph_fit, GraphSymbols.FitScreen, GraphToolkitTags.FIT) {
                open = false
                actions.fit()
            }
            // One graph's sliders at a time: the dialog edits ranges, which mean nothing across graphs.
            if (graphs.singleOrNull()?.parameters?.isNotEmpty() == true) {
                ActionItem(Strings.graph_sliders, GraphSymbols.InstantMix, GraphToolkitTags.SLIDERS) {
                    open = false
                    actions.editSliders()
                }
            }
            // Absent until there is something to go back to.
            if (graphs.any { it.originalExpression != null }) {
                ActionItem(Strings.graph_reset, GraphSymbols.RestartAlt, GraphToolkitTags.RESET) {
                    open = false
                    actions.reset()
                }
            }
        }
    }
}

/** Each press shows the middle two-thirds of what was showing. */
private const val ZOOM_STEP = 2.0 / 3.0

@Composable
private fun BackgroundItem(
    value: GraphBackground,
    label: String,
    icon: ImageVector,
    current: GraphBackground?,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = { if (current == value) Icon(GraphSymbols.Check, contentDescription = null) },
        onClick = onClick,
        // A choice of three, announced as one: which is chosen is state, not a check icon.
        modifier = Modifier
            .testTag(GraphToolkitTags.background(value))
            .semantics {
                role = Role.RadioButton
                selected = current == value
            },
    )
}

@Composable
private fun ToggleItem(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = { if (checked) Icon(GraphSymbols.Check, contentDescription = null) },
        onClick = { onChange(!checked) },
        modifier = Modifier
            .testTag(tag)
            .semantics {
                role = Role.Checkbox
                toggleableState = ToggleableState(checked)
            },
    )
}

@Composable
private fun ActionItem(label: String, icon: ImageVector, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    )
}
