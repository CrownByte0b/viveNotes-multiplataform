package com.vivenotes.desktop.rendering;

import java.awt.*;
import java.awt.event.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import javax.accessibility.*;
import javax.swing.*;
import jdk.jfr.*;

/** AWT wheel-delivery diagnostic; deliberately separate from the native Robot input workload. */
public final class ScrollProbe {
    @Name("com.vivenotes.EglScrollPhase") @StackTrace(false)
    static class Phase extends jdk.jfr.Event { public String phase; }
    record Target(AccessibleContext context, Rectangle bounds, double maximum) {}
    record Input(int index, long scheduled, long posted, long dispatched, long finished, int direction) {}
    record Mark(long nanos, int width, int height) {}
    static final List<Input> inputs = new ArrayList<>();
    static final List<Mark> marks = new ArrayList<>();
    static final List<String> phases = new ArrayList<>();
    static final List<String> errors = new CopyOnWriteArrayList<>();
    static volatile Frame frame;
    static String gpu;
    static boolean measuring;
    static long edtThread;
    static Path output;
    static StringBuilder readiness = new StringBuilder();

    static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        EventQueue.invokeLater(task);
        return task.get(30, TimeUnit.SECONDS);
    }

    static void visit(Accessible node, Set<Accessible> seen, List<Target> targets, int depth, Rectangle parentBounds) {
        if (depth > 40 || seen.size() >= 10_000 || !seen.add(node)) return;
        AccessibleContext context = node.getAccessibleContext();
        if (context == null) return;
        AccessibleComponent component = context.getAccessibleComponent();
        Rectangle bounds = parentBounds;
        if (component != null && component.isShowing()) {
            try { bounds = new Rectangle(component.getLocationOnScreen(), component.getSize()); }
            catch (IllegalComponentStateException ignored) { }
        }
        readiness.append(" ".repeat(Math.min(depth, 20))).append(context.getAccessibleRole()).append(' ')
            .append(context.getAccessibleName()).append(' ').append(bounds).append(' ')
            .append(context.getAccessibleStateSet()).append('\n');
        if (context.getAccessibleRole() == AccessibleRole.SCROLL_BAR && bounds != null &&
                context.getAccessibleStateSet().contains(AccessibleState.VERTICAL) && context.getAccessibleValue() != null)
            targets.add(new Target(context, bounds, context.getAccessibleValue().getMaximumAccessibleValue().doubleValue()));
        for (int i = 0; i < Math.min(10_000, context.getAccessibleChildrenCount()); i++) {
            Accessible child = context.getAccessibleChild(i);
            if (child != null) visit(child, seen, targets, depth + 1, bounds);
        }
    }

    static Target target() throws Exception {
        return edt(() -> {
            List<Target> found = new ArrayList<>();
            readiness.setLength(0);
            readiness.append("frame ").append(frame.getBounds()).append('\n');
            visit(frame, Collections.newSetFromMap(new IdentityHashMap<>()), found, 0, frame.getBounds());
            return chooseTarget(found);
        });
    }

    static Target chooseTarget(List<Target> found) {
        return found.stream().filter(t -> t.bounds.width >= 200 && t.bounds.height >= 200 && t.maximum > 0)
            .max(Comparator.comparingLong(t -> (long)t.bounds.width * t.bounds.height)).orElse(null);
    }

    static void contract() {
        Target centered = new Target(null, new Rectangle(0, 97, 720, 573), 8000);
        Target side = new Target(null, new Rectangle(0, 97, 220, 573), 200);
        Target ribbon = new Target(null, new Rectangle(0, 50, 610, 46), 100);
        Target empty = new Target(null, new Rectangle(0, 0, 1000, 900), 0);
        if (chooseTarget(List.of(side, ribbon, centered, empty)) != centered || chooseTarget(List.of(ribbon, empty)) != null)
            throw new AssertionError("Centered canvas/hidden navigation targeting regression");
        if (!q("a\n\"\\b").equals("\"a\\u000a\\\"\\\\b\"")) throw new AssertionError("JSON escaping");
        JToggleButton tool = new JToggleButton("Pen 1");
        int[] clicks = {0};
        tool.addActionListener(event -> clicks[0]++);
        tool.setSelected(true);
        if (!action(tool, "Pen 1", Collections.newSetFromMap(new IdentityHashMap<>()), 0) || clicks[0] != 0)
            throw new AssertionError("Selecting an active tool must not open its settings");
        tool.setSelected(false);
        if (!action(tool, "Pen 1", Collections.newSetFromMap(new IdentityHashMap<>()), 0) || clicks[0] != 1)
            throw new AssertionError("Inactive tool must be activated");
        System.out.println("DRIVER_CONTRACT_PASS");
    }

    static boolean action(Accessible node, String name, Set<Accessible> seen, int depth) {
        if (depth > 40 || seen.size() >= 10_000 || !seen.add(node)) return false;
        AccessibleContext context = node.getAccessibleContext();
        if (context == null) return false;
        AccessibleAction action = context.getAccessibleAction();
        if (name.equals(context.getAccessibleName()) && action != null && action.getAccessibleActionCount() > 0)
            return context.getAccessibleStateSet().contains(AccessibleState.SELECTED) ||
                context.getAccessibleStateSet().contains(AccessibleState.CHECKED) || action.doAccessibleAction(0);
        for (int i = 0; i < Math.min(context.getAccessibleChildrenCount(), 10_000); i++) {
            Accessible child = context.getAccessibleChild(i);
            if (child != null && action(child, name, seen, depth + 1)) return true;
        }
        return false;
    }

    static double currentScroll() throws Exception {
        Target current = target();
        if (current == null) throw new IllegalStateException("Canvas scrollbar disappeared");
        return edt(() -> current.context.getAccessibleValue().getCurrentAccessibleValue().doubleValue());
    }
    static Set<String> renderApis(Component component) {
        Set<String> result = new TreeSet<>();
        if (component instanceof org.jetbrains.skiko.swing.SkiaSwingLayer layer) result.add(layer.getRenderApi().name());
        if (component instanceof Container parent) for (Component child : parent.getComponents()) result.addAll(renderApis(child));
        return result;
    }
    static long cpu() { return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime(); }
    static String q(String value) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '\\' || c == '"') b.append('\\').append(c);
            else if (c < 32) b.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    interface Action { void run() throws Exception; }
    static String memory() throws Exception {
        Map<String, Long> status = new HashMap<>();
        for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
            String[] parts = line.trim().split("\\s+");
            if (parts[0].equals("VmRSS:") || parts[0].equals("VmHWM:"))
                status.put(parts[0], Long.parseLong(parts[1]) * 1024);
        }
        return "{\"rssBytes\":" + status.get("VmRSS:") + ",\"highWaterRssBytes\":" + status.get("VmHWM:") +
            ",\"heapUsedBytes\":" + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() + "}";
    }
    static void phase(String name, Action action) throws Exception {
        edt(() -> null);
        String memoryBefore = memory();
        long start = System.nanoTime(), cpuBefore = cpu();
        long framesBefore = edt(() -> WaylandRendering.statistics().frames());
        Phase event = new Phase(); event.phase = name; event.begin();
        action.run();
        edt(() -> null);
        event.end(); event.commit();
        long end = System.nanoTime(), cpuAfter = cpu();
        long count = edt(() -> WaylandRendering.statistics().frames()) - framesBefore;
        phases.add("{\"name\":" + q(name) + ",\"startNanos\":" + start + ",\"endNanos\":" + end +
            ",\"cpuNanos\":" + (cpuAfter-cpuBefore) + ",\"submittedFrames\":" + count +
            ",\"memoryBefore\":" + memoryBefore + ",\"memoryAfter\":" + memory() + "}");
        System.out.println("PHASE " + name + " frames=" + count);
    }

    static void pause(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }

    static void wheel(Target target, Component child, Point local, Point screen, int direction, int count, long period) {
        CountDownLatch drained = new CountDownLatch(count);
        long start = System.nanoTime() + period;
        int base = inputs.size();
        for (int i = 0; i < count; i++) {
            final int index = base + i;
            long due = start + i * period;
            while (System.nanoTime() < due) LockSupport.parkNanos(due - System.nanoTime());
            long posted = System.nanoTime();
            long eventMillis = System.currentTimeMillis();
            EventQueue.invokeLater(() -> {
                long dispatched = System.nanoTime();
                try {
                    child.dispatchEvent(new MouseWheelEvent(child, MouseEvent.MOUSE_WHEEL, eventMillis, 0,
                        local.x, local.y, screen.x, screen.y, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, direction, direction));
                    inputs.add(new Input(index, due, posted, dispatched, System.nanoTime(), direction));
                } catch (Throwable e) { errors.add(e.toString()); }
                finally { drained.countDown(); }
            });
        }
        try { if (!drained.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Wheel queue did not drain"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        pause(800);
    }

    static void run(boolean trace, int count, long period, boolean interactions, int inkRepetitions) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        Target selected = null;
        boolean prepared = false;
        while (selected == null && System.nanoTime() < deadline) {
            edt(() -> {
                for (Frame f : Frame.getFrames()) if (f.isShowing()) {
                    if (frame != null && frame != f) throw new IllegalStateException("More than one visible app window");
                    frame = f;
                }
                return null;
            });
            if (frame != null) {
                if (!prepared) prepared = edt(() -> action(frame, "Draw", Collections.newSetFromMap(new IdentityHashMap<>()), 0));
                selected = target();
            }
            if (selected == null) pause(100);
        }
        if (selected == null) {
            Files.writeString(output.resolve("readiness.txt"), readiness.toString());
            throw new IllegalStateException("No canvas scrollbar");
        }
        final Target canvas = selected;
        edt(() -> action(frame, "Pen 1", Collections.newSetFromMap(new IdentityHashMap<>()), 0));
        pause(2500);
        Files.writeString(output.resolve("readiness.txt"), readiness + "\nSelected viewport: " + canvas.bounds + ", maximum=" + canvas.maximum + "\n");
        Point screen = new Point(canvas.bounds.x + canvas.bounds.width * 2 / 3, canvas.bounds.y + canvas.bounds.height / 2);
        Component child = edt(() -> SwingUtilities.getDeepestComponentAt(frame, screen.x-frame.getX(), screen.y-frame.getY()));
        if (child == null) throw new IllegalStateException("No wheel component");
        Point local = new Point(screen);
        edt(() -> { SwingUtilities.convertPointFromScreen(local, child); return null; });
        // Warm both directions before enabling timing or JFR.
        wheel(canvas, child, local, screen, 1, 20, period);
        wheel(canvas, child, local, screen, -1, 20, period);
        edt(() -> { inputs.clear(); marks.clear(); return null; });
        double initial = currentScroll();
        try (Recording recording = new Recording(Configuration.getConfiguration("profile"))) {
            recording.enable(Phase.class).withoutThreshold();
            if (trace) recording.enable("jdk.MethodTrace").withoutThreshold().with("filter", String.join(";",
                "com.vivenotes.desktop.rendering.WaylandRenderer::redraw",
                "com.vivenotes.desktop.rendering.WaylandRenderer::peer",
                "com.vivenotes.desktop.rendering.WaylandRendering::restack",
                "com.vivenotes.ui.canvas.SegmentedInkRasterCache::draw",
                "androidx.compose.ui.scene.ComposeSceneMediator::onRender"));
            recording.start();
            edt(() -> { measuring = true; return null; });
            phase("idle", () -> pause(1500));
            phase("scroll_down", () -> wheel(canvas, child, local, screen, 1, count, period));
            double middle = currentScroll();
            phase("scroll_up", () -> wheel(canvas, child, local, screen, -1, count, period));
            double end = currentScroll();
            String extra = interactions ? InteractionProbe.run(inkRepetitions) : "null";
            phase("idle_final", () -> pause(1000));
            edt(() -> { measuring = false; return null; });
            recording.stop(); recording.dump(output.resolve("scroll.jfr"));
            if (inputs.size() != count * 2 || middle <= initial || end >= middle) throw new IllegalStateException(
                "Scroll delivery failed: inputs=" + inputs.size() + ", start=" + initial + ", middle=" + middle + ", end=" + end);
            if (!errors.isEmpty()) throw new IllegalStateException(errors.toString());
            if (gpu == null || gpu.isBlank() || gpu.toLowerCase(Locale.ROOT).contains("llvmpipe") ||
                gpu.toLowerCase(Locale.ROOT).contains("softpipe") || gpu.toLowerCase(Locale.ROOT).contains("swiftshader"))
                throw new IllegalStateException("Missing hardware EGL renderer: " + gpu);
            Set<String> apis = edt(() -> renderApis(frame));
            if (!apis.equals(Set.of("OPENGL"))) throw new IllegalStateException("Unexpected render APIs: " + apis);
            Rectangle bounds = edt(() -> frame.getBounds());
            double awtScale = edt(() -> frame.getGraphicsConfiguration().getDefaultTransform().getScaleX());
            List<String> inputRows = new ArrayList<>(), frameRows = new ArrayList<>();
            for (Input i : inputs) inputRows.add("[" + i.index + "," + i.scheduled + "," + i.posted + "," + i.dispatched + "," + i.finished + "," + i.direction + "]");
            for (Mark m : marks) frameRows.add("[" + m.nanos + "," + m.width + "," + m.height + "]");
            String report = "{\"schema\":\"vive-egl-scroll/2\",\"toolkit\":" + q(Toolkit.getDefaultToolkit().getClass().getName()) +
                ",\"variant\":" + q(System.getProperty("vivenotes.ink.viewportRendering", "raster")) +
                ",\"gpu\":" + q(gpu) + ",\"javaRuntime\":" + q(System.getProperty("java.runtime.version")) +
                ",\"appLocation\":" + q(Class.forName("com.vivenotes.desktop.MainKt").getProtectionDomain().getCodeSource().getLocation().toString()) +
                ",\"trace\":" + trace + ",\"renderApis\":[" + String.join(",", apis.stream().map(ScrollProbe::q).toList()) + "],\"edtThreadId\":" + edtThread +
                ",\"interactions\":" + extra +
                ",\"awtScale\":" + awtScale +
                ",\"logicalSize\":[" + bounds.width + "," + bounds.height + "],\"scroll\":[" + initial + "," + middle + "," + end +
                "],\"inputPeriodNanos\":" + period + ",\"phases\":[" + String.join(",", phases) +
                "],\"inputs\":[" + String.join(",", inputRows) + "],\"drawCompletions\":[" + String.join(",", frameRows) + "]}\n";
            Files.writeString(output.resolve("raw.json"), report, StandardOpenOption.CREATE_NEW);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--contract")) { contract(); return; }
        if (args.length != 7) throw new IllegalArgumentException("output owner-token trace count period-ms scenario ink-repetitions");
        output = Path.of(args[0]).toRealPath();
        if (!Files.readString(output.resolve(".egl-probe-owner")).trim().equals(args[1]) ||
            !Path.of(System.getProperty("user.dir")).toRealPath().equals(output) ||
            !"dev".equals(System.getProperty("vivenotes.profile"))) throw new IllegalArgumentException("Not an owned dev profile");
        boolean trace = Boolean.parseBoolean(args[2]);
        int count = Integer.parseInt(args[3]);
        long period = TimeUnit.MILLISECONDS.toNanos(Long.parseLong(args[4]));
        int inkRepetitions = Integer.parseInt(args[6]);
        if (inkRepetitions < 1 || inkRepetitions > 20) throw new IllegalArgumentException("Invalid ink repetitions");
        if (count < 10 || count > 1000 || period < 1_000_000 || period > 100_000_000) throw new IllegalArgumentException("Invalid input schedule");
        System.setProperty("awt.toolkit.name", "WLToolkit");
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { error.printStackTrace(); errors.add(error.toString()); });
        edt(() -> {
            edtThread = Thread.currentThread().threadId();
            WaylandRendering.frameObserver = (layer, surface) -> {
                if (gpu == null) gpu = WaylandEgl.renderer(0);
                if (measuring) marks.add(new Mark(System.nanoTime(), surface.getWidth(), surface.getHeight()));
            };
            return null;
        });
        Thread worker = new Thread(() -> {
            int status = 0;
            try { run(trace, count, period, args[5].equals("interactions"), inkRepetitions); }
            catch (Throwable error) { error.printStackTrace(); status = 1; }
            finally {
                CountDownLatch closed = new CountDownLatch(1);
                try { edt(() -> {
                    WaylandRendering.frameObserver = null;
                    if (frame != null) {
                        frame.addWindowListener(new WindowAdapter() {
                            @Override public void windowClosed(WindowEvent event) { closed.countDown(); }
                        });
                        frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
                    }
                    return null;
                });
                    if (frame == null || !closed.await(15, TimeUnit.SECONDS)) status = 1;
                    if (edt(() -> WaylandRendering.statistics().active()) != 0) status = 1;
                    Files.writeString(output.resolve("closed.json"), "{\"normalWindowClose\":" + (status == 0) + "}\n");
                } catch (Exception error) { error.printStackTrace(); status = 1; }
                System.exit(status);
            }
        }, "egl-scroll-probe");
        worker.setDaemon(true); worker.start();
        Class.forName("com.vivenotes.desktop.MainKt").getMethod("main", String[].class).invoke(null, (Object)new String[0]);
    }
}
