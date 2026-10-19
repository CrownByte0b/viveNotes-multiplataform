plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}

tasks.register<Exec>("lint") {
    group = "verification"
    description = "Check shared UI resources, translation placeholders, and hardcoded UI text."
    val linter = layout.projectDirectory.file("tools/lint/ResourceLint.java")
    inputs.file(linter)
    inputs.files(fileTree("shared/src") { include("**/*.kt", "**/composeResources/**/*.xml") })
    inputs.files(fileTree("desktopApp/src/main") { include("**/*.kt") })
    inputs.files(fileTree("webApp/src/webMain") { include("**/*.kt") })
    executable = java.io.File(System.getProperty("java.home"), "bin/java").absolutePath
    args(linter.asFile.absolutePath, layout.projectDirectory.asFile.absolutePath)
}
