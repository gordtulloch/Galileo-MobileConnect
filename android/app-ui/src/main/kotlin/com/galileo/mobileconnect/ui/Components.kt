package com.galileo.mobileconnect.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galileo.mobileconnect.AppViewModel
import com.galileo.mobileconnect.core.api.ArgumentInfo
import com.galileo.mobileconnect.core.control.HoldState
import com.galileo.mobileconnect.core.control.HoldStop
import com.galileo.mobileconnect.core.control.OperationRecord
import com.galileo.mobileconnect.core.control.OperationState
import com.galileo.mobileconnect.core.state.displayText
import com.galileo.mobileconnect.core.state.double
import com.galileo.mobileconnect.core.state.string
import com.galileo.mobileconnect.design.Layout
import com.galileo.mobileconnect.ui.theme.LocalCompact
import com.galileo.mobileconnect.ui.theme.LocalTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

val LocalApp = staticCompositionLocalOf<AppViewModel> { error("no AppViewModel") }

val spacing get() = Layout.spacing.dp
val minimumTarget get() = Layout.minimumTarget.dp
val compactTarget get() = Layout.compactTarget.dp
val cornerRadius get() = Layout.cornerRadius.dp

/** Text that takes its colour from the theme: the only way text is drawn, so Night can't be broken by a stray default. */
@Composable
fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 16.sp,
    weight: FontWeight = FontWeight.Normal,
    color: Color = LocalTheme.current.text,
    strike: Boolean = false,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    mono: Boolean = false,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color, fontSize = size, fontWeight = weight, textAlign = align ?: TextAlign.Unspecified,
            textDecoration = if (strike) TextDecoration.LineThrough else null,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Heading(text: String, modifier: Modifier = Modifier, size: TextUnit = 18.sp) {
    Txt(text, modifier.semantics { heading() }, size = size, weight = FontWeight.SemiBold)
}

@Composable
fun Card(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalTheme.current
    val compact = LocalCompact.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .background(theme.surface)
            .border(1.dp, theme.border, RoundedCornerShape(cornerRadius))
            .padding(if (compact) 8.dp else spacing + 4.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else spacing),
    ) {
        if (title != null) Heading(title, size = if (compact) 15.sp else 18.sp)
        content()
    }
}

/** Screen scaffolding: the same padding, the unavailable-Pier banner, and the same scrolling everywhere. */
@Composable
fun ScreenScroll(title: String, content: @Composable ColumnScope.() -> Unit) {
    val app = LocalApp.current
    val theme = LocalTheme.current
    Column(
        Modifier.fillMaxSize().background(theme.background).semantics { paneTitle = title }
            .verticalScroll(rememberScrollState()).padding(spacing + 4.dp),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        app.workingPier?.let { pier -> app.snapshot.unavailableReason(pier) }?.let { reason ->
            Banner("⚠", "${app.pier?.name ?: "This Pier"}: $reason")
        }
        content()
    }
}

/** True where there is room for two columns (MAPP-LAY-010); one where there is not (MAPP-LAY-020). */
@Composable
fun isWide(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

@Composable
fun Columns(left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    if (isWide()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing), content = left)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing), content = right)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            left()
            right()
        }
    }
}

@Composable
fun Banner(icon: String, text: String, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.danger).padding(10.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt(icon, color = theme.onDanger, size = 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.semantics { contentDescription = "" })
        Txt(text, color = theme.onDanger, size = 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Txt(text, modifier, size = 13.sp, color = LocalTheme.current.textSecondary)
}

/** A label and the Master's value. A stale value is dimmed, crossed through and shows its age: never as if live. */
@Composable
fun LiveRow(label: String, key: String, unit: String? = null, digits: Int? = null) {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val view = app.value(key)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 28.dp).semantics(mergeDescendants = true) {
            stateDescription = if (view?.stale == true) "not live" else ""
        },
        verticalAlignment = Alignment.Top,
    ) {
        Txt(label, color = theme.textSecondary, modifier = Modifier.weight(1f))
        Box(Modifier.padding(start = 12.dp), contentAlignment = Alignment.CenterEnd) {
            if (view != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Txt(
                        format(view.value, unit, digits), weight = FontWeight.SemiBold, strike = view.stale,
                        modifier = Modifier.alpha(if (view.stale) 0.6f else 1f),
                    )
                    if (view.stale) Txt("as of ${view.age.seconds} s ago", size = 12.sp)
                }
            } else {
                Txt("—", color = theme.textSecondary)
            }
        }
    }
}

