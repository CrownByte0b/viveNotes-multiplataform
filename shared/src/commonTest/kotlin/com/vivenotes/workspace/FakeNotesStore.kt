package com.vivenotes.workspace

import com.vivenotes.data.DeletionOutcome
import com.vivenotes.data.NotesStore
import com.vivenotes.data.PageLoad
import com.vivenotes.data.db.NotebookEntity
import com.vivenotes.data.db.NotebookWithSections
import com.vivenotes.data.db.PageEntity
import com.vivenotes.data.db.SectionEntity
import com.vivenotes.data.pagePreview
import com.vivenotes.model.PageDoc
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [NotesStore] in memory, observable the way Room is: flows re-emit when their rows change. It
 * records every write, and can be told to fail, so a session's storage traffic can be asserted.
 */
class FakeNotesStore : NotesStore {

    private val tree = MutableStateFlow<List<NotebookWithSections>>(emptyList())
    private val pageRows = MutableStateFlow<List<PageEntity>>(emptyList())
    private val bodies = mutableMapOf<String, PageLoad>()
    private var nextId = 0
    private var now = 1_000L

    val saves = mutableListOf<Pair<String, PageDoc>>()
    val renames = mutableListOf<Pair<String, String>>()
    val loads = mutableListOf<String>()
    val deletes = mutableListOf<String>()

    /** Saves and deletes in the order storage received them, as `save:<id>` and `delete:<id>`. */
    val writes = mutableListOf<String>()

    /** Thrown by every delete while set. */
    var deleteFailure: Exception? = null

    /** Holds every delete until completed, so what the session does meanwhile can be observed. */
    var deleteGate: CompletableDeferred<Unit>? = null
    var seeds = 0
        private set

    /** Thrown by every save while set, as a full disk or a vanished drive would. */
    var saveFailure: Exception? = null

    /** Holds seeding — and so the whole first read — until completed, like a slow disk at launch. */
    var seedGate: CompletableDeferred<Unit>? = null

    /** Adds a notebook of [sections], each a name and its pages' titles and bodies. */
    fun notebook(name: String, vararg sections: Pair<String, List<Pair<String, PageDoc>>>): List<String> {
        val notebook = NotebookEntity(id(name), name, 0, tree.value.size, createdAt = now, updatedAt = now)
        val sectionRows = sections.mapIndexed { index, (sectionName, pages) ->
            val section = SectionEntity(id(sectionName), notebook.id, sectionName, 0, index, now, now)
            pages.forEachIndexed { order, (title, doc) -> addPage(section.id, title, order, doc) }
            section
        }
        tree.value = tree.value + NotebookWithSections(notebook, sectionRows)
        return sectionRows.map { it.id }
    }

    /** Replaces a page's stored body underneath whoever has it open, as sync or an import would. */
    fun storeBody(pageId: String, load: PageLoad) {
        bodies[pageId] = load
    }

    fun body(pageId: String): PageLoad? = bodies[pageId]

    /** Rewrites a page row the way another writer would, so the pages flow re-emits. */
    fun touchPage(pageId: String, title: String) {
        pageRows.value = pageRows.value.map { if (it.id == pageId) it.copy(title = title, updatedAt = ++now) else it }
    }

    fun pageIdsIn(sectionId: String): List<String> = pageRows.value.filter { it.sectionId == sectionId }.map { it.id }

    override suspend fun seedIfEmpty() {
        seedGate?.await()
        seeds++
        if (tree.value.isEmpty()) notebook("My Notebook", "Getting Started" to listOf("Welcome" to PageDoc.empty()))
    }

    override fun observeTree(): Flow<List<NotebookWithSections>> = tree

    override fun observePages(sectionId: String): Flow<List<PageEntity>> =
        pageRows.map { rows -> rows.filter { it.sectionId == sectionId }.sortedBy { it.sortIndex } }.distinctUntilChanged()

    override suspend fun pageById(id: String): PageEntity? = pageRows.value.firstOrNull { it.id == id }

    override suspend fun createPage(sectionId: String, title: String): String =
        addPage(sectionId, title, pageRows.value.count { it.sectionId == sectionId }, PageDoc.empty())

    override suspend fun renamePage(id: String, title: String) {
        renames += id to title
        touchPage(id, title)
    }

    override suspend fun renameNotebook(id: String, name: String) {
        renames += id to name
        tree.value = tree.value.map { entry ->
            if (entry.notebook.id == id) entry.copy(notebook = entry.notebook.copy(name = name, updatedAt = ++now)) else entry
        }
    }

    override suspend fun renameSection(id: String, name: String) {
        renames += id to name
        tree.value = tree.value.map { entry ->
            entry.copy(sections = entry.sections.map { if (it.id == id) it.copy(name = name, updatedAt = ++now) else it })
        }
    }

    /** Takes the rows out of the flows, as a tombstone does; nothing here restores them. */
    override suspend fun deleteNotebook(id: String): DeletionOutcome {
        recordDelete(id)
        val sectionIds = tree.value.firstOrNull { it.notebook.id == id }?.sections.orEmpty().map { it.id }.toSet()
        tree.value = tree.value.filterNot { it.notebook.id == id }
        pageRows.value = pageRows.value.filterNot { it.sectionId in sectionIds }
        return DeletionOutcome.Tombstoned
    }

    override suspend fun deleteSection(id: String): DeletionOutcome {
        recordDelete(id)
        tree.value = tree.value.map { entry -> entry.copy(sections = entry.sections.filterNot { it.id == id }) }
        pageRows.value = pageRows.value.filterNot { it.sectionId == id }
        return DeletionOutcome.Tombstoned
    }

    override suspend fun deletePage(id: String): DeletionOutcome {
        recordDelete(id)
        pageRows.value = pageRows.value.filterNot { it.id == id }
        return DeletionOutcome.Tombstoned
    }

    override suspend fun loadDoc(pageId: String): PageLoad {
        loads += pageId
        return bodies.getValue(pageId)
    }

    override suspend fun saveDoc(pageId: String, doc: PageDoc) {
        saveFailure?.let { throw it }
        saves += pageId to doc
        writes += "save:$pageId"
        bodies[pageId] = PageLoad.Loaded(doc)
        pageRows.value = pageRows.value.map {
            if (it.id == pageId) it.copy(preview = pagePreview(doc), updatedAt = ++now) else it
        }
    }

    private suspend fun recordDelete(id: String) {
        deleteGate?.await()
        deleteFailure?.let { throw it }
        deletes += id
        writes += "delete:$id"
    }

    private fun addPage(sectionId: String, title: String, order: Int, doc: PageDoc): String {
        val id = id(title.ifBlank { "page" })
        pageRows.value = pageRows.value + PageEntity(id, sectionId, title, order, pagePreview(doc), now, now)
        bodies[id] = PageLoad.Loaded(doc)
        return id
    }

    private fun id(name: String) = "${name.lowercase().replace(' ', '-')}-${nextId++}"
}
