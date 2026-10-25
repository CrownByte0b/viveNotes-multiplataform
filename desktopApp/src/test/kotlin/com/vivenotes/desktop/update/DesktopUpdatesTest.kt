package com.vivenotes.desktop.update

import com.vivenotes.desktop.DesktopProfile
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.ui.update.AvailableUpdate
import com.vivenotes.ui.update.UpdateState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.UnknownHostException
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/** When the installed app offers an update, and what each of the dialog's choices then does. */
class DesktopUpdatesTest {
    @get:Rule val temp = TemporaryFolder()

    private val log = mutableListOf<String>()
    private val newer = release("0.2.0", notes = "• Faster ink")
    private val update = AvailableUpdate("0.2.0", "0.1.0", "• Faster ink", newer.installer.bytes)

    @Test
    fun aNewerReleaseIsOfferedAfterTheLaunchCheckAndOldInstallersAreCleared() = runBlocking {
        val harness = Harness(this)
        val downloads = harness.downloads.apply { mkdirs() }
        File(downloads, "ViveNotes-0.1.5-x64.msi").writeText("old")
        File(downloads, "ViveNotes-0.2.0-x64.msi-123.part").writeText("unfinished")
        File(downloads, WindowsMsiInstaller.INSTALL_LOG).writeText("last install")

        harness.updates.start().join()

        assertEquals(UpdateState.Available(update), harness.updates.state.value)
        assertEquals(listOf(WindowsMsiInstaller.INSTALL_LOG), downloads.list()!!.toList())
        harness.scope.cancel()
    }

    @Test
    fun nothingIsOfferedWhenUpToDateOfflineOrWithoutAnyRelease() = runBlocking {
        for (latest in listOf(release("0.1.0"), release("0.0.9"), null)) {
            val harness = Harness(this, feed = { latest })
            harness.updates.check()
            assertEquals(UpdateState.Idle, harness.updates.state.value, "latest $latest")
            harness.scope.cancel()
        }
        val offline = Harness(this, feed = { throw UnknownHostException("api.github.com") })
        offline.updates.check()
        assertEquals(UpdateState.Idle, offline.updates.state.value)
        assertTrue(log.any { it.contains("check for updates failed: UnknownHostException") }, log.toString())
        offline.scope.cancel()
    }

    @Test
    fun aSkippedVersionStaysSkippedAcrossLaunchesUntilANewerOneComesOut() = runBlocking {
        val first = Harness(this)
        first.updates.check()
        first.updates.skip()
        assertEquals(UpdateState.Idle, first.updates.state.value)
        first.scope.cancel()

        val relaunched = Harness(this)
        relaunched.updates.check()
        assertEquals(UpdateState.Idle, relaunched.updates.state.value)
        relaunched.scope.cancel()

        val newerStill = Harness(this, feed = { release("0.2.1") })
        newerStill.updates.check()
        assertEquals("0.2.1", assertIs<UpdateState.Available>(newerStill.updates.state.value).update.version)
        newerStill.scope.cancel()
    }

    @Test
    fun laterPutsTheUpdateOffOnlyUntilTheNextLaunch() = runBlocking {
        val first = Harness(this)
        first.updates.check()
        first.updates.later()
        assertEquals(UpdateState.Idle, first.updates.state.value)
        first.scope.cancel()

        val relaunched = Harness(this)
        relaunched.updates.check()
        assertEquals(UpdateState.Available(update), relaunched.updates.state.value)
        relaunched.scope.cancel()
    }

    @Test
    fun installingDownloadsThenClosesTheAppAndHandsTheVerifiedInstallerOver() = runBlocking {
        val seen = mutableListOf<UpdateState>()
        lateinit var harness: Harness
        harness = Harness(this, downloader = { release, directory, onBytes ->
            seen += harness.updates.state.value
            onBytes(1_000)
            seen += harness.updates.state.value
            onBytes(release.installer.bytes)
            File(directory, release.installer.name).apply { parentFile.mkdirs(); writeText("msi") }
        })
        harness.updates.check()

        harness.updates.install()
        harness.awaitClose()

        assertEquals(listOf<UpdateState>(UpdateState.Downloading(update, 0), UpdateState.Downloading(update, 1_000)), seen)
        assertEquals(UpdateState.Installing(update), harness.updates.state.value)
        assertEquals(emptyList<File>(), harness.launched, "Nothing runs until the notes are closed")
        harness.closes.single().first()
        assertEquals(listOf(File(harness.downloads, "ViveNotes-0.2.0-x64.msi")), harness.launched)
        harness.scope.cancel()
    }

