package com.galileo.mobileconnect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galileo.mobileconnect.core.link.DiscoveredMaster
import com.galileo.mobileconnect.core.link.DiscoveryEvent
import com.galileo.mobileconnect.core.link.LinkError
import com.galileo.mobileconnect.core.link.MasterAddress
import com.galileo.mobileconnect.core.link.PairingStatus
import com.galileo.mobileconnect.core.session.SessionStop
import com.galileo.mobileconnect.ui.theme.LocalTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** First screen: find a Master, type its address, reconnect to a paired one, or try the demo (A-CONN). */
@Composable
fun WelcomeView() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val scope = rememberCoroutineScope()
    var browsing by remember { mutableStateOf(app.localNetworkExplained) }
    var found by remember { mutableStateOf<List<DiscoveredMaster>>(emptyList()) }
    var denied by remember { mutableStateOf(false) }
    var searchProblem by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf("") }
    var manualProblem by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf<String?>(null) }
    var searchIsSlow by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { app.refreshPaired() }
    LaunchedEffect(browsing) {
        if (!browsing) return@LaunchedEffect
        app.discovery.events().collect { event ->
            when (event) {
                is DiscoveryEvent.Results -> {
                    found = event.masters
                    denied = false
                    searchProblem = null
                }
                DiscoveryEvent.PermissionDenied -> denied = true
                is DiscoveryEvent.Failed -> searchProblem = event.message
            }
        }
    }
    LaunchedEffect(browsing) {
        // A spinner alone looks broken: discovery can take a while on some networks.
        if (!browsing) return@LaunchedEffect
        delay(10_000)
        searchIsSlow = true
    }

    fun connectManually() {
        MasterAddress.parse(manual).fold(
            onSuccess = {
                manualProblem = null
                app.startPairing(it, it.host)
            },
            onFailure = { manualProblem = (it as? MasterAddress.ProblemException)?.problem?.message ?: it.message },
        )
    }

    Box(Modifier.fillMaxSize().background(theme.background), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            Heading("Galileo", size = 34.sp)
            Txt("Watch and operate your Galileo observatory.", color = theme.textSecondary)
            app.welcomeMessage?.let { Banner("ⓘ", it) }

            if (app.paired.isNotEmpty()) {
                Card("Paired") {
                    app.paired.forEach { master ->
                        ActionButton("${master.name} — ${master.address.host}", kind = ActionKind.Prominent) { app.connect(master) }
                    }
                }
            }
            Card("On this network") {
                when {
                    !app.localNetworkExplained -> {
                        // Our words first, so that anything the system asks makes sense (MAPP-PLT-070).
                        Txt("Galileo needs to find your observatory computer on your Wi-Fi network. Android may ask for permission to find devices on the local network.")
                        ActionButton("Find my observatory", kind = ActionKind.Prominent) {
                            app.explainedLocalNetwork()
                            browsing = true
                        }
                    }
                    denied -> {
                        Txt("Local network access is off, so this app can't find or reach your observatory.")
                        Note("Turn it on in Settings › Apps › Galileo › Permissions. Discovery, pairing and control all need it; only the demo works without.")
                    }
                    found.isEmpty() -> {
                        Row(Modifier.heightIn(min = minimumTarget), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Spinner()
                            Txt("Looking for Galileo…")
                        }
                        if (searchIsSlow) Note("Nothing yet. Check that Galileo is running and that this device is on the same network. It can take up to a minute, or you can enter the address below.")
                        searchProblem?.let { Note(it) }
                    }
                    else -> found.forEach { master ->
                        ActionButton(
                            master.name, kind = ActionKind.Prominent, busy = resolving == master.id, enabled = resolving == null,
                        ) {
                            resolving = master.id
                            scope.launch {
                                try {
                                    app.startPairing(app.discovery.resolve(master), master.name)
                                } catch (e: LinkError) {
                                    searchProblem = e.message
                                } catch (e: Exception) {
                                    searchProblem = "Could not reach ${master.name}."
                                } finally {
                                    resolving = null
                                }
                            }
                        }
                    }
                }
            }
            Card("Enter its address") {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = minimumTarget).clip(RoundedCornerShape(8.dp)).background(theme.raised).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    TextBox(
                        manual, { manual = it }, "Computer name or IP address", keyboard = KeyboardType.Uri, label = "Address",
                        tag = "address", modifier = Modifier.fillMaxWidth(), onDone = ::connectManually,
                    )
                }
                manualProblem?.let { Note(it) }
                ActionButton("Connect", tag = "connect", onClick = ::connectManually)
            }
            Card("No observatory to hand?") {
                ActionButton("Try the demo", tag = "demo") { app.startDemo() }
                Note("A simulated observatory. Nothing here reaches any real equipment.")
            }
        }
    }
}

