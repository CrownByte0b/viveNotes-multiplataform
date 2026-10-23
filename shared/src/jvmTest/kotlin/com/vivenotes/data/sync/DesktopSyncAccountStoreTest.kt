package com.vivenotes.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop's `SyncAccountStore`: Android's record and installation id, in a DataStore file in the
 * profile's data directory. What the Android unit tests cannot cover is the file itself — that a
 * registration survives a restart, and that the directory holding it is private.
 */
class DesktopSyncAccountStoreTest {

    private val root: File = Files.createTempDirectory("sync-store").toFile()
    private val file = File(root, "datastore/sync.preferences_pb")

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun aRegistrationSurvivesARestart() = runBlocking {
        withStore { store ->
            assertNull(store.account.first())
            store.setAccount(ACCOUNT)
        }

        withStore { store -> assertEquals(ACCOUNT, store.account.first()) }
    }

    /**
     * Forgetting the registration keeps the installation: the next sign-in rotates the same device
     * row on the server instead of adding a second.
     */
    @Test
    fun forgettingTheRegistrationKeepsTheInstallationId() = runBlocking {
        val installation = withStore { store ->
            store.setAccount(ACCOUNT)
            store.installationId(SERVER).also { store.clear() }
        }

        withStore { store ->
            assertNull(store.account.first())
            assertEquals(installation, store.installationId(SERVER))
        }
    }

    /**
     * Derived from the machine's seed and the server, as Android derives it from `ANDROID_ID`: the
     * same after the app's data is deleted, different on another server, and random without a seed.
     */
    @Test
    fun theInstallationIdIsTheServerScopedHashOfTheSeed() = runBlocking {
        val derived = withStore(seed = SEED) { it.installationId(SERVER) }
        assertEquals(serverScopedInstallationId(SEED, SERVER), derived)

        root.deleteRecursively()
        assertEquals(derived, withStore(seed = SEED) { it.installationId(SERVER) })

        root.deleteRecursively()
        assertNotEquals(derived, withStore(seed = SEED) { it.installationId("https://notes.example.test") })

        root.deleteRecursively()
        val random = withStore(seed = null) { it.installationId(SERVER) }
        assertNotEquals(derived, random)
        assertEquals(random, withStore(seed = SEED) { it.installationId(SERVER) }, "a stored id is kept")
    }

    /** The desktop's stand-in for Android's app-private storage: only this user can enter it. */
    @Test
    fun theStoreLivesInADirectoryOnlyItsOwnerCanEnter() = runBlocking {
        withStore { store -> store.setAccount(ACCOUNT) }

        val directory = file.parentFile.toPath()
        if (directory.fileSystem.supportedFileAttributeViews().contains("posix")) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)))
        }
        assertTrue(file.isFile)
    }

    /**
     * Windows will not replace a file that is open, and DataStore reads its file without waiting for
     * a write, so a write can meet a reader. A clear that does is written once the reader lets go,
     * rather than leaving the registration on disk while the app shows it forgotten.
     */
    @Test
    fun aClearThatMeetsAnOpenReaderStillReachesTheDisk() = runBlocking {
        withStore { store ->
            store.setAccount(ACCOUNT)
            // FileInputStream, as DataStore's own reads use: on Windows it does not share deletion.
            val reader = FileInputStream(file)
            val released = launch(Dispatchers.IO) {
                try { delay(200) } finally { reader.close() }
            }
            store.clear()
            released.join()
        }

        withStore { store -> assertNull(store.account.first()) }
    }

    /** One store at a time per file, as DataStore requires; its scope is cancelled to release it. */
    private suspend fun <T> withStore(seed: String? = SEED, block: suspend (SyncAccountStore) -> T): T {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            return block(SyncAccountStore(file, seed, scope))
        } finally {
            scope.cancel()
            scope.coroutineContext.job.join()
        }
    }

    private companion object {
        const val SERVER = "http://localhost:5444"
        const val SEED = "0123456789abcdef0123456789abcdef\u0000owner\u0000DEVELOPMENT"
        val ACCOUNT = SyncAccount(
            serverUrl = SERVER,
            accountId = "10000000-0000-0000-0000-000000000001",
            deviceId = "20000000-0000-0000-0000-000000000002",
            token = "vive_test-token",
            deviceName = "fedora (3f2a)",
            email = "owner@example.com",
            authProvider = AccountAuthProvider.Password,
        )
    }
}
