package com.vivenotes.performance

import com.vivenotes.ai.FormulaTokenizerDecoder
import com.vivenotes.model.BlockType
import com.vivenotes.richtext.pastedHtmlBlocks
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

internal fun measureComputePerformance(runner: BaselineMeasurements) {
    for (words in listOf(250, 1_000, 4_000)) {
        val fixture = performanceHtmlParagraph(words)
        val parsed = checkNotNull(pastedHtmlBlocks(fixture.html))
        check(parsed.single().type == BlockType.Paragraph)
        check(parsed.single().runs.single().text == fixture.text)
        runner.measure("richtext.paste_plain_html", words, fingerprint(fixture.html)) {
            pastedHtmlBlocks(fixture.html)
        }
    }
    for (vocabularySize in listOf(1_000, 10_000, 50_000)) {
        val fixture = performanceFormulaTokenizer(vocabularySize)
        withPerformanceTokenizerFile(fixture) { tokenizer ->
            check(FormulaTokenizerDecoder.decode(tokenizer, fixture.tokenIds) == fixture.expected)
            runner.measure("ai.decode_formula_tokenizer", vocabularySize, fingerprint(fixture.json)) {
                FormulaTokenizerDecoder.decode(tokenizer, fixture.tokenIds)
            }
        }
    }
}

internal data class HtmlParagraphPerformanceFixture(val text: String, val html: String)

internal fun performanceHtmlParagraph(words: Int): HtmlParagraphPerformanceFixture {
    require(words > 0)
    val text = "word ".repeat(words).trimEnd()
    return HtmlParagraphPerformanceFixture(text, "<p>$text</p>")
}

internal data class FormulaTokenizerPerformanceFixture(
    val vocabularySize: Int,
    val json: String,
    val tokenIds: List<Long> = listOf(0, 5, 6, 7, 8, 9, 1, 2, 8),
    val expected: String = "x ^ 2 = Café",
)

/** A full BPE-shaped payload, including unused merge metadata parsed by the current decoder. */
internal fun performanceFormulaTokenizer(vocabularySize: Int): FormulaTokenizerPerformanceFixture {
    require(vocabularySize in 10..50_000)
    fun token(id: Int): String = when (id) {
        0 -> "<s>"
        1 -> "<pad>"
        2 -> "</s>"
        3 -> "<unk>"
        4 -> "Ġ"
        5 -> "x"
        6 -> "Ġ^"
        7 -> "Ġ2"
        8 -> "Ġ="
        9 -> "ĠCafÃ©"
        else -> "Ġsymbol${id.toString().padStart(5, '0')}"
    }
    val root = buildJsonObject {
        put("version", "1.0")
        put("added_tokens", JsonArray((0..2).map { id ->
            buildJsonObject {
                put("id", id)
                put("content", token(id))
                put("single_word", false)
                put("lstrip", false)
                put("rstrip", false)
                put("normalized", false)
                put("special", true)
            }
        }))
        put("pre_tokenizer", buildJsonObject {
            put("type", "ByteLevel")
            put("add_prefix_space", false)
            put("trim_offsets", true)
            put("use_regex", true)
        })
        put("decoder", buildJsonObject {
            put("type", "ByteLevel")
            put("add_prefix_space", false)
            put("trim_offsets", true)
            put("use_regex", true)
        })
        put("model", buildJsonObject {
            put("type", "BPE")
            put("unk_token", "<unk>")
            put("fuse_unk", false)
            put("byte_fallback", false)
            put("vocab", buildJsonObject {
                repeat(vocabularySize) { id -> put(token(id), id) }
            })
            put("merges", JsonArray((10 until vocabularySize - 1).map { id ->
                JsonPrimitive("${token(id)} ${token(id + 1)}")
            }))
        })
    }
    return FormulaTokenizerPerformanceFixture(vocabularySize, Json.encodeToString(JsonObject.serializer(), root))
}

internal fun <T> withPerformanceTokenizerFile(
    fixture: FormulaTokenizerPerformanceFixture,
    action: (File) -> T,
): T {
    val parent = File(System.getProperty("vivenotes.performance.scratch", "build/performance-tmp"))
    check(parent.isDirectory || parent.mkdirs()) { "Could not create benchmark scratch directory" }
    val directory = Files.createTempDirectory(parent.toPath(), "tokenizer-").toFile()
    try {
        val file = File(directory, "tokenizer.json")
        file.writeText(fixture.json, StandardCharsets.UTF_8)
        return action(file)
    } finally {
        check(directory.deleteRecursively()) { "Could not remove benchmark tokenizer" }
    }
}