fun format(value: JsonElement, unit: String? = null, digits: Int? = null): String {
    var result = value.displayText()
    val number = value.double
    if (digits != null && number != null) result = String.format(Locale.US, "%.${digits}f", number)
    if (unit != null) result += " $unit"
    return result
}

/** A label and a value that isn't the Master's live state (the Settings rows). */
@Composable
fun ValueRow(label: String, value: String) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Txt(label, color = theme.textSecondary, modifier = Modifier.weight(1f))
        Txt(value, weight = FontWeight.SemiBold)
    }
}

enum class ActionKind { Normal, Prominent, Destructive }

@Composable
fun Spinner(modifier: Modifier = Modifier, size: Int = 20) {
    val color = LocalTheme.current.text
    val turn = rememberInfiniteTransition(label = "spinner").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "turn",
    )
    Canvas(modifier.size(size.dp)) {
        drawArc(
            color, startAngle = turn.value, sweepAngle = 270f, useCenter = false,
            style = Stroke(width = size.dp.toPx() / 8, cap = StrokeCap.Round),
        )
    }
}

/** A button drawn from the theme alone: nothing about it can come out the wrong colour in Night. */
@Composable
fun ActionButton(
    text: String,
    modifier: Modifier = Modifier,
    kind: ActionKind = ActionKind.Normal,
    enabled: Boolean = true,
    busy: Boolean = false,
    tag: String? = null,
    description: String? = null,
    onClick: () -> Unit,
) {
    val theme = LocalTheme.current
    val compact = LocalCompact.current
    val (background, foreground) = when (kind) {
        ActionKind.Normal -> theme.raised to theme.text
        ActionKind.Prominent -> theme.accent to theme.onAccent
        ActionKind.Destructive -> theme.danger to theme.onDanger
    }
    val shape = RoundedCornerShape(if (compact) 7.dp else 10.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) compactTarget else minimumTarget)
            .alpha(if (!enabled) 0.5f else 1f)
            .clip(shape).background(background).border(1.dp, theme.border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) 6.dp else 10.dp, vertical = if (compact) 4.dp else 8.dp)
            .let { if (tag != null) it.testTag(tag) else it }
            .semantics { if (description != null) contentDescription = description },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            Box(Modifier.padding(end = 8.dp)) { Spinner(size = 18) }
        }
        Txt(
            text, color = foreground, size = if (compact) 13.sp else 16.sp, weight = FontWeight.SemiBold,
            align = TextAlign.Center, maxLines = 2,
        )
    }
}

/** Buttons side by side, each taking an equal share. */
@Composable
fun ButtonRow(vararg buttons: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        buttons.forEach { Box(Modifier.weight(1f)) { it() } }
    }
}

/**
 * A control bound to a catalogue operation (ADR-APP-004). A Viewer sees nothing (MAPP-CTL-010); an absent or
 * unavailable operation is disabled with the reason; the outcome shown is the Master's (MAPP-CTL-050).
 */
@Composable
fun OperationButton(
    title: String,
    operation: String,
    args: Map<String, JsonElement> = emptyMap(),
    kind: ActionKind = ActionKind.Normal,
) {
    val app = LocalApp.current
    if (!app.canOperate) return
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val info = app.operation(operation)
    val record = app.record(operation)
    val open = record?.state?.isOpen == true
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionButton(
            title, kind = kind, busy = open, enabled = info != null && info.available && !open, tag = operation,
        ) { scope.launch { message = app.perform(operation, args) } }
        when {
            info == null -> Note("This Master doesn't offer this yet.")
            !info.available && !open && info.unavailableBecause != null -> Note(info.unavailableBecause!!) // not while this very operation is the thing running
            message != null -> Note(message!!)
            record != null -> OutcomeNote(record)
        }
    }
}

/** What became of the last request. Done is only ever the Master's word. */
@Composable
fun OutcomeNote(record: OperationRecord) {
    when (val state = record.state) {
        OperationState.Pending -> Note("Waiting for the Master…")
        is OperationState.Running -> if (state.progress != null) ThemedProgress(state.progress!!) else Note("Running…")
        is OperationState.Done -> Note("Done.")
        is OperationState.Refused -> Attention(state.reason)
        is OperationState.Unavailable -> Attention(state.reason)
        is OperationState.Busy -> Attention(state.reason)
        is OperationState.Unreachable -> Attention(state.reason)
        is OperationState.Invalid -> Attention(listOfNotNull(state.path, state.reason).joinToString(" "))
        OperationState.OutcomeUnknown -> Attention("Outcome unknown: the connection dropped. Check the state before trying again.")
    }
}

