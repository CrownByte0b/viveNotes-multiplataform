package com.vivenotes.data.sync

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentDownloadStagesTest {
    @Test
    fun reclaimsAbandonedLeasesAndKeepsUnrelatedFiles() {
        val root = Files.createTempDirectory("orphan-download").toFile()
        try {
            val directory = File(root, ".sync-staging").apply { mkdirs() }
            val stale = File(directory, "${"a".repeat(64)}-00000000-0000-0000-0000-000000000000.part")
            stale.writeText("partial bytes")
            File(stale.path + ".lock").writeText("")
            val unrelated = File(directory, "keep-this-file").apply { writeText("unknown owner") }
            AttachmentDownloadStages().create(File(root, "target.sync"), "b".repeat(64)).use { active ->
                assertFalse(stale.exists())
                assertFalse(File(stale.path + ".lock").exists())
                assertTrue(unrelated.exists())
                assertTrue(active.file.isFile, "The stage exists before a network response can arrive")
            }
            assertTrue(root.walkTopDown().none { it.extension == "part" })
        } finally { assertTrue(root.deleteRecursively()) }
    }

    @Test
    fun independentOwnersNeverRemoveEachOthersActiveStages() {
        val root = Files.createTempDirectory("active-download").toFile()
        try {
            AttachmentDownloadStages().create(File(root, "first.sync"), "a".repeat(64)).use { first ->
                first.file.writeText("still downloading")
                AttachmentDownloadStages().create(File(root, "second.sync"), "b".repeat(64)).use { second ->
                    assertEquals("still downloading", first.file.readText())
                    assertTrue(second.file.isFile)
                }
                assertTrue(first.file.isFile)
            }
            assertTrue(root.walkTopDown().none { it.extension == "part" })
        } finally { assertTrue(root.deleteRecursively()) }
    }

    @Test
    fun cleanupSkipsAnotherLiveProcessThenReclaimsItsStageAfterItCrashes() {
        val root = Files.createTempDirectory("process-download").toFile()
        val ready = File(root, "ready")
        val log = File(root, "child.log")
        val javaName = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/$javaName").absolutePath,
            "-cp", System.getProperty("java.class.path"), AttachmentStageLeaseProcess::class.java.name,
            root.absolutePath).redirectErrorStream(true).redirectOutput(log).start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!ready.isFile && process.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(ready.isFile, "Child did not acquire its stage: ${log.takeIf { it.exists() }?.readText()}")
            val childStage = File(root, ".sync-staging/${ready.readText()}")
            AttachmentDownloadStages().create(File(root, "parent.sync"), "b".repeat(64)).use {
                assertEquals("live process bytes", childStage.readText())
            }
            process.destroyForcibly()
            assertTrue(process.waitFor(15, TimeUnit.SECONDS))
            AttachmentDownloadStages().create(File(root, "after-crash.sync"), "c".repeat(64)).use {
                assertFalse(childStage.exists())
                assertFalse(File(childStage.path + ".lock").exists())
            }
            assertTrue(root.walkTopDown().none { it.extension == "part" })
        } finally {
            process.destroyForcibly().waitFor()
            assertTrue(root.deleteRecursively())
        }
    }
}

/** The parent kills this JVM without close(), exercising actual operating-system lease release. */
object AttachmentStageLeaseProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.single())
        val active = AttachmentDownloadStages().create(File(root, "child.sync"), "a".repeat(64))
        active.file.writeText("live process bytes")
        File(root, "ready").writeText(active.file.name)
        System.`in`.read()
    }
}
