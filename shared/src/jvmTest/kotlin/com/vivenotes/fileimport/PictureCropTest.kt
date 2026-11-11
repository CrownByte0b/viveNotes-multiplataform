package com.vivenotes.fileimport

import com.vivenotes.fileimport.docx.DocxFixture
import com.vivenotes.fileimport.docx.WordCrop
import com.vivenotes.fileimport.docx.croppedPicture
import com.vivenotes.richtext.CanvasPasteItem
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Android's PictureCropTest, using desktop image codecs, plus both import paths that apply the crop. */
class PictureCropTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun halves(width: Int, height: Int): ByteArray {
        val bitmap = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = bitmap.createGraphics()
        try {
            graphics.color = Color.RED
            graphics.fillRect(0, 0, width / 2, height)
            graphics.color = Color.BLUE
            graphics.fillRect(width / 2, 0, width - width / 2, height)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().use { out ->
            ImageIO.write(bitmap, "png", out)
            out.toByteArray()
        }
    }

    private fun decoded(bytes: ByteArray?): BufferedImage =
        ImageIO.read(ByteArrayInputStream(requireNotNull(bytes)))

    @Test
    fun whatIsCutAwayIsGoneAndTheRestIsKept() {
        val cropped = decoded(croppedPicture(halves(400, 100), WordCrop(0f, 0f, 0.5f, 0f)))

        assertEquals(200, cropped.width)
        assertEquals(100, cropped.height)
        assertEquals(Color.RED.rgb, cropped.getRGB(10, 50))
        assertEquals(Color.RED.rgb, cropped.getRGB(190, 50))
    }

    @Test
    fun aBigPictureIsCutToNoMoreThanTheStoreKeeps() {
        val cropped = decoded(croppedPicture(halves(4200, 2100), WordCrop(0f, 0f, 0.5f, 0f)))

        assertEquals(FileImporter.PICTURE_LONGEST_PX, maxOf(cropped.width, cropped.height))
        assertEquals(cropped.width, cropped.height)
        assertEquals(Color.RED.rgb, cropped.getRGB(cropped.width - 20, cropped.height / 2))
    }

    @Test
    fun paddingIsNotCropping() {
        val cropped = decoded(croppedPicture(halves(400, 100), WordCrop(-0.25f, 0f, 0.5f, 0f)))

        assertEquals(200, cropped.width)
    }

    @Test
    fun aCropThatLeavesNothingOrBytesThatAreNoPictureGiveNothing() {
        assertNull(croppedPicture(halves(100, 100), WordCrop(0.6f, 0f, 0.5f, 0f)))
        assertNull(croppedPicture(byteArrayOf(1, 2, 3), WordCrop(0f, 0f, 0.5f, 0f)))
    }

    @Test
    fun wordObjectsContainOnlyTheVisiblePicture() = runBlocking {
        val file = folder.newFile("crop.docx")
        DocxFixture.write(
            file,
            """<w:p><w:r><w:drawing><wp:inline><wp:extent cx="1828800" cy="914400"/>
                <a:graphic><a:graphicData><pic:pic><pic:blipFill><a:blip r:embed="image"/><a:srcRect r="50000"/></pic:blipFill></pic:pic></a:graphicData></a:graphic>
            </wp:inline></w:drawing></w:r></w:p>""",
            relationships = listOf(DocxFixture.Rel("image", DocxFixture.IMAGE_REL, "media/a.png")),
            parts = mapOf("word/media/a.png" to halves(400, 100)),
        )
        assertImportedCrop(file)
    }

    @Test
    fun slideObjectsContainOnlyTheVisiblePicture() = runBlocking {
        val file = folder.newFile("crop.pptx")
        val office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        fun rels(body: String) = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$body</Relationships>"""
        val entries = mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/></Types>""",
            "_rels/.rels" to rels("""<Relationship Id="main" Type="$office/officeDocument" Target="ppt/presentation.xml"/>"""),
            "ppt/presentation.xml" to """<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="$office"><p:sldIdLst><p:sldId id="256" r:id="slide"/></p:sldIdLst><p:sldSz cx="9144000" cy="6858000"/></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to rels("""<Relationship Id="slide" Type="$office/slide" Target="slides/slide1.xml"/>"""),
            "ppt/slides/slide1.xml" to """<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="$office"><p:cSld><p:spTree><p:pic><p:nvPicPr><p:cNvPr id="2" name="Picture"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="image"/><a:srcRect r="50000"/></p:blipFill><p:spPr><a:xfrm><a:off x="914400" y="914400"/><a:ext cx="1828800" cy="914400"/></a:xfrm><a:prstGeom prst="rect"/></p:spPr></p:pic></p:spTree></p:cSld></p:sld>""",
            "ppt/slides/_rels/slide1.xml.rels" to rels("""<Relationship Id="image" Type="$office/image" Target="../media/a.png"/><Relationship Id="layout" Type="$office/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>"""),
            "ppt/slideLayouts/slideLayout1.xml" to """<p:sldLayout xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree/></p:cSld></p:sldLayout>""",
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to rels("""<Relationship Id="master" Type="$office/slideMaster" Target="../slideMasters/slideMaster1.xml"/>"""),
            "ppt/slideMasters/slideMaster1.xml" to """<p:sldMaster xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree/></p:cSld></p:sldMaster>""",
        )
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries.mapValues { it.value.toByteArray() } + ("ppt/media/a.png" to halves(400, 100))) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        assertImportedCrop(file)
    }

    private suspend fun assertImportedCrop(file: File) {
        val importer = FileImporter(folder.newFolder())
        try {
            val opened = importer.open(file.path)
            val image = importer.objects(opened, listOf(0), emptySet()).single() as CanvasPasteItem.Image
            val bitmap = decoded(image.bytes)
            assertEquals(200, bitmap.width)
            assertEquals(100, bitmap.height)
            assertEquals(Color.RED.rgb, bitmap.getRGB(190, 50))
            assertEquals(2f, image.widthDp!! / image.heightDp!!, 0.01f)
        } finally {
            importer.release()
        }
    }
}
