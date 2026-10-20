// Ported from the account half of the Android app's `ui/NotesApp.kt`: the state it keeps one level
// above the Account destination, and every callback it hands `AccountScreen`. The desktop has no
// Activity to pass Credential Manager, no Play purchase sheet, and no Custom Tab for Stripe's pages
// (see `StripePages.kt`); everything else is Android's.
package com.vivenotes.ui.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import com.vivenotes.data.billing.CLOUD_STORAGE_SUBSCRIPTION_ID
import com.vivenotes.data.billing.ManagedSubscriptionController
import com.vivenotes.data.sync.AccountAuthProvider
import com.vivenotes.data.sync.AccountDeletionResult
import com.vivenotes.data.sync.CloudArchiveResult
import com.vivenotes.data.sync.CloudSignInResult
import com.vivenotes.data.sync.ConnectFailure
import com.vivenotes.data.sync.DisconnectResult
import com.vivenotes.data.sync.PaidProvider
import com.vivenotes.data.sync.PasswordResetResult
import com.vivenotes.data.sync.ServerConnection
import com.vivenotes.data.sync.SyncAccounts
import com.vivenotes.data.sync.SyncRunResult
import com.vivenotes.localization.Strings
import com.vivenotes.workspace.NotebookCloud
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URI

/**
 * The account feature over Android's [SyncAccounts]: the top bar's state, and the destination.
 *
 * [scope] is the application's, for the two flows the top bar reads; the destination's requests run
 * in a scope held by the workspace composition, as Android's do.
 */
class SyncAccountHost(
    private val accounts: SyncAccounts,
    private val subscription: ManagedSubscriptionController,
    scope: CoroutineScope,
    /** Opens a page in the person's browser — the Play subscription page, and Stripe's pages. */
    private val openBrowser: (URI) -> Unit,
) : AccountHost {

    override val connected: StateFlow<Boolean> =
        accounts.account.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)

    override val serverUnreachable: StateFlow<Boolean> =
        combine(accounts.account, accounts.status) { stored, status ->
            // Only meaningful with a registration behind it. A device that was never connected has
            // no server to be cut off from, and a cloud with a line through it there would read as a
            // feature that is broken rather than one not set up.
            stored != null && status.serverUnreachable
        }.stateIn(scope, SharingStarted.Eagerly, false)

    @Composable
    override fun Destination(visible: Boolean, onBack: () -> Unit) {
        AccountDestination(visible, onBack, accounts, subscription, openBrowser)
    }
}

/** Android's `runCloudArchive`, over the same two [SyncAccounts] calls. */
class SyncNotebookCloud(
    private val accounts: SyncAccounts,
    scope: CoroutineScope,
) : NotebookCloud {

    override val connected: StateFlow<Boolean> =
        accounts.account.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)

    override suspend fun move(notebookId: String, toCloud: Boolean): String? {
        val result = if (toCloud) {
            accounts.moveNotebookToCloud(notebookId)
        } else {
            accounts.bringNotebookBack(notebookId)
        }
        return cloudArchiveMessage(result, toCloud)
    }
}

