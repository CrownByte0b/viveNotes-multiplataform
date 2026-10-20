// Ported from the Android app's instrumented `ui/account/AccountScreenTest.kt`. The cases are Android's;
// the harness is the desktop's Compose UI test, there is no soft keyboard to put away, and the
// purchase cases run with `purchasesAvailable` on, where the desktop itself has no Play store.
package com.vivenotes.ui.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import com.vivenotes.data.billing.CheckoutProvider
import com.vivenotes.data.billing.ManagedSubscriptionState
import com.vivenotes.data.sync.ConnectFailure
import com.vivenotes.data.sync.AccountDeletionFailure
import com.vivenotes.data.sync.AccountAuthProvider
import com.vivenotes.data.sync.ManagedSubscriptionStatus
import com.vivenotes.data.sync.PaidProvider
import com.vivenotes.data.sync.PaidSubscriptionState
import com.vivenotes.data.sync.PermanentSyncFailure
import com.vivenotes.data.sync.ServerConnection
import com.vivenotes.data.sync.SyncRunResult
import com.vivenotes.data.sync.SyncStatus
import com.vivenotes.data.sync.SyncSummary
import com.vivenotes.localization.Strings
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class AccountScreenTest {

    /**
     * Driven rather than replaced: the connection state is an input to the screen, so the suite
     * moves it the way the app will and sets content once. See `PageViewTest` for the pattern.
     */
    private var connection by mutableStateOf<ServerConnection>(ServerConnection.Idle)

    private val connectCalls = mutableListOf<Triple<String, String, String>>()
    private val logInCalls = mutableListOf<Pair<String, String>>()
    private val signUpCalls = mutableListOf<Pair<String, String>>()
    private var disconnects = 0
    private var forceDisconnects = 0
    private var disconnectFailure by mutableStateOf<ConnectFailure?>(null)
    private var syncs = 0
    private var syncing by mutableStateOf(false)
    private var syncStatus by mutableStateOf(SyncStatus())

    private var googleAvailable by mutableStateOf(true)
    private var signingInWithGoogle by mutableStateOf(false)
    private var googleFailure by mutableStateOf<ConnectFailure?>(null)
    private var googleSignIns = 0
    private var linkRequired by mutableStateOf(false)
    private var linking by mutableStateOf(false)
    private val linkCalls = mutableListOf<Pair<String, String>>()
    private var linkCancels = 0
    private var accountCreated by mutableStateOf(false)
    private var managedSubscription by mutableStateOf(ManagedSubscriptionState())
    private var subscriptions = 0
    private var managedSubscriptions = 0
    private val couponCodes = mutableListOf<String>()
    private var failedPasswordLogins by mutableStateOf(0)
    private var passwordResetState by mutableStateOf(PasswordResetUiState())
    private val passwordResetRequests = mutableListOf<String>()
    private val passwordResetCompletions = mutableListOf<Triple<String, String, String>>()
    private var passwordResetCloses = 0
    private var accountDeletionState by mutableStateOf(AccountDeletionUiState())
    private val accountDeletionRequests = mutableListOf<String>()

    private var purchasesAvailable by mutableStateOf(true)
    private var backs = 0

    /** The default offer, and the only button on the card until the disclosure is opened. */
    @Test
    fun googleSignInIsTheDefaultAction() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()

        onNodeWithTag(AccountTags.GOOGLE).assertIsDisplayed().performClick()

        assertEquals(1, googleSignIns)
    }

    @Test
    fun googleButtonHasNoSignInOrRegistrationExplainer() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()

        onNodeWithText("One button signs you in or creates your account.")
            .assertDoesNotExist()
    }

    @Test
    fun googleButtonShowsProgressAndDisablesItselfWhileSigningIn() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.GOOGLE_PROGRESS).assertDoesNotExist()

        signingInWithGoogle = true

        onNodeWithTag(AccountTags.GOOGLE_PROGRESS).assertIsDisplayed()
        onNodeWithTag(AccountTags.GOOGLE).assertIsNotEnabled()
    }

    /**
     * A build with no Web client id shows the button disabled and says why, rather than letting the
     * press fail inside Credential Manager with an error that reads like the user's Google account
     * is at fault.
     */
    @Test
    fun googleSignInIsDisabledAndExplainedWhenTheBuildHasNoClientId() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        googleAvailable = false

        onNodeWithTag(AccountTags.GOOGLE).assertIsNotEnabled()
        onNodeWithText(Strings.account_google_unconfigured)
            .assertIsDisplayed()
    }

    @Test
    fun googleFailureIsExplainedUnderItsOwnButton() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        googleFailure = ConnectFailure.NoGoogleAccount

        onNodeWithTag(AccountTags.GOOGLE_STATUS)
            .assertTextContains(Strings.account_error_no_google_account)
    }

    /**
     * `409 account_link_required` has one resolution in the contract and this dialog is it. The
     * screen must not offer it before the server has asked for it — showing it unprompted would ask
     * for a password nothing is going to check.
     */
    @Test
    fun linkDialogAppearsOnlyWhenTheServerAsksForIt() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.LINK_DIALOG).assertDoesNotExist()

        linkRequired = true

        onNodeWithTag(AccountTags.LINK_DIALOG).assertIsDisplayed()
        onNodeWithTag(AccountTags.LINK_CONFIRM).assertIsNotEnabled()
    }

    @Test
    fun linkDialogSendsTheEmailAndPasswordItWasGiven() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        linkRequired = true

        onNodeWithTag(AccountTags.LINK_EMAIL).performTextInput("owner@example.com")
        onNodeWithTag(AccountTags.LINK_PASSWORD).performTextInput("correct horse")
        onNodeWithTag(AccountTags.LINK_CONFIRM).performClick()

        assertEquals(listOf("owner@example.com" to "correct horse"), linkCalls)
    }

    /**
     * Cancelling is a real operation rather than just closing a dialog: `SyncAccounts` is holding a
     * Google ID token for as long as the link is pending, and only this drops it.
     */
    @Test
    fun cancellingTheLinkReportsItRatherThanOnlyClosing() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        linkRequired = true

        onNodeWithTag(AccountTags.LINK_CANCEL).performClick()

        assertEquals(1, linkCancels)
        assertTrue(linkCalls.isEmpty())
    }

    /**
     * The one thing the single button cannot show any other way. Only the server knows whether it
     * found the account or made it, and it says so once.
     */
    @Test
    fun creatingAnAccountIsAnnouncedInsteadOfMerelyConnected() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        connection = ServerConnection.Connected("https://sync.vivenotes.net", "Pixel Tablet")
        accountCreated = true

        onNodeWithTag(AccountTags.CONNECT_STATUS)
            .assertTextContains(Strings.account_connected_created)
    }

    @Test
    fun selfHostRevealsUrlEmailAndPasswordFields() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.SERVER_URL).assertDoesNotExist()

        onNodeWithTag(AccountTags.SELF_HOST).performClick()

        onNodeWithTag(AccountTags.SERVER_URL).assertIsDisplayed()
        onNodeWithTag(AccountTags.EMAIL).assertIsDisplayed()
        onNodeWithTag(AccountTags.PASSWORD).assertIsDisplayed()
    }

    @Test
    fun selfHostAcceptsConnectionDetails() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.SELF_HOST).performClick()

        onNodeWithTag(AccountTags.SERVER_URL)
            .performTextInput("https://notes.example.com")
        onNodeWithTag(AccountTags.EMAIL)
            .performTextInput("writer@example.com")
        onNodeWithTag(AccountTags.PASSWORD)
            .performTextInput("correct horse battery staple")

        onNodeWithTag(AccountTags.SERVER_URL)
            .assertTextContains("https://notes.example.com")
        onNodeWithTag(AccountTags.EMAIL)
            .assertTextContains("writer@example.com")
    }

    /** Connect belongs to the disclosure, not the card: there is nothing to connect to above it. */
    @Test
    fun connectAppearsOnlyWithTheSelfHostFields() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.CONNECT).assertDoesNotExist()

        onNodeWithTag(AccountTags.SELF_HOST).performClick()

        // Scrolled to first: the card now carries Google and the two managed buttons above this
        // disclosure, so Connect can open below the fold on a shorter screen.
        onNodeWithTag(AccountTags.CONNECT).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun connectWaitsForAllThreeFieldsAndThenSendsWhatWasTyped() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.SELF_HOST).performClick()
        onNodeWithTag(AccountTags.CONNECT).assertIsNotEnabled()

        onNodeWithTag(AccountTags.SERVER_URL).performTextInput("http://10.0.2.2:5444")
        onNodeWithTag(AccountTags.EMAIL).performTextInput("owner@example.com")
        // Still short of all three, so the round trip that could only fail is not offered.
        onNodeWithTag(AccountTags.CONNECT).assertIsNotEnabled()

        onNodeWithTag(AccountTags.PASSWORD).performTextInput("correct horse")
        onNodeWithTag(AccountTags.CONNECT).assertIsEnabled()
        // Scrolled to first: typing raises the IME, and the card's scroll container can leave the
        // button below the visible area, where an injected tap silently lands nowhere.
        onNodeWithTag(AccountTags.CONNECT).performScrollTo().performClick()

        assertEquals(
            listOf(Triple("http://10.0.2.2:5444", "owner@example.com", "correct horse")),
            connectCalls,
        )
    }

    @Test
    fun theButtonShowsAProgressIndicatorInsteadOfItsLabelWhileTheServerAnswers() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.SELF_HOST).performClick()
        onNodeWithTag(AccountTags.CONNECT_PROGRESS).assertDoesNotExist()

        connection = ServerConnection.Connecting

        onNodeWithTag(AccountTags.CONNECT_PROGRESS).performScrollTo().assertIsDisplayed()
        // Pressing again would register a second device on the server, not retry the first.
        onNodeWithTag(AccountTags.CONNECT).assertIsNotEnabled()
    }

    @Test
    fun aFailureIsExplainedInTermsOfWhatToChange() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.SELF_HOST).performClick()
        onNodeWithTag(AccountTags.CONNECT_STATUS).assertDoesNotExist()

        connection = ServerConnection.Failed(ConnectFailure.InvalidCredentials)

        onNodeWithTag(AccountTags.CONNECT_STATUS)
            .assertTextContains(Strings.account_error_credentials)

        connection = ServerConnection.Failed(ConnectFailure.Unreachable)

        onNodeWithTag(AccountTags.CONNECT_STATUS)
            .assertTextContains(Strings.account_error_unreachable)
    }

    /**
     * Connected is a different form, not a message appended to the old one: the fields describe a
     * connection to make, and once there is one they have nothing left to say.
     */
    @Test
    fun connectedReplacesTheFormWithItsStatusAndADisconnect() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.SELF_HOST).performClick()

        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

        onNodeWithTag(AccountTags.CONNECTED).assertIsDisplayed()
        onNodeWithTag(AccountTags.CONNECT_STATUS)
            .assertTextContains(Strings.account_connected_title)
        onNodeWithTag(AccountTags.DISCONNECT).assertIsDisplayed()
        onNodeWithTag(AccountTags.SYNC).assertIsDisplayed()

        onNodeWithTag(AccountTags.SERVER_URL).assertDoesNotExist()
        onNodeWithTag(AccountTags.EMAIL).assertDoesNotExist()
        onNodeWithTag(AccountTags.PASSWORD).assertDoesNotExist()
        onNodeWithTag(AccountTags.CONNECT).assertDoesNotExist()
    }

    /** The connected card names the account and the provider used, not this device's registration. */
    @Test
    fun connectedGoogleAccountShowsItsEmailAndGoogleIdentity() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()

        connection = ServerConnection.Connected(
            serverUrl = "https://sync.vivenotes.net",
            deviceName = "Pixel Tablet",
            email = "owner@example.com",
            authProvider = AccountAuthProvider.Google,
        )

        // Shown, never a link: there is nothing at the sync address for a person to open.
        onNodeWithText("https://sync.vivenotes.net")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        onNodeWithText(Strings.account_connected_server)
            .assertIsDisplayed()
        onNodeWithText("owner@example.com").assertIsDisplayed()
        onNodeWithText(Strings.account_provider_google)
            .assertIsDisplayed()
        onNodeWithTag(AccountTags.CONNECTED_PROVIDER)
            .assertContentDescriptionEquals(Strings.account_provider_google)
        onNodeWithText("Registered as Pixel Tablet").assertDoesNotExist()
    }

    @Test
    fun connectedPasswordAccountDistinguishesItsProvider() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        connection = ServerConnection.Connected(
            serverUrl = "https://sync.vivenotes.net",
            deviceName = "Pixel Tablet",
            email = "owner@example.com",
            authProvider = AccountAuthProvider.Password,
        )
        setScreen()

        onNodeWithText("owner@example.com").assertIsDisplayed()
        onNodeWithText(Strings.account_provider_password)
            .assertIsDisplayed()
        onNodeWithTag(AccountTags.CONNECTED_PROVIDER)
            .assertContentDescriptionEquals(Strings.account_provider_password)
    }

    /** Arriving already connected must show it, not hide it behind anything. */
    @Test
    fun anExistingConnectionIsShownOnArrival() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")
        setScreen()

        onNodeWithTag(AccountTags.CONNECTED).assertIsDisplayed()
    }

    @Test
    fun disconnectAsksFirstAndCanBeBackedOutOf() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

        onNodeWithTag(AccountTags.DISCONNECT).performScrollTo().performClick()

        // Nothing has happened yet: the token cannot be reissued, so the tap only opens the question.
        assertEquals(0, disconnects)
        onNodeWithText(Strings.account_disconnect_cancel).performClick()
        assertEquals(0, disconnects)

        onNodeWithTag(AccountTags.DISCONNECT).performScrollTo().performClick()
        onNodeWithTag(AccountTags.DISCONNECT_CONFIRM).performClick()

        assertEquals(1, disconnects)
    }

    /**
     * The way out of a server that never answers again.
     *
     * Without it the stored registration is a one-way door: it is what this screen shows instead of
     * the connect form, so a device whose server is offline could neither revoke itself nor move to
     * another server. The offer is deliberately not on screen before the ordinary revoke has been
     * tried and failed — it leaves a live device row behind on the server.
     */
    @Test
    fun aFailedRevokeOffersTheLocalOnlyWayOut() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

        onNodeWithTag(AccountTags.DISCONNECT_ANYWAY).assertDoesNotExist()

        disconnectFailure = ConnectFailure.Unreachable

        // What stays behind is stated beside the button rather than behind a second dialog: the
        // person has already confirmed a disconnect, and this is the only part of it that differs.
        onNodeWithText(Strings.account_disconnect_anyway_note)
            .performScrollTo()
            .assertIsDisplayed()
        onNodeWithTag(AccountTags.DISCONNECT_ANYWAY).performScrollTo().performClick()

        assertEquals(1, forceDisconnects)
        // The failed route is not retried on the way out; nothing goes to the server at all.
        assertEquals(0, disconnects)
    }

    @Test
    fun syncNowShowsProgressAndThenASummary() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

        onNodeWithTag(AccountTags.SYNC).performScrollTo().performClick()
        assertEquals(1, syncs)

        syncing = true
        onNodeWithTag(AccountTags.SYNC_PROGRESS).assertIsDisplayed()
        onNodeWithTag(AccountTags.SYNC).assertIsNotEnabled()
        onNodeWithTag(AccountTags.DISCONNECT).assertIsNotEnabled()

        syncing = false
        syncStatus = SyncStatus(
            lastSucceededAtMillis = System.currentTimeMillis(),
            lastSummary = SyncSummary(pulled = 2, pushed = 1, conflictsResolved = 0),
        )
        onNodeWithTag(AccountTags.SYNC_STATUS).assertTextContains(
            Strings.account_sync_just_now +
                Strings.account_sync_counts( 2, 1),
        )
    }

    /**
     * The status line answers for the clock as well as for the button, which is the entire reason it
     * reads from a shared status rather than from the last press. A sync that has been failing since
     * before this screen was opened has to be visible on opening it.
     */
    @Test
    fun aFailedBackgroundRunIsOnScreenWithoutAnybodyPressingSync() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

        syncStatus = SyncStatus(failure = SyncRunResult.Retryable(ConnectFailure.Unreachable))

        onNodeWithTag(AccountTags.SYNC_STATUS).performScrollTo().assertTextContains(
            Strings.account_sync_will_retry(
                Strings.account_error_unreachable,
            ),
        )
        assertEquals(0, syncs)
    }

    /**
     * The same glyph the ribbon badges the account button with, beside the sentence explaining it —
     * so somebody who followed the badge here recognises what they followed.
     */
    @Test
    fun anUnreachableServerIsMarkedWithCloudOff() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

        syncStatus = SyncStatus(failure = SyncRunResult.Retryable(ConnectFailure.Unreachable))

        onNodeWithTag(AccountTags.SYNC_OFFLINE).performScrollTo().assertIsDisplayed()
    }

    /**
     * And only for that state. A cloud with a line through it beside "the server rejected this
     * change" sends its reader to check the wifi, which is fine and is not the problem.
     */
    @Test
    fun aFailureThatIsNotTheConnectionCarriesNoCloudOff() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

        syncStatus = SyncStatus(
            failure = SyncRunResult.Failed(PermanentSyncFailure.UnsupportedKind),
        )

        onNodeWithTag(AccountTags.SYNC_STATUS).performScrollTo()
        onNodeWithTag(AccountTags.SYNC_OFFLINE).assertDoesNotExist()
    }

    /**
     * Connecting is an invitation, and there is nothing left to invite. Leaving any of the three
     * routes on a screen that is already connected offers a second account to an app that holds one.
     */
    @Test
    fun connectedHidesEveryWayToConnectAgain() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")

        onNodeWithTag(AccountTags.CONNECTED).assertIsDisplayed()
        onNodeWithTag(AccountTags.GOOGLE).assertDoesNotExist()
        onNodeWithTag(AccountTags.LOGIN).assertDoesNotExist()
        onNodeWithTag(AccountTags.SIGN_UP).assertDoesNotExist()
        onNodeWithTag(AccountTags.SELF_HOST).assertDoesNotExist()
    }

    @Test
    fun selfHostedConnectionNeverShowsManagedBilling() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")
        setScreen()

        onNodeWithTag(AccountTags.CONNECTED).assertIsDisplayed()
        onNodeWithTag(AccountTags.SUBSCRIPTION).assertDoesNotExist()
    }

    @Test
    fun managedPlanShowsPlayPriceAndStartsThePurchaseFlow() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        connection = ServerConnection.Connected("https://sync.vivenotes.net", "Pixel Tablet")
        managedSubscription = ManagedSubscriptionState(
            visible = true,
            formattedPrice = "\$4.99",
            productAvailable = true,
        )
        setScreen()

        onNodeWithText(
            Strings.account_subscription_price( "\$4.99"),
        ).performScrollTo().assertIsDisplayed()
        val planWidth = onNodeWithTag(AccountTags.SUBSCRIPTION)
            .fetchSemanticsNode().boundsInRoot.width
        val purchase = onNodeWithTag(AccountTags.SUBSCRIBE)
        purchase.performScrollTo()
        onNodeWithTag(AccountTags.GOOGLE_PLAY_MARK, useUnmergedTree = true)
            .assertIsDisplayed()
        assertTrue(purchase.fetchSemanticsNode().boundsInRoot.width < planWidth)
        purchase.performClick()

        assertEquals(1, subscriptions)
    }

    @Test
    fun membershipRequiredExplainsThePurchaseAndEmphasizesSubscribe() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        connection = ServerConnection.Connected("https://sync.vivenotes.net", "Pixel Tablet")
        managedSubscription = ManagedSubscriptionState(
            visible = true,
            formattedPrice = "\$4.99",
            productAvailable = true,
        )
        syncStatus = SyncStatus(
            failure = SyncRunResult.Failed(PermanentSyncFailure.MembershipRequired),
        )
        setScreen()

        onNodeWithTag(AccountTags.SYNC_STATUS).performScrollTo().assertTextContains(
            Strings.account_sync_membership_required,
        )
        onNodeWithTag(AccountTags.SUBSCRIBE).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                Strings.account_subscription_required_state,
            ),
        )
    }

    /**
     * Somebody who is paying reads that storage is active and when it renews; nothing on the card
     * asks them to buy anything or to enter a coupon.
     */
    @Test
    fun renewingPlayPlanIsAStatusWithOnlyAManageLink() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        connection = ServerConnection.Connected("https://sync.vivenotes.net", "Pixel Tablet")
        managedSubscription = ManagedSubscriptionState(
            visible = true,
            status = ManagedSubscriptionStatus(
                active = true,
                validUntil = "2026-12-03T12:00:00Z",
                paidState = PaidSubscriptionState.Active,
                paidValidUntil = "2026-10-03T12:00:00Z",
                promotionalValidUntil = "2026-12-03T12:00:00Z",
                autoRenewing = true,
                productId = "vivenotes_storage_monthly",
            ),
            formattedPrice = "\$4.99",
            productAvailable = true,
            playPurchaseOwned = true,
        )
        setScreen()

        // The label is the Surface's child: a non-clickable Surface does not merge its content.
        onNodeWithTag(AccountTags.SUBSCRIPTION_BADGE, useUnmergedTree = true)
            .onChild()
            .assertTextContains(Strings.account_subscription_badge_active)
        onNodeWithText(
            Strings.account_subscription_renews( formattedSubscriptionDate("2026-10-03T12:00:00Z")),
        ).performScrollTo().assertIsDisplayed()
        onNodeWithText(
            Strings.account_subscription_price_short( "\$4.99"),
        ).assertIsDisplayed()
        onNodeWithTag(AccountTags.SUBSCRIBE).assertDoesNotExist()
        onNodeWithTag(AccountTags.COUPON_TOGGLE).assertDoesNotExist()
        onNodeWithTag(AccountTags.COUPON).assertDoesNotExist()
        onNodeWithTag(AccountTags.MANAGE_SUBSCRIPTION)
            .performScrollTo()
            .assertTextContains(Strings.account_subscription_manage_link)
            .performClick()
        assertEquals(1, managedSubscriptions)
    }

    /** Auto-renewal off: an end date instead of "renews", and a way back that is not a new purchase. */
    @Test
    fun aPlanThatWillNotRenewSaysUntilWhenAndOffersRenewInPlay() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        connection = ServerConnection.Connected("https://sync.vivenotes.net", "Pixel Tablet")
        managedSubscription = ManagedSubscriptionState(
            visible = true,
            status = ManagedSubscriptionStatus(
                active = true,
                validUntil = "2027-01-21T12:00:00Z",
                paidState = PaidSubscriptionState.Canceled,
                paidValidUntil = "2027-01-21T12:00:00Z",
                promotionalValidUntil = null,
                autoRenewing = false,
                productId = "vivenotes_storage_monthly",
            ),
            formattedPrice = "\$4.99",
            productAvailable = true,
            playPurchaseOwned = true,
        )
        setScreen()

        onNodeWithTag(AccountTags.SUBSCRIPTION_STATUS).performScrollTo().assertTextContains(
            Strings.account_subscription_available_until(
                formattedSubscriptionDate("2027-01-21T12:00:00Z"),
            ),
        )
        onNodeWithText(Strings.account_subscription_renewal_off)
            .assertIsDisplayed()
        onNodeWithTag(AccountTags.SUBSCRIBE).assertDoesNotExist()
        onNodeWithTag(AccountTags.COUPON_TOGGLE).assertDoesNotExist()
        onNodeWithTag(AccountTags.MANAGE_SUBSCRIPTION)
            .performScrollTo()
            .assertTextContains(Strings.account_subscription_renew)
            .performClick()
        assertEquals(1, managedSubscriptions)
        assertEquals(0, subscriptions)
    }

    /** An install Google Play did not make sells through Stripe, and nothing on the card says Google. */
    @Test
    fun anInstallThatSellsByCardOffersCardCheckoutInPlaceOfPlay() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        connection = ServerConnection.Connected("https://sync.vivenotes.net", "Pixel Tablet")
        managedSubscription = ManagedSubscriptionState(
            visible = true,
            formattedPrice = "\$5.49",
            checkout = CheckoutProvider.Stripe,
            cardPrice = "\$4.99",
            cardManagementAvailable = true,
        )
        setScreen()

        onNodeWithText(Strings.account_subscription_price("\$4.99"))
            .performScrollTo()
            .assertIsDisplayed()
        onNodeWithText(Strings.account_subscription_price("\$5.49"))
            .assertDoesNotExist()
        val purchase = onNodeWithTag(AccountTags.SUBSCRIBE)
        purchase.performScrollTo()
            .assertTextContains(Strings.account_subscription_subscribe_card)
            .assertIsEnabled()
        onNodeWithTag(AccountTags.CARD_CHECKOUT_MARK, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag(AccountTags.GOOGLE_PLAY_MARK, useUnmergedTree = true).assertDoesNotExist()
        purchase.performScrollTo().performClick()

        assertEquals(1, subscriptions)
    }

    @Test
    fun openingCardCheckoutShowsProgressInsteadOfTheLabel() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        connection = ServerConnection.Connected("https://sync.vivenotes.net", "Pixel Tablet")
        managedSubscription = ManagedSubscriptionState(
            visible = true,
            checkout = CheckoutProvider.Stripe,
            cardPrice = "\$4.99",
            purchasing = true,
        )
        setScreen()

        val purchase = onNodeWithTag(AccountTags.SUBSCRIBE)
            .performScrollTo()
            .assertIsNotEnabled()
        // Desktop: Compose Multiplatform's LoadingIndicator merges its own semantics, so the
        // description is announced from the indicator inside the button rather than the button.
        purchase.onChild().assertContentDescriptionEquals(Strings.account_subscription_opening_checkout)
        onNodeWithText(Strings.account_subscription_subscribe_card).assertDoesNotExist()
    }

    @Test
    fun aRenewingCardPlanShowsItsPriceAndOpensItsOwnPortal() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        connection = ServerConnection.Connected("https://sync.vivenotes.net", "Pixel Tablet")
        managedSubscription = ManagedSubscriptionState(
            visible = true,
            status = cardStatus(PaidSubscriptionState.Active, autoRenewing = true),
            formattedPrice = "\$5.49",
            checkout = CheckoutProvider.Stripe,
            cardPrice = "\$4.99",
            cardManagementAvailable = true,
        )
        setScreen()

        onNodeWithText(Strings.account_subscription_price_short("\$4.99"))
            .performScrollTo()
            .assertIsDisplayed()
        onNodeWithTag(AccountTags.SUBSCRIBE).assertDoesNotExist()
        onNodeWithTag(AccountTags.MANAGE_SUBSCRIPTION)
            .performScrollTo()
            .assertTextContains(Strings.account_subscription_manage_link)
            .performClick()
        assertEquals(1, managedSubscriptions)
    }

    /** Play's policy covers the copy Play installed: a card plan is shown there but not linked to. */
    @Test
    fun aCardPlanSeenFromAPlayInstallHasNoLinkToItsPortal() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        connection = ServerConnection.Connected("https://sync.vivenotes.net", "Pixel Tablet")
        managedSubscription = ManagedSubscriptionState(
            visible = true,
            status = cardStatus(PaidSubscriptionState.Active, autoRenewing = true),
            formattedPrice = "\$5.49",
            productAvailable = true,
        )
        setScreen()

        onNodeWithTag(AccountTags.SUBSCRIPTION_STATUS)
            .performScrollTo()
            .assertTextContains(Strings.account_subscription_active_headline)
        onNodeWithTag(AccountTags.MANAGE_SUBSCRIPTION).assertDoesNotExist()
        onNodeWithTag(AccountTags.SUBSCRIBE).assertDoesNotExist()
    }

    @Test
    fun aCardPaymentBeingRetriedIsExplainedInStripesTerms() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        connection = ServerConnection.Connected("https://sync.vivenotes.net", "Pixel Tablet")
        managedSubscription = ManagedSubscriptionState(
            visible = true,
            status = cardStatus(PaidSubscriptionState.Grace, autoRenewing = true),
            checkout = CheckoutProvider.Stripe,
            cardManagementAvailable = true,
        )
        setScreen()

        onNodeWithText(Strings.account_subscription_card_grace)
            .performScrollTo()
            .assertIsDisplayed()
        onNodeWithText(Strings.account_subscription_paid_grace)
            .assertDoesNotExist()
        onNodeWithTag(AccountTags.MANAGE_SUBSCRIPTION)
            .performScrollTo()
            .assertTextContains(Strings.account_subscription_fix_payment)
            .performClick()
        assertEquals(1, managedSubscriptions)
    }

    private fun cardStatus(paidState: PaidSubscriptionState, autoRenewing: Boolean) =
        ManagedSubscriptionStatus(
            active = true,
            validUntil = "2026-12-03T12:00:00Z",
            paidState = paidState,
            paidValidUntil = "2026-12-03T12:00:00Z",
            promotionalValidUntil = null,
            autoRenewing = autoRenewing,
            productId = "price_monthly",
            paidProvider = PaidProvider.Stripe,
        )

    @Test
    fun couponFieldSubmitsTheTypedCode() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        connection = ServerConnection.Connected("https://sync.vivenotes.net", "Pixel Tablet")
        managedSubscription = ManagedSubscriptionState(visible = true)
        setScreen()

        // Folded behind its link until asked for.
        onNodeWithTag(AccountTags.COUPON).assertDoesNotExist()
        onNodeWithTag(AccountTags.COUPON_TOGGLE).performScrollTo().performClick()
        onNodeWithTag(AccountTags.COUPON).performScrollTo().performTextInput("FREE-MONTH")
        val planWidth = onNodeWithTag(AccountTags.SUBSCRIPTION)
            .fetchSemanticsNode().boundsInRoot.width
        val couponWidth = onNodeWithTag(AccountTags.COUPON)
            .fetchSemanticsNode().boundsInRoot.width
        val redeem = onNodeWithTag(AccountTags.REDEEM_COUPON)
        redeem.performScrollTo()
        val redeemWidth = redeem.fetchSemanticsNode().boundsInRoot.width

        assertTrue(couponWidth < planWidth)
        assertTrue(redeemWidth < couponWidth)
        redeem.performClick()

        assertEquals(listOf("FREE-MONTH"), couponCodes)
    }

    /**
     * Asserted through the button rather than by reading the password field, whose semantics carry
     * the masked text: back on the empty form, Connect has an empty required field again. The point
     * is that the token replaced the password in memory rather than joining it.
     */
    @Test
    fun connectingDropsThePassword() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.SELF_HOST).performClick()
        onNodeWithTag(AccountTags.SERVER_URL).performTextInput("http://10.0.2.2:5444")
        onNodeWithTag(AccountTags.EMAIL).performTextInput("owner@example.com")
        onNodeWithTag(AccountTags.PASSWORD).performTextInput("correct horse")
        onNodeWithTag(AccountTags.CONNECT).assertIsEnabled()

        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")
        // Asserted, not just assigned: without a sync point here the two writes coalesce into one
        // recomposition that only ever sees Idle, and the effect that drops the password never runs.
        onNodeWithTag(AccountTags.CONNECTED).assertIsDisplayed()

        connection = ServerConnection.Idle

        onNodeWithTag(AccountTags.CONNECT).assertIsNotEnabled()
    }

    /**
     * The managed account takes an email and a password and **no server address**. That absence is
     * the whole separation: a hostname belongs to Self host, which is somebody else's server.
     */
    @Test
    fun loggingInToTheManagedAccountNeverAsksForAServerAddress() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()

        onNodeWithTag(AccountTags.LOGIN).performClick()

        onNodeWithTag(AccountTags.CLOUD_EMAIL).assertIsDisplayed()
        onNodeWithTag(AccountTags.CLOUD_PASSWORD).assertIsDisplayed()
        onNodeWithTag(AccountTags.SERVER_URL).assertDoesNotExist()
    }

    @Test
    fun loggingInReportsWhatWasTypedAndNothingElse() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.LOGIN).performClick()
        onNodeWithTag(AccountTags.CLOUD_EMAIL).performTextInput("owner@example.com")
        onNodeWithTag(AccountTags.CLOUD_PASSWORD).performTextInput("correct horse")

        onNodeWithTag(AccountTags.CLOUD_SUBMIT).performScrollTo().performClick()

        assertEquals(listOf("owner@example.com" to "correct horse"), logInCalls)
        // The two are not interchangeable: signing up would create an account first.
        assertTrue(signUpCalls.isEmpty())
    }

    @Test
    fun forgotPasswordAppearsOnlyAfterTwoRejectedLogins() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.LOGIN).performClick()

        failedPasswordLogins = 1
        onNodeWithTag(AccountTags.FORGOT_PASSWORD).assertDoesNotExist()

        failedPasswordLogins = 2
        onNodeWithTag(AccountTags.FORGOT_PASSWORD).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun passwordRecoverySendsEmailThenCodeAndNewPassword() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.LOGIN).performClick()
        onNodeWithTag(AccountTags.CLOUD_EMAIL).performTextInput("owner@example.com")
        failedPasswordLogins = 2
        onNodeWithTag(AccountTags.FORGOT_PASSWORD).performScrollTo().performClick()

        onNodeWithTag(AccountTags.RESET_EMAIL)
            .assertTextContains("owner@example.com")
        onNodeWithTag(AccountTags.RESET_REQUEST).performClick()
        assertEquals(listOf("owner@example.com"), passwordResetRequests)

        passwordResetState = PasswordResetUiState(codeRequested = true)
        onNodeWithTag(AccountTags.RESET_CODE).performTextInput("01234567")
        onNodeWithTag(AccountTags.RESET_PASSWORD).performTextInput("new password")
        onNodeWithTag(AccountTags.RESET_CONFIRM_PASSWORD)
            .performScrollTo()
            .performTextInput("new password")
        onNodeWithTag(AccountTags.RESET_COMPLETE).performScrollTo().performClick()

        assertEquals(
            listOf(Triple("owner@example.com", "01234567", "new password")),
            passwordResetCompletions,
        )
    }

    @Test
    fun managedAccountDeletionRequiresTypedEmailAndUsesDangerConfirmation() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        connection = ServerConnection.Connected(
            serverUrl = "https://sync.vivenotes.net",
            deviceName = "Pixel Tablet",
            email = "owner@example.com",
            authProvider = AccountAuthProvider.Password,
        )
        managedSubscription = ManagedSubscriptionState(visible = true)
        setScreen()

        onNodeWithTag(AccountTags.DELETE_ACCOUNT).performScrollTo().performClick()
        onNodeWithTag(AccountTags.DELETE_DIALOG).assertIsDisplayed()
        onNodeWithText(Strings.account_delete_irreversible)
            .assertIsDisplayed()
        onNodeWithText("Enter the account email to confirm.").assertDoesNotExist()
        onNodeWithTag(AccountTags.DELETE_CONFIRM).assertIsNotEnabled()

        onNodeWithTag(AccountTags.DELETE_EMAIL).performTextInput("someone@example.com")
        onNodeWithTag(AccountTags.DELETE_CONFIRM).assertIsNotEnabled()
        onNodeWithTag(AccountTags.DELETE_EMAIL).performTextClearance()
        onNodeWithTag(AccountTags.DELETE_EMAIL).performTextInput("owner@example.com")
        onNodeWithTag(AccountTags.DELETE_CONFIRM).performClick()

        assertEquals(listOf("owner@example.com"), accountDeletionRequests)
    }

    @Test
    fun anUnavailableDeletionRouteKeepsTheConfirmationOpen() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        connection = ServerConnection.Connected("https://sync.vivenotes.net", "Pixel Tablet")
        managedSubscription = ManagedSubscriptionState(visible = true)
        setScreen()
        onNodeWithTag(AccountTags.DELETE_ACCOUNT).performScrollTo().performClick()

        accountDeletionState = AccountDeletionUiState(
            failure = AccountDeletionFailure.Unavailable,
        )

        onNodeWithTag(AccountTags.DELETE_DIALOG).assertIsDisplayed()
        onNodeWithText(Strings.account_delete_error_unavailable)
            .assertIsDisplayed()
    }

    @Test
    fun signingUpUsesItsOwnCallbackAndRelabelsTheButton() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.SIGN_UP).performClick()
        onNodeWithTag(AccountTags.CLOUD_SUBMIT)
            .assertTextContains(Strings.account_create)
        onNodeWithTag(AccountTags.CLOUD_EMAIL).performTextInput("owner@example.com")
        onNodeWithTag(AccountTags.CLOUD_PASSWORD).performTextInput("correct horse")
        onNodeWithTag(AccountTags.CLOUD_CONFIRM).performScrollTo()
            .performTextInput("correct horse")
        onNodeWithTag(AccountTags.CLOUD_SUBMIT).performScrollTo().performClick()

        assertEquals(listOf("owner@example.com" to "correct horse"), signUpCalls)
        assertTrue(logInCalls.isEmpty())
    }

    /**
     * A mistyped new password cannot be recovered — the server keeps an Argon2id hash and this
     * contract has no reset — so the second field is the one chance to catch it before it becomes an
     * account nobody can get into.
     */
    @Test
    fun signingUpWaitsForTheTwoPasswordsToMatch() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.SIGN_UP).performClick()
        onNodeWithTag(AccountTags.CLOUD_EMAIL).performTextInput("owner@example.com")
        onNodeWithTag(AccountTags.CLOUD_PASSWORD).performTextInput("correct horse")

        // Long enough on its own, so only the confirmation is holding the button back.
        onNodeWithTag(AccountTags.CLOUD_SUBMIT).performScrollTo().assertIsNotEnabled()

        // One character short of the password, so it is wrong now and correct after the append —
        // `performTextInput` adds at the end, so the difference has to be at the end.
        onNodeWithTag(AccountTags.CLOUD_CONFIRM).performScrollTo()
            .performTextInput("correct hors")
        onNodeWithTag(AccountTags.CLOUD_SUBMIT).performScrollTo().assertIsNotEnabled()
        onNodeWithText(Strings.account_password_mismatch)
            .assertIsDisplayed()

        onNodeWithTag(AccountTags.CLOUD_CONFIRM).performScrollTo().performTextInput("e")
        onNodeWithTag(AccountTags.CLOUD_SUBMIT).performScrollTo().assertIsEnabled()
    }

    /**
     * An empty second field is unfinished, not wrong. Colouring it the moment the first one is
     * filled scolds somebody who is still typing.
     */
    @Test
    fun theConfirmationStaysQuietUntilSomethingIsTypedInIt() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.SIGN_UP).performClick()
        onNodeWithTag(AccountTags.CLOUD_PASSWORD).performTextInput("correct horse")

        onNodeWithText(Strings.account_password_mismatch)
            .assertDoesNotExist()
    }

    /** Logging in checks a password that already exists, so a second field would be noise. */
    @Test
    fun loggingInHasNoConfirmationField() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()

        onNodeWithTag(AccountTags.LOGIN).performClick()

        onNodeWithTag(AccountTags.CLOUD_PASSWORD).assertIsDisplayed()
        onNodeWithTag(AccountTags.CLOUD_CONFIRM).assertDoesNotExist()
    }

    /**
     * Switching modes empties it. A confirmation left over from a previous pass would silently
     * satisfy a check the person did not make this time.
     */
    @Test
    fun switchingModesClearsTheConfirmation() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.SIGN_UP).performClick()
        onNodeWithTag(AccountTags.CLOUD_EMAIL).performTextInput("owner@example.com")
        onNodeWithTag(AccountTags.CLOUD_PASSWORD).performTextInput("correct horse")
        onNodeWithTag(AccountTags.CLOUD_CONFIRM).performScrollTo()
            .performTextInput("correct horse")
        onNodeWithTag(AccountTags.CLOUD_SUBMIT).performScrollTo().assertIsEnabled()

        // Scrolled to before each press: the assertion above scrolled the card down, and a tap
        // injected at a button that is now above the viewport lands nowhere and fails silently.
        onNodeWithTag(AccountTags.LOGIN).performScrollTo().performClick()
        onNodeWithTag(AccountTags.SIGN_UP).performScrollTo().performClick()

        onNodeWithTag(AccountTags.CLOUD_SUBMIT).performScrollTo().assertIsNotEnabled()
    }

    /** The two share one panel, so pressing the other button must switch it rather than stack it. */
    @Test
    fun logInAndSignUpShareOnePanelAndSwitchBetweenThemselves() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()

        onNodeWithTag(AccountTags.LOGIN).performClick()
        onNodeWithTag(AccountTags.CLOUD_SUBMIT)
            .assertTextContains(Strings.account_log_in)

        onNodeWithTag(AccountTags.SIGN_UP).performClick()

        onNodeWithTag(AccountTags.CLOUD_SUBMIT)
            .assertTextContains(Strings.account_create)
        onNodeWithTag(AccountTags.CLOUD_EMAIL).assertIsDisplayed()
    }

    /** They are alternatives, and both open at once would show two emails and two submit buttons. */
    @Test
    fun openingSelfHostClosesTheManagedPanel() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.LOGIN).performClick()

        onNodeWithTag(AccountTags.SELF_HOST).performScrollTo().performClick()

        onNodeWithTag(AccountTags.SERVER_URL).assertIsDisplayed()
        onNodeWithTag(AccountTags.CLOUD_EMAIL).assertDoesNotExist()
    }

    /**
     * The contract's eight-character minimum, refused here rather than by the server: a shorter one
     * has exactly one possible answer, and spending a round trip on it is spending it to be told so.
     */
    @Test
    fun signingUpWaitsForAPasswordTheServerWouldAccept() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.SIGN_UP).performClick()
        onNodeWithTag(AccountTags.CLOUD_EMAIL).performTextInput("owner@example.com")

        // Seven characters: one short, which is the boundary worth testing rather than a
        // comfortably wrong length. Confirmed as it goes, so length is the only thing under test.
        onNodeWithTag(AccountTags.CLOUD_PASSWORD).performTextInput("shorter")
        onNodeWithTag(AccountTags.CLOUD_CONFIRM).performScrollTo()
            .performTextInput("shorter")
        onNodeWithTag(AccountTags.CLOUD_SUBMIT).performScrollTo().assertIsNotEnabled()

        // `performTextInput` appends at the cursor, so these make it exactly eight.
        onNodeWithTag(AccountTags.CLOUD_PASSWORD).performTextInput("s")
        onNodeWithTag(AccountTags.CLOUD_CONFIRM).performScrollTo().performTextInput("s")
        onNodeWithTag(AccountTags.CLOUD_SUBMIT).performScrollTo().assertIsEnabled()
    }

    /**
     * Logging in accepts any password, because it is checking one that already exists. Showing the
     * new-password rule there would state a requirement about the wrong password.
     */
    @Test
    fun loggingInAcceptsAnyPasswordAndDoesNotShowTheMinimum() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.LOGIN).performClick()
        onNodeWithTag(AccountTags.CLOUD_EMAIL).performTextInput("owner@example.com")
        onNodeWithTag(AccountTags.CLOUD_PASSWORD).performTextInput("short")

        onNodeWithTag(AccountTags.CLOUD_SUBMIT).performScrollTo().assertIsEnabled()
        onNodeWithText(Strings.account_password_minimum)
            .assertDoesNotExist()
    }

    /**
     * The one failure that leaves something behind on the server. Repeating Sign up would only say
     * `email_taken`, so the message has to name the way in that now exists.
     */
    @Test
    fun anAccountCreatedWithoutItsDeviceSaysToLogInInstead() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.SIGN_UP).performClick()
        connection = ServerConnection.Failed(ConnectFailure.AccountCreatedNotRegistered)

        onNodeWithTag(AccountTags.CLOUD_STATUS)
            .assertTextContains(Strings.account_error_created_not_registered)
    }

    @Test
    fun aServerWithRegistrationsClosedIsExplainedRatherThanBlamedOnCredentials() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.SIGN_UP).performClick()
        connection = ServerConnection.Failed(ConnectFailure.SignupClosed)

        onNodeWithTag(AccountTags.CLOUD_STATUS)
            .assertTextContains(Strings.account_error_signup_closed)
    }

    /**
     * Desktop: there is no Google Play to buy from, so the card says where a plan is bought rather
     * than showing a purchase button that could never work. Coupons still redeem here.
     */
    @Test
    fun withoutAPlayStoreThePlanSaysWhereToBuyInsteadOfOfferingAPurchase() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        purchasesAvailable = false
        connection = ServerConnection.Connected("https://sync.vivenotes.net", "fedora (3f2a)")
        managedSubscription = ManagedSubscriptionState(visible = true)
        setScreen()

        onNodeWithTag(AccountTags.SUBSCRIBE_ON_ANDROID).performScrollTo().assertIsDisplayed()
        onNodeWithTag(AccountTags.SUBSCRIBE).assertDoesNotExist()
        onNodeWithTag(AccountTags.COUPON_TOGGLE).performScrollTo().assertIsDisplayed()
    }

    /**
     * Desktop: still no Google Play, but a managed server that sells by card is a checkout the
     * desktop can open, so the card offers it instead of sending the person to Android.
     */
    @Test
    fun withoutAPlayStoreAServerThatSellsByCardIsSubscribedToHere() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        purchasesAvailable = false
        connection = ServerConnection.Connected("https://sync.vivenotes.net", "fedora (3f2a)")
        managedSubscription = ManagedSubscriptionState(
            visible = true,
            checkout = CheckoutProvider.Stripe,
            cardPrice = "\$4.99",
            cardManagementAvailable = true,
        )
        setScreen()

        onNodeWithTag(AccountTags.SUBSCRIBE_ON_ANDROID).assertDoesNotExist()
        onNodeWithText(Strings.account_subscription_price("\$4.99")).performScrollTo().assertIsDisplayed()
        onNodeWithTag(AccountTags.SUBSCRIBE)
            .performScrollTo()
            .assertTextContains(Strings.account_subscription_subscribe_card)
            .performClick()
        assertEquals(1, subscriptions)
        onNodeWithTag(AccountTags.COUPON_TOGGLE).performScrollTo().assertIsDisplayed()
    }

    /**
     * Desktop: Escape is Android's Back. With a dialog in front it answers the dialog; only then does
     * it leave the screen.
     */
    @Test
    fun escapeAnswersTheDialogInFrontBeforeLeavingTheScreen() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        connection = ServerConnection.Connected("http://localhost:5444", "fedora (3f2a)")
        setScreen()
        onNodeWithTag(AccountTags.DISCONNECT).performScrollTo().performClick()
        onNodeWithTag(AccountTags.DISCONNECT_CONFIRM).assertIsDisplayed()

        onRoot().performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag(AccountTags.DISCONNECT_CONFIRM).assertDoesNotExist()
        assertEquals(0, backs)
        assertEquals(0, disconnects)

        onRoot().performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, backs)
    }

    /**
     * Desktop regression from the real app: the sign-in replaced the form whose field had the
     * focus, nothing on the screen had it any more, and Escape went nowhere.
     */
    @Test
    fun escapeStillLeavesOnceTheSignInHasReplacedTheFocusedField() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setScreen()
        onNodeWithTag(AccountTags.SELF_HOST).performClick()
        onNodeWithTag(AccountTags.PASSWORD).performTextInput("correct horse")
        onNodeWithTag(AccountTags.PASSWORD).assertIsFocused()

        connection = ServerConnection.Connected("http://10.0.2.2:5444", "Pixel Tablet")
        onNodeWithTag(AccountTags.PASSWORD).assertDoesNotExist()

        onRoot().performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, backs)
    }

    /** Desktop: Android's `DateUtils` relative time, "Synced 5 minutes ago". */
    @Test
    fun anEarlierSyncSaysHowLongAgo() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        connection = ServerConnection.Connected("http://localhost:5444", "fedora (3f2a)")
        syncStatus = SyncStatus(lastSucceededAtMillis = System.currentTimeMillis() - 5 * 60_000L - 1_000L)
        setScreen()

        onNodeWithTag(AccountTags.SYNC_STATUS).performScrollTo().assertTextContains("Synced 5 minutes ago")
    }

    private fun ComposeUiTest.setScreen() {
        setContent {
            ViveNotesTheme {
                AccountScreen(
                    onBack = { backs++ },
                    googleAvailable = googleAvailable,
                    signingInWithGoogle = signingInWithGoogle,
                    onSignInWithGoogle = { googleSignIns++ },
                    googleFailure = googleFailure,
                    linkRequired = linkRequired,
                    linking = linking,
                    onLinkAccount = { email, password -> linkCalls += email to password },
                    onCancelLink = { linkCancels++ },
                    accountCreated = accountCreated,
                    connection = connection,
                    managedSubscription = managedSubscription,
                    purchasesAvailable = purchasesAvailable,
                    onSubscribe = { subscriptions++ },
                    onManageSubscription = { managedSubscriptions++ },
                    onRedeemCoupon = { couponCodes += it },
                    onConnect = { url, email, password ->
                        connectCalls += Triple(url, email, password)
                    },
                    onLogIn = { email, password -> logInCalls += email to password },
                    failedPasswordLogins = failedPasswordLogins,
                    passwordResetState = passwordResetState,
                    onRequestPasswordReset = { passwordResetRequests += it },
                    onCompletePasswordReset = { email, code, password ->
                        passwordResetCompletions += Triple(email, code, password)
                    },
                    onPasswordResetClosed = { passwordResetCloses++ },
                    onSignUp = { email, password -> signUpCalls += email to password },
                    syncing = syncing,
                    syncStatus = syncStatus,
                    onSync = { syncs++ },
                    disconnectFailure = disconnectFailure,
                    onDisconnect = { disconnects++ },
                    onForceDisconnect = { forceDisconnects++ },
                    accountDeletionState = accountDeletionState,
                    onRequestAccountDeletion = { accountDeletionRequests += it },
                )
            }
        }
    }

    private companion object {
        /** A tall window, so most of the card is on screen; the rest scrolls into view. */
        const val WIDTH = 900
        const val HEIGHT = 1400
    }
}
