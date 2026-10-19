package com.vivenotes.data.sync

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop half of Sign in with Google: the system browser, a loopback redirect and a PKCE code
 * exchange, ending in what Android's Credential Manager hands back — an ID token and its email
 * claim, or a dismissal, or a refusal the screen can explain.
 *
 * Google is a loopback `HttpServer` here, and the browser is a thread that follows the redirect the
 * way a browser would, so the whole exchange runs over real sockets.
 */
class GoogleIdentityProviderTest {

    private val tokenRequests = CopyOnWriteArrayList<Map<String, String>>()
    private var tokenStatus = 200
    private var tokenBody = """{"id_token":"${idToken("owner@example.com")}","access_token":"unused"}"""
    private val google: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/token") { exchange ->
            tokenRequests += form(exchange.requestBody.readBytes().decodeToString())
            val body = tokenBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(tokenStatus, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val tokenEndpoint = "http://127.0.0.1:${google.address.port}/token"

    /** What the browser was asked to open, and what it was shown on returning to the app. */
    private val opened = CopyOnWriteArrayList<URI>()
    private val shown = CopyOnWriteArrayList<Pair<Int, String>>()
    private val browsers = CopyOnWriteArrayList<Thread>()

    /** The browser reads its page after the provider has returned; this waits for it to finish. */
    private fun browserDone() = browsers.forEach { it.join(5_000) }

    @AfterTest
    fun tearDown() {
        google.stop(0)
    }

    @Test
    fun anUnconfiguredBuildRefusesWithoutOpeningABrowser() = runBlocking {
        val provider = GoogleIdentityProvider(clientId = "", openBrowser = { opened += it })

        assertEquals(GoogleIdToken.Rejected(ConnectFailure.GoogleNotConfigured), provider.requestIdToken("nonce"))
        assertTrue(opened.isEmpty())
    }

    /**
     * The whole sign-in: the server's nonce goes to Google, the code comes back to this process only,
     * and the exchange proves with the verifier that it is the same process that asked.
     */
    @Test
    fun aCompletedSignInReturnsTheIdTokenAndItsEmailClaim() = runBlocking {
        val provider = provider(browser = { uri -> followRedirect(uri, code = "auth-code") }, secret = "installed-secret")

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
        assertEquals("auth-code", exchange["code"])
        assertEquals("desktop-client", exchange["client_id"])
        assertEquals("installed-secret", exchange["client_secret"])
        assertEquals("authorization_code", exchange["grant_type"])
        assertEquals(asked["redirect_uri"], exchange["redirect_uri"])
        assertEquals(asked["code_challenge"], challengeOf(exchange.getValue("code_verifier")))

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

    /** `invalid_client` is a client id or secret Google does not know: the build is not set up. */
    @Test
    fun aClientGoogleDoesNotRecogniseIsReportedAsNotConfigured() = runBlocking {
        tokenStatus = 401
        tokenBody = """{"error":"invalid_client"}"""
        val provider = provider(browser = { uri -> followRedirect(uri, code = "auth-code") })

        assertEquals(GoogleIdToken.Rejected(ConnectFailure.GoogleNotConfigured), provider.requestIdToken("nonce"))
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
            openBrowser = { throw UnsupportedOperationException("no browser") },
            tokenEndpoint = tokenEndpoint,
        )

        assertEquals(GoogleIdToken.Rejected(ConnectFailure.GoogleNotConfigured), provider.requestIdToken("nonce"))
        assertTrue(tokenRequests.isEmpty())
    }

    private fun provider(
        browser: (URI) -> Unit,
        secret: String = "",
        timeoutMillis: Long = 10_000,
    ) = GoogleIdentityProvider(
        clientId = "desktop-client",
        clientSecret = secret,
        openBrowser = { uri ->
            opened += uri
            // Off the caller's thread, as a real browser is: the provider is not listening until
            // `openBrowser` returns.
            browsers += thread(isDaemon = true) { browser(uri) }
        },
        authorizationEndpoint = "https://accounts.example.test/auth",
        tokenEndpoint = tokenEndpoint,
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
