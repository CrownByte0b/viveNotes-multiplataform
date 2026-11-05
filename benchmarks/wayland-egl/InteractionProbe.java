package com.vivenotes.desktop.rendering;

import com.vivenotes.eglprobe.Fixture;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import javax.swing.SwingUtilities;

/** Additional AWT/application contracts, never a substitute for native input tests. */
final class InteractionProbe {
    static final long POINTER_PERIOD = 16_000_000L;
    static final java.util.List<ScrollProbe.Input> pointerInputs = new ArrayList<>();
    static void await(String description, Callable<Boolean> predicate) throws Exception {
        long end = System.nanoTime() + 10_000_000_000L;
        while (!predicate.call()) {
            if (System.nanoTime() >= end) throw new IllegalStateException(description);
            ScrollProbe.pause(40);
        }
    }

    static void action(String name) throws Exception {
        if (!ScrollProbe.edt(() -> ScrollProbe.action(ScrollProbe.frame, name,
                Collections.newSetFromMap(new IdentityHashMap<>()), 0)))
            throw new IllegalStateException("Missing accessible action: " + name);
        ScrollProbe.pause(150);
    }

    static float zoom() throws Exception {
        Properties settings = new Properties();
        try (var input = Files.newInputStream(ScrollProbe.output.resolve("devdb/config/view.properties"))) { settings.load(input); }
        return Float.parseFloat(settings.getProperty("zoom", "1"));
    }

    static Component component(Point screen, Point local) throws Exception {
        return ScrollProbe.edt(() -> {
            Frame frame = ScrollProbe.frame;
            Component child = SwingUtilities.getDeepestComponentAt(frame, screen.x-frame.getX(), screen.y-frame.getY());
            if (child == null) throw new IllegalStateException("No owned canvas component");
            local.setLocation(screen);
            SwingUtilities.convertPointFromScreen(local, child);
            return child;
        });
    }

    static void zoomWheel(int amount) throws Exception {
        Rectangle bounds = ScrollProbe.target().bounds();
        Point screen = new Point(bounds.x+bounds.width/2, bounds.y+bounds.height/2), local = new Point();
        Component child = component(screen, local);
        ScrollProbe.edt(() -> {
            child.dispatchEvent(new MouseWheelEvent(child, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(),
                InputEvent.CTRL_DOWN_MASK, local.x, local.y, screen.x, screen.y, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, amount, amount));
            return null;
        });
    }

