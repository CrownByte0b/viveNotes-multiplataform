// Ported from the Android app's `data/billing/PlayBilling.kt`: its types, its seam and the purchase
// binding, without `GooglePlayBillingStore`, which is the Play Billing library and exists only on
// Android. [PlayBillingStore.launch] takes no Activity here. [NoPlayBillingStore] stands in for it.
package com.vivenotes.data.billing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.security.MessageDigest
import java.util.Base64

internal data class PlayPurchase(
    val purchaseToken: String,
    val productId: String,
)

internal data class PlayBillingSnapshot(
    val formattedPrice: String?,
    val purchases: List<PlayPurchase>,
    val pending: Boolean,
    val productAvailable: Boolean,
)

internal enum class PlayBillingFailure {
    Unavailable,
    Network,
    ProductUnavailable,
    DeveloperError,
    AlreadyOwned,
    Unknown,
}

internal sealed interface PlayBillingUpdate {
    data class Purchases(
        val completed: List<PlayPurchase>,
        val pending: Boolean,
    ) : PlayBillingUpdate

    data object Canceled : PlayBillingUpdate
    data class Failed(val reason: PlayBillingFailure) : PlayBillingUpdate
}

internal sealed interface PlayBillingLaunch {
    data object Started : PlayBillingLaunch
    data object AlreadyOwned : PlayBillingLaunch
    data class Failed(val reason: PlayBillingFailure) : PlayBillingLaunch
}

/** Small seam around the Play Store so lifecycle logic can be tested without a licensed device. */
internal interface PlayBillingStore {
    val updates: Flow<PlayBillingUpdate>

    suspend fun refresh(): Result<PlayBillingSnapshot>

    fun launch(obfuscatedAccountId: String): PlayBillingLaunch
}

/**
 * Google Play, as the desktop sees it: no store to buy from and no purchases to restore.
 *
 * Subscriptions are bought through Play on Android; the server reports every entitlement either way,
 * so the desktop shows a plan and redeems coupons without ever holding a purchase token.
 */
internal object NoPlayBillingStore : PlayBillingStore {
    override val updates: Flow<PlayBillingUpdate> = emptyFlow()

    override suspend fun refresh(): Result<PlayBillingSnapshot> = Result.success(
        PlayBillingSnapshot(formattedPrice = null, purchases = emptyList(), pending = false, productAvailable = false),
    )

    override fun launch(obfuscatedAccountId: String): PlayBillingLaunch =
        PlayBillingLaunch.Failed(PlayBillingFailure.Unavailable)
}

internal class PlayBillingException(val reason: PlayBillingFailure) : Exception()

/** Must remain byte-for-byte identical to the managed server's purchase binding check. */
internal fun obfuscatedPlayAccountId(accountId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("vivenotes:$accountId".encodeToByteArray())
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}
