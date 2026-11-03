package com.vivenotes.desktop.rendering;

import com.vivenotes.diagnostics.DebugLog;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.lang.reflect.Field;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.jetbrains.skia.*;
import org.jetbrains.skiko.RenderException;
import org.jetbrains.skiko.SkikoRenderDelegate;
import org.jetbrains.skiko.swing.SkiaSwingLayer;
import org.jetbrains.skiko.swing.SwingRenderer;

/** One persistent GPU context/swapchain per Compose layer, including component popups. */
final class WaylandRenderer implements SwingRenderer {
    record Pixels(int width, int height) {
        static Pixels of(int width, int height, double scale) {
            if (width < 0 || height < 0 || !Double.isFinite(scale) || scale <= 0 ||
                    width * scale > Integer.MAX_VALUE || height * scale > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Invalid Wayland render dimensions");
            return new Pixels((int)Math.ceil(width * scale), (int)Math.ceil(height * scale));
        }
    }

    private final SkiaSwingLayer layer;
    private final SkikoRenderDelegate delegate;
    private final DebugLog log;
    private final HierarchyListener visibility;
    private long parent, handle;
    private DirectContext context;
    private Surface surface;
    private BackendRenderTarget target;
    private Pixels pixels;
    private boolean disposed;

    WaylandRenderer(SkiaSwingLayer layer, SkikoRenderDelegate delegate, DebugLog log) {
        this.layer = layer;
        this.delegate = delegate;
        this.log = log;
        visibility = event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && !layer.isShowing()) release();
        };
        layer.addHierarchyListener(visibility);
    }

    @Override public void redraw(Graphics2D graphics) {
        WaylandRendering.requireEdt();
        if (disposed || !layer.isShowing() || layer.getWidth() <= 0 || layer.getHeight() <= 0) return;
        long binding = 0;
        try {
            binding = WaylandEgl.saveCurrent();
            Window owner = SwingUtilities.getWindowAncestor(layer);
            Object peer = peer(owner);
            if (peer == null) { layer.repaint(); return; }
            Object nativeSurface = peer.getClass().getMethod("getSurface").invoke(peer);
            long nextParent = (Long)nativeSurface.getClass().getMethod("getWlSurfacePtr").invoke(nativeSurface);
            if (nextParent == 0) { layer.repaint(); return; }
            double scale = layer.getGraphicsConfiguration().getDefaultTransform().getScaleX();
            Pixels next = Pixels.of(layer.getWidth(), layer.getHeight(), scale);
            if (nextParent != parent) release();
            if (handle == 0) {
                parent = nextParent;
                Class<?> displayType = Class.forName("sun.awt.wl.WLDisplay");
                Object display = displayType.getMethod("getInstance").invoke(null);
                long displayPtr = (Long)displayType.getMethod("getDisplayPtr").invoke(display);
                handle = WaylandEgl.create(displayPtr, parent, next.width, next.height);
                WaylandRendering.opened(layer, this);
                try (GLAssembledInterface api = GLAssembledInterface.Companion.createFromNativePointers(0, WaylandEgl.getProcAddress())) {
                    context = DirectContext_jvmKt.makeGLWithInterface(DirectContext.Companion, api);
                }
                if (log.getEnabled()) log.event("render", () -> "native Wayland EGL: " + WaylandEgl.renderer(handle));
            }
            WaylandEgl.makeCurrent(handle);
            java.awt.Point position = SwingUtilities.convertPoint(layer, 0, 0, owner);
            int x = units(peer, "javaUnitsToSurfaceUnits", position.x);
            int y = units(peer, "javaUnitsToSurfaceUnits", position.y);
            int logicalWidth = units(peer, "javaUnitsToSurfaceSize", layer.getWidth());
            int logicalHeight = units(peer, "javaUnitsToSurfaceSize", layer.getHeight());
            WaylandEgl.configure(handle, next.width, next.height, logicalWidth, logicalHeight, x, y);
            if (!next.equals(pixels)) {
                closeSurface();
                target = BackendRenderTarget.Companion.makeGL(next.width, next.height, 0, 8, 0, 0x8058);
                surface = Surface.Companion.makeFromBackendRenderTarget(context, target, SurfaceOrigin.BOTTOM_LEFT,
                        SurfaceColorFormat.RGBA_8888, ColorSpace.Companion.getSRGB(), null);
                if (surface == null) throw new IllegalStateException("Cannot wrap the Wayland EGL framebuffer");
                pixels = next;
            }
            org.jetbrains.skia.Canvas canvas = surface.getCanvas();
            canvas.clear(0);
            int saved = canvas.save();
            try {
                Rectangle visible = layer.getVisibleRect();
                canvas.clipRect(org.jetbrains.skia.Rect.Companion.makeLTRB((float)(visible.x * scale),
                        (float)(visible.y * scale), (float)((visible.x + visible.width) * scale),
                        (float)((visible.y + visible.height) * scale)));
                delegate.onRender(canvas, next.width, next.height, System.nanoTime());
            } finally { canvas.restoreToCount(saved); }
            context.flushAndSubmit(surface, false);
            WaylandRendering.rendered(layer, surface);
            WaylandRendering.restack(owner);
            WaylandEgl.present(handle);
            WaylandRendering.presented();
        } catch (Throwable error) {
            release();
            if (error instanceof VirtualMachineError fatal) throw fatal;
            log.failure("render", "native Wayland EGL", error);
            throw new RenderException("Native Wayland EGL rendering failed", error);
        } finally {
            if (binding != 0) WaylandEgl.restoreCurrent(binding);
        }
    }

    private static int units(Object peer, String method, int value) throws ReflectiveOperationException {
        return (Integer)peer.getClass().getMethod(method, int.class).invoke(peer, value);
    }

    private static Object peer(Window owner) throws ReflectiveOperationException {
        if (owner == null) return null;
        Field peersField = Class.forName("sun.awt.wl.WLToolkit").getDeclaredField("wlSurfaceToPeerMap");
        peersField.setAccessible(true);
        Map<?, ?> peers = (Map<?, ?>)peersField.get(null);
        synchronized (peers) {
            for (Object peer : peers.values())
                if (peer.getClass().getMethod("getTarget").invoke(peer) == owner) return peer;
        }
        return null;
    }

    long surface() { return handle == 0 ? 0 : WaylandEgl.surface(handle); }
    void placeAbove(long sibling) { WaylandEgl.placeAbove(handle, sibling == 0 ? parent : sibling); }

    private void closeSurface() {
        if (surface != null) { surface.close(); surface = null; }
        if (target != null) { target.close(); target = null; }
        pixels = null;
    }

    private void release() {
        if (handle == 0) return;
        long old = handle;
        long binding = WaylandEgl.saveCurrent();
        try {
            try {
                WaylandEgl.makeCurrent(old);
            } catch (IllegalStateException lostContext) {
                // A reset/lost EGL context cannot be made current for normal Ganesh cleanup.
                if (context != null) context.abandon();
            }
            closeSurface();
            if (context != null) { context.close(); context = null; }
        } finally {
            WaylandEgl.destroy(old);
            handle = parent = 0;
            WaylandRendering.closed(layer, this);
            WaylandEgl.restoreCurrent(binding);
        }
    }

    @Override public void dispose() {
        WaylandRendering.requireEdt();
        if (disposed) return;
        disposed = true;
        layer.removeHierarchyListener(visibility);
        release();
    }
}
