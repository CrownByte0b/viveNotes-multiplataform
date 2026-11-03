package com.vivenotes.desktop.rendering;

import com.vivenotes.diagnostics.DebugLog;
import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.*;
import kotlin.jvm.functions.Function2;
import org.jetbrains.skiko.*;
import org.jetbrains.skiko.renderer.RenderApiFallbackManager;
import org.jetbrains.skiko.swing.SkiaSwingLayer;
import org.jetbrains.skiko.swing.SwingRenderer;

/** The version-checked adapter is confined here; Compose continues to own the component tree. */
public final class WaylandRendering {
    private static final String OBSERVED = WaylandRendering.class.getName();
    private static final Map<SkiaSwingLayer, WaylandRenderer> live = new IdentityHashMap<>();
    private static boolean bootstrapSoftware;
    private static long created, destroyed, frames;
    static BiConsumer<SkiaSwingLayer, org.jetbrains.skia.Surface> frameObserver;

    public record Statistics(long created, long destroyed, long frames, int active) {}
    public static Statistics statistics() { requireEdt(); return new Statistics(created, destroyed, frames, live.size()); }
    static void opened(SkiaSwingLayer layer, WaylandRenderer renderer) { live.put(layer, renderer); created++; }
    static void closed(SkiaSwingLayer layer, WaylandRenderer renderer) { live.remove(layer, renderer); destroyed++; }
    static void presented() { frames++; }
    static void rendered(SkiaSwingLayer layer, org.jetbrains.skia.Surface surface) {
        if (frameObserver != null) frameObserver.accept(layer, surface);
    }

    public static void prepare() {
        if (System.getProperty("skiko.renderApi") == null && System.getenv("SKIKO_RENDER_API") == null) {
            // ComposePanel is final and creates its Skia layer during addNotify. Initialize that
            // layer without GLX before the container listener installs the EGL factory.
            System.setProperty("skiko.renderApi", "SOFTWARE_FAST");
            bootstrapSoftware = true;
        }
    }

    public static boolean accelerationRequested(String requested) {
        return requested == null || requested.equalsIgnoreCase("OPENGL");
    }

    public static void observe(Component component, DebugLog log) {
        requireEdt();
        if (!(Toolkit.getDefaultToolkit().getClass().getName().equals("sun.awt.wl.WLToolkit"))) return;
        if (!bootstrapSoftware && !accelerationRequested(System.getProperty("skiko.renderApi", System.getenv("SKIKO_RENDER_API")))) return;
        if (component instanceof JComponent jc) {
            if (Boolean.TRUE.equals(jc.getClientProperty(OBSERVED))) return;
            jc.putClientProperty(OBSERVED, true);
        }
        if (component instanceof SkiaSwingLayer layer) bind(layer, log);
        if (component instanceof Container parent) {
            for (Component child : parent.getComponents()) observe(child, log);
            parent.addContainerListener(new ContainerAdapter() {
                @Override public void componentAdded(ContainerEvent event) { observe(event.getChild(), log); }
            });
        }
    }

    /** Fails a dependency contract test if Skiko changes this deliberately narrow internal seam. */
    public static void checkAdapterContract() throws ReflectiveOperationException {
        if (field(SkiaSwingLayer.class, "rendererManager").getType() != RenderApiFallbackManager.class ||
                field(SkiaSwingLayer.class, "renderDelegateWithClipping").getType() != SkikoRenderDelegate.class ||
                field(RenderApiFallbackManager.class, "factory").getType() != Function2.class)
            throw new NoSuchFieldException("Skiko's Swing renderer factory contract changed");
    }

    @SuppressWarnings("unchecked")
    private static void bind(SkiaSwingLayer layer, DebugLog log) {
        try {
            RenderApiFallbackManager<SwingRenderer> manager = (RenderApiFallbackManager<SwingRenderer>)field(SkiaSwingLayer.class, "rendererManager").get(layer);
            SkikoRenderDelegate delegate = (SkikoRenderDelegate)field(SkiaSwingLayer.class, "renderDelegateWithClipping").get(layer);
            Field factoryField = field(RenderApiFallbackManager.class, "factory");
            Function2<GraphicsApi, SwingRenderer, SwingRenderer> original = (Function2<GraphicsApi, SwingRenderer, SwingRenderer>)factoryField.get(manager);
            Function2<GraphicsApi, SwingRenderer, SwingRenderer> replacement = (api, previous) -> {
                if (api != GraphicsApi.OPENGL && !(bootstrapSoftware && api == GraphicsApi.SOFTWARE_FAST)) return original.invoke(api, previous);
                if (previous != null) previous.dispose();
                manager.setRenderApi(GraphicsApi.OPENGL);
                return new WaylandRenderer(layer, delegate, log);
            };
            factoryField.set(manager, replacement);
            // A component created within an already displayable Swing popup may have initialized
            // before its parent notified listeners. Reinitialize it once through the new factory.
            if (manager.getCurrent() != null) {
                manager.setRenderApi(GraphicsApi.OPENGL);
                manager.findNextWorkingRenderApi(true);
            }
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Unsupported Skiko Swing renderer contract", error);
        }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    static void restack(Window window) {
        List<WaylandRenderer> ordered = new ArrayList<>();
        collect(window, ordered);
        long below = 0;
        for (WaylandRenderer renderer : ordered) {
            long surface = renderer.surface();
            if (surface == 0) continue;
            renderer.placeAbove(below);
            below = surface;
        }
    }

    private static void collect(Container parent, List<WaylandRenderer> result) {
        // Swing index zero is on top. Place Wayland siblings from bottom to top.
        Component[] children = parent.getComponents();
        for (int i = children.length - 1; i >= 0; i--) {
            Component child = children[i];
            if (!child.isVisible()) continue;
            if (child instanceof SkiaSwingLayer layer) {
                WaylandRenderer renderer = live.get(layer);
                if (renderer != null) result.add(renderer);
            } else if (child instanceof Container container) collect(container, result);
        }
    }

    static void requireEdt() {
        if (!EventQueue.isDispatchThread()) throw new IllegalStateException("Wayland rendering requires the AWT event thread");
    }
    private WaylandRendering() {}
}
