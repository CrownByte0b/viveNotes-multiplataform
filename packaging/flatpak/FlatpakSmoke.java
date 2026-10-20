import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Runs against the packaged jars inside the sandbox; never installed in the application. */
public final class FlatpakSmoke {
    public static void main(String[] args) throws Exception {
        String id = System.getenv("FLATPAK_ID");
        if (!"net.vivenotes.ViveNotes".equals(id)) throw new AssertionError("Missing Flatpak identity: " + id);
        Path data = Path.of(System.getenv("XDG_DATA_HOME"), "vivenotes");
        // This test checks the UI and SQLite without triggering a Python download.
        Files.createDirectories(data.resolve("math_engine"));
        Files.writeString(data.resolve("math_engine/removed"), "");
        // The UI probe stays offline; production first runs automatically download Small.
        Path config = Path.of(System.getenv("XDG_CONFIG_HOME"), "vivenotes");
        Files.createDirectories(config);
        Files.writeString(config.resolve("ai.properties"), "deletedFormulaModels=UniMerNetSmall\n");
        if (new ProcessBuilder("/app/bin/uv", "--version").inheritIO().start().waitFor() != 0)
            throw new AssertionError("Bundled uv does not run");
        if (Arrays.asList(args).contains("--x11")) {
            if (System.getenv("DISPLAY") == null) throw new AssertionError("No sandbox X11 display");
        } else if (System.getenv("WAYLAND_DISPLAY") == null) {
            throw new AssertionError("No sandbox Wayland display");
        }
        Thread app = new Thread(() -> com.vivenotes.desktop.MainKt.main(args));
        app.setUncaughtExceptionHandler((thread, error) -> { error.printStackTrace(); System.exit(1); });
        app.start();
        long deadline = System.nanoTime() + 45_000_000_000L;
        while (System.nanoTime() < deadline) {
            Thread.sleep(200);
            Frame[] shown = new Frame[1];
            EventQueue.invokeAndWait(() -> shown[0] = Arrays.stream(Frame.getFrames())
                .filter(frame -> frame.isShowing() && frame.getTitle().equals("Vive Notes"))
                .findFirst().orElse(null));
            if (shown[0] == null) continue;
            // Allow Compose to render; the outer test also rejects asynchronous errors in the log.
            Thread.sleep(2500);
            if ("WLToolkit".equals(System.getProperty("awt.toolkit.name"))
                    && !id.equals(System.getProperty("awt.app.id")))
                throw new AssertionError("Wayland app_id does not match desktop entry");
            if (!Files.isRegularFile(data.resolve("notes.db")))
                throw new AssertionError("No sandbox database");
            if (new ProcessBuilder("/usr/bin/zenity", "--version").inheritIO().start().waitFor() != 0)
                throw new AssertionError("Portal file chooser helper does not run");
            System.out.println("FLATPAK_SMOKE_WINDOW_OK " + System.getProperty("awt.toolkit.name"));
            EventQueue.invokeLater(() -> shown[0].dispatchEvent(new WindowEvent(shown[0], WindowEvent.WINDOW_CLOSING)));
            return;
        }
        throw new AssertionError("Packaged UI did not show a window");
    }
}
