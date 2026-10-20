import java.io.File
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(libs.versions.jvmTarget.get())
        freeCompilerArgs.add("-Xjdk-release=${libs.versions.jvmTarget.get()}")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = libs.versions.jvmTarget.get().toInt()
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.compose.uiToolingPreview)

    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
}

// Skiko loads a native library; JDK 24+ warns about that, and a future release will block it.
val nativeAccessJvmArg = "--enable-native-access=ALL-UNNAMED"
// X11 has no property for the window class, so WindowClass.kt sets XToolkit's field; touch also
// reaches XToolkit and the AWT peer accessor (desktop/touch). The X11 package exists only in Linux
// runtimes; elsewhere the JVM would warn about opening it.
val launchJvmArgs = listOf(nativeAccessJvmArg) +
    if (System.getProperty("os.name").startsWith("Linux", ignoreCase = true))
        listOf("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED", "--add-exports=java.desktop/sun.awt=ALL-UNNAMED")
    else emptyList()
// Native Wayland touch reads JetBrains Runtime's display and surfaces. Only JBR has the package, and
// other runtimes warn about opening one they lack, so it is added only where JBR runs the app.
val jbrJvmArgs = listOf("--add-opens=java.desktop/sun.awt.wl=ALL-UNNAMED")

val appVersion = project.version.toString()

/**
 * Deployment values that are not the same on every machine, as the Android app's build reads them:
 * a `-P` on the command line, then `local.properties` at the repository root (gitignored here), then
 * the default. `./gradlew :desktopApp:run -Pvive.cloudBaseUrl=http://192.168.1.20:5444`.
 */
val localProperties: Properties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun localSetting(name: String, fallback: String): String =
    (project.findProperty(name) as String?)?.takeIf(String::isNotBlank)
        ?: localProperties.getProperty(name)?.takeIf(String::isNotBlank)
        ?: fallback

/**
 * Android's `vive.cloudBaseUrl`: replaces the managed sync server both profiles talk to, as it does
 * either Android build type. Unset, `DesktopBuildInfo` gives the development profile — Android's
 * debug build — the local Docker server and everything else `https://sync.vivenotes.net`.
 */
val cloudBaseUrlOverride = localSetting("vive.cloudBaseUrl", "")

/**
 * A Google OAuth client of the Desktop app type, in the project that holds the Android app's Web
 * client, and the not-confidential secret Google issues installed apps with it. Empty until filled
 * in, which leaves Sign in with Google disabled and explained, as an Android build without
 * `vive.googleWebClientId` does. The server must list the same id in `GOOGLE_CLIENT_IDS`.
 */
val googleDesktopClientId = localSetting("vive.googleDesktopClientId", "")
val googleDesktopClientSecret = localSetting("vive.googleDesktopClientSecret", "")

compose.desktop {
    application {
        mainClass = "com.vivenotes.desktop.MainKt"
        jvmArgs += launchJvmArgs
        nativeDistributions {
            packageVersion = appVersion
        }
        // Release jars are the compiled jars, unshrunk. Compose's default ProGuard (7.8) cannot read
        // the Gradle JDK's Java 27 classes, and the JVM rejects what 7.10 writes: its optimizer
        // breaks okio (Guardsquare/proguard#533), its preverifier writes wrong stack map frames for
        // PageCanvas (a Kotlin local reusing the upper slot of a dead long), and shrinking without
        // preverification drops every method's frames.
        buildTypes.release.proguard {
            isEnabled = false
        }
    }
}

tasks.processResources {
    val values = mapOf(
        "appVersion" to appVersion,
        "cloudBaseUrl" to cloudBaseUrlOverride,
        "googleDesktopClientId" to googleDesktopClientId,
        "googleDesktopClientSecret" to googleDesktopClientSecret,
    )
    inputs.properties(values)
    expand(values)
}

tasks.withType<Test>().configureEach {
    jvmArgs(launchJvmArgs)
}

