package com.vivenotes.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class ReleaseArtifactsTest {
    @Test
    fun draftArtifactAttachmentContracts() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        val root = File(System.getProperty("vivenotes.test.projectRoot"))
        val process = ProcessBuilder("python3", "-B", ".github/tests/test_release_artifacts.py")
            .directory(root).redirectErrorStream(true).start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Release artifact contracts timed out")
        assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
    }

    @Test
    fun attachingArtifactsRequiresExplicitManualActionAndNeverPublishes() {
        val root = File(System.getProperty("vivenotes.test.projectRoot"))
        val workflow = File(root, ".github/workflows/attach-release-artifacts.yml").readText()
        val triggers = workflow.substringAfter("\non:\n").substringBefore("\nconcurrency:")
        assertTrue(triggers.contains("  workflow_dispatch:\n"))
        assertTrue(triggers.contains("      tag:\n"))
        assertFalse(triggers.contains("  push:"))
        assertFalse(triggers.contains("  release:"))
        assertTrue(workflow.contains("      actions: read\n"))
        assertTrue(workflow.contains("      contents: write\n"))
        assertTrue(workflow.contains("attach_release_artifacts.py --tag \"\$RELEASE_TAG\""))
        assertFalse(workflow.contains("gh release create"))
        assertFalse(workflow.contains("gh release edit"))
    }
}
