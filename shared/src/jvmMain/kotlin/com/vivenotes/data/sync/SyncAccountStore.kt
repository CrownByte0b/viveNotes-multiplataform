// Ported from the Android app's `data/sync/SyncAccountStore.kt`. The record, its JSON, the store and
// the installation id are Android's. DataStore is opened on a file instead of through a `Context`,
// the platform identifier Android reads from `ANDROID_ID` is [deviceSeed], and writes retry the
// file replacement Windows refuses while a reader has the file open.
package com.vivenotes.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID

/** The identity provider used to establish this installation's current device credential. */
@Serializable
enum class AccountAuthProvider {
    @SerialName("password")
    Password,

    @SerialName("google")
    Google,
}

/**
 * This installation's registration with one self-hosted server.
 *
 * One record, not a list: an installation syncs with a single server, and a second one would mean
 * two copies of the notebook tree with no rule for which owns a page.
 *
 * The password is deliberately absent and must stay absent. It is typed once, exchanged for
 * [token], and forgotten — that split is the whole reason `POST /v1/devices` exists as a separate
 * endpoint (`viveCServer/docs/openapi.yaml`), and keeping the password here would throw it away.
 */
@Serializable
data class SyncAccount(
    /** Already normalised by [normaliseServerAddress]; every later request is built from it. */
    val serverUrl: String,
    val accountId: String,
    val deviceId: String,
    /**
     * The bearer credential, held because the server cannot reproduce it — only its SHA-256 is
     * stored. Losing this row requires authenticating for a replacement and can leave an unusable
     * credential behind, which is why it is written in the same `edit` as everything else here.
     */
    val token: String,
    /** What the server will show for this device, kept so the UI can name it without a round trip. */
    val deviceName: String,
    /** Login identity shown on Account. Optional so credentials stored by older builds still load. */
    val email: String? = null,
    /** Provider used for the current login. Optional for the same upgrade reason as [email]. */
    val authProvider: AccountAuthProvider? = null,
)

/**
 * Persists the device token across launches.
 *
 * Android keeps it in plain DataStore in app-private storage rather than in an encrypted store, and
 * that is a decision: `EncryptedSharedPreferences` is deprecated in androidx.security and its
 * replacement is not settled, and what it bought on a device with file-based encryption and a lock
 * screen was protection against another app reading the file, which app-private storage already
 * gives. The credential's real defence is that it is revocable.
 *
 * The desktop has no app-private storage, so the nearest equivalent is used: the file lives in a
 * directory only this user can enter ([file]'s parent is created owner-only where the platform
 * supports POSIX permissions; Windows already keeps `%APPDATA%` per user).
 *
 * One JSON blob rather than separate keys: these values are only ever read and written together,
 * and a half-written registration is a credential that cannot work.
 */
