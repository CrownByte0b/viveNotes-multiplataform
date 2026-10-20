// Ported from the Android app's `ui/account/StripePages.kt` and the Stripe half of its `NotesApp`. The
// desktop has no Custom Tab: the page opens in the person's own browser, and the Activity's
// `ON_RESUME` that tells Android the person is back becomes this window getting the focus back.
package com.vivenotes.ui.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.vivenotes.data.billing.ManagedSubscriptionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * Opens a Stripe-hosted page — Checkout or the customer portal — in the person's browser.
 *
 * Only https is opened. The URL comes from the managed server, and a page for typing a card into
 * has no business arriving as a `file:` link or a custom scheme whatever the server says. The
 * browser's own address bar shows the person they are on Stripe.
 *
 * False when the URL is not one to open, or nothing on this computer could open it.
 */
internal fun openStripePage(openBrowser: (URI) -> Unit, url: String): Boolean {
    val page = runCatching { URI(url) }.getOrNull() ?: return false
    if (page.scheme != "https" || page.host.isNullOrEmpty()) return false
    return try {
        openBrowser(page)
        true
    } catch (noBrowser: Exception) {
        false
    }
}

/**
 * Android's two `NotesApp` effects for Stripe: open each page [subscription] hands over, and, once
 * the person is back, let it ask the server what they did there.
 *
 * [foreground] is whether this window has the focus. Paying happens in another window, so the focus
 * coming back is the desktop's closest thing to the Custom Tab closing over the app; the controller
 * refreshes only when it sent the person to a page, so any other return costs nothing.
 *
 * Composed for as long as the workspace is, open Account screen or not, as Android's are for as long
 * as its Activity is: a page that is ready while nothing collects it is dropped, not opened later.
 */
@Composable
internal fun StripePageEffects(
    subscription: ManagedSubscriptionController,
    openBrowser: (URI) -> Unit,
    foreground: Boolean,
) {
    LaunchedEffect(subscription, openBrowser) {
        subscription.browserPages.collect { url ->
            // AWT's browse call can wait on the desktop session, which is no work for the UI thread.
            val opened = withContext(Dispatchers.IO) { openStripePage(openBrowser, url) }
            if (!opened) subscription.onBrowserUnavailable()
        }
    }
    LaunchedEffect(subscription, foreground) {
        if (foreground) subscription.onForeground()
    }
}
