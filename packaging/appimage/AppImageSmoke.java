import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.event.WindowEvent;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * A Java agent the smoke test attaches to the real AppImage launch through JAVA_TOOL_OPTIONS;
 * never included in the AppImage.
 */
public final class AppImageSmoke {
    public static void premain(String options, Instrumentation instrumentation) {
        Thread probe = new Thread(() -> {
            try {
                probe();
            } catch (Throwable error) {
                error.printStackTrace();
                Runtime.getRuntime().halt(1);
            }
        }, "appimage-smoke");
        probe.setDaemon(true);
        probe.start();
    }

    private static void probe() throws Exception {
        if (System.getenv("APPIMAGE") == null || System.getenv("APPDIR") == null)
            throw new AssertionError("Not started by the AppImage runtime");
        Path appDir = Path.of(System.getenv("APPDIR")).toRealPath();
        if (!Path.of(System.getProperty("java.home")).toRealPath().startsWith(appDir))
            throw new AssertionError("Not the bundled JBR: " + System.getProperty("java.home"));
        Path uv = Path.of(System.getProperty("vivenotes.uv"));
        if (!uv.toRealPath().startsWith(appDir)) throw new AssertionError("Not the bundled uv: " + uv);
        if (new ProcessBuilder(uv.toString(), "--version").inheritIO().start().waitFor() != 0)
            throw new AssertionError("Bundled uv does not run");
        long deadline = System.nanoTime() + 60_000_000_000L;
        // Main chooses the toolkit before AWT starts; touching AWT earlier would choose it instead.
        while (System.getProperty("awt.toolkit.name") == null) {
            if (System.nanoTime() > deadline) throw new AssertionError("The app never chose a toolkit");
            Thread.sleep(100);
        }
        while (System.nanoTime() < deadline) {
            Thread.sleep(200);
            Frame[] shown = new Frame[1];
            EventQueue.invokeAndWait(() -> shown[0] = Arrays.stream(Frame.getFrames())
                .filter(frame -> frame.isShowing() && frame.getTitle().equals("Vive Notes"))
                .findFirst().orElse(null));
            if (shown[0] == null) continue;
            // Allow Compose to render; the outer test also rejects asynchronous errors in the log.
            Thread.sleep(2500);
            String toolkit = System.getProperty("awt.toolkit.name");
            // The desktop entry's StartupWMClass matches windows to it.
            String windowClass = "WLToolkit".equals(toolkit) ? System.getProperty("awt.app.id") : x11WindowClass();
            if (!"vivenotes".equals(windowClass))
                throw new AssertionError("Window class does not match the desktop entry: " + windowClass);
            if (!Files.isRegularFile(Path.of(System.getenv("XDG_DATA_HOME"), "vivenotes/notes.db")))
                throw new AssertionError("No database in the test home");
            System.out.println("APPIMAGE_SMOKE_WINDOW_OK " + toolkit + " " + appDir);
            EventQueue.invokeLater(() -> shown[0].dispatchEvent(new WindowEvent(shown[0], WindowEvent.WINDOW_CLOSING)));
            return;
        }
        throw new AssertionError("The AppImage did not show a window");
    }

    private static String x11WindowClass() throws ReflectiveOperationException {
        var field = Class.forName("sun.awt.X11.XToolkit").getDeclaredField("awtAppClassName");
        field.setAccessible(true);
        return (String) field.get(null);
    }
}
