package com.vivenotes.desktop

import com.vivenotes.localization.Strings
import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.minutes

internal data class GoogleIdToken(val idToken: String, val email: String)

internal fun interface GoogleIdTokenProvider {
    /** Null means the person dismissed Google's browser flow. */
    suspend fun requestIdToken(nonce: String): GoogleIdToken?
}

/** Desktop OAuth authorization code flow with PKCE and a short-lived loopback listener. */
internal class DesktopGoogleIdentity(
    private val clientId: String,
    private val openBrowser: (URI) -> Unit = ::browseDesktop,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build(),
    private val authorizationEndpoint: URI = URI("https://accounts.google.com/o/oauth2/v2/auth"),
    private val tokenEndpoint: URI = URI("https://oauth2.googleapis.com/token"),
) : GoogleIdTokenProvider {
    override suspend fun requestIdToken(nonce: String): GoogleIdToken? {
        require(clientId.isNotBlank()) { Strings.google_desktop_client_id_is_missing }
        val verifier = randomUrlToken()
        val state = randomUrlToken()
        val challenge = urlBase64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val listener = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val result = CompletableDeferred<String?>()
        val redirect = "http://127.0.0.1:${listener.address.port}/oauth2/callback"
        listener.createContext("/oauth2/callback") { exchange ->
            val params = parseQuery(exchange.requestURI.rawQuery.orEmpty())
            val validState = params["state"] == state
            val code = params["code"]
            val accepted = validState && (!code.isNullOrBlank() || params["error"] == "access_denied")
            val message = if (accepted) Strings.oauth_return_to_app else Strings.oauth_invalid_response
            val escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val html = "<html><body>$escaped</body></html>"
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(if (accepted) 200 else 400, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
            if (accepted) result.complete(code)
        }
        listener.start()
        try {
            val authorizationUrl = URI(authorizationEndpoint.toString() + "?" + form(mapOf(
                "client_id" to clientId,
                "redirect_uri" to redirect,
                "response_type" to "code",
                "scope" to "openid email",
                "state" to state,
                "nonce" to nonce,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
            )))
            openBrowser(authorizationUrl)
            val code = withTimeout(5.minutes) { result.await() } ?: return null
            val token = withContext(Dispatchers.IO) { exchangeCode(code, verifier, redirect) }
            val payload = tokenPayload(token)
            require(payload["nonce"]?.jsonPrimitive?.content == nonce) { Strings.google_nonce_did_not_match }
            require(payload["aud"]?.jsonPrimitive?.content == clientId) { Strings.google_client_id_did_not_match }
            require(payload["email_verified"]?.jsonPrimitive?.boolean == true) { Strings.google_email_is_not_verified }
            val email = payload["email"]?.jsonPrimitive?.content
                ?: error(Strings.google_did_not_return_an_email_address)
            return GoogleIdToken(token, email)
        } finally {
            listener.stop(0)
        }
    }

    private fun exchangeCode(code: String, verifier: String, redirect: String): String {
        val request = HttpRequest.newBuilder(tokenEndpoint).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form(mapOf(
                "code" to code,
                "client_id" to clientId,
                "code_verifier" to verifier,
                "redirect_uri" to redirect,
                "grant_type" to "authorization_code",
            )))).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() == 200) { Strings.google_sign_in_could_not_be_completed }
        val json = Json.parseToJsonElement(response.body()) as? JsonObject
            ?: error(Strings.invalid_google_token_response)
        return json["id_token"]?.jsonPrimitive?.content
            ?: error(Strings.google_did_not_return_an_id_token)
    }
}

internal fun browseDesktop(uri: URI) {
    require(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Strings.a_system_browser_is_needed_for_this_action
    }
    Desktop.getDesktop().browse(uri)
}

private fun randomUrlToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return urlBase64(bytes)
}

private fun urlBase64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private fun form(values: Map<String, String>): String = values.entries.joinToString("&") { (key, value) ->
    "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
}

private fun parseQuery(query: String): Map<String, String> = query.split('&').mapNotNull { part ->
    val index = part.indexOf('=')
    if (index <= 0) null else URLDecoder.decode(part.substring(0, index), StandardCharsets.UTF_8) to
        URLDecoder.decode(part.substring(index + 1), StandardCharsets.UTF_8)
}.toMap()

private fun tokenPayload(token: String): JsonObject {
    val encoded = token.split('.').getOrNull(1) ?: error(Strings.invalid_google_id_token)
    val bytes = Base64.getUrlDecoder().decode(encoded)
    return Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        ?: error(Strings.invalid_google_id_token)
}
