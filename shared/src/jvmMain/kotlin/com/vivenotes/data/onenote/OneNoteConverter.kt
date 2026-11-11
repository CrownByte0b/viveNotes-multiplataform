package com.vivenotes.data.onenote

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class OneNoteConversionException(val reason: Reason, detail: String) : Exception(detail) {
    enum class Reason { TableOfContents, Unreadable, NoSections, Failed }
}

/** Android's JNI API, with the desktop library extracted from the application's resources. */
internal object OneNoteConverter {
    private val loadFailure: Throwable? by lazy {
        try {
            loadLibrary()
            null
        } catch (missing: UnsatisfiedLinkError) {
            missing
        } catch (failure: Exception) {
            failure
        }
    }

    val available: Boolean get() = loadFailure == null

    /** Blocks until conversion finishes; callers run it on the IO dispatcher. */
    fun toBundle(input: File, fileName: String, seed: String, output: File): OneNoteConversion {
        check(available) { "The OneNote converter is not in this build." }
        return decodeReport(convert(input.path, fileName, seed, output.path))
    }

    internal fun decodeReport(json: String): OneNoteConversion {
        val report = JSON.decodeFromString<Report>(json)
        report.error?.let { code ->
            val reason = when (code) {
                "tableOfContents" -> OneNoteConversionException.Reason.TableOfContents
                "unreadable" -> OneNoteConversionException.Reason.Unreadable
                "noSections" -> OneNoteConversionException.Reason.NoSections
                else -> OneNoteConversionException.Reason.Failed
            }
            throw OneNoteConversionException(reason, report.detail ?: code)
        }
        return OneNoteConversion(
            sections = report.sections,
            pages = report.pages,
            strokes = report.strokes,
            attachments = report.attachments,
            warnings = report.warnings,
        )
    }

    private fun loadLibrary() {
        val resource = nativeResource(System.getProperty("os.name"), System.getProperty("os.arch"))
            ?: throw UnsatisfiedLinkError("Unsupported OneNote converter platform")
        val input = javaClass.getResourceAsStream(resource)
            ?: throw UnsatisfiedLinkError("The OneNote converter is not in this build")
        input.use {
            val directory = Files.createTempDirectory("vive-onenote-").toFile().apply { deleteOnExit() }
            val library = File(directory, resource.substringAfterLast('/')).apply { deleteOnExit() }
            try {
                library.outputStream().use(input::copyTo)
                System.load(library.absolutePath)
            } catch (failure: Throwable) {
                library.delete()
                directory.delete()
                throw failure
            }
        }
    }

    internal fun nativeResource(os: String, arch: String): String? {
        val architecture = when (arch.lowercase()) {
            "amd64", "x86_64" -> "x86_64"
            "arm64", "aarch64" -> "aarch64"
            else -> return null
        }
        return when {
            os.startsWith("Linux", ignoreCase = true) -> "/onenote/native/linux-$architecture/libvive_converter.so"
            os.startsWith("Windows", ignoreCase = true) -> "/onenote/native/windows-$architecture/vive_converter.dll"
            else -> null
        }
    }

    /** Must match `Java_com_vivenotes_data_onenote_OneNoteConverter_convert` in `native.rs`. */
    @JvmStatic
    private external fun convert(input: String, fileName: String, seed: String, output: String): String

    @Serializable
    private data class Report(
        val sections: Int = 0,
        val pages: Int = 0,
        val strokes: Int = 0,
        val attachments: Int = 0,
        val warnings: List<String> = emptyList(),
        val error: String? = null,
        val detail: String? = null,
    )

    private val JSON = Json { ignoreUnknownKeys = true }
}
