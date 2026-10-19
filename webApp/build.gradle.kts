import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.ExperimentalJsTestDsl
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsExec

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    @OptIn(ExperimentalJsTestDsl::class)
    js {
        browser {
            test {
                chromium()
            }
        }
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask {
                // Kotlin 2.4's managed browser runner supports JS only; Karma reuses its Chromium.
                dependsOn("kotlinInstallPlaywrightBrowsers")
                environment("VIVE_PLAYWRIGHT_MODULE",
                    rootProject.layout.buildDirectory.dir("js/node_modules/playwright").get().asFile.absolutePath)
                providers.gradleProperty("kotlin.gradle.playwright.browsers.path").orNull?.let {
                    environment("PLAYWRIGHT_BROWSERS_PATH", it)
                }
                useKarma {
                    useChromeHeadless()
                    useConfigDirectory(rootProject.file("gradle/karma"))
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))

            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutinesCore)
        }
        jsTest.dependencies {
            implementation(npm("mocha", libs.versions.mocha.get()))
        }
    }
}

apply(from = rootProject.file("gradle/browser-test-page.gradle.kts"))

val webBundlingTest = NodeJsExec.register(
    (kotlin.targets.getByName("js") as KotlinJsIrTarget).compilations.getByName("main"),
    "webBundlingTest",
) {
    group = "verification"
    description = "Check RaTeX ESM interoperability with Kotlin/JS using the real Webpack package."
    sourceMapStackTraces = false
    inputFileProperty.set(rootProject.layout.projectDirectory.file("gradle/tests/ratex-webpack.cjs"))
    val fragment = layout.projectDirectory.file("webpack.config.d/ratex.js")
    inputs.file(fragment)
    args(rootProject.layout.buildDirectory.dir("js/node_modules").get().asFile.absolutePath,
        fragment.asFile.absolutePath)
}

tasks.named("check") {
    dependsOn(webBundlingTest)
}
