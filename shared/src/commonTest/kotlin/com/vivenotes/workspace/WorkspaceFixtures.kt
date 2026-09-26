package com.vivenotes.workspace

/**
 * The page's first text box focused for editing, as a click into it leaves the workspace. A page
 * with no text box — an unreadable page's stand-in — is left as it is.
 */
fun WorkspaceState.focusBody(): WorkspaceState = bodyTextOutline?.let { focusTextBox(it.id) } ?: this
