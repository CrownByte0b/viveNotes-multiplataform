package com.vivenotes.ui.ribbon.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vivenotes.ai.AiModelCatalog
import com.vivenotes.ai.AiModelInstallState
import com.vivenotes.ai.AiModels
import com.vivenotes.ai.FormulaEngine
import com.vivenotes.ai.displayName
import com.vivenotes.ai.formatModelBytes
import com.vivenotes.ai.strength
import com.vivenotes.ui.components.PaneGroup
import com.vivenotes.ui.components.ToolPane
import com.vivenotes.ui.icons.ObjectSymbols
import com.vivenotes.ui.icons.SettingsSymbols
import com.vivenotes.ui.icons.ViewSymbols
import kotlin.math.roundToInt

/** Semantics identifiers for the Models pane. */
object ModelsTags {
    const val Open = "settings-models"
    const val TextModel = "models-text"
    fun formula(engine: FormulaEngine): String = "models-formula-${engine.name}"
    fun download(engine: FormulaEngine): String = "models-formula-download-${engine.name}"
    fun delete(engine: FormulaEngine): String = "models-formula-delete-${engine.name}"
    fun use(engine: FormulaEngine): String = "models-formula-use-${engine.name}"
    fun progress(engine: FormulaEngine): String = "models-formula-progress-${engine.name}"
}

/**
 * Settings → Models, docked beside the canvas: Android's Integrated pane. Its Math group lists the
 * formula recognition models, one row each — Download until installed, then Use and Delete, with
 * the model in use marked "In use". Text is the bundled OCR, which has nothing to do.
 */
@Composable
internal fun ModelsPane(models: AiModels?, onClose: () -> Unit) {
    ToolPane(title = "Models", onClose = onClose) {
        if (models != null) {
            val state by models.state.collectAsState()
            PaneGroup("Math") {
                FormulaEngine.entries.forEachIndexed { index, engine ->
                    val install = state.formula(engine)
                    FormulaModelRow(
                        engine = engine,
                        install = install,
                        inUse = engine == state.formulaEngine && install == AiModelInstallState.Installed,
                        first = index == 0,
                        onDownload = { models.download(engine) },
                        onDelete = { models.delete(engine) },
                        onUse = { models.selectFormulaEngine(engine) },
                    )
                }
            }
            PaneGroup("Text") { TextModelRow(state.handwritingText) }
        }
    }
}

/** One formula model: what it is good at and its size, then what can be done with it. */
@Composable
private fun FormulaModelRow(
    engine: FormulaEngine,
    install: AiModelInstallState,
    inUse: Boolean,
    first: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onUse: () -> Unit,
) {
    ModelRow(
        title = engine.displayName,
        detail = "${engine.strength} · ${formatModelBytes(AiModelCatalog.downloadBytes(engine))}",
        first = first,
        modifier = Modifier.testTag(ModelsTags.formula(engine)),
    ) {
        when (install) {
            AiModelInstallState.Installed -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The two rows are one choice, as Android's toggle buttons are: the model in use is
                // the checked one, in the accent colour, and pressing it again changes nothing.
                if (inUse) {
                    Button(
                        onClick = {},
                        shape = MaterialTheme.shapes.small,
                        contentPadding = CompactButtonPadding,
                        modifier = Modifier.semantics { selected = true }.testTag(ModelsTags.use(engine)),
                    ) {
                        Icon(ViewSymbols.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("In use")
                    }
                } else {
                    FilledTonalButton(
                        onClick = onUse,
                        shape = MaterialTheme.shapes.small,
                        contentPadding = CompactButtonPadding,
                        modifier = Modifier.semantics { selected = false }.testTag(ModelsTags.use(engine)),
                    ) { Text("Use") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onDelete,
                    shape = MaterialTheme.shapes.small,
                    contentPadding = CompactButtonPadding,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag(ModelsTags.delete(engine)),
                ) {
                    Icon(ObjectSymbols.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete")
                }
            }
            AiModelInstallState.NotInstalled -> DownloadButton("Download", ModelsTags.download(engine), onDownload)
            AiModelInstallState.Verifying -> ProgressLine("Verifying…", fraction = null, ModelsTags.progress(engine))
            is AiModelInstallState.Downloading -> {
                val fraction = if (install.totalBytes == 0L) 0f
                    else (install.downloadedBytes.toDouble() / install.totalBytes).toFloat().coerceIn(0f, 1f)
                ProgressLine("Downloading ${(fraction * 100).roundToInt()}%", fraction, ModelsTags.progress(engine))
            }
            is AiModelInstallState.Failed -> {
                ErrorLine(install.message)
                DownloadButton("Retry", ModelsTags.download(engine), onDownload)
            }
        }
    }
}

/** The bundled OCR. Nothing to download, delete or choose, so it only says what it is. */
@Composable
private fun TextModelRow(install: AiModelInstallState) {
    ModelRow(
        title = AiModelCatalog.TEXT_MODEL_NAME,
        detail = "Handwriting and pictures · ${formatModelBytes(AiModelCatalog.bundledTextBytes)} · built in",
        first = true,
        modifier = Modifier.testTag(ModelsTags.TextModel),
    ) {
        when (install) {
            is AiModelInstallState.Failed -> ErrorLine(install.message)
            AiModelInstallState.Verifying -> ProgressLine("Verifying…", fraction = null, tag = null)
            else -> {}
        }
    }
}

/** A row of a [PaneGroup]: a title, a detail line, then whatever the row offers below them. */
@Composable
private fun ModelRow(
    title: String,
    detail: String,
    first: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    // One item to a screen reader: the name, what it is, and any error, with its buttons apart.
    Column(modifier.fillMaxWidth().heightIn(min = 46.dp).semantics(mergeDescendants = true) {}
        .padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), content = content)
    }
}

@Composable
private fun DownloadButton(label: String, tag: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        contentPadding = CompactButtonPadding,
        modifier = Modifier.testTag(tag),
    ) {
        Icon(SettingsSymbols.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

/** What is happening and, when it is known, how far along: a plain bar without one. */
@Composable
private fun ProgressLine(label: String, fraction: Float?, tag: String?) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}
        .then(if (tag != null) Modifier.testTag(tag) else Modifier)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        if (fraction == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ErrorLine(message: String) {
    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(8.dp))
}

private val CompactButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
