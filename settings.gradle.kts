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
        mavenLocal { content { includeGroup("com.vivenotes.byteink") } }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
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

// Published ByteInk artifacts are the default. Source development requires an explicit opt-in.
val byteinkSource = providers.gradleProperty("byteinkCompositePath").orNull
    ?: if (providers.gradleProperty("byteinkComposite").orNull == "true") file("../byteink").path else null
byteinkSource?.let { includeBuild(it) }

include(":desktopApp")
include(":shared")
include(":webApp")
