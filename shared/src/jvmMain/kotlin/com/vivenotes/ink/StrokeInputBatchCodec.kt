package com.vivenotes.ink

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * The inputs of one stroke as an `ink/androidx1` point blob stores them.
 *
 * [x], [y] and [elapsedSeconds] have one value per input; a channel the stroke did not record is
 * null. [strokeUnitLengthCm] is 0 when the relationship to physical distance is unknown. An empty
 * batch has no inputs to take a tool or unit length from, so it reports neither.
 */
class StrokeInputs(
    val toolType: ToolType,
    val strokeUnitLengthCm: Float,
    val x: FloatArray,
    val y: FloatArray,
    val elapsedSeconds: FloatArray,
    val pressure: FloatArray?,
    val tilt: FloatArray?,
    val orientation: FloatArray?,
    val noiseSeed: Int,
) {
    val size: Int get() = x.size

    /** `CodedStrokeInputBatch.ToolType`, in its wire order. */
    enum class ToolType { Unknown, Mouse, Touch, Stylus }
}

/**
 * Reads the point blobs Android writes with AndroidX Ink — a gzip-compressed
 * `ink.proto.CodedStrokeInputBatch` — without AndroidX Ink, which has no Windows build.
 *
 * What Android's `InkCodec.hasValidInputData` answers is whether `StrokeInputBatch.decode` accepts
 * the blob, so this applies the same rules as the library's native decoder (google/ink
 * `ink/storage/stroke_input_batch.cc`, `input_batch.cc`, `numeric_run.cc` and
 * `StrokeInputBatch::Append`): numeric runs of equal length with finite offsets and scales, then
 * every input finite, time never negative or running backwards, pressure in [0, 1], tilt in
 * [0, π/2], orientation in [0, 2π], and each optional channel present on all inputs or none. An
 * input repeating the previous one's position and time is dropped, as there.
 *
 * Pinned to AndroidX Ink 1.1.0-alpha06, the Android app's version. Its proto has no barrel twist,
 * so field 11 — which later google/ink versions give it — is an unknown field and skipped. Move this
 * with the Android app when it upgrades. `StrokeInputBatchCodecTest` holds these rules to verdicts
 * that version's native decoder gave.
 */
object StrokeInputBatchCodec {

    /** The decoded inputs of [blob]; throws [IllegalArgumentException] for anything Android refuses. */
    fun decode(blob: ByteArray): StrokeInputs = CodedStrokeInputBatch.parse(gunzip(blob)).decode()

    /**
     * Android decompresses into a buffer that doubles until the stream ends, so a bomb is refused
     * there by running out of memory. This refuses it by size instead, far above anything a stroke
     * reaches: every input costs a few bytes per channel, and a stored blob is at most 4 MiB.
     */
    internal const val MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024

    private fun gunzip(blob: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(blob)).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(output.size() + read <= MAX_DECOMPRESSED_BYTES) { "ink blob expands beyond its limit" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
}

/** `StrokeInput::kNoPressure`, `kNoTilt` and `kNoOrientation`. */
private const val NOT_REPORTED = -1f

/** `kQuarterTurn` and `kFullTurn`, as the library spells them. */
private const val QUARTER_TURN = 1.5707963268f
private const val FULL_TURN = 6.2831853072f

/** `ink.proto.CodedNumericRun`: `offset + scale * (running sum of deltas)` per value. */
private class CodedNumericRun {
    var deltas = IntArray(8)
    var count = 0
    var scale = 1f
    var offset = 0f

    fun add(delta: Int) {
        if (count == deltas.size) deltas = deltas.copyOf(deltas.size * 2)
        deltas[count++] = delta
    }

    /** `DecodeFloatNumericRun` and its iterator. */
    fun values(): FloatArray {
        require(offset.isFinite()) { "invalid float numeric run: non-finite offset" }
        require(scale.isFinite()) { "invalid float numeric run: non-finite scale" }
        var cumulative = 0L
        return FloatArray(count) { index ->
            cumulative += deltas[index]
            (offset.toDouble() + scale.toDouble() * cumulative.toDouble()).toFloat()
        }
    }

    companion object {
        /** Merges one occurrence into [into], as protobuf merges a repeated singular message. */
        fun parse(reader: ProtoReader, into: CodedNumericRun?): CodedNumericRun {
            val run = into ?: CodedNumericRun()
            while (!reader.atEnd) {
                val tag = reader.readTag()
                when {
                    tag.field == 1 && tag.wireType == WIRE_LEN -> {
                        val packed = reader.child(reader.readLength())
                        while (!packed.atEnd) run.add(zigZag(packed.readVarint().toInt()))
                    }
                    tag.field == 1 && tag.wireType == WIRE_VARINT -> run.add(zigZag(reader.readVarint().toInt()))
                    tag.field == 2 && tag.wireType == WIRE_I32 -> run.scale = Float.fromBits(reader.readFixed32())
                    tag.field == 3 && tag.wireType == WIRE_I32 -> run.offset = Float.fromBits(reader.readFixed32())
                    else -> reader.skip(tag)
                }
            }
            return run
        }

        private fun zigZag(value: Int): Int = (value ushr 1) xor -(value and 1)
    }
}

/** `ink.proto.CodedStrokeInputBatch`, as protobuf's parser leaves it. */
private class CodedStrokeInputBatch {
    var x: CodedNumericRun? = null
    var y: CodedNumericRun? = null
    var time: CodedNumericRun? = null
    var pressure: CodedNumericRun? = null
    var tilt: CodedNumericRun? = null
    var orientation: CodedNumericRun? = null
    var toolType = 0
    var strokeUnitLengthCm = 0f
    var noiseSeed = 0

