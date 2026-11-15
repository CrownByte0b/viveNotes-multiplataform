package com.vivenotes.desktop.update

import com.vivenotes.desktop.DesktopProfile
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.ui.update.AvailableUpdate
import com.vivenotes.ui.update.UpdateCheck
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
    fun aNewerReleaseIsOfferedAfterTheLaunchCheckWhichAlsoClearsLeftovers() = runBlocking {
        val harness = Harness(this)
        assertEquals(UpdateCheck.NotChecked, harness.updates.check.value)

        harness.updates.start().join()

        assertEquals(UpdateState.Available(update), harness.updates.state.value)
        assertEquals(UpdateCheck.Found("0.2.0"), harness.updates.check.value)
        assertEquals(1, harness.cleared)
        harness.scope.cancel()
    }

    @Test
    fun checkingAgainFromAboutReportsEachResultAndClearsNothing() = runBlocking {
        var latest: Release? = release("0.1.0")
        var failure: Exception? = null
        val harness = Harness(this, feed = { failure?.let { throw it }; latest })
        harness.updates.start().join()
        assertEquals(UpdateCheck.UpToDate, harness.updates.check.value)
        assertEquals(UpdateState.Idle, harness.updates.state.value)

        failure = UnknownHostException("api.github.com")
        harness.updates.checkNow()
        harness.awaitCheck()
        assertEquals(UpdateCheck.Failed("The download server could not be reached. Check the connection and try again."),
            harness.updates.check.value)

        failure = null
        latest = newer
        harness.updates.checkNow()
        harness.awaitCheck()
        assertEquals(UpdateCheck.Found("0.2.0"), harness.updates.check.value)
        assertEquals(UpdateState.Available(update), harness.updates.state.value)
        assertEquals(1, harness.cleared, "Only the launch check clears; a staged download may be waiting")
        harness.scope.cancel()
    }

    @Test
    fun checkingFromAboutOffersEvenASkippedVersionButTheLaunchCheckDoesNot() = runBlocking {
        val first = Harness(this)
        first.updates.check()
        first.updates.skip()
        first.scope.cancel()

        val relaunched = Harness(this)
        relaunched.updates.start().join()
        assertEquals(UpdateState.Idle, relaunched.updates.state.value)
        assertEquals(UpdateCheck.Found("0.2.0"), relaunched.updates.check.value, "About still says it exists")

        relaunched.updates.checkNow()
        relaunched.awaitCheck()
        assertEquals(UpdateState.Available(update), relaunched.updates.state.value)
        relaunched.scope.cancel()
    }

    @Test
    fun aCheckFromAboutBeforeTheLaunchCheckReplacesIt() = runBlocking {
        var asked = 0
        val harness = Harness(this, feed = { asked++; newer })
        harness.updates.checkNow()
        harness.awaitCheck()
        harness.updates.start().join()
        assertEquals(1, asked)
        harness.scope.cancel()
    }

    @Test
    fun aCheckDuringADownloadLeavesItAlone() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        var asked = 0
        val harness = Harness(this, feed = { asked++; newer }, downloader = { _, _, _, _ ->
            reading.complete(Unit)
            CompletableDeferred<Unit>().await()
            error("not reached")
        })
        harness.updates.check()
        harness.updates.install()
        reading.await()

        harness.updates.checkNow()
        repeat(10) { yield() }

        assertEquals(1, asked)
        assertIs<UpdateState.Downloading>(harness.updates.state.value)
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
        assertIs<UpdateCheck.Failed>(offline.updates.check.value)
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
        harness = Harness(this, downloader = { release, target, seed, onProgress ->
            assertEquals(harness.seed, seed)
            assertEquals(harness.staged, target)
            seen += harness.updates.state.value
            onProgress(DownloadProgress(0, 0))
            seen += harness.updates.state.value
            onProgress(DownloadProgress(1_000, 19_000_000, 210_876_426))
            seen += harness.updates.state.value
            target.apply { parentFile.mkdirs(); writeText("msi") }
        })
        harness.updates.check()

        harness.updates.install()
        harness.awaitClose()

        assertEquals(listOf<UpdateState>(UpdateState.Downloading(update, 0),
            UpdateState.Downloading(update, 0, total = 0), UpdateState.Downloading(update, 1_000, 19_000_000, 210_876_426)), seen)
        assertEquals(UpdateState.Installing(update), harness.updates.state.value)
        assertEquals(emptyList<File>(), harness.launched, "Nothing runs until the notes are closed")
        harness.closes.single().first()
        assertEquals(listOf(harness.staged), harness.launched)
        harness.scope.cancel()
    }

    @Test
    fun aSecondClickBeforeTheDownloadStartsDoesNotStartAnother() = runBlocking {
        var downloads = 0
        val harness = Harness(this, downloader = { _, target, _, _ ->
            downloads++
            target.apply { parentFile.mkdirs(); writeText("msi") }
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
        val harness = Harness(this, downloader = { _, _, _, onProgress ->
            try {
                reading.complete { bytes -> onProgress(DownloadProgress(bytes, 1_000)) }
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
        val harness = Harness(this, downloader = { release, target, _, _ ->
            attempts++
            if (attempts == 1) throw IOException("${release.installer.name} failed verification")
            target.apply { parentFile.mkdirs(); writeText("msi") }
        })
        harness.updates.check()

        harness.updates.install()
        harness.awaitDownloadFinished()
        assertEquals(UpdateState.Failed(update, "ViveNotes-x64.msi failed verification"), harness.updates.state.value)

        harness.updates.install()
        harness.awaitClose()
        assertEquals(UpdateState.Installing(update), harness.updates.state.value)
        assertEquals(2, attempts)
        harness.scope.cancel()
    }

    @Test
    fun anUnreachableServerIsExplainedInWords() = runBlocking {
        val harness = Harness(this, downloader = { _, _, _, _ -> throw UnknownHostException("github.com") })
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
        val harness = Harness(this, downloader = { _, target, _, _ ->
            downloads++
            target.apply { parentFile.mkdirs(); writeText("msi") }
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
    fun anInstallationThatCannotReplaceItselfDownloadsNothing() = runBlocking {
        var downloaded = false
        val harness = Harness(this, problem = "Windows Installer could not be started on this computer.",
            downloader = { _, _, _, _ -> downloaded = true; error("unused") })
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
    fun onlyTheInstalledWindowsAppAndTheAppImageInTheProductionProfileUpdateThemselves() {
        val app = InstalledApp(File("C:/Users/Ada/AppData/Local/Vive Notes/Vive Notes.exe"), listOf(1L))
        val image = RunningAppImage(File("/home/ada/Applications/ViveNotes-0.1.0-x86_64.AppImage"), 1L)
        fun updater(os: String = "Windows 11", profile: DesktopProfile = DesktopProfile.PRODUCTION,
            installed: InstalledApp? = app, appImage: RunningAppImage? = null, version: String = "0.1.0") =
            DesktopUpdates.forThisInstallation(profile, DebugLog(), closeApp = { _, _ -> }, osName = os,
                environment = { null }, installedApp = { installed }, appImage = { appImage }, version = version)

        assertNotNull(updater())
        assertNotNull(updater(os = "Linux", installed = null, appImage = image))
        assertNull(updater(os = "Linux"), "Flatpak and Gradle runs on Linux are not an AppImage")
        assertNull(updater(os = "Windows 11", installed = null, appImage = image))
        assertNull(updater(os = "Mac OS X", appImage = image))
        assertNull(updater(profile = DesktopProfile.DEVELOPMENT))
        assertNull(updater(os = "Linux", appImage = image, profile = DesktopProfile.DEVELOPMENT))
        assertNull(updater(installed = null), "Gradle runs are not an installation to replace")
        assertNull(updater(version = "development"))
        assertNull(updater(os = "Linux", appImage = image, version = "1.2.0-dev0"), "Only a release's version updates")
    }

    @Test
    fun theAppImageIsTheOneThisJvmRunsFrom() {
        val folder = temp.newFolder("Applications")
        val image = File(folder, "ViveNotes-0.1.0-x86_64.AppImage").apply { writeText("image") }
        val mount = temp.newFolder(".mount_ViveNoXYZ")
        val variables = mapOf("APPIMAGE" to image.path, "APPDIR" to mount.path)

        val running = assertNotNull(RunningAppImage.current(variables::get, javaHome = File(mount, "usr/jbr").path))
        assertEquals(image.absoluteFile, running.image)
        assertEquals(ProcessHandle.current().pid(), running.processId)
        assertNull(RunningAppImage.current(variables::get, javaHome = "/usr/lib/jvm/java-25"),
            "A Gradle run that inherited the variables from an AppImage's terminal")
        assertNull(RunningAppImage.current({ null }, javaHome = File(mount, "usr/jbr").path))
        assertNull(RunningAppImage.current(mapOf("APPIMAGE" to File(folder, "gone.AppImage").path,
            "APPDIR" to mount.path)::get, javaHome = File(mount, "usr/jbr").path))
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
            ReleaseAsset(INSTALLER_NAME, URI("https://github.com/x/releases/download/v$v/$INSTALLER_NAME"), 229_876_426),
            ReleaseAsset("$INSTALLER_NAME.sha256", URI("https://github.com/x/releases/download/v$v/$INSTALLER_NAME.sha256"), 85))
    }

    private inner class Harness(
        parent: CoroutineScope,
        feed: ReleaseFeed = ReleaseFeed { newer },
        downloader: UpdateDownloader = UpdateDownloader { _, target, _, _ ->
            target.apply { parentFile.mkdirs(); writeText("msi") }
        },
        problem: String? = null,
        launch: (File) -> Unit = {},
    ) {
        val scope = CoroutineScope(parent.coroutineContext + SupervisorJob())
        val staged = File(temp.root, "cache/updates/$INSTALLER_NAME")
        val seed = File(temp.root, "cache/updates/seed.msi")
        val launched = mutableListOf<File>()
        val closes = mutableListOf<Pair<() -> Unit, () -> Unit>>()
        var cleared = 0
        val updates = DesktopUpdates(
            currentVersion = ReleaseVersion.parse("0.1.0")!!,
            feed = feed,
            downloader = downloader,
            installer = object : UpdateInstaller {
                override fun problem() = problem
                override fun staged(release: Release) = staged
                override fun seed() = seed
                override fun clearLeftovers() { cleared++ }
                override fun launch(file: File, release: Release) {
                    launch(file)
                    launched += file
                }
            },
            preferences = UpdatePreferencesFile(File(temp.root, "config/updates.properties")),
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

        /** Lets a check that was just asked for run, then waits for its answer. */
        suspend fun awaitCheck() {
            repeat(1_000) {
                yield()
                if (updates.check.value != UpdateCheck.Checking && updates.check.value != UpdateCheck.NotChecked) return
            }
            error("Still checking")
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
