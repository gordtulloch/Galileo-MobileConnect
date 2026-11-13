package com.galileo.mobileconnect.ui.sky

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galileo.mobileconnect.SessionTarget
import com.galileo.mobileconnect.core.sky.SkyGeometry
import com.galileo.mobileconnect.core.state.double
import com.galileo.mobileconnect.core.state.get
import com.galileo.mobileconnect.core.state.string
import com.galileo.mobileconnect.ui.ActionButton
import com.galileo.mobileconnect.ui.ActionKind
import com.galileo.mobileconnect.ui.ButtonRow
import com.galileo.mobileconnect.ui.Card
import com.galileo.mobileconnect.ui.LocalApp
import com.galileo.mobileconnect.ui.Note
import com.galileo.mobileconnect.ui.OperationButton
import com.galileo.mobileconnect.ui.OutcomeNote
import com.galileo.mobileconnect.ui.ScreenScroll
import com.galileo.mobileconnect.ui.Spinner
import com.galileo.mobileconnect.ui.ThemedSlider
import com.galileo.mobileconnect.ui.TextBox
import com.galileo.mobileconnect.ui.Txt
import com.galileo.mobileconnect.ui.isWide
import com.galileo.mobileconnect.ui.minimumTarget
import com.galileo.mobileconnect.ui.theme.LocalCompact
import com.galileo.mobileconnect.ui.theme.LocalTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Where the object menu is: where the object was tapped, or beside a search result, wherever that has come to be. */
private sealed interface MenuPlace {
    data class At(val point: Offset) : MenuPlace
    data object BesideSelection : MenuPlace
}

/** The sky above the Observatory, from the Master's data: no catalog here, and no ephemeris (MAPP-SKY-*, MAPP-STR-*). */
@Composable
fun SkyScreen() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val scope = rememberCoroutineScope()
    var sky by remember { mutableStateOf<SkyModel?>(null) }

    LaunchedEffect(app.workingPier, app.snapshot.piers.isNotEmpty()) {
        sky?.stop()
        val context = app.skyContext()
        sky = context?.let { (session, pier) -> SkyModel(session, pier, app, app.sessionScope) }
        sky?.start(Instant.now(), app.snapshot.capabilities?.skyStreamRate)
    }
    // Nobody is looking: don't keep asking the Master.
    DisposableEffect(Unit) { onDispose { sky?.stop() } }

    val model = sky
    val unavailable = when {
        app.snapshot.capabilities?.skyStreamAvailable == false -> {
            val reason = app.snapshot.capabilities?.skyStreamReason
            if (reason == "master_hardware") "This Master's computer isn't fast enough to stream the sky." else reason ?: "This Master doesn't offer the sky view."
        }
        model?.siteMissing == true -> "This Observatory has no location set. Set one on the desktop to see its sky."
        else -> null
    }
    when {
        unavailable != null -> ScreenScroll("Sky") { Card { Txt(unavailable) } }
        model != null && model.siteFailed -> ScreenScroll("Sky") {
            Card {
                Txt("The sky data couldn't be fetched from the Master.")
                Note("Galileo may need updating, or the link may have dropped.")
                ActionButton("Try again", kind = ActionKind.Prominent, tag = "sky.retry") {
                    scope.launch { model.start(Instant.now(), app.snapshot.capabilities?.skyStreamRate) }
                }
            }
        }
        model != null -> SkyContent(model)
        app.snapshot.piers.isEmpty() && app.snapshot.isConnected -> ScreenScroll("Sky") { Card { Txt("There is no Pier to show yet. The Master has not offered one.") } }
        else -> ScreenScroll("Sky") { Spinner() }
    }
}

@Composable
private fun SkyContent(sky: SkyModel) {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val beside = isWide()
    var panelOpen by remember { mutableStateOf(app.pref("sky.panel.pinned", "false") == "true") }
    var panelPinned by remember { mutableStateOf(app.pref("sky.panel.pinned", "false") == "true") }
    var menu by remember { mutableStateOf<MenuPlace?>(null) }

    // The sky gets the whole screen unless asked otherwise: the panel slides over it when wanted, and only takes room
    // from it if it's been told to stay.
    Box(Modifier.fillMaxSize().background(theme.background)) {
        val picture: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize()) {
                SkyCanvas(
                    sky,
                    onTap = { hit ->
                        // Touching something asks about it, in a menu beside it. Touching nothing clears the way to the sky.
                        menu = hit?.let { MenuPlace.At(it) }
                        if (hit == null && !panelPinned) panelOpen = false
                    },
                    onMoved = { menu = null },
                )
                ObjectMenu(sky, menu)
                if (!panelOpen) {
                    Box(Modifier.align(Alignment.TopEnd).padding(8.dp).width(minimumTarget)) {
                        ActionButton("☰", tag = "sky.panel", description = "Search, time and display") { panelOpen = true }
                    }
                }
                Captions(sky, Modifier.align(Alignment.BottomCenter))
            }
        }
        if (beside) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { picture() }
                if (panelOpen && panelPinned) SkyPanelFrame(sky, true, panelPinned, { panelPinned = it; app.setPref("sky.panel.pinned", it.toString()) }, { panelOpen = false }) { menu = MenuPlace.BesideSelection }
            }
            if (panelOpen && !panelPinned) {
                Box(Modifier.align(Alignment.TopEnd)) {
                    SkyPanelFrame(sky, true, panelPinned, { panelPinned = it; app.setPref("sky.panel.pinned", it.toString()) }, { panelOpen = false }) {
                        menu = MenuPlace.BesideSelection
                        panelOpen = false
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().heightIn(min = 280.dp)) { picture() }
                if (panelOpen && panelPinned) SkyPanelFrame(sky, false, panelPinned, { panelPinned = it; app.setPref("sky.panel.pinned", it.toString()) }, { panelOpen = false }) { menu = MenuPlace.BesideSelection }
            }
            if (panelOpen && !panelPinned) {
                Box(Modifier.align(Alignment.BottomCenter)) {
                    SkyPanelFrame(sky, false, panelPinned, { panelPinned = it; app.setPref("sky.panel.pinned", it.toString()) }, { panelOpen = false }) {
                        menu = MenuPlace.BesideSelection
                        panelOpen = false
                    }
                }
            }
        }
    }
}

