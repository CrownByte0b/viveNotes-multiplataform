package com.vivenotes.desktop

import com.sun.net.httpserver.HttpServer
import com.vivenotes.data.NotesLibrary
import com.vivenotes.ui.account.AccountSession
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopSyncCoordinatorTest {
    @Test
    fun serverRevocationClearsRestoredCredentialAndStopsSync() = runBlocking {
        val root = Files.createTempDirectory("desktop-sync-revoked").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/changes/watch") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            val body = "event: ready\ndata: {\"changes\":[],\"purges\":[],\"cursor\":0,\"hasMore\":false}\n\n" +
                "event: revoked\ndata: {}\n\n"
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            NotesLibrary.open(root).use { library ->
                var cleared = false
                val saved = StoredAccountCredential(AccountSession(
                    "http://127.0.0.1:${server.address.port}", "a@b.com", "a", "d", "active",
                    persisted = true), "vive_secret")
                val accounts = DesktopAccountService(credentialStore = object : AccountCredentialStore {
                    override fun load() = saved
                    override fun save(credential: StoredAccountCredential) = true
                    override fun clear() { cleared = true }
                }, syncEngine = library.synchronizer)
                val coordinator = DesktopSyncCoordinator(scope, accounts, library.synchronizer)
                coordinator.start()
                try {
                    withTimeout(5_000) { while (accounts.session.value != null) delay(20) }
                    assertTrue(cleared)
                } finally { coordinator.stop() }
            }
        } finally {
            scope.cancel()
            server.stop(0)
            root.deleteRecursively()
        }
    }

    @Test
    fun aRestoredLocalOutboxUploadsAfterTheLiveStreamIsReady() = runBlocking {
        val root = Files.createTempDirectory("desktop-sync").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pushes = AtomicInteger()
        val watches = AtomicInteger()
        server.createContext("/") { exchange ->
            val request = exchange.requestURI.path
            val payload = when (request) {
                "/v1/changes/watch" -> {
                    assertEquals("Bearer vive_secret", exchange.requestHeaders.getFirst("Authorization"))
                    watches.incrementAndGet()
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    "event: ready\ndata: {\"changes\":[],\"purges\":[],\"cursor\":0,\"hasMore\":false}\n\n"
                }
                "/v1/changes" -> {
                    assertTrue(watches.get() > 0, "upload began before the stream's catch-up")
                    val changes = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText())
                        .jsonObject.getValue("changes").jsonArray
                    pushes.incrementAndGet()
                    val applied = changes.joinToString(",") { change ->
                        val row = change.jsonObject
                        """{"kind":"${row.getValue("kind").jsonPrimitive.content}","id":"${row.getValue("id").jsonPrimitive.content}","version":1}"""
                    }
                    """{"applied":[$applied],"rejected":[],"cursor":1}"""
                }
                else -> error("Unexpected path: $request")
            }
            val bytes = payload.toByteArray()
            exchange.sendResponseHeaders(200, if (request == "/v1/changes/watch") 0 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            NotesLibrary.open(root).use { library ->
                library.repository.createNotebook("Offline notebook")
                val saved = StoredAccountCredential(AccountSession(
                    "http://127.0.0.1:${server.address.port}", "a@b.com", "a", "d", "active",
                    persisted = true), "vive_secret")
                val accounts = DesktopAccountService(credentialStore = object : AccountCredentialStore {
                    override fun load() = saved
                    override fun save(credential: StoredAccountCredential) = true
                    override fun clear() = Unit
                })
                val coordinator = DesktopSyncCoordinator(scope, accounts, library.synchronizer)
                coordinator.start()
                try {
                    withTimeout(5_000) {
                        while (pushes.get() == 0 || library.synchronizer.hasPendingChanges()) delay(20)
                    }
                    assertTrue(watches.get() > 0)
                    assertTrue(pushes.get() > 0)
                } finally { coordinator.stop() }
            }
        } finally {
            scope.cancel()
            server.stop(0)
            root.deleteRecursively()
        }
    }
}
