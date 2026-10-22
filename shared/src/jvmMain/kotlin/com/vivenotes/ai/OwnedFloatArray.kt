package com.vivenotes.ai

import java.nio.FloatBuffer

/** Takes the complete heap array from an owned buffer, or copies its requested slice. */
internal fun ownedFloatArray(buffer: FloatBuffer, count: Int): FloatArray {
    if (buffer.hasArray() && buffer.position() == 0 && buffer.arrayOffset() == 0 &&
        buffer.remaining() == count && buffer.array().size == count) return buffer.array()
    return FloatArray(count).also { buffer.asReadOnlyBuffer().get(it) }
}
