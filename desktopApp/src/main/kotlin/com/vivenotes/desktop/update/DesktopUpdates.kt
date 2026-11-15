package com.vivenotes.desktop.update

import com.vivenotes.desktop.DesktopBuildInfo
import com.vivenotes.desktop.DesktopProfile
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.localization.Strings
import com.vivenotes.ui.update.AppUpdates
import com.vivenotes.ui.update.AvailableUpdate
import com.vivenotes.ui.update.UpdateCheck
import com.vivenotes.ui.update.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Closes the app so an installer can replace it: [onClosed] runs once the notes are written and the
 * database is closed, just before the process exits; [onKeptOpen] when they could not be saved.
 */
internal typealias CloseForUpdate = (onClosed: () -> Unit, onKeptOpen: () -> Unit) -> Unit

/**
 * The updater of an installation that replaces itself: shortly after launch it asks [feed] for the
 * newest release and, when that is newer than [currentVersion] and not skipped, offers it; About can
 * ask again at any time. Installing downloads and verifies the new installer (only the parts the
 * [installer]'s seed lacks, where the release allows), closes the app through [closeApp] and leaves
 * the rest to [installer].
 *
 * [scope] is the UI thread's, like the notes'; network and disk work run on [io].
 */
internal class DesktopUpdates(
    private val currentVersion: ReleaseVersion,
    private val feed: ReleaseFeed,
    private val downloader: UpdateDownloader,
    private val installer: UpdateInstaller,
    private val preferences: UpdatePreferencesFile,
    private val closeApp: CloseForUpdate,
    private val scope: CoroutineScope,
    private val io: CoroutineContext = Dispatchers.IO,
    private val checkDelay: Duration = CHECK_DELAY,
    private val log: DebugLog = DebugLog(),
) : AppUpdates {
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    private val mutableCheck = MutableStateFlow<UpdateCheck>(UpdateCheck.NotChecked)
    override val check: StateFlow<UpdateCheck> = mutableCheck.asStateFlow()

    private var release: Release? = null
    private var verified: File? = null
    private var download: Job? = null
    private var checking: Job? = null

    /**
     * Checks once, [checkDelay] after launch, so the request stays out of the way of opening the
     * notes, unless About asked first. This first check also clears what earlier updates left.
     */
    fun start(): Job = scope.launch {
        delay(checkDelay)
        if (mutableCheck.value == UpdateCheck.NotChecked && checking?.isActive != true) check(manual = false)
    }

    override fun checkNow() {
        if (checking?.isActive == true) return
        // A newer release found now must not replace the one being downloaded or installed.
        if (mutableState.value is UpdateState.Downloading || mutableState.value is UpdateState.Installing) return
        checking = scope.launch { check(manual = true) }
    }

    internal suspend fun check(manual: Boolean = false) {
        mutableCheck.value = UpdateCheck.Checking
        val latest = try {
            withContext(io) {
                if (!manual) installer.clearLeftovers()
                feed.latest()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Offline or rate-limited: the launch check says nothing, About shows why.
            log.failure("update", "check for updates", error)
            mutableCheck.value = UpdateCheck.Failed(reason(error))
            return
        }
        if (latest == null || latest.version <= currentVersion) {
            log.event("update") { "up to date: $currentVersion, latest ${latest?.version ?: "none"}" }
            mutableCheck.value = UpdateCheck.UpToDate
            return
        }
        mutableCheck.value = UpdateCheck.Found(latest.version.toString())
        if (!manual && latest.version == preferences.skippedVersion()) {
            log.event("update") { "${latest.version} skipped by the user" }
            return
        }
        if (mutableState.value is UpdateState.Downloading || mutableState.value is UpdateState.Installing) return
        log.event("update") { "${latest.version} available, running $currentVersion" }
        if (release?.version != latest.version) verified = null
        release = latest
        mutableState.value = UpdateState.Available(latest.offer())
    }

    override fun install() {
        val release = release ?: return
        val offer = when (val current = mutableState.value) {
            is UpdateState.Available -> current.update
            is UpdateState.Failed -> current.update
            else -> return
        }
        installer.problem()?.let { problem ->
            mutableState.value = UpdateState.Failed(offer, problem)
            return
        }
        // At once, not in the coroutine, so a second click finds the update already under way.
        val ready = verified?.takeIf(File::isFile)
        mutableState.value = if (ready != null) UpdateState.Installing(offer) else UpdateState.Downloading(offer, 0)
        download = scope.launch {
            val file = ready ?: try {
                withContext(io) {
                    downloader.download(release, installer.staged(release), installer.seed()) { progress ->
                        // Only while still downloading: a cancelled read may report once more.
                        mutableState.update {
                            if (it is UpdateState.Downloading) {
                                it.copy(bytes = progress.bytes, total = progress.total, reused = progress.reused)
                            } else it
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log.failure("update", "download ${release.installer.name}", error)
                mutableState.value = UpdateState.Failed(offer, reason(error))
                return@launch
            }
            verified = file
            log.event("update") { "${release.version} verified; closing for the installer" }
            mutableState.value = UpdateState.Installing(offer)
            closeApp(
                {
                    try {
                        installer.launch(file, release)
                        log.event("update") { "installer started for ${release.version}" }
                    } catch (error: Exception) {
                        // The notes are already closed; the next launch offers the update again.
                        log.failure("update", "start the installer", error)
                    }
                },
                {
                    log.event("update") { "kept open: unsaved changes" }
                    mutableState.value = UpdateState.Failed(offer, Strings.update_unsaved_changes)
                },
            )
        }
    }

    override fun cancel() {
        val current = mutableState.value as? UpdateState.Downloading ?: return
        download?.cancel()
        download = null
        mutableState.value = UpdateState.Available(current.update)
        log.event("update") { "download cancelled" }
    }

    override fun later() {
        if (mutableState.value is UpdateState.Available || mutableState.value is UpdateState.Failed) {
            mutableState.value = UpdateState.Idle
        }
    }

    override fun skip() {
        val release = release ?: return
        if (mutableState.value !is UpdateState.Available) return
        try {
            preferences.skip(release.version)
        } catch (error: Exception) {
            log.failure("update", "remember the skipped version", error)
        }
        mutableState.value = UpdateState.Idle
        log.event("update") { "${release.version} skipped" }
    }

    private fun Release.offer() = AvailableUpdate(version.toString(), currentVersion.toString(), notes, installer.bytes)

    private fun reason(error: Exception): String = when (error) {
        is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketTimeoutException ->
            Strings.update_unreachable
        else -> error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName.orEmpty()
    }

    companion object {
        val CHECK_DELAY: Duration = 3.seconds

        /** Points the check at another release feed: a local server when testing an update end to end. */
        const val FEED_OVERRIDE = "VIVENOTES_UPDATE_FEED"

        /**
         * The updater for this process, or null where it has nothing to do: the development profile,
         * a version that is not a release's, and any run but the installed Windows app (started from
         * its launcher) or an AppImage. The Flatpak updates through Flatpak.
         */
        fun forThisInstallation(
            profile: DesktopProfile,
            log: DebugLog,
            closeApp: CloseForUpdate,
            /** The launch's options, which a reopened AppImage gets again. */
            arguments: List<String> = emptyList(),
            osName: String = System.getProperty("os.name"),
            environment: (String) -> String? = System::getenv,
            installedApp: () -> InstalledApp? = InstalledApp::current,
            appImage: () -> RunningAppImage? = { RunningAppImage.current(environment) },
            version: String = DesktopBuildInfo.version,
        ): DesktopUpdates? {
            if (profile != DesktopProfile.PRODUCTION) return null
            val directories = profile.directories()
            val updates = File(directories.cache, "updates")
            val (target, installer, location) = when {
                osName.startsWith("Windows", ignoreCase = true) -> {
                    val app = installedApp() ?: return null
                    Triple(UpdatePackage.WINDOWS_MSI, WindowsMsiInstaller(app, updates), app.directory)
                }
                osName.startsWith("Linux", ignoreCase = true) -> {
                    val image = appImage() ?: return null
                    Triple(UpdatePackage.APPIMAGE, AppImageInstaller(image, arguments, updates), image.image)
                }
                else -> return null
            }
            val current = ReleaseVersion.parse(version) ?: return null
            val feedUrl = environment(FEED_OVERRIDE)?.takeIf(String::isNotBlank)
                ?.let { runCatching { URI(it) }.getOrNull() }?.takeIf(::isTrustedDownload)
                ?: GitHubReleaseFeed.LATEST_RELEASE
            val userAgent = "ViveNotes/$current (${target.label})"
            log.event("update") { "updates on for $current (${target.label}) at $location" }
            return DesktopUpdates(current, GitHubReleaseFeed(feedUrl, userAgent, target),
                VerifiedUpdateDownloader(userAgent, log = log), installer,
                UpdatePreferencesFile(File(directories.config, "updates.properties")), closeApp, MainScope(), log = log)
        }
    }
}
