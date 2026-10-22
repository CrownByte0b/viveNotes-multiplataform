package com.vivenotes.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OcrOutputCopyTest {
    @Test
    fun completeOwnedHeapBuffersReuseTheirExistingCopyIncludingSpecialFloatBits() {
        val values = floatArrayOf(-0f, 0f, Float.fromBits(0x7fc01234), Float.POSITIVE_INFINITY)
        val buffer = FloatBuffer.wrap(values)
        val result = ownedFloatArray(buffer, values.size)
        assertSame(values, result)
        assertEquals(values.map { it.toRawBits() }, result.map { it.toRawBits() })
        assertEquals(0, buffer.position())
        assertSame(values, ownedFloatArray(buffer.slice(), values.size))
    }

    @Test
    fun slicesPartialLimitsReadOnlyAndDirectBuffersCopyExactlyWithoutChangingPositions() {
        val values = floatArrayOf(91f, -0f, Float.fromBits(0x7fc01234), 4f, 5f, 92f)
        val bounded = FloatBuffer.wrap(values).apply { position(1); limit(5) }
        val buffers = listOf(bounded, bounded.slice(), bounded.asReadOnlyBuffer(),
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .apply { put(values); flip(); position(1); limit(5) })
        for (buffer in buffers) {
            val position = buffer.position()
            val limit = buffer.limit()
            val result = ownedFloatArray(buffer, 4)
            assertNotSame(values, result)
            assertEquals(values.slice(1..4).map { it.toRawBits() }, result.map { it.toRawBits() })
            assertEquals(position, buffer.position())
            assertEquals(limit, buffer.limit())
        }
        val prefix = ownedFloatArray(FloatBuffer.wrap(values), 2)
        assertEquals(values.take(2).map { it.toRawBits() }, prefix.map { it.toRawBits() })
        assertNotSame(values, prefix)
        val restricted = FloatBuffer.wrap(values).apply { limit(4) }
        assertNotSame(values, ownedFloatArray(restricted, 4))
    }

    @Test
    fun emptyAndTruncatedSlicesPreserveTheOriginalReadContract() {
        val empty = FloatArray(0)
        assertSame(empty, ownedFloatArray(FloatBuffer.wrap(empty), 0))
        val truncated = FloatBuffer.wrap(floatArrayOf(1f, 2f)).apply { position(1) }
        assertFailsWith<BufferUnderflowException> { ownedFloatArray(truncated, 2) }
        assertEquals(1, truncated.position())
    }

    @Test
    fun actualDetectorOwnedCopyStaysIndependentOfNativeOutputAndOutlivesResultAndSession() {
        val environment = OrtEnvironment.getEnvironment()
        val model = javaClass.getResourceAsStream("/ai/${AiModelCatalog.DETECTION_MODEL.fileName}")!!
            .use { it.readBytes() }
        var retained: FloatArray? = null
        var expected: FloatArray? = null
        textImage(256, 128, 40f, listOf(Triple("Search", 16f, 70f))).use { image ->
            val input = preprocessDetection(image, channelOrder = DetectionChannelOrder.Bgr)
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                environment.createSession(model, options).use { session ->
                    OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.values),
                        longArrayOf(1, 3, input.height.toLong(), input.width.toLong())).use { tensor ->
                        session.run(mapOf(session.inputNames.single() to tensor)).use { output ->
                            val native = output[0] as OnnxTensor
                            assertTrue(output.isResultOwner(0))
                            assertFalse(native.ownsBuffer())
                            val copied = native.floatBuffer
                            assertTrue(copied.hasArray(), "Pinned ORT1.31 returns a heap defensive copy")
                            retained = ownedFloatArray(copied, input.width * input.height)
                            assertSame(copied.array(), retained)
                            expected = retained!!.copyOf()
                            val first = expected!![0]
                            retained!![0] = -100f
                            assertEquals(first.toRawBits(), native.floatBuffer.get(0).toRawBits(),
                                "Java-owned probability mutation cannot alter native output")
                            retained!![0] = first
                        }
                        session.run(mapOf(session.inputNames.single() to tensor)).use { output ->
                            assertFalse((output[0] as OnnxTensor).isClosed)
                        }
                    }
                }
            }
        }
        assertEquals(expected!!.map { it.toRawBits() }, retained!!.map { it.toRawBits() })
        assertTrue(retained!!.any { it > .2f }, "The fixture exercises actual detector probabilities")
    }

    @Test
    fun reducedChoiceCopiesOutliveTheirOutputSessionAndRemainIndependentAcrossRuns() {
        val environment = OrtEnvironment.getEnvironment()
        var retainedIds: LongBuffer? = null
        var retainedScores: FloatBuffer? = null
        val first = floatArrayOf(.1f, .9f, 0f, .1f, .2f, .8f)
        val second = floatArrayOf(.9f, .1f, 0f, .8f, .2f, .1f)
        NativeCtcReduction.create(environment, 1).use { reduction ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(first), longArrayOf(1, 2, 3)).use { logits ->
                reduction.withChoices(logits) { ids, scores ->
                    assertTrue(ids.hasArray())
                    assertTrue(scores.hasArray())
                    retainedIds = ids
                    retainedScores = scores
                }
            }
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(second), longArrayOf(1, 2, 3)).use { logits ->
                reduction.withChoices(logits) { ids, scores ->
                    assertNotSame(retainedIds!!.array(), ids.array())
                    assertNotSame(retainedScores!!.array(), scores.array())
                    assertEquals(listOf(0L, 0L), List(ids.remaining()) { ids.get(it) })
                }
            }
        }
        assertEquals(listOf(1L, 2L), List(retainedIds!!.remaining()) { retainedIds!!.get(it) })
        assertEquals(listOf(.9f.toRawBits(), .8f.toRawBits()),
            List(retainedScores!!.remaining()) { retainedScores!!.get(it).toRawBits() })
        retainedIds!!.put(0, 2L)
        retainedScores!!.put(0, .25f)
        assertEquals(2L, retainedIds!!.get(0))
        assertEquals(.25f.toRawBits(), retainedScores!!.get(0).toRawBits())
    }
}
