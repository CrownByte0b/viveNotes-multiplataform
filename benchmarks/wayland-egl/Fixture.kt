package com.vivenotes.eglprobe

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.execSQL
import com.vivenotes.byteink.kit.StoredInkStroke
import com.vivenotes.byteink.kit.StoredInkErase
import com.vivenotes.byteink.kit.ViveInkPage
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkCodec
import com.vivenotes.byteink.kit.ViveInkTool
import com.vivenotes.data.db.*
import com.vivenotes.model.JsonDocumentCodec
import com.vivenotes.model.PageDoc
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import kotlin.math.sin

/** Account-free, deterministic native ink distributed over a scrollable page. */
object Fixture {
    @JvmStatic fun addedRows(work: String): Int = BundledSQLiteDriver().open(
        File(work, "devdb/${NotesDatabase.FILE_NAME}").path, SQLITE_OPEN_READONLY).use { connection ->
        connection.prepare("SELECT COUNT(*) FROM ink_strokes WHERE pageId = 'egl-page' AND id NOT LIKE 'egl-stroke-%' AND deletedAt IS NULL").use {
            check(it.step()); it.getLong(0).toInt()
        }
    }
    @JvmStatic fun visibleAddedStrokes(work: String): Int = BundledSQLiteDriver().open(
        File(work, "devdb/${NotesDatabase.FILE_NAME}").path, SQLITE_OPEN_READONLY).use { connection ->
        connection.execSQL("BEGIN")
        try {
            val strokes = connection.prepare("SELECT id,pageId,seq,brushFamily,brushVersion,sizeDp,colorArgb,colorFollowsTheme,epsilon,stabilization,minX,minY,maxX,maxY,points,enc,createdAt,groupId FROM ink_strokes WHERE pageId='egl-page' AND id NOT LIKE 'egl-stroke-%' AND deletedAt IS NULL").use { query ->
                buildList { while (query.step()) add(StoredInkStroke(query.getText(0), query.getText(1),
                    query.getLong(2).toInt(), query.getText(3), query.getLong(4).toInt(), query.getDouble(5).toFloat(),
                    query.getLong(6).toInt(), query.getLong(7) != 0L, query.getDouble(8).toFloat(), query.getLong(9).toInt(),
                    query.getDouble(10).toFloat(), query.getDouble(11).toFloat(), query.getDouble(12).toFloat(), query.getDouble(13).toFloat(),
                    query.getBlob(14), query.getText(15), query.getLong(16), if (query.isNull(17)) null else query.getText(17), null)) }
            }
            val erases = connection.prepare("SELECT id,pageId,mode,sizeDp,points,enc,createdAt FROM ink_erases WHERE pageId='egl-page' AND deletedAt IS NULL").use { query ->
                buildList { while (query.step()) {
                    val id = query.getText(0)
                    val targets = connection.prepare("SELECT strokeId FROM ink_erase_targets WHERE eraseId=?").use { target ->
                        target.bindText(1, id)
                        buildList { while (target.step()) add(target.getText(0)) }
                    }
                    add(StoredInkErase(id, query.getText(1), query.getText(2), query.getDouble(3).toFloat(),
                        query.getBlob(4), query.getText(5), query.getLong(6), null, targets))
                } }
            }
            ViveInkPage.load(strokes, erases, emptyList()).strokes.size
        } finally { connection.execSQL("ROLLBACK") }
    }
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 6)
        val work = File(args[0]).canonicalFile
        require(File(System.getProperty("user.dir")).canonicalFile == work)
        require(File(work, ".egl-probe-owner").readText().trim() == args[1])
        val count = args[2].toInt().also { require(it in 1..40_000) }
        val verify = args[3] != "seed"
        require(args[3] in listOf("seed", "verify", "contract"))
        val width = args[4].toInt().also { require(it in 1..200) }
        val points = args[5].toInt().also { require(it in 3..128) }
        val directory = File(work, "devdb")
        if (!verify) require(!directory.exists()) { "Refusing to overwrite a profile" }
        val doc = JsonDocumentCodec.encodeToString(PageDoc())
        val time = 1_800_000_000_000L
        val hash = MessageDigest.getInstance("SHA-256")
        fun hashRows(rows: List<InkStrokeEntity>): String {
            hash.update(doc.toByteArray())
            rows.sortedBy { it.seq }.forEach { row ->
                hash.update(listOf(row.id, row.pageId, row.seq, row.brushFamily, row.brushVersion, row.sizeDp,
                    row.colorArgb, row.colorFollowsTheme, row.epsilon, row.stabilization,
                    row.minX, row.minY, row.maxX, row.maxY, row.enc, row.createdAt, row.groupId,
                    row.deletedAt).joinToString("|").toByteArray())
                hash.update(row.points)
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        val database = NotesDatabase.create(File(directory, NotesDatabase.FILE_NAME))
        try {
            if (!verify) {
                val rows = List(count) { index ->
                    val color = if (index % 4 == 0) 0x7788bb22 else 0xff263850.toInt()
                    val family = if (index % 4 == 0) ViveBrushes.HIGHLIGHTER else ViveBrushes.MARKER
                    val tool = ViveInkTool(family, 0, color, if (index % 4 == 0) 6f else 2f)
                    val spacing = 600f / width
                    val x = 20f + index % width * spacing
                    val y = 100f + index / width * 90f
                    val stroke = Stroke(tool.brush, MutableStrokeInputBatch().apply {
                        repeat(points) { point ->
                            val fraction = point.toFloat() / (points - 1)
                            add(InputToolType.MOUSE, x + fraction * spacing * .9f,
                                y + sin(fraction * 9f + index % 7) * 10f, point * 4L)
                        }
                    })
                    ViveInkCodec.encodeStroke(stroke, "egl-stroke-${index.toString().padStart(6, '0')}",
                        "egl-page", index, family, 0, false, time).let { row ->
                        InkStrokeEntity(row.id, row.pageId, row.seq, row.brushFamily, row.brushVersion,
                            row.sizeDp, row.colorArgb, row.colorFollowsTheme, row.epsilon, row.stabilization,
                            row.minX, row.minY, row.maxX, row.maxY, row.points, row.enc, row.createdAt,
                            row.groupId, row.deletedAt)
                    }
                }
                val expected = hashRows(rows)
                database.useWriterConnection { writer -> writer.immediateTransaction {
                    database.notebookDao().insert(NotebookEntity("egl-notebook", "Renderer fixture", 0xff263850.toInt(), 0,
                        createdAt = time, updatedAt = time))
                    database.sectionDao().insert(SectionEntity("egl-section", "egl-notebook", "Synthetic ink", 0xff263850.toInt(), 0, time, time))
                    database.pageDao().insert(PageEntity("egl-page", "egl-section", "01 Renderer ink", 0, createdAt = time, updatedAt = time))
                    database.pageContentDao().upsert(PageContentEntity("egl-page", doc, time, JsonDocumentCodec.id))
                    database.inkStrokeDao().insert(rows)
                    database.syncDao().clearOutbox()
                } }
                require(hashRows(database.inkStrokeDao().byPage("egl-page")) == expected)
                File(work, "fixture.json").writeText(buildJsonObject {
                    put("schema", "vive-egl-fixture/1"); put("strokes", count); put("columns", width)
                    put("pointsPerStroke", points); put("sha256", expected)
                }.toString() + "\n")
            } else {
                val fixture = Json.parseToJsonElement(File(work, "fixture.json").readText()).jsonObject
                if (args[3] == "contract") {
                    val original = database.inkStrokeDao().byPage("egl-page").first()
                    val authored = List(2) { original.copy(id = "contract-authored-$it", seq = count + it) }
                    database.inkStrokeDao().insert(authored)
                    check(addedRows(work.path) == 2 && visibleAddedStrokes(work.path) == 2)
                    val erase = InkEraseEntity("contract-erase", "egl-page", com.vivenotes.data.EraserMode.Object,
                        64f, original.points, original.enc, time + 1)
                    database.useWriterConnection { writer -> writer.immediateTransaction {
                        database.inkEraseDao().insert(erase)
                        database.inkEraseDao().insertTargets(authored.map { InkEraseTargetEntity(erase.id, it.id) })
                    } }
                    check(addedRows(work.path) == 2 && visibleAddedStrokes(work.path) == 0) {
                        "Object erase must remove geometry while preserving source rows for undo"
                    }
                }
                val rows = database.inkStrokeDao().byPage("egl-page").filter { it.id.startsWith("egl-stroke-") }
                require(rows.size == count && hashRows(rows) == fixture.getValue("sha256").jsonPrimitive.content)
                require(database.pageContentDao().byId("egl-page")?.docJson == doc)
                require(database.inkEraseDao().byPage("egl-page").none { erase -> erase.targets.any { it.strokeId.startsWith("egl-stroke-") } })
                require(visibleAddedStrokes(work.path) == 0) { "Authored ink remains visible" }
                println("FIXTURE_UNCHANGED")
            }
        } finally { database.close() }
        if (!verify) {
            File(directory, "config").mkdirs()
            File(directory, "config/ai.properties").writeText("deletedFormulaModels=UniMerNetSmall,UniMerNetTiny,FormulaNetS\n")
            File(directory, "math_engine").mkdirs()
            File(directory, "math_engine/removed").writeText("Benchmark: automatic installation disabled.\n")
            File(directory, "config/interface.properties").writeText("displayScale=1.0\nuiScale=1.0\nfontScale=1.0\n")
            File(directory, "config/view.properties").writeText("zoom=1.0\ntabsLayout=Vertical\n")
            File(directory, "config/ink.properties").writeText("eraser.mode=Object\neraser.sizeDp=16\n")
        }
    }
}
