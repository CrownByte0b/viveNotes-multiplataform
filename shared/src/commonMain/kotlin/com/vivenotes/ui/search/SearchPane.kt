package com.vivenotes.ui.search

import com.vivenotes.localization.Strings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vivenotes.model.search.ContentHit
import com.vivenotes.model.search.ContentKind
import com.vivenotes.model.search.snippetOf
import com.vivenotes.search.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object SearchTags {
    const val Open = "search-open"
    const val Pane = "search-pane"
    const val Query = "search-query"
    const val Close = "search-close"
    const val Status = "search-status"
    const val Progress = "search-progress"
    fun hit(hit: ContentHit) = "search-hit-${hit.unit.pageId}-${hit.unit.boxId}-${hit.unit.blockIndex}-${hit.unit.kind}"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SearchPane(
    service: ContentSearchService,
    request: SearchRequest,
    query: String,
    onQueryChange: (String) -> Unit,
    onHit: (ContentHit) -> Unit,
    onClose: () -> Unit,
    focusRequest: Int = 0,
) {
    var results by remember { mutableStateOf(SearchResults()) }
    var retry by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusRequest) { focus.requestFocus() }
    LaunchedEffect(request, retry) {
        results = SearchResults(searching = query.isNotBlank())
        if (query.isBlank()) return@LaunchedEffect
        delay(180)
        try {
            service.search(request).collect { results = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            results = SearchResults(error = Strings.search_could_not_finish_try_again)
        }
    }
    Surface(Modifier.width(340.dp).fillMaxHeight().testTag(SearchTags.Pane),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Strings.search, style = MaterialTheme.typography.titleMedium)
                TextButton(onClose, Modifier.testTag(SearchTags.Close)) { Text(Strings.close) }
            }
            Text(Strings.in_this_notebook_text_and_pictures, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(query, onQueryChange, singleLine = true,
                label = { Text(Strings.search_notebook) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(SearchTags.Query))
            val status = when {
                query.isBlank() -> Strings.search_page_titles_text_boxes_and_text_in_pictures
                results.error != null -> results.error!!
                results.searching && results.imagesTotal == 0 -> Strings.searching
                else -> buildString {
                    append(if (results.truncated) Strings.label_120_matches else Strings.value_matches(results.hitCount))
                    if (results.imagesTotal > 0) append(Strings.pictures_value_value(results.imagesDone, results.imagesTotal))
                    if (results.failedImages > 0) append(Strings.value_could_not_be_read(results.failedImages))
                    if (results.unreadablePages > 0) append(Strings.value_unreadable_pages(results.unreadablePages))
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(SearchTags.Status))
            if (results.searching) LinearWavyProgressIndicator(Modifier.fillMaxWidth().testTag(SearchTags.Progress))
            if (results.error != null) TextButton(onClick = { retry++ }) { Text(Strings.retry_search) }
            if (results.failedImages > 0) TextButton(onClick = {
                scope.launch {
                    try {
                        service.retryFailedImages()
                        retry++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        results = results.copy(error = Strings.pictures_could_not_be_queued_try_again)
                    }
                }
            }) { Text(Strings.retry_pictures) }
            if (query.isNotBlank() && !results.searching && results.error == null && results.hitCount == 0) {
                Text(Strings.no_matches, style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                results.pages.forEach { page ->
                    item(key = "page-${page.pageId}") {
                        Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
                            Text(page.title.ifBlank { Strings.untitled_page }, fontWeight = FontWeight.SemiBold)
                            Text(page.sectionName, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(page.hits, key = SearchTags::hit) { hit ->
                        val snippet = snippetOf(hit.unit.text, hit.spans)
                        val emphasis = MaterialTheme.colorScheme.primary
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.fillMaxWidth().clickable { onHit(hit) }
                                .testTag(SearchTags.hit(hit)).padding(10.dp)) {
                                Text(buildAnnotatedString {
                                    append(snippet.text)
                                    snippet.spans.forEach { addStyle(SpanStyle(color = emphasis,
                                        fontWeight = FontWeight.Bold), it.start, it.end) }
                                }, style = MaterialTheme.typography.bodyMedium)
                                Text(when (hit.unit.kind) {
                                    ContentKind.Title -> Strings.page_title
                                    ContentKind.Image -> Strings.picture_text
                                    ContentKind.Cell -> Strings.table_cell
                                    ContentKind.Text -> Strings.text_box
                                }, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
