package com.vivenotes.desktop.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the updater reads from GitHub's latest-release answer. The fixture keeps the real API's shape
 * (taken from a live response) with this repository's tag and the assets **Attach release artifacts**
 * uploads: the installers, their checksums and their zsync control files.
 */
class ReleaseFeedTest {
    private val fixture: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResource("/update/github-release-latest.json")).readText(),
    ).jsonObject

    @Test
    fun theLatestReleaseGivesItsVersionWindowsInstallerChecksumAndNotes() {
        val release = assertNotNull(parseGitHubRelease(fixture.toString()))

        assertEquals("0.2.0", release.version.toString())
        assertEquals(ReleaseAsset("ViveNotes-x64.msi",
            URI("https://github.com/CrownByte0b/viveNotes-multiplataform/releases/download/v0.2.0/ViveNotes-x64.msi"),
            229_876_426), release.installer)
        assertEquals(ReleaseAsset("ViveNotes-x64.msi.sha256",
            URI("https://github.com/CrownByte0b/viveNotes-multiplataform/releases/download/v0.2.0/ViveNotes-x64.msi.sha256"),
            85), release.checksum)
        assertEquals("""
            What's Changed
            • Line spacing for paragraphs by @CrownByte0b in https://github.com/CrownByte0b/viveNotes-multiplataform/pull/41
            • Drag pictures onto a page by @CrownByte0b in https://github.com/CrownByte0b/viveNotes-multiplataform/pull/42

            Full Changelog: https://github.com/CrownByte0b/viveNotes-multiplataform/compare/v0.1.0...v0.2.0
        """.trimIndent(), release.notes)
    }

    /** The README's download link and this updater both rely on one installer name for every version. */
    @Test
    fun theInstallerKeepsOneNameInEveryRelease() {
        assertEquals("ViveNotes-x64.msi", INSTALLER_NAME)
        val next = assertNotNull(parseGitHubRelease(fixture.with("tag_name", JsonPrimitive("v0.3.0")).toString()))

        assertEquals("0.3.0", next.version.toString())
        assertEquals(INSTALLER_NAME, next.installer.name)
        assertEquals("$INSTALLER_NAME.sha256", next.checksum.name)
    }

    @Test
    fun draftsAndPreReleasesAreNotOffered() {
        assertNull(parseGitHubRelease(fixture.with("draft", JsonPrimitive(true)).toString()))
        assertNull(parseGitHubRelease(fixture.with("prerelease", JsonPrimitive(true)).toString()))
    }

    @Test
    fun aTagThatIsNotANumericVersionIsNotOffered() {
        assertNull(parseGitHubRelease(fixture.with("tag_name", JsonPrimitive("nightly")).toString()))
        assertNull(parseGitHubRelease(fixture.with("tag_name", JsonPrimitive("v0.2.0-beta.1")).toString()))
    }

    @Test
    fun aReleaseWithoutAFinishedInstallerAndChecksumIsNotOffered() {
        // The installer's zsync control file, still listed, does not stand in for either of them.
        assertNull(parseGitHubRelease(fixture.withAssets { it.filterNot { a -> a.name == INSTALLER_NAME } }.toString()))
        assertNull(parseGitHubRelease(fixture.withAssets { it.filterNot { a -> a.name == "$INSTALLER_NAME.sha256" } }.toString()))
        // Only the installer's own name counts, not a copy named for its version as releases up to
        // 1.0.2 attached.
        assertNull(parseGitHubRelease(fixture.withAssets { assets ->
            assets.map { if (it.name == INSTALLER_NAME) it.with("name", JsonPrimitive("ViveNotes-0.2.0-x64.msi")) else it }
        }.toString()))
        // GitHub lists an upload still under way as "open".
        assertNull(parseGitHubRelease(fixture.withAssets { assets ->
            assets.map { if (it.name.endsWith(".msi")) it.with("state", JsonPrimitive("open")) else it }
        }.toString()))
        assertNull(parseGitHubRelease(fixture.withAssets { assets ->
            assets.map { if (it.name.endsWith(".msi")) it.with("size", JsonPrimitive(0)) else it }
        }.toString()))
    }

    @Test
    fun anInstallerOfferedInTheClearIsRefused() {
        val release = fixture.withAssets { assets ->
            assets.map {
                if (it.name.endsWith(".msi")) it.with("browser_download_url", JsonPrimitive("http://example.com/ViveNotes-x64.msi")) else it
            }
        }
        assertNull(parseGitHubRelease(release.toString()))
    }

    @Test
    fun downloadsAreTrustedOnlyOverHttpsOrFromThisComputer() {
        assertTrue(isTrustedDownload(URI("https://github.com/a/b")))
        assertTrue(isTrustedDownload(URI("https://release-assets.githubusercontent.com/x?sig=1")))
        assertTrue(isTrustedDownload(URI("http://127.0.0.1:8123/feed")))
        assertTrue(isTrustedDownload(URI("http://localhost/feed")))
        assertTrue(isTrustedDownload(URI("http://[::1]:9000/feed")))
        assertFalse(isTrustedDownload(URI("http://github.com/a/b")))
        // A name that merely resolves here could resolve elsewhere by the time it is fetched.
        assertFalse(isTrustedDownload(URI("http://localtest.me/feed")))
        assertFalse(isTrustedDownload(URI("http://127.0.0.1.example.com/feed")))
        assertFalse(isTrustedDownload(URI("file:///C:/ViveNotes-x64.msi")))
        assertFalse(isTrustedDownload(URI("ftp://example.com/a.msi")))
    }

    @Test
    fun versionsCompareNumericallyPartByPart() {
        fun v(text: String) = assertNotNull(ReleaseVersion.parse(text), text)

        assertTrue(v("0.10.0") > v("0.9.9"))
        assertTrue(v("v1.0.0") > v("0.255.65535"))
        assertTrue(v("0.1.1") > v("0.1.0"))
        assertEquals(0, v("1.0").compareTo(v("1.0.0")))
        assertEquals("0.2.0", v("V0.2.0").toString())
        assertNull(ReleaseVersion.parse("0.2.0-rc1"))
        assertNull(ReleaseVersion.parse("latest"))
        assertNull(ReleaseVersion.parse(""))
    }

    @Test
    fun releaseNotesBecomePlainTextAndLongOnesAreCut() {
        assertEquals("Fixes\n• Bold gone: Bold\n  • nested code: code\nSee the docs.",
            plainReleaseNotes("### Fixes\r\n- **Bold** gone: __Bold__\r\n  * nested `code`: code\r\nSee [the docs](https://x)."))
        val long = plainReleaseNotes("a".repeat(5_000))
        assertEquals(2_001, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals("", plainReleaseNotes(""))
    }

    private val JsonObject.name: String get() = (this["name"] as JsonPrimitive).content

    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))

    private fun JsonObject.withAssets(change: (List<JsonObject>) -> List<JsonObject>) =
        with("assets", JsonArray(change(getValue("assets").jsonArray.map { it.jsonObject })))
}
