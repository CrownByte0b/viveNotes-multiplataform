package com.vivenotes.ai

/**
 * Which graph turns a formula bitmap into LaTeX. Listed best first, which is the order the Models
 * pane shows them in and the order [formulaEngineInUse] falls back through. Android's
 * `ai/FormulaEngine.kt`.
 *
 * `simulations/formula-models` in the Android repository measured both on its Recognition Test page.
 * UniMERNet-T read ordinary handwriting exactly more often, 66 of 88 rasters against 54. It never
 * read the handwritten matrix, which FormulaNet-S read 9 times in 11. Both stay installable, and the
 * user picks.
 *
 * [stemPx] is the stroke width each model was measured best at, stated in the 384-square frame
 * [recognitionStemSize] uses for every width. That frame is only the yardstick — UniMERNet's own
 * input is 192×672 — so the two numbers compare directly. FormulaNet-S wants 10 px; UniMERNet-T was
 * exact on 66/88 at 3–4 px, 65 at 6 and 55 at 10, so drawing for one model starves the other.
 */
enum class FormulaEngine(val stemPx: Float) {
    UniMerNetTiny(UNIMERNET_STEM_PX),
    FormulaNetS(RECOGNITION_STEM_PX),
}

/** The name a person reads for a formula model, in the Models pane and in log lines. */
val FormulaEngine.displayName: String
    get() = when (this) {
        FormulaEngine.UniMerNetTiny -> "UniMERNet-T"
        FormulaEngine.FormulaNetS -> "PP-FormulaNet-S"
    }

/** What each is measurably better at — `simulations/formula-models` — as Android's pane says it. */
val FormulaEngine.strength: String
    get() = when (this) {
        FormulaEngine.UniMerNetTiny -> "Best on algebra"
        FormulaEngine.FormulaNetS -> "Reads matrices"
    }

/** UniMERNet-T's measured best stem; see [FormulaEngine]. */
internal const val UNIMERNET_STEM_PX = 4f

/** The model everyone ends up on unless they pick another, and the one a launch fetches unasked. */
internal val DEFAULT_FORMULA_ENGINE = FormulaEngine.UniMerNetTiny

/**
 * The model formula recognition runs, at launch and again after every install.
 *
 * That is the user's own choice while its model is installed. A choice is made only by pressing
 * Use, never by a default. Otherwise it is the best installed model, and otherwise the default. So
 * someone with only FormulaNet-S keeps reading with it until UniMERNet-T arrives, then moves to it.
 * Someone who picked FormulaNet-S stays on it.
 */
internal fun formulaEngineInUse(
    chosen: FormulaEngine?,
    installed: Set<FormulaEngine>,
): FormulaEngine =
    chosen?.takeIf { it in installed }
        ?: FormulaEngine.entries.firstOrNull { it in installed }
        ?: DEFAULT_FORMULA_ENGINE

/**
 * What a launch fetches without being asked, where launches fetch at all: the default, whenever it
 * is not installed and the user has never deleted it.
 *
 * FormulaNet-S being installed does not stop it. FormulaNet-S is the optional model, and someone
 * who had it from before UniMERNet-T existed belongs on the default like everyone else. Only
 * deleting the default itself is a no — deleting FormulaNet-S says nothing about UniMERNet-T — and
 * fetching it again on the next launch would undo that no.
 */
internal fun formulaEngineToFetch(
    installed: Set<FormulaEngine>,
    deleted: Set<FormulaEngine>,
): FormulaEngine? = DEFAULT_FORMULA_ENGINE.takeIf { it !in installed && it !in deleted }

/**
 * Deleting the model in use hands formula recognition to the best one still installed. With none
 * left, the selection stays where it is and nothing is ready.
 */
internal fun formulaEngineAfterDelete(
    selected: FormulaEngine,
    deleted: FormulaEngine,
    stillInstalled: Set<FormulaEngine>,
): FormulaEngine =
    if (selected != deleted) selected
    else FormulaEngine.entries.firstOrNull { it in stillInstalled } ?: selected

/**
 * How wide to ink a stroke so the stem lands at [target] pixels in the model's own square.
 *
 * Thickness has to be stated in the frame the model sees, not in page units. FormulaNet-S's
 * preprocessing scales every crop to fit 384, so one width in page units becomes a different width
 * in the tensor for every selection: a formula written large is squeezed to a hairline and a small
 * one comes out fat. Measured on three formulas (`simulations/formula-render`), that one confusion is
 * worth more than everything else in the pipeline put together — mean token accuracy 0.615 at the
 * stored 2 dp, 0.898 once the stem is pinned here.
 *
 * Solving `stem * 384 / (longest + stem) = target` for the width to draw with:
 *
 *     stem = target * longest / (384 - target)
 *
 * [longest] is the long edge of the selection in page units.
 *
 * 10 px was measured, not chosen: 2 px reads as noise and scored 0.617, 24 px blots the counters
 * shut and scored 0.437, and the curve between them is not monotonic. Another model passes its own
 * [target] in the same 384 frame — see [FormulaEngine.stemPx].
 */
internal fun recognitionStemSize(longest: Float, target: Float = RECOGNITION_STEM_PX): Float =
    target * longest / (FORMULA_INPUT_PX - target)

/** The square PP-FormulaNet-S is fed, and the frame every model's stem width is stated in. */
internal const val FORMULA_INPUT_PX = 384f

/** FormulaNet-S's measured best stem, and the width text recognition renders at. */
internal const val RECOGNITION_STEM_PX = 10f
