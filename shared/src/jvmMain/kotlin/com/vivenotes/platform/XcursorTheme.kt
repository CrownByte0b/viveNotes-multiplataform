package com.vivenotes.platform

import java.awt.Component
import java.awt.Toolkit
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.abs
import kotlin.math.ceil

/** One image of an Xcursor file: [pixels] are premultiplied ARGB, as the file stores them. */
internal class XcursorImage(
    val nominalSize: Int,
    val width: Int,
    val height: Int,
    val xHot: Int,
    val yHot: Int,
    val pixels: IntArray,
)

/**
 * The freedesktop cursor theme the desktop draws its own cursors from, found the way libXcursor
 * and libwayland-cursor find it: [theme] and the themes it inherits, in each of [directories].
 * AWT names only a dozen cursors, and its Move cursor is Adwaita's `move` — the plain arrow — so
 * CSS cursors such as `all-scroll` and `grabbing` come from the theme's files directly.
 */
internal class XcursorTheme(private val theme: String, private val directories: List<Path>) {
    /** The first of [names] the theme chain has, at the nominal size nearest [size] pixels. */
    fun find(names: List<String>, size: Int): XcursorImage? {
        val chain = chain()
        for (name in names) for (theme in chain) for (directory in directories) {
            val file = directory.resolve(theme).resolve("cursors").resolve(name)
            val bytes = runCatching {
                if (Files.isRegularFile(file) && Files.size(file) <= MAX_FILE_BYTES) Files.readAllBytes(file) else null
            }.getOrNull() ?: continue
            read(bytes, size)?.let { return it }
        }
        return null
    }

    /** [theme], then what it inherits depth first, each once. */
    private fun chain(): List<String> {
        val themes = LinkedHashSet<String>()
        fun visit(name: String) {
            if (themes.size >= MAX_THEMES || !themes.add(name)) return
            for (directory in directories) inherits(directory.resolve(name).resolve("index.theme")).forEach(::visit)
        }
        visit(theme)
        return themes.toList()
    }

    private fun inherits(index: Path): List<String> = runCatching {
        if (!Files.isRegularFile(index)) return emptyList()
        Files.readAllLines(index).asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("Inherits") && it.substringBefore('=').trim() == "Inherits" }
            ?.substringAfter('=')
            ?.split(',', ';')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && '/' !in it && it != "." && it != ".." }
            .orEmpty()
    }.getOrDefault(emptyList())

    companion object {
        private const val MAGIC = 0x72756358 // "Xcur", little-endian
        private const val IMAGE_TYPE = 0xfffd0002.toInt()
        private const val IMAGE_HEADER = 36
        private const val MAX_SIDE = 1024
        private const val MAX_FILE_BYTES = 16L shl 20
        private const val MAX_THEMES = 32

        /** The theme and size from the environment, as libXcursor reads them. */
        fun fromEnvironment(
            env: (String) -> String? = System::getenv,
            home: String = System.getProperty("user.home").orEmpty(),
        ): XcursorTheme = XcursorTheme(env("XCURSOR_THEME")?.takeIf { it.isNotBlank() } ?: "default",
            searchPath(env, home))

        /** XCURSOR_SIZE: the cursor's side in logical pixels. */
        fun size(env: (String) -> String? = System::getenv): Int =
            env("XCURSOR_SIZE")?.trim()?.toIntOrNull()?.takeIf { it in 8..256 } ?: 24

        /** XCURSOR_PATH, or the XDG icon directories libXcursor searches without it. */
        internal fun searchPath(env: (String) -> String?, home: String): List<Path> {
            fun path(entry: String): Path? = runCatching {
                Paths.get(if (entry == "~" || entry.startsWith("~/")) home + entry.drop(1) else entry)
            }.getOrNull()
            env("XCURSOR_PATH")?.takeIf { it.isNotBlank() }?.let { explicit ->
                return explicit.split(':').filter { it.isNotBlank() }.mapNotNull(::path)
            }
            val dataHome = env("XDG_DATA_HOME")?.takeIf { it.startsWith("/") } ?: "$home/.local/share"
            val dataDirs = env("XDG_DATA_DIRS")?.split(':')?.filter { it.startsWith("/") }?.takeIf { it.isNotEmpty() }
                ?: listOf("/usr/local/share", "/usr/share")
            return (listOf("$dataHome/icons", "~/.icons") + dataDirs.map { "$it/icons" } +
                listOf("/usr/share/pixmaps", "/usr/X11R6/lib/X11/icons")).distinct().mapNotNull(::path)
        }

        /**
         * The first image of the nominal size nearest [size] in Xcursor file [bytes]; an animated
         * cursor's later frames are ignored. A malformed file yields null.
         */
        fun read(bytes: ByteArray, size: Int): XcursorImage? = runCatching {
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bytes.size < 16 || data.getInt(0) != MAGIC) return null
            val headerSize = data.getInt(4)
            val count = data.getInt(12)
            if (headerSize < 16 || count <= 0 || count > (bytes.size - headerSize) / 12) return null
            var chosen = -1
            var chosenSize = 0
            for (index in 0 until count) {
                val entry = headerSize + index * 12
                if (data.getInt(entry) != IMAGE_TYPE) continue
                val nominal = data.getInt(entry + 4)
                if (chosen < 0 || abs(nominal - size) < abs(chosenSize - size)) {
                    chosen = data.getInt(entry + 8)
                    chosenSize = nominal
                }
            }
            if (chosen < 0 || chosen > bytes.size - IMAGE_HEADER) return null
            if (data.getInt(chosen) != IMAGE_HEADER || data.getInt(chosen + 4) != IMAGE_TYPE) return null
            val width = data.getInt(chosen + 16)
            val height = data.getInt(chosen + 20)
            val xHot = data.getInt(chosen + 24)
            val yHot = data.getInt(chosen + 28)
            if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE || xHot !in 0 until width || yHot !in 0 until height) return null
            val start = chosen + IMAGE_HEADER
            if (bytes.size - start < width * height * 4) return null
            val pixels = IntArray(width * height) { data.getInt(start + it * 4) }
            XcursorImage(chosenSize, width, height, xHot, yHot, pixels)
        }.getOrNull()
    }
}

/**
 * Pixels per logical pixel in a custom cursor's image for [component]'s window. JBR's native
 * Wayland toolkit sets the cursor buffer's scale to the output's, so the image must be that much
 * larger; X11 and Windows cursors are in device pixels already.
 */
internal fun customCursorScale(component: Component?): Int {
    if (Toolkit.getDefaultToolkit().javaClass.name != "sun.awt.wl.WLToolkit") return 1
    val scale = component?.graphicsConfiguration?.defaultTransform?.scaleX ?: return 1
    return ceil(scale).toInt().coerceIn(1, 8)
}
