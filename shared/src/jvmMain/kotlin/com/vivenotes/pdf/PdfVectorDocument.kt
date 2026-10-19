package com.vivenotes.pdf

import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.bridge.BridgeContext
import org.apache.batik.bridge.DocumentLoader
import org.apache.batik.bridge.GVTBuilder
import org.apache.batik.bridge.UserAgentAdapter
import org.apache.batik.util.XMLResourceDescriptor
import org.apache.fop.svg.PDFDocumentGraphics2D
import org.apache.xmlgraphics.java2d.GraphicContext
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.DynamicMemoryWStream
import org.jetbrains.skia.Rect
import org.jetbrains.skia.svg.SVGCanvas
import java.io.OutputStream

/** Android PdfDocument's desktop adapter: Skia paths, glyphs and embedded pictures stay vector PDF. */
internal class PdfVectorDocument(stream: OutputStream, private val width: Int, private val height: Int) : AutoCloseable {
    private val graphics = PDFDocumentGraphics2D(true).apply {
        graphicContext = GraphicContext()
        deviceDPI = 72f
        setupDocument(stream, width, height)
    }

    fun sheet(draw: (Canvas) -> Unit) {
        val svg = DynamicMemoryWStream().use { output ->
            // Outlined glyphs retain the same bundled/system font metrics on every PDF reader.
            SVGCanvas.make(Rect.makeWH(width.toFloat(), height.toFloat()), output,
                convertTextToPaths = true, prettyXML = false).use(draw)
            ByteArray(output.bytesWritten()).also { output.read(it, 0, it.size) }
        }
        val factory = SAXSVGDocumentFactory(XMLResourceDescriptor.getXMLParserClassName())
        val document = svg.inputStream().use { factory.createSVGDocument(null, it) }
        val userAgent = UserAgentAdapter()
        val bridge = BridgeContext(userAgent, DocumentLoader(userAgent))
        try {
            GVTBuilder().build(bridge, document).paint(graphics)
            graphics.nextPage()
        } finally {
            bridge.dispose()
        }
    }

    override fun close() {
        try { graphics.finish() } finally { graphics.dispose() }
    }
}