@Composable
fun Attention(text: String) {
    Txt("⚠ $text", size = 13.sp, weight = FontWeight.SemiBold, color = LocalTheme.current.warning)
}

/** The system progress bar's track is grey, which Night can't have. */
@Composable
fun ThemedProgress(value: Double, label: String = "Progress") {
    val theme = LocalTheme.current
    val clamped = value.coerceIn(0.0, 1.0)
    Box(
        Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(theme.raised).semantics {
            contentDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo(clamped.toFloat(), 0f..1f)
        },
    ) {
        Box(Modifier.fillMaxWidth(clamped.toFloat()).height(6.dp).background(theme.accent))
    }
}

/** The system slider's thumb is white and its track grey, which Night can't have. */
@Composable
fun ThemedSlider(label: String, value: Double, range: ClosedFloatingPointRange<Double>, onChange: (Double) -> Unit) {
    val theme = LocalTheme.current
    val compact = LocalCompact.current
    val thumb = if (compact) 20.dp else 28.dp
    val span = range.endInclusive - range.start
    Box(
        Modifier.fillMaxWidth().height(if (compact) 28.dp else 44.dp)
            .semantics {
                contentDescription = label
                stateDescription = String.format(Locale.US, "%.2f", value)
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), range.start.toFloat()..range.endInclusive.toFloat())
                setProgress { target ->
                    onChange(target.toDouble().coerceIn(range))
                    true
                }
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }
            val thumbPx = with(androidx.compose.ui.platform.LocalDensity.current) { thumb.toPx() }
            fun set(x: Float) {
                val position = ((x - thumbPx / 2) / maxOf(1f, widthPx - thumbPx)).coerceIn(0f, 1f)
                onChange(range.start + span * position)
            }
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(range) { detectTapGestures { set(it.x) } }
                    .pointerInput(range) { detectDragGestures(onDragStart = { set(it.x) }) { change, _ -> set(change.position.x) } },
            ) {
                val fraction = ((value - range.start) / span).toFloat().coerceIn(0f, 1f)
                val x = (size.width - thumbPx) * fraction
                val y = size.height / 2
                drawLine(theme.raised, Offset(0f, y), Offset(size.width, y), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
                drawLine(theme.accent, Offset(0f, y), Offset(x + thumbPx / 2, y), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(theme.accent, radius = thumbPx / 2, center = Offset(x + thumbPx / 2, y))
            }
        }
    }
}

/** A switch the theme can colour. */
@Composable
fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val compact = LocalCompact.current
    Row(
        modifier.fillMaxWidth().heightIn(min = if (compact) compactTarget else minimumTarget)
            .clickable(role = Role.Switch) { onChange(!checked) }
            .semantics(mergeDescendants = true) { stateDescription = if (checked) "on" else "off" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, size = if (compact) 13.sp else 16.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(width = 52.dp, height = 30.dp).clip(CircleShape)
                .background(if (checked) theme.accent else theme.raised).border(1.dp, theme.border, CircleShape),
        ) {
            Box(
                Modifier.padding(3.dp).size(24.dp).clip(CircleShape)
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .background(if (checked) theme.onAccent else theme.textSecondary),
            )
        }
    }
}

/** A text field drawn from the theme (a system field's colours can't be held to Night). */
@Composable
fun TextBox(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    label: String = hint,
    tag: String? = null,
    align: TextAlign = TextAlign.Start,
    onDone: (() -> Unit)? = null,
) {
    val theme = LocalTheme.current
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = TextStyle(color = theme.text, fontSize = 16.sp, textAlign = align),
        cursorBrush = SolidColor(theme.accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = modifier.semantics { contentDescription = label }.let { if (tag != null) it.testTag(tag) else it },
        decorationBox = { inner ->
            Box(contentAlignment = when (align) { TextAlign.End -> Alignment.CenterEnd; else -> Alignment.CenterStart }) {
                if (value.isEmpty()) Txt(hint, color = theme.textSecondary, maxLines = 1)
                inner()
            }
        },
    )
}

/** App dialogs are drawn here rather than as system dialogs: they can be held to Night (docs/android/SDD.md 3.9). */
@Composable
fun Dialog(title: String, content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalTheme.current
    Box(
        Modifier.fillMaxSize().background(theme.veil(0.8f)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 480.dp).fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)).background(theme.surface).border(1.dp, theme.border, RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            Heading(title, size = 22.sp)
            content()
        }
    }
}

