package com.vivenotes.diagnostics;

import com.vivenotes.ink.DesktopInkPageKt;
import com.vivenotes.model.ink.InkPage;
import com.vivenotes.performance.BaselineMeasurements;
import com.vivenotes.performance.InkAppendFixture;
import com.vivenotes.performance.InkPerformanceCasesKt;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

/** Separate warmed diagnostic; it never invokes the frozen main or its 128th-append workload. */
public final class InkAppendWarmDiagnostic {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected report path, fork and base-stroke count");
        Path output = Path.of(args[0]).toAbsolutePath();
        int fork = Integer.parseInt(args[1]);
        int count = Integer.parseInt(args[2]);
        InkAppendFixture fixture = InkPerformanceCasesKt.performanceInkAppendFixture(count);
        InkPage ordinary = DesktopInkPageKt.withDesktopEdit(fixture.getBefore127(), fixture.getAppend127());
        if (ordinary.getStrokes().size() != count + 127 ||
                DesktopInkPageKt.desktopGeometry(ordinary).getProjections().size() != count + 127) {
            throw new IllegalStateException("127th-append fixture validation failed");
        }
        // Fixed operations intentionally bypass BaselineMeasurements' duration condition;
        // request 20,000 calls explicitly rather than mistaking 400ms for an enforced minimum.
        BaselineMeasurements measurements = new BaselineMeasurements(400_000_000L, 20_000, 50, 10_000_000L);
        measurements.measure("diagnostic.ink.apply_127th_append.warmed", count, fixture.getFingerprint127(),
                1, true, () -> DesktopInkPageKt.withDesktopEdit(fixture.getBefore127(), fixture.getAppend127()));
        String vmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .map(InkAppendWarmDiagnostic::quote).collect(Collectors.joining(",", "[", "]"));
        String cases = measurements.getResults().stream().map(Object::toString)
                .collect(Collectors.joining(",", "[", "]"));
        String report = "{\n\"schema\":\"vive-ink-warm-diagnostic/1\",\n\"fork\":" + fork +
                ",\n\"runtime\":" + quote(System.getProperty("java.runtime.version")) +
                ",\n\"vendor\":" + quote(System.getProperty("java.vendor")) +
                ",\n\"maxHeapBytes\":" + Runtime.getRuntime().maxMemory() +
                ",\n\"vmArgs\":" + vmArgs + ",\n\"cases\":" + cases + "\n}\n";
        Files.createDirectories(output.getParent());
        Files.writeString(output, report);
        System.out.println("Warmed append diagnostic: " + output);
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 32) result.append(String.format("\\u%04x", (int) character));
                    else result.append(character);
                }
            }
        }
        return result.append('"').toString();
    }
}