tasks.test {
    val settingsFile = rootProject.file("settings.gradle.kts")
    inputs.file(settingsFile)
    inputs.files(rootProject.files("build.gradle.kts", "gradle.properties", "gradle/libs.versions.toml",
        "shared/build.gradle.kts", "webApp/build.gradle.kts", "desktopApp/build.gradle.kts",
        "gradle/karma/managed-chromium.js", "gradle/browser-test-page.gradle.kts",
        "gradle/tests/ratex-webpack.cjs", "webApp/webpack.config.d/ratex.js"))
    inputs.file(rootProject.file("tools/lint/ResourceLint.java"))
    systemProperty("vivenotes.test.projectRoot", rootProject.projectDir.absolutePath)
    systemProperty("vivenotes.test.settingsFile", settingsFile.absolutePath)
    systemProperty("vivenotes.test.gradleHome", gradle.gradleHomeDir!!.absolutePath)
    systemProperty("vivenotes.test.gradleUserHome", gradle.gradleUserHomeDir.absolutePath)
}

// Installed distributions use the production profile. Gradle's everyday run uses a stable,
// separate development profile rooted at <repository>/devdb.
tasks.register<JavaExec>("runProduction") {
    group = "application"
    description = "Run Vive Notes with the production profile"
    mainClass.set("com.vivenotes.desktop.MainKt")
    classpath = sourceSets.main.get().runtimeClasspath
    jvmArgs(launchJvmArgs)
    systemProperty("vivenotes.profile", "production")
    workingDir = rootProject.projectDir
}

fun isJetBrainsRuntime(home: File): Boolean =
    File(home, "bin/java").canExecute() &&
        File(home, "release").takeIf { it.isFile }
            ?.useLines { lines -> lines.any { it.startsWith("IMPLEMENTOR=\"JetBrains") } } == true

fun findJetBrainsRuntime(): File? {
    val override = providers.environmentVariable("VIVE_JBR_HOME").orNull?.let(::File)
    if (override != null) {
        require(isJetBrainsRuntime(override)) { "VIVE_JBR_HOME must point to a JetBrains Runtime" }
        return override
    }

    val dataHome = providers.environmentVariable("XDG_DATA_HOME").orNull
        ?.let(::File) ?: File(System.getProperty("user.home"), ".local/share")
    val toolboxApps = File(dataHome, "JetBrains/Toolbox/apps")
    val candidates = listOfNotNull(
        providers.environmentVariable("JAVA_HOME").orNull?.let(::File),
        File(System.getProperty("java.home")),
    ) + toolboxApps.listFiles().orEmpty().filter { it.isDirectory }.map { File(it, "jbr") }
    return candidates.firstOrNull(::isJetBrainsRuntime)
}

val isLinuxWaylandSession = System.getProperty("os.name").startsWith("Linux", ignoreCase = true) &&
    (providers.environmentVariable("XDG_SESSION_TYPE").orNull.equals("wayland", ignoreCase = true) ||
        !providers.environmentVariable("WAYLAND_DISPLAY").orNull.isNullOrBlank())

// Gradle fixes the Java launcher before task actions run, so the runtime is chosen while
// configuring. Compose registers `run` and `runRelease` after evaluation and sets their executable in
// that registration, which would override a configureEach action. `runRelease` runs the release jars
// with the installed app's (production) profile. JBR also hosts the `--x11` fallback,
// whose XToolkit Main.kt selects explicitly. The doFirst action captures only locals: the
// configuration cache cannot serialize this script.
afterEvaluate {
    tasks.named<JavaExec>("run") {
        systemProperty("vivenotes.profile", "dev")
        workingDir = rootProject.projectDir
    }
    listOf("run", "runProduction", "runRelease").forEach { taskName ->
        tasks.named<JavaExec>(taskName) {
            val waylandSession = isLinuxWaylandSession
            val jbrHome = if (waylandSession) findJetBrainsRuntime() else null
            if (jbrHome != null) {
                setExecutable(File(jbrHome, "bin/java").absolutePath)
                jvmArgs(jbrJvmArgs)
            }
            val missingJbr = waylandSession && jbrHome == null
            doFirst {
                check(!missingJbr || "--x11" in (this as JavaExec).args) {
                    "Native Wayland needs JetBrains Runtime. Install it via JetBrains Toolbox, " +
                        "set VIVE_JBR_HOME, or run with --args='--x11'."
                }
            }
        }
    }
}
