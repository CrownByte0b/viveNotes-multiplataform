// ByteInk's C++ Ink engine for the browser — byteink-engine.mjs/.wasm and their licenses — extracted
// to build/generated/inkEngine/ink by `inkEngineAssets`. The web app serves it beside its bundle;
// Wasm browser tests reach it through Karma (gradle/karma/ink-engine.js). The ByteInk client imports
// it at run time, so webpack never bundles it.

val inkEngine = configurations.create("inkEngine") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies.add("inkEngine", "com.vivenotes.byteink:byteink-wasm:" +
    providers.gradleProperty("byteinkVersion").orElse("0.2.0-SNAPSHOT").get() + ":engine@zip")

interface InkEngineArchives {
    @get:Inject val archives: ArchiveOperations
}
val inkEngineArchives = objects.newInstance(InkEngineArchives::class.java).archives

tasks.register<Sync>("inkEngineAssets") {
    // Locals keep the configuration cache from capturing this script.
    val archives = inkEngineArchives
    from(inkEngine.elements.map { files -> files.map { archives.zipTree(it.asFile) } })
    into(layout.buildDirectory.dir("generated/inkEngine/ink"))
}