/** What must stay visible while the panel is away: that this isn't now, and whose picture it is. */
@Composable
private fun Captions(sky: SkyModel, modifier: Modifier) {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault())
    Row(modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
        if (sky.timeOffset != 0.0) {
            Txt(
                "Not now: ${formatter.format(sky.time(app.now))}", weight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(theme.surface).padding(6.dp).testTag("sky.notnow"),
            )
        }
        Box(Modifier.weight(1f))
        val credit = sky.survey.credit
        val lightPage = app.theme == com.galileo.mobileconnect.ui.theme.ThemeChoice.Standard && theme.background.red > 0.5f
        if (sky.showSurvey && credit != null && !lightPage) Txt(credit, size = 11.sp, color = theme.textSecondary, modifier = Modifier.testTag("sky.credit"))
    }
}

@Composable
private fun SkyPanelFrame(
    sky: SkyModel, beside: Boolean, pinned: Boolean, onPin: (Boolean) -> Unit, onClose: () -> Unit, onPicked: () -> Unit,
) {
    val theme = LocalTheme.current
    CompositionLocalProvider(LocalCompact provides true) {
        Column(
            (if (beside) Modifier.width(280.dp).fillMaxHeight() else Modifier.fillMaxWidth().heightIn(max = 340.dp)).background(theme.background),
        ) {
            Row(Modifier.padding(8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.weight(1f)) { ActionButton(if (pinned) "Auto-hide" else "Keep open", tag = "sky.panel.pin") { onPin(!pinned) } }
                if (!pinned) Box(Modifier.weight(1f)) { ActionButton("Hide", tag = "sky.panel.close", onClick = onClose) }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) { SkyPanel(sky, onPicked) }
        }
    }
}

/** Beside the object, on whichever side has the room, and kept on screen. */
@Composable
private fun ObjectMenu(sky: SkyModel, menu: MenuPlace?) {
    val app = LocalApp.current
    if (menu == null || sky.selection == null) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val width = 230.dp
        val boxWidth = constraints.maxWidth.toFloat()
        val boxHeight = constraints.maxHeight.toFloat()
        val at = when (menu) {
            is MenuPlace.At -> menu.point
            MenuPlace.BesideSelection -> placeOfSelection(sky, app.now, boxWidth, boxHeight)
        }
        val inTopHalf = at.y < boxHeight / 2
        val x = with(density) { (min(max(8f, at.x + 14f), max(8f, boxWidth - width.toPx() - 8f))).toDp() }
        Box(
            Modifier.fillMaxSize().padding(start = x),
            contentAlignment = if (inTopHalf) Alignment.TopStart else Alignment.BottomStart,
        ) {
            val yOffset = with(density) { if (inTopHalf) (at.y + 14f).toDp() else (boxHeight - at.y + 14f).toDp() }
            Box(Modifier.width(width).padding(top = if (inTopHalf) yOffset else 0.dp, bottom = if (inTopHalf) 0.dp else yOffset)) {
                CompositionLocalProvider(LocalCompact provides true) { ObjectMenuContent(sky) }
            }
        }
    }
}

private fun min(a: Float, b: Float) = if (a < b) a else b
private fun max(a: Float, b: Float) = if (a > b) a else b

/** On screen even if the object isn't: one below the horizon can be out of the view's reach. */
private fun placeOfSelection(sky: SkyModel, now: Instant, w: Float, h: Float): Offset {
    val middle = Offset(w / 2, h / 2)
    val selection = sky.selection ?: return middle
    val site = sky.site ?: return middle
    val place = SkyGeometry.horizontal(selection.raDeg, selection.decDeg, site, sky.time(now))
    val point = sky.camera.project(place.altitude, place.azimuth, h.toDouble()) ?: return middle
    return Offset(min(w, max(0f, middle.x + point.x.toFloat())), min(h, max(0f, middle.y - point.y.toFloat())))
}

