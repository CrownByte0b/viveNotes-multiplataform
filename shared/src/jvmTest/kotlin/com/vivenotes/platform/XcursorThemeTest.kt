package com.vivenotes.platform

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class XcursorThemeTest {
    /** One square image per size, every pixel [colour] + size, hotspot at a size-specific point. */
    private fun xcursor(vararg sizes: Int, colour: Int = 0x7f000000, frames: Int = 1): ByteArray {
        val images = sizes.flatMap { size -> List(frames) { frame -> size to frame } }
        val tocEnd = 16 + images.size * 12
        val total = tocEnd + images.sumOf { (size, _) -> 36 + size * size * 4 }
        val data = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        data.putInt(0x72756358).putInt(16).putInt(0x10000).putInt(images.size)
        var position = tocEnd
        images.forEach { (size, _) ->
            data.putInt(0xfffd0002.toInt()).putInt(size).putInt(position)
            position += 36 + size * size * 4
        }
        images.forEach { (size, frame) ->
            data.putInt(36).putInt(0xfffd0002.toInt()).putInt(size).putInt(1)
                .putInt(size).putInt(size).putInt(size / 2 - 1).putInt(size / 4).putInt(50)
            repeat(size * size) { data.putInt(colour + size + frame) }
        }
        return data.array()
    }

    private fun Path.cursor(theme: String, name: String, bytes: ByteArray) =
        resolve(theme).resolve("cursors").createDirectories().resolve(name).writeBytes(bytes)

    private fun Path.inherits(theme: String, value: String) =
        resolve(theme).createDirectories().resolve("index.theme").writeText("[Icon Theme]\nName=$theme\nInherits=$value\n")

    @Test
    fun readsTheImageOfTheNearestNominalSizeAndItsFirstAnimationFrame() {
        val bytes = xcursor(24, 48, 96, frames = 2)
        val exact = assertNotNull(XcursorTheme.read(bytes, 48))
        assertEquals(48, exact.nominalSize)
        assertEquals(48, exact.width)
        assertEquals(23, exact.xHot)
        assertEquals(12, exact.yHot)
        assertEquals(0x7f000000 + 48, exact.pixels.first())
        assertEquals(48 * 48, exact.pixels.size)
        assertEquals(24, XcursorTheme.read(bytes, 30)!!.nominalSize)
        assertEquals(96, XcursorTheme.read(bytes, 80)!!.nominalSize)
        assertEquals(96, XcursorTheme.read(bytes, 400)!!.nominalSize)
    }

    @Test
    fun malformedFilesAreRejectedRatherThanRead() {
        val good = xcursor(24)
        assertNull(XcursorTheme.read(byteArrayOf(), 24))
        assertNull(XcursorTheme.read(good.copyOf(40), 24), "truncated pixels")
        assertNull(XcursorTheme.read(good.copyOf().also { it[0] = 'Y'.code.toByte() }, 24), "wrong magic")
        val hugeCount = good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(12, Int.MAX_VALUE) }
        assertNull(XcursorTheme.read(hugeCount, 24), "table of contents past the end")
        val badHotspot = good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(28 + 24, 24) }
        assertNull(XcursorTheme.read(badHotspot, 24), "hotspot outside the image")
        val wide = good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(28 + 16, 1 shl 20) }
        assertNull(XcursorTheme.read(wide, 24), "absurd width")
    }

    @Test
    fun findsNamesThroughTheInheritedThemesInSearchPathOrder() {
        val root = Files.createTempDirectory("xcursor-theme")
        try {
            val user = root.resolve("user")
            val system = root.resolve("system")
            // "default" only names the real theme, as on most desktops.
            user.inherits("default", "Mine")
            system.inherits("Mine", " Base , Missing")
            system.cursor("Base", "all-scroll", xcursor(24, 48, colour = 0x10000000))
            system.cursor("Base", "grabbing", xcursor(24, colour = 0x20000000))
            user.cursor("Mine", "closedhand", xcursor(24, colour = 0x30000000))
            // A user copy shadows the system one, as earlier directories do for libXcursor.
            user.cursor("Base", "all-scroll", xcursor(48, colour = 0x40000000))
            val theme = XcursorTheme("default", listOf(user, system))

            assertEquals(0x40000000 + 48, theme.find(listOf("all-scroll", "fleur"), 48)!!.pixels.first())
            // A CSS name anywhere in the chain is preferred to an older alias nearer the top.
            assertEquals(0x20000000 + 24, theme.find(listOf("grabbing", "closedhand"), 24)!!.pixels.first())
            assertEquals(0x30000000 + 24, theme.find(listOf("dnd-grab", "closedhand"), 24)!!.pixels.first())
            assertNull(theme.find(listOf("zoom-in"), 24))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun inheritanceCyclesBrokenFilesAndSymlinkedAliasesAreHandled() {
        val root = Files.createTempDirectory("xcursor-cycle")
        try {
            root.inherits("A", "B")
            root.inherits("B", "A,../escape")
            root.cursor("A", "all-scroll", byteArrayOf(1, 2, 3))
            root.cursor("B", "all-scroll", xcursor(24))
            val theme = XcursorTheme("A", listOf(root))
            assertEquals(24, theme.find(listOf("all-scroll"), 24)!!.width)
            val link = root.resolve("B/cursors/fleur")
            if (runCatching { Files.createSymbolicLink(link, link.resolveSibling("all-scroll")) }.isSuccess) {
                assertEquals(24, theme.find(listOf("fleur"), 24)!!.width)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun environmentChoosesThemeSizeAndSearchPathLikeLibXcursor() {
        // As paths, not strings: Windows, where the JVM tests run too, writes them with backslashes.
        fun paths(vararg names: String) = names.map { Path.of(it) }
        val env = mapOf("XDG_DATA_HOME" to "/data/home", "XDG_DATA_DIRS" to "/flatpak/share:relative:/usr/share")
        assertEquals(paths("/data/home/icons", "/home/me/.icons", "/flatpak/share/icons", "/usr/share/icons",
            "/usr/share/pixmaps", "/usr/X11R6/lib/X11/icons"),
            XcursorTheme.searchPath(env::get, "/home/me"))
        assertEquals(paths("/home/me/.local/share/icons", "/home/me/.icons", "/usr/local/share/icons", "/usr/share/icons",
            "/usr/share/pixmaps", "/usr/X11R6/lib/X11/icons"),
            XcursorTheme.searchPath({ null }, "/home/me"))
        assertEquals(paths("/home/me/cursors", "/opt/icons"),
            XcursorTheme.searchPath(mapOf("XCURSOR_PATH" to "~/cursors::/opt/icons")::get, "/home/me"))
        assertEquals(24, XcursorTheme.size { null })
        assertEquals(32, XcursorTheme.size(mapOf("XCURSOR_SIZE" to "32")::get))
        assertEquals(24, XcursorTheme.size(mapOf("XCURSOR_SIZE" to "huge")::get))
        assertEquals(24, XcursorTheme.size(mapOf("XCURSOR_SIZE" to "100000")::get))
    }
}
