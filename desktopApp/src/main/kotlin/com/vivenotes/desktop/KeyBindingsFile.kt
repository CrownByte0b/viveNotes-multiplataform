package com.vivenotes.desktop

import com.vivenotes.workspace.KeyBindings
import com.vivenotes.workspace.KeyChord
import com.vivenotes.workspace.ShortcutAction
import java.io.File
import java.util.Properties

/**
 * The keyboard shortcuts changed in Settings → Hardware. Only changes are written, one line per
 * command — `Undo=Ctrl+Shift+U`, or `Undo=` for a disabled one — so a default this build changes
 * reaches everyone who never touched it.
 */
internal class KeyBindingsFile(private val file: File) {
    fun load(): KeyBindings {
        val properties = file.readProperties() ?: return KeyBindings.Default
        // A command this build does not know, or a chord it cannot read, is skipped rather than failing.
        val changes = ShortcutAction.entries.mapNotNull { action ->
            val value = properties.getProperty(action.name) ?: return@mapNotNull null
            action to value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { KeyChord.decode(it) }
        }.filter { (_, chords) -> chords.none { it == null } }
            .associate { (action, chords) -> action to chords.filterNotNull() }
        return KeyBindings.of(changes)
    }

    fun save(bindings: KeyBindings) {
        file.writePropertiesAtomically(Properties().apply {
            bindings.changes.forEach { (action, chords) ->
                setProperty(action.name, chords.joinToString(",") { it.encode() })
            }
        }, "ViveNotes keyboard shortcuts")
    }
}
