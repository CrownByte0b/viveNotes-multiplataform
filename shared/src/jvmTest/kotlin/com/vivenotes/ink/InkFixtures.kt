package com.vivenotes.ink

/**
 * Point blobs AndroidX Ink 1.1.0-alpha06 — the Android app's version — encoded, for tests that need
 * ink rows Android would have written. See `StrokeInputBatchCodecTest` for where they came from.
 */
object InkFixtures {
    /** Two unknown-tool inputs, (10, 20) at 0 ms and (30, 40) at 10 ms: Android's transfer-test stroke. */
    val twoPointStroke: ByteArray get() = blob("two-point-unknown")

    fun blob(name: String): ByteArray =
        checkNotNull(InkFixtures::class.java.getResource("/ink/androidx-ink-1.1.0-alpha06/$name.bin")) { name }
            .readBytes()
}