    @Test
    fun aSecondClickBeforeTheDownloadStartsDoesNotStartAnother() = runBlocking {
        var downloads = 0
        val harness = Harness(this, downloader = { release, directory, _ ->
            downloads++
            File(directory, release.installer.name).apply { parentFile.mkdirs(); writeText("msi") }
        })
        harness.updates.check()

        harness.updates.install()
        harness.updates.install()
        harness.awaitClose()
        repeat(10) { yield() }

        assertEquals(1, downloads)
        assertEquals(1, harness.closes.size)
        harness.scope.cancel()
    }

    @Test
    fun cancellingStopsTheDownloadAndOffersTheUpdateAgain() = runBlocking {
        val reading = CompletableDeferred<(Long) -> Unit>()
        val stopped = CompletableDeferred<Unit>()
        val harness = Harness(this, downloader = { _, _, onBytes ->
            try {
                reading.complete(onBytes)
                CompletableDeferred<Unit>().await()
                error("not reached")
            } finally {
                stopped.complete(Unit)
            }
        })
        harness.updates.check()

        harness.updates.install()
        val onBytes = reading.await()
        harness.updates.cancel()
        stopped.await()
        // A read already under way may still report after the cancel.
        onBytes(500)

        assertEquals(UpdateState.Available(update), harness.updates.state.value)
        assertTrue(harness.closes.isEmpty())
        harness.scope.cancel()
    }

    @Test
    fun aFailedDownloadSaysWhyAndCanBeRetried() = runBlocking {
        var attempts = 0
        val harness = Harness(this, downloader = { release, directory, _ ->
            attempts++
            if (attempts == 1) throw IOException("${release.installer.name} failed verification")
            File(directory, release.installer.name).apply { parentFile.mkdirs(); writeText("msi") }
        })
        harness.updates.check()

        harness.updates.install()
        harness.awaitDownloadFinished()
        assertEquals(UpdateState.Failed(update, "ViveNotes-0.2.0-x64.msi failed verification"), harness.updates.state.value)

        harness.updates.install()
        harness.awaitClose()
        assertEquals(UpdateState.Installing(update), harness.updates.state.value)
        assertEquals(2, attempts)
        harness.scope.cancel()
    }

    @Test
    fun anUnreachableServerIsExplainedInWords() = runBlocking {
        val harness = Harness(this, downloader = { _, _, _ -> throw UnknownHostException("github.com") })
        harness.updates.check()

        harness.updates.install()
        harness.awaitDownloadFinished()

        assertEquals(UpdateState.Failed(update, "The download server could not be reached. Check the connection and try again."),
            harness.updates.state.value)
        harness.scope.cancel()
    }

    @Test
    fun unsavedChangesKeepTheAppOpenAndRetryReusesTheVerifiedDownload() = runBlocking {
        var downloads = 0
        val harness = Harness(this, downloader = { release, directory, _ ->
            downloads++
            File(directory, release.installer.name).apply { parentFile.mkdirs(); writeText("msi") }
        })
        harness.updates.check()
        harness.updates.install()
        harness.awaitClose()

        harness.closes.single().second()
        assertEquals(UpdateState.Failed(update,
            "Some changes could not be saved, so Vive Notes stayed open. Resolve the error on the page, then try again."),
            harness.updates.state.value)

        harness.updates.install()
        harness.awaitClose(count = 2)
        assertEquals(1, downloads)
        assertEquals(UpdateState.Installing(update), harness.updates.state.value)
        harness.scope.cancel()
    }

    @Test
    fun withoutWindowsInstallerNothingIsDownloaded() = runBlocking {
        var downloaded = false
        val harness = Harness(this, installerAvailable = false, downloader = { _, _, _ -> downloaded = true; error("unused") })
        harness.updates.check()

        harness.updates.install()

        assertEquals(UpdateState.Failed(update, "Windows Installer could not be started on this computer."), harness.updates.state.value)
        assertTrue(!downloaded)
        harness.scope.cancel()
    }

