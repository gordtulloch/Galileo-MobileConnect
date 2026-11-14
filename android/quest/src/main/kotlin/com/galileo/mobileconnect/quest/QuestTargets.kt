package com.galileo.mobileconnect.quest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galileo.mobileconnect.ui.ActionButton
import com.galileo.mobileconnect.ui.ActionKind
import com.galileo.mobileconnect.ui.Card
import com.galileo.mobileconnect.ui.LocalApp
import com.galileo.mobileconnect.ui.Note
import com.galileo.mobileconnect.ui.OperationButton
import com.galileo.mobileconnect.ui.OutcomeNote
import kotlinx.coroutines.launch

/**
 * The Targets pane: the saved targets from the Master (Planning > Targets on the desktop), newest first, as tiles. Reading is open
 * to a Viewer; Slew To, Goto and Centre and Remove are offered only when this app can operate the Pier ([Targets.actions]), and
 * Slew To asks the Master's confirmation like the object menu's Goto does. [source] is null until the Master is connected.
 */
@Composable
fun TargetsPaneContent(source: TargetsSource?) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val model = remember(source) { source?.let { TargetsModel(it) } }
    var state by remember(model) { mutableStateOf<TargetsState>(TargetsState.Loading) }
    var message by remember(model) { mutableStateOf<String?>(null) }
    LaunchedEffect(model) {
        if (model != null) {
            model.load()
            state = model.state
        }
    }
    val actions = Targets.actions(app.canOperate)
    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (model == null) {
            Note("Connect to a Master to see its saved targets.")
            return@Column
        }
        message?.let { Note(it) }
        when (val s = state) {
            TargetsState.Loading -> Note("Loading…")
            is TargetsState.Failed -> {
                Note(s.message)
                ActionButton("Try again", tag = "targets.retry") { scope.launch { model.load(); state = model.state } }
            }
            is TargetsState.Loaded -> {
                s.note?.let { Note(it) }
                if (s.isEmpty) {
                    Note(Targets.EMPTY)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(s.tiles, key = { it.name }) { tile ->
                            Card(tile.name) {
                                if (tile.subtitle.isNotEmpty()) Note(tile.subtitle)
                                if (actions.isNotEmpty()) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ActionButton("Slew To", Modifier.weight(1f), ActionKind.Prominent, tag = "targets.slew.${tile.name}") {
                                            scope.launch { message = app.goTo(tile.name, tile.target.raDeg, tile.target.decDeg) }
                                        }
                                        Column(Modifier.weight(1f)) {
                                            OperationButton("Goto and Centre", "solve.centre", Targets.centreArgs(tile.target))
                                        }
                                        ActionButton("Remove", Modifier.weight(1f), ActionKind.Destructive, tag = "targets.remove.${tile.name}") {
                                            scope.launch {
                                                message = if (model.remove(tile.name)) null else "The Master wouldn't remove ${tile.name}."
                                                state = model.state
                                            }
                                        }
                                    }
                                    app.record("mount.slew")?.let { OutcomeNote(it) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
