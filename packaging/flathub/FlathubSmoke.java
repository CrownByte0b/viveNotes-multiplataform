import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Runs against the Flathub build inside its sandbox; never installed in the application. Beyond what
 * packaging/flatpak/FlatpakSmoke.java checks, it checks what this build makes from source: the JBR,
 * libink.so loaded from /app rather than extracted, and the math engine's own Python.
 */
public final class FlathubSmoke {
    public static void main(String[] args) throws Exception {
        String id = System.getenv("FLATPAK_ID");
        if (!"net.vivenotes.ViveNotes".equals(id)) throw new AssertionError("Missing Flatpak identity: " + id);
        if (!System.getProperty("java.vendor.version", "").startsWith("JBR-"))
            throw new AssertionError("Not the JetBrains Runtime: " + System.getProperty("java.vendor.version"));

        // The UI probe stays offline; production first runs automatically download Small.
        Path config = Path.of(System.getenv("XDG_CONFIG_HOME"), "vivenotes");
        Files.createDirectories(config);
        Files.writeString(config.resolve("ai.properties"), "deletedFormulaModels=UniMerNetSmall\n");

        String python = System.getProperty("vivenotes.mathPython");
        Process imports = new ProcessBuilder(python, "-E", "-s", "-c",
            "import platform, sympy, mpmath, antlr4; print('FLATHUB_SMOKE_PYTHON', platform.python_version(), sympy.__version__)")
            .inheritIO().start();
        if (imports.waitFor() != 0) throw new AssertionError("The bundled Python cannot import the math engine's packages");

        Path ink = com.vivenotes.byteink.nativeloader.InkNativeLibrary.INSTANCE.load().getPath();
        if (!ink.equals(Path.of("/app/lib/libink.so"))) throw new AssertionError("libink.so loaded from " + ink);
        System.out.println("FLATHUB_SMOKE_INK " + ink);

        if (Arrays.asList(args).contains("--x11")) {
            if (System.getenv("DISPLAY") == null) throw new AssertionError("No sandbox X11 display");
        } else if (System.getenv("WAYLAND_DISPLAY") == null) {
            throw new AssertionError("No sandbox Wayland display");
        }
        Thread app = new Thread(() -> com.vivenotes.desktop.MainKt.main(args));
        app.setUncaughtExceptionHandler((thread, error) -> { error.printStackTrace(); System.exit(1); });
        app.start();
        Path data = Path.of(System.getenv("XDG_DATA_HOME"), "vivenotes");
        long deadline = System.nanoTime() + 45_000_000_000L;
        while (System.nanoTime() < deadline) {
            Thread.sleep(200);
            Frame[] shown = new Frame[1];
            EventQueue.invokeAndWait(() -> shown[0] = Arrays.stream(Frame.getFrames())
                .filter(frame -> frame.isShowing() && frame.getTitle().equals("Vive Notes"))
                .findFirst().orElse(null));
            if (shown[0] == null) continue;
            // Allow Compose to render and the math runtime's launch check to finish.
            Thread.sleep(4000);
            if ("WLToolkit".equals(System.getProperty("awt.toolkit.name"))
                    && !id.equals(System.getProperty("awt.app.id")))
                throw new AssertionError("Wayland app_id does not match desktop entry");
            if (!Files.isRegularFile(data.resolve("notes.db")))
                throw new AssertionError("No sandbox database");
            if (Files.exists(data.resolve("math_engine/python")) || Files.exists(data.resolve("math_engine/venv")))
                throw new AssertionError("The math engine was installed instead of using the bundled Python");
            System.out.println("FLATHUB_SMOKE_WINDOW_OK " + System.getProperty("awt.toolkit.name"));
            EventQueue.invokeLater(() -> shown[0].dispatchEvent(new WindowEvent(shown[0], WindowEvent.WINDOW_CLOSING)));
            return;
        }
        throw new AssertionError("Packaged UI did not show a window");
    }
}
