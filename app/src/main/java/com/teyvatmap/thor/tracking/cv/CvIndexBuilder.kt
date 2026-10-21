package com.teyvatmap.thor.tracking.cv

import com.teyvatmap.thor.data.store.PackParser
import com.teyvatmap.thor.data.store.PackPaths
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

fun interface BuildProgress {
    fun report(stage: String, done: Int, total: Int)
}

/**
 * Builds the matching reference data of one map version from the local tiles (PLAN §6.4), the Kotlin counterpart of
 * `tools/cv/{mapdata,preprocess,layers,targets}.py`:
 *  * grayscale land mosaics at z-3..0, preprocessed (LCN) into memory-mappable `prep_z{z}.bin` files,
 *  * floor-overlay references (overlay stretched onto its bounds, composited over the dark underground background,
 *    surface gray as filler, padded by 220 z0 px) at z-2..0,
 *  * the layer-zone index (floor bounds) and the teleport-target list, stored in `meta.json` / `targets.json`.
 * Works from the pack folder only (no database), so it also runs inside instrumented tests.
 */
class CvIndexBuilder(
    private val pack: PackPaths,
    private val cv: CvPaths,
    private val params: TrackerParams = TrackerParams(),
    private val threads: Int = min(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1),
) {
    private val levels = listOf(-3, -2, -1, 0)
    private val floorLevels = listOf(-2, -1, 0)

    /** Maps found in the pack that have tile folders. */
    fun builtMaps(): List<Pair<Int, String>> = pack.mapIds().mapNotNull { id ->
        val geo = readGeo(id) ?: return@mapNotNull null
        id to geo.version
    }

    fun readGeo(mapId: Int): MapGeo? {
        val f = pack.mapFile(mapId, "info.json")
        if (f.length() == 0L) return null
        return MapGeo.fromInfoJson(mapId, PackParser.json.parseToJsonElement(f.readText()))
    }

    /** True if every tile of the working levels is present (otherwise the mosaic would have holes). */
    fun tilesComplete(geo: MapGeo): Boolean {
        val dir = File(pack.root, "tiles/${geo.mapId}/${geo.version}")
        return levels.filter { it >= geo.minZoom }.all { z ->   // coarser levels are derived from the finest stored ones
            val lv = levelGeom(geo, z)
            val ts = 256
            val tx1 = (lv.ox + lv.w - 1) / ts
            val ty1 = (lv.oy + lv.h - 1) / ts
            val nExpected = (tx1 - lv.ox / ts + 1) * (ty1 - lv.oy / ts + 1)
            val have = File(dir, "$z").list()?.size ?: 0
            have >= nExpected * 0.95
        }
    }

    /**
     * Builds (or rebuilds) the index of [geo]. Returns the output directory. [cancelled] is polled between steps and
     * must throw (e.g. `ensureActive()`) to abort; partial output is left without `meta.json` and is ignored.
     */
    fun build(geo: MapGeo, progress: BuildProgress = BuildProgress { _, _, _ -> }, cancelled: () -> Unit = {}): File {
        Cv.init()
        val t0 = System.currentTimeMillis()
        val out = cv.dir(geo.mapId, geo.version)
        out.deleteRecursively()
        out.mkdirs()
        val work = File(out, "tmp").also { it.mkdirs() }
        val pool = Executors.newFixedThreadPool(threads)
        try {
            // ---- work estimate for the progress bar
            val floors = readFloors(geo)
            val blocksPerLevel = levels.map { z -> blocks(levelGeom(geo, z)).size }
            val total = levels.sumOf { z -> tileCount(levelGeom(geo, z)) } + blocksPerLevel.sum() + floors.size * floorLevels.size + 2
            val done = AtomicInteger(0)
            fun tick(stage: String, n: Int = 1) = progress.report(stage, done.addAndGet(n), total)

            val mosaics = HashMap<Int, File>()
            for (z in levels.reversed()) {   // finest first: levels below the server's min_zoom are derived from the next finer one
                cancelled()
                mosaics[z] = if (z < geo.minZoom) downscale(geo, z, mosaics.getValue(z + 1), work) { tick("Map ${geo.mapId}: tiles z$z", it) }
                else stitch(geo, z, work, pool, cancelled) { tick("Map ${geo.mapId}: tiles z$z", it) }
            }
            val levelMetas = ArrayList<Level>()
            for (z in levels) {
                cancelled()
                val lv = levelGeom(geo, z)
                prepare(mosaics.getValue(z), lv, File(out, "prep_z$z.bin"), pool, cancelled) { tick("Map ${geo.mapId}: preprocessing z$z", it) }
                levelMetas += lv
            }
            // ---- floors
            val packed = HashMap<Int, java.io.FileOutputStream>()
            val offsets = HashMap<Int, Long>()
            val tmpLevels = HashMap<Int, MutableMap<Int, FloorLevelMeta>>()
            try {
                for ((i, fl) in floors.withIndex()) {
                    cancelled()
                    val overlay = loadOverlay(fl) ?: continue
                    val gray = overlayGray(fl, overlay)
                    overlay.release()
                    for (z in floorLevels) {
                        val lv = floorGeom(fl, z)
                        val patch = floorPatch(geo, fl, gray, z, lv, mosaics.getValue(z))
                        val bytes = ByteArray(lv.w * lv.h)
                        patch.get(0, 0, bytes)
                        patch.release()
                        val os = packed.getOrPut(z) { java.io.FileOutputStream(File(out, "floors_z$z.bin"), false).also { offsets[z] = 0L } }
                        os.write(bytes)
                        tmpLevels.getOrPut(fl.floorId) { HashMap() }[z] = FloorLevelMeta(lv, offsets[z]!!)
                        offsets[z] = offsets[z]!! + bytes.size
                    }
                    gray.release()
                    tick("Map ${geo.mapId}: underground floors", floorLevels.size)
                    if (i % 16 == 0) progress.report("Map ${geo.mapId}: underground floors", done.get(), total)
                }
            } finally {
                packed.values.forEach { it.close() }
            }
            val finalFloors = floors.map { FloorMeta(it, tmpLevels[it.floorId] ?: emptyMap()) }.filter { it.levels.isNotEmpty() }
            // ---- targets
            cancelled()
            val points = readPoints(geo)
            val (entrances, linkOf) = readEntrances(geo, finalFloors.map { it.floor }, points)
            val targets = readTargets(geo, points, linkOf)
            CvIndex.writeTargets(out, targets)
            tick("Map ${geo.mapId}: teleport targets")
            work.deleteRecursively()
            val meta = IndexMeta(
                CvIndex.FORMAT, geo, params.sigma, params.lcnC, levelMetas, floorLevels, finalFloors, targets.size, entrances,
                System.currentTimeMillis(), System.currentTimeMillis() - t0,
            )
            CvIndex.writeMeta(out, meta)
            tick("Map ${geo.mapId}: done")
            return out
        } finally {
            pool.shutdownNow()
            work.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------------------- mosaics
    private fun tileRange(lv: Level): IntArray {
        val ts = 256
        return intArrayOf(lv.ox.floorDiv(ts), lv.oy.floorDiv(ts), (lv.ox + lv.w - 1).floorDiv(ts), (lv.oy + lv.h - 1).floorDiv(ts))
    }

    private fun tileCount(lv: Level): Int {
        val r = tileRange(lv)
        return (r[2] - r[0] + 1) * (r[3] - r[1] + 1)
    }

    /** Stitches the land bounding box at level [z] into a raw gray file (blank / missing tiles = sea gray 22). */
    private fun stitch(geo: MapGeo, z: Int, work: File, pool: ExecutorService, cancelled: () -> Unit, tick: (Int) -> Unit): File {
        val lv = levelGeom(geo, z)
        val file = File(work, "mosaic_z$z.raw")
        val (buf, mat) = mapRw(file, lv.w, lv.h)
        mat.setTo(Scalar(SEA_GRAY.toDouble()))
        val r = tileRange(lv)
        val ts = 256
        val jobs = ArrayList<Callable<Unit>>()
        for (ty in r[1]..r[3]) jobs += Callable {
            for (tx in r[0]..r[2]) {
                cancelled()
                val f = pack.tileFile(geo.mapId, geo.version, z, tx, ty)
                if (f.length() >= 100) {
                    val img = Imgcodecs.imread(f.path, Imgcodecs.IMREAD_GRAYSCALE)
                    if (!img.empty()) {
                        val gx = tx * ts - lv.ox
                        val gy = ty * ts - lv.oy
                        val sx0 = max(0, -gx); val sy0 = max(0, -gy)
                        val dx0 = max(0, gx); val dy0 = max(0, gy)
                        val wv = min(img.cols() - sx0, min(ts - sx0, lv.w - dx0))
                        val hv = min(img.rows() - sy0, min(ts - sy0, lv.h - dy0))
                        if (wv > 0 && hv > 0) {
                            img.submat(Rect(sx0, sy0, wv, hv)).copyTo(mat.submat(Rect(dx0, dy0, wv, hv)))
                        }
                    }
                    img.release()
                }
                tick(1)
            }
        }
        pool.invokeAll(jobs).forEach { it.get() }
        (buf as java.nio.MappedByteBuffer).force()
        mat.release()
        return file
    }

    /** Level [z] from the next finer mosaic [srcFile] (level z+1) by 2x area averaging, for maps whose tiles stop above z. */
    private fun downscale(geo: MapGeo, z: Int, srcFile: File, work: File, tick: (Int) -> Unit): File {
        val lv = levelGeom(geo, z)
        val sv = levelGeom(geo, z + 1)
        val file = File(work, "mosaic_z$z.raw")
        val (buf, dst) = mapRw(file, lv.w, lv.h)
        val (_, src) = mapRo(srcFile, sv.w, sv.h)
        // source pixel u_s lands at (u_s + sv.ox) / 2 - lv.ox; sv.ox - 2 * lv.ox is 0 or 1
        val padded = Mat(lv.h * 2, lv.w * 2, CvType.CV_8UC1, Scalar(SEA_GRAY.toDouble()))
        val dx = sv.ox - 2 * lv.ox
        val dy = sv.oy - 2 * lv.oy
        val w = min(sv.w, padded.cols() - dx)
        val h = min(sv.h, padded.rows() - dy)
        if (w > 0 && h > 0) src.submat(Rect(0, 0, w, h)).copyTo(padded.submat(Rect(dx, dy, w, h)))
        Imgproc.resize(padded, dst, Size(lv.w.toDouble(), lv.h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        (buf as java.nio.MappedByteBuffer).force()
        padded.release(); src.release(); dst.release()
        tick(tileCount(lv))
        return file
    }

    private fun mapRw(file: File, w: Int, h: Int): Pair<ByteBuffer, Mat> {
        val raf = RandomAccessFile(file, "rw")
        raf.setLength(w.toLong() * h)
        val buf = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, w.toLong() * h)
        raf.close()
        return buf to Mat(h, w, CvType.CV_8UC1, buf)
    }

    private fun mapRo(file: File, w: Int, h: Int): Pair<ByteBuffer, Mat> {
        val buf = CvIndex.mapFile(file)
        return buf to Mat(h, w, CvType.CV_8UC1, buf)
    }

    private fun blocks(lv: Level): List<Rect> {
        val out = ArrayList<Rect>()
        var y = 0
        while (y < lv.h) {
            var x = 0
            while (x < lv.w) {
                out += Rect(x, y, min(BLOCK, lv.w - x), min(BLOCK, lv.h - y))
                x += BLOCK
            }
            y += BLOCK
        }
        return out
    }

    /** Preprocesses a whole gray mosaic blockwise (with halo) into a uint8 file. */
    private fun prepare(mosaic: File, lv: Level, outFile: File, pool: ExecutorService, cancelled: () -> Unit, tick: (Int) -> Unit) {
        val (bin, src) = mapRo(mosaic, lv.w, lv.h)
        val (bout, dst) = mapRw(outFile, lv.w, lv.h)
        val halo = max(32, (6 * max(params.sigma, 8.0)).toInt())
        val jobs = blocks(lv).map { b ->
            Callable {
                cancelled()
                val x0 = max(0, b.x - halo); val y0 = max(0, b.y - halo)
                val x1 = min(lv.w, b.x + b.width + halo); val y1 = min(lv.h, b.y + b.height + halo)
                mats { sc ->
                    val g = sc.mat()
                    src.submat(Rect(x0, y0, x1 - x0, y1 - y0)).convertTo(g, CvType.CV_32F)
                    val rep = sc.mat()
                    Preprocess.represent(g, params.sigma, params.lcnC, rep)
                    val enc = sc.mat()
                    Preprocess.encode(rep, enc)
                    enc.submat(Rect(b.x - x0, b.y - y0, b.width, b.height)).copyTo(dst.submat(b))
                }
                tick(1)
            }
        }
        pool.invokeAll(jobs).forEach { it.get() }
        (bout as java.nio.MappedByteBuffer).force()
        src.release(); dst.release()
        check(bin.capacity() > 0)
    }

    // ----------------------------------------------------------------------------------- floors
    private fun readFloors(geo: MapGeo): List<Floor> {
        val f = pack.mapFile(geo.mapId, "point_groups.json")
        if (f.length() == 0L) return emptyList()
        val list = PackParser.unwrap(PackParser.json.parseToJsonElement(f.readText()), "list") as? JsonArray ?: return emptyList()
        val out = ArrayList<Floor>()
        for (g in list) {
            val go = g as? JsonObject ?: continue
            val gid = go["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
            for (fe in (go["floors"] as? JsonArray).orEmpty()) {
                val fo = fe as? JsonObject ?: continue
                val fid = fo["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val o = fo["overlay"] as? JsonObject ?: continue
                if (fid == 0 || o["l_x"] == null || pack.overlayFile(fid).length() == 0L) continue
                fun d(k: String) = o[k]!!.jsonPrimitive.content.toDouble()
                val fl = Floor(
                    gid, fid, fo["floor_name"]?.jsonPrimitive?.content ?: "",
                    d("l_x") + geo.originX, d("l_y") + geo.originY, d("r_x") + geo.originX, d("r_y") + geo.originY,
                )
                if (min(fl.r - fl.l, fl.b - fl.t) < LayerIndex.MIN_SIDE) continue
                out += fl
            }
        }
        return out
    }

    private fun loadOverlay(fl: Floor): Mat? {
        val raw = Imgcodecs.imread(pack.overlayFile(fl.floorId).path, Imgcodecs.IMREAD_UNCHANGED)
        if (raw.empty()) return null
        val bgra = Mat()
        when (raw.channels()) {
            4 -> raw.copyTo(bgra)
            3 -> Imgproc.cvtColor(raw, bgra, Imgproc.COLOR_BGR2BGRA)
            1 -> Imgproc.cvtColor(raw, bgra, Imgproc.COLOR_GRAY2BGRA)
            else -> { raw.release(); bgra.release(); return null }
        }
        raw.release()
        if (bgra.depth() != CvType.CV_8U) { bgra.convertTo(bgra, CvType.CV_8U) }
        return bgra
    }

    /** Overlay stretched onto its bounds at z0 resolution, composited over the dark background, as gray uint8. */
    private fun overlayGray(fl: Floor, bgra: Mat): Mat = mats { sc ->
        val w = max(2, Math.rint(fl.r - fl.l).toInt())
        val h = max(2, Math.rint(fl.b - fl.t).toInt())
        val interp = if (bgra.cols() > w) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR
        val a = sc.mat()
        Imgproc.resize(bgra, a, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, interp)
        val ch = ArrayList<Mat>(4)
        Core.split(a, ch)
        ch.forEach { sc.track(it) }
        val al = sc.mat()
        ch[3].convertTo(al, CvType.CV_32F, 1.0 / 255.0)
        val inv = sc.mat()
        Core.subtract(Mat(h, w, CvType.CV_32F, Scalar(1.0)).also { sc.track(it) }, al, inv)
        val outCh = ArrayList<Mat>(3)
        for (c in 0..2) {
            val f = sc.mat()
            ch[c].convertTo(f, CvType.CV_32F)
            Core.multiply(f, al, f)
            val bg = sc.mat()
            Core.multiply(inv, Scalar(DARK_BGR[c]), bg)
            Core.add(f, bg, f)
            val u = sc.mat()
            f.convertTo(u, CvType.CV_8U, 1.0, -0.5) // truncate, like numpy astype
            outCh += u
        }
        val bgr = sc.mat()
        Core.merge(outCh, bgr)
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        gray
    }

    fun floorGeom(fl: Floor, z: Int): Level {
        val f = Math.pow(2.0, z.toDouble())
        val ox = floor((fl.l - FLOOR_PAD) * f).toInt()
        val oy = floor((fl.t - FLOOR_PAD) * f).toInt()
        val ex = ceil((fl.r + FLOOR_PAD) * f).toInt()
        val ey = ceil((fl.b + FLOOR_PAD) * f).toInt()
        return Level(z, ox, oy, ex - ox, ey - oy)
    }

    /** Prepared floor reference: surface gray as filler, the floor composite inside its bounds. */
    private fun floorPatch(geo: MapGeo, fl: Floor, overlayGray: Mat, z: Int, lv: Level, mosaic: File): Mat = mats { sc ->
        val sl = levelGeom(geo, z)
        val (_, src) = mapRo(mosaic, sl.w, sl.h)
        val patch = sc.full(lv.h, lv.w, CvType.CV_32F, 22.0)
        val x0 = lv.ox - sl.ox
        val y0 = lv.oy - sl.oy
        val xa = max(0, x0); val ya = max(0, y0)
        val xb = min(sl.w, x0 + lv.w); val yb = min(sl.h, y0 + lv.h)
        if (xb > xa && yb > ya) {
            val s32 = sc.mat()
            src.submat(Rect(xa, ya, xb - xa, yb - ya)).convertTo(s32, CvType.CV_32F)
            s32.copyTo(patch.submat(Rect(xa - x0, ya - y0, xb - xa, yb - ya)))
        }
        src.release()
        val f = Math.pow(2.0, z.toDouble())
        val w = max(2, Math.rint((fl.r - fl.l) * f).toInt())
        val h = max(2, Math.rint((fl.b - fl.t) * f).toInt())
        val gz = sc.mat()
        Imgproc.resize(overlayGray, gz, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, if (f < 1) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)
        val u0 = Math.rint(fl.l * f).toInt() - lv.ox
        val v0 = Math.rint(fl.t * f).toInt() - lv.oy
        val ua = max(0, u0); val va = max(0, v0)
        val ub = min(lv.w, u0 + w); val vb = min(lv.h, v0 + h)
        if (ub > ua && vb > va) {
            val g32 = sc.mat()
            gz.submat(Rect(ua - u0, va - v0, ub - ua, vb - va)).convertTo(g32, CvType.CV_32F)
            g32.copyTo(patch.submat(Rect(ua, va, ub - ua, vb - va)))
        }
        val rep = sc.mat()
        Preprocess.represent(patch, params.sigma, params.lcnC, rep)
        val out = Mat()
        Preprocess.encode(rep, out)
        out
    }

    // ------------------------------------------------------------------------- entrances / targets
    private fun readPoints(geo: MapGeo): List<com.teyvatmap.thor.data.store.PointDto> {
        val pf = pack.mapFile(geo.mapId, "points.json")
        if (pf.length() == 0L) return emptyList()
        return pf.inputStream().use { PackParser.decodePoints(it) }
    }

    /**
     * Entrance index (PLAN §6.2): every point listed in a floor's `entrance_ids` (position -> the floor it leads to)
     * plus each group's own `underground_entrance_x/y_pos` (-> all floors of the group, default floor first).
     * Also returns point id -> linked floor ids (used to match underground Teleport Waypoints against their floor).
     */
    private fun readEntrances(geo: MapGeo, floors: List<Floor>, points: List<com.teyvatmap.thor.data.store.PointDto>): Pair<List<Entrance>, Map<Long, List<Int>>> {
        val f = pack.mapFile(geo.mapId, "point_groups.json")
        if (f.length() == 0L) return emptyList<Entrance>() to emptyMap()
        val list = PackParser.unwrap(PackParser.json.parseToJsonElement(f.readText()), "list") as? JsonArray ?: return emptyList<Entrance>() to emptyMap()
        val valid = floors.associateBy { it.floorId }
        val pos = points.associateBy { it.id }
        val byPoint = LinkedHashMap<Long, MutableList<Int>>()
        val out = ArrayList<Entrance>()
        for (g in list) {
            val go = g as? JsonObject ?: continue
            val gid = go["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
            val gFloors = ArrayList<Int>()
            for (fe in (go["floors"] as? JsonArray).orEmpty()) {
                val fo = fe as? JsonObject ?: continue
                val fid = fo["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
                if (fid !in valid) continue
                gFloors += fid
                for (pid in (fo["entrance_ids"] as? JsonArray).orEmpty()) {
                    val id = pid.jsonPrimitive.content.toDoubleOrNull()?.toLong() ?: continue
                    byPoint.getOrPut(id) { ArrayList(1) } += fid
                }
            }
            val ex = go["underground_entrance_x_pos"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            val ey = go["underground_entrance_y_pos"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            if ((ex != 0.0 || ey != 0.0) && gFloors.isNotEmpty()) {
                val di = (go["default_floor_index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0).coerceIn(0, gFloors.size - 1)
                val ordered = listOf(gFloors[di]) + gFloors.filterIndexed { i, _ -> i != di }
                out += Entrance(ex + geo.originX, ey + geo.originY, ordered, gid)
            }
        }
        val groupOf = floors.associate { it.floorId to it.groupId }
        for ((id, fids) in byPoint) {
            val p = pos[id] ?: continue
            out += Entrance(p.x + geo.originX, p.y + geo.originY, fids.distinct(), groupOf[fids.first()] ?: 0, id)
        }
        return out to byPoint
    }

    private fun readTargets(geo: MapGeo, points: List<com.teyvatmap.thor.data.store.PointDto>, linkOf: Map<Long, List<Int>>): Targets {
        val lf = pack.mapFile(geo.mapId, "labels.json")
        if (lf.length() == 0L || points.isEmpty()) return Targets.EMPTY
        val labels = PackParser.parseLabelTree(PackParser.json.parseToJsonElement(lf.readText()))
        val root = labels.firstOrNull { it.id == WAYPOINTS_LABEL && it.parentId == 0 } ?: return Targets.EMPTY
        val kids = labels.groupBy { it.parentId }
        val kindOf = HashMap<Int, Int>()
        fun visit(id: Int) {
            val l = labels.first { it.id == id }
            val nm = l.name.lowercase()
            kindOf[id] = Targets.KINDS.indexOf(
                when {
                    "statue" in nm -> "statue"
                    nm.startsWith("domain") -> "domain"
                    "teleport" in nm -> "waypoint"
                    else -> "other"
                },
            )
            kids[id].orEmpty().forEach { visit(it.id) }
        }
        visit(root.id)
        val xy = ArrayList<Double>()
        val kd = ArrayList<Byte>()
        val lk = ArrayList<Int>()
        points.forEach { p ->
            val k = kindOf[p.labelId] ?: return@forEach
            xy += p.x + geo.originX
            xy += p.y + geo.originY
            kd += k.toByte()
            lk += linkOf[p.id]?.firstOrNull() ?: 0
        }
        return Targets(xy.toDoubleArray(), kd.toByteArray(), lk.toIntArray())
    }

    companion object {
        const val SEA_GRAY = 22
        const val BLOCK = 1024
        const val FLOOR_PAD = 220.0
        const val WAYPOINTS_LABEL = 1
        private val DARK_BGR = doubleArrayOf(24.0, 20.0, 16.0)
    }
}
