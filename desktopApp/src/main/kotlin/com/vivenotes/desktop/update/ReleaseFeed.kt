package com.vivenotes.desktop.update

import com.vivenotes.localization.Strings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * A numeric `MAJOR.MINOR.BUILD`: the only version an MSI accepts (`packaging/windows/build.ps1`), and
 * a release's tag without its `v`.
 */
internal data class ReleaseVersion(private val parts: List<Int>) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        for (index in 0 until maxOf(parts.size, other.parts.size)) {
            val difference = parts.getOrElse(index) { 0 }.compareTo(other.parts.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }

    override fun toString(): String = parts.joinToString(".")

    companion object {
        private val pattern = Regex("""[vV]?(\d{1,5}(?:\.\d{1,5}){0,3})""")

        fun parse(text: String): ReleaseVersion? = pattern.matchEntire(text.trim())?.groupValues?.get(1)
            ?.split('.')?.map(String::toInt)?.let(::ReleaseVersion)
    }
}

internal data class ReleaseAsset(val name: String, val url: URI, val bytes: Long)

/** A published release this app can install: its Windows installer and the installer's checksum. */
internal data class Release(
    val version: ReleaseVersion,
    val notes: String,
    val installer: ReleaseAsset,
    val checksum: ReleaseAsset,
)

/** The newest published release, or null when there is none to install. Throws when it cannot tell. */
internal fun interface ReleaseFeed {
    fun latest(): Release?
}

/** What `packaging/windows/build.ps1` names the installer for [version], and what a release attaches. */
internal fun installerName(version: ReleaseVersion): String = "ViveNotes-$version-x64.msi"

/**
 * GitHub's latest release of the public repository: the newest one that is neither a draft nor a
 * pre-release. Anonymous, which GitHub allows 60 times an hour per address; one check per launch.
 */
internal class GitHubReleaseFeed(
    private val url: URI = LATEST_RELEASE,
    private val userAgent: String,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : ReleaseFeed {
    override fun latest(): Release? {
        val connection = openConnection(url.toURL()).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            return when (val response = connection.responseCode) {
                HttpURLConnection.HTTP_OK ->
                    parseGitHubRelease(connection.inputStream.use { it.readNBytes(MAX_RESPONSE_BYTES) }.decodeToString())
                // GitHub's answer while the repository has no published release.
                HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> throw IOException(Strings.server_returned_http_value(response))
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        val LATEST_RELEASE: URI = URI("https://api.github.com/repos/CrownByte0b/viveNotes-multiplataform/releases/latest")
        private const val TIMEOUT_MS = 15_000
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}

private val json = Json { ignoreUnknownKeys = true }

/**
 * The installable release in a GitHub release object, or null when it is a draft or pre-release, its
 * tag is not a version, or it lacks a finished installer or checksum over HTTPS.
 */
internal fun parseGitHubRelease(text: String): Release? {
    val release = json.parseToJsonElement(text).jsonObject
    if (release.flag("draft") || release.flag("prerelease")) return null
    val version = release.text("tag_name")?.let(ReleaseVersion::parse) ?: return null
    val assets = release["assets"]?.jsonArray?.map { it.jsonObject }.orEmpty()
    fun asset(name: String): ReleaseAsset? {
        val entry = assets.firstOrNull { it.text("name") == name } ?: return null
        // GitHub lists an upload under way as "open"; only an "uploaded" asset is complete.
        if (entry.text("state")?.let { it != "uploaded" } == true) return null
        val url = entry.text("browser_download_url")?.let { runCatching { URI(it) }.getOrNull() } ?: return null
        val bytes = entry["size"]?.jsonPrimitive?.longOrNull ?: return null
        return ReleaseAsset(name, url, bytes).takeIf { isTrustedDownload(url) && bytes in 1..MAX_INSTALLER_BYTES }
    }
    val installer = asset(installerName(version)) ?: return null
    val checksum = asset("${installer.name}.sha256") ?: return null
    return Release(version, plainReleaseNotes(release.text("body").orEmpty()), installer, checksum)
}

/** An MSI larger than this is not one of ours; the current one is about 230 MB. */
internal const val MAX_INSTALLER_BYTES = 2_000_000_000L

/**
 * HTTPS, or plain HTTP to this computer, where a test or a local mirror serves releases. An installer
 * is never fetched in the clear from anywhere else.
 */
internal fun isTrustedDownload(url: URI): Boolean = when (url.scheme?.lowercase()) {
    "https" -> !url.host.isNullOrEmpty()
    // Only names that cannot resolve elsewhere: a DNS answer could change between check and connect.
    "http" -> url.host?.lowercase()?.let { host ->
        host == "localhost" || host == "[::1]" || Regex("""127\.\d{1,3}\.\d{1,3}\.\d{1,3}""").matches(host)
    } == true
    else -> false
}

/**
 * A release's Markdown notes as the dialog's plain text: headings and emphasis markers dropped, list
 * items as bullets, links as their words. Long notes are cut; the release page has the rest.
 */
internal fun plainReleaseNotes(markdown: String): String {
    val text = markdown.replace("\r\n", "\n").lineSequence().map { line ->
        line.trimEnd()
            .replace(Regex("""^\s{0,3}#{1,6}\s+"""), "")
            .replace(Regex("""^(\s*)[*+-]\s+"""), "$1• ")
            .replace(Regex("""!?\[([^\]]*)]\([^)]*\)"""), "$1")
            .replace(Regex("""\*\*|__|`"""), "")
    }.joinToString("\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
    return if (text.length <= MAX_NOTES_CHARS) text else text.take(MAX_NOTES_CHARS).trimEnd() + "…"
}

private const val MAX_NOTES_CHARS = 2_000

private fun JsonObject.text(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

private fun JsonObject.flag(key: String): Boolean = this[key]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } == true
