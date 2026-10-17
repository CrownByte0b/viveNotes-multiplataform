package com.vivenotes.search

import com.vivenotes.localization.Strings
import com.vivenotes.ai.AiModelInstallState
import com.vivenotes.ai.AiModelStore
import com.vivenotes.ai.OnnxInkRecognitionEngine
import com.vivenotes.model.DocumentCodecs
import com.vivenotes.model.migrated
import com.vivenotes.model.search.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Image
import java.io.File

@Serializable
internal enum class SearchOperation { Project, Match, Ocr }

@Serializable
internal data class WorkerRequest(
    val operation: SearchOperation,
    val pageId: String = "",
    val sectionId: String = "",
    val title: String = "",
    val body: String? = null,
    val codec: String = DocumentCodecs.default.id,
    val query: String = "",
    val units: List<ContentUnit> = emptyList(),
    val imagePath: String? = null,
)

@Serializable
internal data class WorkerReply(
    @kotlinx.serialization.Required val pid: Long = ProcessHandle.current().pid(),
    val units: List<ContentUnit> = emptyList(),
    val images: List<ImagePlacement> = emptyList(),
    val hits: List<ContentHit> = emptyList(),
    val text: String = "",
    val confidence: Float = 0f,
    val failed: Boolean = false,
    val unreadable: Boolean = false,
)

/** A separate JVM with no database access; stdout carries only the private request protocol. */
object SearchWorker {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val output = System.out.bufferedWriter(Charsets.UTF_8)
        // Native/library diagnostics must never become a protocol reply or disclose note text.
        System.setOut(System.err)
        var models: AiModelStore? = null
        var engine: OnnxInkRecognitionEngine? = null
        try {
            System.`in`.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val reply = try {
                        val request = Json.decodeFromString<WorkerRequest>(line)
                        when (request.operation) {
                            SearchOperation.Project -> project(request)
                            SearchOperation.Match -> WorkerReply(hits = searchContent(request.units, request.query, MAX_HITS + 1))
                            SearchOperation.Ocr -> {
                                if (engine == null) {
                                    // OCR is read from resources. No directory is created, including
                                    // when a worker is killed while its current query is cancelled.
                                    models = AiModelStore(File(System.getProperty("java.io.tmpdir"),
                                        "vivenotes-ocr-${ProcessHandle.current().pid()}"))
                                    val available = models!!.state.first { it.handwritingText != AiModelInstallState.Verifying }
                                    check(available.handwritingText == AiModelInstallState.Installed)
                                    engine = OnnxInkRecognitionEngine(models!!, lanes = 1, inferenceThreads = 1)
                                }
                                Image.makeFromEncoded(File(requireNotNull(request.imagePath)).readBytes()).use { image ->
                                    val result = engine!!.recognizeImageText(image)
                                    WorkerReply(text = result.text, confidence = result.meanConfidence)
                                }
                            }
                        }
                    } catch (_: Exception) {
                        WorkerReply(failed = true)
                    }
                    output.write(Json.encodeToString(reply))
                    output.newLine()
                    output.flush()
                }
            }
        } finally {
            engine?.close()
            models?.close()
        }
    }

    private fun project(request: WorkerRequest): WorkerReply {
        val doc = request.body?.let { body ->
            runCatching {
                val codec = DocumentCodecs.byId(request.codec) ?: error(Strings.unknown_document_format)
                codec.decode(body.encodeToByteArray()).migrated()
            }.getOrNull()
        }
        return WorkerReply(
            units = doc?.contentUnits(request.pageId, request.sectionId, request.title)
                ?: listOfNotNull(titleUnit(request.pageId, request.sectionId, request.title)),
            images = doc?.imagePlacements(request.pageId, request.sectionId).orEmpty(),
            unreadable = request.body != null && doc == null,
        )
    }
}