class SyncAccountStore(
    /**
     * Android's `datastore/sync.preferences_pb` in the app's files directory. Must end in
     * `.preferences_pb`, and only one store may be open on it per process, as with any DataStore.
     */
    file: File,
    /**
     * Stands in for Android's `Settings.Secure.ANDROID_ID`: an identifier for this machine, user and
     * app that survives deleting and reinstalling the app, or null where the platform has none. It is
     * only ever hashed — see [installationId].
     */
    private val deviceSeed: String?,
    /**
     * Where DataStore runs its reads and writes. Its own by default, and deliberately not one the
     * window cancels at close: a token being written as the app closes must still land. A test passes
     * one it can cancel, which releases the file for another store.
     */
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {

    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { file.also { ownerOnlyDirectory(it.parentFile) } },
    )

    /** Null until this installation has been connected to a server. */
    val account: Flow<SyncAccount?> = store.data.map { prefs ->
        prefs[ACCOUNT]?.let(::decode)
    }

    /**
     * This device's stable, server-scoped app id, assigned on first use and retained locally.
     *
     * Deliberately outside [SyncAccount] and untouched by [clear]. It is what the Google routes send
     * as `device.installationId`, and the server uses it to recognise a returning installation, so
     * signing out and back in rotates one device row instead of adding a second. Tying it to the
     * account record would mint a new identity on every disconnect.
     *
     * New installations derive the UUID from [deviceSeed] plus [serverBaseUrl]. Hashing it with the
     * server origin means neither the raw value nor a cross-server identifier leaves the app. Unlike a
     * random UUID it survives deleting the app's data on the same machine. Existing stored ids are
     * retained, so a later change to the seed produces no duplicate.
     *
     * The read-then-write is safe against two callers because DataStore serialises `edit`
     * transactions: the second sees the first's value and returns it.
     */
    suspend fun installationId(serverBaseUrl: String): String {
        store.data.map { it[INSTALLATION_ID] }.first()?.let { return it }

        val candidate = deviceSeed?.takeIf { it.isNotBlank() }
            ?.let { serverScopedInstallationId(it, serverBaseUrl) }
            ?: UUID.randomUUID().toString()
        var assigned = ""
        write { prefs ->
            assigned = prefs[INSTALLATION_ID]
                ?: candidate.also { prefs[INSTALLATION_ID] = it }
        }
        return assigned
    }

    suspend fun setAccount(account: SyncAccount) {
        write { it[ACCOUNT] = syncAccountJson.encodeToString(SyncAccount.serializer(), account) }
    }

    /**
     * Forgets the registration locally. It does **not** revoke the current token on the server —
     * that is a request this app cannot make once the token is gone, so revocation belongs on the
     * device list or the admin dashboard.
     */
    suspend fun clear() {
        write { it.remove(ACCOUNT) }
    }

    private suspend fun write(transform: suspend (MutablePreferences) -> Unit) {
        retrySyncAccountWrite { store.edit(transform) }
    }

    /**
     * A blob written by a build with a field this one does not have decodes anyway; one missing a
     * required field is treated as no registration at all, because a partial credential would fail
     * every request while the UI claimed to be connected.
     */
    private fun decode(text: String): SyncAccount? =
        runCatching { syncAccountJson.decodeFromString(SyncAccount.serializer(), text) }.getOrNull()

    private companion object {
        val ACCOUNT = stringPreferencesKey("account")

        /** Separate from [ACCOUNT] so that forgetting a registration does not forget the install. */
        val INSTALLATION_ID = stringPreferencesKey("installationId")
    }
}

/** DataStore 1.3 wraps a refused Windows file replacement in an [IOException]. */
internal suspend fun <T> retrySyncAccountWrite(write: suspend () -> T): T {
    var attempt = 1
    while (true) {
        try {
            return write()
        } catch (refused: IOException) {
            if (generateSequence<Throwable>(refused) { it.cause }.none { it is AccessDeniedException } ||
                attempt == REPLACE_ATTEMPTS) throw refused
            delay(REPLACE_RETRY_MILLIS * attempt++)
        }
    }
}

/** Wait 50 ms longer each time, 750 ms in all, for a reader of this small file to close. */
private const val REPLACE_ATTEMPTS = 6
private const val REPLACE_RETRY_MILLIS = 50L

private val syncAccountJson: Json = Json { ignoreUnknownKeys = true }

/**
 * Creates [directory] so that only its owner can list or enter it, the desktop stand-in for Android's
 * app-private storage. Where the file system has no POSIX permissions (Windows) the per-user profile
 * directory is already private, and the directory is simply created.
 */
private fun ownerOnlyDirectory(directory: File?) {
    if (directory == null) return
    val path = directory.toPath()
    val posix = runCatching { path.fileSystem.supportedFileAttributeViews().contains("posix") }.getOrDefault(false)
    if (!posix) {
        directory.mkdirs()
        return
    }
    val ownerOnly = PosixFilePermissions.fromString("rwx------")
    if (!Files.isDirectory(path)) {
        Files.createDirectories(path, PosixFilePermissions.asFileAttribute(ownerOnly))
    }
    Files.setPosixFilePermissions(path, ownerOnly)
}

/** A UUIDv8-shaped, one-way identifier that cannot be correlated between two server origins. */
internal fun serverScopedInstallationId(androidId: String, serverBaseUrl: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest("viveNotes-installation\u0000$serverBaseUrl\u0000$androidId".encodeToByteArray())
        .copyOf(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x80).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val buffer = ByteBuffer.wrap(bytes)
    return UUID(buffer.long, buffer.long).toString()
}
