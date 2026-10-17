package com.vivenotes.ui.account

import com.vivenotes.localization.Strings
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
                if (label == Strings.sign_in && failure is AccountRequestException && failure.status == 401) {
                    failedManagedLogins++
                }
                error = failure.message?.take(240) ?: Strings.the_request_failed
            } finally {
                busy = null
            }
        }
    }

    fun connect(server: String, email: String, password: String, create: Boolean, managed: Boolean) =
        launchAction(if (create) Strings.create_account else Strings.sign_in) { target ->
            if (managed) target.connectManaged(email, password, create)
            else target.connect(server, email, password, create)
            failedManagedLogins = 0
            notice = if (create) Strings.account_created_and_signed_in else Strings.signed_in
            refreshDetails(target)
        }

    fun googleSignIn() = launchAction(Strings.google_sign_in) { target ->
        when (target.signInWithGoogle()) {
            GoogleSignInOutcome.Connected -> {
                linkRequired = false
                notice = if (target.session.value?.createdNow == true) Strings.account_created_with_google
                    else Strings.signed_in_with_google
                refreshDetails(target)
            }
            GoogleSignInOutcome.LinkRequired -> linkRequired = true
            GoogleSignInOutcome.Dismissed -> Unit
        }
    }

    fun linkGoogle(email: String, password: String) = launchAction(Strings.link_google_account) { target ->
        target.linkGoogleAccount(email, password)
        linkRequired = false
        notice = Strings.google_account_linked
        refreshDetails(target)
    }

    fun cancelGoogleLink() {
        service?.cancelGoogleLink()
        linkRequired = false
    }

    fun requestReset(email: String) = launchAction(Strings.request_reset) { target ->
        target.requestPasswordReset(email)
        resetRequested = true
        notice = Strings.if_that_email_is_registered_recovery_instructions_will_be_sent
    }

    fun completeReset(email: String, code: String, password: String) = launchAction(Strings.reset_password) { target ->
        target.completePasswordReset(email, code, password)
        resetComplete = true
        failedManagedLogins = 0
        notice = Strings.password_changed_sign_in_with_the_new_password
    }

    fun refresh() = launchAction(Strings.refresh) { target -> refreshDetails(target) }

    fun sync() = launchAction(Strings.sync) { target ->
        val result = target.synchronize()
        notice = result.message
    }

    private suspend fun refreshDetails(target: AccountService) {
        try {
            subscription = target.refreshSubscription()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = Strings.membership_could_not_be_loaded_value(failure.message.orEmpty().take(120))
        }
        try {
            devices = target.listDevices()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = Strings.devices_could_not_be_loaded_value(failure.message.orEmpty().take(120))
        }
    }

    fun redeemCoupon(code: String) = launchAction(Strings.redeem_coupon) { target ->
        val grant = target.redeemCoupon(code)
        notice = Strings.coupon_added_value_month_s_through_value(grant.monthsGranted, grant.validUntil)
        subscription = target.refreshSubscription()
    }

    fun renameDevice(id: String, name: String) = launchAction(Strings.rename_device) { target ->
        target.renameDevice(id, name)
        devices = target.listDevices()
        notice = Strings.device_renamed
    }

    fun revokeDevice(id: String) = launchAction(Strings.remove_device) { target ->
        target.revokeDevice(id)
        devices = target.listDevices()
        notice = Strings.device_removed
    }

    fun disconnect() = launchAction(Strings.disconnect) { target ->
        try {
            target.disconnect()
            subscription = null
            devices = emptyList()
            disconnectFailed = false
            notice = Strings.device_disconnected
        } catch (failure: Exception) {
            disconnectFailed = true
            throw failure
        }
    }

    fun forget() = launchAction(Strings.forget_locally) { target ->
        target.forget()
        subscription = null
        devices = emptyList()
        disconnectFailed = false
        notice = Strings.forgot_this_connection_the_server_may_still_list_this_device
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
                TextButton(onClick = onBack, modifier = Modifier.testTag(AccountTags.Back)) { Text(Strings.back) }
                Text(Strings.account, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                if (busy) Text(model.busy ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (service == null) {
                        Text(Strings.account_connections_are_unavailable_in_this_preview)
                    } else if (session == null) {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(Strings.vive_notes_cloud, style = MaterialTheme.typography.titleMedium)
                                Button(onClick = model::googleSignIn, enabled = service.googleAvailable && !busy,
                                    modifier = Modifier.fillMaxWidth().testTag(AccountTags.Google)) {
                                    Text(Strings.continue_with_google)
                                }
                                if (!service.googleAvailable) Text(Strings.google_sign_in_needs_a_desktop_oauth_client_id,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (model.linkRequired) {
                                    Text(Strings.this_google_email_belongs_to_a_password_account_enter_its_password)
                                    OutlinedTextField(managedEmail, { managedEmail = it }, label = { Text(Strings.email) },
                                        modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(managedPassword, { managedPassword = it },
                                        label = { Text(Strings.existing_account_password) },
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth())
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = model::cancelGoogleLink) { Text(Strings.cancel) }
                                        Button(onClick = { model.linkGoogle(managedEmail, managedPassword) },
                                            enabled = !busy, modifier = Modifier.testTag(AccountTags.GoogleLink)) {
                                            Text(Strings.link_account)
                                        }
                                    }
                                }
                                HorizontalDivider()
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { managedCreate = false }) { Text(Strings.sign_in) }
                                    TextButton(onClick = { managedCreate = true }) { Text(Strings.create_account) }
                                }
                                OutlinedTextField(managedEmail, { managedEmail = it }, label = { Text(Strings.email) },
                                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag(AccountTags.Email))
                                OutlinedTextField(managedPassword, { managedPassword = it },
                                    label = { Text(Strings.password) }, singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth().testTag(AccountTags.Password))
                                if (managedCreate) OutlinedTextField(managedConfirm, { managedConfirm = it },
                                    label = { Text(Strings.confirm_password) }, singleLine = true,
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
                                    Text(if (managedCreate) Strings.create_account else Strings.sign_in)
                                }
                                if (model.failedManagedLogins >= 2 || resetOpen) {
                                    TextButton(onClick = { resetOpen = !resetOpen }) { Text(Strings.forgot_password) }
                                }
                                if (resetOpen) {
                                    OutlinedButton(onClick = { model.requestReset(managedEmail) }, enabled = !busy,
                                        modifier = Modifier.testTag(AccountTags.ResetRequest)) {
                                        Text(Strings.email_recovery_code)
                                    }
                                    if (model.resetRequested) {
                                        OutlinedTextField(resetCode, { resetCode = it },
                                            label = { Text(Strings.label_8_digit_code) }, modifier = Modifier.fillMaxWidth())
                                        OutlinedTextField(resetPassword, { resetPassword = it },
                                            label = { Text(Strings.new_password) },
                                            visualTransformation = PasswordVisualTransformation(),
                                            modifier = Modifier.fillMaxWidth())
                                        OutlinedTextField(resetConfirm, { resetConfirm = it },
                                            label = { Text(Strings.confirm_new_password) },
                                            visualTransformation = PasswordVisualTransformation(),
                                            modifier = Modifier.fillMaxWidth().testTag(AccountTags.ResetConfirm))
                                        Button(onClick = {
                                            model.completeReset(managedEmail, resetCode, resetPassword)
                                            resetPassword = ""
                                            resetConfirm = ""
                                        }, enabled = !busy && resetCode.length == 8 &&
                                            resetPassword.length >= 8 && resetPassword == resetConfirm,
                                            modifier = Modifier.testTag(AccountTags.ResetComplete)) {
                                            Text(Strings.change_password)
                                        }
                                    }
                                }
                            }
                        }
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(onClick = { selfHostOpen = !selfHostOpen }) {
                                    Text(if (selfHostOpen) Strings.hide_self_hosted_server else Strings.connect_to_a_self_hosted_server)
                                }
                                if (selfHostOpen) {
                                    OutlinedTextField(server, { server = it }, label = { Text(Strings.server_url) },
                                        modifier = Modifier.fillMaxWidth().testTag(AccountTags.Server))
                                    OutlinedTextField(hostEmail, { hostEmail = it }, label = { Text(Strings.email) },
                                        modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(hostPassword, { hostPassword = it },
                                        label = { Text(Strings.password) },
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth())
                                    Button(onClick = {
                                        model.connect(server, hostEmail, hostPassword, false, managed = false)
                                        hostPassword = ""
                                    }, enabled = !busy) { Text(Strings.connect) }
                                }
                            }
                        }
                    } else {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(session.email, style = MaterialTheme.typography.titleMedium)
                                Text(if (session.managed) Strings.vive_notes_cloud else session.serverUrl)
                                Text(Strings.signed_in_with_value(if (session.provider == AccountProvider.Google) Strings.google_provider else Strings.password_provider))
                                if (session.createdNow) Text(Strings.account_created)
                                if (!session.persisted) Text(Strings.this_sign_in_lasts_until_you_close_the_app_secure_credential,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = model::refresh, enabled = !busy) { Text(Strings.refresh) }
                                    OutlinedButton(onClick = model::disconnect, enabled = !busy,
                                        modifier = Modifier.testTag(AccountTags.Disconnect)) { Text(Strings.disconnect) }
                                }
                                if (model.disconnectFailed) TextButton(onClick = model::forget,
                                    modifier = Modifier.testTag(AccountTags.Forget)) { Text(Strings.forget_locally) }
                            }
                        }
                        if (session.managed) {
                            OutlinedCard(Modifier.fillMaxWidth().testTag(AccountTags.Subscription)) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(Strings.cloud_storage_plan, style = MaterialTheme.typography.titleMedium)
                                    val plan = model.subscription
                                    Text(if (plan == null) Strings.checking_membership else
                                        if (plan.state == "active") Strings.active_membership else Strings.no_active_membership)
                                    plan?.validUntil?.let { Text(Strings.available_until_value(it)) }
                                    plan?.paidState?.let { Text(Strings.google_play_value(it)) }
                                    plan?.paidValidUntil?.let { Text(Strings.paid_time_through_value(it)) }
                                    plan?.promotionalValidUntil?.let { Text(Strings.promotional_time_through_value(it)) }
                                    plan?.staffValidUntil?.let { Text(Strings.staff_time_through_value(it)) }
                                    if (plan?.staffRevokedAt != null) Text(Strings.staff_access_revoked)
                                    if (plan?.autoRenewing == true) Text(Strings.renews_automatically)
                                    if (plan?.paidState != null) {
                                        TextButton(onClick = service::openPlayManagement) {
                                            Text(Strings.manage_google_play_subscription)
                                        }
                                    }
                                    OutlinedTextField(coupon, { coupon = it }, label = { Text(Strings.coupon_code) },
                                        modifier = Modifier.fillMaxWidth().testTag(AccountTags.Coupon))
                                    Button(onClick = { model.redeemCoupon(coupon) }, enabled = !busy && coupon.isNotBlank(),
                                        modifier = Modifier.testTag(AccountTags.CouponRedeem)) {
                                        Text(Strings.redeem_coupon)
                                    }
                                }
                            }
                        }
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(Strings.synchronization, style = MaterialTheme.typography.titleMedium)
                                Text(syncStatus?.message ?: Strings.sync_is_unavailable_in_this_preview)
                                Button(onClick = model::sync, enabled = !busy && syncStatus?.running != true &&
                                    syncStatus != null, modifier = Modifier.testTag(AccountTags.Sync)) {
                                    Text(if (syncStatus?.running == true) Strings.syncing else Strings.sync_now)
                                }
                            }
                        }
                        OutlinedCard(Modifier.fillMaxWidth().testTag(AccountTags.Devices)) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(Strings.devices, style = MaterialTheme.typography.titleMedium)
                                model.devices.forEach { device ->
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(Strings.value_value(device.name, device.platform) +
                                            if (device.id == session.deviceId) Strings.this_device else "")
                                        if (device.revoked) Text(Strings.revoked) else {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                TextButton(onClick = {
                                                    renameId = device.id
                                                    deviceName = device.name
                                                }) { Text(Strings.rename) }
                                                if (device.id != session.deviceId) TextButton(
                                                    onClick = { model.revokeDevice(device.id) }, enabled = !busy) {
                                                    Text(Strings.remove)
                                                }
                                            }
                                        }
                                        if (renameId == device.id) {
                                            OutlinedTextField(deviceName, { deviceName = it },
                                                label = { Text(Strings.device_name) })
                                            Button(onClick = {
                                                model.renameDevice(device.id, deviceName)
                                                renameId = null
                                            }, enabled = !busy) { Text(Strings.save) }
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
