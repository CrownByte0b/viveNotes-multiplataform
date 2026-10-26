// The Flathub build runs without network: every plugin and library resolves from the Maven
// repository that maven-sources.json lays out, named by VIVE_OFFLINE_REPOSITORY. It goes first in
// every build of the composite (this app, ByteInk and its build-logic), ahead of the repositories
// their settings declare, which an offline build never reaches.
val offlineRepository = File(System.getenv("VIVE_OFFLINE_REPOSITORY")
    ?: error("Set VIVE_OFFLINE_REPOSITORY to the offline Maven repository")).absoluteFile

beforeSettings {
    pluginManagement.repositories.maven { url = uri(offlineRepository) }
    dependencyResolutionManagement.repositories.maven { url = uri(offlineRepository) }
}
