package com.vivenotes.desktop

import com.vivenotes.workspace.WorkspaceState
import java.io.DataInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/** Native Wayland runs on JetBrains Runtime 25, so builds on newer JDKs must keep Java 25 bytecode. */
class JvmTargetTest {
    @Test
    fun desktopAndSharedClassesLoadOnJetBrainsRuntime25() {
        assertEquals(JAVA_25_CLASS_VERSION, classFileMajorVersion(WindowBackend::class.java))
        assertEquals(JAVA_25_CLASS_VERSION, classFileMajorVersion(WorkspaceState::class.java))
    }

    private fun classFileMajorVersion(type: Class<*>): Int =
        DataInputStream(checkNotNull(type.getResourceAsStream("${type.simpleName}.class"))).use { input ->
            check(input.readInt() == 0xCAFEBABE.toInt()) { "${type.name} is not a class file" }
            input.readUnsignedShort()
            input.readUnsignedShort()
        }

    private companion object {
        const val JAVA_25_CLASS_VERSION = 69
    }
}
