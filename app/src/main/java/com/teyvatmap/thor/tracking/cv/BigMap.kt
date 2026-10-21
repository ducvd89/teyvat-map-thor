package com.teyvatmap.thor.tracking.cv

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features.BFMatcher
import org.opencv.features.SIFT
import org.opencv.geometry.Geometry
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Geometry of a mosaic stitched from whole tiles (the CORRECT origin): mosaic pixel (0, 0) is level-[z] pixel
 * (`tx0 * 256`, `ty0 * 256`), i.e. zoom-0 pixel (`tx0 * 256 / 2^z`, ...), NOT the unrounded corner of the requested box.
 * (A quick analysis script once used the box corner and was off by up to a tile, which showed up as a tens-of-px
 * "offset" between the in-game map and the HoYoLAB data; there is none.)
 */
data class MosaicGeom(val z: Int, val tx0: Int, val ty0: Int, val tx1: Int, val ty1: Int) {
    val f: Double get() = 2.0.pow(z)
    val w: Int get() = (tx1 - tx0 + 1) * TILE
    val h: Int get() = (ty1 - ty0 + 1) * TILE

    fun toZ0(u: Double, v: Double) = P2((tx0 * TILE + u) / f, (ty0 * TILE + v) / f)
    fun fromZ0(x: Double, y: Double) = P2(x * f - tx0 * TILE, y * f - ty0 * TILE)

    companion object {
        const val TILE = 256

        /** Tiles of level [z] covering the zoom-0 box (l, t, r, b), clamped to the map (`geo`). */
        fun forBox(geo: MapGeo, z: Int, l: Double, t: Double, r: Double, b: Double): MosaicGeom {
            val f = 2.0.pow(z)
            val nx = ceil(geo.totalW * f / TILE).toInt()
            val ny = ceil(geo.totalH * f / TILE).toInt()
            val tx0 = floor(l * f / TILE).toInt().coerceIn(0, nx - 1)
            val ty0 = floor(t * f / TILE).toInt().coerceIn(0, ny - 1)
            val tx1 = floor(r * f / TILE).toInt().coerceIn(tx0, nx - 1)
            val ty1 = floor(b * f / TILE).toInt().coerceIn(ty0, ny - 1)
            return MosaicGeom(z, tx0, ty0, tx1, ty1)
        }
    }
}

/** A gray mosaic of tiles ([mat] is owned by the caller) and where it sits. */
class Mosaic(val mat: Mat, val geom: MosaicGeom) : AutoCloseable {
    /** SIFT features of the (CLAHE'd) mosaic, computed on first use and reused for every query scale. */
    var keypoints: Array<org.opencv.core.KeyPoint>? = null
    var descriptors: Mat? = null

    override fun close() { mat.release(); descriptors?.release(); descriptors = null; keypoints = null }
}

object TileMosaic {
    const val SEA_GRAY = 22.0

    /** Stitches the tiles of [geom]; a missing or blank tile is sea gray. [tileFile] gives the webp of (z, tx, ty). */
    fun build(geom: MosaicGeom, tileFile: (Int, Int, Int) -> File): Mosaic {
        val m = Mat(geom.h, geom.w, CvType.CV_8UC1, Scalar(SEA_GRAY))
        for (ty in geom.ty0..geom.ty1) for (tx in geom.tx0..geom.tx1) {
            val f = tileFile(geom.z, tx, ty)
            if (f.length() < 100) continue
            val img = Imgcodecs.imread(f.path, Imgcodecs.IMREAD_GRAYSCALE)
            if (!img.empty()) {
                val x = (tx - geom.tx0) * MosaicGeom.TILE
                val y = (ty - geom.ty0) * MosaicGeom.TILE
                val w = min(img.cols(), MosaicGeom.TILE); val h = min(img.rows(), MosaicGeom.TILE)
                val sub = m.submat(Rect(x, y, w, h))
                img.submat(Rect(0, 0, w, h)).copyTo(sub)
                sub.release()
            }
            img.release()
        }
        return Mosaic(m, geom)
    }
}

/**
 * The in-game big map (key M) is recognised by its fixed UI frame: the controller-glyph bar at the bottom (X disc, gear, LT,
 * RT, A, B, RS) is matched in a strip. The glyphs shift sideways with the label lengths ("Chon" vs "Danh Dau"), so the whole
 * strip is searched; >= 5 of 7 glyphs at TM_CCOEFF_NORMED >= 0.65 means big map. On the 450 real frames: 2 of 2 big-map frames,
 * 0 of 448 others (max 0 of 7 glyphs). The minimap ring is absent on the big map (checked by the caller).
 */
class BigMapDetector {
    private class Glyph(val name: String, val mat: Mat)

    private val glyphs: List<Glyph> by lazy {
        Cv.init()
        NAMES.mapNotNull { n ->
            val b = BigMapDetector::class.java.classLoader?.getResourceAsStream("cv/bigmap_ui/$n.png")?.use { it.readBytes() } ?: return@mapNotNull null
            val raw = org.opencv.core.MatOfByte(*b)
            val m = Imgcodecs.imdecode(raw, Imgcodecs.IMREAD_COLOR)
            raw.release()
            if (m.empty()) null else Glyph(n, m)
        }
    }

    val available: Boolean get() = glyphs.size >= NAMES.size

    /** How many of the glyphs were found in the strip of a full BGR 1920x1080 frame (scaled strips for other sizes are not supported). */
    fun glyphsFound(frameBgr: Mat, thr: Double = 0.65): Int {
        if (glyphs.isEmpty() || frameBgr.cols() != 1920 || frameBgr.rows() != 1080) return 0
        var n = 0
        mats { sc ->
            val strip = sc.track(frameBgr.submat(Rect(0, STRIP_Y0, 1920, STRIP_Y1 - STRIP_Y0)))
            val res = sc.mat()
            for (g in glyphs) {
                Imgproc.matchTemplate(strip, g.mat, res, Imgproc.TM_CCOEFF_NORMED)
                if (Core.minMaxLoc(res).maxVal >= thr) n++
            }
        }
        return n
    }

