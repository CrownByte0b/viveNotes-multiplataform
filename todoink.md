# ByteInk: gaps against the Android app

Found while porting Android's account sync to the desktop (started 2026-10-18). Each entry names
the Android API, what ByteInk offers instead, and where the desktop works around it.

## Missing

Nothing so far has been missing outright.

## Different from Android's `app/src/main/java/com/vivenotes/ink/InkCodec.kt`

These exist in ByteInk, but under another name, place or shape than Android's.

1. **`InkCodec.eraseMask(inputs: StrokeInputBatch, sizeDp: Float): Stroke`** has no direct
   counterpart. `ViveBrushes` has no public `eraseMask`. The nearest is
   `ViveInkCodec.reloadedEraseMask(inputs, sizeDp): Stroke?`, which first encodes and decodes the
   inputs and returns null on failure, where Android builds the mask stroke directly and cannot
   fail.
   - Desktop workaround: `shared/src/jvmTest/.../data/sync/InkSyncCostTest.kt` (`sampleStroke`)
     calls `reloadedEraseMask` and `requireNotNull`s the result.
2. **`InkCodec.encode(stroke, pageId, seq, pen: PenPreset, now)`** is
   `ViveInkCodec.encodeStroke(stroke, id, pageId, seq, brushFamily, stabilization,
   colorFollowsTheme, createdAt, groupId)`. It takes the pen's fields instead of Android's
   `PenPreset`, needs the caller to supply the row id, and returns ByteInk's `StoredInkStroke`
   rather than `InkStrokeEntity`, so the desktop converts it with `toEntity()`
   (`shared/src/jvmMain/.../ink/DesktopInkPage.kt`).
3. **`InkCodec.BRUSH_VERSION`** is `ViveBrushes.BRUSH_VERSION`, not on the codec.
