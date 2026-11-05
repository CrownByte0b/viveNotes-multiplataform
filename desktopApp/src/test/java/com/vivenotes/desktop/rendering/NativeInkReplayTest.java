package com.vivenotes.desktop.rendering;

import androidx.compose.ui.geometry.Rect;
import androidx.compose.ui.graphics.SkiaBackedCanvas;
import androidx.compose.ui.platform.ComposeUiSkikoRuntime_nonAndroidKt;
import androidx.ink.brush.InputToolType;
import androidx.ink.brush.Brush;
import androidx.ink.brush.StockBrushes;
import androidx.ink.geometry.ImmutableAffineTransform;
import androidx.ink.strokes.MutableStrokeInputBatch;
import androidx.ink.strokes.Stroke;
import com.vivenotes.byteink.compose.*;
import com.vivenotes.diagnostics.DebugLog;
import com.vivenotes.ui.canvas.*;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.jetbrains.skia.*;
import org.jetbrains.skiko.*;
import org.jetbrains.skiko.swing.SkiaSwingLayer;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class NativeInkReplayTest {
    private static final int SIZE = 128, LEFT = 8, RIGHT = 152, TOP = 8, BACKGROUND = 0xffe3dacd;
    private static final int TRANSLUCENT_STROKES = 6;
    private static final ImmutableAffineTransform IDENTITY = new ImmutableAffineTransform(1, 0, 0, 0, 1, 0);
    record View(float x, float y, float zoom, boolean erased) {}
    record Pixels(int[] reference, int[] candidate) {}

    @Test public void recordedGpuInkPreservesAlphaClippingFractionalViewsAndEraseUndo() throws Exception {
        assumeTrue(Toolkit.getDefaultToolkit().getClass().getName().equals("sun.awt.wl.WLToolkit"));
        ComposeUiSkikoRuntime_nonAndroidKt.registerSkikoComposeImplementation();
        WaylandRendering.prepare();
        List<InkSceneStroke> strokes = new ArrayList<>();
        for (int layer = 0; layer < 2; layer++) {
            MutableStrokeInputBatch inputs = new MutableStrokeInputBatch();
            inputs.add(InputToolType.MOUSE, 5.25f, 12.5f, 0);
            inputs.add(InputToolType.MOUSE, 28.5f, 53.125f, 40);
            inputs.add(InputToolType.MOUSE, 55.25f, 12.5f, 80);
            Stroke stroke = new Stroke(Brush.Companion.createWithColorIntArgb(StockBrushes.marker(),
                layer == 0 ? 0x667700ff : 0x6655cc99, 9, .1f), inputs);
            for (int index = 0; index < 3; index++) strokes.add(new InkSceneStroke(stroke,
                new ImmutableAffineTransform(1, .07f, index * 7.25f, -.11f, 1, layer * 7.5f + index * 5.5f), null));
        }
        InkScene first = new InkScene(strokes.subList(0, 3)), second = new InkScene(strokes.subList(3, 6));
        List<InkSceneLayer> full = List.of(new InkSceneLayer(first, Set.of()), new InkSceneLayer(second, Set.of()));
        List<InkSceneLayer> erased = List.of(new InkSceneLayer(first, Set.of(strokes.get(1))), new InkSceneLayer(second, Set.of()));
        List<View> views = List.of(new View(0, 0, 1, false), new View(3.25f, 2.5f, 1, false),
            new View(3.25f, 2.5f, 1, false), new View(7.75f, 3.5f, .75f, false),
            new View(7.75f, 3.5f, .75f, true), new View(7.75f, 3.5f, .75f, false),
            new View(11.25f, 8.5f, 1.25f, false));
        AtomicInteger current = new AtomicInteger();
        Map<Integer, Pixels> rendered = new ConcurrentHashMap<>();
        InkPathRenderer paths = new InkPathRenderer();
        SegmentedInkRasterCache candidate = new SegmentedInkRasterCache(4, 64L * 1024 * 1024, InkViewportRendering.MOVING);
        JFrame[] frame = {null};
        SkiaSwingLayer[] layer = {null};
        int activeBefore = edt(() -> WaylandRendering.statistics().active());
        try {
            edt(() -> {
                layer[0] = new SkiaSwingLayer((canvas, width, height, nanos) -> {
                    View view = views.get(current.get());
                    float scale = (float)frame[0].getGraphicsConfiguration().getDefaultTransform().getScaleX() * view.zoom;
                    List<InkSceneLayer> content = view.erased ? erased : full;
                    canvas.clear(BACKGROUND);
                    try (SegmentedInkRasterCache reference = new SegmentedInkRasterCache(4, 64L * 1024 * 1024, InkViewportRendering.RASTER)) {
                        record(canvas, LEFT, reference, content, paths, view, scale);
                    }
                    record(canvas, RIGHT, candidate, content, paths, view, scale);
                }, new SkiaLayerAnalytics() {}, null, new SkiaLayerProperties());
                WaylandRendering.observe(layer[0], new DebugLog());
                WaylandRendering.frameObserver = (observed, surface) -> {
                    if (observed != layer[0]) return;
                    rendered.putIfAbsent(current.get(), new Pixels(read(surface, LEFT), read(surface, RIGHT)));
                };
                frame[0] = new JFrame("Native ink replay regression");
                frame[0].add(layer[0]);
                frame[0].setSize(400, 300);
                frame[0].setVisible(true);
                return null;
            });
            for (int index = 0; index < views.size(); index++) {
                int next = index;
                edt(() -> { current.set(next); layer[0].repaint(); return null; });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (!rendered.containsKey(index) && System.nanoTime() < deadline) Thread.sleep(20);
                assertTrue("No GPU pixels for view " + index, rendered.containsKey(index));
                Pixels pixels = rendered.get(index);
                save(index, pixels);
                equivalent(pixels, "view=" + index + " " + views.get(index));
            }
            assertEquals(GraphicsApi.OPENGL, edt(() -> layer[0].getRenderApi()));
            assertTrue("Changing views must execute recorded vector replay", edt(() -> candidate.work().getDirectDraws()) >= 3);
        } finally {
            edt(() -> {
                WaylandRendering.frameObserver = null;
                if (frame[0] != null) frame[0].dispose();
                candidate.close();
                paths.clearCache();
                return null;
            });
        }
        assertEquals(activeBefore, (int)edt(() -> WaylandRendering.statistics().active()));
    }

    private static void record(org.jetbrains.skia.Canvas destination, int x, SegmentedInkRasterCache cache,
        List<InkSceneLayer> layers, InkPathRenderer paths, View view, float scale) {
        try (PictureRecorder recorder = new PictureRecorder()) {
            org.jetbrains.skia.Canvas canvas = recorder.beginRecording(0, 0, SIZE, SIZE, null);
            assertNull("Compose-style recording canvas has no exposed GPU context", canvas.getRecordingContext());
            canvas.scale(scale, scale);
            canvas.translate(-view.x, -view.y);
            SkiaBackedCanvas compose = new SkiaBackedCanvas();
            compose.setInternalSkiaCanvas(canvas);
            cache.draw(compose, layers, paths, new Rect(view.x, view.y, view.x + SIZE / scale, view.y + SIZE / scale), IDENTITY, scale);
            try (Picture picture = recorder.finishRecordingAsPicture()) {
                int saved = destination.save();
                try {
                    destination.translate(x, TOP);
                    destination.clipRect(0, 0, SIZE, SIZE, false);
                    picture.playback(destination, null);
                } finally { destination.restoreToCount(saved); }
            }
        }
    }

    private static int[] read(Surface surface, int x) {
        try (Bitmap bitmap = new Bitmap()) {
            if (!bitmap.allocN32Pixels(SIZE, SIZE, false) || !surface.readPixels(bitmap, x, TOP))
                throw new AssertionError("Could not read native GPU pixels");
            int[] pixels = new int[SIZE * SIZE];
            for (int y = 0; y < SIZE; y++) for (int column = 0; column < SIZE; column++)
                pixels[y * SIZE + column] = bitmap.getColor(column, y);
            return pixels;
        }
    }

    // CPU and GPU path rasterizers can assign different coverage to edge pixels. Uniform interiors
    // must match to one channel level; edge coverage must stay within a single physical pixel.
    // At overlapping edges, independent 8-bit blend rounding can accumulate one level per stroke.
    private static void equivalent(Pixels pixels, String description) {
        int foreground = 0;
        for (int y = 1; y < SIZE - 1; y++) for (int x = 1; x < SIZE - 1; x++) {
            int expected = pixels.reference[y * SIZE + x], actual = pixels.candidate[y * SIZE + x];
            if (expected != BACKGROUND) foreground++;
            boolean uniform = true;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int offset = (y + dy) * SIZE + x + dx;
                if (pixels.reference[offset] != expected) uniform = false;
            }
            assertTrue(description + " interior at " + x + "," + y + " expected=" + Integer.toHexString(expected) +
                " actual=" + Integer.toHexString(actual), !uniform || distance(expected, actual) <= 1);
            assertTrue(description + " edge at " + x + "," + y,
                withinCoverage(actual, pixels.reference, x, y) && withinCoverage(expected, pixels.candidate, x, y));
        }
        assertTrue("Ink comparison must contain visible ink", foreground > 300);
    }

    private static int distance(int first, int second) {
        int largest = 0;
        for (int shift = 0; shift <= 24; shift += 8)
            largest = Math.max(largest, Math.abs((first >>> shift & 255) - (second >>> shift & 255)));
        return largest;
    }

    private static boolean withinCoverage(int pixel, int[] neighborhood, int x, int y) {
        for (int shift = 0; shift <= 24; shift += 8) {
            int minimum = 255, maximum = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int channel = neighborhood[(y + dy) * SIZE + x + dx] >>> shift & 255;
                minimum = Math.min(minimum, channel);
                maximum = Math.max(maximum, channel);
            }
            int channel = pixel >>> shift & 255;
            if (channel < minimum - TRANSLUCENT_STROKES || channel > maximum + TRANSLUCENT_STROKES) return false;
        }
        return true;
    }

    @Test public void imageComparisonRejectsInteriorColorErrorsAndGeometryShifts() {
        int[] expected = new int[SIZE * SIZE];
        Arrays.fill(expected, BACKGROUND);
        for (int y = 20; y < 70; y++) for (int x = 20; x < 70; x++) expected[y * SIZE + x] = 0xff7799aa;
        equivalent(new Pixels(expected, expected.clone()), "identical");
        int[] wrongColor = expected.clone();
        wrongColor[40 * SIZE + 40] = 0xff7999aa;
        assertThrows(AssertionError.class, () -> equivalent(new Pixels(expected, wrongColor), "interior regression"));
        int[] shifted = new int[SIZE * SIZE];
        Arrays.fill(shifted, BACKGROUND);
        for (int y = 20; y < 70; y++) for (int x = 22; x < 72; x++) shifted[y * SIZE + x] = 0xff7799aa;
        assertThrows(AssertionError.class, () -> equivalent(new Pixels(expected, shifted), "geometry regression"));
    }

    private static void save(int index, Pixels pixels) throws Exception {
        String root = System.getProperty("vivenotes.test.waylandScreenshotDir");
        if (root == null) return;
        Path output = Path.of(root, "ink");
        Files.createDirectories(output);
        for (int side = 0; side < 2; side++) {
            BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, SIZE, SIZE, side == 0 ? pixels.reference : pixels.candidate, 0, SIZE);
            ImageIO.write(image, "png", output.resolve(index + (side == 0 ? "-raster.png" : "-moving.png")).toFile());
        }
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get(15, TimeUnit.SECONDS);
    }
}