    /** `DecodeStrokeInputBatchProto` followed by `DecodeStrokeInputBatch`'s appends. */
    fun decode(): StrokeInputs {
        val size = x?.count ?: 0
        require(
            (y?.count ?: 0) == size && (time?.count ?: 0) == size &&
                pressure.hasCount(size) && tilt.hasCount(size) && orientation.hasCount(size),
        ) { "invalid StrokeInputBatch: mismatched numeric run lengths" }
        val xs = (x ?: CodedNumericRun()).values()
        val ys = (y ?: CodedNumericRun()).values()
        // Duration32::Seconds turns NaN into infinity, which the finiteness check then refuses.
        val times = (time ?: CodedNumericRun()).values().also { values ->
            values.indices.forEach { if (values[it].isNaN()) values[it] = Float.POSITIVE_INFINITY }
        }
        val pressures = pressure?.values()
        val tilts = tilt?.values()
        val orientations = orientation?.values()

        val kept = ArrayList<Int>(size)
        for (i in 0 until size) {
            val last = kept.lastOrNull()
            if (last != null && xs[i] == xs[last] && ys[i] == ys[last] && times[i] == times[last]) continue
            validateInput(xs[i], ys[i], times[i], pressures?.get(i) ?: NOT_REPORTED, tilts?.get(i) ?: NOT_REPORTED,
                orientations?.get(i) ?: NOT_REPORTED)
            if (last != null) {
                require(times[last] <= times[i]) { "Inputs must have non-decreasing elapsed_time" }
                require(
                    reported(pressures, last) == reported(pressures, i) &&
                        reported(tilts, last) == reported(tilts, i) &&
                        reported(orientations, last) == reported(orientations, i),
                ) { "Either all or none of the inputs in a batch must report each optional channel" }
            }
            kept += i
        }

        fun FloatArray.keep() = FloatArray(kept.size) { this[kept[it]] }
        // A channel whose every value is the "not reported" marker is a batch without it.
        fun FloatArray?.channel() = this?.keep()?.takeIf { values -> values.any { it != NOT_REPORTED } }
        return StrokeInputs(
            toolType = if (kept.isEmpty()) StrokeInputs.ToolType.Unknown else StrokeInputs.ToolType.entries[toolType],
            strokeUnitLengthCm = if (kept.isEmpty()) 0f else strokeUnitLengthCm,
            x = xs.keep(),
            y = ys.keep(),
            elapsedSeconds = times.keep(),
            pressure = pressures.channel(),
            tilt = tilts.channel(),
            orientation = orientations.channel(),
            noiseSeed = noiseSeed,
        )
    }

    /** `ValidateSingleInput`. The tool type and stroke unit length are shared by every input. */
    private fun validateInput(x: Float, y: Float, t: Float, pressure: Float, tilt: Float, orientation: Float) {
        require(x.isFinite() && y.isFinite()) { "StrokeInput::position must be finite" }
        require(t.isFinite() && t >= 0f) { "StrokeInput::elapsed_time must be finite and non-negative" }
        require(strokeUnitLengthCm == 0f || (strokeUnitLengthCm.isFinite() && strokeUnitLengthCm > 0f)) {
            "If present, StrokeInput::stroke_unit_length must be finite and strictly positive"
        }
        require(pressure.isFinite() && (pressure == NOT_REPORTED || pressure in 0f..1f)) {
            "StrokeInput::pressure must be -1 or in the range [0, 1]"
        }
        require(tilt.isFinite() && (tilt == NOT_REPORTED || tilt in 0f..QUARTER_TURN)) {
            "StrokeInput::tilt must be -1 or in the range [0, pi / 2]"
        }
        require(orientation.isFinite() && (orientation == NOT_REPORTED || orientation in 0f..FULL_TURN)) {
            "StrokeInput::orientation must be -1 or in the range [0, 2 * pi]"
        }
    }

