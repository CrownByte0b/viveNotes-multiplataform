package com.vivenotes.gcprobe

import java.io.File
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Component experiment after real OCR and explicit native-resource disposal; no app GC policy. */
object IdleGcProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args[0]
        require(mode in setOf("normal", "periodic", "explicit", "explicit-concurrent"))
        val output = File(args[1]).also { check(it.isDirectory && !File(it, "result.json").exists()) }
        val fixture = File(args[2]).canonicalFile
        val snapshots = ArrayList<JsonObject>()
        fun snapshot(stage: String) {
            val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
            snapshots += buildJsonObject {
                put("stage", stage); put("epochMillis", System.currentTimeMillis()); put("nanoTime", System.nanoTime())
                put("used", heap.used); put("committed", heap.committed)
                put("gcCount", ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionCount })
                put("gcMillis", ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime })
            }
        }
        val method = Class.forName("com.vivenotes.startupprobe.StartupRecognitionProbe")
            .getMethod("main", Array<String>::class.java)
        fun recognize(file: String) {
            method.invoke(null, arrayOf("ocr", File(output, file).absolutePath, fixture.path))
        }
        fun nativeMemory(file: String) {
            val report = ManagementFactory.getPlatformMBeanServer().invoke(
                ObjectName("com.sun.management:type=DiagnosticCommand"), "vmNativeMemory",
                arrayOf(arrayOf("summary", "scale=KB")), arrayOf("[Ljava.lang.String;"),
            ) as String
            check(report.contains("Native Memory Tracking") && report.contains("Java Heap"))
            File(output, file).writeText(report)
        }
        snapshot("before_work")
        recognize("first.json")
        nativeMemory("nmt-after-work.txt")
        snapshot("resources_closed")
        var requestNanos: Long? = null
        repeat(12) { tick ->
            Thread.sleep(1_000)
            if (tick == 4 && mode.startsWith("explicit")) {
                snapshot("before_request")
                val start = System.nanoTime()
                System.gc()
                requestNanos = System.nanoTime() - start
                snapshot("after_request")
            }
            snapshot("idle_${tick + 1}")
        }
        snapshot("before_next_work")
        nativeMemory("nmt-after-idle.txt")
        val resume = System.nanoTime()
        recognize("next.json")
        val nextNanos = System.nanoTime() - resume
        snapshot("after_next_work")
        val first = Json.parseToJsonElement(File(output, "first.json").readText()) as JsonObject
        val next = Json.parseToJsonElement(File(output, "next.json").readText()) as JsonObject
        for (key in listOf("fixtureSha256", "readingSha256", "confidenceBits")) check(first[key] == next[key])
        File(output, "result.json").writeText(buildJsonObject {
            put("schema", "vive-idle-gc-component/1"); put("mode", mode)
            put("java", System.getProperty("java.runtime.version"))
            put("collectors", JsonArray(ManagementFactory.getGarbageCollectorMXBeans().map { JsonPrimitive(it.name) }))
            put("snapshots", JsonArray(snapshots)); put("requestNanos", requestNanos?.let(::JsonPrimitive) ?: JsonNull)
            put("nextWorkNanos", nextNanos); put("outputParity", true)
            put("scope", "Real synthetic-image OCR, engine/Image/model-store close, 12 seconds without work, then engine reconstruction and OCR; no UI/input latency")
        }.toString() + "\n")
    }
}
