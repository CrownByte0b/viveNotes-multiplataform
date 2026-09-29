package com.vivenotes.desktop

import com.vivenotes.data.sync.ChangeStreamEvent
import com.vivenotes.data.sync.HierarchySync
import com.vivenotes.data.sync.SyncAccount
import com.vivenotes.data.sync.SyncRunResult
import com.vivenotes.data.sync.SyncServerClient
import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/** Keeps one authenticated change stream open and uploads the durable outbox after catch-up. */
internal class DesktopSyncCoordinator(
    private val scope: CoroutineScope,
    private val accounts: DesktopAccountService,
    private val engine: HierarchySync,
    private val client: SyncServerClient = SyncServerClient(),
    private val log: DebugLog = DebugLog(),
    private val beforeRemoteApply: suspend () -> Unit = {},
    private val afterRemoteApply: () -> Unit = {},
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            accounts.session.map { session -> accounts.currentSyncAccount()?.let { it to session?.membership } }
                .distinctUntilChanged().collectLatest { connection ->
                    val account = connection?.first ?: return@collectLatest
                    supervisorScope { deliver(account) }
            }
        }
    }

    suspend fun stop() {
        job?.cancel()
        job?.join()
        job = null
    }

    private suspend fun CoroutineScope.deliver(account: SyncAccount) {
        val wakeups = Channel<Unit>(Channel.CONFLATED)
        var ready = false
        val uploader = launch {
            engine.pendingChanges.collect { pending ->
                if (pending && ready) wakeups.trySend(Unit)
            }
        }
        val uploads = launch {
            for (ignored in wakeups) {
                val result = engine.pushPending(account)
                if (result is SyncRunResult.Succeeded && result.summary.conflictsResolved > 0) afterRemoteApply()
                when (result) {
                    is SyncRunResult.Succeeded -> accounts.reportBackgroundSync(result)
                    SyncRunResult.Revoked -> {
                        revoke()
                        break
                    }
                    is SyncRunResult.Failed -> accounts.reportBackgroundSync(result)
                    is SyncRunResult.Retryable -> {
                        accounts.reportBackgroundSync(result)
                        delay(RETRY_DELAY_MILLIS)
                        if (engine.hasPendingChanges()) wakeups.trySend(Unit)
                    }
                }
            }
        }
        try {
            var reconnectDelay = INITIAL_RECONNECT_MILLIS
            while (currentCoroutineContext().isActive && accounts.currentSyncAccount() == account) {
                ready = false
                beforeRemoteApply()
                val cursor = engine.beginChangeStream(account.accountId)
                var terminal: ChangeStreamEvent? = null
                client.watchChanges(account.serverUrl, account.token, cursor).collect { event ->
                    when (event) {
                        is ChangeStreamEvent.Ready, is ChangeStreamEvent.Changes -> {
                            val page = when (event) {
                                is ChangeStreamEvent.Ready -> event.page
                                is ChangeStreamEvent.Changes -> event.page
                            }
                            if (page.hasMore) ready = false
                            beforeRemoteApply()
                            val result = engine.applyStreamPage(account, page)
                            if (result is SyncRunResult.Succeeded && result.summary.pulled > 0) afterRemoteApply()
                            accounts.reportBackgroundSync(result)
                            if (result !is SyncRunResult.Succeeded) {
                                terminal = if (result == SyncRunResult.Revoked) ChangeStreamEvent.Unauthorized
                                    else ChangeStreamEvent.Failed(
                                        com.vivenotes.data.sync.ConnectFailure.Unreachable, retryable = false)
                                throw StopDelivery()
                            }
                            if (!page.hasMore) {
                                ready = true
                                reconnectDelay = INITIAL_RECONNECT_MILLIS
                                if (engine.hasPendingChanges()) wakeups.trySend(Unit)
                            }
                        }
                        ChangeStreamEvent.Unauthorized, is ChangeStreamEvent.Failed -> terminal = event
                    }
                }
                when (val ended = terminal) {
                    ChangeStreamEvent.Unauthorized -> {
                        revoke()
                        return
                    }
                    is ChangeStreamEvent.Failed -> {
                        accounts.reportStreamFailure(ended.reason.toString())
                        if (!ended.retryable) return
                    }
                    else -> accounts.reportStreamFailure("Connection interrupted")
                }
                delay(reconnectDelay)
                reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_MILLIS)
            }
        } catch (_: StopDelivery) {
            // The sync engine has already recorded the permanent failure.
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.failure("sync", "change stream", failure)
            accounts.reportStreamFailure("Sync failed; try again")
        } finally {
            uploader.cancel()
            uploads.cancel()
            wakeups.close()
        }
    }

    private suspend fun revoke() = withContext(NonCancellable) { accounts.forget() }

    private class StopDelivery : RuntimeException()

    private companion object {
        const val INITIAL_RECONNECT_MILLIS = 1_000L
        const val MAX_RECONNECT_MILLIS = 60_000L
        const val RETRY_DELAY_MILLIS = 30_000L
    }
}