/** A choice among a few, as buttons (a system menu can't be held to Night). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Choices(options: List<String>, selected: String, minWidth: Int = 88, onSelect: (String) -> Unit) {
    val compact = LocalCompact.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
        options.forEach { choice ->
            Box(Modifier.widthIn(min = (if (compact) 56 else minWidth).dp)) {
                ActionButton(
                    choice, kind = if (choice == selected) ActionKind.Prominent else ActionKind.Normal,
                    description = if (choice == selected) "$choice, selected" else choice,
                ) { onSelect(choice) }
            }
        }
    }
}

/** Moves only while pressed (MAPP-DEV-030). The core does the timing and the stopping. */
@Composable
fun HoldButton(title: String, glyph: String, family: String, args: Map<String, JsonElement>, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val theme = LocalTheme.current
    var pressed by remember { mutableStateOf(false) }
    fun release() {
        if (!pressed) return
        pressed = false
        app.holdReleased()
    }
    fun press() {
        if (pressed) return
        pressed = true
        app.holdPressed(family, args)
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { release() } }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = minimumTarget).clip(shape)
            .background(if (pressed) theme.accent else theme.raised).border(1.dp, theme.border, shape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    press()
                    tryAwaitRelease()
                    release()
                })
            }
            .testTag("hold.$family.$title")
            .semantics {
                role = Role.Button
                contentDescription = "$title. Hold to move. Moves only while held."
                customActions = listOf(
                    CustomAccessibilityAction("Start moving") { press(); true },
                    CustomAccessibilityAction("Stop moving") { release(); true },
                )
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt("$glyph $title", weight = FontWeight.SemiBold, color = if (pressed) theme.onAccent else theme.text)
    }
}

/** What to say about a hold that ended by itself, or null if there's nothing to say. */
val HoldState.explanation: String?
    get() = (this as? HoldState.Stopped)?.let { stopped ->
        when (val reason = stopped.reason) {
            HoldStop.Released, HoldStop.Background -> null
            HoldStop.ConnectionLost -> "Connection lost. The Master stops the motion by itself within a second."
            HoldStop.LeaseLost -> "Stopped: this device no longer has control."
            HoldStop.BeatFailed -> "Stopped: the Master ended the motion."
            is HoldStop.Refused -> reason.reason
        }
    }

/** A press held for about a second: the deliberate gesture of the emergency actions (MAPP-EMG-010). */
@Composable
fun HoldToConfirm(title: String, tag: String, enabled: Boolean = true, onAction: () -> Unit) {
    val theme = LocalTheme.current
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.fillMaxWidth().height(72.dp).alpha(if (enabled) 1f else 0.5f).clip(shape).background(theme.raised)
            .border(2.dp, theme.danger, shape)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    val job: Job = scope.launch {
                        progress.animateTo(1f, tween(1000, easing = LinearEasing))
                        onAction()
                        progress.snapTo(0f)
                    }
                    tryAwaitRelease()
                    if (job.isActive) {
                        job.cancel()
                        scope.launch { progress.animateTo(0f, tween(150)) }
                    }
                })
            }
            .testTag(tag)
            .semantics {
                role = Role.Button
                contentDescription = "$title. Press and hold for a second, or use the action."
                onClick("Do it now") {
                    if (enabled) onAction()
                    enabled
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value.coerceIn(0f, 1f)).align(Alignment.CenterStart).background(theme.danger))
        Txt(
            title, size = 20.sp, weight = FontWeight.Bold, align = TextAlign.Center,
            color = if (progress.value > 0.5f) theme.onDanger else theme.text,
        )
    }
}

/** `ra_deg` reads as "RA" with its unit beside it, not "Ra Deg (deg)". */
fun argumentTitle(name: String): String {
    val words = name.split('_').toMutableList()
    if (words.size > 1 && words.last() in setOf("deg", "s", "arcsec", "pct")) words.removeAt(words.size - 1)
    return words.joinToString(" ") { word ->
        mapOf("ra" to "RA", "id" to "ID")[word] ?: word.replaceFirstChar { it.uppercase() }
    }
}

