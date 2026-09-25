# ViveNotes Desktop

Kotlin Multiplatform port of the Android ViveNotes app, initially targeting Linux and Windows with
Compose Multiplatform and Material 3 Expressive.

The original Android source is currently the product and format reference. This repository is a
fresh desktop implementation which will preserve its note model, `.vive` transfer format, and sync
semantics while replacing Android-only services with desktop adapters.

## Current status

Phase 0 is implemented: a runnable in-memory workspace skeleton with ribbon navigation, notebook
and section navigation, a page list, responsive desktop panes, and editable placeholder pages.
Phase 1 is under way: the canonical Android `PageDoc` model, migrations, JSON/CBOR codecs, and the
first compatibility-test batch now live in shared code. The UI still uses fixture state;
persistence, rich editing, and sync are intentionally not wired yet.

The audited port sequence and compatibility risks are documented in
[`memory/port-plan.md`](memory/port-plan.md). Ink implementation is reserved for a later design
discussion and does not block the text-first port.

## Project layout

- `shared/src/commonMain`: shared state, UI, document model/codecs, and portable domain contracts.
- `shared/src/jvmMain`: future Linux/Windows platform implementations.
- `desktopApp`: JVM desktop entry point and window configuration.
- `webApp`: generated template only; web is outside the current scope.
- `memory`: durable project decisions and the port plan.

## Run and test

Use a current JDK with `jpackage` available (JDK 21 is recommended).

```bash
./gradlew :desktopApp:run
./gradlew :shared:jvmTest
```

Compose hot reload remains available through the generated task:

```bash
./gradlew :desktopApp:hotRun --auto
```

Packaging is deferred. Linux will use Flatpak; the Windows distribution choice will be finalized
with release work.
