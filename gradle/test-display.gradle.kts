import java.io.File
import java.util.concurrent.TimeUnit
import org.gradle.api.logging.Logging
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

// The JVM UI suites open real windows, move the pointer and send keys. On a Linux desktop they would
// do that on the session's own display, where the window manager places, animates and focuses their
// windows and the user's pointer and keyboard take part. So a local run gets a private Xvfb display,
// as CI's xvfb-run gives it: one per project, started by the first suite that needs it, stopped when the
// build ends. `-Pvive.tests.liveDisplay=true` keeps the session's display; CI is left as it is.
// `PrivateTestDisplayTest` checks this.

/** A private Xvfb server for this build's UI suites. */
abstract class PrivateTestDisplay : BuildService<PrivateTestDisplay.Params>, AutoCloseable {
    interface Params : BuildServiceParameters {
        /** Xvfb's own output, for when it does not start. */
        val log: RegularFileProperty
    }

    private var server: Process? = null
    private var display: String? = null

    /** The display's name, such as `:120`, or null when Xvfb is not installed or will not start. */
    @Synchronized
    fun display(): String? {
        if (server?.isAlive == true) return display
        val xvfb = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, "Xvfb") }.firstOrNull { it.canExecute() } ?: return null
        val log = parameters.log.get().asFile.apply { parentFile.mkdirs() }
        for (number in FIRST_DISPLAY..LAST_DISPLAY) {
            // A display is taken by its lock file or its socket: a compositor's Xwayland can listen on
            // a socket with no lock file, and a server sent there would replace that socket.
            if (File("/tmp/.X$number-lock").exists() || File("/tmp/.X11-unix/X$number").exists()) continue
            val process = ProcessBuilder(xvfb.path, ":$number", "-displayfd", "1",
                "-screen", "0", "1920x1200x24", "-nolisten", "tcp")
                .redirectError(ProcessBuilder.Redirect.appendTo(log))
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .start()
            // With -displayfd, Xvfb writes the display's number once it accepts clients.
            if (readyNumber(process) == number) {
                server = process
                display = ":$number"
                Logging.getLogger(PrivateTestDisplay::class.java).lifecycle(
                    "UI tests run on a private Xvfb display :$number (-Pvive.tests.liveDisplay=true uses the session's).")
                return display
            }
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
        return null
    }

    private fun readyNumber(process: Process): Int? {
        val output = process.inputStream
        val line = StringBuilder()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (output.available() > 0) {
                val next = output.read()
                if (next == '\n'.code) return line.toString().trim().toIntOrNull()
                if (next < 0) return null
                line.append(next.toChar())
            } else if (!process.isAlive) {
                return null
            } else {
                Thread.sleep(10)
            }
        }
        return null
    }

    override fun close() {
        val process = server ?: return
        server = null
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    companion object {
        // Above xvfb-run's 99 and the few a desktop uses.
        const val FIRST_DISPLAY = 120
        const val LAST_DISPLAY = 999
    }
}

/** Points a suite at the private display, or leaves it on the session's when there is none. */
class UsePrivateTestDisplay(private val service: Provider<PrivateTestDisplay>) : Action<Task> {
    override fun execute(task: Task) {
        val test = task as Test
        val display = service.get().display()
        if (display == null) {
            task.logger.warn("${task.path}: Xvfb is not installed or would not start, so UI tests use the " +
                "session's display and its pointer. Install Xvfb, or pass -Pvive.tests.liveDisplay=true.")
            return
        }
        test.setEnvironment(test.environment.filterKeys { it != "WAYLAND_DISPLAY" } +
            mapOf("DISPLAY" to display, "XDG_SESSION_TYPE" to "x11"))
    }
}

val privateTestDisplay = System.getProperty("os.name").startsWith("Linux") &&
    !providers.gradleProperty("vive.tests.liveDisplay").map(String::toBoolean).getOrElse(false) &&
    !providers.environmentVariable("CI").isPresent &&
    (providers.environmentVariable("DISPLAY").isPresent || providers.environmentVariable("WAYLAND_DISPLAY").isPresent)

if (privateTestDisplay) {
    val service = gradle.sharedServices.registerIfAbsent("privateTestDisplay${project.path}", PrivateTestDisplay::class) {
        parameters.log.set(layout.buildDirectory.file("tmp/privateTestDisplay/xvfb.log"))
    }
    tasks.withType<Test>().matching { it.name in setOf("jvmTest", "test", "waylandPopupTest") }.configureEach {
        usesService(service)
        doFirst(UsePrivateTestDisplay(service))
    }
}
