package com.vivenotes.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop models are pinned independently; legacy formula packages keep Android's pins.
 */
class AiModelCatalogTest {
    @Test
    fun smallUsesTheValidatedGithubReleaseWithoutChangingAndroidPackages() {
        val artifacts = AiModelCatalog.formulaArtifacts(FormulaEngine.UniMerNetSmall)
        assertEquals(
            listOf(
                "unimernet-small-encoder-int8.onnx:61983109:4982c38cbed338e5bdc24420322128c63bef7199415eb8662c74a32fbdf665d8",
                "unimernet-small-decoder-int8.onnx:144912813:6fb715a5e9b840b0e4b8f9cb98dbe815f5563c18c49ed8938d630e145820140e",
                "unimernet-small-tokenizer.json:2140013:02c318d9cfa95bf323371762b8f838a82709530274d36dba6eca880f0add6cc4",
            ),
            artifacts.map { "${it.fileName}:${it.bytes}:${it.sha256}" },
        )
        artifacts.forEach { artifact ->
            assertEquals(
                "https://github.com/AquilaIgnis/viveNotes/releases/download/models-unimernet-small-v1/${artifact.fileName}",
                artifact.url,
            )
        }
        assertEquals(209_035_935L, AiModelCatalog.downloadBytes(FormulaEngine.UniMerNetSmall))
    }

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
    fun theBundledOcrIsTheCompletePpOcrV6SmallPackage() {
        assertEquals(
            listOf(
                "pp-ocrv6_small_rec.onnx:21159378:5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634",
                "ppocrv6_dict.txt:74947:b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d",
                "pp-ocrv6_small_det.onnx:9880512:d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e",
            ),
            AiModelCatalog.BUNDLED_TEXT.map { "${it.fileName}:${it.bytes}:${it.sha256}" },
        )
        assertEquals(31_114_837L, AiModelCatalog.bundledTextBytes)
    }

    /** What the pane prints: decimal megabytes, as Android's short file sizes read. */
    @Test
    fun sizesReadAsAFileManagerWritesThem() {
        assertEquals("209 MB", formatModelBytes(AiModelCatalog.downloadBytes(FormulaEngine.UniMerNetSmall)))
        assertEquals("113 MB", formatModelBytes(AiModelCatalog.downloadBytes(FormulaEngine.UniMerNetTiny)))
        assertEquals("234 MB", formatModelBytes(AiModelCatalog.downloadBytes(FormulaEngine.FormulaNetS)))
        assertEquals("31.1 MB", formatModelBytes(AiModelCatalog.bundledTextBytes))
        assertEquals("1 kB", formatModelBytes(1_416))
        assertEquals("100 MB", formatModelBytes(99_960_000))
    }
}