    static void gesture(double x, double fromY, double toY) throws Exception {
        Rectangle bounds = ScrollProbe.target().bounds();
        System.out.println("GESTURE bounds=" + bounds + " x=" + x + " y=" + fromY + ".." + toY);
        Point first = new Point(bounds.x+(int)(bounds.width*x), bounds.y+(int)(bounds.height*fromY));
        Point firstLocal = new Point();
        Component child = component(first, firstLocal);
        long start = System.nanoTime() + POINTER_PERIOD;
        int base = ScrollProbe.edt(() -> pointerInputs.size());
        CountDownLatch drained = new CountDownLatch(15);
        for (int i = 0; i <= 14; i++) {
            int point = i;
            Point screen = new Point(bounds.x+(int)(bounds.width*x),
                bounds.y+(int)(bounds.height*(fromY+(toY-fromY)*Math.min(i,13)/13)));
            Point local = new Point(firstLocal.x + screen.x-first.x, firstLocal.y + screen.y-first.y);
            long due = start + i * POINTER_PERIOD;
            while (System.nanoTime() < due) LockSupport.parkNanos(due - System.nanoTime());
            long posted = System.nanoTime();
            long eventMillis = System.currentTimeMillis();
            EventQueue.invokeLater(() -> {
                long dispatched = System.nanoTime();
                int id = point == 0 ? MouseEvent.MOUSE_PRESSED : point == 14 ? MouseEvent.MOUSE_RELEASED : MouseEvent.MOUSE_DRAGGED;
                int button = id == MouseEvent.MOUSE_DRAGGED ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1;
                int modifiers = id == MouseEvent.MOUSE_RELEASED ? 0 : InputEvent.BUTTON1_DOWN_MASK;
                try {
                    child.dispatchEvent(new MouseEvent(child, id, eventMillis, modifiers,
                        local.x, local.y, screen.x, screen.y, 1, false, button));
                    pointerInputs.add(new ScrollProbe.Input(base + point, due, posted, dispatched, System.nanoTime(), id));
                } catch (Throwable error) { ScrollProbe.errors.add(error.toString()); }
                finally { drained.countDown(); }
            });
        }
        if (!drained.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Pointer event queue did not drain");
        ScrollProbe.pause(40);
    }

    static void escape() throws Exception {
        ScrollProbe.edt(() -> {
            KeyboardFocusManager manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            Component owner = manager.getFocusOwner();
            if (owner == null || SwingUtilities.getWindowAncestor(owner) != ScrollProbe.frame)
                throw new IllegalStateException("No keyboard focus in the owned app");
            for (int id : new int[]{KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED})
                manager.redispatchEvent(owner, new KeyEvent(owner, id, System.currentTimeMillis(), 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED));
            return null;
        });
    }

    static String run(int repetitions) throws Exception {
        float initialZoom = zoom();
        float[] peakZoom = {initialZoom};
        ScrollProbe.phase("zoom", () -> {
            zoomWheel(-2);
            await("Ctrl-wheel did not zoom", () -> zoom() > initialZoom);
            peakZoom[0] = zoom();
            zoomWheel(2);
            await("Zoom did not return", () -> Math.abs(zoom()-initialZoom) < .0001f);
            ScrollProbe.pause(400);
        });
        int initialRows = Fixture.addedRows(ScrollProbe.output.toString());
        if (initialRows != 0) throw new IllegalStateException("Fixture already has authored ink");
        ScrollProbe.phase("pen", () -> {
            action("Pen 1");
            for (int i = 0; i < repetitions; i++) {
                gesture(.90, .38, .65);
                gesture(.95, .38, .65);
            }
            await("Pen strokes were not persisted", () -> Fixture.addedRows(ScrollProbe.output.toString()) == 2 * repetitions);
            ScrollProbe.pause(300);
        });
        String penInputs = pointerRows();
        ScrollProbe.edt(() -> { pointerInputs.clear(); return null; });
        ScrollProbe.phase("erase", () -> {
            action("Eraser");
            gesture(.90, .34, .70);
            gesture(.95, .34, .70);
            await("Object erasing did not remove the authored geometry", () -> Fixture.visibleAddedStrokes(ScrollProbe.output.toString()) == 0);
            ScrollProbe.pause(300);
        });
        Dimension before = ScrollProbe.edt(() -> ScrollProbe.frame.getSize());
        Dimension[] resized = {before};
        long contextsBefore = ScrollProbe.edt(() -> WaylandRendering.statistics().created());
        ScrollProbe.phase("resize", () -> {
            ScrollProbe.edt(() -> { ScrollProbe.frame.setSize(before.width+80, before.height-40); return null; });
            ScrollProbe.pause(600);
            resized[0] = ScrollProbe.edt(() -> ScrollProbe.frame.getSize());
            ScrollProbe.edt(() -> { ScrollProbe.frame.setSize(before); return null; });
            ScrollProbe.pause(600);
        });
        long contextsAfter = ScrollProbe.edt(() -> WaylandRendering.statistics().created());
        if (contextsAfter != contextsBefore) throw new IllegalStateException("Resize recreated the EGL context");
        int active = ScrollProbe.edt(() -> WaylandRendering.statistics().active());
        int[] popupActive = {active};
        ScrollProbe.phase("popup", () -> {
            action("Pen 1 settings");
            await("Popup did not get an accelerated surface", () -> ScrollProbe.edt(() -> WaylandRendering.statistics().active()) > active);
            popupActive[0] = ScrollProbe.edt(() -> WaylandRendering.statistics().active());
            ScrollProbe.pause(400);
            escape();
            await("Popup surface did not close", () -> ScrollProbe.edt(() -> WaylandRendering.statistics().active()) == active);
        });
        return "{\"zoomBefore\":"+initialZoom+",\"zoomPeak\":"+peakZoom[0]+",\"zoomAfter\":"+zoom()+
            ",\"pointerPeriodNanos\":" + POINTER_PERIOD + ",\"penInputs\":" + penInputs + ",\"eraseInputs\":" + pointerRows() +
            ",\"expectedPenStrokes\":" + (2 * repetitions) + ",\"persistedPenStrokes\":" + (2 * repetitions) +
            ",\"remainingAuthoredStrokes\":0,\"resizeHonored\":"+!before.equals(resized[0])+
            ",\"resizeReusedContext\":true,\"popupSurfaces\":"+(popupActive[0]-active)+",\"popupClosed\":true}";
    }

    static String pointerRows() throws Exception {
        return ScrollProbe.edt(() -> "[" + String.join(",", pointerInputs.stream().map(i ->
            "["+i.index()+","+i.scheduled()+","+i.posted()+","+i.dispatched()+","+i.finished()+","+i.direction()+"]").toList()) + "]");
    }
}
