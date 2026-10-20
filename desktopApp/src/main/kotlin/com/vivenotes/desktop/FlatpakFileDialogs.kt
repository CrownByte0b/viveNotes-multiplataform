package com.vivenotes.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import java.io.File
import java.io.IOException

/** Freedesktop's zenity uses GtkFileChooserNative and the document portal inside Flatpak. */
internal object FlatpakFileDialogs {
    val enabled: Boolean get() = !System.getenv("FLATPAK_ID").isNullOrBlank()
    val dispatcher get() = if (enabled) Dispatchers.IO else Dispatchers.Swing

    fun choose(title: String, extensions: List<String>, name: String? = null): File? =
        chooseWith(title, extensions, name) { command ->
            val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            val output = process.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            process.waitFor() to output
        }

    internal fun chooseWith(
        title: String,
        extensions: List<String>,
        name: String?,
        run: (List<String>) -> Pair<Int, String>,
    ): File? {
        val command = buildList {
            addAll(listOf("/usr/bin/zenity", "--file-selection", "--modal", "--title=$title"))
            add("--file-filter=" + extensions.joinToString(" ") { "*.$it" })
            if (name != null) addAll(listOf("--save", "--confirm-overwrite", "--filename=${File(name).name}"))
        }
        val (status, output) = run(command)
        return when (status) {
            0 -> File(output.removeSuffix("\n")).also {
                if (!it.isAbsolute) throw IOException("The file chooser returned an invalid path")
            }
            1 -> null
            else -> throw IOException("The file chooser exited with status $status")
        }
    }
}
