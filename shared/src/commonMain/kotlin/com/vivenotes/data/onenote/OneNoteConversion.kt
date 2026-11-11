package com.vivenotes.data.onenote

/** What a OneNote file came to: counted from the `.vive` the converter wrote. */
data class OneNoteConversion(
    val sections: Int,
    val pages: Int,
    val strokes: Int,
    val attachments: Int,
    /** Each thing that was left out, as the converter words it. */
    val warnings: List<String>,
)
