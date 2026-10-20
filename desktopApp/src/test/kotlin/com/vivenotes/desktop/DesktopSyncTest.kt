package com.vivenotes.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.vivenotes.data.NotesLibrary
import com.vivenotes.data.billing.CheckoutProvider
import com.vivenotes.data.sync.InstallationDetails
import com.vivenotes.data.sync.PaidProvider
import com.vivenotes.data.sync.SyncAccount
import com.vivenotes.data.sync.SyncAccountStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The desktop's account and sync wiring end to end — Android's `SyncAccounts` and foreground
 * scheduler over a real notes database — against a loopback server speaking the sync contract: the
 * change stream, pushes, and revocation. What the window does at close is here too, because the
 * desktop, unlike Android, ends the stream then and gives a final push a bounded wait.
 */
class DesktopSyncTest {

    private val root: File = Files.createTempDirectory("desktop-sync").toFile()
    private val server = FakeSyncServer()

    @AfterTest
    fun tearDown() {
        server.stop()
        root.deleteRecursively()
    }

    @Test
    fun aStoredRegistrationStreamsAndPushesWhatThisDeviceAlreadyHeld() = runBlocking {
        val library = NotesLibrary.open(File(root, "notes"))
        try {
            val notebookId = library.repository.createNotebook("Field Notes")
            register(library)
            val sync = desktopSync(library)

            sync.start()
            withTimeout(10_000) { sync.accounts.streamReady.first { it } }
            withTimeout(10_000) { server.awaitPush() }

            val pushed = server.pushedChanges.map { it["kind"]!!.jsonPrimitive.content to it["id"]!!.jsonPrimitive.content }
            assertTrue("notebook" to notebookId in pushed, "the offline notebook was not pushed: $pushed")
            assertEquals("Bearer $TOKEN", server.authorizations.first())

            sync.stop()
            assertTrue(server.awaitStreamClosed(), "closing must end the change stream")
        } finally {
            library.close()
        }
    }

    /** The stream reports a revocation; the credential that can only fail from now on is deleted. */
    @Test
    fun aRevokedDeviceIsForgottenWhenTheStreamSaysSo() = runBlocking {
        server.revoked = true
        val library = NotesLibrary.open(File(root, "notes"))
        try {
            register(library)
            val sync = desktopSync(library)

            sync.start()
            withTimeout(10_000) { sync.accounts.account.first { it == null } }
            sync.stop()

            // And it stays forgotten on disk: the next launch has no registration to stream with. A
            // copy is read, because DataStore keeps one live store per file and the app's is open.
            val copy = File(root, "after-restart/sync.preferences_pb")
            File(library.directory, "datastore/sync.preferences_pb").copyTo(copy)
            assertNull(withStoreAt(copy) { it.account.first() })
        } finally {
            library.close()
        }
    }

    /**
     * A server that takes a push and never answers must not hold the window open: the final push is
     * given its bounded time, and what it carried stays in the outbox for the next launch.
     */
    @Test
    fun closingIsBoundedWhenTheFinalPushNeverReturns() = runBlocking {
        server.holdPushes = true
        val library = NotesLibrary.open(File(root, "notes"))
        try {
            register(library)
            val sync = desktopSync(library)
            sync.start()
            withTimeout(10_000) { sync.accounts.streamReady.first { it } }
            library.repository.createNotebook("Written just before closing")
            withTimeout(10_000) { server.awaitPushStarted() }

            val started = System.nanoTime()
            sync.stop()
            val elapsed = (System.nanoTime() - started) / 1_000_000

            assertTrue(elapsed < 6_000, "close waited $elapsed ms")
            assertTrue(sync.accounts.hasPendingChanges(), "the unanswered push stays queued")
        } finally {
            server.releasePushes()
            library.close()
        }
    }

