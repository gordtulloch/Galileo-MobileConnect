package com.galileo.mobileconnect.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galileo.mobileconnect.core.sky.SavedTarget
import com.galileo.mobileconnect.core.sky.SavedTargets
import com.galileo.mobileconnect.ui.ActionButton
import com.galileo.mobileconnect.ui.ActionKind
import com.galileo.mobileconnect.ui.Card
import com.galileo.mobileconnect.ui.LocalApp
import com.galileo.mobileconnect.ui.Note
import com.galileo.mobileconnect.ui.OperationButton
import com.galileo.mobileconnect.ui.ScreenScroll
import com.galileo.mobileconnect.ui.Txt
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** What to say under the list when there is nothing to list. */
const val NO_TARGETS = "No Targets found."

/** The line of coordinates on a tile. */
fun coordinates(target: SavedTarget): String = "RA %.3f°  Dec %+.3f°".format(target.raDeg, target.decDeg)

/**
 * The Master's saved targets (Planning > Targets on the desktop), newest first. Slew To asks first, as the Sky screen's
 * Goto does; Goto and Centre is the Master's `solve.centre`. Both need control of the Pier. Remove needs an Operator and
 * is shown disabled to a Viewer. The list is read when the screen opens and when asked again.
 */
@Composable
fun TargetsScreen() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var listing by remember { mutableStateOf<SavedTargets?>(null) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        loading = true
        val answer = app.savedTargets()
        if (answer != null) listing = answer
        failed = answer == null
        loading = false
    }
    LaunchedEffect(app.snapshot.isConnected) { if (app.snapshot.isConnected) load() }

    ScreenScroll("Targets") {
        ActionButton("Refresh", busy = loading, enabled = !loading, tag = "targets.refresh") { scope.launch { load() } }
        message?.let { Note(it) }
        val current = listing
        when {
            current == null && failed -> Note(NO_TARGETS)
            current == null -> Note("Loading the saved targets…")
            current.targets.isEmpty() -> Note(NO_TARGETS)
            else -> current.targets.forEach { target ->
                TargetTile(target) { name ->
                    scope.launch {
                        message = app.removeSavedTarget(name)
                        load()
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetTile(target: SavedTarget, onRemove: (String) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var goToMessage by remember(target.name) { mutableStateOf<String?>(null) }
    val args: Map<String, JsonElement> = mapOf("ra_deg" to JsonPrimitive(target.raDeg), "dec_deg" to JsonPrimitive(target.decDeg))
    Card(target.name, Modifier.testTag("target.${target.name}")) {
        // The object's picture runs the full height of this block, on the right; the text sets the height.
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(target.summary, size = 13.sp, weight = FontWeight.SemiBold)
                Note(coordinates(target))
                if (target.designations.isNotEmpty()) Note(target.designations.joinToString(", "))
            }
            TargetThumbnail(target.name)
        }
        if (app.canOperate) {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("Slew To", kind = ActionKind.Prominent, tag = "target.slew") {
                    scope.launch { goToMessage = app.goTo(target.name, target.raDeg, target.decDeg) }
                }
                goToMessage?.let { Note(it) }
                OperationButton("Goto and Centre", "solve.centre", args + ("name" to JsonPrimitive(target.name)))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            ActionButton("Remove", kind = ActionKind.Destructive, enabled = app.canOperate, tag = "target.remove") { onRemove(target.name) }
        }
    }
}

/** The survey picture of a saved target, loaded from the Master; empty space where there is none. */
@Composable
private fun TargetThumbnail(name: String) {
    val app = LocalApp.current
    var picture by remember(name) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(name) {
        picture = app.targetThumbnail(name)?.let {
            android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
        }
    }
    Box(Modifier.width(110.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp))) {
        picture?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}
