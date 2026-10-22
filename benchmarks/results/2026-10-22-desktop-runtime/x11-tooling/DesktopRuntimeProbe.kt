package com.vivenotes.runtimeprobe

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkCodec
import com.vivenotes.byteink.kit.ViveInkTool
import com.vivenotes.byteink.kit.StoredInkStroke
import com.vivenotes.byteink.kit.ViveInkPage
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.NotebookEntity
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.PageContentEntity
import com.vivenotes.data.db.PageEntity
import com.vivenotes.data.db.SectionEntity
import com.vivenotes.model.Block
import com.vivenotes.model.JsonDocumentCodec
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.Run
import com.sun.management.OperatingSystemMXBean
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.Properties
import javax.accessibility.Accessible
import javax.accessibility.AccessibleRole
import javax.accessibility.AccessibleState
import javax.imageio.ImageIO
import jdk.jfr.Category
import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Opt-in diagnostic entry point. Not an application hook and never part of the shipped app. */
object DesktopRuntimeProbe {
    private const val VERSION = "vive-desktop-runtime-probe/1"
    private const val INK_PAGE = "runtime-probe-ink"
    private const val TEXT_PAGE = "runtime-probe-text"
    private const val INK_TITLE = "01 Runtime ink"
    private const val TEXT_TITLE = "02 Runtime text"
    private const val TOKEN = "viveprobetoken"
    private const val FIXTURE_TIME = 1_800_000_000_000L
    private lateinit var work: File
    private lateinit var events: File
    private var ownedFrame: Frame? = null
    private var rendering: RuntimeRenderProbe? = null
    private val json = Json { prettyPrint = true }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size % 2 == 0) { "Arguments must be --name value pairs" }
        val options = args.toList().chunked(2).associate { (key, value) ->
            require(key.startsWith("--"))
            key.removePrefix("--") to value
        }
        Locale.setDefault(Locale.ENGLISH)
        work = File(options.getValue("work-dir")).canonicalFile
        require(File(System.getProperty("user.dir")).canonicalFile == work) { "The probe must run in its isolated working directory" }
        val owner = File(work, ".vive-runtime-probe-owner")
        require(owner.isFile && owner.readText().trim() == options.getValue("owner-token")) { "Missing isolated-profile ownership marker" }
        require(!Files.isSymbolicLink(owner.toPath()) && !Files.isSymbolicLink(File(work, "devdb").toPath()))
        require(System.getProperty("vivenotes.profile") == "dev") { "The probe requires -Dvivenotes.profile=dev" }
        events = File(work, "events.jsonl")
        when (options.getValue("mode")) {
            "seed" -> seed(options)
            "verify" -> verify(options)
            "app" -> app(options)
            "probe-contract" -> probeContract()
            "render-contract" -> File(work, "render-contract.json").writeText(RuntimeRenderProbe.selfContract().toString() + "\n")
            else -> error("Unsupported probe mode")
        }
    }

    private fun seed(options: Map<String, String>) = runBlocking {
        val directory = File(work, "devdb")
        require(!directory.exists()) { "Seeding refuses an existing profile" }
        val count = options.getValue("ink-count").toInt().also { require(it in 1..100_000) }
        val textCount = options.getValue("text-outlines").toInt().also { require(it in 1..5_000) }
        val characters = options.getValue("characters").toInt().also { require(it in 64..10_000) }
        val text = textFixture(textCount, characters)
        val inkDocument = JsonDocumentCodec.encodeToString(PageDoc())
        val textDocument = JsonDocumentCodec.encodeToString(text)
        val brush = ViveInkTool(ViveBrushes.MARKER, 0, 0xff668877.toInt(), 2f)
        val rows = List(count) { index ->
            val x = index % 200 * 12f
            val y = 100f + index / 200 * 12f
            val stroke = Stroke(brush.brush, MutableStrokeInputBatch().apply {
                add(InputToolType.MOUSE, x + 1f, y + 3f, 0L)
                add(InputToolType.MOUSE, x + 5f, y + 6f, 10L)
                add(InputToolType.MOUSE, x + 9f, y + 3f, 20L)
            })
            ViveInkCodec.encodeStroke(stroke, "runtime-stroke-${index.toString().padStart(6, '0')}",
                INK_PAGE, index, ViveBrushes.MARKER, 0, false, FIXTURE_TIME).let { row ->
                InkStrokeEntity(row.id, row.pageId, row.seq, row.brushFamily, row.brushVersion,
                    row.sizeDp, row.colorArgb, row.colorFollowsTheme, row.epsilon, row.stabilization,
                    row.minX, row.minY, row.maxX, row.maxY, row.points, row.enc, row.createdAt,
                    row.groupId, row.deletedAt)
            }
        }
        val fixtureHash = fingerprint(rows, inkDocument, textDocument)
        val db = NotesDatabase.create(File(directory, NotesDatabase.FILE_NAME))
        try {
            db.useWriterConnection { writer -> writer.immediateTransaction {
                db.notebookDao().insert(NotebookEntity("runtime-notebook", "Runtime fixture", 0xff668877.toInt(), 0,
                    createdAt = FIXTURE_TIME, updatedAt = FIXTURE_TIME))
                db.sectionDao().insert(SectionEntity("runtime-section", "runtime-notebook", "Synthetic pages", 0xff668877.toInt(), 0,
                    FIXTURE_TIME, FIXTURE_TIME))
                db.pageDao().insert(PageEntity(INK_PAGE, "runtime-section", INK_TITLE, 0,
                    createdAt = FIXTURE_TIME, updatedAt = FIXTURE_TIME))
                db.pageDao().insert(PageEntity(TEXT_PAGE, "runtime-section", TEXT_TITLE, 1,
                    preview = "runtime paragraph 0000", createdAt = FIXTURE_TIME, updatedAt = FIXTURE_TIME))
                db.pageContentDao().upsert(PageContentEntity(INK_PAGE, inkDocument, FIXTURE_TIME, JsonDocumentCodec.id))
                db.pageContentDao().upsert(PageContentEntity(TEXT_PAGE, textDocument, FIXTURE_TIME, JsonDocumentCodec.id))
                db.inkStrokeDao().insert(rows)
                db.syncDao().clearOutbox()
            } }
            val loaded = db.inkStrokeDao().byPage(INK_PAGE)
            check(loaded.size == count && fingerprint(loaded, inkDocument, textDocument) == fixtureHash)
        } finally { db.close() }
        File(directory, "config").mkdirs()
        File(directory, "config/ai.properties").writeText(
            "deletedFormulaModels=UniMerNetSmall,UniMerNetTiny,FormulaNetS\n")
        File(directory, "math_engine").mkdirs()
        File(directory, "math_engine/removed").writeText("Isolated runtime probe: automatic installs disabled.\n")
        File(directory, "config/interface.properties").writeText("displayScale=1.0\nuiScale=1.0\nfontScale=1.0\n")
        File(directory, "config/view.properties").writeText("zoom=1.0\ntabsLayout=Vertical\n")
        File(work, "fixture.json").writeText(json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("schema", VERSION); put("fixtureSha256", fixtureHash); put("inkRows", count)
            put("inkCorpusSha256", fingerprint(rows, inkDocument, ""))
            put("textOutlines", textCount); put("charactersPerOutline", characters)
            put("inkEncoding", rows.first().enc); put("pointBytes", rows.sumOf { it.points.size.toLong() })
            put("automaticModelDownload", false); put("automaticMathInstall", false); put("account", "absent")
        }) + "\n")
    }

    private fun textFixture(count: Int, characters: Int): PageDoc = PageDoc(outlines = List(count) { index ->
        val prefix = "runtime paragraph ${index.toString().padStart(4, '0')} "
        Outline.Text("runtime-text-$index", x = 0f, y = 100f + index * 300f, width = 720f,
            blocks = listOf(Block("runtime-block-$index", runs = listOf(Run((prefix +
                "fixed deterministic text for desktop profiling. ".repeat(characters / 40 + 1)).take(characters))))))
    })

    private fun verify(options: Map<String, String>) = runBlocking {
        val fixture = json.parseToJsonElement(File(work, "fixture.json").readText()) as JsonObject
        val count = (fixture.getValue("inkRows") as JsonPrimitive).content.toInt()
        val expectedAdded = options.getValue("strokes").toInt().also { require(it in 0..256) }
        val expectedText = textFixture((fixture.getValue("textOutlines") as JsonPrimitive).content.toInt(),
            (fixture.getValue("charactersPerOutline") as JsonPrimitive).content.toInt())
        val db = NotesDatabase.create(File(work, "devdb/${NotesDatabase.FILE_NAME}"))
        try {
            val rows = db.inkStrokeDao().byPage(INK_PAGE)
            val original = rows.filter { it.id.startsWith("runtime-stroke-") }
            val added = rows.filterNot { it.id.startsWith("runtime-stroke-") }
            val inkContent = checkNotNull(db.pageContentDao().byId(INK_PAGE))
            val textContent = checkNotNull(db.pageContentDao().byId(TEXT_PAGE))
            check(inkContent.format == JsonDocumentCodec.id && textContent.format == JsonDocumentCodec.id)
            val ink = inkContent.docJson
            val text = textContent.docJson
            check(original.size == count) { "Original ink rows changed: ${original.size}/$count" }
            check(fingerprint(original, ink, "") == (fixture.getValue("inkCorpusSha256") as JsonPrimitive).content) {
                "Original canonical ink or ink document changed"
            }
            check(added.size == expectedAdded) { "Robot ink did not persist exactly: ${added.size}/$expectedAdded" }
            check(added.all { it.seq >= count && it.points.isNotEmpty() && it.pageId == INK_PAGE && it.deletedAt == null })
            for (row in added) check(ViveInkPage.decode(StoredInkStroke(row.id, row.pageId, row.seq,
                row.brushFamily, row.brushVersion, row.sizeDp, row.colorArgb, row.colorFollowsTheme,
                row.epsilon, row.stabilization, row.minX, row.minY, row.maxX, row.maxY, row.points,
                row.enc, row.createdAt, row.groupId, row.deletedAt))?.pageBounds != null) { "Invalid authored native ink" }
            val occurrences = verifyText(JsonDocumentCodec.decodeFromString(text), expectedText, expectedAdded > 0)
            check(db.inkEraseDao().byPage(INK_PAGE).isEmpty() && db.inkMoveDao().byPage(INK_PAGE).isEmpty()) {
                "The lasso-only workflow must not create erase or move operations"
            }
            check(db.inkStrokeDao().byPage(TEXT_PAGE).isEmpty()) { "Ink was accidentally drawn on the text page" }
            ProbeDatabase().use { observed ->
                check(observed.long("SELECT COUNT(*) FROM notebooks") == 1L)
                check(observed.long("SELECT COUNT(*) FROM sections") == 1L)
                check(observed.long("SELECT COUNT(*) FROM pages") == 2L)
                check(observed.text("SELECT title FROM pages WHERE id = ?", INK_PAGE) == INK_TITLE)
                check(observed.text("SELECT title FROM pages WHERE id = ?", TEXT_PAGE) == TEXT_TITLE)
            }
            if (expectedAdded == 0) check(fingerprint(original, ink, text) ==
                (fixture.getValue("fixtureSha256") as JsonPrimitive).content)
            File(work, "verification.json").writeText(json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("schema", VERSION); put("passed", true); put("originalInkRows", original.size)
                put("persistedRobotStrokes", added.size); put("persistedTextToken", TOKEN in text)
                put("originalTextIntact", true); put("tokenOccurrences", occurrences)
                put("addedNativeInkValid", true); put("unexpectedEraseOrMove", false)
                put("notebooks", 1); put("sections", 1); put("pages", 2)
                put("fixtureSha256", (fixture.getValue("fixtureSha256") as JsonPrimitive).content)
            }) + "\n")
        } finally { db.close() }
    }

    private fun verifyText(actual: PageDoc, expected: PageDoc, edited: Boolean): Int {
        if (!edited) { check(actual == expected) { "Original text fixture changed" }; return 0 }
        check(actual.outlines.size == expected.outlines.size)
        val first = actual.outlines.first() as? Outline.Text ?: error("First text box is absent")
        val original = expected.outlines.first() as Outline.Text
        check(first.blocks.size == 1 && first.blocks.single().runs.all { it.marks.isEmpty() })
        val content = first.blocks.single().text
        val occurrences = content.windowed(TOKEN.length).count { it == TOKEN }
        check(occurrences == 1 && content.replace(TOKEN, "") == original.blocks.single().text) {
            "The editor must insert exactly one owned token without losing original text"
        }
        val cleaned = first.copy(blocks = listOf(first.blocks.single().copy(runs = original.blocks.single().runs)))
        check(actual.copy(outlines = listOf(cleaned) + actual.outlines.drop(1)) == expected) {
            "Text geometry, formatting or unrelated outlines changed"
        }
        return occurrences
    }

    private fun probeContract() {
        val expected = textFixture(3, 80)
        val first = expected.outlines.first() as Outline.Text
        fun changed(body: String, remaining: List<Outline> = expected.outlines.drop(1)): PageDoc = expected.copy(
            outlines = listOf(first.copy(blocks = listOf(first.blocks.single().copy(runs = listOf(Run(body)))))) + remaining)
        val source = first.blocks.single().text
        check(verifyText(expected, expected, false) == 0)
        check(verifyText(changed(source.take(12) + TOKEN + source.drop(12)), expected, true) == 1)
        for (invalid in listOf(expected, changed(source + TOKEN + TOKEN), changed(source.drop(1) + TOKEN),
            changed(source + TOKEN, expected.outlines.drop(1).reversed()))) {
            check(runCatching { verifyText(invalid, expected, true) }.isFailure)
        }
        File(work, "probe-contract.json").writeText(buildJsonObject {
            put("schema", VERSION); put("passed", true); put("textValidationCases", 6)
        }.toString() + "\n")
    }

    /** Short read-only observations; no write, retained transaction or direct session mutation. */
    private class ProbeDatabase : AutoCloseable {
        private val connection = BundledSQLiteDriver().open(File(work, "devdb/${NotesDatabase.FILE_NAME}").absolutePath,
            SQLITE_OPEN_READONLY)
        fun long(sql: String, vararg values: String): Long = connection.prepare(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.bindText(index + 1, value) }
            check(statement.step()); statement.getLong(0)
        }
        fun text(sql: String, vararg values: String): String = connection.prepare(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.bindText(index + 1, value) }
            check(statement.step()); statement.getText(0)
        }
        fun addedStrokes(): Long = long("SELECT COUNT(*) FROM ink_strokes WHERE pageId = ? AND deletedAt IS NULL AND id NOT LIKE 'runtime-stroke-%'", INK_PAGE)
        fun textSaved(): Boolean = long("SELECT instr(docJson, ?) FROM page_content WHERE pageId = ?", TOKEN, TEXT_PAGE) > 0
        override fun close() = connection.close()
    }

    private fun fingerprint(rows: List<InkStrokeEntity>, ink: String, text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DataOutputStream(DigestOutputStream(OutputStream.nullOutputStream(), digest)).use { out ->
            out.writeUTF(VERSION); out.writeUTF("canonical-genuine-ink-and-text/1")
            for (body in listOf(ink, text)) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size); out.write(bytes)
            }
            out.writeInt(rows.size)
            for (row in rows) {
                out.writeUTF(row.id); out.writeUTF(row.pageId); out.writeInt(row.seq)
                out.writeUTF(row.brushFamily); out.writeInt(row.brushVersion); out.writeFloat(row.sizeDp)
                out.writeInt(row.colorArgb); out.writeInt(when (row.colorFollowsTheme) { null -> -1; false -> 0; true -> 1 })
                out.writeFloat(row.epsilon); out.writeInt(row.stabilization)
                out.writeFloat(row.minX); out.writeFloat(row.minY); out.writeFloat(row.maxX); out.writeFloat(row.maxY)
                out.writeUTF(row.enc); out.writeLong(row.createdAt); out.writeInt(row.points.size); out.write(row.points)
                check(row.groupId == null && row.deletedAt == null)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun app(options: Map<String, String>) {
        val strokes = options.getValue("strokes").toInt().also { require(it in 1..256) }
        if (System.getProperty("os.name").startsWith("Linux", ignoreCase = true)) {
            System.setProperty("awt.toolkit.name", if (options.getValue("backend") == "native") "WLToolkit" else "XToolkit")
        }
        val startup = System.nanoTime()
        mark("main_enter", "instant")
        Runtime.getRuntime().addShutdownHook(Thread({ mark("process_shutdown", "instant") }, "probe-shutdown"))
        Thread({
            var frame: Frame? = null
            try {
                frame = await(60_000) { onEdt { Frame.getFrames().firstOrNull { it.isShowing && it.title == "Vive Notes" } } }
                mark("frame_visible", "instant", System.nanoTime() - startup)
                val ownFrame = requireNotNull(frame)
                ownedFrame = ownFrame
                onEdt {
                    val screen = ownFrame.graphicsConfiguration.bounds
                    ownFrame.setBounds(screen.x + 30, screen.y + 30, minOf(1280, screen.width - 60), minOf(900, screen.height - 60))
                    ownFrame.toFront(); ownFrame.requestFocus()
                    rendering = RuntimeRenderProbe.install(ownFrame)
                }
                val robot = Robot().apply { autoDelay = 12 }
                await(10_000) { onEdt { ownFrame.isFocused.takeIf { it } } }
                awaitTarget(ownFrame, INK_TITLE, prefix = true, leftHalf = true)
                val initialFrames = checkNotNull(rendering).completedFrames()
                click(robot, awaitTarget(ownFrame, "Draw").bounds)
                awaitTarget(ownFrame, "Pen 1", enabled = true)
                awaitEditor(ownFrame, INK_TITLE, exact = true)
                awaitRender(initialFrames)
                saveRuntimeMetadata(ownFrame, options)
                mark("workspace_ready", "instant", System.nanoTime() - startup,
                    evidence = evidence("selectedPage" to INK_PAGE, "inkReady" to true,
                        "completedFrames" to checkNotNull(rendering).completedFrames()))
                nmtHandshake("initial", options)
                phase("idle_initial") { Thread.sleep(2_000); evidence("idleMillis" to 2_000) }
                ProbeDatabase().use { persisted ->
                phase("robot_ink") {
                    click(robot, awaitTarget(ownFrame, "Pen 1", enabled = true).bounds)
                    await(10_000) { targets(ownFrame).firstOrNull { it.name == "Pen 1" && it.selected } }
                    val viewport = canvasViewport(ownFrame).bounds
                    val point = Point(viewport.x + viewport.width * 2 / 3, viewport.y + viewport.height / 2)
                    repeat(strokes) { index ->
                        val x = point.x
                        val y = point.y + index % 8 * 5
                        drag(robot, listOf(Point(x, y), Point(x + 20, y + 8), Point(x + 40, y - 2)))
                    }
                    evidence("injectedStrokes" to strokes, "penSelected" to true)
                }
                phase("autosave_settle") {
                    val count = await(30_000) { persisted.addedStrokes().takeIf { it == strokes.toLong() } }
                    evidence("persistedRobotStrokes" to count, "pollMillis" to 100)
                }
                phase("robot_viewport") {
                    val before = canvasViewport(ownFrame)
                    val viewport = before.bounds
                    robot.mouseMove(viewport.x + viewport.width * 2 / 3, viewport.y + viewport.height / 2)
                    ensureOwnFocus()
                    repeat(10) { robot.mouseWheel(1); Thread.sleep(35) }
                    val down = await(10_000) { canvasViewport(ownFrame).value?.takeIf { it > checkNotNull(before.value) } }
                    repeat(10) { robot.mouseWheel(-1); Thread.sleep(35) }
                    val restored = await(10_000) { canvasViewport(ownFrame).value?.takeIf { kotlin.math.abs(it - checkNotNull(before.value)) < 1.0 } }
                    val zoomBefore = persistedZoom()
                    robot.keyPress(KeyEvent.VK_CONTROL)
                    var zoomChanged = zoomBefore
                    try {
                        robot.mouseWheel(-2)
                        zoomChanged = await(10_000) { persistedZoom().takeIf { it > zoomBefore } }
                        robot.mouseWheel(2)
                    }
                    finally { robot.keyRelease(KeyEvent.VK_CONTROL) }
                    val zoomRestored = await(10_000) { persistedZoom().takeIf { kotlin.math.abs(it - zoomBefore) < .0001f } }
                    robot.waitForIdle()
                    evidence("scrollBefore" to before.value, "scrollDown" to down, "scrollRestored" to restored,
                        "zoomBefore" to zoomBefore, "zoomChanged" to zoomChanged, "zoomRestored" to zoomRestored)
                }
                phase("robot_lasso") {
                    click(robot, awaitTarget(ownFrame, "Lasso", enabled = true).bounds)
                    await(10_000) { targets(ownFrame).firstOrNull { it.name == "Lasso" && it.selected } }
                    val viewport = canvasViewport(ownFrame).bounds
                    val x = viewport.x + viewport.width * 2 / 3
                    val y = viewport.y + viewport.height / 2
                    drag(robot, listOf(Point(x - 10, y - 20), Point(x + 75, y - 20),
                        Point(x + 75, y + 65), Point(x - 10, y + 65), Point(x - 10, y - 20)))
                    awaitTarget(ownFrame, "Copy selection", enabled = true)
                    key(robot, KeyEvent.VK_ESCAPE)
                    await(10_000) { true.takeIf { targets(ownFrame).none { it.name == "Copy selection" } &&
                        targets(ownFrame).any { it.name == "Lasso" && !it.selected } } }
                    evidence("lassoSelectionObserved" to true, "selectionCleared" to true)
                }
                phase("robot_text_page") {
                    click(robot, awaitTarget(ownFrame, TEXT_TITLE, prefix = true, leftHalf = true).bounds)
                    awaitEditor(ownFrame, TEXT_TITLE, exact = true)
                    val text = awaitEditor(ownFrame, "runtime paragraph 0000")
                    val point = Point(text.bounds.x + minOf(80, text.bounds.width / 2), text.bounds.y + minOf(15, text.bounds.height / 2))
                    click(robot, point)
                    await(10_000) { targets(ownFrame).firstOrNull { it.editable && it.focused && it.text?.startsWith("runtime paragraph 0000") == true } }
                    key(robot, KeyEvent.VK_END)
                    for (character in TOKEN) key(robot, KeyEvent.getExtendedKeyCodeForChar(character.code))
                    robot.waitForIdle()
                    await(10_000) { targets(ownFrame).firstOrNull { it.editable && it.focused && it.text?.contains(TOKEN) == true } }
                    evidence("selectedPage" to TEXT_PAGE, "editorTokenObserved" to true)
                }
                phase("text_autosave_settle") {
                    await(30_000) { true.takeIf { persisted.textSaved() } }
                    evidence("persistedTextToken" to true, "pollMillis" to 100)
                }
                phase("robot_return_ink") {
                    key(robot, KeyEvent.VK_ESCAPE)
                    click(robot, awaitTarget(ownFrame, INK_TITLE, prefix = true, leftHalf = true).bounds)
                    awaitEditor(ownFrame, INK_TITLE, exact = true)
                    awaitTarget(ownFrame, "Pen 1", enabled = true)
                    evidence("selectedPage" to INK_PAGE, "inkReady" to true)
                }
                }
                nmtHandshake("final", options)
                phase("idle_final") { Thread.sleep(2_000); evidence("idleMillis" to 2_000) }
                mark("driver_complete", "instant")
            } catch (failure: Throwable) {
                mark("driver_failed", "error", error = failure.toString())
                frame?.let { saveAccessibleTree(it) }
                frame?.let { own -> runCatching { ImageIO.write(Robot().createScreenCapture(onEdt { own.bounds }), "png", File(work, "failure-window.png")) } }
            } finally {
                frame?.let { own -> onEdt {
                    rendering?.let { probe ->
                        runCatching { probe.close() }.onFailure { mark("driver_failed", "error", error = it.toString()) }
                        runCatching { File(work, "rendering.json").writeText(probe.summary().toString() + "\n") }
                    }
                    mark("window_close_request", "instant")
                    own.dispatchEvent(WindowEvent(own, WindowEvent.WINDOW_CLOSING))
                } }
            }
        }, "runtime-probe-robot").apply { isDaemon = true; start() }
        // Deliberately launches the real entry point. No mocked Compose host or direct session edits.
        Class.forName("com.vivenotes.desktop.MainKt").getMethod("main", Array<String>::class.java)
            .invoke(null, if (options["backend"] == "x11") arrayOf("--x11") else emptyArray<String>())
    }

    private data class Target(val name: String, val bounds: Rectangle, val enabled: Boolean,
        val editable: Boolean, val focused: Boolean, val selected: Boolean, val role: AccessibleRole?,
        val vertical: Boolean, val value: Double?, val maximum: Double?, val text: String?)

    private fun targets(frame: Frame): List<Target> = onEdt {
        val found = ArrayList<Target>()
        val visited = Collections.newSetFromMap(IdentityHashMap<Accessible, Boolean>())
        val frameBounds = frame.bounds
        fun visit(node: Accessible, depth: Int, parentBounds: Rectangle?) {
            if (depth > 48 || visited.size >= 10_000 || !visited.add(node)) return
            val context = node.accessibleContext ?: return
            val component = context.accessibleComponent
            val role = context.accessibleRole
            val states = context.accessibleStateSet
            var bounds: Rectangle? = parentBounds
            if (component != null && component.isShowing) runCatching {
                val location = component.locationOnScreen
                val size = component.size
                bounds = Rectangle(location, size).intersection(frameBounds).takeIf { it.width > 0 && it.height > 0 }
            }
            if (bounds != null && (component?.isShowing == true || role == AccessibleRole.SCROLL_BAR)) runCatching {
                val editor = context.accessibleEditableText
                val text = editor?.let { it.getTextRange(0, it.charCount.coerceAtMost(20_000)) }
                val value = context.accessibleValue
                found += Target(context.accessibleName.orEmpty(), checkNotNull(bounds), component?.isEnabled != false,
                    states.contains(AccessibleState.EDITABLE) || editor != null, states.contains(AccessibleState.FOCUSED),
                    states.contains(AccessibleState.SELECTED), role, states.contains(AccessibleState.VERTICAL),
                    value?.currentAccessibleValue?.toDouble(), value?.maximumAccessibleValue?.toDouble(), text)
            }
            for (index in 0 until context.accessibleChildrenCount.coerceAtMost(10_000)) {
                context.getAccessibleChild(index)?.let { visit(it, depth + 1, bounds) }
            }
        }
        visit(frame, 0, frameBounds)
        found
    }

    private fun awaitTarget(frame: Frame, label: String, enabled: Boolean = false,
        prefix: Boolean = false, leftHalf: Boolean = false): Target = await(30_000) {
        val frameBounds = onEdt { frame.bounds }
        targets(frame).firstOrNull { target ->
            (if (prefix) target.name == label || target.name.startsWith("$label,") else target.name == label) &&
                (!enabled || target.enabled) && (!leftHalf || target.bounds.centerX < frameBounds.centerX) &&
                frameBounds.intersects(target.bounds)
        }
    }

    private fun awaitEditor(frame: Frame, content: String, exact: Boolean = false): Target = await(30_000) {
        targets(frame).firstOrNull { target -> target.editable && target.enabled &&
            target.text?.let { if (exact) it == content else it.startsWith(content) } == true }
    }

    private fun canvasViewport(frame: Frame): Target {
        val bounds = onEdt { frame.bounds }
        return targets(frame).filter { it.role == AccessibleRole.SCROLL_BAR && it.vertical &&
            it.value != null && (it.maximum ?: 0.0) > 0.0 && it.bounds.centerX > bounds.centerX }
            .maxByOrNull { it.bounds.width.toLong() * it.bounds.height }
            ?.also { check(it.bounds.width >= 240 && it.bounds.height >= 240) }
            ?: error("The owned canvas has no observable vertical scroll range")
    }

    private fun persistedZoom(): Float {
        val properties = Properties()
        File(work, "devdb/config/view.properties").inputStream().use(properties::load)
        return checkNotNull(properties.getProperty("zoom")?.toFloatOrNull())
    }

    private fun saveRuntimeMetadata(frame: Frame, options: Map<String, String>) {
        File(work, "runtime.json").writeText(onEdt { buildJsonObject {
            put("schema", VERSION)
            put("javaRuntime", System.getProperty("java.runtime.version")); put("javaVendor", System.getProperty("java.vendor"))
            put("javaVm", System.getProperty("java.vm.name")); put("javaHome", System.getProperty("java.home"))
            put("vmArgs", JsonArray(ManagementFactory.getRuntimeMXBean().inputArguments.map(::JsonPrimitive)))
            put("profile", System.getProperty("vivenotes.profile")); put("userDir", System.getProperty("user.dir"))
            put("classPath", System.getProperty("java.class.path")); put("requestedBackend", options.getValue("backend"))
            put("awtToolkit", Toolkit.getDefaultToolkit().javaClass.name)
            put("frame", rectangleJson(frame.bounds)); put("screen", rectangleJson(frame.graphicsConfiguration.bounds))
            put("scaleX", frame.graphicsConfiguration.defaultTransform.scaleX)
            put("scaleY", frame.graphicsConfiguration.defaultTransform.scaleY)
            put("inputSafety", "Every Robot action checks ownership and focus; points must stay inside the owned frame")
            put("completionScope", "Accessible state, read-only SQLite observations and completed Skia callbacks; no presentation or physical-latency timestamp")
        } }.toString() + "\n")
    }

    private fun rectangleJson(rectangle: Rectangle): JsonObject = buildJsonObject {
        put("x", rectangle.x); put("y", rectangle.y); put("width", rectangle.width); put("height", rectangle.height)
    }

    private fun evidence(vararg fields: Pair<String, Any?>): JsonObject = buildJsonObject {
        fields.forEach { (name, value) -> when (value) {
            is Boolean -> put(name, value)
            is Number -> put(name, value)
            null -> put(name, kotlinx.serialization.json.JsonNull)
            else -> put(name, value.toString())
        } }
    }

    private fun awaitRender(before: Long) {
        await(15_000) { checkNotNull(rendering).completedFrames().takeIf { it > before } }
    }

    private fun nmtHandshake(which: String, options: Map<String, String>) {
        if (options["nmt-handshake"] != "true") return
        mark("nmt_${which}_request", "instant")
        val path = File(work, "nmt-idle_$which.done")
        val outcome = await(15_000) {
            if (!path.isFile) null else runCatching { json.parseToJsonElement(path.readText()) as JsonObject }.getOrNull()
        }
        val status = (outcome.getValue("status") as JsonPrimitive).content
        check(status in setOf("captured", "unavailable"))
        mark("nmt_${which}_complete", "instant", evidence = evidence("status" to status))
    }

    private fun saveAccessibleTree(frame: Frame) {
        runCatching { File(work, "accessibility.tsv").writeText(targets(frame).joinToString("\n") {
            "${it.bounds}\t${it.enabled}\t${it.role}\tselected=${it.selected}\tfocused=${it.focused}\t${it.name.take(160).replace('\n', ' ')}\t${it.text?.take(160)?.replace('\n', ' ')}\tvalue=${it.value}\tmax=${it.maximum}\tvertical=${it.vertical}"
        }) }
    }

    private fun click(robot: Robot, bounds: Rectangle) = click(robot, Point(bounds.centerX.toInt(), bounds.centerY.toInt()))
    private fun ensureOwnFocus(point: Point? = null) {
        onEdt {
            val frame = checkNotNull(ownedFrame)
            check(frame.isShowing && frame.isFocused) { "Owned frame lost focus; input is stopped" }
            check(point == null || frame.bounds.contains(point)) { "Input would leave the owned frame" }
        }
    }
    private fun click(robot: Robot, point: Point) {
        ensureOwnFocus(point)
        robot.mouseMove(point.x, point.y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        try { } finally { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
        robot.waitForIdle()
    }
    private fun key(robot: Robot, code: Int) {
        ensureOwnFocus(); check(code != KeyEvent.VK_UNDEFINED)
        robot.keyPress(code); try { } finally { robot.keyRelease(code) }
    }
    private fun drag(robot: Robot, points: List<Point>) {
        points.forEach(::ensureOwnFocus)
        robot.mouseMove(points.first().x, points.first().y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        try {
            for ((from, to) in points.zipWithNext()) repeat(6) { step ->
                robot.mouseMove(from.x + (to.x - from.x) * (step + 1) / 6,
                    from.y + (to.y - from.y) * (step + 1) / 6)
            }
        } finally { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
        robot.waitForIdle()
    }
    private fun <T : Any> await(timeoutMillis: Long, value: () -> T?): T {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        do { value()?.let { return it }; Thread.sleep(100) } while (System.nanoTime() < deadline)
        error("Timed out waiting for the owned application window/control")
    }
    private fun <T> onEdt(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var value: Result<T>? = null
        EventQueue.invokeAndWait { value = runCatching(block) }
        return checkNotNull(value).getOrThrow()
    }
    private inline fun phase(name: String, block: () -> JsonObject) {
        val observer = checkNotNull(rendering)
        val framesBefore = observer.completedFrames()
        observer.setPhase(name)
        val event = ProbePhase().apply { phase = name; begin() }
        mark(name, "begin")
        val start = System.nanoTime()
        try {
            val observed = block()
            if (name.startsWith("robot_")) awaitRender(framesBefore)
            val details = buildJsonObject {
                observed.forEach { (key, value) -> put(key, value) }
                put("completedFramesBefore", framesBefore); put("completedFramesAfter", observer.completedFrames())
            }
            event.outcome = "complete"; mark(name, "end", System.nanoTime() - start, evidence = details)
        }
        catch (failure: Throwable) { event.outcome = "failed"; throw failure }
        finally { event.end(); event.commit(); observer.setPhase("between_phases") }
    }
    @Synchronized
    private fun mark(name: String, state: String, elapsed: Long? = null, error: String? = null, evidence: JsonObject? = null) {
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        val os = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
        val record = buildJsonObject {
            put("schema", VERSION); put("phase", name); put("state", state)
            put("nanoTime", System.nanoTime()); put("epochMillis", System.currentTimeMillis())
            put("processCpuNanos", os?.processCpuTime ?: -1L); put("heapUsed", heap.used)
            put("heapCommitted", heap.committed); put("threadCount", ManagementFactory.getThreadMXBean().threadCount)
            elapsed?.let { put("elapsedNanos", it) }; error?.let { put("error", it) }
            evidence?.let { put("evidence", it) }
        }
        events.appendText(record.toString() + "\n")
    }
}

@Name("com.vivenotes.RuntimeProbePhase")
@Label("Vive Notes runtime probe phase")
@Category("Vive Notes diagnostics")
class ProbePhase : Event() {
    @JvmField @Label("Phase") var phase: String = ""
    @JvmField @Label("Outcome") var outcome: String = ""
}
