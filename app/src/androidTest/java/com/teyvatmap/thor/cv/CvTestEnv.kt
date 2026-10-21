package com.teyvatmap.thor.cv

import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.teyvatmap.thor.data.store.PackPaths
import com.teyvatmap.thor.tracking.cv.Calib
import com.teyvatmap.thor.tracking.cv.Cv
import com.teyvatmap.thor.tracking.cv.CvIndex
import com.teyvatmap.thor.tracking.cv.CvIndexBuilder
import com.teyvatmap.thor.tracking.cv.CvPaths
import com.teyvatmap.thor.tracking.cv.FrameInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/** Shared test setup: the app's pack + CV index on the device, and the fixtures shipped in the test APK. */
object CvTestEnv {
    const val TAG = "CvTest"
    val target: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx: Context get() = InstrumentationRegistry.getInstrumentation().context
    val json = Json { ignoreUnknownKeys = true }

    val pack by lazy { PackPaths(target) }
    val cvPaths by lazy { CvPaths(File(target.getExternalFilesDir(null)!!, "cv")) }

    /** Opens the map-2 index, building it from the local tiles first when it is missing. */
    @Synchronized
    fun index(mapId: Int = 2): CvIndex {
        Cv.init()
        val builder = CvIndexBuilder(pack, cvPaths)
        val geo = builder.readGeo(mapId) ?: error("no pack on the device (pack/maps/$mapId/info.json)")
        if (!cvPaths.isBuilt(mapId, geo.version)) {
            val t0 = System.currentTimeMillis()
            builder.build(geo, progress = com.teyvatmap.thor.tracking.cv.BuildProgress { stage, done, total -> if (done % 200 == 0) Log.i(TAG, "build: $stage $done/$total") })
            Log.i(TAG, "index built in ${System.currentTimeMillis() - t0} ms")
        }
        return CvIndex.open(cvPaths.dir(mapId, geo.version))!!
    }

    fun asset(path: String): ByteArray = testCtx.assets.open("cv/$path").use { it.readBytes() }

    fun jsonAsset(path: String): JsonObject = json.parseToJsonElement(String(asset(path))).jsonObject

    fun bgr(path: String): Mat {
        val raw = MatOfByte(*asset(path))
        return Imgcodecs.imdecode(raw, Imgcodecs.IMREAD_COLOR).also { raw.release() }
    }

    fun gray(path: String): Mat {
        val raw = MatOfByte(*asset(path))
        return Imgcodecs.imdecode(raw, Imgcodecs.IMREAD_GRAYSCALE).also { raw.release() }
    }

    fun calibOf(o: JsonObject): Calib = Calib(
        cx = o["cx"]!!.jsonPrimitive.double, cy = o["cy"]!!.jsonPrimitive.double, r = o["r"]!!.jsonPrimitive.double,
        s = o["s"]!!.jsonPrimitive.double,
        ringRef = (o["ringRef"] as? JsonArray)?.map { it.jsonPrimitive.double.toFloat() },
        frameW = o["frameW"]!!.jsonPrimitive.int, frameH = o["frameH"]!!.jsonPrimitive.int,
    )

    fun frameInput(roiPng: String, thumbPng: String?, roi: List<Int>): FrameInput {
        val m = bgr(roiPng)
        val b = ByteArray(m.rows() * m.cols() * 3)
        m.get(0, 0, b)
        val th = ByteArray(64 * 36)
        if (thumbPng != null) {
            val tm = gray(thumbPng)
            tm.get(0, 0, th)
            tm.release()
        }
        val fi = FrameInput(b, m.cols(), m.rows(), roi[0], roi[1], th)
        m.release()
        return fi
    }

    fun JsonElement.dbl() = jsonPrimitive.double
    fun JsonElement.intv() = jsonPrimitive.int
    fun JsonElement?.isNull() = this == null || this is kotlinx.serialization.json.JsonNull
    fun JsonObject.arr(k: String) = this[k]!!.jsonArray
    fun JsonObject.obj(k: String) = this[k]!!.jsonObject
    @Suppress("unused") val unused = JsonPrimitive(0)
}
