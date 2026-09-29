package com.vivenotes.workspace

/** Where the notebook and section navigation lives — the View tab's Tabs Layout control. */
enum class TabsLayout { Vertical, Horizontal }

/**
 * The View tab's settings that describe this device's view of the notes rather than any page.
 *
 * Android's `ViewSettings`, split from [com.vivenotes.model.PageStyle] along the same line: ruling,
 * paper and page colour belong to the page and travel with it, while zoom, navigation layout and
 * canvas brightness are how one person is looking at it on this machine. They are kept in the
 * desktop's config directory, never in the notes database or its sync.
 */
data class ViewSettings(
    val zoom: Float = 1f,
    val tabsLayout: TabsLayout = TabsLayout.Vertical,
    /** Switch Background's override. Null follows the app theme. */
    val canvasDark: Boolean? = null,
    /** Whether this device fetches and shows YouTube thumbnail previews. */
    val linkPreviews: Boolean = true,
    /** Theme in force when Switch Background was last used; null is the older, unscoped setting. */
    val canvasThemeDark: Boolean? = null,
) {
    fun normalized(): ViewSettings =
        copy(zoom = zoom.takeIf { it.isFinite() }?.coerceIn(MIN_ZOOM, MAX_ZOOM) ?: 1f)

    /** An explicit app theme supersedes a canvas preference saved under another theme. */
    fun canvasDarkForTheme(themePreference: Boolean?, themeDark: Boolean): Boolean =
        canvasDark.takeIf { themePreference == null || canvasThemeDark == themePreference } ?: themeDark

    companion object {
        /** The zoom levels the ribbon offers, and the ladder Zoom in and Zoom out climb. */
        val ZOOM_STEPS = listOf(0.05f, 0.1f, 0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)

        val MIN_ZOOM = ZOOM_STEPS.first()
        val MAX_ZOOM = ZOOM_STEPS.last()

        /**
         * The next preset above [zoom]. Zoom does not always sit on a preset — Ctrl+wheel and Page
         * Width land between them — so this finds the neighbouring step rather than indexing.
         */
        fun zoomStepUp(zoom: Float): Float = ZOOM_STEPS.firstOrNull { it > zoom + ZOOM_EPSILON } ?: MAX_ZOOM

        fun zoomStepDown(zoom: Float): Float = ZOOM_STEPS.lastOrNull { it < zoom - ZOOM_EPSILON } ?: MIN_ZOOM

        /** The zoom that fits [contentWidthDp] into [viewportWidthDp]; null before the canvas is measured. */
        fun fitZoom(viewportWidthDp: Float, contentWidthDp: Float): Float? {
            if (viewportWidthDp <= 0f || contentWidthDp <= 0f) return null
            return (viewportWidthDp / contentWidthDp).coerceIn(MIN_ZOOM, MAX_ZOOM)
        }

        private const val ZOOM_EPSILON = 0.001f
    }
}
