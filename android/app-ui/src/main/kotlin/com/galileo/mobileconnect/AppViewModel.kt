package com.galileo.mobileconnect

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.galileo.mobileconnect.core.api.LeaseRefusal
import com.galileo.mobileconnect.core.api.OperationInfo
import com.galileo.mobileconnect.core.api.PierInfo
import com.galileo.mobileconnect.core.api.Role
import com.galileo.mobileconnect.core.control.ConfirmationRequest
import com.galileo.mobileconnect.core.control.OperationRecord
import com.galileo.mobileconnect.core.control.OperationState
import com.galileo.mobileconnect.core.control.RequestAnswer
import com.galileo.mobileconnect.core.link.ClientIdentity
import com.galileo.mobileconnect.core.link.InMemorySecureStore
import com.galileo.mobileconnect.core.link.MasterAddress
import com.galileo.mobileconnect.core.link.MasterConnection
import com.galileo.mobileconnect.core.link.MasterCredentials
import com.galileo.mobileconnect.core.link.PairingFlow
import com.galileo.mobileconnect.core.link.PairingStatus
import com.galileo.mobileconnect.core.link.PinPolicy
import com.galileo.mobileconnect.core.link.PreviewSource
import com.galileo.mobileconnect.core.link.SecureStore
import com.galileo.mobileconnect.core.mock.MockMaster
import com.galileo.mobileconnect.core.session.ObservatorySession
import com.galileo.mobileconnect.core.session.SessionSnapshot
import com.galileo.mobileconnect.core.sky.PreviewImage
import com.galileo.mobileconnect.core.state.ValueView
import com.galileo.mobileconnect.core.state.string
import com.galileo.mobileconnect.platform.AndroidImageRenderer
import com.galileo.mobileconnect.platform.KeystoreSecureStore
import com.galileo.mobileconnect.platform.NsdDiscovery
import com.galileo.mobileconnect.ui.theme.ThemeChoice
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/** What the user has asked to be taken to (the sky's Add to Session, for one). */
data class SessionTarget(val name: String, val raDeg: Double, val decDeg: Double)

enum class Route { Welcome, Pairing, Connected }