    /**
     * Card checkout over the desktop's own wiring: no copy of the desktop came from Google Play, so a
     * managed server that sells by card is offered, Subscribe opens its Checkout page, and coming back
     * to the app has the server read the outcome from Stripe.
     */
    @Test
    fun aManagedServerThatSellsByCardIsSubscribedToFromTheDesktop() = runBlocking {
        server.sellsByCard = true
        val library = NotesLibrary.open(File(root, "notes"))
        try {
            register(library)
            val sync = desktopSync(library)
            val subscription = sync.subscription
            withTimeout(10_000) { subscription.state.first { it.visible && !it.loading } }
            assertEquals(CheckoutProvider.Stripe, subscription.state.value.checkout)
            assertTrue(subscription.state.value.cardManagementAvailable)

            val pages = CopyOnWriteArrayList<String>()
            val opener = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                subscription.browserPages.collect { pages += it }
            }
            subscription.purchase()
            withTimeout(10_000) { while (pages.isEmpty()) delay(10) }
            assertEquals(listOf(server.checkoutUrl), pages)
            assertTrue("POST /v1/billing/stripe/checkout-sessions Bearer $TOKEN" in server.billingCalls)

            server.paidByCard = true
            subscription.onForeground()
            withTimeout(10_000) { subscription.state.first { it.purchaseConfirmed } }
            assertEquals(PaidProvider.Stripe, subscription.state.value.status?.paidProvider)
            assertTrue("POST /v1/billing/stripe/subscriptions/refresh Bearer $TOKEN" in server.billingCalls)

            opener.cancel()
            sync.stop()
        } finally {
            library.close()
        }
    }

    /** A server without a Stripe account leaves the desktop where it was: plans are bought on Android. */
    @Test
    fun aManagedServerWithoutCardBillingOffersNoCheckout() = runBlocking {
        val library = NotesLibrary.open(File(root, "notes"))
        try {
            register(library)
            val sync = desktopSync(library)
            val state = withTimeout(10_000) { sync.subscription.state.first { it.visible && !it.loading } }

            assertEquals(CheckoutProvider.GooglePlay, state.checkout)
            assertEquals(null, state.cardPrice)
            assertEquals(null, state.failure)
            assertTrue("GET /v1/billing/stripe/plan Bearer $TOKEN" in server.billingCalls)
            sync.stop()
        } finally {
            library.close()
        }
    }

    private fun desktopSync(library: NotesLibrary) = DesktopSync(
        library = library,
        profile = DesktopProfile.DEVELOPMENT,
        identity = DesktopSyncIdentity("test (0000)", "Linux", InstallationDetails(), deviceSeed = null),
        openBrowser = {},
        cloudBaseUrl = server.url,
        googleClientId = "",
        googleClientSecret = "",
        finalPushTimeout = 500.milliseconds,
    )

    /** What a sign-in leaves behind: the registration, written to the store this library's app reads. */
    private suspend fun register(library: NotesLibrary) {
        withStore(library) { store ->
            store.setAccount(SyncAccount(server.url, ACCOUNT_ID, DEVICE_ID, TOKEN, "test (0000)"))
        }
    }

    private suspend fun <T> withStore(library: NotesLibrary, block: suspend (SyncAccountStore) -> T): T =
        withStoreAt(File(library.directory, "datastore/sync.preferences_pb"), block)

    private suspend fun <T> withStoreAt(file: File, block: suspend (SyncAccountStore) -> T): T {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            return block(SyncAccountStore(file, null, scope))
        } finally {
            scope.cancel()
            scope.coroutineContext.job.join()
        }
    }

    /** The parts of the sync contract a foreground session uses, on loopback. */
    private class FakeSyncServer {
        val pushedChanges = CopyOnWriteArrayList<JsonObject>()
        val authorizations = CopyOnWriteArrayList<String>()
        @Volatile var revoked = false
        @Volatile var holdPushes = false
        @Volatile var sellsByCard = false
        @Volatile var paidByCard = false
        /** "<method> <path> <Authorization>" of every billing request. */
        val billingCalls = CopyOnWriteArrayList<String>()
        private val pushed = CountDownLatch(1)
        private val pushStarted = CountDownLatch(1)
        private val pushRelease = CountDownLatch(1)
        private val streamClosed = CountDownLatch(1)
        @Volatile private var cursor = 0L
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/v1/changes/watch") { exchange -> watch(exchange) }
            createContext("/v1/changes") { exchange -> push(exchange) }
            createContext("/v1/subscription") { exchange -> billing(exchange) }
            createContext("/v1/billing/stripe") { exchange -> billing(exchange) }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}"
        val checkoutUrl = "https://checkout.stripe.com/c/pay/cs_test_desktop"

        fun awaitPush() = check(pushed.await(10, TimeUnit.SECONDS)) { "no push arrived" }
        fun awaitPushStarted() = check(pushStarted.await(10, TimeUnit.SECONDS)) { "no push started" }
        fun awaitStreamClosed(): Boolean = streamClosed.await(10, TimeUnit.SECONDS)
        fun releasePushes() = pushRelease.countDown()

        fun stop() {
            pushRelease.countDown()
            server.stop(0)
            (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
        }

        private fun watch(exchange: HttpExchange) {
            authorizations += exchange.requestHeaders.getFirst("Authorization").orEmpty()
            if (revoked) {
                reply(exchange, 401, """{"error":"unauthenticated","message":"revoked"}""")
                return
            }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val out = exchange.responseBody
            try {
                out.write("event: ready\ndata: {\"changes\":[],\"cursor\":$cursor,\"hasMore\":false,\"purges\":[]}\n\n".toByteArray())
                out.flush()
                // Silent while nothing changes, as the real stream is; a comment line now and then is
                // how this end notices the client has gone.
                while (true) {
                    Thread.sleep(50)
                    out.write(": keep-alive\n\n".toByteArray())
                    out.flush()
                }
            } catch (_: IOException) {
                streamClosed.countDown()
            } catch (_: InterruptedException) {
            } finally {
                runCatching { exchange.close() }
            }
        }

        private fun push(exchange: HttpExchange) {
            authorizations += exchange.requestHeaders.getFirst("Authorization").orEmpty()
            val request = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            val changes = request["changes"]!!.jsonArray.map { it.jsonObject }
            pushStarted.countDown()
            if (holdPushes) {
                pushRelease.await(30, TimeUnit.SECONDS)
                runCatching { exchange.close() }
                return
            }
            pushedChanges += changes
            cursor++
            val applied = JsonArray(changes.map { change ->
                JsonObject(mapOf("kind" to change["kind"]!!, "id" to change["id"]!!,
                    "version" to kotlinx.serialization.json.JsonPrimitive(1)))
            })
            reply(exchange, 200, JsonObject(mapOf("applied" to applied, "rejected" to JsonArray(emptyList()),
                "cursor" to kotlinx.serialization.json.JsonPrimitive(cursor))).toString())
            pushed.countDown()
        }

        /** The managed server's answers, as `cloudServer-viveNotes` gives them with card billing on or off. */
        private fun billing(exchange: HttpExchange) {
            val path = exchange.requestURI.path
            billingCalls += "${exchange.requestMethod} $path ${exchange.requestHeaders.getFirst("Authorization")}"
            exchange.requestBody.readBytes()
            val unpaid = """{"state":"inactive","autoRenewing":false}"""
            val paid = """{"state":"active","validUntil":"2026-11-20T12:00:00Z","paidState":"active",""" +
                """"paidValidUntil":"2026-11-20T12:00:00Z","paidProvider":"stripe","autoRenewing":true}"""
            when {
                path == "/v1/subscription" -> reply(exchange, 200, if (paidByCard) paid else unpaid)
                !sellsByCard -> reply(exchange, 503, """{"error":"billing_unavailable","message":"card billing is not configured"}""")
                path == "/v1/billing/stripe/plan" ->
                    reply(exchange, 200, """{"unitAmount":499,"currency":"usd","interval":"month","intervalCount":1}""")
                path == "/v1/billing/stripe/checkout-sessions" ->
                    reply(exchange, 200, """{"url":"$checkoutUrl","expiresAt":"2026-10-20T19:00:00Z"}""")
                path == "/v1/billing/stripe/subscriptions/refresh" -> reply(exchange, 200, if (paidByCard) paid else unpaid)
                else -> reply(exchange, 404, """{"error":"not_found","message":"no such route"}""")
            }
        }

        private fun reply(exchange: HttpExchange, status: Int, body: String) {
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private companion object {
        const val ACCOUNT_ID = "10000000-0000-0000-0000-000000000001"
        const val DEVICE_ID = "20000000-0000-0000-0000-000000000002"
        const val TOKEN = "vive_desktop-sync-test"
    }
}