    @Test
    fun anInstallerThatWillNotStartDoesNotStopTheAppClosing() = runBlocking {
        val harness = Harness(this, launch = { throw IOException("powershell.exe was blocked") })
        harness.updates.check()
        harness.updates.install()
        harness.awaitClose()

        harness.closes.single().first()

        assertTrue(log.any { it.contains("start the installer failed: IOException: powershell.exe was blocked") }, log.toString())
        harness.scope.cancel()
    }

    @Test
    fun onlyTheInstalledWindowsAppInTheProductionProfileUpdatesItself() {
        val app = InstalledApp(File("C:/Users/Ada/AppData/Local/Vive Notes/Vive Notes.exe"), listOf(1L))
        fun updater(os: String = "Windows 11", profile: DesktopProfile = DesktopProfile.PRODUCTION,
            installed: InstalledApp? = app, version: String = "0.1.0") =
            DesktopUpdates.forInstalledApp(profile, DebugLog(), closeApp = { _, _ -> }, osName = os,
                environment = { null }, installedApp = { installed }, version = version)

        assertNotNull(updater())
        assertNull(updater(os = "Linux"))
        assertNull(updater(os = "Mac OS X"))
        assertNull(updater(profile = DesktopProfile.DEVELOPMENT))
        assertNull(updater(installed = null), "Gradle runs are not an installation to replace")
        assertNull(updater(version = "development"))
    }

    @Test
    fun theRunningLauncherIsFoundFromJpackagesProperty() {
        val launcher = temp.newFile("Vive Notes.exe")

        val app = assertNotNull(InstalledApp.current(launcher.path))

        assertEquals(launcher.absoluteFile.parentFile, app.directory)
        assertEquals(ProcessHandle.current().pid(), app.processIds.first())
        assertNull(InstalledApp.current(null))
        assertNull(InstalledApp.current(""))
        assertNull(InstalledApp.current(File(temp.root, "missing.exe").path))
    }

    private fun release(version: String, notes: String = "") = ReleaseVersion.parse(version)!!.let { v ->
        Release(v, notes,
            ReleaseAsset(installerName(v), URI("https://github.com/x/releases/download/v$v/${installerName(v)}"), 229_876_426),
            ReleaseAsset("${installerName(v)}.sha256", URI("https://github.com/x/releases/download/v$v/${installerName(v)}.sha256"), 92))
    }

    private inner class Harness(
        parent: CoroutineScope,
        feed: ReleaseFeed = ReleaseFeed { newer },
        downloader: UpdateDownloader = UpdateDownloader { release, directory, _ ->
            File(directory, release.installer.name).apply { parentFile.mkdirs(); writeText("msi") }
        },
        installerAvailable: Boolean = true,
        launch: (File) -> Unit = {},
    ) {
        val scope = CoroutineScope(parent.coroutineContext + SupervisorJob())
        val downloads = File(temp.root, "cache/updates")
        val launched = mutableListOf<File>()
        val closes = mutableListOf<Pair<() -> Unit, () -> Unit>>()
        val updates = DesktopUpdates(
            currentVersion = ReleaseVersion.parse("0.1.0")!!,
            feed = feed,
            downloader = downloader,
            installer = object : UpdateInstaller {
                override fun available() = installerAvailable
                override fun launch(msi: File) {
                    launch(msi)
                    launched += msi
                }
            },
            preferences = UpdatePreferencesFile(File(temp.root, "config/updates.properties")),
            downloads = downloads,
            closeApp = { onClosed, onKeptOpen -> closes += onClosed to onKeptOpen },
            scope = scope,
            io = EmptyCoroutineContext,
            checkDelay = Duration.ZERO,
            log = DebugLog(enabled = true) { log += it },
        )

        suspend fun awaitClose(count: Int = 1) {
            repeat(1_000) { if (closes.size >= count) return; yield() }
            error("The app was never asked to close: ${updates.state.value}")
        }

        suspend fun awaitDownloadFinished() {
            repeat(1_000) {
                if (updates.state.value !is UpdateState.Downloading) return
                yield()
            }
            error("Still downloading")
        }
    }
}
