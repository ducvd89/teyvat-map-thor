package com.teyvatmap.thor.cv

import com.teyvatmap.thor.data.store.PackParser
import com.teyvatmap.thor.tracking.cv.Calib
import com.teyvatmap.thor.tracking.cv.FrameExtractor
import com.teyvatmap.thor.tracking.cv.FrameInput
import com.teyvatmap.thor.tracking.cv.MatSource
import com.teyvatmap.thor.tracking.cv.RegionZoomTable
import com.teyvatmap.thor.tracking.cv.ZoomAnchor
import com.teyvatmap.thor.tracking.cv.ZoomEntry
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/**
 * The REAL Thor frames (data/cv/real_report.md) exported by `tools/cv/export_real_fixtures.py` and pushed to
 * `getExternalFilesDir/real/` (not part of the APK): the 450-frame sequence as minimap ROI + thumbnail, ~60 full frames
 * with the UID blanked, and the big-map captures.
 */
object RealEnv {
    val dir: File get() = File(CvTestEnv.target.getExternalFilesDir(null), "real")
    val available: Boolean get() = File(dir, "seq/index.json").isFile

    class Frame(val o: JsonObject) {
        val i = o["i"]!!.jsonPrimitive.int
        val t = o["t"]!!.jsonPrimitive.double
        val kind = o["kind"]!!.jsonPrimitive.content
        val labelled = o["inWorld"]!!.jsonPrimitive.content == "true"
        val x: Double? = o["x_pos"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.double
        val y: Double? = o["y_pos"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.double
        val scale: Double? = o["scale"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.double
    }

    class Seq(val roi: List<Int>, val calib: Calib, val frames: List<Frame>)

    fun sequence(): Seq {
        val idx = CvTestEnv.json.parseToJsonElement(File(dir, "seq/index.json").readText()).jsonObject
        val c = idx["calib"]!!.jsonObject
        val calib = Calib(c["cx"]!!.jsonPrimitive.double, c["cy"]!!.jsonPrimitive.double, c["r"]!!.jsonPrimitive.double, c["s"]!!.jsonPrimitive.double)
        return Seq(idx["roi"]!!.jsonArray.map { it.jsonPrimitive.int }, calib, idx["frames"]!!.jsonArray.map { Frame(it.jsonObject) })
    }

    fun frameInput(seq: Seq, f: Frame): FrameInput {
        val roi = Imgcodecs.imread(File(dir, "seq/roi/%03d.webp".format(f.i)).path, Imgcodecs.IMREAD_COLOR)
        val th = Imgcodecs.imread(File(dir, "seq/thumb/%03d.png".format(f.i)).path, Imgcodecs.IMREAD_GRAYSCALE)
        val b = ByteArray(roi.rows() * roi.cols() * 3); roi.get(0, 0, b)
        val tb = ByteArray(64 * 36); th.get(0, 0, tb)
        val fi = FrameInput(b, roi.cols(), roi.rows(), seq.roi[0], seq.roi[1], tb)
        roi.release(); th.release()
        return fi
    }

    /** A full frame (BGR) of the `full/` set or `bigmap/`. */
    fun full(rel: String): Mat = Imgcodecs.imread(File(dir, rel).path, Imgcodecs.IMREAD_COLOR)

    fun fullIndex(): List<JsonObject> =
        CvTestEnv.json.parseToJsonElement(File(dir, "full/index.json").readText()).jsonObject["frames"]!!.jsonArray.map { it.jsonObject }

    /** FrameInput of a full frame through the production extractor. */
    fun extract(m: Mat, calib: Calib): FrameInput = FrameExtractor.extract(MatSource(m), calib)

    /** Region zoom table from the pack's anchors (Teyvat) seeded with the two measured scales. */
    fun zoomTable(seeded: Boolean = true): RegionZoomTable {
        val anchors = PackParser.parseAnchors(PackParser.json.parseToJsonElement(File(CvTestEnv.pack.mapFile(2, "anchors.json").path).readText()), 2)
            .map { ZoomAnchor(it.id, it.parentId, it.name, it.lX, it.lY, it.rX, it.rY) }
        return RegionZoomTable(anchors, if (seeded) RegionZoomTable.SEEDS.map { (id, s) -> ZoomEntry(id, s, 30, 0) } else emptyList())
    }
}
