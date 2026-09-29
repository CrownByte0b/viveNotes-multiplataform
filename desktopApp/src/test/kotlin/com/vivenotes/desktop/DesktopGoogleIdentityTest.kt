package com.vivenotes.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopGoogleIdentityTest {
    @Test
    fun browserCodeFlowChecksStateUsesPkceAndReturnsNonceBoundIdToken() = runBlocking {
        val expectedNonce = "n".repeat(43)
        val clientId = "desktop.apps.googleusercontent.com"
        val token = unsignedTestIdToken(clientId, expectedNonce, "owner@example.com")
        var tokenForm: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/token") { exchange ->
            tokenForm = exchange.requestBody.bufferedReader().readText()
            val reply = """{"id_token":"$token","access_token":"discard-me"}"""
            exchange.sendResponseHeaders(200, reply.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(reply.toByteArray()) }
        }
        server.start()
        try {
            var authParams: Map<String, String>? = null
            val http = HttpClient.newHttpClient()
            val provider = DesktopGoogleIdentity(clientId,
                openBrowser = { uri ->
                    authParams = params(uri.rawQuery)
                    val redirect = URI(authParams!!["redirect_uri"]!!)
                    val bad = URI("$redirect?state=wrong&code=stolen")
                    assertEquals(400, http.send(HttpRequest.newBuilder(bad).GET().build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode())
                    val good = URI("$redirect?state=${authParams!!["state"]}&code=approved")
                    assertEquals(200, http.send(HttpRequest.newBuilder(good).GET().build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode())
                }, tokenEndpoint = URI("http://127.0.0.1:${server.address.port}/token"))
            val result = assertNotNull(provider.requestIdToken(expectedNonce))
            assertEquals("owner@example.com", result.email)
            assertEquals(token, result.idToken)
            assertEquals(expectedNonce, authParams!!["nonce"])
            assertEquals("openid email", authParams!!["scope"])
            val verifier = params(tokenForm)["code_verifier"]!!
            val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            assertEquals(expectedChallenge, authParams!!["code_challenge"])
            assertTrue(verifier.length in 43..128)
        } finally { server.stop(0) }
    }

    private fun unsignedTestIdToken(audience: String, nonce: String, email: String): String {
        fun encode(value: String) = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray())
        return encode("""{"alg":"none"}""") + "." + encode(
            """{"aud":"$audience","nonce":"$nonce","email":"$email","email_verified":true}""") + ".signature"
    }

    private fun params(query: String?): Map<String, String> = query.orEmpty().split('&').mapNotNull { part ->
        val cut = part.indexOf('=')
        if (cut < 1) null else URLDecoder.decode(part.substring(0, cut), StandardCharsets.UTF_8) to
            URLDecoder.decode(part.substring(cut + 1), StandardCharsets.UTF_8)
    }.toMap()
}
