package com.galileo.mobileconnect.quest

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** One saved target as the Targets pane needs it (`GET /mobileconnect/v1/targets`, schema SavedTarget). */
data class SavedTarget(
    val name: String,
    val type: String,
    val magnitude: Double,
    val sizeArcmin: Double,
    val raDeg: Double,
    val decDeg: Double,
)

/** What the Master answered: the list, newest first, and a version that changes with it. */
data class SavedTargets(val targets: List<SavedTarget>, val version: String, val reason: String? = null)

/** The Master's side of the Targets pane. `list` is null when the Master could not be reached; `remove` says whether it was removed. */
interface TargetsSource {
    suspend fun list(): SavedTargets?
    suspend fun remove(name: String): Boolean
}

enum class TargetAction(val label: String, val operation: String?) {
    SlewTo("Slew To", "mount.slew"),
    GotoAndCentre("Goto and Centre", "solve.centre"),
    Remove("Remove", null), // a request to the Targets route, not a Pier operation
}

/** What one tile shows. */
data class TargetTile(val name: String, val subtitle: String, val target: SavedTarget)

/** The Targets pane's rules, apart from drawing it: what a tile says, which actions it offers, and what they send. */
object Targets {
    const val EMPTY = "No Targets found."

    /** Magnitude 90 or more and size 0 mean "unknown" on the wire; unknown parts are left out rather than shown as numbers. */
    fun subtitle(target: SavedTarget): String {
        val parts = ArrayList<String>()
        if (target.type.isNotBlank()) parts += target.type.replace(Regex("([a-z])([A-Z])"), "$1 $2") // GlobularCluster -> Globular Cluster
        if (target.magnitude < 90.0) parts += "mag %.1f".format(target.magnitude)
        if (target.sizeArcmin > 0.0) parts += "%.1f′".format(target.sizeArcmin)
        return parts.joinToString(" · ")
    }

    /** The tiles in the order the Master gave them (newest first). */
    fun tiles(list: List<SavedTarget>): List<TargetTile> = list.map { TargetTile(it.name, subtitle(it), it) }

    /**
     * A Viewer sees the list and nothing else; an Operator who can operate this Pier also gets Slew To, Goto and Centre
     * and Remove (the Master refuses a removal from an app without control). [canOperate] is the same flag the object menu uses.
     */
    fun actions(canOperate: Boolean): List<TargetAction> = if (canOperate) TargetAction.entries.toList() else emptyList()

    /** The arguments of `mount.slew`, and the same two for `mount.sync`'s siblings. */
    fun slewArgs(target: SavedTarget): Map<String, JsonElement> =
        mapOf("ra_deg" to JsonPrimitive(target.raDeg), "dec_deg" to JsonPrimitive(target.decDeg))

    /** The arguments of `solve.centre`: the position and the name. */
    fun centreArgs(target: SavedTarget): Map<String, JsonElement> = slewArgs(target) + ("name" to JsonPrimitive(target.name))
}

/** The state the pane draws. */
sealed interface TargetsState {
    data object Loading : TargetsState
    data class Failed(val message: String) : TargetsState
    data class Loaded(val tiles: List<TargetTile>, val note: String? = null) : TargetsState {
        val isEmpty get() = tiles.isEmpty()
    }
}

/** Loads and edits the list through a [TargetsSource]; the pane reads [state] and calls [load] when it opens. */
class TargetsModel(private val source: TargetsSource) {
    var state: TargetsState = TargetsState.Loading
        private set
    private var version = ""

    suspend fun load() {
        val answer = source.list()
        state = if (answer == null) {
            // Keep what is on show when a refresh fails; say so only when there is nothing to show.
            (state as? TargetsState.Loaded) ?: TargetsState.Failed("The Master could not be reached.")
        } else {
            version = answer.version
            TargetsState.Loaded(Targets.tiles(answer.targets), answer.reason)
        }
    }

    /** Removes [name] on the Master and then reloads, so what is shown is what the Master has. Returns false when the Master refused. */
    suspend fun remove(name: String): Boolean {
        val ok = source.remove(name)
        if (ok) load()
        return ok
    }
}
