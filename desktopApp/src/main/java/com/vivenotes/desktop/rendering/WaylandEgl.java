package com.vivenotes.desktop.rendering;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** JNI only owns child surfaces and EGL resources; JBR owns the display and parent surface. */
final class WaylandEgl {
    static {
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        arch = switch (arch) { case "amd64", "x86_64" -> "x86_64"; case "aarch64", "arm64" -> "aarch64"; default -> arch; };
        String resource = "/native/linux-" + arch + "/libvive_wayland_egl.so";
        try (InputStream input = WaylandEgl.class.getResourceAsStream(resource)) {
            if (input == null) throw new UnsatisfiedLinkError("Missing Wayland EGL resource " + resource);
            Path library = Files.createTempFile("vive-wayland-egl-", ".so");
            try {
                Files.copy(input, library, StandardCopyOption.REPLACE_EXISTING);
                System.load(library.toAbsolutePath().toString());
            } finally {
                Files.deleteIfExists(library);
            }
        } catch (IOException error) {
            throw new UnsatisfiedLinkError("Cannot load Wayland EGL renderer: " + error.getMessage());
        }
    }

    static native long create(long display, long parent, int width, int height);
    static native void configure(long handle, int width, int height, int logicalWidth, int logicalHeight, int x, int y);
    static native void makeCurrent(long handle);
    static native long saveCurrent();
    static native void restoreCurrent(long binding);
    static native void present(long handle);
    static native void destroy(long handle);
    static native long surface(long handle);
    static native void placeAbove(long handle, long sibling);
    static native long getProcAddress();
    static native String renderer(long handle);
    static native int connectionCount();
    private WaylandEgl() {}
}