/** A form built from a catalogue entry's arguments: ranges and units come from the Master (ADR-APP-004). */
@Composable
fun OperationForm(title: String, operation: String, fields: List<String>? = null, kind: ActionKind = ActionKind.Normal) {
    val app = LocalApp.current
    if (!app.canOperate) return
    val compact = LocalCompact.current
    val scope = rememberCoroutineScope()
    val text = remember { mutableStateMapOf<String, String>() }
    val flags = remember { mutableStateMapOf<String, Boolean>() }
    var message by remember { mutableStateOf<String?>(null) }
    val info = app.operation(operation)
    val shown = info?.arguments?.filter { fields == null || it.name in fields } ?: emptyList()
    val record = app.record(operation)
    val open = record?.state?.isOpen == true

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (compact) {
            // Short boxes side by side, so the form takes a line or two rather than a screenful.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                shown.filter { it.isBoxed }.forEach { Box(Modifier.weight(1f)) { Field(operation, it, text, flags) } }
            }
            shown.filterNot { it.isBoxed }.forEach { Field(operation, it, text, flags) }
        } else {
            shown.forEach { Field(operation, it, text, flags) }
        }
        ActionButton(
            title, kind = kind, busy = open, enabled = info != null && info.available && !open, tag = operation,
        ) { if (info != null) scope.launch { message = send(app, operation, shown, text, flags) } }
        when {
            info == null -> Note("This Master doesn't offer this yet.")
            !info.available && info.unavailableBecause != null -> Note(info.unavailableBecause!!)
            message != null -> Note(message!!)
            record != null -> OutcomeNote(record)
        }
    }
}

private val ArgumentInfo.isBoxed get() = kind != ArgumentInfo.Kind.Bool && kind != ArgumentInfo.Kind.Enum

private fun hint(argument: ArgumentInfo): String {
    argument.defaultValue?.let { return it.displayText() }
    val low = argument.minimum
    val high = argument.maximum
    return if (low != null && high != null) "${plain(low)} to ${plain(high)}" else ""
}

private fun plain(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else v.toString()

@Composable
private fun Field(
    operation: String,
    argument: ArgumentInfo,
    text: MutableMap<String, String>,
    flags: MutableMap<String, Boolean>,
) {
    val theme = LocalTheme.current
    val compact = LocalCompact.current
    val label = argumentTitle(argument.name)
    when (argument.kind) {
        ArgumentInfo.Kind.Bool -> Toggle(label, flags[argument.name] ?: false, { flags[argument.name] = it })
        ArgumentInfo.Kind.Enum -> {
            val selected = text[argument.name] ?: argument.defaultValue.string ?: argument.choices.firstOrNull() ?: ""
            Txt(label, size = if (compact) 11.sp else 13.sp, color = theme.textSecondary)
            Choices(argument.choices, selected) { text[argument.name] = it }
        }
        else -> {
            val withUnit = label + (argument.unit?.let { " ($it)" } ?: "")
            val keyboard = if (argument.kind == ArgumentInfo.Kind.Str) KeyboardType.Text else KeyboardType.Decimal
            if (compact) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = compactTarget).clip(RoundedCornerShape(7.dp)).background(theme.raised).padding(horizontal = 6.dp),
                ) {
                    Txt(withUnit, size = 11.sp, color = theme.textSecondary, maxLines = 1)
                    TextBox(text[argument.name] ?: "", { text[argument.name] = it }, hint(argument), keyboard = keyboard, label = label, tag = "$operation.${argument.name}", modifier = Modifier.fillMaxWidth())
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = minimumTarget).clip(RoundedCornerShape(8.dp)).background(theme.raised).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(withUnit, color = theme.textSecondary)
                    TextBox(
                        text[argument.name] ?: "", { text[argument.name] = it }, hint(argument), keyboard = keyboard, label = label,
                        tag = "$operation.${argument.name}", align = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

private suspend fun send(
    app: AppViewModel,
    operation: String,
    shown: List<ArgumentInfo>,
    text: Map<String, String>,
    flags: Map<String, Boolean>,
): String? {
    val args = mutableMapOf<String, JsonElement>()
    for (argument in shown) {
        when (argument.kind) {
            ArgumentInfo.Kind.Bool -> flags[argument.name]?.let { args[argument.name] = JsonPrimitive(it) }
            ArgumentInfo.Kind.Int, ArgumentInfo.Kind.Float -> {
                val entered = text[argument.name]?.takeIf { it.isNotEmpty() } ?: continue
                // Left as text if it isn't a number, so the validator names the field.
                args[argument.name] = entered.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(entered)
            }
            ArgumentInfo.Kind.Enum, ArgumentInfo.Kind.Str -> text[argument.name]?.takeIf { it.isNotEmpty() }?.let { args[argument.name] = JsonPrimitive(it) }
        }
    }
    return app.perform(operation, args)
}
