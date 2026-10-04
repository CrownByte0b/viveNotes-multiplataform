rootProject.name = "multiplataform-vive"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        mavenLocal { content { includeGroup("com.vivenotes.byteink") } }
        // GitHub Packages rejects anonymous reads, and a repository with a null username fails
        // resolution outright instead of letting Gradle report the module as missing.
        val githubUser = providers.gradleProperty("byteinkGithubUser").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
        val githubToken = providers.gradleProperty("byteinkGithubToken").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
        if (githubUser != null && githubToken != null) {
            maven("https://maven.pkg.github.com/crownbyte0b/byteink") {
                content { includeGroup("com.vivenotes.byteink") }
                credentials {
                    username = githubUser
                    password = githubToken
                }
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// ByteInk builds from source: -PbyteinkCompositePath, else the sibling ../byteink checkout. Passing
// -PbyteinkVersion or -PbyteinkComposite=false resolves published Maven artifacts instead.
val byteinkSource = providers.gradleProperty("byteinkCompositePath").orNull
    ?: file("../byteink").takeIf {
        it.isDirectory &&
            providers.gradleProperty("byteinkVersion").orNull == null &&
            providers.gradleProperty("byteinkComposite").orNull != "false"
    }?.path
byteinkSource?.let { includeBuild(it) }

include(":desktopApp")
include(":shared")
include(":webApp")