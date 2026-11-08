package com.vivenotes.workspace

/**
 * Desktop tools use page dp; eraser mode chooses cuts or whole stored strokes.
 *
 * [Laser] is held like a pen but lays nothing down: a dot and a fading trace for pointing at what is
 * already on the page (Android `DrawTool.Laser`). It has no settings and no history.
 */
enum class InkTool { Pen, Highlighter, Laser, Eraser }