/**
 * A-BRIDGE: the one object screens talk to. It holds the latest immutable [SessionSnapshot] and the little state the
 * screens share (the working Pier, the open dialog), and it is the only place that owns a connection. Screens read it;
 * they never see the generated types (docs/android/SDD.md 3.10).
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("galileo", Context.MODE_PRIVATE)
    val discovery = NsdDiscovery(application)
    private val renderer = AndroidImageRenderer()

    var route by mutableStateOf(Route.Welcome); private set
    var snapshot by mutableStateOf(SessionSnapshot()); private set
    var pairing by mutableStateOf<PairingStatus>(PairingStatus.Idle); private set
    var pairingMaster by mutableStateOf(""); private set
    var paired by mutableStateOf<List<MasterCredentials>>(emptyList()); private set
    var isDemo by mutableStateOf(false); private set
    var masterName by mutableStateOf(""); private set
    var welcomeMessage by mutableStateOf<String?>(null); private set

    /** Moves once a second, so ages on screen keep counting between updates. */
    var now by mutableStateOf(Instant.now()); private set

    var workingPier by mutableStateOf<String?>(null)
    var question by mutableStateOf<ConfirmationRequest?>(null)
    var leaseOffer by mutableStateOf<LeaseRefusal?>(null)
    var emergencyOpen by mutableStateOf(false)
    var pierPickerOpen by mutableStateOf(false)
    var sessionTarget by mutableStateOf<SessionTarget?>(null)

    /** An area to go to: the shell takes it and clears it. */
    var requestedArea by mutableStateOf<String?>(null)

    var theme by mutableStateOf(ThemeChoice.of(prefs.getString("theme", null)) ?: ThemeChoice.Night)
        private set

    var localNetworkExplained by mutableStateOf(prefs.getBoolean("localNetworkExplained", false))
        private set

    var menuPinned by mutableStateOf(prefs.getBoolean("menu.pinned", false))
        private set

    private var session: ObservatorySession? = null
    private var master: MockMaster? = null
    private var updates: Job? = null
    private var pairingJob: Job? = null
    private var store: SecureStore = KeystoreSecureStore(application)
    private var liveCredentials: MasterCredentials? = null
    private var launchOptions: LaunchOptions = LaunchOptions()

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                now = Instant.now()
            }
        }
    }

    /** The scope the connection and the sky live in: it outlives a screen, and ends with the app. */
    val sessionScope get() = viewModelScope

    /** Small per-screen preferences (a panel kept open, a chosen action). Not secrets: those are in the store. */
    fun pref(key: String, default: String): String = prefs.getString(key, default) ?: default

    fun setPref(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun chooseTheme(choice: ThemeChoice) {
        theme = choice
        prefs.edit().putString("theme", choice.name).apply()
    }

    fun pinMenu(pinned: Boolean) {
        menuPinned = pinned
        prefs.edit().putBoolean("menu.pinned", pinned).apply()
    }

    fun explainedLocalNetwork() {
        localNetworkExplained = true
        prefs.edit().putBoolean("localNetworkExplained", true).apply()
    }

    val identity: ClientIdentity
        get() {
            val isTablet = getApplication<Application>().resources.configuration.smallestScreenWidthDp >= 600
            return ClientIdentity(
                deviceName = listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" ").ifBlank { "Android device" },
                appVersion = APP_VERSION, platform = "android", clientClass = if (isTablet) "tablet" else "phone",
            )
        }

    // -- start-up ---------------------------------------------------------------------------------------------------

    /** Launch arguments for tests and screenshots, then: one paired Master goes straight to it. */
    fun launched(options: LaunchOptions) {
        if (route != Route.Welcome || session != null) return
        launchOptions = options
        options.theme?.let { theme = it }
        options.demoScenario?.let {
            startDemo(it)
            return
        }
        refreshPaired()
        if (paired.size == 1 && !options.noAutoConnect) connect(paired.first())
    }

    fun refreshPaired() {
        paired = runCatching { store.all() }.getOrDefault(emptyList())
    }

    // -- pairing ----------------------------------------------------------------------------------------------------

    fun startPairing(address: MasterAddress, name: String) {
        isDemo = false
        store = KeystoreSecureStore(getApplication())
        val link = MasterConnection.live(address, PinPolicy.Pairing, identity, null)
        val flow = PairingFlow(link.api, store, address, name, presentedFingerprint = { link.trust?.presentedFingerprint })
        run(flow, name)
    }

    /** Demonstration mode: the real client against the scripted Master, and nothing kept (MAPP-CON-110). */
    fun startDemo(scenario: String = "normal-night") {
        val mock = runCatching { MockMaster(scenario, viewModelScope, renderer = renderer) }.getOrElse {
            welcomeMessage = "The demonstration could not start."
            return
        }
        isDemo = true
        master = mock
        store = InMemorySecureStore()
        val link = mock.connection(identity)
        val flow = PairingFlow(link.api, store, MasterAddress("demo"), "Demo observatory")
        run(flow, "Demo observatory")
    }

    private fun run(flow: PairingFlow, masterName: String) {
        pairingJob?.cancel()
        pairingMaster = masterName
        pairing = PairingStatus.Idle
        welcomeMessage = null
        route = Route.Pairing
        pairingJob = viewModelScope.launch {
            val first = flow.start()
            pairing = first
            if (first !is PairingStatus.Waiting) return@launch
            val last = flow.waitForAnswer()
            pairing = last
            if (last is PairingStatus.Paired) connect(last.credentials)
        }
    }

    fun cancelPairing() {
        pairingJob?.cancel()
        pairing = PairingStatus.Idle
        isDemo = false
        master?.shutDown()
        master = null
        route = Route.Welcome
        refreshPaired()
    }

    // -- session ----------------------------------------------------------------------------------------------------

    fun connect(credentials: MasterCredentials) {
        disconnect()
        liveCredentials = credentials
        masterName = credentials.name
        val link = master?.takeIf { isDemo }?.connection(identity, credentials.token)
            ?: MasterConnection.live(credentials.address, PinPolicy.Pinned(credentials.fingerprint), identity, credentials.token)
        val id = credentials.id
        val current = store
        val new = ObservatorySession(link, viewModelScope, onUnpaired = { runCatching { current.delete(id) } })
        session = new
        route = Route.Connected
        snapshot = SessionSnapshot()
        new.start()
        updates = viewModelScope.launch { new.snapshot.collect { apply(it) } }
    }

    private fun apply(next: SessionSnapshot) {
        snapshot = next
        val working = workingPier
        if (working == null || next.piers.none { it.id == working }) {
            workingPier = next.piers.firstOrNull { it.access == PierInfo.Access.Control && it.reachable }?.id ?: next.piers.firstOrNull()?.id
        }
    }

    private fun disconnect() {
        stopLiveLoop()
        updates?.cancel()
        val old = session
        session = null
        viewModelScope.launch { old?.stop() }
    }

    /** The token and pin are discarded. The pairing on the Master is the owner's to remove (MAPP-CON-100). */
    fun unpair(credentials: MasterCredentials) {
        runCatching { store.delete(credentials.id) }
        if (liveCredentials?.id == credentials.id) backToWelcome("Unpaired. Remove this device's pairing on the Master as well.")
        refreshPaired()
    }

    fun unpairCurrent() {
        liveCredentials?.let(::unpair)
    }

    fun backToWelcome(message: String? = null) {
        disconnect()
        master?.shutDown()
        master = null
        liveCredentials = null
        isDemo = false
        snapshot = SessionSnapshot()
        workingPier = null
        question = null
        leaseOffer = null
        emergencyOpen = false
        store = KeystoreSecureStore(getApplication())
        welcomeMessage = message
        route = Route.Welcome
        refreshPaired()
    }

    /** MAPP-CON-050: the old pin is thrown away only because the user said so. */
    fun pairAgainAfterIdentityChange() {
        val credentials = liveCredentials ?: return
        runCatching { store.delete(credentials.id) }
        backToWelcome()
        startPairing(credentials.address, credentials.name)
    }

    // -- lifecycle (A-LIFE) -----------------------------------------------------------------------------------------

    fun enteredForeground() {
        session?.enteredForeground()
    }

    /** The app left the foreground: held motion and the live loop stop at once (MAPP-DEV-030). */
    fun enteredBackground() {
        stopLiveLoop()
        val current = session ?: return
        viewModelScope.launch { current.enteredBackground() }
    }

    // -- operations -------------------------------------------------------------------------------------------------

    /** Null when the answer needs nothing more from the screen; a message when it does. */
    suspend fun perform(operation: String, args: Map<String, JsonElement> = emptyMap()): String? {
        val pier = workingPier ?: return null
        val current = session ?: return null
        return handle(current.request(pier, operation, args))
    }

    fun accept(request: ConfirmationRequest) {
        question = null
        val current = session ?: return
        viewModelScope.launch { handle(current.confirm(request)) }
    }

    private fun handle(answer: RequestAnswer): String? = when (answer) {
        is RequestAnswer.Confirm -> {
            question = answer.request
            null
        }
        is RequestAnswer.Sent -> null
        is RequestAnswer.NotAllowed -> answer.reason
        is RequestAnswer.Invalid -> "${answer.path.replace('_', ' ')} ${answer.reason}"
    }

    fun emergency(operation: String) {
        val pier = workingPier ?: return
        val current = session ?: return
        viewModelScope.launch { current.emergency(pier, operation) }
    }

    fun takeLease(token: String? = null) {
        val pier = workingPier ?: return
        val current = session ?: return
        viewModelScope.launch { leaseOffer = current.takeLease(pier, token) }
    }

    fun releaseLease() {
        val pier = workingPier ?: return
        val current = session ?: return
        viewModelScope.launch { current.releaseLease(pier) }
    }

    fun holdPressed(family: String, args: Map<String, JsonElement>) {
        val pier = workingPier ?: return
        val current = session ?: return
        viewModelScope.launch { current.startHold(pier, family, args) }
    }

    fun holdReleased() {
        val current = session ?: return
        viewModelScope.launch { current.stopHold() }
    }

    suspend fun preview(width: Int, stretch: Int, source: PreviewSource = PreviewSource.Current): PreviewImage? {
        val pier = workingPier ?: return null
        val current = session ?: return null
        return runCatching { current.preview(pier, width, stretch, source = source) }.getOrNull()
    }

    /** One read-class operation's value on the working Pier (the guide-star cut-out, for one). */
    suspend fun read(operation: String): JsonElement? {
        val pier = workingPier ?: return null
        val current = session ?: return null
        return current.read(pier, operation)
    }

    suspend fun tile(level: Int, x: Int, y: Int, stretch: Int, source: PreviewSource = PreviewSource.Current): ByteArray? {
        val pier = workingPier ?: return null
        val current = session ?: return null
        return runCatching { current.tile(pier, level, x, y, stretch, source) }.getOrNull()
    }

    /** The session and Pier a sky view works against, or null when there is no connection. */
    fun skyContext(): Pair<ObservatorySession, String>? {
        val pier = workingPier ?: return null
        val current = session ?: return null
        return current to pier
    }

    /** MAPP-SKY-040: asks first, always. Null when the answer needs nothing more from the screen. */
    suspend fun goTo(name: String, raDeg: Double, decDeg: Double): String? {
        val pier = workingPier ?: return null
        val current = session ?: return null
        return handle(current.goToQuestion(pier, name, raDeg, decDeg))
    }

    // -- live loop (MAPP-IMG-020) ---------------------------------------------------------------------------------

    var liveLoopRunning by mutableStateOf(false); private set
    var liveLoopMessage by mutableStateOf<String?>(null); private set
    private var liveLoop: Job? = null

    /**
     * One exposure after another, each shown as it arrives. Core has no live-loop operation, so this repeats a single
     * capture while the app is in front: it asks for nothing while nobody is looking.
     */
    fun startLiveLoop(seconds: Double) {
        if (liveLoopRunning) return
        liveLoopRunning = true
        liveLoopMessage = null
        liveLoop = viewModelScope.launch {
            while (isActive && liveLoopRunning) {
                val problem = perform("imaging.capture", mapOf("seconds" to JsonPrimitive(seconds), "count" to JsonPrimitive(1)))
                if (problem != null) {
                    liveLoopMessage = problem
                    break
                }
                delay(200)
                while (isActive && record("imaging.capture")?.state?.isOpen == true) delay(250)
                if (record("imaging.capture")?.state !is OperationState.Done) {
                    liveLoopMessage = "The live loop stopped: the last exposure didn't finish."
                    break
                }
            }
            liveLoopRunning = false
        }
    }

    fun stopLiveLoop() {
        liveLoopRunning = false
        liveLoop?.cancel()
        liveLoop = null
    }

    // -- reading state ----------------------------------------------------------------------------------------------

    val pier: PierInfo? get() = snapshot.piers.firstOrNull { it.id == workingPier }

    fun value(key: String): ValueView? = workingPier?.let { snapshot.value(key, it, now) }

    /** Child keys under a prefix, for example the safety inputs. */
    fun children(prefix: String): List<String> {
        val values = workingPier?.let { snapshot.live.piers[it]?.values } ?: return emptyList()
        return values.keys.filter { it.startsWith("$prefix.") }.map { it.removePrefix("$prefix.") }.sorted()
    }

    val canOperate: Boolean
        get() {
            val pier = workingPier ?: return false
            return snapshot.role == Role.Operator && snapshot.access(pier) == PierInfo.Access.Control && snapshot.unavailableReason(pier) == null
        }

    fun operation(name: String): OperationInfo? = workingPier?.let { snapshot.operation(name, it) }

    fun record(name: String): OperationRecord? = workingPier?.let { snapshot.ledger.latest(it, name) }

    val overallSafety: String? get() = value("safety.overall")?.value.string

    companion object {
        const val APP_VERSION = "0.1.0"
    }
}

/** Launch arguments for UI tests and screenshots (`--es galileo.demo normal-night`). Nothing a user can reach. */
data class LaunchOptions(val demoScenario: String? = null, val theme: ThemeChoice? = null, val noAutoConnect: Boolean = false)
