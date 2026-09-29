package com.vivenotes.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/**
 * Exact Material Symbols Rounded path data for the Settings tab's panes: Keyboard and Stylus from
 * the Android Hardware pane's drawables, Restart Alt from Google's `material-design-icons` repository.
 */
object SettingsSymbols {
    private fun symbol(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 960f, 960f).group(translationY = 960f) {
            addPath(addPathNodes(path), fill = SolidColor(Color.Black))
        }.build()

    val Keyboard: ImageVector by lazy {
        symbol("Keyboard", "M160-200q-33 0-56.5-23.5T80-280v-400q0-33 23.5-56.5T160-760h640q33 0 56.5 23.5T880-680v400q0 33-23.5 56.5T800-200H160Zm0-80h640v-400H160v400Zm200-40h240q17 0 28.5-11.5T640-360q0-17-11.5-28.5T600-400H360q-17 0-28.5 11.5T320-360q0 17 11.5 28.5T360-320Zm-200 40v-400 400Zm108.5-291.5Q280-583 280-600t-11.5-28.5Q257-640 240-640t-28.5 11.5Q200-617 200-600t11.5 28.5Q223-560 240-560t28.5-11.5Zm120 0Q400-583 400-600t-11.5-28.5Q377-640 360-640t-28.5 11.5Q320-617 320-600t11.5 28.5Q343-560 360-560t28.5-11.5Zm120 0Q520-583 520-600t-11.5-28.5Q497-640 480-640t-28.5 11.5Q440-617 440-600t11.5 28.5Q463-560 480-560t28.5-11.5Zm120 0Q640-583 640-600t-11.5-28.5Q617-640 600-640t-28.5 11.5Q560-617 560-600t11.5 28.5Q583-560 600-560t28.5-11.5Zm120 0Q760-583 760-600t-11.5-28.5Q737-640 720-640t-28.5 11.5Q680-617 680-600t11.5 28.5Q703-560 720-560t28.5-11.5Zm-480 120Q280-463 280-480t-11.5-28.5Q257-520 240-520t-28.5 11.5Q200-497 200-480t11.5 28.5Q223-440 240-440t28.5-11.5Zm120 0Q400-463 400-480t-11.5-28.5Q377-520 360-520t-28.5 11.5Q320-497 320-480t11.5 28.5Q343-440 360-440t28.5-11.5Zm120 0Q520-463 520-480t-11.5-28.5Q497-520 480-520t-28.5 11.5Q440-497 440-480t11.5 28.5Q463-440 480-440t28.5-11.5Zm120 0Q640-463 640-480t-11.5-28.5Q617-520 600-520t-28.5 11.5Q560-497 560-480t11.5 28.5Q583-440 600-440t28.5-11.5Zm120 0Q760-463 760-480t-11.5-28.5Q737-520 720-520t-28.5 11.5Q680-497 680-480t11.5 28.5Q703-440 720-440t28.5-11.5Z")
    }

    val Stylus: ImageVector by lazy {
        symbol("Stylus", "M160-120l22-65q8-25 29-40t47-15h444q26,0 47,15t29,40l22,65H160Zm80-200 200-520h80l200,520H240Zm116-80h248L480-721 356-400Zm0,0h248-248Z")
    }

    /** Puts one shortcut back to its default. */
    val RestartAlt: ImageVector by lazy {
        symbol("RestartAlt", "M393-132q-103-29-168-113.5T160-440q0-57 19-108.5t54-94.5q11-12 27-12.5t29 12.5q11 11 11.5 27T290-586q-24 31-37 68t-13 78q0 81 47.5 144.5T410-209q13 4 21.5 15t8.5 24q0 20-14 31.5t-33 6.5Zm174 0q-19 5-33-7t-14-32q0-12 8.5-23t21.5-15q75-24 122.5-87T720-440q0-100-70-170t-170-70h-3l16 16q11 11 11 28t-11 28q-11 11-28 11t-28-11l-84-84q-6-6-8.5-13t-2.5-15q0-8 2.5-15t8.5-13l84-84q11-11 28-11t28 11q11 11 11 28t-11 28l-16 16h3q134 0 227 93t93 227q0 109-65 194T567-132Z")
    }

    /** Material Symbols Rounded arrow used for external links. */
    val ArrowOutward: ImageVector by lazy {
        ImageVector.Builder("ArrowOutward", 24.dp, 24.dp, 960f, 960f).apply {
            addPath(addPathNodes("M640,336L284,692Q273,703 256,703Q239,703 228,692Q217,681 217,664Q217,647 228,636L584,280L280,280Q263,280 251.5,268.5Q240,257 240,240Q240,223 251.5,211.5Q263,200 280,200L680,200Q697,200 708.5,211.5Q720,223 720,240L720,640Q720,657 708.5,668.5Q697,680 680,680Q663,680 651.5,668.5Q640,657 640,640L640,336Z"),
                fill = SolidColor(Color.Black))
        }.build()
    }

    /** GitHub's mark, matching the icon used by the Android About screen. */
    val GitHub: ImageVector by lazy {
        ImageVector.Builder("GitHub", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(addPathNodes("M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"),
                fill = SolidColor(Color.Black))
        }.build()
    }

    /** Material Favorite for the project's support link. */
    val Favorite: ImageVector by lazy {
        ImageVector.Builder("Favorite", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(addPathNodes("M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"),
                fill = SolidColor(Color.Black))
        }.build()
    }
}
