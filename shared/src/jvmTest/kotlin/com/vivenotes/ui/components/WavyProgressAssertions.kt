package com.vivenotes.ui.components

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.unit.dp

/**
 * Material pads every linear indicator's semantics 10 dp above and below, so a flat bar reports
 * 24 dp and the wavy one its 10 dp container plus that padding.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun SemanticsNodeInteraction.assertIsIndeterminateWavyBar(): SemanticsNodeInteraction =
    assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
        .assertHeightIsEqualTo(WavyProgressIndicatorDefaults.LinearContainerHeight + 20.dp)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun SemanticsNodeInteraction.assertIsDeterminateWavyBar(progress: Float): SemanticsNodeInteraction =
    assertRangeInfoEquals(ProgressBarRangeInfo(progress, 0f..1f))
        .assertHeightIsEqualTo(WavyProgressIndicatorDefaults.LinearContainerHeight + 20.dp)
