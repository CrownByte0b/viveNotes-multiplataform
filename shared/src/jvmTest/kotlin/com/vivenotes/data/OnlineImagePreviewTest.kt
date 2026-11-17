package com.vivenotes.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** What Search online fetches for a result: its tile, and the picture once it is picked. */
@OptIn(InternalComposeUiApi::class)
class OnlineImagePreviewTest {

    @BeforeTest
    fun initializeGraphicsWithoutDependingOnAnEarlierUiTest() { registerSkikoComposeImplementation() }

    private val opened = mutableListOf<String>()

    @Test
    fun aTileIsTheSourcesRenditionDecodedAtGridSize() = runBlocking {
        val search = search(mapOf(THUMB to { Canned(body = png(1200, 600)) }))
        val tile = assertNotNull(search.thumbnail(IMAGE))
        assertEquals(OnlineImageSearch.PREVIEW_MAX_DIMENSION to 180, tile.width to tile.height)
        assertEquals(listOf(THUMB), opened)
    }

    @Test
    fun aTileIsFetchedOnceThenRemembered() = runBlocking {
        val search = search(mapOf(THUMB to { Canned(body = png(40, 30)) }))
        val first = search.thumbnail(IMAGE)
        assertSame(first, search.thumbnail(IMAGE))
        assertEquals(listOf(THUMB), opened)
    }

    @Test
    fun whenTheRenditionIsUnavailableTheOriginalIsTheTileNeverEnlarged() = runBlocking {
        // Openverse's thumbnail service answering for a provider it cannot reach.
        val search = search(mapOf(
            THUMB to { Canned(code = 424, type = "application/json") },
            ORIGINAL to { Canned(body = png(50, 40)) },
        ))
        val tile = assertNotNull(search.thumbnail(IMAGE))
        assertEquals(50 to 40, tile.width to tile.height)
        assertEquals(listOf(THUMB, ORIGINAL), opened)
    }

    @Test
    fun anUnavailableThumbnailFallsBackToSvgPixelsWithTransparency() = runBlocking {
        val original = svg()
        val image = IMAGE.copy(url = SVG_ORIGINAL)
        val search = search(mapOf(
            THUMB to { Canned(code = 424, type = "application/json") },
            SVG_ORIGINAL to { Canned(type = "image/svg+xml", body = original) },
        ))
        val tile = assertNotNull(search.thumbnail(image))
        assertEquals(360 to 180, tile.width to tile.height)
        val pixels = tile.toPixelMap()
        assertEquals(Color.TRANSPARENT, pixels[90, 90].toArgb())
        assertEquals(Color.RED, pixels[270, 90].toArgb())
        assertSame(tile, search.thumbnail(image))
        assertEquals(listOf(THUMB, SVG_ORIGINAL), opened)
        assertContentEquals(original, search.download(image))
    }

    @Test
    fun aCommonsSvgUsesItsPngPreviewAndDownloadsTheOriginalVector() = runBlocking {
        val image = WikimediaCommonsClient.parseSearch("""
            {"query":{"pages":[{"pageid":1,"title":"File:Diagram.svg","imageinfo":[{
              "url":"$SVG_ORIGINAL","thumburl":"$THUMB",
              "mime":"image/svg+xml","width":8000,"height":4000}]}]}}
        """.trimIndent(), "diagram")!!.images.single()
        val original = svg()
        val search = search(mapOf(
            THUMB to { Canned(body = png(40, 20)) },
            SVG_ORIGINAL to { Canned(type = "image/svg+xml", body = original) },
        ))
        val tile = assertNotNull(search.thumbnail(image))
        assertEquals(40 to 20, tile.width to tile.height)
        assertEquals(Color.BLUE, tile.toPixelMap()[20, 10].toArgb())
        assertContentEquals(original, search.download(image))
        assertEquals(listOf(THUMB, SVG_ORIGINAL), opened)
    }

    @Test
    fun noTileAtAllIsNullAndIsAskedForAgainLater() = runBlocking {
        val search = search(emptyMap())
        assertNull(search.thumbnail(IMAGE))
        assertNull(search.thumbnail(IMAGE))
        assertEquals(listOf(THUMB, ORIGINAL, THUMB, ORIGINAL), opened)
    }

    @Test
    fun aTileThatDoesNotDecodeIsNoTile() = runBlocking {
        val search = search(mapOf(THUMB to { Canned(body = "not a picture".encodeToByteArray()) }))
        assertNull(search.thumbnail(IMAGE))
    }

    @Test
    fun aPickedPictureIsTheOriginalWhenItCanBeHad() = runBlocking {
        val original = png(64, 48)
        val search = search(mapOf(ORIGINAL to { Canned(body = original) }, THUMB to { Canned(body = png(8, 6)) }))
        assertContentEquals(original, search.download(IMAGE))
        assertEquals(listOf(ORIGINAL), opened)
    }

    @Test
    fun aPickedPictureFallsBackToTheTileWhenTheOriginalIsNotAPicture() = runBlocking {
        // A removed Flickr photo answers with an HTML page.
        val tile = png(8, 6)
        val search = search(mapOf(
            ORIGINAL to { Canned(type = "text/html", body = "<html>gone</html>".encodeToByteArray()) },
            THUMB to { Canned(body = tile) },
        ))
        assertContentEquals(tile, search.download(IMAGE))
        assertNull(search(emptyMap()).download(IMAGE))
    }

    @Test
    fun theCacheLetsTheLeastRecentlyUsedPreviewGoFirst() {
        // Room for two 10 × 10 previews of four bytes a pixel.
        val cache = PreviewCache(maxBytes = 2 * 10 * 10 * 4L)
        val a = ImageBitmap(10, 10)
        val b = ImageBitmap(10, 10)
        val c = ImageBitmap(10, 10)
        cache.put("a", a)
        cache.put("b", b)
        assertSame(a, cache.get("a"))
        cache.put("c", c)
        assertNull(cache.get("b"))
        assertSame(a, cache.get("a"))
        assertSame(c, cache.get("c"))
        assertEquals(2, cache.size)
        // A preview larger than the whole budget is trimmed away with the rest, as LruCache trims.
        cache.put("huge", ImageBitmap(40, 40))
        assertEquals(0, cache.size)
    }

    private fun search(answers: Map<String, () -> Canned>) = OnlineImageSearch(
        http = PictureHttp(openConnection = { url ->
            opened += url.toString()
            answers[url.toString()]?.invoke() ?: Canned(code = 404, type = "text/html")
        }),
        commons = { _, _ -> error("not a search") },
        openverse = { _, _ -> error("not a search") },
    )

    private companion object {
        const val ORIGINAL = "https://live.staticflickr.com/a.jpg"
        const val SVG_ORIGINAL = "https://example.org/diagram.svg"
        const val THUMB = "https://api.openverse.org/v1/images/a/thumb/"
        val IMAGE = OnlineImage("a", "A fox", ORIGINAL, THUMB, 1200, 600, ImageSource.Openverse)

        fun svg(): ByteArray = """<svg xmlns="http://www.w3.org/2000/svg" width="80" height="40"><path fill="red" d="M40 0h40v40H40z"/></svg>""".encodeToByteArray()

        fun png(width: Int, height: Int): ByteArray = Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.clear(Color.BLUE)
            surface.makeImageSnapshot().use { it.encodeToData(EncodedImageFormat.PNG)!!.bytes }
        }
    }
}
