import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = libs.versions.jvmTarget.get().toInt()
}

// Skiko and the bundled SQLite driver load native libraries in tests; JDK 24+ warns about that,
// and a future release will block it.
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Committed like the Android app's `app/schemas/`: every schema change needs a reviewed export and
// an explicit migration. `SchemaCompatibilityTest` holds this export to the Android baseline.
room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.fromTarget(libs.versions.jvmTarget.get())
            freeCompilerArgs.add("-Xjdk-release=${libs.versions.jvmTarget.get()}")
        }
    }

    js {
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }


    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.ratex)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.cbor)
            // Entities and DAOs are annotated in common code; only the JVM target builds a database.
            implementation(libs.androidx.room.common)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.compose.uiTest)
        }
        jvmMain.dependencies {
            val byteinkVersion = providers.gradleProperty("byteinkVersion").orElse("0.1.0-SNAPSHOT").get()
            implementation("com.vivenotes.byteink:byteink-compose:$byteinkVersion")
            implementation("com.vivenotes.byteink:byteink-kit:$byteinkVersion")
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
            runtimeOnly("io.github.darriousliu:ratex-native-linux-x86-64:0.1.14")
            runtimeOnly("io.github.darriousliu:ratex-native-linux-aarch64:0.1.14")
            runtimeOnly("io.github.darriousliu:ratex-native-windows-x86-64:0.1.14")
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
        jsMain.dependencies {
            implementation(libs.wrappers.browser)
        }
    }
}

dependencies {
    add("kspJvm", libs.androidx.room.compiler)
}

// The synthetic 40k-page interaction workload is opt-in and runs separately from normal tests.
tasks.register<Test>("inkPhase2Scenario") {
    group = "verification"
    description = "Records finished-page wet/finish/erase rendering at Fit and sparse zoom."
    val regular = tasks.named<Test>("jvmTest").get()
    testClassesDirs = regular.testClassesDirs
    classpath = regular.classpath
    dependsOn("jvmTestClasses")
    maxHeapSize = "4g"
    filter.includeTestsMatching("com.vivenotes.ui.canvas.InkLayerPerformanceScenario")
    systemProperty("vivenotes.ink.phase2Scenario", "true")
    systemProperty("vivenotes.ink.phase2Report", providers.gradleProperty("byteinkInkScenarioOutput")
        .orElse(layout.buildDirectory.file("reports/ink-phase2-scenario.json").map { it.asFile.absolutePath }).get())
    systemProperty("vivenotes.ink.phase2Optimized", providers.gradleProperty("byteinkInkScenarioRequireReuse")
        .orElse("false").get())
    outputs.upToDateWhen { false }
}
