package com.vivenotes.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object AccountTags {
    const val Open = "account-open"
    const val Screen = "account-screen"
    const val Back = "account-back"
    const val Server = "account-server"
    const val Email = "account-email"
    const val Password = "account-password"
    const val SignIn = "account-sign-in"
    const val Create = "account-create"
    const val CreateConfirm = "account-create-confirm"
    const val Google = "account-google"
    const val GoogleLink = "account-google-link"
    const val Status = "account-status"
    const val Disconnect = "account-disconnect"
    const val Forget = "account-forget"
    const val ResetRequest = "account-reset-request"
    const val ResetComplete = "account-reset-complete"
    const val ResetConfirm = "account-reset-confirm"
    const val Coupon = "account-coupon"
    const val CouponRedeem = "account-coupon-redeem"
    const val Subscription = "account-subscription"
    const val Devices = "account-devices"
    const val Sync = "account-sync"
}

/** Kept above the destination so an in-flight one-time device token is not lost on Back. */
internal class AccountScreenModel(private val service: AccountService?, private val scope: CoroutineScope) {
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var failedManagedLogins by mutableStateOf(0)
        private set
    var resetRequested by mutableStateOf(false)
        private set
    var resetComplete by mutableStateOf(false)
        private set
    var linkRequired by mutableStateOf(false)
        private set
    var disconnectFailed by mutableStateOf(false)
        private set
    var subscription by mutableStateOf<AccountSubscription?>(null)
        private set
    var devices by mutableStateOf<List<AccountDevice>>(emptyList())
        private set

    private fun launchAction(label: String, action: suspend (AccountService) -> Unit) {
        val target = service ?: return
        if (busy != null) return
        busy = label
        error = null
        notice = null
        scope.launch {
            try {
                action(target)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (label == "Sign in" && failure is AccountRequestException && failure.status == 401) {
                    failedManagedLogins++
                }
                error = failure.message?.take(240) ?: "The request failed."
            } finally {
                busy = null
            }
        }
    }

    fun connect(server: String, email: String, password: String, create: Boolean, managed: Boolean) =
        launchAction(if (create) "Create account" else "Sign in") { target ->
            if (managed) target.connectManaged(email, password, create)
            else target.connect(server, email, password, create)
            failedManagedLogins = 0
            notice = if (create) "Account created and signed in." else "Signed in."
            refreshDetails(target)
        }

    fun googleSignIn() = launchAction("Google sign-in") { target ->
        when (target.signInWithGoogle()) {
            GoogleSignInOutcome.Connected -> {
                linkRequired = false
                notice = if (target.session.value?.createdNow == true) "Account created with Google."
                    else "Signed in with Google."
                refreshDetails(target)
            }
            GoogleSignInOutcome.LinkRequired -> linkRequired = true
            GoogleSignInOutcome.Dismissed -> Unit
        }
    }

    fun linkGoogle(email: String, password: String) = launchAction("Link Google account") { target ->
        target.linkGoogleAccount(email, password)
        linkRequired = false
        notice = "Google account linked."
        refreshDetails(target)
    }

    fun cancelGoogleLink() {
        service?.cancelGoogleLink()
        linkRequired = false
    }

    fun requestReset(email: String) = launchAction("Request reset") { target ->
        target.requestPasswordReset(email)
        resetRequested = true
        notice = "If that email is registered, recovery instructions will be sent."
    }

    fun completeReset(email: String, code: String, password: String) = launchAction("Reset password") { target ->
        target.completePasswordReset(email, code, password)
        resetComplete = true
        failedManagedLogins = 0
        notice = "Password changed. Sign in with the new password."
    }

    fun refresh() = launchAction("Refresh") { target -> refreshDetails(target) }

    fun sync() = launchAction("Sync") { target ->
        val result = target.synchronize()
        notice = result.message
    }

