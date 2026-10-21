package com.teyvatmap.thor.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.teyvatmap.thor.R
import com.teyvatmap.thor.data.PointDetails
import com.teyvatmap.thor.data.PointInfo
import com.teyvatmap.thor.data.db.FloorEntity
import com.teyvatmap.thor.data.db.LabelEntity
import com.teyvatmap.thor.data.db.PointEntity
import com.teyvatmap.thor.data.db.UserMarkEntity
import com.teyvatmap.thor.data.prefs.Settings
import com.teyvatmap.thor.data.store.PackPaths
import com.teyvatmap.thor.data.update.DownloadState
import com.teyvatmap.thor.data.update.UpdateUiState
import com.teyvatmap.thor.domain.LabelHit
import com.teyvatmap.thor.domain.LabelNode
import com.teyvatmap.thor.domain.PointHit
import com.teyvatmap.thor.domain.SearchResults
import com.teyvatmap.thor.sync.SyncPhase
import com.teyvatmap.thor.sync.SyncUiState
import com.teyvatmap.thor.tracking.CvIndexUiState
import com.teyvatmap.thor.tracking.TrackingStatus
import com.teyvatmap.thor.tracking.cv.TrackState
import com.teyvatmap.thor.ui.map.ChromeState
import com.teyvatmap.thor.ui.map.IconCache
import com.teyvatmap.thor.ui.map.LabelIcon
import com.teyvatmap.thor.ui.map.MapChoice
import com.teyvatmap.thor.ui.map.MapChrome
import com.teyvatmap.thor.ui.map.MapPanel
import com.teyvatmap.thor.ui.map.MapViewModel
import com.teyvatmap.thor.ui.map.SheetState
import com.teyvatmap.thor.ui.map.ChromeActions
import com.teyvatmap.thor.ui.map.UndoAction
import com.teyvatmap.thor.ui.settings.SettingsActions
import com.teyvatmap.thor.ui.settings.SettingsContent
import com.teyvatmap.thor.ui.settings.SettingsUi
import com.teyvatmap.thor.ui.theme.TeyvatTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * JVM screenshots of the UI at the Thor bottom screen size (1240x1080 px, xhdpi = 620x540 dp, landscape) with fake
 * state and a painted stand-in for the map. PNGs go to build/ui-shots (copied out by hand).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w620dp-h540dp-land-xhdpi", application = android.app.Application::class)
class UiScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val out = File("build/ui-shots").also { it.mkdirs() }
    private fun shot(name: String) = rule.onRoot().captureRoboImage(File(out, "$name.png").path)

    // ------------------------------------------------------------------------------------------- fakes
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val icons = object : IconCache(PackPaths(ApplicationProvider.getApplicationContext())) {
        override suspend fun get(labelId: Int): ImageBitmap = fakeIcon(labelId)
    }

    private fun fakeIcon(id: Int): ImageBitmap {
        val colors = intArrayOf(0xFF4FC3F7.toInt(), 0xFFF4C95D.toInt(), 0xFFB48CF2.toInt(), 0xFF7BD389.toInt(), 0xFFE5736B.toInt(), 0xFF9AA7C4.toInt())
        val bmp = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF10182C.toInt(); c.drawCircle(36f, 36f, 34f, p)
        p.color = colors[id % colors.size]
        if (id % 3 == 0) c.drawCircle(36f, 36f, 22f, p)
        else if (id % 3 == 1) c.drawRoundRect(RectF(15f, 15f, 57f, 57f), 8f, 8f, p)
        else c.drawPath(Path().apply { moveTo(36f, 11f); lineTo(60f, 56f); lineTo(12f, 56f); close() }, p)
        return bmp.asImageBitmap()
    }

    private val tree = listOf(
        LabelNode(1, "Oculi", null, 400, listOf(
            LabelNode(11, "Anemoculus", null, 66, emptyList()),
            LabelNode(12, "Geoculus", null, 131, emptyList()),
            LabelNode(13, "Electroculus", null, 181, emptyList()),
        )),
        LabelNode(2, "Chests", null, 400, listOf(
            LabelNode(21, "Common Chest", null, 1127, emptyList()),
            LabelNode(22, "Exquisite Chest", null, 603, emptyList()),
            LabelNode(23, "Precious Chest", null, 206, emptyList()),
            LabelNode(24, "Luxurious Chest", null, 122, emptyList()),
        )),
        LabelNode(3, "Teleporters", null, 400, listOf(
            LabelNode(31, "Teleport Waypoint", null, 308, emptyList()),
            LabelNode(32, "Statue of the Seven", null, 21, emptyList()),
        )),
        LabelNode(4, "Challenges", null, 400, listOf(
            LabelNode(41, "Domain", null, 52, emptyList()),
            LabelNode(42, "Time Trial Challenge", null, 47, emptyList()),
        )),
        LabelNode(5, "Local Specialties", null, 400, listOf(LabelNode(51, "Cecilia", null, 80, emptyList()))),
    )

    private val maps = listOf(MapChoice(2, "Teyvat Interactive Map"), MapChoice(7, "The Chasm Interactive Map"), MapChoice(9, "Enkanomiya Interactive Map"))
    private val floors = listOf(
        FloorEntity(1, 2, 1, "The Chasm: Underground Mines", null, 0.0, 0.0, 0.0, 0.0, 0),
        FloorEntity(2, 2, 1, "Mt. Hulao Cave", null, 0.0, 0.0, 0.0, 0.0, 0),
    )

    private fun point(floor: Int? = null) = PointEntity(
        id = 4821, mapId = 2, labelId = 22, x = 1830.0, y = -1210.0, areaId = 4, zLevel = 0, groupId = null, floorId = floor,
        displayState = 0, ctime = 0, detailVersion = null, removedAt = null,
    )
    private fun sheet() = SheetState.Shown(
        PointInfo(point(), LabelEntity(22, 2, 2, "Exquisite Chest", null, 0, 603, true), "Liyue · Mt. Hulao", null),
        PointDetails("Located on the ledge behind the waterfall. Glide from the tall pine to reach it, or climb the rock face from the east side.", emptyList()),
    )

    private val trackingOn = TrackingStatus(capturing = true, tracking = true)

    /** Stand-in for the map: terrain gradient, a few rivers and some marker icons. */
    @androidx.compose.runtime.Composable
    private fun FakeMap(content: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2B3A33), Color(0xFF3B4A3A), Color(0xFF2C3B45))))) {
            ComposeCanvas(Modifier.fillMaxSize()) {
                for (i in 0..12) drawLine(Color(0x14FFFFFF), androidx.compose.ui.geometry.Offset(i * size.width / 12f, 0f), androidx.compose.ui.geometry.Offset(i * size.width / 12f, size.height), 2f)
                for (i in 0..10) drawLine(Color(0x14FFFFFF), androidx.compose.ui.geometry.Offset(0f, i * size.height / 10f), androidx.compose.ui.geometry.Offset(size.width, i * size.height / 10f), 2f)
                val river = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, size.height * 0.7f); cubicTo(size.width * 0.3f, size.height * 0.4f, size.width * 0.5f, size.height * 0.9f, size.width, size.height * 0.45f)
                }
                drawPath(river, Color(0x664A8FB5), style = androidx.compose.ui.graphics.drawscope.Stroke(26f))
            }
            val pts = listOf(60 to 120, 110 to 300, 160 to 200, 220 to 340, 260 to 150, 300 to 260, 340 to 90, 400 to 210, 430 to 330, 150 to 400, 90 to 440, 330 to 420)
            pts.forEachIndexed { i, (x, y) -> Box(Modifier.offset(x.dp, y.dp)) { LabelIcon(icons, 11 + i % 9, 26.dp, Modifier.then(if (i % 4 == 0) Modifier.background(Color.Transparent) else Modifier)) } }
            // the player arrow
            ComposeCanvas(Modifier.offset(250.dp, 230.dp).size(28.dp)) {
                drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(size.width / 2, 0f); lineTo(size.width, size.height); lineTo(size.width / 2, size.height * 0.72f); lineTo(0f, size.height); close() }, Color(0xFFF4C95D))
            }
            content()
        }
    }

    private fun render(state: ChromeState, name: String) {
        rule.setContent { TeyvatTheme { FakeMap { MapChrome(state, ChromeActions(), icons) } } }
        rule.waitForIdle()
        shot(name)
    }

    private val base = ChromeState(
        maps = maps, currentMapId = 2, tree = tree, enabled = setOf(11, 12, 21, 22, 31, 32, 41),
        floors = floors, sync = SyncUiState(SyncPhase.IDLE, account = "player@example.com"),
        hasOpenApp = true,
    )

    // ------------------------------------------------------------------------------------------- shots
    @Test fun map_idle() = render(base.copy(), "01_map_idle")

    @Test fun map_tracking() = render(
        base.copy(track = trackingOn, trackState = TrackState.TRACKING, hasPosition = true, following = true, gameModeAvailable = false),
        "02_map_tracking_following",
    )

    @Test fun map_tracking_recenter() = render(
        base.copy(track = trackingOn, trackState = TrackState.TRACKING, hasPosition = true, following = false, activeFloorId = 2, floorAuto = true),
        "03_map_tracking_underground_recenter",
    )

    @Test fun map_finding() = render(
        base.copy(track = trackingOn, trackState = TrackState.RELOCATING, gameModeAvailable = true, hasOpenApp = false, sync = SyncUiState(SyncPhase.SYNCING)),
        "04_map_finding_position",
    )

    @Test fun map_menu() = render(
        base.copy(track = trackingOn, trackState = TrackState.DOMAIN, panel = MapPanel.MENU, canMoveToOtherScreen = true, sync = SyncUiState(SyncPhase.ERROR, error = "x")),
        "05_map_menu_domain",
    )

    @Test fun map_maps_panel() = render(base.copy(panel = MapPanel.MAPS), "06_map_switcher")
    @Test fun map_layers_panel() = render(base.copy(panel = MapPanel.FLOORS, activeFloorId = 2), "07_map_layers")

    private val stackFloors = listOf(
        FloorEntity(41, 2, 19, "Mountain Cavern", null, 0.0, 0.0, 0.0, 0.0, 0),
        FloorEntity(107, 2, 19, "Mountain Cavern: F2", null, 0.0, 0.0, 0.0, 0.0, 0),
        FloorEntity(108, 2, 19, "Mountain Cavern: F3", null, 0.0, 0.0, 0.0, 0.0, 0),
        FloorEntity(109, 2, 19, "Mountain Cavern: F4", null, 0.0, 0.0, 0.0, 0.0, 0),
    )
    private val stackOrder = mapOf(41 to 0, 107 to 1, 108 to 2, 109 to 3)

    private fun layerState(active: Int?) = base.copy(
        track = trackingOn, trackState = TrackState.TRACKING, hasPosition = true, following = true,
        floors = stackFloors, activeFloorId = active, floorAuto = active != null,
        floorNav = com.teyvatmap.thor.domain.FloorNav.nav(stackFloors, active, 19, stackOrder),
    )

    @Test fun map_layer_buttons_middle() = render(layerState(107), "15_layer_buttons_f2")
    @Test fun map_layer_buttons_surface() = render(layerState(null), "16_layer_buttons_surface")
    @Test fun map_layer_buttons_bottom() = render(layerState(109), "17_layer_buttons_bottom")

    /** The real Thor bottom screen: 1240x1080 px at ~369 dpi = about 538x468 dp. */
    @Test @Config(qualifiers = "w538dp-h468dp-land-369dpi") fun map_layer_buttons_real_density() = render(layerState(108), "18_layer_buttons_real_density")
    @Test @Config(qualifiers = "w538dp-h468dp-land-369dpi") fun map_real_density_tracking() = render(
        base.copy(track = trackingOn, trackState = TrackState.TRACKING, hasPosition = true, gameModeAvailable = true), "19_rail_real_density",
    )

    @Test fun map_nearby() = render(
        base.copy(
            track = trackingOn, trackState = TrackState.TRACKING, hasPosition = true, following = true, panel = MapPanel.NEARBY,
            nearby = listOf(
                MapViewModel.NearbyItem(1, 22, "Exquisite Chest", 84.0, 30.0),
                MapViewModel.NearbyItem(2, 12, "Geoculus", 130.0, 150.0),
                MapViewModel.NearbyItem(3, 31, "Teleport Waypoint", 210.0, 250.0),
            ),
        ),
        "08_map_nearby",
    )

    @Test fun filter_drawer() = render(base.copy(panel = MapPanel.FILTER, hideFound = true, expandedGroups = setOf(2)), "10_filter_drawer")

    @Test fun point_sheet() = render(base.copy(sheet = sheet(), respawnHours = 0), "11_point_sheet")

    @Test fun point_sheet_found_undo() = render(
        base.copy(
            sheet = sheet(), sheetMark = UserMarkEntity(4821, "FOUND", System.currentTimeMillis(), "d", null),
            undo = UndoAction("Marked as found", null, null),
        ),
        "12_point_sheet_found",
    )

    @Test fun search() = render(
        base.copy(
            searchOpen = true, query = "chest",
            results = SearchResults(
                labels = listOf(LabelHit(21, "Common Chest", null, 1127), LabelHit(22, "Exquisite Chest", null, 603), LabelHit(23, "Precious Chest", null, 206)),
                points = listOf(PointHit(1, 2, 22, "Exquisite Chest", null, "Behind the waterfall, glide from the pine tree.")),
            ),
        ),
        "13_search",
    )

    @Test fun map_dense_rail_small_density() = render(
        base.copy(track = trackingOn, trackState = TrackState.TRACKING, hasPosition = true, gameModeAvailable = true, nearby = emptyList()),
        "14_rail_full",
    )

    private val settingsUi = SettingsUi(
        settings = Settings(), download = DownloadState.Idle, import = ImportState.Idle, points = 29431,
        update = UpdateUiState(lastChecked = 1_760_000_000_000, lastUpdated = 1_759_900_000_000, lastSummary = "3 points added"),
        sync = SyncUiState(SyncPhase.IDLE, lastSynced = 1_760_000_000_000, account = "player@example.com"),
        syncMessage = null, cv = CvIndexUiState(), cvBytes = 412_000_000, dataMessage = null,
        backupFiles = listOf("marks-2026-10-21.json", "marks-2026-10-20.json"), packBytes = 1_800_000_000, packPath = "/sdcard/Android/data/com.teyvatmap.thor/files/pack",
        canMoveToOtherScreen = true,
        overlay = com.teyvatmap.thor.dualscreen.OverlayControls(available = true, granted = true, start = {}, requestPermission = {}),
    )

    @Test @Config(qualifiers = "w620dp-h540dp-land-xhdpi") fun settings_top() {
        rule.setContent { TeyvatTheme { SettingsContent(settingsUi, SettingsActions()) } }
        rule.waitForIdle(); shot("20_settings_top")
    }

    @Test @Config(qualifiers = "w620dp-h1900dp-xhdpi") fun settings_full() {
        rule.setContent { TeyvatTheme { SettingsContent(settingsUi, SettingsActions()) } }
        rule.waitForIdle(); shot("21_settings_full")
    }

    @Test @Config(qualifiers = "w620dp-h540dp-land-xhdpi") fun icon_sheet() {
        val all = TIcons::class.java.declaredFields.filter { it.type == Int::class.javaPrimitiveType && !it.name.contains("$") }
        rule.setContent {
            TeyvatTheme {
                Box(Modifier.fillMaxSize().padding(14.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        all.forEach { f ->
                            f.isAccessible = true
                            val res = f.getInt(null)
                            Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(56.dp).background(Color(0xFF142039), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                                    TIcon(res, null, tint = Color(0xFFF4C95D), size = 32.dp)
                                }
                                Text(f.name.removePrefix("get").removePrefix("INSTANCE"), fontSize = 9.sp, color = Color(0xFFA9B2C7), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle(); shot("30_icon_set")
    }

    @Test fun launcher_icon() {
        val size = 432
        val dr = ctx.getDrawable(R.mipmap.ic_launcher)!!
        fun render(mask: Path?, bg: Int): Bitmap {
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawColor(bg)
            if (mask != null) c.clipPath(mask)
            dr.setBounds(0, 0, size, size); dr.draw(c)
            return b
        }
        val circle = Path().apply { addCircle(size / 2f, size / 2f, size / 3f, Path.Direction.CW) }
        val squircle = Path().apply { addRoundRect(RectF(size / 6f, size / 6f, size * 5 / 6f, size * 5 / 6f), size * 0.15f, size * 0.15f, Path.Direction.CW) }
        // adaptive icons draw 108dp with a 72dp visible window: show full layers, circle mask and rounded-square mask side by side
        val sheet = Bitmap.createBitmap(size * 3, size, Bitmap.Config.ARGB_8888)
        val sc = Canvas(sheet)
        sc.drawBitmap(render(null, 0xFF202020.toInt()), 0f, 0f, null)
        sc.drawBitmap(render(circle, 0xFF202020.toInt()), size.toFloat(), 0f, null)
        sc.drawBitmap(render(squircle, 0xFF202020.toInt()), size * 2f, 0f, null)
        File(out, "30_launcher_icon.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
