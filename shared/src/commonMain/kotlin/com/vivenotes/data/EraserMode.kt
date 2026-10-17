package com.vivenotes.data

import com.vivenotes.localization.Strings
import kotlinx.serialization.Serializable

/**
 * Whether the eraser cuts through ink or removes a complete stroke at the first contact.
 *
 * From the Android app's `data/PenSettings.kt`. It is here ahead of the desktop eraser because
 * `ink_erases.mode` stores its names, and a row written by Android has to read back unchanged.
 */
@Serializable
enum class EraserMode(private val labelText: () -> String) {
    Normal({ Strings.normal }),
    Object({ Strings.object_label });

    val label: String get() = labelText()
}
