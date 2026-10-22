// The desktop counterpart of the Android app's `data/sync/GoogleIdentityProvider.kt`. [GoogleIdToken]
// is Android's unchanged, and so is what [SyncAccounts] does with it; only how the ID token is
// obtained differs, because Credential Manager is an Android service.
package com.vivenotes.data.sync

import com.vivenotes.data.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/** What asking for a Google identity produced. */
sealed interface GoogleIdToken {

    /**
     * A signed ID token and its email claim, straight from Google.
     *
     * [email] remains untrusted while this result is in memory. [SyncAccounts] persists and displays
     * it only after the managed server accepts this exact token, having verified its signature,
     * audience, nonce, expiry, and verified-email claim.
     */
    data class Received(val idToken: String, val email: String?) : GoogleIdToken

    /**
     * The person closed the sheet.
     *
     * Its own outcome rather than a failure, because it is not one: the screen goes quietly back to
     * where it was. An error message here would tell somebody who changed their mind that something
     * went wrong.
     */
    data object Dismissed : GoogleIdToken

    data class Rejected(val reason: ConnectFailure) : GoogleIdToken
}

/**
 * Sign in with Google on the desktop: the system browser, and an authorization code returned to a
 * loopback address (RFC 8252), exchanged with PKCE for the ID token.
 *
 * The rules Android's provider keeps are kept here. [nonce] is not optional: the server issued it
 * seconds earlier, Google seals it inside the signed token, and the server checks that the token
 * carries the challenge it issued. Nothing in the token is trusted locally; the server verifies
 * Google's signature and every claim.
 *
 * [clientId] is a Google OAuth client of the **Desktop app** type, in the same Google Cloud project
 * as the Web client the Android app uses. Google issues such clients a "secret" that it documents as
 * not confidential for installed apps, and its token endpoint asks for it, so [clientSecret] is sent
 * when configured. The server accepts the token only if this client id is one of its
 * `GOOGLE_CLIENT_IDS`. An empty [clientId] is a supported state, as on Android: [configured] is
 * false and the screen says sign-in is not available rather than failing in the browser.
 */