    private suspend fun refreshDetails(target: AccountService) {
        try {
            subscription = target.refreshSubscription()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = "Membership could not be loaded: ${failure.message.orEmpty().take(120)}"
        }
        try {
            devices = target.listDevices()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = "Devices could not be loaded: ${failure.message.orEmpty().take(120)}"
        }
    }

    fun redeemCoupon(code: String) = launchAction("Redeem coupon") { target ->
        val grant = target.redeemCoupon(code)
        notice = "Coupon added ${grant.monthsGranted} month(s) through ${grant.validUntil}."
        subscription = target.refreshSubscription()
    }

    fun renameDevice(id: String, name: String) = launchAction("Rename device") { target ->
        target.renameDevice(id, name)
        devices = target.listDevices()
        notice = "Device renamed."
    }

    fun revokeDevice(id: String) = launchAction("Remove device") { target ->
        target.revokeDevice(id)
        devices = target.listDevices()
        notice = "Device removed."
    }

    fun disconnect() = launchAction("Disconnect") { target ->
        try {
            target.disconnect()
            subscription = null
            devices = emptyList()
            disconnectFailed = false
            notice = "Device disconnected."
        } catch (failure: Exception) {
            disconnectFailed = true
            throw failure
        }
    }

    fun forget() = launchAction("Forget locally") { target ->
        target.forget()
        subscription = null
        devices = emptyList()
        disconnectFailed = false
        notice = "Forgot this connection. The server may still list this device."
    }
}