    fun isBigMap(frameBgr: Mat): Boolean = glyphsFound(frameBgr) >= MIN_GLYPHS

    /**
     * Layer mode of the big map: the game draws a floor overlay over the darkened surface and a column of white layer-selector
     * discs (6-7, ~77 px apart, area 220-470 px) at the right edge. The normal big map has no white blobs there (real frames: 7 in layer mode, 0 in 6 others).
     */
    fun layerSelectorVisible(frameBgr: Mat): Boolean {
        if (frameBgr.cols() != 1920 || frameBgr.rows() != 1080) return false
        return mats { sc ->
            val roi = sc.track(frameBgr.submat(Rect(SEL_X0, SEL_Y0, SEL_X1 - SEL_X0, SEL_Y1 - SEL_Y0)))
            val hsv = sc.mat()
            Imgproc.cvtColor(roi, hsv, Imgproc.COLOR_BGR2HSV)
            val m = sc.mat()
            Core.inRange(hsv, Scalar(0.0, 0.0, 225.0), Scalar(180.0, 45.0, 255.0), m)
            val lab = sc.mat(); val st = sc.mat(); val ce = sc.mat()
            val n = Imgproc.connectedComponentsWithStats(m, lab, st, ce, 8)
            val a = IntArray(n * 5); st.get(0, 0, a)
            (1 until n).count { a[it * 5 + Imgproc.CC_STAT_AREA] in 150..700 } >= SEL_MIN_BLOBS
        }
    }

    /**
     * Reads the floor selector of layer mode (right edge, x 1785..1870): a "surface" icon on top (large, y ~300) and one tile per floor
     * (~77 px apart, white ~270 px blobs; the current floor's tile is blue and carries a small person badge). Real frame (Fortress of Meropide):
     * white tiles at y 389/466/542/619/772, blue tile at 696 with badge blobs at x 1846 => n = 6, highlighted = 5. Tiles are in the data order
     * of the floor group (top to bottom). Null when the panel is not there (the normal surface map). [FloorSelector.highlighted] 0 = the surface icon is the blue one.
     */
    fun floorSelector(frameBgr: Mat): FloorSelector? {
        if (frameBgr.cols() != 1920 || frameBgr.rows() != 1080 || !layerSelectorVisible(frameBgr)) return null
        return mats { sc ->
            val roi = sc.track(frameBgr.submat(Rect(SEL_X0, SEL_Y0, SEL_X1 - SEL_X0, SEL_Y1 - SEL_Y0)))
            val hsv = sc.mat()
            Imgproc.cvtColor(roi, hsv, Imgproc.COLOR_BGR2HSV)
            // each blob: cx, cy (frame px), area, width, height
            fun blobs(lo: Scalar, hi: Scalar, minArea: Int): List<DoubleArray> {
                val m = sc.mat(); Core.inRange(hsv, lo, hi, m)
                val lab = sc.mat(); val st = sc.mat(); val ce = sc.mat()
                val n = Imgproc.connectedComponentsWithStats(m, lab, st, ce, 8)
                val a = IntArray(n * 5); st.get(0, 0, a)
                val c = DoubleArray(n * 2); ce.get(0, 0, c)
                return (1 until n).filter { a[it * 5 + Imgproc.CC_STAT_AREA] >= minArea }.map {
                    doubleArrayOf(SEL_X0 + c[it * 2], SEL_Y0 + c[it * 2 + 1], a[it * 5 + Imgproc.CC_STAT_AREA].toDouble(), a[it * 5 + Imgproc.CC_STAT_WIDTH].toDouble(), a[it * 5 + Imgproc.CC_STAT_HEIGHT].toDouble())
                }
            }
            val white = blobs(Scalar(0.0, 0.0, 225.0), Scalar(180.0, 45.0, 255.0), 150).filter { it[2] <= 400 && it[3] <= 40 }
            val blue = blobs(Scalar(95.0, 90.0, 170.0), Scalar(115.0, 255.0, 255.0), 60)
            // the lit icon is blue but its animation splits it into pieces (and the first frames are dimmer): any blue piece in the icon column counts
            val lit = blue.filter { it[0] in SEL_ICON_X0..SEL_ICON_X1 && it[2] >= 100 }
            val litMain = lit.maxByOrNull { it[2] }?.let { m -> lit.filter { abs(it[1] - m[1]) <= 40 } }.orEmpty()
            val litY = if (litMain.isEmpty()) null else litMain.sumOf { it[1] * it[2] } / litMain.sumOf { it[2] }
            val badge = blue.filter { it[0] >= SEL_BADGE_X && it[2] < 300 }
            val slots = (white.filter { it[1] >= SEL_FLOOR_Y0 }.map { it[1] } + listOfNotNull(litY?.takeIf { it >= SEL_FLOOR_Y0 })).sorted()
            // merge duplicate detections of one tile
            val ys = ArrayList<Double>()
            for (y in slots) if (ys.isEmpty() || y - ys.last() > 30) ys += y
            // a tile that was neither white nor found as the lit one (rare): the pitch is regular, so fill the gaps
            if (ys.size >= 2) {
                val pitch = ys.zipWithNext { a, b -> b - a }.sorted().let { it[it.size / 2] }
                var i = 1
                while (i < ys.size) {
                    val gap = ys[i] - ys[i - 1]
                    if (pitch > 40 && gap > 1.6 * pitch) { val k = Math.rint(gap / pitch).toInt(); for (j in 1 until k) ys.add(i - 1 + j, ys[i - 1] + gap * j / k); i += k } else i++
                }
            }
            if (ys.isEmpty()) return@mats null
            fun slotOf(y: Double): Int = ys.indices.minBy { abs(ys[it] - y) } + 1
            val highlighted = when {
                litY == null -> -1
                litY < SEL_FLOOR_Y0 -> 0
                else -> slotOf(litY)
            }
            // the person badge sits below-right of the tile centre: the player's own floor (differs from the lit tile when another floor is being viewed)
            val player = if (badge.isEmpty()) -1 else slotOf(badge.map { it[1] }.average() - SEL_BADGE_DY)
            FloorSelector(ys.size, highlighted, player)
        }
    }

