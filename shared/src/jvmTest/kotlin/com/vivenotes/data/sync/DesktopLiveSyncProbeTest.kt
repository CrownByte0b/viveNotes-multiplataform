package com.vivenotes.data.sync

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkCodec
import com.vivenotes.byteink.kit.ViveInkTool
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.db.AttachmentEntity
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.ink.InkPageReader
import com.vivenotes.ink.toEntity
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import java.io.File
import java.net.URI
import java.security.MessageDigest
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Explicit local-server/connected-emulator diagnostic. Never opens the user's notes database. */
class DesktopLiveSyncProbeTest {
    @Test
    fun exchangeOnlyAnOwnedTemporaryNotebookWithTheExistingAndroidApp() = runBlocking {
        assumeTrue(System.getenv("VIVE_DESKTOP_LIVE_SYNC") == "1")
        val root = File(requireNotNull(System.getenv("VIVE_DESKTOP_LIVE_DIRECTORY"))).canonicalFile
        require(root.toPath().startsWith(File("/tmp").toPath()) && root.name.startsWith("vive-desktop-live-"))
        root.mkdirs()
        root.setReadable(false, false); root.setReadable(true, true)
        root.setExecutable(false, false); root.setExecutable(true, true)
        val phase = System.getenv("VIVE_DESKTOP_LIVE_PHASE") ?: "prepare"
        require(phase in setOf("prepare", "verify", "cleanup"))
        val source = File(requireNotNull(System.getenv("VIVE_DESKTOP_LIVE_ACCOUNT"))).canonicalFile
        require(source.isFile)
        val copiedAccount = File(root, "account.preferences_pb")
        source.copyTo(copiedAccount, overwrite = true)
        copiedAccount.setReadable(false, false); copiedAccount.setReadable(true, true)
        copiedAccount.setWritable(false, false); copiedAccount.setWritable(true, true)
        val accountScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val account = try {
            assertNotNull(SyncAccountStore(copiedAccount, null, accountScope).account.first())
        } finally { accountScope.cancel(); copiedAccount.delete() }
        require(URI(account.serverUrl).host in setOf("localhost", "127.0.0.1", "::1")) {
            "This diagnostic is restricted to the local verification server"
        }
        val state = File(root, "owned-note.json")
        val db = NotesDatabase.create(File(root, "notes.db"))
        try {
            val repository = NotesRepository(db)
            val pictures = TemporaryAttachmentBytes(File(root, "pictures"))
            val client = SyncServerClient()
            val sync = HierarchySync(db, client, RemoteInkSignal(), AttachmentBlobSync(db, client, pictures))
            suspend fun synchronize() {
                val result = withTimeout(120_000) { sync.run(account) }
                assertTrue(result is SyncRunResult.Succeeded, "Local live synchronization: $result")
            }
            synchronize()
            if (phase == "prepare") {
                check(!state.exists()) { "An owned verification notebook already exists; verify/clean it first" }
                val name = "DesktopPerfVerify-${System.currentTimeMillis()}"
                val notebook = repository.createNotebook(name)
                val section = repository.createSection(notebook, "Interoperability")
                val page = repository.createPage(section, "Desktop to Android")
                // Record ownership before network work so cleanup remains possible after interruption.
                state.writeText(JsonObject(mapOf("notebook" to JsonPrimitive(notebook),
                    "section" to JsonPrimitive(section), "page" to JsonPrimitive(page), "name" to JsonPrimitive(name))).toString())
                val png = Surface.makeRasterN32Premul(8, 8).use { surface ->
                    surface.canvas.clear(0xff176cce.toInt())
                    surface.makeImageSnapshot().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
                }
                val digest = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
                pictures.write(digest, png)
                val now = System.currentTimeMillis()
                db.attachmentDao().insert(AttachmentEntity(digest, "image/png", 8, 8, png.size.toLong(), 0, now))
                repository.saveDoc(page, PageDoc(outlines = listOf(
                    Outline.Text(id = "desktop-live-text", blocks = listOf(Block.of("Desktop interoperability verification"))),
                    Outline.Image(id = "desktop-live-picture", attachmentId = digest, x = 150f, y = 220f, width = 64f, height = 64f),
                )))
                val tool = ViveInkTool(ViveBrushes.MARKER, 0, 0xff176cce.toInt(), 5f)
                val stroke = Stroke(tool.brush, MutableStrokeInputBatch().apply {
                    add(InputToolType.MOUSE, 90f, 400f, 0L)
                    add(InputToolType.MOUSE, 160f, 440f, 50L)
                    add(InputToolType.MOUSE, 230f, 400f, 100L)
                })
                repository.addStroke(ViveInkCodec.encodeStroke(stroke, "desktop-live-$page", page, 0,
                    ViveBrushes.MARKER, 0, false, now).toEntity())
                synchronize()
                assertTrue(db.syncDao().outbox(32).isEmpty())
                println("DESKTOP_LIVE_SYNC prepared name=$name")
            } else {
                val owned = Json.parseToJsonElement(state.readText()) as JsonObject
                val notebook = owned.getValue("notebook").jsonPrimitive.content
                val page = owned.getValue("page").jsonPrimitive.content
                val name = owned.getValue("name").jsonPrimitive.content
                assertEquals(name, db.notebookDao().byId(notebook)?.name, "Cleanup must retain exact ownership")
                if (phase == "verify") {
                    val rows = db.inkStrokeDao().byPage(page)
                    assertTrue(rows.size >= 2, "Android must have contributed a new persisted stroke to the owned page")
                    assertTrue(rows.any { it.id == "desktop-live-$page" })
                    val decoded = InkPageReader.read(page, rows, emptyList(), emptyList())
                    assertEquals(rows.size, decoded.strokes.size)
                    println("DESKTOP_LIVE_SYNC Android stroke imported and decoded; rows=${rows.size}")
                }
                repository.deleteNotebook(notebook)
                synchronize()
                assertNotNull(db.notebookDao().byId(notebook)?.deletedAt)
                println("DESKTOP_LIVE_SYNC cleaned owned notebook=$name")
            }
        } finally { db.close() }
    }
}