class GoogleIdentityProvider(
    private val clientId: String,
    private val clientSecret: String = "",
    /** Opens [URI] in the person's browser; the desktop passes AWT's `Desktop.browse`. */
    private val openBrowser: (URI) -> Unit = {},
    private val authorizationEndpoint: String = "https://accounts.google.com/o/oauth2/v2/auth",
    private val tokenEndpoint: String = "https://oauth2.googleapis.com/token",
    /** How long the browser may take. The server's challenge lives five minutes, so no longer. */
    private val timeoutMillis: Long = 5 * 60 * 1000L,
    /** The page the browser shows once it has handed the answer back. */
    private val returnPage: (signedIn: Boolean) -> String = { "" },
    private val openConnection: (URL) -> HttpURLConnection = { url -> url.openConnection() as HttpURLConnection },
) {

    /** False when this build has no Google client id, which is a state the screen shows rather than hides. */
    val configured: Boolean get() = clientId.isNotBlank()

    private val page = MutableStateFlow<URI?>(null)

    /**
     * Google's page while a sign-in waits for the browser to come back with its answer, else null.
     *
     * The screen offers it to copy into another browser, for when the default one did not open it
     * or Google showed an error there.
     */
    val browserPage: StateFlow<URI?> = page.asStateFlow()

    private val waiting = AtomicReference<BrowserWait?>(null)

    /**
     * Ends the wait for the browser as though the person had closed Google's page, so that a new
     * sign-in can start. False when no sign-in is waiting, which includes one whose answer has
     * already arrived: from there it is exchanging a code, and finishes rather than being dropped.
     */
    fun abandonBrowserSignIn(): Boolean = waiting.get()?.abandon() == true

    /**
     * Opens Google's sign-in in the browser and returns the ID token it produced.
     *
     * Waits on a loopback socket only this process listens on, for one redirect carrying the
     * `state` it sent; anything else that connects is answered and ignored.
     */
    suspend fun requestIdToken(nonce: String): GoogleIdToken {
        if (!configured) return GoogleIdToken.Rejected(ConnectFailure.GoogleNotConfigured)

        val verifier = randomToken()
        val state = randomToken()
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        return withContext(Dispatchers.IO) {
            ServerSocket(0, 8, InetAddress.getLoopbackAddress()).use { listener ->
                val redirectUri = "http://127.0.0.1:${listener.localPort}$CALLBACK_PATH"
                val authorization = URI(
                    authorizationEndpoint + "?" + form(
                        "client_id" to clientId,
                        "redirect_uri" to redirectUri,
                        "response_type" to "code",
                        "scope" to "openid email",
                        "state" to state,
                        "nonce" to nonce,
                        "code_challenge" to challenge,
                        "code_challenge_method" to "S256",
                        "prompt" to "select_account",
                    ),
                )
                val wait = BrowserWait()
                waiting.set(wait)
                page.value = authorization
                val answer = try {
                    try {
                        openBrowser(authorization)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Log.w(TAG, "No browser to sign in with", failure)
                        return@withContext GoogleIdToken.Rejected(ConnectFailure.GoogleNotConfigured)
                    }
                    withTimeoutOrNull(timeoutMillis) { awaitRedirect(listener, state, wait) }
                } finally {
                    // However the wait ended, the page is no longer one to offer, and a code being
                    // exchanged is no longer a sign-in to abandon.
                    if (waiting.compareAndSet(wait, null)) page.value = null
                } ?: return@withContext GoogleIdToken.Dismissed
                when (answer) {
                    RedirectAnswer.Denied, RedirectAnswer.Abandoned -> GoogleIdToken.Dismissed
                    is RedirectAnswer.Failed -> {
                        Log.w(TAG, "Google answered the sign-in with ${answer.error}")
                        GoogleIdToken.Rejected(ConnectFailure.GoogleNotConfigured)
                    }
                    is RedirectAnswer.Code -> exchange(answer.code, verifier, redirectUri)
                }
            }
        }
    }

    /**
     * Serves the loopback address until the redirect with [state] arrives.
     *
     * Accepts with a short timeout so cancellation — the person leaving, or [timeoutMillis] — and
     * [abandonBrowserSignIn] are noticed between connections rather than only when the browser next
     * speaks.
     */
    private suspend fun awaitRedirect(listener: ServerSocket, state: String, wait: BrowserWait): RedirectAnswer {
        listener.soTimeout = ACCEPT_POLL_MILLIS
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (wait.abandoned) return RedirectAnswer.Abandoned
            val socket = try {
                listener.accept()
            } catch (_: SocketTimeoutException) {
                continue
            }
            val answer = socket.use { answer(it, state, wait) }
            if (answer != null) return answer
        }
    }

    /** Reads one request and answers it. Null unless it was the redirect this sign-in is waiting for. */
    private fun answer(socket: Socket, state: String, wait: BrowserWait): RedirectAnswer? {
        socket.soTimeout = REQUEST_READ_MILLIS
        val requestLine = runCatching {
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            reader.readLine().also {
                // The headers are read before answering: a browser whose request is cut off shows a
                // reset connection instead of the page. A redirect carries no body.
                repeat(MAX_HEADER_LINES) { if (reader.readLine().isNullOrEmpty()) return@also }
            }
        }.getOrNull().orEmpty()
        val target = requestLine.split(' ').getOrNull(1).orEmpty()
        val query = runCatching { URI(target) }.getOrNull()
        val parameters = query?.rawQuery?.let(::parseForm).orEmpty()
        val ours = requestLine.startsWith("GET ") && query?.rawPath == CALLBACK_PATH &&
            parameters["state"] == state

        val result = when {
            !ours -> null
            parameters["error"] == "access_denied" -> RedirectAnswer.Denied
            parameters["error"] != null -> RedirectAnswer.Failed(parameters.getValue("error"))
            parameters["code"].isNullOrBlank() -> RedirectAnswer.Failed("missing_code")
            else -> RedirectAnswer.Code(parameters.getValue("code"))
        }?.let { if (wait.claim()) it else RedirectAnswer.Abandoned }
        val (status, body) = if (result != null) {
            "200 OK" to returnPage(result is RedirectAnswer.Code)
        } else {
            "404 Not Found" to ""
        }
        runCatching {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            socket.getOutputStream().apply {
                write(
                    ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                        .toByteArray(Charsets.ISO_8859_1),
                )
                write(bytes)
                flush()
            }
        }
        return result
    }

    /** Trades the authorization code for the ID token, proving with [verifier] that this is who asked. */
    private fun exchange(code: String, verifier: String, redirectUri: String): GoogleIdToken {
        val body = form(
            *listOfNotNull(
                "code" to code,
                "client_id" to clientId,
                clientSecret.takeIf { it.isNotBlank() }?.let { "client_secret" to it },
                "redirect_uri" to redirectUri,
                "grant_type" to "authorization_code",
                "code_verifier" to verifier,
            ).toTypedArray(),
        ).toByteArray(StandardCharsets.UTF_8)

        val connection = try {
            openConnection(URI(tokenEndpoint).toURL()).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TOKEN_TIMEOUT_MILLIS
                readTimeout = TOKEN_TIMEOUT_MILLIS
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
            }
        } catch (failure: IOException) {
            return GoogleIdToken.Rejected(ConnectFailure.Unreachable)
        }
        return try {
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().decodeToString() }
                .orEmpty()
            if (status !in 200..299) {
                // `invalid_client` is a client id or secret Google does not recognise: configuration.
                Log.w(TAG, "Google's token endpoint answered $status: ${errorCode(text)}")
                return GoogleIdToken.Rejected(ConnectFailure.GoogleNotConfigured)
            }
            val idToken = (runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject)
                ?.get("id_token")
                ?.let { (it as? JsonPrimitive)?.content }
                ?.takeIf { it.isNotBlank() }
                ?: return GoogleIdToken.Rejected(ConnectFailure.GoogleNotConfigured)
            GoogleIdToken.Received(idToken = idToken, email = unverifiedEmail(idToken))
        } catch (failure: IOException) {
            Log.w(TAG, "Google's token endpoint could not be reached", failure)
            GoogleIdToken.Rejected(ConnectFailure.Unreachable)
        } finally {
            connection.disconnect()
        }
    }

    private sealed interface RedirectAnswer {
        data class Code(val code: String) : RedirectAnswer
        data object Denied : RedirectAnswer
        data class Failed(val error: String) : RedirectAnswer

        /** [abandonBrowserSignIn] ended the wait; a redirect arriving after it is turned away. */
        data object Abandoned : RedirectAnswer
    }

    /**
     * One sign-in's wait for the browser. Its redirect and [abandonBrowserSignIn] race to end it, and
     * whichever claims it first decides how the sign-in ends.
     */
    private class BrowserWait {
        private val outcome = AtomicReference(Outcome.Waiting)

        val abandoned: Boolean get() = outcome.get() == Outcome.Abandoned

        fun claim(): Boolean = outcome.compareAndSet(Outcome.Waiting, Outcome.Answered)

        fun abandon(): Boolean = outcome.compareAndSet(Outcome.Waiting, Outcome.Abandoned)

        private enum class Outcome { Waiting, Answered, Abandoned }
    }

    private companion object {
        const val TAG = "GoogleIdentity"
        const val CALLBACK_PATH = "/oauth2/callback"
        const val ACCEPT_POLL_MILLIS = 250
        const val REQUEST_READ_MILLIS = 5_000
        const val MAX_HEADER_LINES = 100
        const val TOKEN_TIMEOUT_MILLIS = 20_000
    }
}

/**
 * The token's email claim, read without checking the signature. Shown only after the server has
 * verified the same token; see [GoogleIdToken.Received].
 */
internal fun unverifiedEmail(idToken: String): String? = runCatching {
    val payload = idToken.split('.')[1]
    val claims = Json.parseToJsonElement(Base64.getUrlDecoder().decode(payload).decodeToString()) as JsonObject
    (claims["email"] as? JsonPrimitive)?.content
}.getOrNull()

private fun errorCode(body: String): String =
    ((runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject)?.get("error") as? JsonPrimitive)
        ?.content ?: "no error code"

private val random = SecureRandom()

private fun randomToken(): String = ByteArray(32).also(random::nextBytes).let(::base64Url)

private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private fun form(vararg values: Pair<String, String>): String = values.joinToString("&") { (key, value) ->
    URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)
}

private fun parseForm(query: String): Map<String, String> = query.split('&').mapNotNull { part ->
    val separator = part.indexOf('=')
    if (separator <= 0) return@mapNotNull null
    URLDecoder.decode(part.substring(0, separator), StandardCharsets.UTF_8) to
        URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8)
}.toMap()
