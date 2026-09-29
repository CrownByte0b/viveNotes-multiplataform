package com.vivenotes.desktop

import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.ui.account.AccountService
import com.vivenotes.ui.account.AccountSession
import com.vivenotes.ui.account.AccountProvider
import com.vivenotes.ui.account.AccountSubscription
import com.vivenotes.ui.account.AccountCoupon
import com.vivenotes.ui.account.AccountDevice
import com.vivenotes.ui.account.AccountRequestException
import com.vivenotes.ui.account.GoogleSignInOutcome
import com.vivenotes.ui.account.AccountSyncStatus
import com.vivenotes.data.sync.HierarchySync
import com.vivenotes.data.sync.SyncAccount
import com.vivenotes.data.sync.SyncRunResult
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.IOException
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Account and device endpoints from the cloud OpenAPI contract. */
internal class DesktopAccountService(
    private val log: DebugLog = DebugLog(),
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build(),
    private val installationId: String = UUID.randomUUID().toString(),
    override val managedServerUrl: String = "https://sync.vivenotes.net",
    private val googleIdentity: GoogleIdTokenProvider? = null,
    private val openBrowser: (URI) -> Unit = ::browseDesktop,
    private val credentialStore: AccountCredentialStore? = null,
    private val syncEngine: HierarchySync? = null,
) : AccountService {
    private val current = MutableStateFlow<AccountSession?>(null)
    override val session: StateFlow<AccountSession?> = current
    private val mutableSyncStatus = MutableStateFlow(AccountSyncStatus())
    override val syncStatus: StateFlow<AccountSyncStatus> = mutableSyncStatus
    internal var flushBeforeSync: suspend () -> Unit = {}
    internal var afterSync: () -> Unit = {}
    private var token: String? = null
    private data class PendingGoogleLink(val challengeId: String, val idToken: String, val email: String)
    private var pendingGoogleLink: PendingGoogleLink? = null
    override val googleAvailable: Boolean get() = googleIdentity != null

    init {
        credentialStore?.load()?.let { stored ->
            token = stored.token
            current.value = stored.session
            log.event("account") { "saved connection restored" }
        }
    }

    override suspend fun connect(serverUrl: String, email: String, password: String, create: Boolean) {
        val base = accountServerUrl(serverUrl)
        val normalizedEmail = email.trim().lowercase()
        require(normalizedEmail.isNotBlank() && normalizedEmail.length <= 320 && '@' in normalizedEmail) {
            "Enter a valid email address."
        }
        require(password.length in (if (create) 8 else 1)..1024) {
            if (create) "Password must contain 8 to 1024 characters." else "Enter a password."
        }
        withContext(Dispatchers.IO) {
            try {
                val createdId = if (create) request(base, "/v1/accounts", "POST", buildJsonObject {
                    put("email", normalizedEmail); put("password", password)
                }).requiredString("accountId") else null
                val registration = request(base, "/v1/devices", "POST", buildJsonObject {
                    put("email", normalizedEmail)
                    put("password", password)
                    put("installationId", installationId)
                    put("name", desktopDeviceName())
                    put("platform", System.getProperty("os.name").take(64))
                })
                val newToken = registration.requiredString("token")
                val accountId = registration.requiredString("accountId")
                val deviceId = registration.requiredString("deviceId")
                require(createdId == null || createdId == accountId) { "Server returned mismatched account IDs." }
                require(newToken.startsWith("vive_") && newToken.length > 5) { "Invalid device token." }
                adopt(newToken, AccountSession(base, normalizedEmail, accountId, deviceId, "unknown",
                    managed = base == accountServerUrl(managedServerUrl)))
                try { refreshSubscription() } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* The account stays connected; the screen reports refresh failure. */ }
                log.event("account") { "connected; membership=${current.value?.membership}" }
            } catch (error: Exception) {
                log.event("account") { "connection failed (${error::class.simpleName})" }
                throw error
            }
        }
    }

    override suspend fun requestPasswordReset(email: String) = withContext(Dispatchers.IO) {
        request(accountServerUrl(managedServerUrl), "/v1/auth/password/reset-requests", "POST", buildJsonObject {
            put("email", email.trim().lowercase())
        })
        log.event("account") { "password recovery requested" }
        Unit
    }

    override suspend fun completePasswordReset(email: String, code: String, password: String) =
        withContext(Dispatchers.IO) {
            require(code.length == 8 && code.all(Char::isDigit)) { "Enter the 8-digit recovery code." }
            require(password.length in 8..1024) { "Password must contain 8 to 1024 characters." }
            request(accountServerUrl(managedServerUrl), "/v1/auth/password/resets", "POST", buildJsonObject {
                put("email", email.trim().lowercase()); put("code", code); put("password", password)
            })
            log.event("account") { "password changed" }
            Unit
        }

    override suspend fun signInWithGoogle(): GoogleSignInOutcome {
        val identity = googleIdentity ?: error("Google sign-in is not configured.")
        pendingGoogleLink = null
        val base = accountServerUrl(managedServerUrl)
        val challenge = withContext(Dispatchers.IO) {
            request(base, "/v1/auth/google/challenges", "POST")
        }
        val challengeId = challenge.requiredString("challengeId")
        val googleToken = identity.requestIdToken(challenge.requiredString("nonce"))
            ?: return GoogleSignInOutcome.Dismissed
        val body = googleBody(challengeId, googleToken.idToken)
        val result = try {
            withContext(Dispatchers.IO) { googleRequest(base, "/v1/auth/google", body) }
        } catch (error: AccountApiException) {
            if (error.code == "account_link_required") {
                pendingGoogleLink = PendingGoogleLink(challengeId, googleToken.idToken, googleToken.email)
                return GoogleSignInOutcome.LinkRequired
            }
            throw error
        }
        adoptGoogle(base, result, googleToken.email)
        return GoogleSignInOutcome.Connected
    }

    override suspend fun linkGoogleAccount(email: String, password: String) {
        val pending = pendingGoogleLink ?: error("Start Google sign-in again to link this account.")
        val base = accountServerUrl(managedServerUrl)
        val body = googleBody(pending.challengeId, pending.idToken, email.trim().lowercase(), password)
        val result = withContext(Dispatchers.IO) { googleRequest(base, "/v1/auth/google/link", body) }
        adoptGoogle(base, result, pending.email)
        pendingGoogleLink = null
    }

    override fun cancelGoogleLink() { pendingGoogleLink = null }

    private fun googleBody(challengeId: String, idToken: String, email: String? = null,
        password: String? = null): JsonObject = buildJsonObject {
        put("challengeId", challengeId)
        put("idToken", idToken)
        put("idempotencyKey", UUID.randomUUID().toString())
        put("device", buildJsonObject {
            put("installationId", installationId)
            put("name", desktopDeviceName())
            put("platform", System.getProperty("os.name").take(64))
        })
        if (email != null) put("email", email)
        if (password != null) put("password", password)
    }

    /** Replays the identical attempt if the response is lost; the server keys it by idempotencyKey. */
    private fun googleRequest(base: String, path: String, body: JsonObject): JsonObject = try {
        request(base, path, "POST", body)
    } catch (_: IOException) {
        request(base, path, "POST", body)
    }

    private suspend fun adoptGoogle(base: String, response: JsonObject, email: String) {
        val newToken = response.requiredString("token")
        val accountId = response.requiredString("accountId")
        val deviceId = response.requiredString("deviceId")
        val created = response["createdAccount"]?.jsonPrimitive?.boolean ?: false
        adopt(newToken, AccountSession(base, email, accountId, deviceId, "unknown",
            provider = AccountProvider.Google, managed = true, createdNow = created))
        try { refreshSubscription() } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* The account stays connected; the screen reports refresh failure. */ }
        log.event("account") { "Google sign-in completed; created=$created" }
    }

    override suspend fun refreshSubscription(): AccountSubscription = withContext(Dispatchers.IO) {
        val connected = requireSession()
        val response = request(connected.serverUrl, "/v1/subscription", "GET", bearer = requireToken())
        val details = AccountSubscription(
            state = response.requiredString("state"),
            validUntil = response.optionalString("validUntil"),
            promotionalValidUntil = response.optionalString("promotionalValidUntil"),
            paidValidUntil = response.optionalString("paidValidUntil"),
            paidState = response.optionalString("paidState"),
            autoRenewing = response["autoRenewing"]?.jsonPrimitive?.boolean ?: false,
            productId = response.optionalString("productId"),
            staffValidUntil = response.optionalString("staffValidUntil"),
            staffRevokedAt = response.optionalString("staffRevokedAt"),
        )
        current.value = connected.copy(membership = details.state)
        details
    }

    override suspend fun redeemCoupon(code: String): AccountCoupon = withContext(Dispatchers.IO) {
        val connected = requireSession()
        require(connected.managed) { "Coupons are only available on Vive Notes Cloud." }
        val result = request(connected.serverUrl, "/v1/coupons/redeem", "POST", buildJsonObject {
            put("code", code.trim())
        }, bearer = requireToken())
        log.event("account") { "coupon redeemed" }
        AccountCoupon(result["monthsGranted"]?.jsonPrimitive?.int
            ?: error("Invalid coupon response."), result.requiredString("validUntil"))
    }

    override suspend fun listDevices(): List<AccountDevice> = withContext(Dispatchers.IO) {
        val connected = requireSession()
        val response = request(connected.serverUrl, "/v1/devices", "GET", bearer = requireToken())
        val entries = response["devices"] as? JsonArray ?: error("Invalid device list response.")
        entries.map { item ->
            val device = item as? JsonObject ?: error("Invalid device response.")
            AccountDevice(device.requiredString("deviceId"), device.requiredString("name"),
                device.requiredString("platform"), device["revokedAt"] != null && device["revokedAt"] != JsonNull)
        }
    }

    override suspend fun renameDevice(id: String, name: String) = withContext(Dispatchers.IO) {
        require(name.trim().length in 1..128) { "Device name must contain 1 to 128 characters." }
        request(requireSession().serverUrl, "/v1/devices/${UUID.fromString(id)}", "PATCH",
            buildJsonObject { put("name", name.trim()) }, bearer = requireToken())
        log.event("account") { "device renamed" }
        Unit
    }

    override suspend fun revokeDevice(id: String) = withContext(Dispatchers.IO) {
        request(requireSession().serverUrl, "/v1/devices/${UUID.fromString(id)}", "DELETE",
            bearer = requireToken())
        if (id == current.value?.deviceId) forget()
        log.event("account") { "device revoked" }
        Unit
    }

    override fun openPlayManagement() {
        val productId = "vivenotes_storage_monthly"
        openBrowser(URI("https://play.google.com/store/account/subscriptions" +
            "?sku=$productId&package=com.vivenotes"))
    }

    private fun requireSession(): AccountSession = current.value ?: error("Sign in first.")
    private fun requireToken(): String = token ?: error("Sign in first.")

    internal fun currentSyncAccount(): SyncAccount? = current.value?.let { connected ->
        token?.let { credential -> SyncAccount(connected.serverUrl, connected.accountId,
            connected.deviceId, credential) }
    }

    internal fun reportBackgroundSync(result: SyncRunResult) {
        mutableSyncStatus.value = when (result) {
            is SyncRunResult.Succeeded -> AccountSyncStatus(
                message = "Synced: ${result.summary.pulled} received, ${result.summary.pushed} uploaded",
                pulled = result.summary.pulled, pushed = result.summary.pushed,
                pictures = result.summary.pictures)
            is SyncRunResult.Retryable -> AccountSyncStatus(message = "Sync will retry: ${result.reason}")
            is SyncRunResult.Failed -> AccountSyncStatus(message = "Sync stopped: ${result.reason}")
            SyncRunResult.Revoked -> AccountSyncStatus(message = "Device access was revoked. Sign in again.")
        }
    }

    internal fun reportStreamFailure(reason: String) {
        mutableSyncStatus.value = AccountSyncStatus(message = "Sync stream: $reason")
    }

    override suspend fun synchronize(): AccountSyncStatus {
        val engine = syncEngine ?: error("Sync is unavailable.")
        val connected = requireSession()
        val account = SyncAccount(connected.serverUrl, connected.accountId, connected.deviceId, requireToken())
        if (mutableSyncStatus.value.running) return mutableSyncStatus.value
        mutableSyncStatus.value = mutableSyncStatus.value.copy(running = true, message = "Syncing…")
        return try {
            flushBeforeSync()
            val result = withContext(Dispatchers.IO) { engine.run(account) }
            if (result is SyncRunResult.Succeeded &&
                (result.summary.pulled > 0 || result.summary.conflictsResolved > 0)) afterSync()
            val status = when (result) {
                is SyncRunResult.Succeeded -> AccountSyncStatus(
                    message = "Synced: ${result.summary.pulled} received, ${result.summary.pushed} uploaded" +
                        if (result.summary.pictures > 0) ", ${result.summary.pictures} pictures" else "",
                    pulled = result.summary.pulled, pushed = result.summary.pushed,
                    pictures = result.summary.pictures,
                )
                is SyncRunResult.Retryable -> AccountSyncStatus(message = "Sync will retry: ${result.reason}")
                is SyncRunResult.Failed -> AccountSyncStatus(message = "Sync stopped: ${result.reason}")
                SyncRunResult.Revoked -> {
                    forget()
                    AccountSyncStatus(message = "Device access was revoked. Sign in again.")
                }
            }
            mutableSyncStatus.value = status
            log.event("sync") { status.message }
            status
        } catch (failure: Exception) {
            val status = AccountSyncStatus(message = "Sync failed; try again.")
            mutableSyncStatus.value = status
            log.event("sync") { "sync failed (${failure::class.simpleName})" }
            throw failure
        }
    }

    private fun adopt(newToken: String, session: AccountSession) {
        token = newToken
        current.value = session
        val saved = credentialStore?.save(StoredAccountCredential(session, newToken)) == true
        current.value = session.copy(persisted = saved)
        if (!saved) log.event("account") { "credential storage unavailable; session only" }
    }

    override suspend fun disconnect() {
        val connected = current.value ?: return
        val credential = token ?: return
        withContext(Dispatchers.IO) {
            try {
                request(connected.serverUrl, "/v1/devices/${connected.deviceId}", "DELETE", bearer = credential)
            } catch (error: AccountApiException) {
                if (error.status != 401) throw error
            }
        }
        forget()
    }

    override suspend fun forget() {
        val accountId = current.value?.accountId
        withContext(Dispatchers.IO) {
            credentialStore?.clear()
            if (accountId != null) syncEngine?.deactivate(accountId)
        }
        token = null
        current.value = null
        mutableSyncStatus.value = AccountSyncStatus()
        log.event("account") { "disconnected" }
    }

    private fun request(base: String, path: String, method: String, body: JsonObject? = null,
        bearer: String? = null): JsonObject {
        val builder = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(20)).header("Accept", "application/json")
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        if (body != null) builder.header("Content-Type", "application/json")
        val request = builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody()
            else HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            val error = runCatching { Json.parseToJsonElement(response.body()) as? JsonObject }.getOrNull()
            val message = error?.get("message")?.jsonPrimitive?.content
                ?: "Server returned HTTP ${response.statusCode()}."
            throw AccountApiException(response.statusCode(), error?.optionalString("error"), message.take(240))
        }
        if (response.statusCode() == 204) return JsonObject(emptyMap())
        return (Json.parseToJsonElement(response.body()) as? JsonObject)
            ?: throw IllegalStateException("Invalid server response.")
    }
}

internal class AccountApiException(status: Int, code: String?, message: String) :
    AccountRequestException(status, code, message)

internal fun accountServerUrl(value: String): String {
    val uri = runCatching { URI(value.trim()) }.getOrNull()
        ?: throw IllegalArgumentException("Enter a server URL.")
    require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.query == null && uri.fragment == null &&
        (uri.path.isNullOrEmpty() || uri.path == "/")) {
        "Enter a server URL such as https://notes.example.com."
    }
    require(uri.scheme.equals("https", ignoreCase = true) ||
        uri.host.equals("localhost", ignoreCase = true) || uri.host == "127.0.0.1" || uri.host == "[::1]") {
        "Use HTTPS for servers outside this computer."
    }
    return URI(uri.scheme.lowercase(), null, uri.host, uri.port, null, null, null).toString()
}

private fun JsonObject.requiredString(key: String): String =
    this[key]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        ?: throw IllegalStateException("Server response is missing $key.")

private fun JsonObject.optionalString(key: String): String? =
    this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

private fun desktopDeviceName(): String =
    (System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME") ?: "Desktop").trim().take(128)
        .ifBlank { "Desktop" }
