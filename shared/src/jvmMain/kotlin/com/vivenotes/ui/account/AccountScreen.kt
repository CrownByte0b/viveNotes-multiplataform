// Ported from the Android app's `ui/account/AccountScreen.kt`: the same routes, states, test tags and
// copy. The desktop differences are presentation only — a header row instead of a `Scaffold` app
// bar, Escape for system Back, the in-window dialog frame instead of `AlertDialog`, desktop icons and
// colours, and, where no card checkout is on offer, no purchase button: Google Play is Android's.
package com.vivenotes.ui.account

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.vivenotes.data.rememberTextClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.vivenotes.ui.components.BoundPasswordField
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vivenotes.data.billing.CheckoutProvider
import com.vivenotes.data.billing.ManagedSubscriptionFailure
import com.vivenotes.data.billing.ManagedSubscriptionState
import com.vivenotes.data.sync.AccountAuthProvider
import com.vivenotes.data.sync.AccountDeletionFailure
import com.vivenotes.data.sync.ConnectFailure
import com.vivenotes.data.sync.MIN_ACCOUNT_PASSWORD
import com.vivenotes.data.sync.PaidProvider
import com.vivenotes.data.sync.PaidSubscriptionState
import com.vivenotes.data.sync.PasswordResetFailure
import com.vivenotes.data.sync.PermanentSyncFailure
import com.vivenotes.data.sync.ServerConnection
import com.vivenotes.data.sync.SyncRunResult
import com.vivenotes.data.sync.SyncStatus
import com.vivenotes.data.sync.SyncSummary
import com.vivenotes.localization.Strings
import com.vivenotes.ui.components.DesktopDialogFrame
import com.vivenotes.ui.components.DestinationFrame
import com.vivenotes.ui.icons.AccountSymbols
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.icons.NavigationSymbols
import com.vivenotes.ui.icons.ObjectSymbols
import com.vivenotes.ui.icons.ShellSymbols
import com.vivenotes.ui.icons.ViewSymbols
import com.vivenotes.ui.theme.LocalDesktopColors
import multiplataform_vive.shared.generated.resources.Res
import multiplataform_vive.shared.generated.resources.account_sync_days_ago
import multiplataform_vive.shared.generated.resources.account_sync_hours_ago
import multiplataform_vive.shared.generated.resources.account_sync_minutes_ago
import org.jetbrains.compose.resources.pluralStringResource
import java.text.DateFormat
import java.time.Instant
import java.util.Date

internal object AccountTags {
    const val SCREEN = "account-screen"
    const val BACK = "account-back"
    const val LOGIN = "account-login"
    const val SIGN_UP = "account-sign-up"
    const val GOOGLE = "account-google"

    /** On the spinner inside the Google button, so a test can tell waiting from idle. */
    const val GOOGLE_PROGRESS = "account-google-progress"
    const val GOOGLE_STATUS = "account-google-status"

    /** Shown while the Google sign-in waits on the browser: what to do if that browser let them down. */
    const val GOOGLE_BROWSER_PANEL = "account-google-browser-panel"
    const val GOOGLE_COPY_LINK = "account-google-copy-link"
    const val GOOGLE_TRY_AGAIN = "account-google-try-again"
    const val SELF_HOST = "account-self-host"

    /** The password-account link dialog, raised only by `409 account_link_required`. */
    const val LINK_DIALOG = "account-link-dialog"
    const val LINK_EMAIL = "account-link-email"
    const val LINK_PASSWORD = "account-link-password"
    const val LINK_CONFIRM = "account-link-confirm"
    const val LINK_CANCEL = "account-link-cancel"
    const val SERVER_URL = "account-server-url"
    const val EMAIL = "account-email"
    const val PASSWORD = "account-password"
    const val CONNECT = "account-connect"

    /**
     * The managed account's own fields, distinct from the self-host form's.
     *
     * Two panels that are never open together could share tags, but only until one of them is
     * mid-exit-animation while the other opens — at which point a test would find two nodes and
     * fail somewhere unrelated to what it was checking.
     */
    const val CLOUD_EMAIL = "account-cloud-email"
    const val CLOUD_PASSWORD = "account-cloud-password"
    const val CLOUD_CONFIRM = "account-cloud-confirm"
    const val CLOUD_SUBMIT = "account-cloud-submit"
    const val CLOUD_PROGRESS = "account-cloud-progress"
    const val CLOUD_STATUS = "account-cloud-status"
    const val FORGOT_PASSWORD = "account-forgot-password"
    const val RESET_EMAIL = "account-reset-email"
    const val RESET_REQUEST = "account-reset-request"
    const val RESET_CODE = "account-reset-code"
    const val RESET_PASSWORD = "account-reset-password"
    const val RESET_CONFIRM_PASSWORD = "account-reset-confirm-password"
    const val RESET_COMPLETE = "account-reset-complete"
    const val RESET_STATUS = "account-reset-status"

    /** On the spinner *inside* the button, so a test can tell waiting from idle. */
    const val CONNECT_PROGRESS = "account-connect-progress"
    const val CONNECT_STATUS = "account-connect-status"
    const val CONNECTED = "account-connected"
    const val CONNECTED_IDENTITY = "account-connected-identity"
    const val CONNECTED_PROVIDER = "account-connected-provider"
    const val SUBSCRIPTION = "account-subscription"
    const val SUBSCRIBE = "account-subscribe"
    const val MANAGE_SUBSCRIPTION = "account-manage-subscription"
    const val GOOGLE_PLAY_MARK = "account-google-play-mark"

    /** The card glyph on Subscribe when this install sells through Stripe instead of Play. */
    const val CARD_CHECKOUT_MARK = "account-card-checkout-mark"
    const val COUPON = "account-coupon"
    const val REDEEM_COUPON = "account-redeem-coupon"
    const val SUBSCRIPTION_STATUS = "account-subscription-status"
    const val SUBSCRIPTION_BADGE = "account-subscription-badge"

    /** Coupon entry is folded behind this link, and exists only while no Play plan does. */
    const val COUPON_TOGGLE = "account-coupon-toggle"
    const val SYNC = "account-sync"
    const val SYNC_PROGRESS = "account-sync-progress"
    const val SYNC_STATUS = "account-sync-status"

    /** The Cloud Off glyph beside that line, present only while the server cannot be reached. */
    const val SYNC_OFFLINE = "account-sync-offline"
    const val DISCONNECT = "account-disconnect"
    const val DISCONNECT_CONFIRM = "account-disconnect-confirm"

    /** The local-only way out, shown only once a revoke has failed. */
    const val DISCONNECT_ANYWAY = "account-disconnect-anyway"
    const val DELETE_ACCOUNT = "account-delete"
    const val DELETE_DIALOG = "account-delete-dialog"
    const val DELETE_EMAIL = "account-delete-email"
    const val DELETE_CONFIRM = "account-delete-confirm"

    /** Desktop only: what stands where Android's purchase button does, with no Play store to buy from. */
    const val SUBSCRIBE_ON_ANDROID = "account-subscribe-on-android"
}

data class PasswordResetUiState(
    val requestingCode: Boolean = false,
    val codeRequested: Boolean = false,
    val changingPassword: Boolean = false,
    val completed: Boolean = false,
    val failure: PasswordResetFailure? = null,
)

data class AccountDeletionUiState(
    val requesting: Boolean = false,
    val failure: AccountDeletionFailure? = null,
)

