package com.vivenotes.localization

/** Localizes the fixed vocabulary from the unchanged Android-compatible Python math protocol. */
internal object MathText {
    private val vocabulary = defaultStrings.filterKeys { it.startsWith("math_engine_") }
    private val exact = vocabulary.entries.associate { (key, value) -> value to key }
    private val placeholder = Regex("""%(\d+)\$[sd]""")
    private data class Template(val key: String, val pattern: Regex, val positions: List<Int>)
    private val templates = vocabulary.mapNotNull { (key, value) ->
        val matches = placeholder.findAll(value).toList()
        if (matches.isEmpty()) null else {
            var start = 0
            val pattern = buildString {
                for (match in matches) {
                    append(Regex.escape(value.substring(start, match.range.first)))
                    append("([\\s\\S]*?)")
                    start = match.range.last + 1
                }
                append(Regex.escape(value.substring(start)))
            }
            Template(key, Regex(pattern), matches.map { it.groupValues[1].toInt() })
        }
    }

    fun translate(text: String): String {
        exact[text]?.let { return Localization.text(it) }
        for (template in templates) {
            val match = template.pattern.matchEntire(text) ?: continue
            val arguments = arrayOfNulls<String>(template.positions.max())
            for ((index, position) in template.positions.withIndex()) arguments[position - 1] = match.groupValues[index + 1]
            return Localization.text(template.key, *arguments)
        }
        return text
    }

    /** The engine returns convergence verdicts as a plain text LaTeX expression. */
    fun translateVerdict(latex: String): String {
        if (!latex.startsWith("\\text{") || !latex.endsWith('}')) return latex
        val original = latex.removePrefix("\\text{").dropLast(1)
        val translated = translate(original)
        if (translated == original) return latex
        val escaped = buildString {
            translated.forEach { character ->
                append(when (character) {
                    '\\' -> "\\textbackslash{}"
                    '{', '}', '_', '&', '%', '#', '$' -> "\\$character"
                    else -> character.toString()
                })
            }
        }
        return "\\text{$escaped}"
    }
}
