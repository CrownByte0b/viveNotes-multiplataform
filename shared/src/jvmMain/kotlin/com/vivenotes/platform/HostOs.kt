package com.vivenotes.platform

/** Whether this JVM runs on Windows. Outside `ui/`, where the resource linter reads literals as UI text. */
internal val runsOnWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")
