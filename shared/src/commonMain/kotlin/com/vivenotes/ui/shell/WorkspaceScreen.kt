package com.vivenotes.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vivenotes.workspace.NotebookSummary
import com.vivenotes.workspace.PageSummary
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.SectionSummary
import com.vivenotes.workspace.WorkspaceState

private val NotebookPaneWidth = 260.dp
private val PagePaneWidth = 292.dp

/** Stable semantics identifiers used by desktop UI tests and future accessibility automation. */
object WorkspaceTestTags {
    const val NavigationToggle = "workspace-navigation-toggle"
    const val NotebookPane = "workspace-notebook-pane"
    const val PagePane = "workspace-page-pane"
    const val PageCanvas = "workspace-page-canvas"
    const val AddPage = "workspace-add-page"
    const val TitleEditor = "workspace-title-editor"
    const val BodyEditor = "workspace-body-editor"

    fun ribbonTab(tab: RibbonTab): String = "workspace-ribbon-${tab.name}"
    fun section(id: String): String = "workspace-section-$id"
    fun page(id: String): String = "workspace-page-$id"
}

@Composable
fun WorkspaceScreen(
    state: WorkspaceState,
    onStateChange: (WorkspaceState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TopNavigation(
            activeTab = state.activeTab,
            navigationVisible = state.navigationVisible,
            onToggleNavigation = {
                onStateChange(state.copy(navigationVisible = !state.navigationVisible))
            },
            onSelectTab = { onStateChange(state.copy(activeTab = it)) },
        )
        CommandRibbon(activeTab = state.activeTab)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val showNotebookPane = maxWidth >= 1040.dp && state.navigationVisible
            val showPagePane = maxWidth >= 760.dp
            val paneMotion = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .animateContentSize(animationSpec = paneMotion),
            ) {
                AnimatedVisibility(
                    visible = showNotebookPane,
                    enter = expandHorizontally(animationSpec = paneMotion),
                    exit = shrinkHorizontally(animationSpec = paneMotion),
                ) {
                    NotebookPane(
                        state = state,
                        onSelectNotebook = { onStateChange(state.selectNotebook(it)) },
                        onSelectSection = { onStateChange(state.selectSection(it)) },
                    )
                }
                if (showNotebookPane) {
                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                if (showPagePane) {
                    PageListPane(
                        section = state.selectedSection,
                        selectedPageId = state.selectedPageId,
                        onAddPage = { onStateChange(state.addPage()) },
                        onSelectPage = { onStateChange(state.selectPage(it)) },
                    )
                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                PageCanvas(
                    page = state.selectedPage,
                    modifier = Modifier.weight(1f),
                    onTitleChange = { onStateChange(state.updateSelectedPage(title = it)) },
                    onBodyChange = { onStateChange(state.updateSelectedPage(body = it)) },
                )
            }
        }
    }
}

@Composable
private fun TopNavigation(
    activeTab: RibbonTab,
    navigationVisible: Boolean,
    onToggleNavigation: () -> Unit,
    onSelectTab: (RibbonTab) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = onToggleNavigation,
                modifier = Modifier
                    .testTag(WorkspaceTestTags.NavigationToggle)
                    .semantics {
                        contentDescription = if (navigationVisible) {
                            "Hide notebook navigation"
                        } else {
                            "Show notebook navigation"
                        }
                    },
            ) {
                Text(
                    text = "☰",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Text(
                text = "ViveNotes",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(end = 8.dp),
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RibbonTab.entries.forEach { tab ->
                    FilterChip(
                        selected = activeTab == tab,
                        onClick = { onSelectTab(tab) },
                        label = { Text(tab.name) },
                        modifier = Modifier.testTag(WorkspaceTestTags.ribbonTab(tab)),
                    )
                }
            }
            Text(
                text = "Local preview",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun CommandRibbon(activeTab: RibbonTab) {
    val actions = when (activeTab) {
        RibbonTab.File -> listOf("Import", "Export", "Print", "History")
        RibbonTab.Home -> listOf(
            "Paste", "Cut", "Copy", "Font", "Size", "Bold", "Italic", "Underline", "Lists", "Align", "Picture",
        )
        RibbonTab.Insert -> listOf("Table", "File", "Picture", "Date", "Link", "Equation", "Shape")
        RibbonTab.Draw -> listOf("Select", "Pen", "Highlighter", "Eraser", "Lasso", "Shape", "Ruler")
        RibbonTab.View -> listOf("Zoom", "Paper", "Page color", "Paper size", "Background")
        RibbonTab.Settings -> listOf("Appearance", "Hardware", "Models", "Account", "About")
    }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEach { action ->
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                ) {
                    Text(action)
                }
            }
            Text(
                text = "Tools unlock as each port phase lands",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun NotebookPane(
    state: WorkspaceState,
    onSelectNotebook: (String) -> Unit,
    onSelectSection: (String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(NotebookPaneWidth)
            .fillMaxHeight()
            .testTag(WorkspaceTestTags.NotebookPane),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                text = "Notebooks",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 10.dp),
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.notebooks, key = NotebookSummary::id) { notebook ->
                    NotebookRow(
                        notebook = notebook,
                        selected = notebook.id == state.selectedNotebookId,
                        onClick = { onSelectNotebook(notebook.id) },
                    )
                    if (notebook.id == state.selectedNotebookId) {
                        notebook.sections.forEach { section ->
                            SectionRow(
                                section = section,
                                selected = section.id == state.selectedSectionId,
                                onClick = { onSelectSection(section.id) },
                            )
                        }
                    }
                }
            }
            TextButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.padding(10.dp),
            ) {
                Text("＋ New notebook")
            }
        }
    }
}

