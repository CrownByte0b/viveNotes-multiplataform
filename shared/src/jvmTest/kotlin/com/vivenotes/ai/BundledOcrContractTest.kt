package com.vivenotes.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.FloatBuffer
import java.security.MessageDigest

class BundledOcrContractTest {
    @Test
    fun smallRecognizerAlphabetMatchesTheCompleteMultilingualDictionary() {
        val characters = resource("/ai/${AiModelCatalog.TEXT_DICTIONARY.fileName}")
            .decodeToString().lineSequence().filter { it.isNotEmpty() }.toList()
        assertEquals(18_708, characters.size)
        assertEquals(characters.size, characters.toSet().size)
        assertTrue(characters.containsAll(listOf("é", "ñ", "你", "好", "🛅")))

        val env = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            env.createSession(resource("/ai/${AiModelCatalog.TEXT_MODEL.fileName}"), options).use { session ->
                val input = session.inputInfo.values.single().info as TensorInfo
                val output = session.outputInfo.values.single().info as TensorInfo
                assertEquals(3L, input.shape[1])
                assertEquals(48L, input.shape[2])
                assertEquals((characters.size + 2).toLong(), output.shape[2])
            }
            env.createSession(resource("/ai/${AiModelCatalog.DETECTION_MODEL.fileName}"), options).use { session ->
                val output = session.outputInfo.values.single().info as TensorInfo
                assertEquals(4, output.shape.size)
                assertEquals(1L, output.shape[1])
            }
        }
    }

    @Test
    fun androidBaselineAssetsKeepTheirOriginalPinsAsCompatibilityFixtures() {
        val expected = listOf(
            Triple("en_pp-ocrv5_mobile_rec.onnx", 7_876_014, "8307465d3c9ef2ba4055c3bd0be55aafe11f518630212b7598b70ccb376028ac"),
            Triple("ppocrv5_en_dict.txt", 1_416, "e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6"),
            Triple("pp-ocrv5_mobile_det.onnx", 4_826_518, "1eb7b4f7ab657ebd1c66d5f79bca7497f29768a2e3c15e52daecbba1a8e4a039"),
        )
        expected.forEach { (name, size, checksum) ->
            val bytes = resource("/ai/android-ocr/$name")
            assertEquals(name, size, bytes.size)
            assertEquals(name, checksum, MessageDigest.getInstance("SHA-256").digest(bytes).hex())
        }
    }

    @Test
    fun androidRecognizerStillReadsUsingTheSharedPreprocessingAndDecoder() {
        val characters = buildList {
            add("")
            addAll(resource("/ai/android-ocr/ppocrv5_en_dict.txt").decodeToString().lineSequence().filter { it.isNotEmpty() }.toList())
            add(" ")
        }
        val env = OrtEnvironment.getEnvironment()
        textImage(320, 64, 44f, listOf(Triple("hello", 16f, 48f))).use { image ->
            val input = preprocessText(image)
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(4)
                env.createSession(resource("/ai/android-ocr/en_pp-ocrv5_mobile_rec.onnx"), options).use { session ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(input.values), longArrayOf(1, 3, 48, input.width.toLong())).use { tensor ->
                        session.run(mapOf(session.inputNames.single() to tensor)).use { result ->
                            @Suppress("UNCHECKED_CAST")
                            val logits = (result[0].value as Array<Array<FloatArray>>)[0]
                            val reading = decodeCtc(logits, characters)
                            val flat = (result[0] as OnnxTensor).floatBuffer
                            assertEquals(reading, decodeCtc(flat, logits.size, characters.size, characters))
                            assertTrue(reading.text, reading.text.contains("hello", ignoreCase = true))
                            assertTrue(reading.confidence > 0.5f)
                        }
                    }
                }
            }
        }
    }

    private fun resource(path: String): ByteArray =
        javaClass.getResourceAsStream(path)?.use { it.readBytes() } ?: error("Missing resource $path")
}
