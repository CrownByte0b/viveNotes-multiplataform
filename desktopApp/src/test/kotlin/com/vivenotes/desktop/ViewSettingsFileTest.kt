package com.vivenotes.desktop

import com.vivenotes.workspace.TabsLayout
import com.vivenotes.workspace.ViewSettings
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class ViewSettingsFileTest {
    @Test
    fun viewSettingsSurviveReopeningAndInvalidValuesFallBack() {
        val directory = createTempDirectory("view-settings").toFile()
        try {
            val file = File(directory, "config/view.properties")
            val store = ViewSettingsFile(file)
            assertEquals(ViewSettings(), store.load())

            val saved = ViewSettings(zoom = 0.5f, tabsLayout = TabsLayout.Horizontal, canvasDark = false,
                linkPreviews = false, canvasThemeDark = true)
            store.save(saved)
            assertEquals(saved, ViewSettingsFile(file).load())

            file.writeText("zoom=900\ntabsLayout=Diagonal\ncanvasDark=maybe\ncanvasThemeDark=maybe\n")
            assertEquals(ViewSettings(zoom = ViewSettings.MAX_ZOOM), store.load())
            file.writeText("not a properties file \u0000\\u12")
            assertEquals(ViewSettings(), store.load())
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Until Switch Background is used the canvas follows the theme, so nothing is written for it. */
    @Test
    fun anUnsetCanvasBrightnessStaysUnset() {
        val directory = createTempDirectory("view-settings").toFile()
        try {
            val file = File(directory, "view.properties")
            ViewSettingsFile(file).save(ViewSettings(zoom = 2f))
            assertEquals(null, ViewSettingsFile(file).load().canvasDark)
            assertEquals(false, file.readText().contains("canvasDark"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
