package com.vivenotes.localization

import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.data.NotesLibrary
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.workspace.KeyChord
import com.vivenotes.workspace.ShortcutAction
import com.vivenotes.workspace.ShortcutKey
import java.util.Locale
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import multiplataform_vive.shared.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal val FrenchFixtures: Map<String, StringResource> = mapOf(
    "close" to Res.string.localization_test_close,
    "settings_tab" to Res.string.localization_test_settings,
    "interface_label" to Res.string.localization_test_interface,
    "cancel" to Res.string.localization_test_cancel,
    "save" to Res.string.localization_test_english_only,
    "size" to Res.string.localization_test_size,
    "custom" to Res.string.localization_test_custom,
    "search_notebook" to Res.string.localization_test_search,
    "undo" to Res.string.localization_test_undo,
    "key_ctrl" to Res.string.localization_test_ctrl,
    "rectangle" to Res.string.localization_test_rectangle,
    "version_value" to Res.string.localization_test_version,
    "coupon_added_value_month_s_through_value" to Res.string.localization_test_coupon,
    "math_engine_derivative_with_respect_to_value" to Res.string.localization_test_derivative,
    "math_engine_solve" to Res.string.localization_test_solve,
    "my_notebook" to Res.string.localization_test_notebook,
    "getting_started" to Res.string.localization_test_getting_started,
    "ideas" to Res.string.localization_test_ideas,
    "welcome" to Res.string.localization_test_welcome,
)

private fun environmentFor(locale: Locale): ResourceEnvironment {
    val previous = Locale.getDefault()
    try {
        Locale.setDefault(locale)
        return getSystemResourceEnvironment()
    } finally {
        Locale.setDefault(previous)
    }
}

private val defaultFixtureEnvironment by lazy { environmentFor(Locale.ROOT) }
private val frenchFixtureEnvironment by lazy { environmentFor(Locale.FRANCE) }

internal suspend fun loadFrenchFixtures() {
    val french = frenchFixtureEnvironment
    val defaults = defaultFixtureEnvironment
    Localization.load { resource ->
        FrenchFixtures[resource.key]?.let { getString(french, it) } ?: getString(defaults, resource)
    }
}

class ResourceCatalogTest {
    @AfterTest
    fun restoreDefaults() = Localization.reset()

    @Test
    fun everyGeneratedDefaultMatchesTheActualPackagedComposeResource() = runBlocking {
        assertTrue(defaultStrings.size >= 800)
        assertTrue(Res.allStringResources.keys.containsAll(defaultStrings.keys))
        val environment = defaultFixtureEnvironment
        for ((key, value) in defaultStrings) {
            assertEquals(value, getString(environment, Res.allStringResources.getValue(key)), key)
        }
    }

    @Test
    fun translatedResourcesCanReorderArgumentsAndFallbackToEnglish() = runBlocking {
        loadFrenchFixtures()
        assertEquals("Fermer", Strings.close)
        assertEquals("Version de l’application 0.1.0", Strings.version_value("0.1.0"))
        assertEquals("Jusqu’au demain : 3 mois ajoutés.",
            Strings.coupon_added_value_month_s_through_value(3, "demain"))
        assertEquals("Save", Strings.save)
    }

    @Test
    fun reloadingLocalizesExistingEnumsWithoutChangingStoredShortcutIdentifiers() = runBlocking {
        val action = ShortcutAction.Undo
        val shape = ShapeKind.Rectangle
        val chord = KeyChord(ShortcutKey.PageUp, ctrl = true)
        assertEquals("Undo", action.label)
        assertEquals("Rectangle", shape.label)
        loadFrenchFixtures()
        assertEquals("Annuler l’action", action.label)
        assertEquals("Rectangle traduit", shape.label)
        assertEquals("Contrôle+Page Up", chord.label)
        assertEquals("Ctrl+PageUp", chord.encode())
        assertEquals(chord, KeyChord.decode(chord.encode()))
        assertEquals("Rectangle", shape.name)
    }

