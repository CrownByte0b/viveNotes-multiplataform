package com.vivenotes.model.ocr

/**
 * Where one recognized line sits on its picture.
 *
 * Everything is a fraction of the picture rather than a pixel, so it stays right whichever size the
 * picture is decoded at and however its frame has been stretched on the page.
 */
class ImageLineLayout(
    /**
     * x then y for each of the four corners. The first two corners run along the line's top in
     * reading order and the last two come back along its bottom, so `[0]→[1]` is the text's direction.
     */
    val corners: FloatArray,
    /** Character boundaries along the line, one more than there are characters. See `CtcReading.edges`. */
    val edges: FloatArray,
)