/** Account is a destination occupying the application window, as on Android. */
@Composable
internal fun AccountScreen(service: AccountService?, model: AccountScreenModel, onBack: () -> Unit) {
    val session = service?.session?.collectAsState()?.value
    val syncStatus = service?.syncStatus?.collectAsState()?.value
    LaunchedEffect(session?.accountId) {
        if (session != null) model.refresh()
    }
    var managedEmail by remember { mutableStateOf("") }
    var managedPassword by remember { mutableStateOf("") }
    var managedConfirm by remember { mutableStateOf("") }
    var managedCreate by remember { mutableStateOf(false) }
    var selfHostOpen by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf("http://localhost:5444") }
    var hostEmail by remember { mutableStateOf("") }
    var hostPassword by remember { mutableStateOf("") }
    var resetOpen by remember { mutableStateOf(false) }
    var resetCode by remember { mutableStateOf("") }
    var resetPassword by remember { mutableStateOf("") }
    var resetConfirm by remember { mutableStateOf("") }
    var coupon by remember { mutableStateOf("") }
    var renameId by remember { mutableStateOf<String?>(null) }
    var deviceName by remember { mutableStateOf("") }
    val busy = model.busy != null

    Surface(Modifier.fillMaxSize().testTag(AccountTags.Screen), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onBack, modifier = Modifier.testTag(AccountTags.Back)) { Text("← Back") }
                Text("Account", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                if (busy) Text(model.busy ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (service == null) {
                        Text("Account connections are unavailable in this preview.")
                    } else if (session == null) {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Vive Notes Cloud", style = MaterialTheme.typography.titleMedium)
                                Button(onClick = model::googleSignIn, enabled = service.googleAvailable && !busy,
                                    modifier = Modifier.fillMaxWidth().testTag(AccountTags.Google)) {
                                    Text("Continue with Google")
                                }
                                if (!service.googleAvailable) Text("Google sign-in needs a desktop OAuth client ID.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (model.linkRequired) {
                                    Text("This Google email belongs to a password account. Enter its password to link them.")
                                    OutlinedTextField(managedEmail, { managedEmail = it }, label = { Text("Email") },
                                        modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(managedPassword, { managedPassword = it },
                                        label = { Text("Existing account password") },
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth())
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = model::cancelGoogleLink) { Text("Cancel") }
                                        Button(onClick = { model.linkGoogle(managedEmail, managedPassword) },
                                            enabled = !busy, modifier = Modifier.testTag(AccountTags.GoogleLink)) {
                                            Text("Link account")
                                        }
                                    }
                                }
                                HorizontalDivider()
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { managedCreate = false }) { Text("Sign in") }
                                    TextButton(onClick = { managedCreate = true }) { Text("Create account") }
                                }
                                OutlinedTextField(managedEmail, { managedEmail = it }, label = { Text("Email") },
                                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag(AccountTags.Email))
                                OutlinedTextField(managedPassword, { managedPassword = it },
                                    label = { Text("Password") }, singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth().testTag(AccountTags.Password))
                                if (managedCreate) OutlinedTextField(managedConfirm, { managedConfirm = it },
                                    label = { Text("Confirm password") }, singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth().testTag(AccountTags.CreateConfirm))
                                Button(onClick = {
                                    model.connect("", managedEmail, managedPassword, managedCreate, managed = true)
                                    managedPassword = ""
                                    managedConfirm = ""
                                }, enabled = !busy && (!managedCreate ||
                                    (managedPassword.length >= 8 && managedPassword == managedConfirm)),
                                    modifier = Modifier.testTag(
                                    if (managedCreate) AccountTags.Create else AccountTags.SignIn)) {
                                    Text(if (managedCreate) "Create account" else "Sign in")
                                }
                                if (model.failedManagedLogins >= 2 || resetOpen) {
                                    TextButton(onClick = { resetOpen = !resetOpen }) { Text("Forgot password?") }
                                }
                                if (resetOpen) {
                                    OutlinedButton(onClick = { model.requestReset(managedEmail) }, enabled = !busy,
                                        modifier = Modifier.testTag(AccountTags.ResetRequest)) {
                                        Text("Email recovery code")
                                    }
                                    if (model.resetRequested) {
                                        OutlinedTextField(resetCode, { resetCode = it },
                                            label = { Text("8-digit code") }, modifier = Modifier.fillMaxWidth())
                                        OutlinedTextField(resetPassword, { resetPassword = it },
                                            label = { Text("New password") },
                                            visualTransformation = PasswordVisualTransformation(),
                                            modifier = Modifier.fillMaxWidth())
                                        OutlinedTextField(resetConfirm, { resetConfirm = it },
                                            label = { Text("Confirm new password") },
                                            visualTransformation = PasswordVisualTransformation(),
                                            modifier = Modifier.fillMaxWidth().testTag(AccountTags.ResetConfirm))
                                        Button(onClick = {
                                            model.completeReset(managedEmail, resetCode, resetPassword)
                                            resetPassword = ""
                                            resetConfirm = ""
                                        }, enabled = !busy && resetCode.length == 8 &&
                                            resetPassword.length >= 8 && resetPassword == resetConfirm,
                                            modifier = Modifier.testTag(AccountTags.ResetComplete)) {
                                            Text("Change password")
                                        }
                                    }
                                }
                            }
                        }
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(onClick = { selfHostOpen = !selfHostOpen }) {
                                    Text(if (selfHostOpen) "Hide self-hosted server" else "Connect to a self-hosted server")
                                }
                                if (selfHostOpen) {
                                    OutlinedTextField(server, { server = it }, label = { Text("Server URL") },
                                        modifier = Modifier.fillMaxWidth().testTag(AccountTags.Server))
                                    OutlinedTextField(hostEmail, { hostEmail = it }, label = { Text("Email") },
                                        modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(hostPassword, { hostPassword = it },
                                        label = { Text("Password") },
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth())
                                    Button(onClick = {
                                        model.connect(server, hostEmail, hostPassword, false, managed = false)
                                        hostPassword = ""
                                    }, enabled = !busy) { Text("Connect") }
                                }
                            }
                        }
                    } else {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(session.email, style = MaterialTheme.typography.titleMedium)
                                Text(if (session.managed) "Vive Notes Cloud" else session.serverUrl)
                                Text("Signed in with ${session.provider.name}")
                                if (session.createdNow) Text("Account created")
                                if (!session.persisted) Text("This sign-in lasts until you close the app. Secure credential storage is unavailable.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = model::refresh, enabled = !busy) { Text("Refresh") }
                                    OutlinedButton(onClick = model::disconnect, enabled = !busy,
                                        modifier = Modifier.testTag(AccountTags.Disconnect)) { Text("Disconnect") }
                                }
                                if (model.disconnectFailed) TextButton(onClick = model::forget,
                                    modifier = Modifier.testTag(AccountTags.Forget)) { Text("Forget locally") }
                            }
                        }
                        if (session.managed) {
                            OutlinedCard(Modifier.fillMaxWidth().testTag(AccountTags.Subscription)) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Cloud storage plan", style = MaterialTheme.typography.titleMedium)
                                    val plan = model.subscription
                                    Text(if (plan == null) "Checking membership…" else
                                        if (plan.state == "active") "Active membership" else "No active membership")
                                    plan?.validUntil?.let { Text("Available until $it") }
                                    plan?.paidState?.let { Text("Google Play: $it") }
                                    plan?.paidValidUntil?.let { Text("Paid time through $it") }
                                    plan?.promotionalValidUntil?.let { Text("Promotional time through $it") }
                                    plan?.staffValidUntil?.let { Text("Staff time through $it") }
                                    if (plan?.staffRevokedAt != null) Text("Staff access revoked")
                                    if (plan?.autoRenewing == true) Text("Renews automatically")
                                    if (plan?.paidState != null) {
                                        TextButton(onClick = service::openPlayManagement) {
                                            Text("Manage Google Play subscription")
                                        }
                                    }
                                    OutlinedTextField(coupon, { coupon = it }, label = { Text("Coupon code") },
                                        modifier = Modifier.fillMaxWidth().testTag(AccountTags.Coupon))
                                    Button(onClick = { model.redeemCoupon(coupon) }, enabled = !busy && coupon.isNotBlank(),
                                        modifier = Modifier.testTag(AccountTags.CouponRedeem)) {
                                        Text("Redeem coupon")
                                    }
                                }
                            }
                        }
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Synchronization", style = MaterialTheme.typography.titleMedium)
                                Text(syncStatus?.message ?: "Sync is unavailable in this preview")
                                Button(onClick = model::sync, enabled = !busy && syncStatus?.running != true &&
                                    syncStatus != null, modifier = Modifier.testTag(AccountTags.Sync)) {
                                    Text(if (syncStatus?.running == true) "Syncing…" else "Sync now")
                                }
                            }
                        }
                        OutlinedCard(Modifier.fillMaxWidth().testTag(AccountTags.Devices)) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Devices", style = MaterialTheme.typography.titleMedium)
                                model.devices.forEach { device ->
                                    Column(Modifier.fillMaxWidth()) {
                                        Text("${device.name} · ${device.platform}" +
                                            if (device.id == session.deviceId) " · This device" else "")
                                        if (device.revoked) Text("Revoked") else {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                TextButton(onClick = {
                                                    renameId = device.id
                                                    deviceName = device.name
                                                }) { Text("Rename") }
                                                if (device.id != session.deviceId) TextButton(
                                                    onClick = { model.revokeDevice(device.id) }, enabled = !busy) {
                                                    Text("Remove")
                                                }
                                            }
                                        }
                                        if (renameId == device.id) {
                                            OutlinedTextField(deviceName, { deviceName = it },
                                                label = { Text("Device name") })
                                            Button(onClick = {
                                                model.renameDevice(device.id, deviceName)
                                                renameId = null
                                            }, enabled = !busy) { Text("Save") }
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                    model.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(AccountTags.Status)) }
                    model.notice?.let { Text(it, modifier = Modifier.testTag(AccountTags.Status)) }
                }
            }
        }
    }
}