@Composable
private fun AccountDestination(
    visible: Boolean,
    onBack: () -> Unit,
    syncAccounts: SyncAccounts,
    managedSubscription: ManagedSubscriptionController,
    openBrowser: (URI) -> Unit,
) {
    // Held here, one level above the Account destination, so closing the screen mid-request neither
    // cancels it nor loses its result. `POST /v1/devices` returns the device token exactly once and
    // the server cannot reissue it, so a scope that dies with the screen would turn a stray Back
    // press into a device registered on the server that nothing here can authenticate as.
    val connectScope = rememberCoroutineScope()
    var selfHostConnection by remember { mutableStateOf<ServerConnection>(ServerConnection.Idle) }
    // Only this screen's button, so the spinner belongs to the press that started it. Automatic
    // outbox and remote-event runs report through `syncStatus` instead; a shared flag would put a
    // spinner on the button whenever another device wrote something without anyone pressing it.
    var syncing by remember { mutableStateOf(false) }
    var disconnecting by remember { mutableStateOf(false) }
    var disconnectFailure by remember { mutableStateOf<ConnectFailure?>(null) }

    // The Google route's own in-flight and failure state, kept apart from the self-host route's for
    // the reason the screen keeps its messages apart: the two fail independently, and one button's
    // spinner has no business appearing under the other.
    var signingInWithGoogle by remember { mutableStateOf(false) }
    var googleFailure by remember { mutableStateOf<ConnectFailure?>(null) }
    var linkRequired by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    // Session-only, deliberately. It answers "what did that button just do", which is news exactly
    // once; persisting it would have a computer still announcing a new account weeks later.
    var accountCreated by remember { mutableStateOf(false) }
    var failedPasswordLogins by remember { mutableStateOf(0) }
    var passwordResetState by remember { mutableStateOf(PasswordResetUiState()) }
    // A public reset request may finish after Account has been closed. Its result must not revive a
    // flow the person already left or replace the state of a newer request.
    var passwordResetGeneration by remember { mutableStateOf(0L) }
    var accountDeletionState by remember { mutableStateOf(AccountDeletionUiState()) }

    // A registration already on disk is shown as the connected state, so leaving the screen and
    // coming back does not read as never having connected. This attempt wins while there is one:
    // its result — including a failure — is news, and the stored account is only the background.
    val storedAccount by syncAccounts.account.collectAsState(initial = null)
    val syncStatus by syncAccounts.status.collectAsState()
    // Application-scoped and refreshed when the process/account starts. Account navigation only
    // observes this cache; purchase and coupon actions update it through their explicit flows.
    val subscriptionState by managedSubscription.state.collectAsState()

    // Stripe Checkout and the customer portal open in the browser. Coming back to this window is
    // when the controller asks the server what happened there.
    StripePageEffects(
        subscription = managedSubscription,
        openBrowser = openBrowser,
        foreground = LocalWindowInfo.current.isWindowFocused,
    )

    // A revocation delivered by the stream or an automatic run still has to reach the screen. The
    // token is already gone by the time this runs, so this is only about saying why the form came
    // back.
    LaunchedEffect(syncStatus.failure) {
        if (syncStatus.failure == SyncRunResult.Revoked) {
            selfHostConnection = ServerConnection.Failed(ConnectFailure.Revoked)
        }
    }

    val displayedConnection = when (val attempt = selfHostConnection) {
        ServerConnection.Idle -> storedAccount
            ?.let {
                ServerConnection.Connected(
                    serverUrl = it.serverUrl,
                    deviceName = it.deviceName,
                    email = it.email,
                    authProvider = it.authProvider,
                )
            }
            ?: ServerConnection.Idle

        else -> attempt
    }
    val closeAccount = {
        onBack()
        failedPasswordLogins = 0
        passwordResetGeneration++
        passwordResetState = PasswordResetUiState()
        accountDeletionState = AccountDeletionUiState()
    }

    if (!visible) return

    AccountScreen(
        onBack = closeAccount,
        googleAvailable = syncAccounts.googleSignInAvailable,
        signingInWithGoogle = signingInWithGoogle,
        onSignInWithGoogle = {
            if (!signingInWithGoogle) {
                signingInWithGoogle = true
                googleFailure = null
                // The whole flow — challenge, browser, sign-in, token write — runs in the scope
                // above this destination, exactly as `onConnect` does: it ends in a device token that
                // is issued once, so a Back press must not cancel it.
                connectScope.launch {
                    when (val result = syncAccounts.signInWithGoogle()) {
                        is CloudSignInResult.Connected -> {
                            accountCreated = result.createdAccount
                            selfHostConnection = ServerConnection.Connected(
                                serverUrl = result.serverUrl,
                                deviceName = result.deviceName,
                                email = result.email,
                                authProvider = AccountAuthProvider.Google,
                            )
                        }

                        CloudSignInResult.LinkRequired -> linkRequired = true

                        // The person closed the sign-in. Nothing to say about it.
                        CloudSignInResult.Dismissed -> Unit

                        is CloudSignInResult.Failed -> googleFailure = result.reason
                    }
                    signingInWithGoogle = false
                }
            }
        },
        googleFailure = googleFailure,
        linkRequired = linkRequired,
        linking = linking,
        onLinkAccount = { email, password ->
            if (!linking) {
                linking = true
                googleFailure = null
                connectScope.launch {
                    when (val result = syncAccounts.linkGoogleAccount(email, password)) {
                        is CloudSignInResult.Connected -> {
                            accountCreated = result.createdAccount
                            linkRequired = false
                            selfHostConnection = ServerConnection.Connected(
                                serverUrl = result.serverUrl,
                                deviceName = result.deviceName,
                                email = result.email,
                                authProvider = AccountAuthProvider.Google,
                            )
                        }

                        // Neither can arise from the link endpoint; `SyncAccounts` maps its own
                        // impossible answer to a failure. Leaving the dialog open is the safe reading
                        // of a result that should not exist.
                        CloudSignInResult.LinkRequired,
                        CloudSignInResult.Dismissed,
                        -> Unit

                        // The dialog stays up with its message: a wrong password is usually one
                        // character, and closing it would cost a whole new sign-in.
                        is CloudSignInResult.Failed -> googleFailure = result.reason
                    }
                    linking = false
                }
            }
        },
        onCancelLink = {
            if (!linking) {
                linkRequired = false
                googleFailure = null
                // Not just closing the dialog: this drops the Google ID token that the pending link
                // is holding in memory.
                syncAccounts.cancelGoogleLink()
            }
        },
        accountCreated = accountCreated,
        connection = displayedConnection,
        managedSubscription = subscriptionState,
        purchasesAvailable = false,
        onSubscribe = managedSubscription::purchase,
        onManageSubscription = {
            // Each paid plan is managed where it was bought: Stripe's portal for a card plan,
            // Play's subscription centre for everything else.
            if (subscriptionState.status?.paidProvider == PaidProvider.Stripe) {
                managedSubscription.manageCardSubscription()
            } else {
                openBrowser(
                    URI(
                        "https://play.google.com/store/account/subscriptions" +
                            "?sku=$CLOUD_STORAGE_SUBSCRIPTION_ID&package=$ANDROID_PACKAGE",
                    ),
                )
            }
        },
        onRedeemCoupon = managedSubscription::redeemCoupon,
        onConnect = { serverUrl, email, password ->
            disconnectFailure = null
            selfHostConnection = ServerConnection.Connecting
            connectScope.launch {
                selfHostConnection = syncAccounts.connect(serverUrl, email, password)
            }
        },
        // The managed account's two email-and-password routes. Same scope as everything else that
        // ends in a device token, and signing up has a second thing to lose: a cancelled call after
        // the account was created leaves an account on the server with no device on it.
        onLogIn = { email, password ->
            disconnectFailure = null
            selfHostConnection = ServerConnection.Connecting
            connectScope.launch {
                val result = syncAccounts.logInToCloud(email, password)
                if (result == ServerConnection.Failed(ConnectFailure.InvalidCredentials)) {
                    failedPasswordLogins++
                } else if (result is ServerConnection.Connected) {
                    failedPasswordLogins = 0
                }
                selfHostConnection = result
            }
        },
        failedPasswordLogins = failedPasswordLogins,
        passwordResetState = passwordResetState,
        onRequestPasswordReset = { email ->
            if (!passwordResetState.requestingCode) {
                val generation = passwordResetGeneration + 1
                passwordResetGeneration = generation
                passwordResetState = PasswordResetUiState(requestingCode = true)
                connectScope.launch {
                    val nextState = when (val result = syncAccounts.requestCloudPasswordReset(email)) {
                        PasswordResetResult.Accepted ->
                            PasswordResetUiState(codeRequested = true)
                        is PasswordResetResult.Failed ->
                            PasswordResetUiState(failure = result.reason)
                    }
                    if (passwordResetGeneration == generation) {
                        passwordResetState = nextState
                    }
                }
            }
        },
        onCompletePasswordReset = { email, code, newPassword ->
            if (!passwordResetState.changingPassword) {
                val generation = passwordResetGeneration + 1
                passwordResetGeneration = generation
                passwordResetState = passwordResetState.copy(
                    changingPassword = true,
                    failure = null,
                )
                connectScope.launch {
                    val nextState = when (
                        val result = syncAccounts.completeCloudPasswordReset(email, code, newPassword)
                    ) {
                        PasswordResetResult.Accepted -> {
                            failedPasswordLogins = 0
                            PasswordResetUiState(codeRequested = true, completed = true)
                        }
                        is PasswordResetResult.Failed -> PasswordResetUiState(
                            codeRequested = true,
                            failure = result.reason,
                        )
                    }
                    if (passwordResetGeneration == generation) {
                        passwordResetState = nextState
                    }
                }
            }
        },
        onPasswordResetClosed = {
            passwordResetGeneration++
            passwordResetState = PasswordResetUiState()
        },
        onSignUp = { email, password ->
            disconnectFailure = null
            selfHostConnection = ServerConnection.Connecting
            connectScope.launch {
                selfHostConnection = syncAccounts.signUpForCloud(email, password)
            }
        },
        syncing = syncing,
        syncStatus = syncStatus,
        onSync = {
            if (!syncing) {
                syncing = true
                connectScope.launch {
                    // The result reaches the screen through `syncStatus`, which every run reports
                    // to; this only has to put the button back.
                    syncAccounts.synchronize()
                    syncing = false
                }
            }
        },
        disconnecting = disconnecting,
        disconnectFailure = disconnectFailure,
        onDisconnect = {
            if (!disconnecting) {
                disconnecting = true
                disconnectFailure = null
                connectScope.launch {
                    when (val result = syncAccounts.disconnect()) {
                        DisconnectResult.Disconnected -> {
                            // Back to Idle rather than to a "disconnected" result: with the stored
                            // account gone the empty form is the screen saying so. `disconnect`
                            // clears the sync status for the same reason.
                            selfHostConnection = ServerConnection.Idle
                            accountCreated = false
                            googleFailure = null
                        }
                        is DisconnectResult.Failed -> {
                            disconnectFailure = result.reason
                        }
                    }
                    disconnecting = false
                }
            }
        },
        // The offer that follows a failed revoke. Same local clean-up, no request in front of it: a
        // server that cannot be reached to be told is exactly the one this has to work without, or
        // the registration it refuses to give up keeps this installation from ever reaching another
        // server.
        onForceDisconnect = {
            if (!disconnecting) {
                disconnecting = true
                connectScope.launch {
                    syncAccounts.forgetConnection()
                    disconnectFailure = null
                    // As above: with the stored account gone, the empty form says so.
                    selfHostConnection = ServerConnection.Idle
                    accountCreated = false
                    googleFailure = null
                    disconnecting = false
                }
            }
        },
        accountDeletionState = accountDeletionState,
        onRequestAccountDeletion = { email ->
            if (!accountDeletionState.requesting) {
                accountDeletionState = AccountDeletionUiState(requesting = true)
                connectScope.launch {
                    when (val result = syncAccounts.requestManagedAccountDeletion(email)) {
                        AccountDeletionResult.Requested -> {
                            selfHostConnection = ServerConnection.Idle
                            accountCreated = false
                            googleFailure = null
                            accountDeletionState = AccountDeletionUiState()
                        }
                        is AccountDeletionResult.Failed -> {
                            accountDeletionState = AccountDeletionUiState(failure = result.reason)
                        }
                    }
                }
            }
        },
    )
}

/**
 * What to say after a move to the cloud, or a restore, that did not simply work.
 *
 * Success returns null. There is nothing to say — the list the person is looking at has already
 * moved the notebook from one group to the other, which is a better report than a sentence.
 */
internal fun cloudArchiveMessage(result: CloudArchiveResult, move: Boolean): String? = when (result) {
    CloudArchiveResult.Moved, CloudArchiveResult.BroughtBack, CloudArchiveResult.AlreadyDone -> null
    CloudArchiveResult.NoAccount -> Strings.closed_notebook_needs_account
    is CloudArchiveResult.NotUploaded -> Strings.closed_notebook_not_uploaded(result.pending)
    CloudArchiveResult.UnknownNotebook -> Strings.closed_notebook_gone
    is CloudArchiveResult.Failed ->
        if (move) Strings.closed_notebook_failed else Strings.closed_notebook_restore_failed
}

/** The Android app's package, whose Play subscription page manages the managed storage plan. */
private const val ANDROID_PACKAGE = "com.vivenotes"
