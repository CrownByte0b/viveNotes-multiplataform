package com.vivenotes.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class InkTextRegion(
    val id: String,
    val text: String,
    val confidence: Float,
    val alternateText: String? = null,
    val alternateConfidence: Float? = null,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val strokeIds: List<String>,
)

object InkTextRegionsCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(InkTextRegion.serializer())

    fun encode(regions: List<InkTextRegion>): String = json.encodeToString(serializer, regions)

    fun decode(value: String): List<InkTextRegion> =
        runCatching { json.decodeFromString(serializer, value) }.getOrDefault(emptyList())
}

