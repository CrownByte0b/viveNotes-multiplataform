import javax.inject.Inject
import org.gradle.process.ExecOperations

abstract class ViveConverterTask : DefaultTask() {
    @get:Internal abstract val checkout: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val prebuilt: RegularFileProperty
    @get:Input abstract val platform: Property<String>
    @get:Input abstract val architecture: Property<String>
    @get:Input abstract val offline: Property<Boolean>
    @get:LocalState abstract val cargoTarget: DirectoryProperty
    @get:OutputDirectory abstract val resources: DirectoryProperty
    @get:Inject abstract val execOperations: ExecOperations

    @TaskAction fun prepare() {
        val source = checkout.get().asFile
        check(source.resolve("Cargo.toml").isFile) {
            "OneNote import needs vive-converter and onenote.rs beside it. " +
                "Set -PviveConverterDir=/path/to/vive-converter (looked in $source)."
        }
        val libraryName = if (platform.get() == "windows") "vive_converter.dll" else "libvive_converter.so"
        val library = prebuilt.orNull?.asFile ?: run {
            val cargo = File(System.getProperty("user.home"), ".cargo/bin/" +
                if (platform.get() == "windows") "cargo.exe" else "cargo")
            execOperations.exec {
                workingDir = source
                commandLine(listOf(if (cargo.isFile) cargo.absolutePath else "cargo", "build", "--locked",
                    "--profile", "native", "--lib", "--target-dir", cargoTarget.get().asFile.absolutePath) +
                    if (offline.get()) listOf("--offline") else emptyList())
            }
            cargoTarget.get().asFile.resolve("native/$libraryName")
        }
        check(library.isFile && library.length() > 0) { "The OneNote converter library is missing: $library" }
        val output = resources.get().asFile.apply { deleteRecursively(); mkdirs() }
        val native = output.resolve("onenote/native/${platform.get()}-${architecture.get()}").apply { mkdirs() }
        library.copyTo(native.resolve(libraryName))
        val licenses = output.resolve("onenote/licenses").apply { mkdirs() }
        source.resolve("LICENSE").copyTo(licenses.resolve("LICENSE-vive-converter.txt"))
        source.parentFile.resolve("onenote.rs/LICENSE").copyTo(licenses.resolve("LICENSE-onenote-parser.txt"))
    }
}

val converterDirectory = providers.gradleProperty("viveConverterDir").orElse("../converter/vive-converter")
val buildViveConverter = tasks.register<ViveConverterTask>("buildViveConverter") {
    group = "build"
    description = "Bundle the OneNote JNI converter for the desktop JVM"
    val source = rootProject.file(converterDirectory.get())
    checkout.set(source)
    sources.from(fileTree(source) { include("src/**", "Cargo.toml", "Cargo.lock", "LICENSE") },
        fileTree(File(source.parentFile, "onenote.rs")) {
            include("Cargo.toml", "LICENSE", "crates/**")
            exclude("**/target/**", "**/tests/**", "**/benches/**")
        })
    providers.gradleProperty("viveConverterLibrary").orNull?.let { prebuilt.set(rootProject.file(it)) }
    platform.set(when {
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows"
        System.getProperty("os.name").startsWith("Linux", ignoreCase = true) -> "linux"
        else -> throw GradleException("OneNote import supports Linux and Windows desktops")
    })
    architecture.set(when (val arch = System.getProperty("os.arch")) {
        "amd64", "x86_64" -> "x86_64"
        "arm64", "aarch64" -> "aarch64"
        else -> throw GradleException("Unsupported OneNote converter architecture: $arch")
    })
    cargoTarget.set(layout.buildDirectory.dir("onenote/target"))
    offline.set(gradle.startParameter.isOffline)
    resources.set(layout.buildDirectory.dir("generated/onenoteResources"))
}

tasks.named<ProcessResources>("jvmProcessResources") {
    from(buildViveConverter.flatMap { it.resources })
}
