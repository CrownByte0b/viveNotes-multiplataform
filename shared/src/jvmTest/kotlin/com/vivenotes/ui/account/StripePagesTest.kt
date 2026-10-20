package com.vivenotes.ui.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.data.billing.CheckoutProvider
import com.vivenotes.data.billing.ManagedBillingAccount
import com.vivenotes.data.billing.ManagedSubscriptionBackend
import com.vivenotes.data.billing.ManagedSubscriptionController
import com.vivenotes.data.billing.ManagedSubscriptionFailure
import com.vivenotes.data.billing.NoPlayBillingStore
import com.vivenotes.data.sync.CouponGrant
import com.vivenotes.data.sync.ManagedSubscriptionStatus
import com.vivenotes.data.sync.PaidProvider
import com.vivenotes.data.sync.PaidSubscriptionState
import com.vivenotes.data.sync.StripePlan
import com.vivenotes.data.sync.StripeRedirect
import com.vivenotes.data.sync.SubscriptionFailure
import com.vivenotes.data.sync.SubscriptionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.IOException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The desktop's stand-ins for Android's Custom Tab and `ON_RESUME`: which Stripe pages reach the
 * browser, and that coming back to the window is what reads the outcome, through the real controller.
 */
@OptIn(ExperimentalTestApi::class)
class StripePagesTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun onlyHttpsPagesWithAHostReachTheBrowser() {
        val opened = mutableListOf<URI>()
        val open: (URI) -> Unit = { opened += it }

        assertTrue(openStripePage(open, CHECKOUT_URL))
        assertFalse(openStripePage(open, "http://checkout.stripe.com/c/pay/cs_test_1"))
        assertFalse(openStripePage(open, "file:///home/user/.ssh/id_ed25519"))
        assertFalse(openStripePage(open, "vivenotes://billing"))
        assertFalse(openStripePage(open, "https:///c/pay/cs_test_1"))
        assertFalse(openStripePage(open, "https://checkout.stripe.com/c/pay/ spaces"))

        assertEquals(listOf(URI(CHECKOUT_URL)), opened)
    }

    @Test
    fun aBrowserThatCannotBeStartedCountsAsNone() {
        assertFalse(openStripePage({ throw IOException("xdg-open: not found") }, CHECKOUT_URL))
    }

    @Test
    fun checkoutOpensInTheBrowserAndComingBackToTheWindowReadsWhatHappened() = runDesktopComposeUiTest {
        val backend = FakeBackend()
        val controller = cardController(backend)
        val opened = CopyOnWriteArrayList<URI>()
        var focused by mutableStateOf(true)
        setContent { StripePageEffects(controller, { opened += it }, foreground = focused) }
        waitUntil(timeoutMillis = TIMEOUT) { controller.state.value.checkout == CheckoutProvider.Stripe }

        controller.purchase()
        waitUntil(timeoutMillis = TIMEOUT) { opened.isNotEmpty() }
        assertEquals(listOf(URI(CHECKOUT_URL)), opened)

        // The browser takes the focus while the person pays; nothing is asked of the server yet.
        focused = false
        waitForIdle()
        assertEquals(0, backend.stripeRefreshes.get())

        backend.refreshResult = SubscriptionResult.Success(activeCardStatus())
        focused = true
        waitUntil(timeoutMillis = TIMEOUT) { controller.state.value.purchaseConfirmed }
        assertEquals(1, backend.stripeRefreshes.get())
        assertEquals(PaidProvider.Stripe, controller.state.value.status?.paidProvider)

        // Later returns to the window, with no page sent in between, cost the server nothing.
        focused = false
        waitForIdle()
        focused = true
        waitForIdle()
        assertEquals(1, backend.stripeRefreshes.get())
    }

    @Test
    fun aPageNothingCouldOpenIsReportedAndNotRefreshedFor() = runDesktopComposeUiTest {
        val backend = FakeBackend()
        val controller = cardController(backend)
        var focused by mutableStateOf(true)
        setContent {
            StripePageEffects(controller, { throw IOException("no browser") }, foreground = focused)
        }
        waitUntil(timeoutMillis = TIMEOUT) { controller.state.value.checkout == CheckoutProvider.Stripe }

        controller.purchase()
        waitUntil(timeoutMillis = TIMEOUT) { controller.state.value.failure == ManagedSubscriptionFailure.BrowserUnavailable }
        assertFalse(controller.state.value.purchasing)

        focused = false
        waitForIdle()
        focused = true
        waitForIdle()
        assertEquals(0, backend.stripeRefreshes.get())
    }

    private fun cardController(backend: FakeBackend) = ManagedSubscriptionController(
        backend,
        NoPlayBillingStore,
        "vivenotes_storage_monthly",
        scope,
        cardCheckoutAllowed = true,
    )

    private class FakeBackend : ManagedSubscriptionBackend {
        override val account: Flow<ManagedBillingAccount?> = flowOf(ManagedBillingAccount(ACCOUNT_ID))

        @Volatile
        var refreshResult: SubscriptionResult<ManagedSubscriptionStatus> =
            SubscriptionResult.Success(inactiveStatus())
        val stripeRefreshes = AtomicInteger()

        override suspend fun read(account: ManagedBillingAccount): SubscriptionResult<ManagedSubscriptionStatus> =
            SubscriptionResult.Success(inactiveStatus())

        override suspend fun confirm(
            account: ManagedBillingAccount,
            purchaseToken: String,
            productId: String,
        ): SubscriptionResult<ManagedSubscriptionStatus> = SubscriptionResult.Failed(SubscriptionFailure.InvalidPurchase)

        override suspend fun redeem(account: ManagedBillingAccount, code: String): SubscriptionResult<CouponGrant> =
            SubscriptionResult.Failed(SubscriptionFailure.InvalidCoupon)

        override suspend fun stripePlan(account: ManagedBillingAccount): SubscriptionResult<StripePlan> =
            SubscriptionResult.Success(StripePlan(unitAmount = 499, currency = "usd", interval = "month", intervalCount = 1))

        override suspend fun openStripeCheckout(account: ManagedBillingAccount): SubscriptionResult<StripeRedirect> =
            SubscriptionResult.Success(StripeRedirect(CHECKOUT_URL, "2026-10-20T19:00:00Z"))

        override suspend fun openStripePortal(account: ManagedBillingAccount): SubscriptionResult<StripeRedirect> =
            SubscriptionResult.Success(StripeRedirect(PORTAL_URL, null))

        override suspend fun refreshStripe(account: ManagedBillingAccount): SubscriptionResult<ManagedSubscriptionStatus> {
            stripeRefreshes.incrementAndGet()
            return refreshResult
        }
    }

    private companion object {
        const val ACCOUNT_ID = "10000000-0000-0000-0000-000000000001"
        const val CHECKOUT_URL = "https://checkout.stripe.com/c/pay/cs_test_1"
        const val PORTAL_URL = "https://billing.stripe.com/p/session/test_1"
        const val TIMEOUT = 10_000L

        fun inactiveStatus() = ManagedSubscriptionStatus(
            active = false,
            validUntil = null,
            promotionalValidUntil = null,
            paidValidUntil = null,
            paidState = null,
            autoRenewing = false,
            productId = null,
        )

        fun activeCardStatus() = ManagedSubscriptionStatus(
            active = true,
            validUntil = "2026-11-20T12:00:00Z",
            promotionalValidUntil = null,
            paidValidUntil = "2026-11-20T12:00:00Z",
            paidState = PaidSubscriptionState.Active,
            autoRenewing = true,
            productId = "price_monthly",
            paidProvider = PaidProvider.Stripe,
        )
    }
}
