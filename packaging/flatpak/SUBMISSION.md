# Future Flathub submission metadata

Prepared from the [submission guide](https://docs.flathub.org/docs/for-app-authors/submission),
[requirements](https://docs.flathub.org/docs/for-app-authors/requirements) and
[MetaInfo guidelines](https://docs.flathub.org/docs/for-app-authors/metainfo-guidelines),
consulted 2026-10-20. These files are local preparation; nothing has been submitted or published.

## Prepared files

| File | Purpose | Installed location |
| --- | --- | --- |
| `net.vivenotes.ViveNotes.metainfo.xml` | Store name, summary, description, developer, license, URLs, branding, age rating, English language, device support and release notes | `/app/share/metainfo/net.vivenotes.ViveNotes.metainfo.xml` |
| `net.vivenotes.ViveNotes.desktop` | Application menu entry, category, keywords and window identity | `/app/share/applications/net.vivenotes.ViveNotes.desktop` |
| `net.vivenotes.ViveNotes.png` | Existing application artwork resized to a square 512 px PNG | `/app/share/icons/hicolor/512x512/apps/net.vivenotes.ViveNotes.png` |
| `flathub.json` | Restrict the initial build to the supported `x86_64` architecture | Beside the manifest in the future Flathub repository |
| `screenshots.xml.in` | Screenshot block to complete when the author supplies images | Not installed; insert the completed block into the MetaInfo |

The application ID, desktop filename, icon name and window ID all use
`net.vivenotes.ViveNotes`. The app's existing Source First license is retained through a
`LicenseRef-proprietary` URL; the separate metadata license is CC0-1.0. Review the OARS rating,
developer identity, links and `vivenotes.net` domain ownership before submission.

`@VERSION@` and `@DATE@` in the MetaInfo are build substitutions. The current packaging script
resolves them from the requested version and `SOURCE_DATE_EPOCH` or source commit date. For an
actual release, use its real version and release date. The future source-build manifest must
perform the same substitutions offline; do not submit an XML file with unresolved placeholders.

## Screenshots to supply later

The author will supply the screenshots. No screenshot URLs or images have been invented.

1. Capture the Linux app window with useful sample content, in English, without personal data,
   desktop wallpaper or promotional overlays. Keep the original image unedited and legible.
2. Host at least one image at a direct, publicly reachable HTTPS image URL. If using a Git
   repository, pin the URL to a tag or commit rather than a moving branch.
3. Replace `@SCREENSHOT_URL@` and `@SCREENSHOT_CAPTION@` in `screenshots.xml.in`, then insert its
   `<screenshots>` block into the MetaInfo `<component>`. Use exactly one `type="default"`
   screenshot; add further screenshot entries with captions if desired.
4. Confirm that each URL returns the actual image and matches its caption.

The screenshot template is deliberately excluded from the local package. AppStream validation
alone can pass before screenshots are present; the graphical Flathub listing still requires them.

## Validate before submission

The packaging contract tests render real release values, check identities and icon dimensions,
and run the system AppStream and desktop validators when installed:

```sh
python3 -B packaging/flatpak/test_packaging.py
```

After adding hosted screenshots, validate the resolved MetaInfo using Flathub's linter too:

```sh
flatpak run --command=flatpak-builder-lint org.flatpak.Builder \
  appstream desktopApp/build/flatpak/payload/share/metainfo/net.vivenotes.ViveNotes.metainfo.xml
```

Use the current Flathub tools again when publishing; validation rules can change. Network checks
must confirm the screenshots, homepage and other public links at that time.

## Files for the future submission PR

Keep the desktop file, icon, MetaInfo and screenshots in this upstream project, with the build
installing them. Flathub asks for a top-level manifest named `net.vivenotes.ViveNotes.json`
(or YAML), `flathub.json` beside it, and any offline dependency manifests or required patches.
Do not copy upstream application source, binaries or duplicate listing metadata into that PR.

The current JSON manifest in this directory stages a locally built binary payload. It is useful
for local packaging but is not the future Flathub source-build manifest. The public ByteInk source
pin below and the runtime/tool dependencies still need wiring into its offline build. This
metadata preparation does not change the build strategy, trigger workflows or publish a release.

## ByteInk version to pin

The author supplied the [ByteInk package listing](https://github.com/orgs/CrownByte0b/packages?repo_name=ByteInk).
Verified 2026-10-20: the published application dependencies are:

```kotlin
implementation("com.vivenotes.byteink:byteink-compose:0.1.0-alpha.1")
implementation("com.vivenotes.byteink:byteink-kit:0.1.0-alpha.1")
```

Repository: `https://maven.pkg.github.com/crownbyte0b/byteink`.
The existing desktop and Flatpak GitHub Actions workflows already use
`ORG_GRADLE_PROJECT_byteinkVersion=0.1.0-alpha.1` with package-read authentication.
The ordinary local development default remains the explicitly chosen local Maven snapshot;
`-PbyteinkVersion=0.1.0-alpha.1` selects this published version when credentials are configured.

### Public source pin for Flathub

The [ByteInk source repository](https://github.com/CrownByte0b/ByteInk) is public and Apache-2.0
licensed. Its immutable source pin is the commit used by the
[successful publication run](https://github.com/CrownByte0b/ByteInk/actions/runs/39328525474):

```json
{
    "type": "git",
    "url": "https://github.com/CrownByte0b/ByteInk.git",
    "commit": "1cf7ffbde7487fa28809cd1f8e7c12cef7bbcbe8",
    "dest": "byteink"
}
```

Add this source object to the future application module's `sources` and use the project's
existing `-PbyteinkCompositePath=byteink` opt-in to build that checkout. This is a source pin,
not a complete Flathub build module. Pin the commit rather than the moving `master` branch;
there were no tags listed when inspected on 2026-10-20. No additional public source archive
needs to be published.

GitHub Maven authentication is a separate issue: an anonymous Maven metadata request returned
HTTP 401, and [GitHub requires a token for public Gradle packages](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry).
The future Flathub build can use the public source checkout above without depending on that
registry. What remains is to prefetch the Gradle/Maven and native toolchain dependencies and
wire the Linux native-library build into the
[offline build](https://docs.flathub.org/docs/for-app-authors/requirements#no-network-access-during-build).
ByteInk's native-loader Gradle build consumes separately built native libraries, so cloning
the public source alone is not yet a verified offline end-to-end build.