/** What the Star Atlas offers on an object, in the same order (MAPP-SKY-100). A Viewer gets the name and the altitude. */
@Composable
private fun ObjectMenuContent(sky: SkyModel) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val selection = sky.selection ?: return
    var goToMessage by remember(selection.name) { mutableStateOf<String?>(null) }
    val args: Map<String, JsonElement> = mapOf("ra_deg" to JsonPrimitive(selection.raDeg), "dec_deg" to JsonPrimitive(selection.decDeg))
    Card(selection.name) {
        if (selection.details.isNotEmpty()) Note(selection.details.joinToString(" · "))
        selection.check?.let { check ->
            Txt("Altitude now: %.1f°".format(check.altitude), size = 13.sp, weight = FontWeight.SemiBold, modifier = Modifier.testTag("sky.altitude"))
            check.reason?.let { Note(it) }
        }
        if (app.canOperate) {
            ActionButton("Goto", kind = ActionKind.Prominent, tag = "sky.goto") {
                scope.launch { goToMessage = app.goTo(selection.name, selection.raDeg, selection.decDeg) }
            }
            val record = app.record("mount.slew")
            when {
                goToMessage != null -> Note(goToMessage!!)
                record != null -> OutcomeNote(record)
            }
            OperationButton("Goto and Centre", "solve.centre", args + ("name" to JsonPrimitive(selection.name)))
            OperationButton("Sync", "mount.sync", args)
            ActionButton("Add to Session", tag = "sky.addsession") {
                app.sessionTarget = SessionTarget(selection.name, selection.raDeg, selection.decDeg)
                app.requestedArea = "Sessions"
            }
        }
    }
}

/** Search, time and the selected target. */
@Composable
private fun SkyPanel(sky: SkyModel, onPicked: () -> Unit) {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card("Find") {
            Box(Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(RoundedCornerShape(7.dp)).background(theme.raised).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                TextBox(
                    sky.query, { sky.query = it }, "Name or designation", label = "Search the sky", tag = "sky.search",
                    modifier = Modifier.fillMaxWidth(), onDone = { scope.launch { sky.search() } },
                )
            }
            sky.results.forEachIndexed { index, obj ->
                ActionButton(obj["name"].string ?: "Object", tag = "sky.result.$index") {
                    onPicked()
                    scope.launch { sky.select(obj, app.now) }
                }
            }
        }
        TimeCard(sky)
        Card("Show") {
            ButtonRow(
                { Switch("Sky survey", "sky.survey", sky.showSurvey) { sky.chooseSurvey(!sky.showSurvey) } },
                { Switch("Ground", "sky.ground", sky.showGround) { sky.chooseGround(!sky.showGround) } },
            )
            Switch("Constellations", "sky.constellations", sky.showConstellations) { sky.chooseConstellations(!sky.showConstellations) }
            if (sky.showConstellations && sky.constellations.lines.isEmpty()) Note("This Master has no constellation figures to send.")
            if (sky.showSurvey && sky.survey.credit == null) Note("Photographs of the sky appear when the Master can fetch them.")
        }
        Card("Catalog") {
            ButtonRow(
                *SkyModel.catalogs.map { name ->
                    @Composable { Switch(name, "sky.catalog.$name", name in sky.shownCatalogs) { sky.toggleCatalog(name) } }
                }.toTypedArray(),
            )
        }
    }
}

@Composable
private fun Switch(title: String, tag: String, on: Boolean, onClick: () -> Unit) {
    ActionButton(title, kind = if (on) ActionKind.Prominent else ActionKind.Normal, tag = tag, description = "$title, ${if (on) "on" else "off"}", onClick = onClick)
}

@Composable
private fun TimeCard(sky: SkyModel) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault())
    fun change(offset: Double) {
        sky.timeOffset = offset
        val now = app.now
        sky.cameraMoved(now)
        scope.launch { sky.fetchSamples(sky.time(now)) }
    }
    Card("Time") {
        Txt(formatter.format(sky.time(app.now)), weight = FontWeight.SemiBold, modifier = Modifier.testTag("sky.time"))
        Note(if (sky.timeOffset == 0.0) "Now" else "Not now: the mount's position isn't shown.")
        ButtonRow(
            { ActionButton("−1 d") { change(sky.timeOffset - 86400) } },
            { ActionButton("−1 h") { change(sky.timeOffset - 3600) } },
            { ActionButton("+1 h") { change(sky.timeOffset + 3600) } },
            { ActionButton("+1 d") { change(sky.timeOffset + 86400) } },
        )
        ThemedSlider("Through the night", (sky.timeOffset / 3600).coerceIn(-12.0, 12.0), -12.0..12.0) { change(it * 3600) }
        ActionButton("Now", kind = if (sky.timeOffset == 0.0) ActionKind.Normal else ActionKind.Prominent, enabled = sky.timeOffset != 0.0, tag = "sky.now") { change(0.0) }
    }
}