    companion object {
        fun parse(bytes: ByteArray): CodedStrokeInputBatch {
            val batch = CodedStrokeInputBatch()
            val reader = ProtoReader(bytes, 0, bytes.size)
            while (!reader.atEnd) {
                val tag = reader.readTag()
                when {
                    tag.wireType == WIRE_LEN && tag.field in 1..6 -> {
                        val child = reader.child(reader.readLength())
                        when (tag.field) {
                            1 -> batch.x = CodedNumericRun.parse(child, batch.x)
                            2 -> batch.y = CodedNumericRun.parse(child, batch.y)
                            3 -> batch.time = CodedNumericRun.parse(child, batch.time)
                            4 -> batch.pressure = CodedNumericRun.parse(child, batch.pressure)
                            5 -> batch.tilt = CodedNumericRun.parse(child, batch.tilt)
                            else -> batch.orientation = CodedNumericRun.parse(child, batch.orientation)
                        }
                    }
                    // A closed proto2 enum: a value it does not name is kept aside as an unknown
                    // field, and the field keeps what it had.
                    tag.field == 7 && tag.wireType == WIRE_VARINT ->
                        reader.readVarint().toInt().takeIf { it in 0..3 }?.let { batch.toolType = it }
                    tag.field == 8 && tag.wireType == WIRE_I32 ->
                        batch.strokeUnitLengthCm = Float.fromBits(reader.readFixed32())
                    tag.field == 9 && tag.wireType == WIRE_I32 -> batch.noiseSeed = reader.readFixed32()
                    else -> reader.skip(tag)
                }
            }
            return batch
        }
    }
}

private fun CodedNumericRun?.hasCount(size: Int): Boolean = this == null || count == size

private fun reported(channel: FloatArray?, index: Int): Boolean = channel != null && channel[index] != NOT_REPORTED

private const val WIRE_VARINT = 0
private const val WIRE_I64 = 1
private const val WIRE_LEN = 2
private const val WIRE_START_GROUP = 3
private const val WIRE_END_GROUP = 4
private const val WIRE_I32 = 5

private data class Tag(val field: Int, val wireType: Int)

/**
 * Protobuf's wire format, as strictly as the C++ runtime reads it: a truncated value, a varint
 * longer than ten bytes, field number 0, an unknown wire type and an unmatched group end are all
 * errors, and a field with a wire type other than its declared one is skipped as unknown.
 */
private class ProtoReader(private val bytes: ByteArray, private var position: Int, private val limit: Int) {
    val atEnd: Boolean get() = position >= limit

    /** The next field's tag. An end-group tag only ever closes a group, which [skip] reads itself. */
    fun readTag(): Tag = readAnyTag().also { require(it.wireType != WIRE_END_GROUP) { "unmatched end group" } }

    fun readVarint(): Long = readVarint(maxBytes = 10)

    fun readFixed32(): Int {
        require(limit - position >= 4) { "truncated fixed32" }
        var value = 0
        repeat(4) { value = value or (next() shl (8 * it)) }
        return value
    }

    /** A length prefix: at most five bytes and below 2 GiB, as protobuf's `ReadSize` insists. */
    fun readLength(): Int {
        val length = readVarint(maxBytes = 5)
        require(length in 0..(limit - position).toLong()) { "truncated length-delimited field" }
        return length.toInt()
    }

    /** A reader over the next [length] bytes, which this one then steps past. */
    fun child(length: Int): ProtoReader = ProtoReader(bytes, position, position + length).also { position += length }

    fun skip(tag: Tag, depth: Int = 0) {
        when (tag.wireType) {
            WIRE_VARINT -> readVarint()
            WIRE_I64 -> {
                require(limit - position >= 8) { "truncated fixed64" }
                position += 8
            }
            WIRE_LEN -> {
                // Read first: `position += readLength()` would add to the position before the prefix.
                val length = readLength()
                position += length
            }
            WIRE_I32 -> readFixed32()
            WIRE_START_GROUP -> {
                require(depth < MAX_GROUP_DEPTH) { "groups nested too deeply" }
                while (true) {
                    val inner = readAnyTag()
                    if (inner.wireType == WIRE_END_GROUP) {
                        require(inner.field == tag.field) { "mismatched end group" }
                        return
                    }
                    skip(inner, depth + 1)
                }
            }
        }
    }

    /** Tags are 32-bit varints of at most five bytes. */
    private fun readAnyTag(): Tag {
        val tag = readVarint(maxBytes = 5).toInt()
        val field = tag ushr 3
        val wireType = tag and 7
        require(field != 0) { "field number 0" }
        require(wireType <= WIRE_I32) { "unknown wire type $wireType" }
        return Tag(field, wireType)
    }

    private fun readVarint(maxBytes: Int): Long {
        var value = 0L
        for (index in 0 until maxBytes) {
            val byte = next()
            value = value or ((byte and 0x7F).toLong() shl (7 * index))
            if (byte and 0x80 == 0) return value
        }
        throw IllegalArgumentException("malformed varint")
    }

    private fun next(): Int {
        require(position < limit) { "truncated message" }
        return bytes[position++].toInt() and 0xFF
    }

    companion object {
        private const val MAX_GROUP_DEPTH = 100
    }
}