/**
 * Account entry point for managed and self-hosted sync.
 *
 * Three routes to one place, in descending order of how many people want them.
 *
 * Sign in with Google sits on top and is the default: one button, because `POST /v1/auth/google` is
 * one endpoint that logs in or registers as the account turns out to need. Below it, Log in and Sign
 * up are the same managed account reached with an email and a password, and neither asks for a
 * server address because the build already names the one there is (`SyncAccounts.cloudServerUrl`).
 *
 * Self host is the third and is a different server, run by whoever is using it, so it is the one
 * route with an address to type.
 *
 * All three end in a base URL and a device token, so [connection] describes all of them and the
 * connected panel is written once.
 *
 * Presentational, including the connect flow: [connection] comes in and [onConnect] goes out, so the
 * request lives in a scope that outlives this screen. The device token comes back exactly once and
 * cannot be reissued, so a request tied to this composition would turn "closing the screen while it
 * spins" into a device row on the server that nothing holds the credential for.
 *
 * The opt-in is for the connect button's [LoadingIndicator].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalComposeUiApi::class)
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    /** False when the build carries no Google client id: the button is shown disabled, not hidden. */
    googleAvailable: Boolean = true,
    signingInWithGoogle: Boolean = false,
    onSignInWithGoogle: () -> Unit = {},
    /**
     * Google's page while the sign-in waits on the browser, else null. With it the screen offers to
     * copy the link into another browser, or to start the sign-in over with [onRetryGoogle].
     */
    googleSignInPage: String? = null,
    onRetryGoogle: () -> Unit = {},
    /**
     * The last Google attempt's failure, or null. Separate from [connection] because the two routes
     * fail independently and a message under the wrong button is a message about the wrong thing.
     */
    googleFailure: ConnectFailure? = null,
    /** Raised by `409 account_link_required`, which has exactly one resolution — see [onLinkAccount]. */
    linkRequired: Boolean = false,
    linking: Boolean = false,
    onLinkAccount: (email: String, password: String) -> Unit = { _, _ -> },
    onCancelLink: () -> Unit = {},
    /** True for the session in which Google sign-in created the account, rather than found it. */
    accountCreated: Boolean = false,
    connection: ServerConnection = ServerConnection.Idle,
    /** Managed-only billing state. Invisible for disconnected and self-hosted registrations. */
    managedSubscription: ManagedSubscriptionState = ManagedSubscriptionState(),
    /**
     * Whether Google Play can sell the plan here. Google Play is Android's, so without card checkout
     * ([ManagedSubscriptionState.checkout]) the desktop shows where to buy instead of a purchase
     * button that could never work.
     */
    purchasesAvailable: Boolean = false,
    onSubscribe: () -> Unit = {},
    onManageSubscription: () -> Unit = {},
    onRedeemCoupon: (String) -> Unit = {},
    /**
     * Sign in to the **managed** account with an email and password — `POST /v1/devices` against the
     * deployment this build was compiled for. No server address, because there is nothing to choose.
     */
    onLogIn: (email: String, password: String) -> Unit = { _, _ -> },
    /** Password recovery appears only after two rejected managed-password attempts. */
    failedPasswordLogins: Int = 0,
    passwordResetState: PasswordResetUiState = PasswordResetUiState(),
    onRequestPasswordReset: (email: String) -> Unit = {},
    onCompletePasswordReset: (email: String, code: String, password: String) -> Unit =
        { _, _, _ -> },
    onPasswordResetClosed: () -> Unit = {},
    /**
     * Create the managed account, then connect — `POST /v1/accounts` followed by `POST /v1/devices`.
     */
    onSignUp: (email: String, password: String) -> Unit = { _, _ -> },
    /** The self-hosted route, which is a different server and keeps its address field. */
    onConnect: (serverUrl: String, email: String, password: String) -> Unit = { _, _, _ -> },
    /** Whether *this screen's* Sync now is in flight — the button's own spinner, not the clock's. */
    syncing: Boolean = false,
    syncStatus: SyncStatus = SyncStatus(),
    onSync: () -> Unit = {},
    disconnecting: Boolean = false,
    disconnectFailure: ConnectFailure? = null,
    onDisconnect: () -> Unit = {},
    /**
     * Forget the server without revoking this device on it, for a server that cannot be told.
     * Offered only after [onDisconnect] has failed.
     */
    onForceDisconnect: () -> Unit = {},
    accountDeletionState: AccountDeletionUiState = AccountDeletionUiState(),
    onRequestAccountDeletion: (email: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var selfHostExpanded by rememberSaveable { mutableStateOf(false) }
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    // Passwords should not be written into saved-instance state or retained after leaving the flow.
    var password by remember { mutableStateOf("") }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteEmail by remember { mutableStateOf("") }
    var passwordResetOpen by rememberSaveable { mutableStateOf(false) }
    var passwordResetEmail by rememberSaveable { mutableStateOf("") }

    // The managed account's panel: whether it is open, and which of the two buttons opened it.
    // Saved, because a configuration change in the middle of filling it must not silently turn
    // Sign up back into Log in.
    var cloudExpanded by rememberSaveable { mutableStateOf(false) }
    var cloudSignUp by rememberSaveable { mutableStateOf(false) }
    var cloudEmail by rememberSaveable { mutableStateOf("") }
    // Composition-only, like the self-host form's password and for the same reason.
    var cloudPassword by remember { mutableStateOf("") }
    var cloudConfirmPassword by remember { mutableStateOf("") }
    val spatialMotion = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    val effectsMotion = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    fun closePasswordReset() {
        passwordResetOpen = false
        onPasswordResetClosed()
    }

    // The spinner replaces the button's label, so without this the button loses its accessible name
    // for as long as the request runs. Read here because a semantics lambda is not composable.
    val connectingLabel = Strings.account_connecting
    val creatingLabel = Strings.account_creating

    if (passwordResetOpen) {
        PasswordResetScreen(
            initialEmail = passwordResetEmail,
            state = passwordResetState,
            onRequestCode = onRequestPasswordReset,
            onComplete = onCompletePasswordReset,
            onBack = ::closePasswordReset,
            modifier = modifier,
        )
        return
    }

    // Pressing the button that is already showing folds the panel away again; pressing the other
    // one switches modes without closing. Self host closes, because the two are alternatives.
    fun openCloudPanel(signUp: Boolean) {
        if (cloudExpanded && cloudSignUp == signUp) {
            cloudExpanded = false
        } else {
            cloudExpanded = true
            cloudSignUp = signUp
            selfHostExpanded = false
            // The confirmation belongs to signing up. Left behind from a previous pass it could
            // silently satisfy a check the person did not make this time.
            cloudConfirmPassword = ""
        }
    }

    val connected = connection as? ServerConnection.Connected

    // Once the token exists the password has no further use, so it stops being held. Deliberately
    // not done on failure: a wrong password is usually a typo in one character, and clearing the
    // field would make correcting it mean typing the whole thing again.
    //
    // Opening the disclosure is in the same effect because it has the same trigger: arriving on this
    // screen already connected should show that, not a collapsed row that hides it. Keyed on
    // whether there is a connection rather than on [connection] itself, so re-expanding does not
    // fight a user who has just folded it away.
    LaunchedEffect(connected != null) {
        if (connected != null) {
            password = ""
            cloudPassword = ""
            cloudConfirmPassword = ""
            selfHostExpanded = true
        } else {
            // A successful deletion request clears the stored registration above this screen. The
            // confirmation must disappear with it rather than floating over the sign-in form.
            confirmDelete = false
            deleteEmail = ""
        }
    }

    @Composable
    fun AccountDialogs() {
        // `409 account_link_required` has exactly one resolution in the contract, and this is it: the
        // Google email already belongs to a password account, and creating a second account would split
        // one person's notes across two. So the dialog is not a choice — it is the next request.
        //
        // The email is typed rather than filled in from the ID token. Those claims are unverified until
        // the server has checked Google's signature, and it re-derives the address from the verified
        // token to compare against this one anyway; asking costs one field and keeps an unverified
        // string from ever being shown back as a fact.
        if (linkRequired) {
            var linkEmail by remember { mutableStateOf("") }
            // Composition-only, like the connect form's password, and for the same reason.
            var linkPassword by remember { mutableStateOf("") }
            val linkingLabel = Strings.account_linking

            DesktopDialogFrame(
                title = Strings.account_link_title,
                onDismiss = { if (!linking) onCancelLink() },
                modifier = Modifier.testTag(AccountTags.LINK_DIALOG),
                content = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = Strings.account_link_body,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = linkEmail,
                            onValueChange = { linkEmail = it },
                            label = { Text(Strings.account_link_email) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next,
                            ),
                            singleLine = true,
                            enabled = !linking,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(AccountTags.LINK_EMAIL),
                        )
                        BoundPasswordField(
                            value = linkPassword,
                            onValueChange = { linkPassword = it },
                            label = { Text(Strings.account_link_password) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            enabled = !linking,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(AccountTags.LINK_PASSWORD),
                        )
                        if (googleFailure != null) {
                            // The failure of the *link* attempt, shown in the dialog that caused it
                            // rather than behind it — the dialog stays open so the password can be
                            // corrected without starting a second sign-in.
                            Text(
                                // A 401 here has two causes, not the one the general message names:
                                // the password, or an email that is not the Google address just
                                // used. The server cannot say which, so the sentence covers both.
                                text = if (googleFailure == ConnectFailure.InvalidCredentials) {
                                    Strings.account_error_link_credentials
                                } else {
                                    failureMessage(googleFailure)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                actions = {
                    TextButton(
                        onClick = onCancelLink,
                        enabled = !linking,
                        modifier = Modifier.testTag(AccountTags.LINK_CANCEL),
                    ) {
                        Text(Strings.account_link_cancel)
                    }
                    TextButton(
                        onClick = { onLinkAccount(linkEmail, linkPassword) },
                        enabled = !linking && linkEmail.isNotBlank() && linkPassword.isNotEmpty(),
                        modifier = Modifier.testTag(AccountTags.LINK_CONFIRM),
                    ) {
                        if (linking) {
                            // Same treatment as Connect and the Google button: the spinner replaces the
                            // label, so it carries the name the label was providing.
                            LoadingIndicator(
                                Modifier
                                    .size(18.dp)
                                    .semantics { contentDescription = linkingLabel },
                            )
                        } else {
                            Text(Strings.account_link_confirm)
                        }
                    }
                },
            )
        }

        // Confirmed rather than immediate: the token cannot be reissued, so a stray tap costs a
        // credential and leaves a device on the server that this app can no longer revoke.
        if (confirmDisconnect) {
            DesktopDialogFrame(
                title = Strings.account_disconnect_title,
                onDismiss = { confirmDisconnect = false },
                content = { Text(Strings.account_disconnect_body) },
                actions = {
                    TextButton(onClick = { confirmDisconnect = false }) {
                        Text(Strings.account_disconnect_cancel)
                    }
                    TextButton(
                        onClick = {
                            confirmDisconnect = false
                            onDisconnect()
                        },
                        modifier = Modifier.testTag(AccountTags.DISCONNECT_CONFIRM),
                    ) {
                        Text(
                            text = Strings.account_disconnect,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        }

        if (confirmDelete) {
            val deletingLabel = Strings.account_delete_requesting
            DesktopDialogFrame(
                title = Strings.account_delete_title,
                onDismiss = {
                    if (!accountDeletionState.requesting) {
                        confirmDelete = false
                        deleteEmail = ""
                    }
                },
                icon = ObjectSymbols.Delete,
                iconTint = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(AccountTags.DELETE_DIALOG),
                content = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(Strings.account_delete_body)
                        Text(
                            text = Strings.account_delete_irreversible,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = deleteEmail,
                            onValueChange = { deleteEmail = it },
                            label = { Text(Strings.account_delete_email) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Done,
                            ),
                            singleLine = true,
                            enabled = !accountDeletionState.requesting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(AccountTags.DELETE_EMAIL),
                        )
                        accountDeletionState.failure?.let { failure ->
                            Text(
                                text = accountDeletionFailureMessage(failure),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            confirmDelete = false
                            deleteEmail = ""
                        },
                        enabled = !accountDeletionState.requesting,
                    ) {
                        Text(Strings.account_delete_cancel)
                    }
                    val expectedEmail = connected?.email
                    val identityMatches = deleteEmail.isNotBlank() &&
                        (
                            expectedEmail == null ||
                                deleteEmail.trim().equals(expectedEmail, ignoreCase = true)
                            )
                    TextButton(
                        onClick = { onRequestAccountDeletion(deleteEmail) },
                        enabled = identityMatches && !accountDeletionState.requesting,
                        modifier = Modifier.testTag(AccountTags.DELETE_CONFIRM),
                    ) {
                        if (accountDeletionState.requesting) {
                            LoadingIndicator(
                                Modifier
                                    .size(18.dp)
                                    .semantics { contentDescription = deletingLabel },
                            )
                        } else {
                            Text(
                                text = Strings.account_delete_confirm,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        }
    }

    AccountDestinationFrame(
        title = Strings.account,
        onBack = onBack,
        // Escape answers the dialog in front, as Back does on Android; only with none open does it
        // leave the screen.
        onEscape = {
            when {
                linkRequired -> if (!linking) onCancelLink()
                confirmDisconnect -> confirmDisconnect = false
                confirmDelete -> if (!accountDeletionState.requesting) {
                    confirmDelete = false
                    deleteEmail = ""
                }
                else -> onBack()
            }
        },
        modifier = modifier,
        overlay = { AccountDialogs() },
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = ShellSymbols.AccountCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(40.dp),
                    )
                }

                // Connected, this screen has one job: say how sync is going and offer the two
                // things that act on it. The hosted buttons and the disclosure that hides the
                // form are an invitation to connect, and there is nothing left to invite.
                if (connected != null) {
                    Spacer(Modifier.height(20.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ConnectedPanel(
                            connected = connected,
                            accountCreated = accountCreated,
                            syncStatus = syncStatus,
                            syncing = syncing,
                            onSync = onSync,
                            disconnecting = disconnecting,
                            disconnectFailure = disconnectFailure,
                            onDisconnect = { confirmDisconnect = true },
                            onForceDisconnect = onForceDisconnect,
                            managedSubscription = managedSubscription,
                            purchasesAvailable = purchasesAvailable,
                            onSubscribe = onSubscribe,
                            onManageSubscription = onManageSubscription,
                            onRedeemCoupon = onRedeemCoupon,
                            onDeleteAccount = { confirmDelete = true },
                        )
                    }
                    return@Column
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    text = Strings.account_title,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = Strings.account_supporting_text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(28.dp))
                GoogleSignInButton(
                    enabled = googleAvailable && !signingInWithGoogle,
                    signingIn = signingInWithGoogle,
                    onClick = onSignInWithGoogle,
                )

                if (signingInWithGoogle && googleSignInPage != null) {
                    Spacer(Modifier.height(10.dp))
                    GoogleBrowserPanel(page = googleSignInPage, onRetry = onRetryGoogle)
                }

                if (!googleAvailable) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        // A build with no client id says so here rather than letting the button
                        // fail with a provider error that reads like the person's Google account
                        // is at fault.
                        text = Strings.account_google_unconfigured,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                // Suppressed while the link dialog is up, because that dialog shows the same
                // failure with wording of its own. Two copies of one message is one of them
                // being read out twice by a screen reader.
                if (googleFailure != null && !linkRequired) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = failureMessage(googleFailure),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag(AccountTags.GOOGLE_STATUS),
                    )
                }

                // The email-and-password way into the **managed** account, unchanged in shape
                // from before Google was added. Neither of these asks for a server address:
                // there is one managed deployment and the build already knows it. Typing a host
                // belongs to Self host below, which is a different server and a different
                // project.
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { openCloudPanel(signUp = false) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (cloudExpanded && !cloudSignUp) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            contentColor = if (cloudExpanded && !cloudSignUp) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag(AccountTags.LOGIN),
                    ) {
                        Text(Strings.account_log_in)
                    }
                    Button(
                        onClick = { openCloudPanel(signUp = true) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (cloudExpanded && cloudSignUp) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            contentColor = if (cloudExpanded && cloudSignUp) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag(AccountTags.SIGN_UP),
                    ) {
                        Text(Strings.account_sign_up)
                    }
                }

                AnimatedVisibility(
                    visible = cloudExpanded,
                    enter = expandVertically(animationSpec = spatialMotion) +
                        fadeIn(animationSpec = effectsMotion),
                    exit = shrinkVertically(animationSpec = spatialMotion) +
                        fadeOut(animationSpec = effectsMotion),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = cloudEmail,
                            onValueChange = { cloudEmail = it },
                            label = { Text(Strings.account_email) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next,
                            ),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(AccountTags.CLOUD_EMAIL),
                        )
                        BoundPasswordField(
                            value = cloudPassword,
                            onValueChange = { cloudPassword = it },
                            label = { Text(Strings.account_password) },
                            // Only while signing up: the minimum is a rule about a *new*
                            // password, and showing it beside an existing one would read as a
                            // claim about the password the person already has.
                            supportingText = if (cloudSignUp) {
                                { Text(Strings.account_password_minimum) }
                            } else {
                                null
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(AccountTags.CLOUD_PASSWORD),
                        )

                        // Signing up only. A mistyped password here cannot be recovered without
                        // the emailed code, so the second field is the one chance to catch the
                        // typo before it becomes an account nobody can get into. Logging in needs
                        // no such thing: a wrong password there is simply refused.
                        val mismatch = cloudSignUp &&
                            cloudConfirmPassword.isNotEmpty() &&
                            cloudConfirmPassword != cloudPassword
                        if (cloudSignUp) {
                            BoundPasswordField(
                                value = cloudConfirmPassword,
                                onValueChange = { cloudConfirmPassword = it },
                                label = {
                                    Text(Strings.account_confirm_password)
                                },
                                isError = mismatch,
                                // Only once something has been typed: an empty second field is
                                // unfinished, not wrong, and colouring it red the moment the
                                // first one is filled scolds somebody who is still going.
                                supportingText = if (mismatch) {
                                    { Text(Strings.account_password_mismatch) }
                                } else {
                                    null
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(AccountTags.CLOUD_CONFIRM),
                            )
                        }

                        val busy = connection is ServerConnection.Connecting
                        Button(
                            onClick = {
                                if (cloudSignUp) {
                                    onSignUp(cloudEmail, cloudPassword)
                                } else {
                                    onLogIn(cloudEmail, cloudPassword)
                                }
                            },
                            // Signing up adds the contract's eight-character minimum, refused
                            // here rather than by the server: a shorter password has exactly one
                            // possible answer, and a round trip to hear it is a wasted one.
                            enabled = !busy &&
                                cloudEmail.isNotBlank() &&
                                if (cloudSignUp) {
                                    cloudPassword.length >= MIN_ACCOUNT_PASSWORD &&
                                        cloudConfirmPassword == cloudPassword
                                } else {
                                    cloudPassword.isNotEmpty()
                                },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(AccountTags.CLOUD_SUBMIT),
                        ) {
                            if (busy) {
                                LoadingIndicator(
                                    Modifier
                                        .size(18.dp)
                                        .testTag(AccountTags.CLOUD_PROGRESS)
                                        .semantics {
                                            contentDescription = if (cloudSignUp) {
                                                creatingLabel
                                            } else {
                                                connectingLabel
                                            }
                                        },
                                )
                            } else {
                                Text(
                                    if (cloudSignUp) {
                                        Strings.account_create
                                    } else {
                                        Strings.account_log_in
                                    },
                                )
                            }
                        }

                        ConnectionStatus(connection, AccountTags.CLOUD_STATUS)

                        if (!cloudSignUp && failedPasswordLogins >= 2) {
                            TextButton(
                                onClick = {
                                    passwordResetEmail = cloudEmail.trim()
                                    passwordResetOpen = true
                                },
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .testTag(AccountTags.FORGOT_PASSWORD),
                            ) {
                                Text(Strings.account_forgot_password)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = {
                        selfHostExpanded = !selfHostExpanded
                        // The two panels are alternatives, so opening one closes the other. Both
                        // open at once would show two email fields and two submit buttons.
                        if (selfHostExpanded) cloudExpanded = false
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(AccountTags.SELF_HOST),
                ) {
                    Text(Strings.account_self_host)
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = if (selfHostExpanded) {
                            NavigationSymbols.ExpandLess
                        } else {
                            NavigationSymbols.ExpandMore
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }

                AnimatedVisibility(
                    visible = selfHostExpanded,
                    enter = expandVertically(animationSpec = spatialMotion) +
                        fadeIn(animationSpec = effectsMotion),
                    exit = shrinkVertically(animationSpec = spatialMotion) +
                        fadeOut(animationSpec = effectsMotion),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = Strings.account_self_host_description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            label = { Text(Strings.account_server_url) },
                            placeholder = { Text(Strings.account_server_url_example) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Next,
                            ),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(AccountTags.SERVER_URL),
                        )
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text(Strings.account_email) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next,
                            ),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(AccountTags.EMAIL),
                        )
                        BoundPasswordField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(Strings.account_password) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(AccountTags.PASSWORD),
                        )

                        val connecting = connection is ServerConnection.Connecting
                        Button(
                            onClick = { onConnect(serverUrl, email, password) },
                            // Blank fields are refused here rather than by the server: all three
                            // are required by the contract, so a round trip could only come back
                            // saying so.
                            enabled = !connecting &&
                                serverUrl.isNotBlank() &&
                                email.isNotBlank() &&
                                password.isNotEmpty(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(AccountTags.CONNECT),
                        ) {
                            if (connecting) {
                                // Uncontained and sized to the label it replaces — an indicator
                                // with its own container inside a button is a container in a
                                // container.
                                LoadingIndicator(
                                    Modifier
                                        .size(18.dp)
                                        .testTag(AccountTags.CONNECT_PROGRESS)
                                        .semantics { contentDescription = connectingLabel },
                                )
                            } else {
                                Text(Strings.account_connect)
                            }
                        }

                        ConnectionStatus(connection, AccountTags.CONNECT_STATUS)
                    }
                }
            }
        }
    }

}

/** The full-window frame both Account destinations share: see [DestinationFrame]. */
@Composable
internal fun AccountDestinationFrame(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onEscape: () -> Unit = onBack,
    overlay: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) = DestinationFrame(title, onBack, AccountTags.SCREEN, AccountTags.BACK, modifier, onEscape, overlay, content)

/**
 * The managed password-recovery destination.
 *
 * The server deliberately gives the same first response for every email, so this screen never
 * claims an account exists. Its second step mirrors the atomic contract: the server validates the
 * eight-digit code and installs the new password in the same request.
 */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun PasswordResetScreen(
    initialEmail: String,
    state: PasswordResetUiState,
    onRequestCode: (String) -> Unit,
    onComplete: (email: String, code: String, password: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    val requestingLabel = Strings.account_reset_sending
    val changingLabel = Strings.account_reset_changing

    AccountDestinationFrame(
        title = Strings.account_reset_title,
        onBack = onBack,
        modifier = modifier,
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.completed) {
                    Text(
                        text = Strings.account_reset_complete_title,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = Strings.account_reset_complete_body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(AccountTags.RESET_STATUS),
                    )
                    Button(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(Strings.account_reset_back_to_login)
                    }
                } else if (!state.codeRequested) {
                    Text(
                        text = Strings.account_reset_email_heading,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = Strings.account_reset_email_body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(Strings.account_email) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Done,
                        ),
                        singleLine = true,
                        enabled = !state.requestingCode,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(AccountTags.RESET_EMAIL),
                    )
                    Button(
                        onClick = { onRequestCode(email) },
                        enabled = email.isNotBlank() && !state.requestingCode,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(AccountTags.RESET_REQUEST),
                    ) {
                        if (state.requestingCode) {
                            LoadingIndicator(
                                Modifier
                                    .size(18.dp)
                                    .semantics { contentDescription = requestingLabel },
                            )
                        } else {
                            Text(Strings.account_reset_send_code)
                        }
                    }
                } else {
                    Text(
                        text = Strings.account_reset_code_heading,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = Strings.account_reset_code_body(email.trim()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { typed ->
                            code = typed.filter(Char::isDigit).take(RESET_CODE_LENGTH)
                        },
                        label = { Text(Strings.account_reset_code) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Next,
                        ),
                        singleLine = true,
                        enabled = !state.changingPassword,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(AccountTags.RESET_CODE),
                    )
                    BoundPasswordField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text(Strings.account_reset_new_password) },
                        supportingText = {
                            Text(Strings.account_password_minimum)
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next,
                        ),
                        enabled = !state.changingPassword,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(AccountTags.RESET_PASSWORD),
                    )
                    val passwordsMismatch = confirmPassword.isNotEmpty() &&
                        confirmPassword != newPassword
                    BoundPasswordField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = {
                            Text(Strings.account_confirm_password)
                        },
                        isError = passwordsMismatch,
                        supportingText = if (passwordsMismatch) {
                            { Text(Strings.account_password_mismatch) }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        enabled = !state.changingPassword,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(AccountTags.RESET_CONFIRM_PASSWORD),
                    )
                    Button(
                        onClick = { onComplete(email, code, newPassword) },
                        enabled = !state.changingPassword &&
                            code.length == RESET_CODE_LENGTH &&
                            newPassword.length >= MIN_ACCOUNT_PASSWORD &&
                            confirmPassword == newPassword,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(AccountTags.RESET_COMPLETE),
                    ) {
                        if (state.changingPassword) {
                            LoadingIndicator(
                                Modifier
                                    .size(18.dp)
                                    .semantics { contentDescription = changingLabel },
                            )
                        } else {
                            Text(Strings.account_reset_change_password)
                        }
                    }
                }

                state.failure?.let { failure ->
                    Text(
                        text = passwordResetFailureMessage(failure),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(AccountTags.RESET_STATUS),
                    )
                }
            }
        }
    }
}

/**
 * Sign in with Google, drawn as the screen's primary action.
 *
 * A neutral container rather than the scheme's `primary`, which is Google's requirement rather than
 * a taste call: the four-colour G is a brand mark that may not be recoloured and is specified
 * against a light or dark neutral. `surfaceContainerHighest` with `onSurface` is the M3 token pair
 * that lands closest in both themes.
 *
 * The spinner replaces the whole label, so the button carries an explicit content description for as
 * long as it is up; without it the control loses its accessible name mid-request.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GoogleSignInButton(
    enabled: Boolean,
    signingIn: Boolean,
    onClick: () -> Unit,
) {
    val signingInLabel = Strings.account_google_signing_in

    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = ButtonDefaults.contentPaddingFor(
            buttonHeight = ButtonDefaults.MediumContainerHeight,
            hasStartIcon = true,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ButtonDefaults.MediumContainerHeight)
            .testTag(AccountTags.GOOGLE),
    ) {
        if (signingIn) {
            LoadingIndicator(
                Modifier
                    .size(18.dp)
                    .testTag(AccountTags.GOOGLE_PROGRESS)
                    .semantics { contentDescription = signingInLabel },
            )
        } else {
            Icon(
                imageVector = AccountSymbols.GoogleG,
                contentDescription = null,
                // Untinted: the mark carries its own four colours and tinting it would flatten it
                // to one.
                tint = Color.Unspecified,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(Strings.account_google_sign_in)
        }
    }
}

/**
 * What to do while the Google sign-in waits on a browser that may never come back: the default
 * browser did not open, or Google showed an error there. Copy link puts Google's page on the
 * clipboard for another browser on this computer — its answer still comes back to this app's
 * loopback address — and Try again starts over with a fresh challenge.
 */
@Composable
private fun GoogleBrowserPanel(page: String, onRetry: () -> Unit) {
    val clipboard = rememberTextClipboard()
    // Per page, so a new attempt's link is not announced as already copied.
    var copied by remember(page) { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountTags.GOOGLE_BROWSER_PANEL),
    ) {
        Text(
            text = Strings.account_google_browser_waiting,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(page))
                    copied = true
                },
                modifier = Modifier
                    .testTag(AccountTags.GOOGLE_COPY_LINK)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Icon(
                    imageVector = if (copied) AccountSymbols.CheckCircle else DocumentSymbols.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(if (copied) Strings.account_google_link_copied else Strings.account_google_copy_link)
            }
            TextButton(onClick = onRetry, modifier = Modifier.testTag(AccountTags.GOOGLE_TRY_AGAIN)) {
                Icon(
                    imageVector = AccountSymbols.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(Strings.account_google_try_again)
            }
        }
    }
}

/**
 * What the screen shows once this installation holds a device token.
 *
 * A status dashboard rather than a checkout page: connection, storage and sync read top to bottom,
 * so somebody can open it, see that notes are syncing, and leave. The two actions that end the
 * relationship sit apart at the foot, under their own heading.
 *
 * Green, from the desktop theme's success colour rather than from the colour scheme, because the
 * scheme has no "good" colour. The tinted plate behind it is that same green at low alpha, so one
 * hue carries the whole state. The check mark is knocked out in the scheme's surface colour.
 */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun ConnectedPanel(
    connected: ServerConnection.Connected,
    /** Only the sign-in that created the account says so, and only for that session. */
    accountCreated: Boolean,
    syncStatus: SyncStatus,
    syncing: Boolean,
    onSync: () -> Unit,
    disconnecting: Boolean,
    disconnectFailure: ConnectFailure?,
    onDisconnect: () -> Unit,
    onForceDisconnect: () -> Unit,
    managedSubscription: ManagedSubscriptionState,
    purchasesAvailable: Boolean,
    onSubscribe: () -> Unit,
    onManageSubscription: () -> Unit,
    onRedeemCoupon: (String) -> Unit,
    onDeleteAccount: () -> Unit,
) {
    val green = LocalDesktopColors.current.success
    val connectedSyncingDescription = Strings.account_syncing
    val disconnectingDescription = Strings.account_disconnecting

    Surface(
        shape = MaterialTheme.shapes.large,
        color = green.copy(alpha = CONNECTED_PLATE_ALPHA),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountTags.CONNECTED),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(green, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = ViewSymbols.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        // "Account created" is the one thing the single Google button cannot show
                        // any other way: the server is the only party that knows whether it found
                        // the account or made it, and it says so once, in the response.
                        text = if (accountCreated) {
                            Strings.account_connected_created
                        } else {
                            Strings.account_connected_title
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = green,
                        modifier = Modifier.testTag(AccountTags.CONNECT_STATUS),
                    )
                    connected.email?.let { email ->
                        val google = connected.authProvider == AccountAuthProvider.Google
                        val provider = if (google) {
                            Strings.account_provider_google
                        } else {
                            Strings.account_provider_password
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.testTag(AccountTags.CONNECTED_IDENTITY),
                        ) {
                            Icon(
                                imageVector = if (google) AccountSymbols.GoogleG else ObjectSymbols.Lock,
                                contentDescription = provider,
                                tint = if (google) Color.Unspecified else green,
                                modifier = Modifier
                                    .size(18.dp)
                                    .testTag(AccountTags.CONNECTED_PROVIDER),
                            )
                            Column {
                                Text(
                                    text = email,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = provider,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Text(
                text = Strings.account_connected_server,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            // Plain text, not a link: this is the address the device syncs with, and opening it in a
            // browser shows nothing a person can use.
            Text(
                text = connected.serverUrl,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    val membershipRequired = syncStatus.failure ==
        SyncRunResult.Failed(PermanentSyncFailure.MembershipRequired)

    if (managedSubscription.visible) {
        ManagedSubscriptionPanel(
            state = managedSubscription,
            membershipRequired = membershipRequired,
            purchasesAvailable = purchasesAvailable,
            onSubscribe = onSubscribe,
            onManageSubscription = onManageSubscription,
            onRedeemCoupon = onRedeemCoupon,
        )
    }

    Button(
        onClick = onSync,
        enabled = !syncing && !disconnecting,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountTags.SYNC),
    ) {
        if (syncing) {
            LoadingIndicator(
                Modifier
                    .size(18.dp)
                    .testTag(AccountTags.SYNC_PROGRESS)
                    .semantics {
                        contentDescription = connectedSyncingDescription
                    },
            )
        } else {
            Text(Strings.account_sync_now)
        }
    }

    SyncStatusLine(syncStatus)

    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(
        text = Strings.account_settings,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(top = 4.dp)
            .semantics { heading() },
    )

    // Delete exists only for the managed account; a self-hosted server's accounts belong to whoever
    // runs it.
    val settingsCount = if (managedSubscription.visible) 2 else 1
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        SegmentedListItem(
            onClick = onDisconnect,
            shapes = ListItemDefaults.segmentedShapes(index = 0, count = settingsCount),
            enabled = !syncing && !disconnecting,
            leadingContent = {
                Icon(imageVector = AccountSymbols.Logout, contentDescription = null)
            },
            trailingContent = {
                if (disconnecting) {
                    LoadingIndicator(
                        Modifier
                            .size(24.dp)
                            .semantics { contentDescription = disconnectingDescription },
                    )
                } else {
                    Icon(imageVector = AccountSymbols.ChevronRight, contentDescription = null)
                }
            },
            modifier = Modifier.testTag(AccountTags.DISCONNECT),
        ) {
            Text(Strings.account_disconnect_account)
        }

        if (managedSubscription.visible) {
            SegmentedListItem(
                onClick = onDeleteAccount,
                shapes = ListItemDefaults.segmentedShapes(index = 1, count = settingsCount),
                enabled = !disconnecting,
                colors = ListItemDefaults.segmentedColors(
                    contentColor = MaterialTheme.colorScheme.error,
                    leadingContentColor = MaterialTheme.colorScheme.error,
                ),
                leadingContent = {
                    Icon(imageVector = ObjectSymbols.Delete, contentDescription = null)
                },
                trailingContent = {
                    Icon(imageVector = AccountSymbols.ChevronRight, contentDescription = null)
                },
                modifier = Modifier.testTag(AccountTags.DELETE_ACCOUNT),
            ) {
                Text(Strings.account_delete_action)
            }
        }
    }

    // A failed revoke is not a locked door. The stored registration is what this screen shows in
    // place of the connect form, so without a local-only way out a server that never answers again
    // would keep this installation for itself — see `SyncAccounts.forgetConnection`. It appears only
    // here, after the polite route has actually been tried and has actually failed, and it says what
    // it leaves behind rather than asking a second time whether the person is sure.
    if (disconnectFailure != null) {
        Text(
            text = Strings.account_disconnect_failed,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = Strings.account_disconnect_anyway_note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(
            onClick = onForceDisconnect,
            enabled = !disconnecting,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(AccountTags.DISCONNECT_ANYWAY),
        ) {
            Text(Strings.account_disconnect_anyway)
        }
    }
}

/**
 * How the managed storage card reads. Each value is one layout, so a plan somebody is paying for is
 * never shown beside a checkout.
 */
internal enum class StoragePlan {
    /** The first read is in flight and nothing is known yet. */
    Checking,

    /** The first read failed; the card shows only the failure. */
    Unknown,

    /** Paid, and the server says Play will renew it. */
    Renewing,

    /** Paid, auto-renewal off; access continues until the period ends. */
    EndingWithoutRenewal,

    /** Play's grace period: storage stays on while Play retries the payment. */
    PaymentRetrying,
    OnHold,
    Paused,
    PaymentPending,

    /** Active through a coupon alone, with no Play subscription behind it. */
    Coupon,
    NotSubscribed,
}

/**
 * "Renews" only when the server confirms auto-renewal; an expiry date on its own says nothing
 * about whether Google will charge again.
 */
internal fun storagePlan(state: ManagedSubscriptionState): StoragePlan {
    val status = state.status ?: return when {
        state.loading -> StoragePlan.Checking
        // A failed read is not a "no": offering a purchase here would be offering it to somebody
        // who may already be paying.
        state.failure != null -> StoragePlan.Unknown
        else -> StoragePlan.NotSubscribed
    }
    return when (status.paidState) {
        PaidSubscriptionState.Active -> if (status.autoRenewing) {
            StoragePlan.Renewing
        } else {
            StoragePlan.EndingWithoutRenewal
        }
        PaidSubscriptionState.Canceled -> if (status.active) {
            StoragePlan.EndingWithoutRenewal
        } else {
            StoragePlan.NotSubscribed
        }
        PaidSubscriptionState.Grace -> StoragePlan.PaymentRetrying
        PaidSubscriptionState.OnHold -> StoragePlan.OnHold
        PaidSubscriptionState.Paused -> StoragePlan.Paused
        PaidSubscriptionState.Pending -> StoragePlan.PaymentPending
        PaidSubscriptionState.Expired,
        PaidSubscriptionState.PendingCanceled,
        PaidSubscriptionState.Unknown,
        null,
        -> when {
            status.active -> StoragePlan.Coupon
            state.purchasePending -> StoragePlan.PaymentPending
            else -> StoragePlan.NotSubscribed
        }
    }
}

/**
 * Managed-service plan card; never composed for a self-hosted registration.
 *
 * Buying and coupon entry appear only while no paid subscription stands behind the storage. Every
 * other state offers at most one action, and it opens the paid plan's own page — Google Play's, or
 * Stripe's customer portal for a card plan — rather than a purchase. Which checkout Subscribe opens
 * is [ManagedSubscriptionState.checkout]'s decision, not this card's.
 */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun ManagedSubscriptionPanel(
    state: ManagedSubscriptionState,
    membershipRequired: Boolean,
    purchasesAvailable: Boolean,
    onSubscribe: () -> Unit,
    onManageSubscription: () -> Unit,
    onRedeemCoupon: (String) -> Unit,
) {
    var coupon by rememberSaveable { mutableStateOf("") }
    var couponOpen by rememberSaveable { mutableStateOf(false) }
    val checkingDescription = Strings.account_subscription_checking
    val buyingDescription = Strings.account_subscription_buying
    val openingCheckoutDescription = Strings.account_subscription_opening_checkout
    val spatialMotion = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    val effectsMotion = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val status = state.status
    val plan = storagePlan(state)
    val sellsByCard = state.checkout == CheckoutProvider.Stripe
    // The paid plan's wording and page follow whoever bills it, not whichever checkout this install
    // would offer: a card plan seen from a Play install is still a card plan.
    val paidByCard = status?.paidProvider == PaidProvider.Stripe
    // A card plan's page is Stripe's portal, which only a non-Play install may open.
    val canManage = !paidByCard || state.cardManagementAvailable
    val offerPrice = if (sellsByCard) state.cardPrice else state.formattedPrice
    val paidPrice = if (paidByCard) state.cardPrice else state.formattedPrice

    @Composable
    fun PurchaseAction(filled: Boolean) {
        if (sellsByCard) {
            CardCheckoutButton(
                onClick = onSubscribe,
                enabled = !state.purchasing && !state.loading,
                filled = filled,
                loading = state.purchasing,
                loadingDescription = openingCheckoutDescription,
                requiredForSync = membershipRequired,
                modifier = Modifier.testTag(AccountTags.SUBSCRIBE),
            )
        } else if (state.playPurchaseOwned) {
            // Play already holds a purchase the server has not accepted; buying again would only
            // return "already owned", and the failure line below says why it was not accepted.
            GooglePlayActionButton(
                label = Strings.account_subscription_manage,
                onClick = onManageSubscription,
                enabled = !state.purchasing,
                filled = filled,
                modifier = Modifier.testTag(AccountTags.MANAGE_SUBSCRIPTION),
            )
        } else if (!purchasesAvailable) {
            // The desktop has no Google Play to open a purchase sheet from, and this server sells
            // nothing by card. The plan itself is the server's, so one bought on Android shows up
            // here by itself.
            Text(
                text = Strings.account_subscription_android_only,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(AccountTags.SUBSCRIBE_ON_ANDROID),
            )
        } else {
            GooglePlayActionButton(
                label = Strings.account_subscription_subscribe,
                onClick = onSubscribe,
                enabled = state.productAvailable && !state.purchasePending &&
                    !state.purchasing && !state.loading,
                filled = filled,
                loading = state.purchasing,
                loadingDescription = buyingDescription,
                requiredForSync = membershipRequired,
                modifier = Modifier.testTag(AccountTags.SUBSCRIBE),
            )
        }
    }

    @Composable
    fun CouponEntry() {
        TextButton(
            onClick = { couponOpen = !couponOpen },
            modifier = Modifier.testTag(AccountTags.COUPON_TOGGLE),
        ) {
            Text(Strings.account_subscription_coupon_prompt)
        }
        AnimatedVisibility(
            visible = couponOpen,
            enter = expandVertically(animationSpec = spatialMotion) +
                fadeIn(animationSpec = effectsMotion),
            exit = shrinkVertically(animationSpec = spatialMotion) +
                fadeOut(animationSpec = effectsMotion),
        ) {
            CouponRedemptionControls(
                coupon = coupon,
                onCouponChange = { coupon = it },
                redeeming = state.redeemingCoupon,
                onRedeem = { onRedeemCoupon(coupon) },
            )
        }
    }

    OutlinedCard(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountTags.SUBSCRIPTION),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Strings.account_subscription_title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (state.loading) {
                    LoadingIndicator(
                        Modifier
                            .size(24.dp)
                            .semantics { contentDescription = checkingDescription },
                    )
                } else {
                    StoragePlanBadge(plan = plan, active = status?.active == true)
                }
            }

            when (plan) {
                StoragePlan.Checking -> PlanDetail(checkingDescription)

                StoragePlan.Unknown -> Unit

                StoragePlan.Renewing -> {
                    PlanHeadline(Strings.account_subscription_active_headline)
                    (status?.paidValidUntil ?: status?.validUntil)?.let {
                        PlanDetail(
                            Strings.account_subscription_renews(formattedSubscriptionDate(it)),
                        )
                    }
                    // A coupon never defers Play's renewal, so both dates are true at once.
                    status?.promotionalValidUntil?.let {
                        PlanDetail(
                            Strings.account_subscription_promotion(formattedSubscriptionDate(it)),
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = paidPrice?.let {
                                Strings.account_subscription_price_short(it)
                            }.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (canManage) {
                            TextButton(
                                onClick = onManageSubscription,
                                enabled = !state.purchasing,
                                modifier = Modifier.testTag(AccountTags.MANAGE_SUBSCRIPTION),
                            ) {
                                Text(Strings.account_subscription_manage_link)
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    imageVector = AccountSymbols.ArrowOutward,
                                    // The label already names the action; the arrow only says it
                                    // leaves the app.
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                StoragePlan.EndingWithoutRenewal -> {
                    PlanHeadline(availableUntilText(status?.validUntil))
                    PlanDetail(Strings.account_subscription_renewal_off)
                    // Neither provider offers an in-app "resubscribe" for a subscription that is
                    // still running; Play's subscription centre and Stripe's portal are where
                    // auto-renewal is turned back on.
                    if (canManage) {
                        // Filled: Material's outlined button reads as disabled on the dark card.
                        ProviderManagementButton(
                            label = Strings.account_subscription_renew,
                            onClick = onManageSubscription,
                            enabled = !state.purchasing,
                            filled = true,
                        )
                    }
                }

                StoragePlan.PaymentRetrying -> {
                    PlanHeadline(Strings.account_subscription_active_headline)
                    PlanDetail(
                        if (paidByCard) {
                            Strings.account_subscription_card_grace
                        } else {
                            Strings.account_subscription_paid_grace
                        },
                    )
                    if (canManage) {
                        ProviderManagementButton(
                            label = Strings.account_subscription_fix_payment,
                            onClick = onManageSubscription,
                            enabled = !state.purchasing,
                        )
                    }
                }

                StoragePlan.OnHold -> {
                    PlanHeadline(
                        if (paidByCard) {
                            Strings.account_subscription_card_on_hold
                        } else {
                            Strings.account_subscription_paid_on_hold
                        },
                    )
                    if (canManage) {
                        ProviderManagementButton(
                            label = Strings.account_subscription_fix_payment,
                            onClick = onManageSubscription,
                            enabled = !state.purchasing,
                            filled = status?.active != true,
                        )
                    }
                }

                StoragePlan.Paused -> {
                    PlanHeadline(
                        if (paidByCard) {
                            Strings.account_subscription_card_paused
                        } else {
                            Strings.account_subscription_paid_paused
                        },
                    )
                    if (canManage) {
                        ProviderManagementButton(
                            label = if (paidByCard) {
                                Strings.account_subscription_manage_card
                            } else {
                                Strings.account_subscription_manage
                            },
                            onClick = onManageSubscription,
                            enabled = !state.purchasing,
                        )
                    }
                }

                StoragePlan.PaymentPending ->
                    PlanHeadline(
                        if (paidByCard) {
                            Strings.account_subscription_card_pending
                        } else {
                            Strings.account_subscription_pending
                        },
                    )

                StoragePlan.Coupon -> {
                    PlanHeadline(availableUntilText(status?.validUntil))
                    PlanDetail(Strings.account_subscription_coupon_no_renewal)
                    PurchaseAction(filled = false)
                    CouponEntry()
                }

                StoragePlan.NotSubscribed -> {
                    PlanHeadline(Strings.account_subscription_pitch)
                    offerPrice?.let {
                        PlanDetail(Strings.account_subscription_price(it))
                    }
                    if (status?.paidState == PaidSubscriptionState.Expired) {
                        PlanDetail(
                            if (paidByCard) {
                                Strings.account_subscription_card_expired
                            } else {
                                Strings.account_subscription_paid_expired
                            },
                        )
                    }
                    if (state.purchasePending) {
                        Text(
                            text = Strings.account_subscription_pending,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    PurchaseAction(filled = true)
                    CouponEntry()
                }
            }

            if (state.purchaseConfirmed) {
                Text(
                    text = Strings.account_subscription_confirmed,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalDesktopColors.current.success,
                )
            }
            state.couponGrant?.let { grant ->
                Text(
                    text = Strings.account_subscription_coupon_applied(grant.monthsGranted),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalDesktopColors.current.success,
                )
            }
            state.failure?.let { failure ->
                Text(
                    text = subscriptionFailureMessage(failure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PlanHeadline(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag(AccountTags.SUBSCRIPTION_STATUS),
    )
}

@Composable
private fun PlanDetail(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun availableUntilText(validUntil: String?): String = if (validUntil != null) {
    Strings.account_subscription_available_until(formattedSubscriptionDate(validUntil))
} else {
    Strings.account_subscription_active_headline
}

/**
 * The plan's state in one word, in the colour the connected card uses for the same meaning: the
 * same green for "working", the error container for a plan that has stopped for want of payment.
 */
@Composable
private fun StoragePlanBadge(plan: StoragePlan, active: Boolean) {
    val green = LocalDesktopColors.current.success
    val colors = MaterialTheme.colorScheme
    val (label, container, content) = when {
        plan == StoragePlan.Checking || plan == StoragePlan.Unknown -> return
        // On hold or paused while a coupon still covers the storage: the storage is what the badge
        // is about, and the line under it explains the Play side.
        active -> Triple(
            Strings.account_subscription_badge_active,
            green.copy(alpha = CONNECTED_PLATE_ALPHA),
            green,
        )
        plan == StoragePlan.OnHold -> Triple(
            Strings.account_subscription_badge_on_hold,
            colors.errorContainer,
            colors.onErrorContainer,
        )
        plan == StoragePlan.PaymentPending -> Triple(
            Strings.account_subscription_badge_pending,
            colors.tertiaryContainer,
            colors.onTertiaryContainer,
        )
        plan == StoragePlan.Paused -> Triple(
            Strings.account_subscription_badge_paused,
            colors.surfaceContainerHighest,
            colors.onSurfaceVariant,
        )
        else -> Triple(
            Strings.account_subscription_badge_none,
            colors.surfaceContainerHighest,
            colors.onSurfaceVariant,
        )
    }
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = Modifier.testTag(AccountTags.SUBSCRIPTION_BADGE),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/**
 * Renew, fix payment, or resume: each only opens the paid plan's own page — Google Play's for a Play
 * subscription, Stripe's customer portal for a card one.
 */
@Composable
private fun ProviderManagementButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    filled: Boolean = false,
) {
    val modifier = Modifier
        .fillMaxWidth()
        .testTag(AccountTags.MANAGE_SUBSCRIPTION)
    if (filled) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
    }
}

/**
 * Subscribe on an install that sells through Stripe Checkout. It mirrors [GooglePlayActionButton]
 * so the card keeps one shape whichever checkout it offers, with the card glyph in place of Google's
 * mark: nothing about this payment involves Google.
 */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun CardCheckoutButton(
    onClick: () -> Unit,
    enabled: Boolean,
    filled: Boolean,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    loadingDescription: String,
    /** Sync has been refused for want of a plan; said to accessibility, since the colour cannot. */
    requiredForSync: Boolean = false,
) {
    val requiredDescription = Strings.account_subscription_required_state
    val buttonModifier = modifier
        .fillMaxWidth()
        .then(
            if (requiredForSync) {
                Modifier.semantics { stateDescription = requiredDescription }
            } else {
                Modifier
            },
        )
    val content: @Composable RowScope.() -> Unit = {
        if (loading) {
            LoadingIndicator(
                Modifier
                    .size(18.dp)
                    .semantics { contentDescription = loadingDescription },
            )
        } else {
            Icon(
                imageVector = AccountSymbols.CreditCard,
                contentDescription = null,
                modifier = Modifier
                    .size(ButtonDefaults.IconSize)
                    .testTag(AccountTags.CARD_CHECKOUT_MARK),
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(Strings.account_subscription_subscribe_card)
        }
    }
    val colors = ButtonDefaults.buttonColors(
        containerColor = Color(0xFF00D4FF),
        contentColor = Color.Black,
    )
    if (filled) {
        Button(onClick = onClick, enabled = enabled, modifier = buttonModifier, colors = colors, content = content)
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
            colors = colors,
            content = content,
        )
    }
}

/**
 * The button that opens Google Play, labelled for that service rather than for Google Pay.
 *
 * The full-colour Google mark stays untinted on its own white disc, so it keeps all four colours on
 * the filled primary container as well as on the outlined surface.
 */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun GooglePlayActionButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    filled: Boolean,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    loadingDescription: String = label,
    /** Sync has been refused for want of a plan; said to accessibility, since the colour cannot. */
    requiredForSync: Boolean = false,
) {
    val requiredDescription = Strings.account_subscription_required_state
    val buttonModifier = modifier
        .fillMaxWidth()
        .then(
            if (requiredForSync) {
                Modifier.semantics { stateDescription = requiredDescription }
            } else {
                Modifier
            },
        )
    val content: @Composable RowScope.() -> Unit = {
        if (loading) {
            LoadingIndicator(
                Modifier
                    .size(18.dp)
                    .semantics { contentDescription = loadingDescription },
            )
        } else {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AccountSymbols.GoogleG,
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .size(16.dp)
                        .testTag(AccountTags.GOOGLE_PLAY_MARK),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(label)
        }
    }
    if (filled) {
        Button(onClick = onClick, enabled = enabled, modifier = buttonModifier, content = content)
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
            content = content,
        )
    }
}

/** Compact side-by-side controls where space permits; narrow windows retain a usable stacked form. */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun CouponRedemptionControls(
    coupon: String,
    onCouponChange: (String) -> Unit,
    redeeming: Boolean,
    onRedeem: () -> Unit,
) {
    val redeemingDescription = Strings.account_subscription_coupon_redeeming

    @Composable
    fun CouponField(modifier: Modifier) {
        OutlinedTextField(
            value = coupon,
            onValueChange = onCouponChange,
            label = { Text(Strings.account_subscription_coupon_label) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            singleLine = true,
            modifier = modifier.testTag(AccountTags.COUPON),
        )
    }

    @Composable
    fun RedeemButton(modifier: Modifier) {
        Button(
            onClick = onRedeem,
            enabled = coupon.isNotBlank() && !redeeming,
            modifier = modifier.testTag(AccountTags.REDEEM_COUPON),
        ) {
            if (redeeming) {
                LoadingIndicator(
                    Modifier
                        .size(18.dp)
                        .semantics { contentDescription = redeemingDescription },
                )
            } else {
                Text(Strings.account_subscription_coupon_redeem)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 360.dp) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CouponField(Modifier.width(240.dp))
                RedeemButton(Modifier.widthIn(min = 104.dp))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CouponField(Modifier.fillMaxWidth())
                RedeemButton(Modifier.align(Alignment.End))
            }
        }
    }
}

internal fun formattedSubscriptionDate(value: String): String = runCatching {
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date.from(Instant.parse(value)))
}.getOrDefault(value)

private fun subscriptionFailureMessage(failure: ManagedSubscriptionFailure): String = when (failure) {
    ManagedSubscriptionFailure.PlayUnavailable -> Strings.account_subscription_error_play_unavailable
    ManagedSubscriptionFailure.PlayNetwork -> Strings.account_subscription_error_play_network
    ManagedSubscriptionFailure.ProductUnavailable -> Strings.account_subscription_error_product
    ManagedSubscriptionFailure.PlayConfiguration -> Strings.account_subscription_error_configuration
    ManagedSubscriptionFailure.InvalidCoupon -> Strings.account_subscription_error_coupon
    ManagedSubscriptionFailure.CouponExpired -> Strings.account_subscription_error_coupon_expired
    ManagedSubscriptionFailure.CouponAlreadyRedeemed -> Strings.account_subscription_error_coupon_used
    ManagedSubscriptionFailure.InvalidPurchase -> Strings.account_subscription_error_purchase
    ManagedSubscriptionFailure.PurchaseAlreadyClaimed -> Strings.account_subscription_error_purchase_owner
    ManagedSubscriptionFailure.AlreadySubscribed -> Strings.account_subscription_error_already_subscribed
    ManagedSubscriptionFailure.ServerBillingUnavailable -> Strings.account_subscription_error_server_billing
    ManagedSubscriptionFailure.InvalidRequest -> Strings.account_subscription_error_request
    ManagedSubscriptionFailure.ServerUnreachable -> Strings.account_subscription_error_server_unreachable
    ManagedSubscriptionFailure.CardServerUnreachable -> Strings.account_subscription_error_card_unreachable
    ManagedSubscriptionFailure.BrowserUnavailable -> Strings.account_subscription_error_browser
    ManagedSubscriptionFailure.ServerError -> Strings.account_subscription_error_server
}

/**
 * The one line that says whether sync is actually working.
 *
 * It reports automatic outbox and remote-event runs, not just the button's, which is the whole
 * point: the failure worth catching is the one nobody asked for. A failure wins over the timestamp
 * because it is the news; the timestamp survives underneath it in [SyncStatus] and is what a later
 * reading of this line falls back to once the trouble clears.
 *
 * Nothing announces an automatic run in progress; the button beside it already spins when a person
 * asks for one.
 */
@Composable
private fun SyncStatusLine(status: SyncStatus) {
    val failure = status.failure
    val green = LocalDesktopColors.current.success
    val (message, isError) = when {
        failure != null -> syncFailureText(failure) to true
        status.lastSucceededAtMillis != null ->
            syncedAtText(status.lastSucceededAtMillis, status.lastSummary) to false
        else -> Strings.account_sync_never to false
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The same glyph the top bar badges the account button with, so the badge somebody saw on
        // the way here is recognisable beside the sentence that explains it. Only for the state it
        // actually means: every other failure has a different cause and a different fix.
        if (status.serverUnreachable) {
            Icon(
                imageVector = AccountSymbols.CloudOff,
                // Decoration beside text that already says it.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .size(16.dp)
                    .testTag(AccountTags.SYNC_OFFLINE),
            )
        } else if (!isError && status.lastSucceededAtMillis != null) {
            Icon(
                imageVector = AccountSymbols.CheckCircle,
                contentDescription = null,
                tint = green,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else green,
            modifier = Modifier.testTag(AccountTags.SYNC_STATUS),
        )
    }
}

/**
 * "Synced just now", and what that run moved when it moved anything.
 *
 * The counts are appended only when they are not both zero: repeatedly reporting "Pulled 0 · Pushed
 * 0" trains its reader to stop looking, and "nothing to do" is already what a bare timestamp means.
 *
 * Pictures are counted separately and shown only when a run carried any, because they are the one
 * thing that makes a sync take noticeably long. A row and its picture are two different units, so
 * they are two different numbers.
 *
 * Relative rather than a clock time, because the question is "is this computer current".
 */
@Composable
private fun syncedAtText(atMillis: Long, summary: SyncSummary?): String {
    val now = System.currentTimeMillis()
    val elapsed = now - atMillis
    val whenText = if (elapsed < MINUTE_MILLIS) {
        Strings.account_sync_just_now
    } else {
        Strings.account_sync_relative(relativeTimeSpan(atMillis, now))
    }
    if (summary == null) return whenText
    val pictures = if (summary.pictures == 0) {
        ""
    } else {
        Strings.account_sync_pictures(summary.pictures)
    }
    if (summary.pulled == 0 && summary.pushed == 0) return whenText + pictures
    return whenText +
        Strings.account_sync_counts(summary.pulled, summary.pushed) +
        pictures
}

/**
 * Android's `DateUtils.getRelativeTimeSpanString(at, now, MINUTE_IN_MILLIS)`: minutes, then hours,
 * then days ago, and the date once it is a week old.
 */
@Composable
private fun relativeTimeSpan(atMillis: Long, now: Long): String {
    val elapsed = (now - atMillis).coerceAtLeast(0L)
    return when {
        elapsed < HOUR_MILLIS -> (elapsed / MINUTE_MILLIS).toInt().let {
            pluralStringResource(Res.plurals.account_sync_minutes_ago, it, it)
        }
        elapsed < DAY_MILLIS -> (elapsed / HOUR_MILLIS).toInt().let {
            pluralStringResource(Res.plurals.account_sync_hours_ago, it, it)
        }
        elapsed < WEEK_MILLIS -> (elapsed / DAY_MILLIS).toInt().let {
            pluralStringResource(Res.plurals.account_sync_days_ago, it, it)
        }
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(atMillis))
    }
}

private fun syncFailureText(failure: SyncRunResult): String = when (failure) {
    // Transport trouble is normal on a laptop and resolves itself, so it says so rather than
    // reading as something the person has to go and fix.
    is SyncRunResult.Retryable -> Strings.account_sync_will_retry(failureMessage(failure.reason))
    is SyncRunResult.Failed -> syncFailureMessage(failure.reason)
    SyncRunResult.Revoked -> Strings.account_error_revoked
    // Not reachable: a success is not a failure. Exhaustive so the compiler keeps it that way.
    is SyncRunResult.Succeeded -> Strings.account_sync_up_to_date
}

private fun syncFailureMessage(reason: PermanentSyncFailure): String = when (reason) {
    PermanentSyncFailure.InvalidServerResponse -> Strings.account_sync_error_response
    PermanentSyncFailure.MembershipRequired -> Strings.account_sync_membership_required
    PermanentSyncFailure.StorageFull -> Strings.account_sync_storage_full
    PermanentSyncFailure.LocalData -> Strings.account_sync_error_local
    PermanentSyncFailure.ChangeTooLarge -> Strings.account_sync_error_large
    PermanentSyncFailure.MalformedChange -> Strings.account_sync_error_malformed
    PermanentSyncFailure.MissingParent -> Strings.account_sync_error_parent
    // Not "an error" so much as "you are behind": the cursor is deliberately parked so the changes
    // this build cannot store stay on the server rather than being skipped past.
    PermanentSyncFailure.UnsupportedKind -> Strings.account_sync_error_unsupported
}

private fun passwordResetFailureMessage(reason: PasswordResetFailure): String = when (reason) {
    PasswordResetFailure.InvalidRequest -> Strings.account_reset_error_request
    PasswordResetFailure.InvalidCode -> Strings.account_reset_error_code
    PasswordResetFailure.Unavailable -> Strings.account_reset_error_unavailable
    PasswordResetFailure.PayloadTooLarge -> Strings.account_reset_error_too_large
    PasswordResetFailure.Unreachable -> Strings.account_reset_error_unreachable
    PasswordResetFailure.ServerError -> Strings.account_reset_error_server
    PasswordResetFailure.NotAViveServer -> Strings.account_reset_error_not_vive
}

private fun accountDeletionFailureMessage(reason: AccountDeletionFailure): String = when (reason) {
    AccountDeletionFailure.NotConnected -> Strings.account_delete_error_not_connected
    AccountDeletionFailure.NotManaged -> Strings.account_delete_error_not_managed
    AccountDeletionFailure.InvalidRequest -> Strings.account_delete_error_request
    AccountDeletionFailure.Unavailable -> Strings.account_delete_error_unavailable
    AccountDeletionFailure.Unreachable -> Strings.account_delete_error_unreachable
    AccountDeletionFailure.ServerError -> Strings.account_delete_error_server
    AccountDeletionFailure.NotAViveServer -> Strings.account_delete_error_not_vive
    AccountDeletionFailure.Revoked -> Strings.account_delete_error_revoked
}

/** Enough tint to read as a state, not enough to compete with the card it sits on. */
private const val CONNECTED_PLATE_ALPHA = 0.14f

/**
 * The one line under the button that says how the last attempt ended.
 *
 * Nothing is shown while idle or in flight: the button is already saying it is working, and a
 * message that appears before there is news is noise. Success is not here either — it replaces the
 * form entirely, in [ConnectedPanel]. Failures are the common case a self-hoster meets first, so
 * they get the error colour and a sentence naming what to change.
 */
@Composable
private fun ConnectionStatus(connection: ServerConnection, testTag: String) {
    when (connection) {
        is ServerConnection.Failed -> Text(
            text = failureMessage(connection.reason),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(testTag),
        )

        is ServerConnection.Connected,
        ServerConnection.Idle,
        ServerConnection.Connecting,
        -> Unit
    }
}

/**
 * Every [ConnectFailure] gets its own sentence.
 *
 * Exhaustive on purpose — no `else` branch — so a failure added to the enum cannot reach the screen
 * as a blank line. The compiler is the reminder to write the message.
 */
internal fun failureMessage(reason: ConnectFailure): String = when (reason) {
    ConnectFailure.InvalidAddress -> Strings.account_error_address
    ConnectFailure.Unreachable -> Strings.account_error_unreachable
    ConnectFailure.InvalidCredentials -> Strings.account_error_credentials
    ConnectFailure.InvalidRequest -> Strings.account_error_request
    ConnectFailure.PayloadTooLarge -> Strings.account_error_too_large
    ConnectFailure.ServerError -> Strings.account_error_server
    ConnectFailure.MembershipRequired -> Strings.account_sync_membership_required
    ConnectFailure.StorageFull -> Strings.account_sync_storage_full
    ConnectFailure.NotAViveServer -> Strings.account_error_not_vive
    ConnectFailure.NotStored -> Strings.account_error_not_stored
    ConnectFailure.Revoked -> Strings.account_error_revoked
    ConnectFailure.InvalidChallenge -> Strings.account_error_challenge
    ConnectFailure.InvalidGoogleToken -> Strings.account_error_google_token
    ConnectFailure.GoogleUnavailable -> Strings.account_error_google_unavailable
    ConnectFailure.IdentityConflict -> Strings.account_error_identity_conflict
    ConnectFailure.IdempotencyConflict -> Strings.account_error_idempotency
    ConnectFailure.AccountUnavailable -> Strings.account_error_account_unavailable
    ConnectFailure.NoGoogleAccount -> Strings.account_error_no_google_account
    ConnectFailure.GoogleNotConfigured -> Strings.account_error_google_unconfigured
    ConnectFailure.SignupClosed -> Strings.account_error_signup_closed
    ConnectFailure.EmailTaken -> Strings.account_error_email_taken
    ConnectFailure.AccountCreatedNotRegistered -> Strings.account_error_created_not_registered
}

private const val RESET_CODE_LENGTH = 8

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
private const val DAY_MILLIS = 24 * HOUR_MILLIS
private const val WEEK_MILLIS = 7 * DAY_MILLIS
