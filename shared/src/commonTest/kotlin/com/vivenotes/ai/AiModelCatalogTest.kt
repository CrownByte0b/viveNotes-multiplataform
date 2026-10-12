package com.vivenotes.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pinned catalog is the Android app's, byte for byte: the same files, sizes and digests, so a
 * model downloaded on either platform is the same model.
 */
class AiModelCatalogTest {
    @Test
    fun formulaPackagesArePinnedToAndroidsArtifacts() {
        assertEquals(
            listOf(
                "unimernet-tiny-encoder-int8.onnx:29081861:cf6cc98a54c97254adee4e251f56af2da045062503663b001a254303dab2aa97",
                "unimernet-tiny-decoder-int8.onnx:82108192:b765a9c522f43cff7dcc6379e9c39dfc441d6ee189822cb71be8f5e7cd703a69",
                "pp-formulanet-tokenizer.json:2140014:2811d82701ec97c192fa256aa2b4516929373870ae660326cc5b1dc879b95ff2",
            ),
            AiModelCatalog.formulaArtifacts(FormulaEngine.UniMerNetTiny).map { "${it.fileName}:${it.bytes}:${it.sha256}" },
        )
        assertEquals(
            listOf(
                "pp-formulanet-s.onnx:231878904:0ee32c7bfbd9e586364f89f71860476ccb5334e35674a61f3df5e0553d6a6dcc",
                "pp-formulanet-tokenizer.json:2140014:2811d82701ec97c192fa256aa2b4516929373870ae660326cc5b1dc879b95ff2",
            ),
            AiModelCatalog.formulaArtifacts(FormulaEngine.FormulaNetS).map { "${it.fileName}:${it.bytes}:${it.sha256}" },
        )
        AiModelCatalog.formulaArtifacts(FormulaEngine.UniMerNetTiny).dropLast(1).forEach { artifact ->
            assertTrue(artifact.url.startsWith(AiModelCatalog.UNIMERNET_RELEASE_URL), artifact.url)
            assertTrue(artifact.url.endsWith("/${artifact.fileName}"), artifact.url)
        }
    }

    @Test
    fun theBundledOcrIsTheAndroidAssetsTrio() {
        assertEquals(
            listOf(
                "en_pp-ocrv5_mobile_rec.onnx:7876014:8307465d3c9ef2ba4055c3bd0be55aafe11f518630212b7598b70ccb376028ac",
                "ppocrv5_en_dict.txt:1416:e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6",
                "pp-ocrv5_mobile_det.onnx:4826518:1eb7b4f7ab657ebd1c66d5f79bca7497f29768a2e3c15e52daecbba1a8e4a039",
            ),
            AiModelCatalog.BUNDLED_TEXT.map { "${it.fileName}:${it.bytes}:${it.sha256}" },
        )
    }

    /** What the pane prints: decimal megabytes, as Android's short file sizes read. */
    @Test
    fun sizesReadAsAFileManagerWritesThem() {
        assertEquals("113 MB", formatModelBytes(AiModelCatalog.downloadBytes(FormulaEngine.UniMerNetTiny)))
        assertEquals("234 MB", formatModelBytes(AiModelCatalog.downloadBytes(FormulaEngine.FormulaNetS)))
        assertEquals("12.7 MB", formatModelBytes(AiModelCatalog.bundledTextBytes))
        assertEquals("1 kB", formatModelBytes(1_416))
        assertEquals("100 MB", formatModelBytes(99_960_000))
    }
}
