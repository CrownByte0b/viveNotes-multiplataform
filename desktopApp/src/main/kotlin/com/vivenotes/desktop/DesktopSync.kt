package com.vivenotes.desktop

import com.vivenotes.data.NotesLibrary
import com.vivenotes.data.billing.ManagedSubscriptionController
import com.vivenotes.data.sync.ForegroundSyncScheduler
import com.vivenotes.data.sync.GoogleIdentityProvider
import com.vivenotes.data.sync.SyncAccountStore
import com.vivenotes.data.sync.SyncAccounts
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.localization.Strings
import com.vivenotes.ui.account.SyncAccountHost
import com.vivenotes.ui.account.SyncNotebookCloud
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Desktop
import java.io.File
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * This process's account and sync: what the Android app's `NotesApplication` builds — `SyncAccounts`
 * over the notes database, the managed subscription, and the foreground scheduler that holds the
 * server's change stream and pushes local changes — and what the window shows of them.
 *
 * Android ties the scheduler to the process lifecycle: the stream is open while the app is visible,
 * and backgrounding hands pending work to WorkManager. A desktop app is visible for as long as its
 * window is open, so [start] runs at launch and [stop] at close, where a final push is given
 * [finalPushTimeout] before the database closes; anything still unsent stays in the outbox, which
 * the next launch's stream sends once it has caught up.
 *
 * Android also stops and starts the scheduler around Stripe's pages, which cover the app; that new
 * start is what reopens a stream the server refused before the account had a plan. A desktop window
 * stays open meanwhile, so the scheduler is restarted when the server first says storage is active.
 */
internal class DesktopSync(
    library: NotesLibrary,
    profile: DesktopProfile,
    identity: DesktopSyncIdentity,
    private val log: DebugLog = DebugLog(),
    openBrowser: (URI) -> Unit = ::openInBrowser,
    cloudBaseUrl: String = DesktopBuildInfo.cloudBaseUrl(profile),
    googleClientId: String = DesktopBuildInfo.googleDesktopClientId,
    cardCheckout: Boolean = DesktopBuildInfo.CARD_CHECKOUT,
    private val finalPushTimeout: Duration = 10.seconds,
) {
    /** For flows the window reads and the subscription's lifecycle; ends at [stop]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The scheduler's own, so [stop] can wait for exactly its final push. */
    private val schedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val accounts = SyncAccounts(
        // Android's `datastore/sync.preferences_pb` in the app's files directory.
        store = SyncAccountStore(File(library.directory, "datastore/sync.preferences_pb"), identity.deviceSeed),
        cloudBaseUrl = cloudBaseUrl,
        database = library.database,
        attachments = library.attachments.syncBytes,
        deviceName = identity.deviceName,
        platform = identity.platform,
        installation = identity.details,
        google = GoogleIdentityProvider(
            clientId = googleClientId,
            codeExchangeEndpoint = cloudBaseUrl.trimEnd('/') + "/v1/auth/google/desktop-tokens",
            openBrowser = openBrowser,
            returnPage = ::googleReturnPage,
        ),
    )

    val subscription = ManagedSubscriptionController(accounts, scope, cardCheckoutAllowed = cardCheckout)

    /** The top bar's account button and the Account destination. */
    val accountHost = SyncAccountHost(accounts, subscription, scope, openBrowser)

    /** Closed Notebooks' Move to cloud and Bring back. */
    val notebookCloud = SyncNotebookCloud(accounts, scope)

    private val foreground = ForegroundSyncScheduler(
        scope = schedulerScope,
        registered = accounts.account.map { it != null },
        localChanges = accounts.pendingChanges,
        remoteChanges = accounts.remoteChanges,
        remoteReady = accounts.streamReady,
        hasPendingChanges = accounts::hasPendingChanges,
        sync = { accounts.pushPending() },
    )

    /** Between [start] and [stop]: only then may an activated plan restart the scheduler. */
    private var running = false
    private val lifecycle = Any()

    init {
        log.event("sync") { "managed server ${if (profile == DesktopProfile.DEVELOPMENT) "development" else "production"}" }
        scope.launch {
            var wasActive: Boolean? = null
            subscription.state.map { it.status?.active }.distinctUntilChanged().collect { active ->
                // Inactive to active only: a first answer, or another account's, opens its own stream.
                if (wasActive == false && active == true) restartAfterActivation()
                wasActive = active
            }
        }
    }

    /** Opens the change stream once a registration exists, and pushes local changes as they land. */
    fun start() {
        log.event("sync") { "foreground sync starting" }
        synchronized(lifecycle) {
            running = true
            foreground.start()
        }
    }

    /**
     * `402 membership_required` ended the stream for good, and with it the pushes, which wait for the
     * stream's backlog. Android's stop and start: pending changes are pushed, and a new stream opens.
     */
    private fun restartAfterActivation() {
        synchronized(lifecycle) {
            if (!running) return
            log.event("sync") { "storage active; restarting foreground sync" }
            foreground.stop()
            foreground.start()
        }
    }

    /**
     * Closes the stream and pushes what is still waiting, within [finalPushTimeout]. Returns once
     * nothing of this class can touch the database any more, so the caller may close it.
     */
    suspend fun stop() {
        synchronized(lifecycle) {
            running = false
            foreground.stop()
        }
        val finished = withTimeoutOrNull(finalPushTimeout) {
            schedulerScope.coroutineContext.job.children.toList().joinAll()
        } != null
        if (!finished) log.event("sync") { "final push still running at close; the outbox keeps it" }
        schedulerScope.cancel()
        scope.cancel()
        // Cancelling does not interrupt a blocking socket read, so this wait is bounded too. A push cut
        // off here is safe: its batch was persisted before the request and is retried under the same
        // id, and an acknowledgement that cannot be written leaves the outbox to send it again.
        withTimeoutOrNull(CANCEL_GRACE) {
            listOf(schedulerScope.coroutineContext.job, scope.coroutineContext.job).joinAll()
        }
        log.event("sync") { "foreground sync stopped" }
    }

    private companion object {
        val CANCEL_GRACE = 3.seconds
    }
}

/** The page the browser shows once it has handed Google's answer back to this app. */
private fun googleReturnPage(signedIn: Boolean): String {
    val message = if (signedIn) Strings.account_google_browser_signed_in else Strings.account_google_browser_failed
    val escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return "<!doctype html><html><head><meta charset=\"utf-8\"><title>${Strings.vive_notes}</title></head>" +
        "<body style=\"font-family:sans-serif;margin:3em\"><p>$escaped</p></body></html>"
}

/**
 * The person's browser, through AWT where it can, and `xdg-open` where it cannot — AWT's desktop
 * integration is not there on every Linux toolkit, JetBrains Runtime's native Wayland one included.
 */
internal fun openInBrowser(uri: URI) {
    val awt = runCatching {
        Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
    }.getOrDefault(false)
    if (awt) {
        Desktop.getDesktop().browse(uri)
        return
    }
    val os = System.getProperty("os.name").orEmpty()
    val command = when {
        os.startsWith("Windows", ignoreCase = true) -> listOf("rundll32", "url.dll,FileProtocolHandler", uri.toString())
        os.startsWith("Mac", ignoreCase = true) -> listOf("open", uri.toString())
        else -> listOf("xdg-open", uri.toString())
    }
    ProcessBuilder(command).redirectErrorStream(true).start()
}
