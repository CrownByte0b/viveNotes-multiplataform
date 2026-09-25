# Vive Notes Desktop

Kotlin Multiplatform port of the Android ViveNotes app, initially targeting Linux and Windows with
Compose Multiplatform and Material 3 Expressive.

The original Android source is currently the product and format reference. This repository is a
fresh desktop implementation which will preserve its note model, `.vive` transfer format, and sync
semantics while replacing Android-only services with desktop adapters.

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