    companion object {
        const val SEL_FLOOR_Y0 = 345; const val SEL_BADGE_X = 1838; const val SEL_ICON_X0 = 1800.0; const val SEL_ICON_X1 = 1836.0; const val SEL_BADGE_DY = 18.0
        val NAMES = listOf("X", "gear", "LT", "RT", "A", "B", "RS")
        const val STRIP_Y0 = 984
        const val STRIP_Y1 = 1056
        const val MIN_GLYPHS = 5
        const val SEL_X0 = 1785; const val SEL_Y0 = 270; const val SEL_X1 = 1870; const val SEL_Y1 = 830
        const val SEL_MIN_BLOBS = 4
    }
}

/**
 * The floor selector of the big map in layer mode: [n] floor tiles (without the surface icon), the lit tile ([highlighted], 1-based, 0 = the surface icon,
 * -1 = unknown) and the tile that carries the player badge ([player], -1 = no badge seen).
 */
class FloorSelector(val n: Int, val highlighted: Int, val player: Int) {
    /** The tile that tells the player's floor: the badge, else the lit tile. */
    val index: Int get() = if (player > 0) player else highlighted
    override fun toString() = "n=$n highlighted=$highlighted player=$player"
}

/** A floor selector resolved against the data: the group and (when the tile is known) the floor; a null group is the surface. */
class SelectedFloor(val groupId: Int?, val floor: Floor?)

/**
 * Selector tile -> floor (pure logic). The tiles are the floors of one group in `point_groups.json` order (top to bottom); the group is the one
 * that the registration matched, else the one whose zone holds the player / view centre and has exactly [FloorSelector.n] floors.
 */
object FloorSelectorRules {
    fun resolve(layers: LayerIndex, sel: FloorSelector, points: List<P2>, regGroup: Int?): SelectedFloor {
        val idx = sel.index
        if (idx == 0) return SelectedFloor(null, null)
        val near = layers.zoneRect.filter { (_, r) -> points.any { it.x >= r[0] - 60 && it.x <= r[2] + 60 && it.y >= r[1] - 60 && it.y <= r[3] + 60 } }.keys
        val gid = when {
            regGroup != null && layers.groups[regGroup]?.size == sel.n -> regGroup
            else -> near.firstOrNull { layers.groups[it]?.size == sel.n }
                ?: regGroup ?: near.firstOrNull()
        } ?: return SelectedFloor(null, null)
        val fl = layers.groups[gid]?.getOrNull(idx - 1)?.takeIf { idx > 0 }
        return SelectedFloor(gid, fl)
    }
}

/** Result of one big-map registration. Positions in zoom-0 px; [xPos]/[yPos] are game coordinates of the player (or null when no arrow was found). */
class BigMapFix(
    val player: P2?,
    val xPos: Double?,
    val yPos: Double?,
    /** Map position of the screen centre = the cursor pin (zoom-0 px). */
    val center: P2,
    /** Zoom-0 px per screen px of the in-game zoom. */
    val z0PerPx: Double,
    val inliers: Int,
    val level: Int,
    val ms: Double,
    val arrowScreen: P2?,
    val headingDeg: Double?,
    val how: String,
    /** The map this registration belongs to (the locator's geo). */
    val mapId: Int = 0,
    /** Floor group whose overlays the registration matched (layer mode, the player is on one of its floors), or null for the surface. */
    val groupId: Int? = null,
    /** Why the player arrow is missing (candidates found / best dart-shape confidence), for the log. */
    val arrowNote: String = "",
)

/** Where to look: a position (zoom-0 px) with the radius it is trusted to. */
class BigMapHint(val center: P2, val radius: Double, val label: String)

/**
 * Registers a big-map screenshot to the HoYoLAB tiles with SIFT + RANSAC similarity (PLAN §6.0): the big map is the same
 * artwork as the tiles but its zoom is user-controlled, so a scale-invariant method is needed. The player arrow's screen position
 * then gives the exact player position (state "Fixed from map").
 *
 * Search order: every hint (last position, banner area, teleport destination) as a z-1 box of radius + 1600 z0 px (the real
 * report: SIFT needs a regional prior; with a few thousand px it still works because the big map is feature rich); without a
 * hint or when they fail, a whole-land z-3 pass (screen assumed 1.5 z0 px per px, then 0.75), refined by a z-1 box around
 * the coarse centre. UI areas, the cursor ring and the arrow are masked; pins/labels are outliers for RANSAC.
 */
