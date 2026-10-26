package com.vivenotes.data.sync

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop half of Sign in with Google: the system browser, a loopback redirect and a PKCE code
 * exchange through the managed server, ending in what Android's Credential Manager hands back — an
 * ID token and its email claim, or a dismissal, or a refusal the screen can explain.
 *
 * The server's code exchange is a loopback `HttpServer` here, and the browser is a thread that
 * follows the redirect the way a browser would, so the whole exchange runs over real sockets.
 */
class GoogleIdentityProviderTest {

    private val tokenRequests = CopyOnWriteArrayList<Map<String, String>>()
    private var tokenStatus = 200
    private var tokenBody = """{"idToken":"${idToken("owner@example.com")}"}"""

    /** Runs as the server's code exchange receives a code, before it answers. */
    @Volatile private var onTokenRequest: () -> Unit = {}
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/v1/auth/google/desktop-tokens") { exchange ->
            val request = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()) as JsonObject
            tokenRequests += request.mapValues { (_, value) -> (value as JsonPrimitive).content }
            onTokenRequest()
            val body = tokenBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(tokenStatus, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val codeExchangeEndpoint = "http://127.0.0.1:${server.address.port}/v1/auth/google/desktop-tokens"

    /** What the browser was asked to open, and what it was shown on returning to the app. */
    private val opened = CopyOnWriteArrayList<URI>()
    private val shown = CopyOnWriteArrayList<Pair<Int, String>>()
    private val browsers = CopyOnWriteArrayList<Thread>()

    /** The browser reads its page after the provider has returned; this waits for it to finish. */
    private fun browserDone() = browsers.forEach { it.join(5_000) }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun anUnconfiguredBuildRefusesWithoutOpeningABrowser() = runBlocking {
        val provider = GoogleIdentityProvider(clientId = "", openBrowser = { opened += it })

        assertEquals(GoogleIdToken.Rejected(ConnectFailure.GoogleNotConfigured), provider.requestIdToken("nonce"))
        assertTrue(opened.isEmpty())
    }

    /** Without the server's code exchange there is no way to finish, so nothing is started. */
    @Test
    fun aBuildWithoutTheCodeExchangeRefusesWithoutOpeningABrowser() = runBlocking {
        val provider = GoogleIdentityProvider(clientId = "desktop-client", openBrowser = { opened += it })

        assertFalse(provider.configured)
        assertEquals(GoogleIdToken.Rejected(ConnectFailure.GoogleNotConfigured), provider.requestIdToken("nonce"))
        assertTrue(opened.isEmpty())
    }

    /**
     * The whole sign-in: the server's nonce goes to Google, the code comes back to this process only,
     * and the server's exchange proves with the verifier that it is the same process that asked. The
     * app sends no client secret; the server adds it.
     */
    @Test
    fun aCompletedSignInReturnsTheIdTokenAndItsEmailClaim() = runBlocking {
        val provider = provider(browser = { uri -> followRedirect(uri, code = "auth-code") })

        val result = provider.requestIdToken("server-nonce")

        assertEquals(GoogleIdToken.Received(idToken("owner@example.com"), "owner@example.com"), result)
        val asked = query(opened.single())
        assertEquals("desktop-client", asked["client_id"])
        assertEquals("code", asked["response_type"])
        assertEquals("openid email", asked["scope"])
        assertEquals("server-nonce", asked["nonce"])
        assertEquals("S256", asked["code_challenge_method"])
        assertTrue(asked.getValue("redirect_uri").startsWith("http://127.0.0.1:"))

        val exchange = tokenRequests.single()
        assertEquals(setOf("code", "codeVerifier", "redirectUri"), exchange.keys)
        assertEquals("auth-code", exchange["code"])
        assertEquals(asked["redirect_uri"], exchange["redirectUri"])
        assertEquals(asked["code_challenge"], challengeOf(exchange.getValue("codeVerifier")))

        browserDone()
        assertEquals(200 to "signed in", shown.single())
    }

    /** Closing Google's page or pressing Cancel there is the person changing their mind: nothing to say. */
    @Test
    fun cancellingInTheBrowserIsADismissalNotAFailure() = runBlocking {
        val provider = provider(browser = { uri -> followRedirect(uri, error = "access_denied") })

        assertEquals(GoogleIdToken.Dismissed, provider.requestIdToken("nonce"))
        assertTrue(tokenRequests.isEmpty())
        browserDone()
        assertEquals(200 to "not signed in", shown.single())
    }

    /** Only the redirect carrying this sign-in's `state` is answered; anything else is turned away. */
    @Test
    fun aRequestWithoutThisSignInsStateIsIgnored() = runBlocking {
        val provider = provider(browser = { uri ->
            followRedirect(uri, code = "forged", state = "not-the-state")
            followRedirect(uri, code = "auth-code")
        })

        val result = provider.requestIdToken("nonce")

        assertTrue(result is GoogleIdToken.Received)
        assertEquals(listOf("auth-code"), tokenRequests.map { it["code"] })
        browserDone()
        assertEquals(listOf(404, 200), shown.map { it.first })
    }

    /**
     * A server without the desktop client, or a code Google refused: neither is something the
     * person can fix, so both read as sign-in not being available.
     */
    @Test
    fun aRefusedExchangeIsReportedAsNotConfigured() = runBlocking {
        for ((status, error) in listOf(503 to "google_auth_unavailable", 400 to "invalid_google_code")) {
            tokenStatus = status
            tokenBody = """{"error":"$error","message":"refused"}"""
            val provider = provider(browser = { uri -> followRedirect(uri, code = "auth-code") })

            assertEquals(GoogleIdToken.Rejected(ConnectFailure.GoogleNotConfigured), provider.requestIdToken("nonce"), error)
        }
    }

    @Test
    fun anUnreachableServerIsReportedAsUnreachable() = runBlocking {
        server.stop(0)
        val provider = provider(browser = { uri -> followRedirect(uri, code = "auth-code") })

        assertEquals(GoogleIdToken.Rejected(ConnectFailure.Unreachable), provider.requestIdToken("nonce"))
    }

    /** A browser tab left open outlives the server's challenge; the sign-in ends quietly instead. */
    @Test
    fun aSignInLeftOpenEndsQuietly() = runBlocking {
        val provider = provider(browser = { }, timeoutMillis = 300)

        assertEquals(GoogleIdToken.Dismissed, provider.requestIdToken("nonce"))
    }

    /** No browser to open Google's page in: nothing can be signed into, which the build cannot fix. */
    @Test
    fun noBrowserIsReportedAsNotConfigured() = runBlocking {
        val provider = GoogleIdentityProvider(
            clientId = "desktop-client",
            codeExchangeEndpoint = codeExchangeEndpoint,
            openBrowser = { throw UnsupportedOperationException("no browser") },
        )

        assertEquals(GoogleIdToken.Rejected(ConnectFailure.GoogleNotConfigured), provider.requestIdToken("nonce"))
        assertTrue(tokenRequests.isEmpty())
    }

    /** Google's page is on offer while the browser is awaited, for the screen's Copy link, and not after. */
    @Test
    fun theOpenedPageIsOfferedOnlyWhileTheBrowserIsAwaited() = runBlocking {
        val offered = CopyOnWriteArrayList<URI?>()
        lateinit var provider: GoogleIdentityProvider
        provider = provider(browser = { uri ->
            offered += provider.browserPage.value
            followRedirect(uri, code = "auth-code")
        })
        assertNull(provider.browserPage.value)

        assertTrue(provider.requestIdToken("nonce") is GoogleIdToken.Received)

        assertEquals(listOf<URI?>(opened.single()), offered)
        assertNull(provider.browserPage.value)
        assertFalse(provider.abandonBrowserSignIn(), "a finished sign-in has nothing to abandon")
    }

    /** Try again: the wait ends as a dismissal and its loopback address stops answering. */
    @Test
    fun abandoningTheBrowserStepEndsTheSignInAsDismissed() = runBlocking {
        val provider = provider(browser = { })
        assertFalse(provider.abandonBrowserSignIn(), "nothing is waiting yet")
        val signIn = async(Dispatchers.Default) { provider.requestIdToken("nonce") }
        val page = withTimeout(5_000) { provider.browserPage.first { it != null } }!!

        assertTrue(provider.abandonBrowserSignIn())

        assertEquals(GoogleIdToken.Dismissed, withTimeout(5_000) { signIn.await() })
        assertNull(provider.browserPage.value)
        assertFalse(provider.abandonBrowserSignIn(), "the same wait cannot be abandoned twice")
        assertFailsWith<IOException> { followRedirect(page, code = "late-code") }
        assertTrue(tokenRequests.isEmpty())
    }

    /**
     * Once Google has answered, the code is on its way to becoming a device token, so Try again
     * leaves the sign-in to finish rather than dropping a credential halfway.
     */
    @Test
    fun aSignInGoogleHasAnsweredCannotBeAbandoned() = runBlocking {
        val exchanging = CountDownLatch(1)
        val release = CountDownLatch(1)
        onTokenRequest = {
            exchanging.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val provider = provider(browser = { uri -> followRedirect(uri, code = "auth-code") })
        val signIn = async(Dispatchers.Default) { provider.requestIdToken("nonce") }
        assertTrue(exchanging.await(5, TimeUnit.SECONDS))

        assertNull(provider.browserPage.value)
        assertFalse(provider.abandonBrowserSignIn())

        release.countDown()
        assertTrue(withTimeout(5_000) { signIn.await() } is GoogleIdToken.Received)
    }

    private fun provider(
        browser: (URI) -> Unit,
        timeoutMillis: Long = 10_000,
    ) = GoogleIdentityProvider(
        clientId = "desktop-client",
        codeExchangeEndpoint = codeExchangeEndpoint,
        openBrowser = { uri ->
            opened += uri
            // Off the caller's thread, as a real browser is: the provider is not listening until
            // `openBrowser` returns.
            browsers += thread(isDaemon = true) { browser(uri) }
        },
        authorizationEndpoint = "https://accounts.example.test/auth",
        timeoutMillis = timeoutMillis,
        returnPage = { signedIn -> if (signedIn) "signed in" else "not signed in" },
    )

    /** What the browser does once Google is done: it loads the redirect address with the answer. */
    private fun followRedirect(authorization: URI, code: String? = null, error: String? = null, state: String? = null) {
        val asked = query(authorization)
        val answer = buildList {
            code?.let { add("code=$it") }
            error?.let { add("error=$it") }
            add("state=${state ?: asked.getValue("state")}")
        }.joinToString("&")
        val connection = URI("${asked.getValue("redirect_uri")}?$answer").toURL().openConnection() as HttpURLConnection
        val status = connection.responseCode
        val body = (if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() }
        shown += status to body.orEmpty()
    }

    private fun query(uri: URI): Map<String, String> = form(uri.rawQuery)

    private fun form(text: String): Map<String, String> = text.split('&').filter { '=' in it }.associate { part ->
        URLDecoder.decode(part.substringBefore('='), StandardCharsets.UTF_8) to
            URLDecoder.decode(part.substringAfter('='), StandardCharsets.UTF_8)
    }

    private fun challengeOf(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private companion object {
        /** An unsigned JWT-shaped token: nothing here checks the signature, the server does. */
        fun idToken(email: String): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val header = encoder.encodeToString("""{"alg":"RS256"}""".toByteArray())
            val payload = encoder.encodeToString("""{"email":"$email","aud":"desktop-client"}""".toByteArray())
            return "$header.$payload.signature"
        }
    }
}