@Composable
private fun NotebookRow(
    notebook: NotebookSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        Color.Transparent
    }
    Surface(
        onClick = onClick,
        color = container,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        ListItem(
            headlineContent = {
                Text(notebook.name, fontWeight = FontWeight.SemiBold)
            },
            leadingContent = {
                Box(
                    Modifier
                        .size(13.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(notebook.colorArgb)),
                )
            },
            trailingContent = { Text(if (selected) "⌄" else "›") },
            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun SectionRow(
    section: SectionSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    Row(
        modifier = Modifier
            .padding(horizontal = 10.dp)
            .testTag(WorkspaceTestTags.section(section.id))
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .clickable(onClick = onClick)
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .width(5.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(section.colorArgb)),
        )
        Text(
            text = section.name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun PageListPane(
    section: SectionSummary?,
    selectedPageId: String,
    onAddPage: () -> Unit,
    onSelectPage: (String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(PagePaneWidth)
            .fillMaxHeight()
            .testTag(WorkspaceTestTags.PagePane),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = section?.name ?: "No section",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "${section?.pages?.size ?: 0} pages",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(
                    onClick = onAddPage,
                    enabled = section != null,
                    modifier = Modifier.testTag(WorkspaceTestTags.AddPage),
                ) {
                    Text("＋ Page")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(section?.pages.orEmpty(), key = PageSummary::id) { page ->
                    PageRow(
                        page = page,
                        selected = page.id == selectedPageId,
                        onClick = { onSelectPage(page.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PageRow(
    page: PageSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(WorkspaceTestTags.page(page.id)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = page.title.ifBlank { "Untitled page" },
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = page.preview,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = page.createdLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PageCanvas(
    page: PageSummary?,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val canvasBackground = MaterialTheme.colorScheme.background
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)

    Box(
        modifier
            .testTag(WorkspaceTestTags.PageCanvas)
            .background(canvasBackground),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 32.dp.toPx()
            var x = 0f
            while (x <= size.width) {
                drawLine(
                    color = gridColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
                x += step
            }
            var y = 0f
            while (y <= size.height) {
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
                y += step
            }
        }

        if (page == null) {
            Text(
                text = "Choose a page to begin",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 48.dp, vertical = 34.dp),
        ) {
            OutlinedTextField(
                value = page.title,
                onValueChange = onTitleChange,
                singleLine = true,
                label = { Text("Page title") },
                textStyle = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp)
                    .testTag(WorkspaceTestTags.TitleEditor),
            )
            Text(
                text = page.createdLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            )
            Spacer(Modifier.height(34.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().widthIn(max = 920.dp),
            ) {
                OutlinedTextField(
                    value = page.body,
                    onValueChange = onBodyChange,
                    label = { Text("Page text — in-memory skeleton") },
                    minLines = 10,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .testTag(WorkspaceTestTags.BodyEditor),
                )
            }
            Spacer(Modifier.height(20.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.large,
            ) {
                Text(
                    text = "This skeleton proves the desktop shell and shared state. Persistence and rich formatting arrive in later phases.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}
