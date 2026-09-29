package com.vivenotes.desktop

import com.sun.net.httpserver.HttpServer
import com.vivenotes.diagnostics.DebugLog
import java.net.InetSocketAddress
import java.nio.file.Files
import com.vivenotes.ui.account.GoogleSignInOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAccountServiceTest {
    @Test
    fun createRegistersDeviceAndReadsMembershipUsingBearerToken() = runBlocking {
        val paths = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            paths += "${exchange.requestMethod} ${exchange.requestURI.path}"
            val body = exchange.requestBody.bufferedReader().readText()
            val reply = when (exchange.requestURI.path) {
                "/v1/accounts" -> {
                    assertEquals("owner@example.com", Json.parseToJsonElement(body).jsonObject["email"]?.jsonPrimitive?.content)
                    201 to """{"accountId":"a"}"""
                }
                "/v1/devices" -> {
                    val fields = Json.parseToJsonElement(body).jsonObject
                    assertEquals("owner@example.com", fields["email"]?.jsonPrimitive?.content)
                    assertEquals("stable-id", fields["installationId"]?.jsonPrimitive?.content)
                    assertTrue(fields["name"]!!.jsonPrimitive.content.isNotBlank())
                    201 to """{"accountId":"a","deviceId":"d","token":"vive_secret"}"""
                }
                "/v1/subscription" -> {
                    assertEquals("Bearer vive_secret", exchange.requestHeaders.getFirst("Authorization"))
                    200 to """{"state":"inactive","autoRenewing":false}"""
                }
                else -> {
                    assertEquals("DELETE", exchange.requestMethod)
                    assertEquals("Bearer vive_secret", exchange.requestHeaders.getFirst("Authorization"))
                    204 to ""
                }
            }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(reply.first,
                if (reply.first == 204) -1 else reply.second.toByteArray().size.toLong())
            exchange.responseBody.use { if (reply.first != 204) it.write(reply.second.toByteArray()) }
        }
        server.start()
        try {
            val logs = mutableListOf<String>()
            val service = DesktopAccountService(DebugLog(true, logs::add), installationId = "stable-id")
            service.connect("http://127.0.0.1:${server.address.port}/", " OWNER@EXAMPLE.COM ", "password123", true)
            assertEquals(listOf("POST /v1/accounts", "POST /v1/devices", "GET /v1/subscription"), paths)
            assertEquals("inactive", service.session.value?.membership)
            assertEquals("owner@example.com", service.session.value?.email)
            assertTrue(logs.none { "password123" in it || "vive_secret" in it })
            service.disconnect()
            assertNull(service.session.value)
            assertEquals("DELETE /v1/devices/d", paths.last())
        } finally { server.stop(0) }
    }

    @Test
    fun rejectedCredentialsDoNotConnect() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/devices") { exchange ->
            val body = """{"error":"invalid_credentials","message":"email or password is incorrect"}"""
            exchange.sendResponseHeaders(401, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val service = DesktopAccountService()
            val error = assertFailsWith<AccountApiException> {
                service.connect("http://127.0.0.1:${server.address.port}", "a@b.com", "wrong", false)
            }
            assertEquals(401, error.status)
            assertNull(service.session.value)
        } finally { server.stop(0) }
    }

    @Test
    fun failedRevocationKeepsConnectionUntilExplicitForget() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.close()
            val reply = when {
                exchange.requestMethod == "DELETE" -> 503 to """{"error":"unavailable","message":"try again"}"""
                exchange.requestURI.path == "/v1/devices" ->
                    201 to """{"accountId":"a","deviceId":"d","token":"vive_secret"}"""
                else -> 200 to """{"state":"active","autoRenewing":false}"""
            }
            exchange.sendResponseHeaders(reply.first, reply.second.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(reply.second.toByteArray()) }
        }
        server.start()
        try {
            val service = DesktopAccountService()
            service.connect("http://127.0.0.1:${server.address.port}", "a@b.com", "secret", false)
            assertEquals(503, assertFailsWith<AccountApiException> { service.disconnect() }.status)
            assertEquals("a", service.session.value?.accountId)
            service.forget()
            assertNull(service.session.value)
        } finally { server.stop(0) }
    }

    @Test
    fun serverUrlRejectsCredentialsAndPaths() {
        assertFailsWith<IllegalArgumentException> { accountServerUrl("https://user:secret@example.com") }
        assertFailsWith<IllegalArgumentException> { accountServerUrl("https://example.com/wrong") }
        assertFailsWith<IllegalArgumentException> { accountServerUrl("http://example.com") }
        assertEquals("https://example.com", accountServerUrl("https://example.com/"))
    }

    @Test
    fun installationIdSurvivesRestart() {
        val root = Files.createTempDirectory("vivenotes-account-id").toFile()
        val file = root.resolve("config/account-installation-id")
        try {
            val first = accountInstallationId(file)
            assertEquals(first, accountInstallationId(file))
            assertEquals(first, file.readText())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun managedRecoveryCouponAndDeviceOperationsUseDocumentedEndpoints() = runBlocking {
        val deviceId = "11111111-2222-4333-8444-555555555555"
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI.path}"
            val body = exchange.requestBody.bufferedReader().readText()
            val reply = when (exchange.requestURI.path) {
                "/v1/devices" -> if (exchange.requestMethod == "POST")
                    201 to """{"accountId":"a","deviceId":"$deviceId","token":"vive_secret"}"""
                else 200 to """{"devices":[{"deviceId":"$deviceId","name":"Desktop","platform":"Linux","createdAt":"2026-01-01T00:00:00Z","lastPulledSeq":0}]}"""
                "/v1/subscription" -> 200 to """{"state":"active","paidState":"active","autoRenewing":true,"validUntil":"2026-12-31T00:00:00Z"}"""
                "/v1/auth/password/reset-requests" -> 202 to """{"message":"if that email is registered, recovery instructions will be sent"}"""
                "/v1/auth/password/resets" -> {
                    assertEquals("01234567", Json.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content)
                    204 to ""
                }
                "/v1/coupons/redeem" -> 200 to """{"code":"WELCOME","monthsGranted":2,"redeemedAt":"2026-01-01T00:00:00Z","validUntil":"2026-03-01T00:00:00Z"}"""
                else -> 204 to ""
            }
            exchange.sendResponseHeaders(reply.first,
                if (reply.first == 204) -1 else reply.second.toByteArray().size.toLong())
            exchange.responseBody.use { if (reply.first != 204) it.write(reply.second.toByteArray()) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val service = DesktopAccountService(managedServerUrl = base)
            service.connectManaged("owner@example.com", "password123", false)
            assertTrue(service.session.value!!.managed)
            service.requestPasswordReset("owner@example.com")
            service.completePasswordReset("owner@example.com", "01234567", "newpassword")
            assertEquals(2, service.redeemCoupon("WELCOME").monthsGranted)
            assertEquals("active", service.refreshSubscription().paidState)
            assertEquals("Desktop", service.listDevices().single().name)
            service.renameDevice(deviceId, "Laptop")
            service.revokeDevice(deviceId)
            assertNull(service.session.value)
            assertTrue(requests.contains("POST /v1/auth/password/reset-requests"))
            assertTrue(requests.contains("POST /v1/auth/password/resets"))
            assertTrue(requests.contains("POST /v1/coupons/redeem"))
            assertTrue(requests.contains("PATCH /v1/devices/$deviceId"))
            assertTrue(requests.contains("DELETE /v1/devices/$deviceId"))
        } finally { server.stop(0) }
    }

    @Test
    fun googleLinkReusesChallengeAndGetsDistinctIdempotencyKey() = runBlocking {
        val challengeId = "11111111-2222-4333-8444-555555555555"
        val seen = mutableListOf<Pair<String, kotlinx.serialization.json.JsonObject>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = exchange.requestBody.bufferedReader().readText()
            if (body.isNotBlank()) seen += path to Json.parseToJsonElement(body).jsonObject
            val reply = when (path) {
                "/v1/auth/google/challenges" -> 201 to """{"challengeId":"$challengeId","nonce":"${"n".repeat(43)}","expiresAt":"2026-12-31T00:00:00Z"}"""
                "/v1/auth/google" -> 409 to """{"error":"account_link_required","message":"Link required"}"""
                "/v1/auth/google/link" -> 200 to """{"accountId":"a","deviceId":"d","token":"vive_secret","tokenExpiresAt":"2026-12-31T00:00:00Z","createdAccount":false}"""
                else -> 200 to """{"state":"active","autoRenewing":false}"""
            }
            exchange.sendResponseHeaders(reply.first, reply.second.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(reply.second.toByteArray()) }
        }
        server.start()
        try {
            val identity = GoogleIdTokenProvider { nonce ->
                assertEquals("n".repeat(43), nonce)
                GoogleIdToken("google-id-token", "owner@example.com")
            }
            val service = DesktopAccountService(managedServerUrl = "http://127.0.0.1:${server.address.port}",
                googleIdentity = identity)
            assertEquals(GoogleSignInOutcome.LinkRequired, service.signInWithGoogle())
            service.linkGoogleAccount("owner@example.com", "password")
            assertEquals("owner@example.com", service.session.value?.email)
            val signIn = seen.first { it.first == "/v1/auth/google" }.second
            val link = seen.first { it.first == "/v1/auth/google/link" }.second
            assertEquals(signIn["challengeId"], link["challengeId"])
            assertEquals(signIn["idToken"], link["idToken"])
            assertTrue(signIn["idempotencyKey"] != link["idempotencyKey"])
        } finally { server.stop(0) }
    }
}