class BigMapLocator(
    private val geo: MapGeo,
    private val tileFile: (Int, Int, Int) -> File,
    /** Floors of this map and their overlay images: with both, layer-mode frames are registered against floor-group mosaics (surface + overlays). */
    private val layers: LayerIndex = LayerIndex.EMPTY,
    private val overlayFile: ((Int) -> File)? = null,
) : AutoCloseable {
    private val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
    private var coarse: Mosaic? = null
    private var lastRegion: Pair<MosaicGeom, Mosaic>? = null
    // layer mode: group mosaics (surface + the group's overlays) with their SIFT features, a few kept; the groups are tried in priority order across calls
    private val floorMosaics = LinkedHashMap<Int, Mosaic>()
    private var floorOrder: List<Int> = emptyList()
    private var floorCursor = 0

    /** True while layer-mode registration still has floor groups to try (the caller retries soon instead of cycling the other maps). */
    val floorsPending: Boolean get() = floorOrder.isNotEmpty() && floorCursor < floorOrder.size

    private class Query(val sImg: Double, val kp: Array<org.opencv.core.KeyPoint>, val desc: Mat)

    private val queries = HashMap<Long, Query>()
    private var queryOwner: Mat? = null

    /** The player arrow of the big map: centroid (screen px), or null. Cyan, H 88..104, S >= 110, V >= 225, area ~1000 px. */
    fun findArrow(bgr: Mat): P2? = arrowCandidates(bgr, false, false).firstOrNull()

    /**
     * Cyan blobs that can be the player's dart, closest to the usual area first. [layerMode]: the floor selector column is excluded and the
     * floor overlay's glow may merge with the dart, so [relaxed] widens the colour and area limits.
     */
    fun arrowCandidates(bgr: Mat, layerMode: Boolean, relaxed: Boolean): List<P2> = mats { sc ->
        val x1 = if (layerMode) SEL_EXCLUDE_X else ARROW_X1
        val roi = sc.track(bgr.submat(Rect(ARROW_X0, ARROW_Y0, x1 - ARROW_X0, ARROW_Y1 - ARROW_Y0)))
        val hsv = sc.mat()
        Imgproc.cvtColor(roi, hsv, Imgproc.COLOR_BGR2HSV)
        val m = sc.mat()
        if (relaxed) Core.inRange(hsv, Scalar(85.0, 80.0, 200.0), Scalar(108.0, 255.0, 255.0), m)
        else Core.inRange(hsv, Scalar(88.0, 110.0, 225.0), Scalar(104.0, 255.0, 255.0), m)
        val lab = sc.mat(); val st = sc.mat(); val ce = sc.mat()
        val n = Imgproc.connectedComponentsWithStats(m, lab, st, ce, 8)
        val s = IntArray(n * 5); st.get(0, 0, s)
        val c = DoubleArray(n * 2); ce.get(0, 0, c)
        val minA = if (relaxed) ARROW_MIN_AREA * 2 / 3 else ARROW_MIN_AREA
        val maxA = if (relaxed) ARROW_MAX_AREA * 3 / 2 else ARROW_MAX_AREA
        val maxS = if (relaxed) 90 else 70
        (1 until n).filter {
            val a = s[it * 5 + Imgproc.CC_STAT_AREA]
            a in minA..maxA && s[it * 5 + Imgproc.CC_STAT_WIDTH] <= maxS && s[it * 5 + Imgproc.CC_STAT_HEIGHT] <= maxS
        }.sortedBy { abs(s[it * 5 + Imgproc.CC_STAT_AREA] - ARROW_AREA) }.take(4).map { P2(ARROW_X0 + c[it * 2], ARROW_Y0 + c[it * 2 + 1]) }
    }

    /** The first candidate that looks like the dart (heading template match >= [ARROW_MIN_CONF]), with its shape; [note] says what was seen. */
    private fun pickArrow(bgr: Mat, layerMode: Boolean): Triple<P2?, Heading.Result?, String> {
        var seen = 0; var bestConf = -1.0
        for (relaxed in listOf(false, true)) {
            val cands = arrowCandidates(bgr, layerMode, relaxed)
            seen = max(seen, cands.size)
            for (c in cands) {
                val sh = arrowShape(bgr, c)
                if (sh != null) bestConf = max(bestConf, sh.confidence)
                if (sh?.deg != null && sh.confidence >= ARROW_MIN_CONF) return Triple(c, sh, "")
            }
        }
        return Triple(null, null, "candidates=$seen bestConf=%.2f (min %.2f)".format(bestConf, ARROW_MIN_CONF))
    }

    /** Heading of the big-map arrow (deg clockwise from north) via the minimap's rotation template, or null. */
    fun arrowHeading(bgr: Mat, at: P2): Double? = mats { sc ->
        val patch = sc.mat()
        Imgproc.getRectSubPix(bgr, Size(75.0, 75.0), Point(at.x - 0.5, at.y - 0.5), patch)
        val small = sc.mat()
        Imgproc.resize(patch, small, Size(48.0, 48.0), 0.0, 0.0, Imgproc.INTER_AREA)
        ArrowHeading.estimate(small)?.deg
    }

    /** Like [arrowHeading] with the template score: a blob that does not look like the player's dart has no result. */
    fun arrowShape(bgr: Mat, at: P2): Heading.Result? = mats { sc ->
        val patch = sc.mat()
        Imgproc.getRectSubPix(bgr, Size(75.0, 75.0), Point(at.x - 0.5, at.y - 0.5), patch)
        val small = sc.mat()
        Imgproc.resize(patch, small, Size(48.0, 48.0), 0.0, 0.0, Imgproc.INTER_AREA)
        ArrowHeading.estimate(small)
    }

    /** Anchor of the player given the arrow centroid and its heading: the dart is drawn around a pivot that is not its centroid. */
    fun playerFromArrow(arrow: P2, headingDeg: Double?): P2 {
        val rot = if (headingDeg == null) 0.0 else Math.toRadians(headingDeg - ARROW_OFFSET_HEADING)
        val ox = ARROW_OFFSET_X * cos(rot) - ARROW_OFFSET_Y * sin(rot)
        val oy = ARROW_OFFSET_X * sin(rot) + ARROW_OFFSET_Y * cos(rot)
        return P2(arrow.x - ox, arrow.y - oy)
    }

    private fun queryFor(gray: Mat, mask: Mat, sImg: Double): Query = queries.getOrPut(java.lang.Double.doubleToLongBits(sImg)) {
        val g = Mat(); val ms = Mat()
        if (sImg < 1.0) {
            Imgproc.resize(gray, g, Size(), sImg, sImg, Imgproc.INTER_AREA)
            Imgproc.resize(mask, ms, Size(g.cols().toDouble(), g.rows().toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
        } else { gray.copyTo(g); mask.copyTo(ms) }
        val eq = Mat(); clahe.apply(g, eq)
        val kp = MatOfKeyPoint(); val d = Mat()
        SIFT.create(QUERY_FEATURES, 3, 0.02).detectAndCompute(eq, ms, kp, d)
        val arr = kp.toArray()
        kp.release(); g.release(); ms.release(); eq.release()
        Query(sImg, arr, d)
    }

    private class Reg(val a: DoubleArray, val inliers: Int, val good: Int, val sImg: Double, val geom: MosaicGeom, val z0PerPx: Double, val rotDeg: Double)

    /** SIFT of [ref] vs the query at [sImg]; null when no consistent similarity is found. */
    private fun register(q: Query, mosaic: Mosaic): Reg? {
        if (q.kp.size < 12 || q.desc.empty()) return null
        if (mosaic.keypoints == null) {
            val eq = Mat(); clahe.apply(mosaic.mat, eq)
            val kp = MatOfKeyPoint(); val d = Mat()
            SIFT.create(REF_FEATURES, 3, 0.02).detectAndCompute(eq, Mat(), kp, d)
            mosaic.keypoints = kp.toArray(); mosaic.descriptors = d
            kp.release(); eq.release()
        }
        val rk = mosaic.keypoints!!
        val d = mosaic.descriptors!!
        return mats { sc ->
            if (rk.size < 12 || d.empty()) return@mats null
            val knn = ArrayList<MatOfDMatch>()
            BFMatcher.create(Core.NORM_L2).knnMatch(q.desc, d, knn, 2)
            val good = ArrayList<DMatch>()
            for (mm in knn) { val a = mm.toArray(); if (a.size == 2 && a[0].distance < RATIO * a[1].distance) good += a[0] }
            knn.forEach { it.release() }
            if (good.size < 8) return@mats null
            val src = MatOfPoint2f(*good.map { q.kp[it.queryIdx].pt }.toTypedArray()).also { sc.track(it) }
            val dst = MatOfPoint2f(*good.map { rk[it.trainIdx].pt }.toTypedArray()).also { sc.track(it) }
            val inl = sc.mat()
            val a = Geometry.estimateAffinePartial2D(src, dst, inl, Geometry.RANSAC, 3.0, 5000, 0.999)
            sc.track(a)
            if (a.empty()) return@mats null
            val v = DoubleArray(6); a.get(0, 0, v)
            val n = Core.countNonZero(inl)
            val scale = hypot(v[0], v[3])
            val rot = Math.toDegrees(atan2(v[3], v[0]))
            val z0PerPx = scale * q.sImg / mosaic.geom.f           // A maps resized-screen px (screen * sImg) to mosaic px: mosaic px per screen px = scale * sImg
            android.util.Log.d("BigMap", "z=${mosaic.geom.z} sImg=%.3f kp q=${q.kp.size} ref=${rk.size} good=${good.size} inl=$n rot=%.2f z0/px=%.3f".format(q.sImg, rot, z0PerPx))
            val ok = n >= MIN_INLIERS && n >= MIN_INLIER_FRAC * good.size && abs(rot) <= 3.0 && z0PerPx in 0.2..8.0
            if (ok) Reg(v, n, good.size, q.sImg, mosaic.geom, z0PerPx, rot) else null
        }
    }

    /** Map position (zoom-0 px) of a screen point under a registration. */
    private fun toZ0(r: Reg, x: Double, y: Double): P2 {
        val sx = x * r.sImg; val sy = y * r.sImg
        val u = r.a[0] * sx + r.a[1] * sy + r.a[2]
        val v = r.a[3] * sx + r.a[4] * sy + r.a[5]
        return r.geom.toZ0(u, v)
    }

    private fun regionMosaic(z: Int, c: P2, halfZ0: Double): Mosaic {
        val g = MosaicGeom.forBox(geo, z, c.x - halfZ0, c.y - halfZ0, c.x + halfZ0, c.y + halfZ0)
        lastRegion?.let { (lg, lm) -> if (lg == g) return lm else lm.close() }
        val m = TileMosaic.build(g, tileFile)
        lastRegion = g to m
        return m
    }

    private fun coarseMosaic(): Mosaic = coarse ?: run {
        // only the land bounding box: the sea around it is blank tiles
        val l = geo.padX.toDouble(); val t = geo.padY.toDouble()
        val land = MosaicGeom.forBox(geo, COARSE_Z, l, t, l + geo.origW, t + geo.origH)
        TileMosaic.build(land, tileFile).also { coarse = it }
    }

    /** Group mosaic: surface tiles of the zone (+ margin) with every floor overlay of the group painted over it, SIFT cached; null without overlays. */
    private fun floorGroupMosaic(groupId: Int): Mosaic? {
        floorMosaics.remove(groupId)?.let { floorMosaics[groupId] = it; return it }        // LRU touch
        val of = overlayFile ?: return null
        val fls = layers.groups[groupId] ?: return null
        val r = layers.zoneRect[groupId] ?: return null
        val g = MosaicGeom.forBox(geo, FLOOR_Z, r[0] - FLOOR_MARGIN, r[1] - FLOOR_MARGIN, r[2] + FLOOR_MARGIN, r[3] + FLOOR_MARGIN)
        val m = TileMosaic.build(g, tileFile)
        for (f in fls) paintOverlay(m, f, of(f.floorId))
        floorMosaics[groupId] = m
        while (floorMosaics.size > FLOOR_CACHE) floorMosaics.remove(floorMosaics.keys.first())?.close()
        return m
    }

    /** Alpha-blends the overlay image of [f] (stretched onto its bounds) into the gray mosaic. */
    private fun paintOverlay(m: Mosaic, f: Floor, file: File) {
        if (file.length() == 0L) return
        val raw = Imgcodecs.imread(file.path, Imgcodecs.IMREAD_UNCHANGED)
        if (raw.empty()) { raw.release(); return }
        val fz = m.geom.f
        val w = max(2, Math.rint((f.r - f.l) * fz).toInt()); val h = max(2, Math.rint((f.b - f.t) * fz).toInt())
        val x0 = Math.rint(f.l * fz).toInt() - m.geom.tx0 * MosaicGeom.TILE
        val y0 = Math.rint(f.t * fz).toInt() - m.geom.ty0 * MosaicGeom.TILE
        val xa = max(0, x0); val ya = max(0, y0); val xb = min(m.mat.cols(), x0 + w); val yb = min(m.mat.rows(), y0 + h)
        if (xb > xa && yb > ya) mats { sc ->
            val rs = sc.mat()
            Imgproc.resize(raw, rs, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, if (raw.cols() > w) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)
            val gray = sc.mat(); val alpha = sc.mat()
            when (rs.channels()) {
                4 -> {
                    val ch = ArrayList<Mat>(4); Core.split(rs, ch); ch.forEach { sc.track(it) }
                    val bgr = sc.mat(); Core.merge(ch.subList(0, 3), bgr); Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
                    ch[3].convertTo(alpha, CvType.CV_32F, 1.0 / 255.0)
                }
                3 -> { Imgproc.cvtColor(rs, gray, Imgproc.COLOR_BGR2GRAY); alpha.create(h, w, CvType.CV_32F); alpha.setTo(Scalar(1.0)) }
                else -> { rs.copyTo(gray); alpha.create(h, w, CvType.CV_32F); alpha.setTo(Scalar(1.0)) }
            }
            val rect = Rect(xa, ya, xb - xa, yb - ya)
            val srcRect = Rect(xa - x0, ya - y0, xb - xa, yb - ya)
            val dst = sc.track(m.mat.submat(rect))
            val g32 = sc.mat(); sc.track(gray.submat(srcRect)).convertTo(g32, CvType.CV_32F)
            val a32 = sc.track(alpha.submat(srcRect))
            val d32 = sc.mat(); dst.convertTo(d32, CvType.CV_32F)
            val inv = sc.mat(); Core.subtract(Mat(a32.rows(), a32.cols(), CvType.CV_32F, Scalar(1.0)).also { sc.track(it) }, a32, inv)
            Core.multiply(g32, a32, g32); Core.multiply(d32, inv, d32); Core.add(g32, d32, g32)
            g32.convertTo(dst, CvType.CV_8U)
        }
        raw.release()
    }

    /** Floor groups by closeness of their zone to the hints (a stale hint still orders them; without hints the index order). */
    private fun floorGroupsByHint(hints: List<BigMapHint>, sel: FloorSelector? = null): List<Int> {
        // the selector shows as many tiles as the group has floors: only those groups when any exists
        val zr = layers.zoneRect.let { all -> if (sel == null) all else all.filterKeys { layers.groups[it]?.size == sel.n }.ifEmpty { all } }
        fun d(gid: Int): Double {
            val r = zr.getValue(gid)
            val cx = (r[0] + r[2]) / 2; val cy = (r[1] + r[3]) / 2
            return hints.minOfOrNull { max(0.0, hypot(cx - it.center.x, cy - it.center.y) - it.radius) } ?: 0.0
        }
        return zr.keys.sortedBy { d(it) }
    }

    private fun maskOf(gray: Mat, arrow: P2?, layerMode: Boolean = false): Mat {
        val m = Mat(gray.rows(), gray.cols(), CvType.CV_8UC1, Scalar(255.0))
        for (r in UI_RECTS) Imgproc.rectangle(m, Point(r[0].toDouble(), r[1].toDouble()), Point(r[2].toDouble(), r[3].toDouble()), Scalar(0.0), -1)
        if (layerMode) Imgproc.rectangle(m, Point(1740.0, 230.0), Point(1919.0, 860.0), Scalar(0.0), -1)      // the floor selector column
        Imgproc.circle(m, Point(gray.cols() / 2.0, gray.rows() / 2.0), CURSOR_R.toInt(), Scalar(0.0), -1)      // cursor ring (+ arrow when centred)
        if (arrow != null) Imgproc.circle(m, Point(arrow.x, arrow.y), 34, Scalar(0.0), -1)
        return m
    }

    /**
     * Which floor's art does the big map show? In layer mode the game draws the highlighted floor's overlay over the darkened surface. With the
     * registration [fix] (screen centre = [BigMapFix.center], [BigMapFix.z0PerPx] zoom-0 px per screen px, no rotation) each candidate overlay is
     * stretched onto the screen, and its opaque part is compared with the screenshot (masked NCC of the blurred gray, +-[ART_SHIFT] px and +-3 % zoom
     * of slack; UI, selector, cursor and arrow are masked). Returns floorId -> score (0 when too little of the overlay is in view).
     */
    fun floorArt(frameBgr: Mat, fix: BigMapFix, cands: List<Floor>): Map<Int, Double> {
        val of = overlayFile ?: return emptyMap()
        val out = LinkedHashMap<Int, Double>()
        mats { sc ->
            val gray = sc.mat(); Imgproc.cvtColor(frameBgr, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(), 1.0)
            val g32 = sc.mat(); gray.convertTo(g32, CvType.CV_32F)
            val ui = sc.track(maskOf(gray, fix.arrowScreen, true))
            val W = frameBgr.cols(); val H = frameBgr.rows()
            for (f in cands) {
                val file = of(f.floorId)
                if (file.length() == 0L) { out[f.floorId] = 0.0; continue }
                val raw = sc.track(Imgcodecs.imread(file.path, Imgcodecs.IMREAD_UNCHANGED))
                if (raw.empty() || raw.channels() != 4) { out[f.floorId] = 0.0; continue }
                var best = 0.0
                for (zoomK in ART_ZOOMS) {
                    val k = zoomK / fix.z0PerPx
                    val w = Math.rint((f.r - f.l) * k).toInt(); val h = Math.rint((f.b - f.t) * k).toInt()
                    if (w < 20 || h < 20 || w > 4000 || h > 4000) continue
                    val x0 = Math.rint(W / 2.0 + (f.l - fix.center.x) * k).toInt(); val y0 = Math.rint(H / 2.0 + (f.t - fix.center.y) * k).toInt()
                    // the part of the overlay in view, shrunk by the shift slack (the template), and the screen rect it is searched in
                    val vx0 = max(0, x0); val vy0 = max(0, y0); val vx1 = min(W, x0 + w); val vy1 = min(H, y0 + h)
                    val tx0 = vx0 + ART_SHIFT; val ty0 = vy0 + ART_SHIFT; val tx1 = vx1 - ART_SHIFT; val ty1 = vy1 - ART_SHIFT
                    if (tx1 - tx0 < 40 || ty1 - ty0 < 40) continue
                    val rs = sc.mat(); Imgproc.resize(raw, rs, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, if (raw.cols() > w) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)
                    val ch = ArrayList<Mat>(4); Core.split(rs, ch); ch.forEach { sc.track(it) }
                    val bgr = sc.mat(); Core.merge(ch.subList(0, 3), bgr)
                    val og = sc.mat(); Imgproc.cvtColor(bgr, og, Imgproc.COLOR_BGR2GRAY); Imgproc.GaussianBlur(og, og, Size(), 1.0)
                    val tRect = Rect(tx0 - x0, ty0 - y0, tx1 - tx0, ty1 - ty0)
                    val tmpl = sc.mat(); sc.track(og.submat(tRect)).convertTo(tmpl, CvType.CV_32F)
                    val opaque = sc.mat(); Imgproc.threshold(sc.track(ch[3].submat(tRect)), opaque, 128.0, 255.0, Imgproc.THRESH_BINARY)
                    val valid = sc.mat(); Core.bitwise_and(opaque, sc.track(ui.submat(Rect(tx0, ty0, tx1 - tx0, ty1 - ty0))), valid)
                    if (Core.countNonZero(valid) < ART_MIN_PIXELS) continue
                    val mask = sc.mat(); valid.convertTo(mask, CvType.CV_32F, 1.0 / 255.0)
                    val area = sc.track(g32.submat(Rect(vx0, vy0, vx1 - vx0, vy1 - vy0)))
                    val res = sc.mat()
                    Imgproc.matchTemplate(area, tmpl, res, Imgproc.TM_CCOEFF_NORMED, mask)
                    val mm = Core.minMaxLoc(res)
                    if (mm.maxVal.isFinite() && mm.maxVal <= 1.0) best = max(best, mm.maxVal)
                }
                out[f.floorId] = best
            }
        }
        return out
    }

    /** Where the last registration landed, so the next one of the same open map starts there (the view is tracked, not searched again). */
    private class View(val groupId: Int?, val geom: MosaicGeom, val z0PerPx: Double, val how: String)
    private var view: View? = null

    /** The map was closed: the next registration searches from scratch. */
    fun resetView() { view = null; floorCursor = floorOrder.size }

    private fun mosaicOf(v: View): Mosaic? = when {
        v.groupId != null -> floorMosaics[v.groupId]
        v.geom.z == COARSE_Z -> coarse
        else -> lastRegion?.takeIf { it.first == v.geom }?.second
    }

    /**
     * Registers [frameBgr] (full 1920x1080 big-map frame). Returns null when nothing consistent is found. Layer mode ([layerMode], with the
     * floor selector [sel] when read): the floor-group mosaics (surface + overlays) are tried first, groups with as many floors as the selector
     * has tiles and nearest to the hints first; while the map stays open the previous registration's mosaic is tried first at its exact zoom.
     */
    fun locate(frameBgr: Mat, hints: List<BigMapHint>, layerMode: Boolean = false, sel: FloorSelector? = null): BigMapFix? {
        val t0 = System.nanoTime()
        val gray = Mat(); Imgproc.cvtColor(frameBgr, gray, Imgproc.COLOR_BGR2GRAY)
        val (validArrow, shape, arrowNote) = pickArrow(frameBgr, layerMode)
        val mask = maskOf(gray, validArrow ?: arrowCandidates(frameBgr, layerMode, true).firstOrNull(), layerMode)
        queries.values.forEach { it.desc.release() }; queries.clear()
        try {
            var reg: Reg? = null
            var how = ""
            var group: Int? = null
            // 0. the view of the last registration (same open map): its mosaic at its zoom
            view?.let { v ->
                val m = mosaicOf(v)
                if (m != null) {
                    val r = register(queryFor(gray, mask, v.z0PerPx * 2.0.pow(v.geom.z)), m)
                    if (r != null) { reg = r; how = "view:${v.how.removePrefix("view:")}"; group = v.groupId }
                }
                if (reg == null) view = null
            }
            // 1. layer mode: the floor overlay hides the surface under it, so each floor group's mosaic (surface + overlays) is tried first, nearest
            //    to the hints first; a call works through as many groups as fit the budget and the next call goes on
            val floorsOn = layerMode && overlayFile != null && layers.floors.isNotEmpty()
            if (reg == null && floorsOn) {
                if (floorCursor >= floorOrder.size) { floorOrder = floorGroupsByHint(hints, sel); floorCursor = 0 }
                while (reg == null && floorCursor < floorOrder.size && (System.nanoTime() - t0) / 1e6 < FLOOR_BUDGET_MS) {
                    val gid = floorOrder[floorCursor++]
                    val m = floorGroupMosaic(gid) ?: continue
                    for (sigma in SIGMAS) {
                        val r = register(queryFor(gray, mask, sigma * 2.0.pow(FLOOR_Z)), m)
                        if (r != null) { reg = r; how = "floors:$gid"; group = gid; break }
                    }
                }
                if (reg != null) floorCursor = floorOrder.size
            }
            // 2. hints: z-1 box around each, two assumed zooms (in layer mode only after the floor groups)
            if (reg == null && !(floorsOn && floorsPending)) outer@ for (h in hints) {
                val half = h.radius + HINT_MARGIN
                for (sigma in SIGMAS) {
                    val z = -1
                    val s = sigma * 2.0.pow(z)
                    val m = regionMosaic(z, h.center, half)
                    val r = register(queryFor(gray, mask, s), m)
                    if (r != null) { reg = r; how = "hint:${h.label}"; break@outer }
                }
            }
            // 3. whole land at z-3, then a z-1 refinement around the coarse centre (in layer mode only after every floor group was tried)
            if (reg == null && !(floorsOn && floorsPending)) {
                for (sigma in SIGMAS) {
                    val m = coarseMosaic()
                    val r = register(queryFor(gray, mask, sigma * 2.0.pow(COARSE_Z)), m)
                    if (r != null) {
                        val c = toZ0(r, frameBgr.cols() / 2.0, frameBgr.rows() / 2.0)
                        val extent = r.z0PerPx * frameBgr.cols() / 2.0 + 400.0
                        val fine = register(queryFor(gray, mask, (r.z0PerPx * 0.5).coerceIn(0.1, 1.0)), regionMosaic(-1, c, extent))
                        reg = fine ?: r
                        how = if (fine != null) "coarse+fine" else "coarse"
                        break
                    }
                }
            }
            val r = reg ?: return null
            view = View(group, r.geom, r.z0PerPx, how)
            val cx = frameBgr.cols() / 2.0; val cy = frameBgr.rows() / 2.0
            // the player position comes from the arrow only; a cyan blob that does not look like the dart (no heading template match) is no arrow
            val heading = validArrow?.let { shape?.deg }
            val anchor = validArrow?.let { playerFromArrow(it, heading) }
            val player = anchor?.let { toZ0(r, it.x, it.y) }
            val g = player?.let { geo.toPos(it.x, it.y) }
            return BigMapFix(player, g?.x, g?.y, toZ0(r, cx, cy), r.z0PerPx, r.inliers, r.geom.z, (System.nanoTime() - t0) / 1e6, validArrow, heading, how, geo.mapId, group, arrowNote)
        } finally {
            gray.release(); mask.release()
            queries.values.forEach { it.desc.release() }; queries.clear()
        }
    }

    override fun close() {
        coarse?.close(); coarse = null
        lastRegion?.second?.close(); lastRegion = null
        floorMosaics.values.forEach { it.close() }; floorMosaics.clear()
    }

    companion object {
        const val COARSE_Z = -3   // z-2 over the whole land (25 MP) needs > 1 GB for SIFT: the emulator was killed; z-3 is 1.5 MP
        const val QUERY_FEATURES = 4000
        const val REF_FEATURES = 4000
        const val RATIO = 0.78
        const val MIN_INLIERS = 15
        const val MIN_INLIER_FRAC = 0.35
        const val HINT_MARGIN = 1500.0
        /** Layer mode: group mosaics at z-1 around the floor zone + this margin (z0 px, surface context for the SIFT match), time per call, cache size. */
        const val FLOOR_Z = -1
        const val FLOOR_MARGIN = 400.0
        const val FLOOR_BUDGET_MS = 1800.0
        const val FLOOR_CACHE = 4
        /** Assumed z0 px per screen px of the in-game zoom: the two zooms seen so far (1.5 default, 0.78 zoomed in); SIFT tolerates ~1.6x. */
        val SIGMAS = listOf(1.5, 0.75)
        const val CURSOR_R = 70.0
        /** [floorArt]: shift slack (screen px), zoom slack, least overlay pixels (opaque and not UI) that make a score. */
        const val ART_SHIFT = 10
        const val ART_MIN_PIXELS = 1500
        val ART_ZOOMS = listOf(0.97, 1.0, 1.03)
        // arrow search area (excludes the HUD list on the left, the top bar and the bottom bar)
        const val ARROW_X0 = 140; const val ARROW_Y0 = 110; const val ARROW_X1 = 1900; const val ARROW_Y1 = 880
        /** Layer mode: the floor selector column starts here (its lit tile is cyan-blue like the dart). */
        const val SEL_EXCLUDE_X = 1770
        /** Minimum [ArrowHeading] confidence of the arrow blob (the real dart scores high; icons and UI glyphs of the same hue do not). */
        const val ARROW_MIN_CONF = 0.2
        const val ARROW_AREA = 990; const val ARROW_MIN_AREA = 700; const val ARROW_MAX_AREA = 1400
        /** Centroid minus true player anchor in screen px at heading [ARROW_OFFSET_HEADING] (measured on bm1..bm6 against the minimap match). */
        const val ARROW_OFFSET_X = 1.3
        const val ARROW_OFFSET_Y = 2.6
        const val ARROW_OFFSET_HEADING = 328.3
        /** Parts of the big-map screen that are UI (quest list, resource bar, bottom bar, labels): masked for the feature search. */
        val UI_RECTS = listOf(intArrayOf(0, 0, 520, 420), intArrayOf(0, 420, 140, 700), intArrayOf(600, 0, 1920, 110), intArrayOf(0, 960, 1920, 1080), intArrayOf(1250, 860, 1920, 1080))
    }
}
