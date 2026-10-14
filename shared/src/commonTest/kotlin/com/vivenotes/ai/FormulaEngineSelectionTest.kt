package com.vivenotes.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which formula model recognition runs, and when one is fetched unasked. Android's
 * `FormulaEngineSelectionTest`, extended for the desktop Small model.
 *
 * The rules that decide what a person upgrading, deleting or downloading ends up with. Each case is
 * one of those people.
 */
class FormulaEngineSelectionTest {
    private val uniMerNet = FormulaEngine.UniMerNetSmall
    private val formulaNet = FormulaEngine.FormulaNetS

    @Test
    fun aFreshInstallStartsOnTheDefaultAndFetchesIt() {
        assertEquals(uniMerNet, formulaEngineInUse(chosen = null, installed = emptySet()))
        assertEquals(uniMerNet, formulaEngineToFetch(installed = emptySet(), deleted = emptySet()))
    }

    /**
     * Someone who had FormulaNet-S before UniMERNet-T existed reads with it only until the default
     * arrives, then moves to it. FormulaNet-S stays installed, as the optional model.
     */
    @Test
    fun anUpgradeWithFormulaNetFetchesTheDefaultAndMovesToIt() {
        assertEquals(formulaNet, formulaEngineInUse(chosen = null, installed = setOf(formulaNet)))
        assertEquals(uniMerNet, formulaEngineToFetch(installed = setOf(formulaNet), deleted = emptySet()))
        assertEquals(uniMerNet, formulaEngineInUse(chosen = null, installed = setOf(uniMerNet, formulaNet)))
    }

    @Test
    fun anInstalledDefaultIsNotFetchedAgain() {
        assertNull(formulaEngineToFetch(installed = setOf(uniMerNet), deleted = emptySet()))
    }

    @Test
    fun aChoiceWinsOnlyWhileItsModelIsInstalled() {
        val both = setOf(uniMerNet, formulaNet)
        assertEquals(formulaNet, formulaEngineInUse(chosen = formulaNet, installed = both))
        assertEquals(uniMerNet, formulaEngineInUse(chosen = formulaNet, installed = setOf(uniMerNet)))
    }

    /** Deleting the default is a no, and the next launch must not undo it. */
    @Test
    fun theDefaultIsNotFetchedAfterTheUserDeletesIt() {
        assertNull(formulaEngineToFetch(installed = emptySet(), deleted = setOf(uniMerNet)))
        assertNull(formulaEngineToFetch(installed = setOf(formulaNet), deleted = setOf(uniMerNet)))
    }

    /** FormulaNet-S is the optional model, so deleting it says nothing about the default. */
    @Test
    fun deletingFormulaNetDoesNotStopTheDefault() {
        assertEquals(uniMerNet, formulaEngineToFetch(installed = emptySet(), deleted = setOf(formulaNet)))
    }

    @Test
    fun deletingTheModelInUseHandsMathToTheOtherOne() {
        assertEquals(
            formulaNet,
            formulaEngineAfterDelete(selected = uniMerNet, deleted = uniMerNet, stillInstalled = setOf(formulaNet)),
        )
        assertEquals(
            uniMerNet,
            formulaEngineAfterDelete(selected = uniMerNet, deleted = formulaNet, stillInstalled = setOf(uniMerNet)),
        )
        // With nothing left, the selection stays and nothing is ready.
        assertEquals(
            uniMerNet,
            formulaEngineAfterDelete(selected = uniMerNet, deleted = uniMerNet, stillInstalled = emptySet()),
        )
    }

    /** A model that finishes installing is in use unless the user picked the other one. */
    @Test
    fun aFinishedDownloadTakesOverUnlessTheUserPickedTheOther() {
        assertEquals(formulaNet, formulaEngineInUse(chosen = null, installed = setOf(formulaNet)))
        assertEquals(formulaNet, formulaEngineInUse(chosen = formulaNet, installed = setOf(uniMerNet, formulaNet)))
        assertEquals(uniMerNet, formulaEngineInUse(chosen = uniMerNet, installed = setOf(uniMerNet, formulaNet)))
    }

    @Test
    fun theMathButtonFollowsTheSelectedModelsInstall() {
        val state = AiModelsState(
            formulaLatex = AiModelInstallState.Installed,
            uniMerNet = AiModelInstallState.NotInstalled,
            uniMerNetSmall = AiModelInstallState.NotInstalled,
            formulaEngine = uniMerNet,
        )
        assertEquals(false, state.formulaReady)
        assertEquals(true, state.copy(formulaEngine = formulaNet).formulaReady)
        assertEquals(setOf(formulaNet), state.installedFormulaEngines)
        val ready = state.withFormula(uniMerNet, AiModelInstallState.Installed)
        assertEquals(true, ready.formulaReady)
        assertEquals(setOf(uniMerNet, formulaNet), ready.installedFormulaEngines)
    }

    @Test
    fun smallTakesOverAnImplicitTinyChoiceButPreservesExplicitChoices() {
        val tiny = FormulaEngine.UniMerNetTiny
        assertEquals(tiny, formulaEngineInUse(chosen = null, installed = setOf(tiny)))
        val installed = setOf(tiny, uniMerNet, formulaNet)
        assertEquals(uniMerNet, formulaEngineInUse(chosen = null, installed = installed))
        assertEquals(tiny, formulaEngineInUse(chosen = tiny, installed = installed))
        assertEquals(formulaNet, formulaEngineInUse(chosen = formulaNet, installed = installed))
        assertEquals(uniMerNet, formulaEngineInUse(chosen = uniMerNet, installed = installed))
    }

    @Test
    fun deletingSmallFallsBackToTinyBeforeFormulaNet() {
        val tiny = FormulaEngine.UniMerNetTiny
        assertEquals(tiny, formulaEngineAfterDelete(uniMerNet, uniMerNet, setOf(tiny, formulaNet)))
        assertEquals(formulaNet, formulaEngineAfterDelete(uniMerNet, uniMerNet, setOf(formulaNet)))
        assertEquals(tiny, formulaEngineInUse(chosen = uniMerNet, installed = setOf(tiny, formulaNet)))
    }

    @Test
    fun tinyStillUsesTheOriginalFallbackAndDeleteRulesWhenSmallIsAbsent() {
        val tiny = FormulaEngine.UniMerNetTiny
        assertEquals(tiny, formulaEngineInUse(chosen = null, installed = setOf(tiny, formulaNet)))
        assertEquals(formulaNet, formulaEngineAfterDelete(tiny, tiny, setOf(formulaNet)))
        assertEquals(uniMerNet, formulaEngineToFetch(installed = setOf(tiny), deleted = setOf(formulaNet)))
        assertEquals(null, formulaEngineToFetch(installed = setOf(tiny), deleted = setOf(uniMerNet)))
    }
}
