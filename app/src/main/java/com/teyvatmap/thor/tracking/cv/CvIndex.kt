package com.teyvatmap.thor.tracking.cv

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** A prepared (LCN, uint8) reference image and where it sits on the map. [mat] is a view of a memory-mapped file. */
class RefImage(val mat: Mat, val level: Level, @Suppress("unused") private val keep: ByteBuffer)

@Serializable
data class FloorLevelMeta(val level: Level, val offset: Long)

@Serializable
data class FloorMeta(val floor: Floor, val levels: Map<Int, FloorLevelMeta>)

@Serializable
data class TargetsFile(val xy: List<Double>, val kind: List<Int>, val link: List<Int> = emptyList())

@Serializable
data class IndexMeta(
    val format: Int,
    val geo: MapGeo,
    val sigma: Double,
    val lcnC: Double,
    /** Surface levels with a `prep_z{z}.bin`. */
    val levels: List<Level>,
    val floorLevels: List<Int>,
    val floors: List<FloorMeta>,
    val targets: Int,
    val entrances: List<Entrance> = emptyList(),
    val builtAtMs: Long,
    val buildMs: Long,
)

/** Where the matching index lives: `getExternalFilesDir/cv/{mapId}/{mapVersion}/`. */
class CvPaths(val root: File) {
    fun dir(mapId: Int, version: String) = File(root, "$mapId/$version")
    fun metaFile(mapId: Int, version: String) = File(dir(mapId, version), META)
    fun isBuilt(mapId: Int, version: String): Boolean = CvIndex.readMeta(dir(mapId, version)) != null
    fun calibrationFile() = File(root, "calibration.json")
    fun paramsFile() = File(root, "params.json")

    companion object {
        const val META = "meta.json"
    }
}

/** A built index of one map version: memory-mapped surface levels, floor references, layer zones and targets. */
class CvIndex private constructor(val dir: File, val meta: IndexMeta) : AutoCloseable {
    val geo: MapGeo get() = meta.geo
    val layers = LayerIndex(meta.floors.map { it.floor })
    val entrances = EntranceIndex(meta.entrances)
    val targets: Targets by lazy { readTargets(dir) }

    private val prepCache = HashMap<Int, RefImage>()
    private val floorFiles = HashMap<Int, ByteBuffer>()
    private val floorCache = HashMap<Pair<Int, Int>, RefImage?>()
    private val floorMeta = meta.floors.associateBy { it.floor.floorId }

    fun hasLevel(z: Int) = meta.levels.any { it.z == z }

    @Synchronized
    fun prep(z: Int): RefImage = prepCache.getOrPut(z) {
        val lv = meta.levels.firstOrNull { it.z == z } ?: error("no prepared level z=$z for map ${geo.mapId}")
        val buf = mapFile(File(dir, "prep_z$z.bin"))
        RefImage(Mat(lv.h, lv.w, CvType.CV_8UC1, buf), lv, buf)
    }

    /** Reference patch of one floor (surface filler + overlay), or null if it was not built for this level. */
    @Synchronized
    fun floorRef(floorId: Int, z: Int): RefImage? = floorCache.getOrPut(floorId to z) {
        val fm = floorMeta[floorId]?.levels?.get(z) ?: return@getOrPut null
        val whole = floorFiles.getOrPut(z) { mapFile(File(dir, "floors_z$z.bin")) }
        val lv = fm.level
        val d = whole.duplicate()
        d.position(fm.offset.toInt())
        d.limit(fm.offset.toInt() + lv.w * lv.h)
        val slice = d.slice()
        RefImage(Mat(lv.h, lv.w, CvType.CV_8UC1, slice), lv, slice)
    }

    @Synchronized
    override fun close() {
        prepCache.values.forEach { it.mat.release() }
        floorCache.values.forEach { it?.mat?.release() }
        prepCache.clear(); floorCache.clear(); floorFiles.clear()
    }

    companion object {
        const val FORMAT = 2
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun readMeta(dir: File): IndexMeta? {
            val f = File(dir, CvPaths.META)
            if (!f.isFile) return null
            return try {
                JSON.decodeFromString(IndexMeta.serializer(), f.readText()).takeIf { it.format == FORMAT }
            } catch (_: Exception) {
                null
            }
        }

        fun writeMeta(dir: File, meta: IndexMeta) {
            val tmp = File(dir, CvPaths.META + ".part")
            tmp.writeText(JSON.encodeToString(IndexMeta.serializer(), meta))
            check(tmp.renameTo(File(dir, CvPaths.META))) { "cannot write index meta" }
        }

        fun open(dir: File): CvIndex? {
            Cv.init()
            val meta = readMeta(dir) ?: return null
            return CvIndex(dir, meta)
        }

        fun mapFile(f: File): ByteBuffer = RandomAccessFile(f, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
        }

        fun writeTargets(dir: File, t: Targets) {
            val tf = TargetsFile(t.xy.toList(), t.kind.map { it.toInt() }, t.link.toList())
            File(dir, "targets.json").writeText(JSON.encodeToString(TargetsFile.serializer(), tf))
        }

        fun readTargets(dir: File): Targets {
            val f = File(dir, "targets.json")
            if (!f.isFile) return Targets.EMPTY
            val tf = JSON.decodeFromString(TargetsFile.serializer(), f.readText())
            return Targets(tf.xy.toDoubleArray(), ByteArray(tf.kind.size) { tf.kind[it].toByte() }, IntArray(tf.kind.size) { tf.link.getOrElse(it) { 0 } })
        }
    }
}