/** The code and the state of the request. Nothing else is reachable until it's approved (MAPP-CON-030). */
@Composable
fun PairingView() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val started = remember { System.currentTimeMillis() }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    Box(Modifier.fillMaxSize().background(theme.background), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Heading("Pair with ${app.pairingMaster}", size = 28.sp)
            when (val status = app.pairing) {
                PairingStatus.Idle -> {
                    Spinner()
                    Txt("Contacting the Master…")
                }
                is PairingStatus.Waiting -> {
                    Txt("Approve this request on the Master. Check that it shows the same code.", align = TextAlign.Center)
                    Txt(
                        status.code.take(3) + " " + status.code.takeLast(3), size = 56.sp, weight = FontWeight.Bold, mono = true,
                        modifier = Modifier.testTag("pairing.code").semantics { contentDescription = "Code " + status.code.toList().joinToString(" ") },
                    )
                    val left = maxOf(0, (status.expiresIn - (System.currentTimeMillis() - started) / 1000.0 - 0 * tick).toInt())
                    Txt("Waiting for approval. ${left / 60}:${"%02d".format(left % 60)} left.", color = theme.textSecondary)
                    Txt("Certificate ${status.fingerprint.take(16)}…", size = 13.sp, mono = true, color = theme.textSecondary)
                    ActionButton("Cancel", Modifier.widthIn(max = 320.dp)) { app.cancelPairing() }
                }
                is PairingStatus.Paired -> {
                    Spinner()
                    Txt("Approved.")
                }
                PairingStatus.Refused -> Outcome("The request was refused on the Master.")
                PairingStatus.TimedOut -> Outcome("Nobody approved the request in time.")
                is PairingStatus.Failed -> Outcome(status.message)
            }
        }
    }
}

@Composable
private fun Outcome(text: String) {
    val app = LocalApp.current
    Txt(text, align = TextAlign.Center)
    ActionButton("Back", Modifier.widthIn(max = 320.dp), kind = ActionKind.Prominent) { app.cancelPairing() }
}

/** A session that ended and can't simply be retried. */
@Composable
fun SessionStoppedView(stop: SessionStop) {
    val app = LocalApp.current
    val theme = LocalTheme.current
    Box(Modifier.fillMaxSize().background(theme.background), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Txt("⚠", size = 48.sp)
            val back = @Composable { ActionButton("Back", Modifier.widthIn(max = 320.dp)) { app.backToWelcome() } }
            when (stop) {
                is SessionStop.Unpaired -> {
                    Heading("No longer paired", size = 28.sp)
                    Txt(stop.message, align = TextAlign.Center)
                    back()
                }
                SessionStop.IdentityChanged -> {
                    Heading("The Master's identity has changed", size = 28.sp)
                    Txt(LinkError.IdentityChanged.message, align = TextAlign.Center)
                    ActionButton("Pair again", Modifier.widthIn(max = 320.dp), kind = ActionKind.Destructive) { app.pairAgainAfterIdentityChange() }
                    back()
                }
                is SessionStop.Refused -> {
                    Heading(
                        when (stop.updateApp) {
                            true -> "Update this app"
                            false -> "Update Galileo"
                            null -> "Versions don't match"
                        },
                        size = 28.sp,
                    )
                    Txt(stop.message, align = TextAlign.Center)
                    if (stop.updateApp == true) Note("Get the latest version from where you installed this app.")
                    back()
                }
                SessionStop.ConfigurationGrant -> {
                    Heading("This pairing has too much access", size = 28.sp)
                    Txt(
                        "The Master granted this device permission to change its configuration. This app must never hold that, so it won't connect. " +
                            "Remove the pairing on the Master and pair again with control or view only.",
                        align = TextAlign.Center,
                    )
                    back()
                }
            }
        }
    }
}
