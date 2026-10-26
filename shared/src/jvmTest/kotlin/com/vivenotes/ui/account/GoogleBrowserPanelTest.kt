package com.vivenotes.ui.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import com.sun.net.httpserver.HttpServer
import com.vivenotes.data.billing.ManagedSubscriptionController
import com.vivenotes.data.sync.GoogleIdentityProvider
import com.vivenotes.data.sync.SyncAccountStore
import com.vivenotes.data.sync.SyncAccounts
import com.vivenotes.localization.Strings
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * The desktop's way out of a browser that let the Google sign-in down: while the app waits for the
 * browser to come back, the Account screen offers to copy Google's page into another browser or to
 * start over with a fresh challenge.
 */
@OptIn(ExperimentalTestApi::class)
class GoogleBrowserPanelTest {

    private val clipboard = FakeClipboard()
    private var signingIn by mutableStateOf(false)
    private var page by mutableStateOf<String?>(null)
    private var retries = 0

    private val challenges = AtomicInteger()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/v1/auth/google/challenges") { exchange ->
            val n = challenges.incrementAndGet()
            val body = """{"challengeId":"challenge-$n","nonce":"nonce-$n","expiresAt":"2026-10-22T07:00:00Z"}"""
                .toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(201, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val root = Files.createTempDirectory("google-browser-panel").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        server.stop(0)
        scope.cancel()
        storeScope.cancel()
        root.deleteRecursively()
    }

    @Test
    fun nothingIsOfferedUntilTheSignInIsWaitingOnTheBrowser() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.GOOGLE_BROWSER_PANEL).assertDoesNotExist()

        // Fetching the server's challenge: the button spins, but there is no page to offer yet.
        signingIn = true
        onNodeWithTag(AccountTags.GOOGLE_BROWSER_PANEL).assertDoesNotExist()

        page = PAGE
        onNodeWithTag(AccountTags.GOOGLE_BROWSER_PANEL).assertIsDisplayed()
        onNodeWithTag(AccountTags.GOOGLE_COPY_LINK).assertIsDisplayed()
        onNodeWithTag(AccountTags.GOOGLE_TRY_AGAIN).assertIsDisplayed()

        signingIn = false
        onNodeWithTag(AccountTags.GOOGLE_BROWSER_PANEL).assertDoesNotExist()
    }

    @Test
    fun copyLinkPutsGooglesPageOnTheClipboardAndSaysSo() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        signingIn = true
        page = PAGE
        setScreen()

        onNodeWithTag(AccountTags.GOOGLE_COPY_LINK).assertTextContains(Strings.account_google_copy_link).performClick()

        assertEquals(PAGE, clipboard.text)
        onNodeWithTag(AccountTags.GOOGLE_COPY_LINK).assertTextContains(Strings.account_google_link_copied)

        // A new attempt is a new link, not yet copied.
        page = "$PAGE&attempt=2"
        onNodeWithTag(AccountTags.GOOGLE_COPY_LINK).assertTextContains(Strings.account_google_copy_link)
    }

    @Test
    fun tryAgainAsksTheDestinationToStartOver() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        signingIn = true
        page = PAGE
        setScreen()

        onNodeWithTag(AccountTags.GOOGLE_TRY_AGAIN).performClick()

        assertEquals(1, retries)
    }

    /**
     * The whole route through Android's [SyncAccounts]: Try again drops the attempt the browser left
     * hanging, frees its loopback address, and opens a new page on a fresh server challenge — while the
     * button keeps spinning, because the sign-in as a whole is still under way.
     */
    @Test
    fun tryAgainStartsAFreshSignInOnANewChallenge() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        val opened = CopyOnWriteArrayList<URI>()
        val host = host(openBrowser = { opened += it })
        setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                ViveNotesTheme { host.Destination(visible = true, onBack = {}) }
            }
        }

        onNodeWithTag(AccountTags.GOOGLE).performClick()
        waitUntil(timeoutMillis = 10_000) { opened.size == 1 && exists(AccountTags.GOOGLE_TRY_AGAIN) }
        onNodeWithTag(AccountTags.GOOGLE_TRY_AGAIN).performClick()
        waitUntil(timeoutMillis = 10_000) { opened.size == 2 && exists(AccountTags.GOOGLE_TRY_AGAIN) }

        val (first, second) = opened.map(::query)
        assertEquals(2, challenges.get())
        assertEquals(listOf("nonce-1", "nonce-2"), listOf(first["nonce"], second["nonce"]))
        assertNotEquals(first["state"], second["state"])
        assertFailsWith<IOException> { redirect(opened[0], "code=late&state=${first["state"]}") }
        onNodeWithTag(AccountTags.GOOGLE_PROGRESS).assertIsDisplayed()

        onNodeWithTag(AccountTags.GOOGLE_COPY_LINK).performClick()
        assertEquals(opened[1].toString(), clipboard.text)

        // The person then cancels on Google's new page: the sign-in ends quietly, ready to start again.
        thread(isDaemon = true) { redirect(opened[1], "error=access_denied&state=${second["state"]}") }
        waitUntil(timeoutMillis = 10_000) { !exists(AccountTags.GOOGLE_BROWSER_PANEL) && !exists(AccountTags.GOOGLE_PROGRESS) }
        onNodeWithTag(AccountTags.GOOGLE).assertIsEnabled()
        assertEquals(2, challenges.get())
    }

    private fun ComposeUiTest.exists(tag: String): Boolean =
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.setScreen() {
        setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                ViveNotesTheme {
                    AccountScreen(
                        onBack = {},
                        signingInWithGoogle = signingIn,
                        googleSignInPage = page,
                        onRetryGoogle = { retries++ },
                    )
                }
            }
        }
    }

    private fun host(openBrowser: (URI) -> Unit): SyncAccountHost {
        val accounts = SyncAccounts(
            store = SyncAccountStore(root.resolve("datastore/sync.preferences_pb"), null, storeScope),
            cloudBaseUrl = "http://127.0.0.1:${server.address.port}",
            deviceName = "test (0000)",
            platform = "Linux",
            google = GoogleIdentityProvider(
                clientId = "desktop-client",
                codeExchangeEndpoint = "http://127.0.0.1:${server.address.port}/v1/auth/google/desktop-tokens",
                openBrowser = openBrowser,
                authorizationEndpoint = "https://accounts.example.test/auth",
            ),
        )
        val subscription = ManagedSubscriptionController(accounts, scope, cardCheckoutAllowed = false)
        return SyncAccountHost(accounts, subscription, scope, openBrowser = {})
    }

    /** What a browser does once Google is done: load the loopback address with Google's answer. */
    private fun redirect(page: URI, answer: String) {
        val connection = URI("${query(page).getValue("redirect_uri")}?$answer").toURL().openConnection() as HttpURLConnection
        connection.responseCode
        connection.disconnect()
    }

    private fun query(uri: URI): Map<String, String> = uri.rawQuery.split('&').associate { part ->
        URLDecoder.decode(part.substringBefore('='), StandardCharsets.UTF_8) to
            URLDecoder.decode(part.substringAfter('='), StandardCharsets.UTF_8)
    }

    private class FakeClipboard : ClipboardManager {
        var text: String? = null

        override fun getText(): AnnotatedString? = text?.let(::AnnotatedString)

        override fun setText(annotatedString: AnnotatedString) {
            text = annotatedString.text
        }
    }

    private companion object {
        const val WIDTH = 900
        const val HEIGHT = 1400
        const val PAGE = "https://accounts.google.com/o/oauth2/v2/auth?client_id=desktop-client&state=s"
    }
}
