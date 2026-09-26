package com.vivenotes.ui.canvas

import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import kotlin.test.Test
import kotlin.test.assertEquals

class RichTextRenderingTest {

    @Test
    fun numberingRestartsWhenARunOfNumberedParagraphsIsInterrupted() {
        val blocks = listOf(
            BlockType.Numbered, BlockType.Numbered, BlockType.Paragraph,
            BlockType.Numbered, BlockType.Bullet, BlockType.Numbered, BlockType.Numbered,
        ).map { Block.of("x", it) }
        assertEquals(listOf(1, 2, null, 1, null, 1, 2), listOrdinals(blocks))
    }

    @Test
    fun numberingCountsAcrossIndentsAsAndroidDoes() {
        val blocks = listOf(0, 1, 1, 0).map { Block.of("x", BlockType.Numbered, indent = it) }
        assertEquals(listOf(1, 2, 3, 4), listOrdinals(blocks))
    }

    @Test
    fun paragraphsReserveTheirIndentAndRoomForAMarker() {
        assertEquals(0f, Block.of("x").leadingSp())
        assertEquals(2 * INDENT_STEP_SP, Block.of("x", indent = 2).leadingSp())
        listOf(BlockType.Bullet, BlockType.Numbered, BlockType.Todo).forEach {
            assertEquals(INDENT_STEP_SP + LIST_GAP_SP, Block.of("x", it, indent = 1).leadingSp(), "$it")
        }
        assertEquals(QUOTE_GAP_SP, Block.of("x", BlockType.Quote).leadingSp())
        assertEquals(INDENT_STEP_SP, Block.of("x", BlockType.Code, indent = 1).leadingSp())
    }
}