    @Test
    fun aFailedReloadKeepsThePreviousCompleteCatalog() = runBlocking {
        loadFrenchFixtures()
        val environment = defaultFixtureEnvironment
        var calls = 0
        assertFailsWith<IllegalStateException> {
            Localization.load { resource ->
                if (++calls == 3) error("fixture read failed")
                getString(environment, resource)
            }
        }
        assertEquals("Fermer", Strings.close)
        assertEquals("Paramètres", Strings.settings_tab)
    }

    @Test
    fun unchangedMathProtocolMessagesAreLocalizedAtThePresentationBoundary() = runBlocking {
        assertEquals("Derivative with respect to x", MathText.translate("Derivative with respect to x"))
        loadFrenchFixtures()
        assertEquals("Résoudre", MathText.translate("Solve"))
        assertEquals("Dérivée par rapport à x", MathText.translate("Derivative with respect to x"))
        assertEquals("Dérivée par rapport à x\ny", MathText.translate("Derivative with respect to x\ny"))
        assertEquals("x^2 + \\frac{1}{y}", MathText.translate("x^2 + \\frac{1}{y}"))
        assertEquals("New external diagnostic", MathText.translate("New external diagnostic"))
    }

    @Test
    fun translatedConvergenceVerdictsRemainValidPlainTextLatex() = runBlocking {
        val environment = defaultFixtureEnvironment
        Localization.load { resource ->
            if (resource.key == "math_engine_converges") "Converge à 100%" else getString(environment, resource)
        }
        assertEquals("\\text{Converge à 100\\%}", MathText.translateVerdict("\\text{Converges}"))
        assertEquals("x^{2}", MathText.translateVerdict("x^{2}"))
        assertEquals("\\text{unknown}", MathText.translateVerdict("\\text{unknown}"))
    }

    @Test
    fun bundledMathActionLabelsTitlesAndStaticInputErrorsHaveTranslationEntries() {
        val source = checkNotNull(javaClass.getResourceAsStream("/math-engine/math_engine.py"))
            .bufferedReader().use { it.readText() }
        val messages = Regex("""_action\("[^"]+",\s*"([^"]+)"\)|_latex_result\("([^"]+)"|MathInputError\("([^"]+)"\)""")
            .findAll(source).map { match -> match.groupValues.drop(1).first(String::isNotBlank) }.toSet()
        val catalog = defaultStrings.filterKeys { it.startsWith("math_engine_") }.values.toSet()
        assertTrue(messages.size > 30)
        assertTrue(catalog.containsAll(messages), "Math messages missing from strings.xml: ${messages - catalog}")
    }

    @Test
    fun initializationLogsOnlyWhenDiagnosticsAreEnabled() = runBlocking {
        val output = mutableListOf<String>()
        Localization.initialize(environment = defaultFixtureEnvironment, log = DebugLog(false, output::add))
        assertTrue(output.isEmpty())
        Localization.initialize(environment = defaultFixtureEnvironment, log = DebugLog(true, output::add))
        assertEquals(1, output.size)
        assertTrue(output.single().contains("[localization] loaded"))
    }

    @Test
    fun starterNamesUseTheInitialLocaleAndPersistAfterTheLocaleChanges() = runBlocking {
        val directory = Files.createTempDirectory("localized-starter-").toFile()
        try {
            loadFrenchFixtures()
            NotesLibrary.open(directory).use { library ->
                library.repository.seedIfEmpty()
                val tree = library.repository.observeTree().first().single()
                assertEquals("Mon carnet", tree.notebook.name)
                assertEquals(listOf("Prise en main", "Idées"), tree.liveSections.map { it.name })
                assertEquals("Bienvenue", library.repository.observePages(tree.liveSections.first().id).first().single().title)
            }
            Localization.reset()
            NotesLibrary.open(directory).use { library ->
                library.repository.seedIfEmpty()
                val tree = library.repository.observeTree().first().single()
                assertEquals("Mon carnet", tree.notebook.name)
                assertEquals(listOf("Prise en main", "Idées"), tree.liveSections.map { it.name })
                assertEquals("Bienvenue", library.repository.observePages(tree.liveSections.first().id).first().single().title)
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
