import java.io.File
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
// X11 has no property for the window class, so WindowClass.kt sets XToolkit's field. The package
// exists only in Linux runtimes; elsewhere the JVM would warn about opening it.
val launchJvmArgs = listOf(nativeAccessJvmArg) +
    if (System.getProperty("os.name").startsWith("Linux", ignoreCase = true))
        listOf("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED") else emptyList()

val appVersion = project.version.toString()

compose.desktop {
    application {
        mainClass = "com.vivenotes.desktop.MainKt"
        jvmArgs += launchJvmArgs
        nativeDistributions {
            packageVersion = appVersion
        }
    }
}

tasks.processResources {
    expand("appVersion" to appVersion)
}

tasks.withType<Test>().configureEach {
    jvmArgs(launchJvmArgs)
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
// configuring. Compose registers `run` after evaluation and sets its executable in that
// registration, which would override a configureEach action. JBR also hosts the `--x11` fallback,
// whose XToolkit Main.kt selects explicitly. The doFirst action captures only locals: the
// configuration cache cannot serialize this script.
afterEvaluate {
    tasks.named<JavaExec>("run") {
        systemProperty("vivenotes.profile", "dev")
        workingDir = rootProject.projectDir
    }
    listOf("run", "runProduction").forEach { taskName ->
        tasks.named<JavaExec>(taskName) {
            val waylandSession = isLinuxWaylandSession
            val jbrHome = if (waylandSession) findJetBrainsRuntime() else null
            if (jbrHome != null) setExecutable(File(jbrHome, "bin/java").absolutePath)
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
