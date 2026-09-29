package com.vivenotes.ui.account

import kotlinx.coroutines.flow.StateFlow

data class AccountSession(
    val serverUrl: String,
    val email: String,
    val accountId: String,
    val deviceId: String,
    val membership: String,
    val provider: AccountProvider = AccountProvider.Password,
    val managed: Boolean = false,
    val createdNow: Boolean = false,
    val persisted: Boolean = false,
)

enum class AccountProvider { Password, Google }

data class AccountSubscription(
    val state: String,
    val validUntil: String? = null,
    val promotionalValidUntil: String? = null,
    val paidValidUntil: String? = null,
    val paidState: String? = null,
    val autoRenewing: Boolean = false,
    val productId: String? = null,
    val staffValidUntil: String? = null,
    val staffRevokedAt: String? = null,
)

data class AccountCoupon(val monthsGranted: Int, val validUntil: String)

data class AccountDevice(
    val id: String,
    val name: String,
    val platform: String,
    val revoked: Boolean,
)

data class AccountSyncStatus(
    val running: Boolean = false,
    val message: String = "Not synced yet",
    val pulled: Int = 0,
    val pushed: Int = 0,
    val pictures: Int = 0,
)

enum class GoogleSignInOutcome { Connected, LinkRequired, Dismissed }

open class AccountRequestException(val status: Int, val code: String?, message: String) : Exception(message)

/** Account operations. Credentials are never exposed to Compose state. */
interface AccountService {
    val session: StateFlow<AccountSession?>
    val syncStatus: StateFlow<AccountSyncStatus>? get() = null
    val managedServerUrl: String get() = ""
    val googleAvailable: Boolean get() = false
    suspend fun connect(serverUrl: String, email: String, password: String, create: Boolean)
    suspend fun connectManaged(email: String, password: String, create: Boolean) =
        connect(managedServerUrl, email, password, create)
    suspend fun requestPasswordReset(email: String) { throw UnsupportedOperationException("Password recovery is unavailable.") }
    suspend fun completePasswordReset(email: String, code: String, password: String) {
        throw UnsupportedOperationException("Password recovery is unavailable.")
    }
    suspend fun signInWithGoogle(): GoogleSignInOutcome {
        throw UnsupportedOperationException("Google sign-in is not configured.")
    }
    suspend fun linkGoogleAccount(email: String, password: String) {
        throw UnsupportedOperationException("Google account linking is unavailable.")
    }
    fun cancelGoogleLink() {}
    suspend fun refreshSubscription(): AccountSubscription {
        throw UnsupportedOperationException("Subscription status is unavailable.")
    }
    suspend fun redeemCoupon(code: String): AccountCoupon {
        throw UnsupportedOperationException("Coupons are unavailable.")
    }
    suspend fun listDevices(): List<AccountDevice> = emptyList()
    suspend fun renameDevice(id: String, name: String) {
        throw UnsupportedOperationException("Device renaming is unavailable.")
    }
    suspend fun revokeDevice(id: String) {
        throw UnsupportedOperationException("Device removal is unavailable.")
    }
    fun openPlayManagement() {}
    suspend fun synchronize(): AccountSyncStatus {
        throw UnsupportedOperationException("Sync is unavailable.")
    }
    suspend fun disconnect()
    suspend fun forget()
}
