package com.teyvatmap.thor.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The portable user-data document (PLAN §7): marks keyed by point id, pins keyed by uuid, respawn overrides keyed
 * by label id. It is the content of `marks.json` in Drive, of the local daily backups and of the JSON export.
 */
@Serializable
data class MarksDoc(
    val schema: Int = SCHEMA,
    val device: String = "",
    val marks: Map<String, MarkEntry> = emptyMap(),
    val pins: Map<String, PinEntry> = emptyMap(),
    val respawn: Map<String, RespawnEntry> = emptyMap(),
) {
    companion object {
        const val SCHEMA = 1
        val EMPTY = MarksDoc()
    }
}

/** `s`: F / H / N (un-marking is an entry, so it syncs), `t`: state time, `n`: note, `nt`: note time. */
@Serializable
data class MarkEntry(val s: String, val t: Long, val n: String? = null, val nt: Long = 0)

@Serializable
data class PinEntry(
    @SerialName("m") val mapId: Int,
    val x: Double,
    val y: Double,
    @SerialName("f") val floorId: Int? = null,
    val title: String = "",
    val note: String = "",
    @SerialName("c") val color: Int = 0,
    val t: Long,
    val del: Boolean = false,
)

@Serializable
data class RespawnEntry(val h: Int, val t: Long)

object MarksJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(doc: MarksDoc): String = json.encodeToString(MarksDoc.serializer(), doc)
    fun decode(text: String): MarksDoc = json.decodeFromString(MarksDoc.serializer(), text)

    fun gzip(doc: MarksDoc): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(encode(doc).toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    /** Accepts gzip (Drive) or plain JSON (exports) transparently. */
    fun decodeBytes(bytes: ByteArray): MarksDoc {
        val isGzip = bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        val text = if (isGzip) GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().toString(Charsets.UTF_8) }
        else bytes.toString(Charsets.UTF_8)
        return decode(text)
    }
}
