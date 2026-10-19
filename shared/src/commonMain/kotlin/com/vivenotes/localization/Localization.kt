package com.vivenotes.localization

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.CancellationException
import multiplataform_vive.shared.generated.resources.Res
import multiplataform_vive.shared.generated.resources.allStringResources
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getSystemResourceEnvironment

/**
 * Resolves Compose resources before opening the workspace, including text used outside composition.
 * The generated English defaults keep previews and isolated state tests usable without initialization.
 * Publishing a complete catalog in one snapshot also updates composed labels together on a reload.
 */
object Localization {
    private var translated by mutableStateOf<Map<String, String>>(emptyMap())
    private val placeholder = Regex("""%(\d+)\$[sd]""")

    suspend fun initialize(environment: ResourceEnvironment? = null, log: DebugLog = DebugLog()) {
        try {
            val selectedEnvironment = environment ?: getSystemResourceEnvironment()
            load { resource -> getString(selectedEnvironment, resource) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.failure("localization", "resource loading", failure)
            throw failure
        }
        log.event("localization") { "loaded ${translated.size} strings" }
    }

    internal suspend fun load(resolve: suspend (StringResource) -> String) {
        val resources = Res.allStringResources
        check(resources.keys.containsAll(defaultStrings.keys)) { "Incomplete application string resources" }
        val loaded = resources.mapValues { (_, resource) -> resolve(resource) }
        translated = loaded
    }

    internal fun text(key: String, vararg arguments: Any?): String {
        val pattern = translated[key] ?: defaultStrings.getValue(key)
        if (arguments.isEmpty()) return pattern
        return placeholder.replace(pattern) { match ->
            val index = match.groupValues[1].toInt() - 1
            require(index in arguments.indices) { "Missing argument for string resource $key" }
            arguments[index].toString()
        }
    }

    internal fun reset() { translated = emptyMap() }
}
