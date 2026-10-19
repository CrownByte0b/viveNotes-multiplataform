package com.vivenotes.ai

import com.vivenotes.localization.Strings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Decodes the token ids one formula model emitted: Android's `ai/FormulaTokenizerDecoder.kt`.
 *
 * Android streams the 2 MB tokenizer with `android.util.JsonReader` so its 50k-token vocabulary is
 * never held. The JVM has no streaming reader in its standard library, so this parses the file once
 * per formula and keeps only the tokens that formula used — tens of milliseconds and a transient few
 * megabytes, against inference that costs more of both.
 */
internal object FormulaTokenizerDecoder {
    fun decode(tokenizer: File, tokenIds: List<Long>): String {
        val ids = tokenIds
            .takeWhile { it != EOS_TOKEN_ID && it in 0 until VOCAB_SIZE }
            .filter { it != BOS_TOKEN_ID && it != PAD_TOKEN_ID }
            .map(Long::toInt)
        if (ids.isEmpty()) return ""

        val wanted = ids.toSet()
        val root = Json.parseToJsonElement(tokenizer.readText(StandardCharsets.UTF_8)).jsonObject
        val specialIds = root["added_tokens"]?.jsonArray.orEmpty().mapNotNullTo(mutableSetOf()) { entry ->
            val token = entry.jsonObject
            token["id"]?.jsonPrimitive?.intOrNull?.takeIf { token["special"]?.jsonPrimitive?.booleanOrNull == true }
        }
        val tokens = mutableMapOf<Int, String>()
        root["model"]?.jsonObject?.get("vocab")?.jsonObject.orEmpty().forEach { (token, id) ->
            val value = id.jsonPrimitive.intOrNull
            if (value != null && value in wanted) tokens[value] = token
        }

        require(ids.all { it in specialIds || it in tokens }) { Strings.formula_tokenizer_does_not_match_model }
        val encoded = buildString {
            ids.forEach { id -> if (id !in specialIds) append(tokens.getValue(id)) }
        }
        return decodeByteLevel(encoded).trim()
    }

    /** Inverse of the GPT-2/Hugging Face byte-to-visible-Unicode mapping used by ByteLevel BPE. */
    private fun decodeByteLevel(encoded: String): String {
        val output = ByteArrayOutputStream(encoded.length)
        encoded.codePoints().forEach { codePoint ->
            val byte = BYTE_DECODER[codePoint]
                ?: error(Strings.unsupported_bytelevel_code_point_u_value(codePoint.toString(16)))
            output.write(byte)
        }
        return output.toByteArray().toString(StandardCharsets.UTF_8)
    }

    private val BYTE_DECODER: Map<Int, Int> = buildMap {
        val visibleBytes = buildList {
            addAll(33..126)
            addAll(161..172)
            addAll(174..255)
        }
        val visibleSet = visibleBytes.toSet()
        visibleBytes.forEach { byte -> put(byte, byte) }
        var extra = 0
        for (byte in 0..255) {
            if (byte !in visibleSet) {
                put(256 + extra, byte)
                extra++
            }
        }
    }

    private const val BOS_TOKEN_ID = 0L
    private const val PAD_TOKEN_ID = 1L
    private const val EOS_TOKEN_ID = 2L
    private const val VOCAB_SIZE = 50_000L
}
