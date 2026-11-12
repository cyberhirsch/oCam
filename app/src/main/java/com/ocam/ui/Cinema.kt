package com.ocam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ocam.CameraUiState
import com.ocam.CameraViewModel
import com.ocam.camera.GridMode
import com.ocam.camera.WhiteBalance
import com.ocam.camera.isoStops
import com.ocam.camera.kelvinStops
import com.ocam.camera.nearestStop
import com.ocam.camera.shutterStopsNs
import com.ocam.sequence.SequenceMode
import java.util.Locale

// The cinema monitor: values across the top of the picture, a solid column of tools around a red
// shutter, and the modes as tabs at the edge. Blue marks what is chosen, red what is running.

internal val CinemaBlue = Color(0xFF1F7FD6)
internal val CinemaInk = Color(0xFF4AA8FF)
private val CinemaTile = Color(0xFF12324F)
internal val CinemaRed = Color(0xFFFF2A1F)
internal val ToolGround = Color(0xFF0B0B0C)
internal val ModeGround = Color(0xFF161719)
private val PanelGround = Color(0xB826262A)
private val PanelEdge = Color(0x73FFFFFF)
private val StripGround = Color(0xE60C0C0D)
private val Idle = Color(0xFFC8C9CB)

/** A value along the top that opens its presets. */
enum class ValueKind(val label: String) {
    LENS("LENS"),
    SPEED("SHUTTER"),
    ISO("ISO"),
    WHITE("WB"),
    FOCUS("FOCUS"),
}

internal fun modeTitle(mode: SequenceMode): String = when (mode) {
    SequenceMode.OFF -> "Photo"
    SequenceMode.HDR -> "HDR"
    SequenceMode.STACK -> "Stack"
    SequenceMode.PANO -> "Pano"
    SequenceMode.PAINT -> "Paint"
    SequenceMode.LONG -> "Long"
    SequenceMode.TRAILS -> "Trails"
    SequenceMode.LAPSE -> "Lapse"
}

/** Line drawings on a 24 unit square, as in the mock-ups. */
private fun modeIcon(mode: SequenceMode): String = when (mode) {
    SequenceMode.OFF -> "M4 8h4l2-3h4l2 3h4v11H4z M12 10.5a3.2 3.2 0 1 0 0 6.4a3.2 3.2 0 1 0 0-6.4"
    SequenceMode.HDR -> "M4 9h12v11H4z M8 9V5h12v11h-4"
    SequenceMode.STACK -> "M12 4l8 4-8 4-8-4z M4 12l8 4 8-4 M4 16l8 4 8-4"
    SequenceMode.PANO -> "M3 7q9 3 18 0v10q-9-3-18 0z"
    SequenceMode.PAINT -> "M4 18c3-9 6 5 8-3s5-9 8-9"
    SequenceMode.LONG -> "M12 4a8 8 0 1 0 0 16a8 8 0 1 0 0-16 M12 8v4l3 2"
    SequenceMode.TRAILS -> "M3 19a9 9 0 0 1 18 0 M7 19a5 5 0 0 1 10 0 M11 19a1 1 0 0 1 2 0"
    SequenceMode.LAPSE -> "M8 3h8 M8 21h8 M9 3c0 5 6 6 6 9s-6 4-6 9 M15 3c0 5-6 6-6 9s6 4 6 9"
}

private const val ICON_GRID = "M4 8V4h4 M16 4h4v4 M20 16v4h-4 M8 20H4v-4"
private const val ICON_EYE = "M3 12s3.5-6 9-6 9 6 9 6-3.5 6-9 6-9-6-9-6z M12 9.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5"
private const val ICON_PEAK = "M3 19l6-10 4 6 3-4 5 8z M6 5h.01 M11 3h.01 M16 5h.01"
private const val ICON_MAG = "M10.5 4a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13 M15.5 15.5L21 21 M10.5 8v5 M8 10.5h5"
private const val ICON_QR = "M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h2v2h-2z M18 18h2v2h-2z"
private const val ICON_SETTINGS = "M12 8.5a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7 M12 3v3 M12 18v3 M3 12h3 M18 12h3 " +
    "M5.6 5.6l2.1 2.1 M16.3 16.3l2.1 2.1 M5.6 18.4l2.1-2.1 M16.3 7.7l2.1-2.1"

@Composable
fun LineIcon(path: String, color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val parsed = remember(path) { PathParser().parsePathString(path).toPath() }
    Canvas(modifier = modifier.size(size)) {
        val scale = this.size.minDimension / 24f
        withTransform({ scale(scale, scale, Offset.Zero) }) {
            drawPath(
                parsed, color,
                style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/** Sensor time as a timecode, at 24 frames to the second. */
private fun timecode(nanos: Long): String {
    val seconds = nanos / 1_000_000_000L
    val frames = (nanos % 1_000_000_000L) * 24 / 1_000_000_000L
    return String.format(
        Locale.US, "%02d:%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60, frames,
    )
}

/**
 * The values across the top. Upright they take a row and the counter sits under them; on its side
 * the counter goes between them, as on a monitor.
 */
@Composable
fun ValueBar(
    state: CameraUiState,
    viewModel: CameraViewModel,
    open: ValueKind?,
    onOpen: (ValueKind) -> Unit,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    val caps = state.capabilities
    val lens = state.selectedLens
    @Composable
    fun RowScope.Cell(kind: ValueKind, value: String, available: Boolean) {
        ValueCell(
            label = kind.label,
            value = value,
            chosen = open == kind,
            available = available,
            onClick = { onOpen(kind) },
        )
    }
    val iris = formatAperture(state.liveAperture ?: state.settings.aperture)?.removePrefix("f/")?.let { "f$it" } ?: "--"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xB0000000), Color.Transparent)))
            .padding(start = 10.dp, end = 8.dp, bottom = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Cell(ValueKind.LENS, lens?.let { state.lensLabel(it) } ?: "--", state.visibleLenses.size > 1)
            Cell(ValueKind.SPEED, formatShutter(state.settings.exposureTimeNs), caps.supportsManualShutter)
            ValueCell(label = "IRIS", value = iris, chosen = false, available = false, onClick = null)
            if (wide) Counter(state, viewModel, Modifier.weight(1f))
            Cell(ValueKind.ISO, state.settings.iso.toString(), caps.supportsManualIso)
            Cell(ValueKind.WHITE, whiteBalanceLabel(state), true)
            Cell(ValueKind.FOCUS, focusLabel(state), caps.supportsManualFocus)
            if (wide) FormatBadge(state, viewModel, Modifier.padding(top = 10.dp))
        }
        if (!wide) {
            Box(modifier = Modifier.fillMaxWidth()) {
                // Spelled out: a bare gear here was too easy to miss.
                Pill(
                    text = "SETTINGS",
                    selected = state.settingsOpen,
                    modifier = Modifier.align(Alignment.CenterStart),
                    onClick = viewModel::openSettings,
                )
                Counter(state, viewModel, Modifier.align(Alignment.Center))
                FormatBadge(state, viewModel, Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}

@Composable
private fun ValueCell(label: String, value: String, chosen: Boolean, available: Boolean, onClick: (() -> Unit)?) {
    val colour = when {
        chosen -> CinemaInk
        available -> Color.White
        else -> Color(0xA6FFFFFF)
    }
    Column(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(4.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .semantics { contentDescription = "$label $value" },
    ) {
        Text(text = label, color = colour, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        Text(text = value, color = colour, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

/** The mode in blue over the time the run has gathered, red while it runs. */
@Composable
private fun Counter(state: CameraUiState, viewModel: CameraViewModel, modifier: Modifier = Modifier) {
    val run = state.sequence
    Column(
        modifier = modifier
            // Hidden on purpose: a long press is out of the way in normal use, and the report is
            // only ever needed when something has gone wrong.
            .pointerInput(Unit) { detectTapGestures(onLongPress = { viewModel.openDiagnostics() }) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = modeTitle(state.sequenceMode),
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(top = 4.dp)
                .background(CinemaBlue, RoundedCornerShape(2.dp))
                .padding(horizontal = 12.dp, vertical = 1.dp),
        )
        Text(
            text = timecode(run?.exposedNs ?: 0L),
            color = if (run != null) CinemaRed else Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** A run writes a JPEG whatever this is set to, so while one is chosen it says that and rests. */
@Composable
private fun FormatBadge(state: CameraUiState, viewModel: CameraViewModel, modifier: Modifier = Modifier) {
    val single = state.sequenceMode == SequenceMode.OFF
    val label = when {
        single -> state.settings.format.label
        state.sequenceMode == SequenceMode.LAPSE -> "MP4"
        else -> "JPEG"
    }
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clickable(enabled = single, onClick = viewModel::cycleFormat)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (single) Color.White else Color(0x99FFFFFF),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .border(1.dp, if (single) Color.White else Color(0x66FFFFFF), RoundedCornerShape(3.dp))
                .padding(horizontal = 5.dp, vertical = 3.dp),
        )
    }
}

private fun whiteBalanceLabel(state: CameraUiState): String {
    val settings = state.settings
    return if (settings.whiteBalance == WhiteBalance.CUSTOM) formatKelvin(settings.kelvin)
    else formatKelvin(settings.whiteBalance.kelvin)
}

private fun focusLabel(state: CameraUiState): String = when {
    state.settings.eyeFocus -> state.liveFocusDiopters?.let(::formatFocus) ?: "eye"
    !state.capabilities.supportsManualFocus -> "fixed"
    else -> formatFocus(state.settings.focusDiopters)
}

/** The presets of the value that was tapped, in a strip over the picture. Every one is a single tap. */
@Composable
fun PresetStrip(state: CameraUiState, viewModel: CameraViewModel, kind: ValueKind, modifier: Modifier = Modifier) {
    val caps = state.capabilities
    val settings = state.settings
    Box(modifier = modifier.fillMaxWidth().height(48.dp).background(StripGround)) {
        when (kind) {
            ValueKind.FOCUS -> Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("∞", color = Idle, fontSize = 13.sp)
                val closest = caps.minFocusDistance
                ThinSlider(
                    progress = if (closest > 0f) settings.focusDiopters / closest else 0f,
                    enabled = caps.supportsManualFocus && !settings.eyeFocus,
                    onProgress = { viewModel.setFocusDiopters(it * closest) },
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                Text(if (closest > 0f) formatFocus(closest) else "--", color = Idle, fontSize = 13.sp)
            }
            ValueKind.LENS -> Chips(
                labels = state.visibleLenses.map { lens ->
                    state.lensLabel(lens) + if (lens.warning != null || lens.id in state.troubled) " !" else ""
                },
                selected = state.visibleLenses.indexOfFirst { it.id == state.selectedLensId },
                onPick = { viewModel.selectLens(state.visibleLenses[it].id) },
            )
            ValueKind.ISO -> {
                val range = caps.isoRange
                if (range == null || !caps.supportsManualIso) {
                    StripNote("This lens sets its own sensitivity")
                } else {
                    val stops = remember(range) { isoStops(range.lower, range.upper) }
                    Chips(
                        labels = stops.map { it.toString() },
                        selected = nearestStop(stops, settings.iso.toDouble()),
                        onPick = { viewModel.setIso(stops[it]) },
                    )
                }
            }
            ValueKind.SPEED -> {
                val range = caps.exposureTimeRange
                if (range == null || !caps.supportsManualShutter) {
                    StripNote("This lens sets its own shutter")
                } else {
                    val stops = remember(range) { shutterStopsNs(range.lower, range.upper) }
                    Chips(
                        labels = stops.map(::formatShutter),
                        selected = nearestStop(stops, settings.exposureTimeNs.toDouble()),
                        onPick = { viewModel.setExposureTime(stops[it]) },
                    )
                }
            }
            ValueKind.WHITE -> {
                // The camera's own lights first, then the frame itself, then temperatures of our own.
                val presets = caps.whiteBalancePresets
                val kelvins = if (caps.supportsManualWhiteBalance) kelvinStops(2500, 10000) else emptyList()
                val custom = settings.whiteBalance == WhiteBalance.CUSTOM
                val selected = when {
                    state.whiteBalanceAdjust -> presets.size
                    custom -> presets.size + 1 + nearestStop(kelvins, settings.kelvin.toDouble())
                    else -> presets.indexOf(settings.whiteBalance)
                }
                Chips(
                    labels = presets.map { it.label } +
                        (if (caps.supportsManualWhiteBalance) listOf("ADJ") else emptyList()) +
                        kelvins.map(::formatKelvin),
                    selected = selected,
                    onPick = { index ->
                        when {
                            index < presets.size -> viewModel.setWhiteBalance(presets[index])
                            index == presets.size -> viewModel.toggleWhiteBalanceAdjust()
                            else -> viewModel.setKelvin(kelvins[index - presets.size - 1])
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun Chips(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    // Opens with the chosen one in view rather than at the start of a long list.
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (selected - 3).coerceAtLeast(0))
    LazyRow(state = list, modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        itemsIndexed(labels) { index, label ->
            val on = index == selected
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(min = 54.dp)
                    .background(if (on) Color(0xFFE8E8E8) else Color.Transparent)
                    .clickable { onPick(index) }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (on) Color(0xFF111111) else Idle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun StripNote(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Idle, fontSize = 12.sp)
    }
}

/** The histogram and the meter, each on its own small panel in a bottom corner of the picture. */
@Composable
fun ScopePanel(content: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier = modifier
            .width(128.dp)
            .clip(shape)
            .background(PanelGround)
            .border(1.dp, PanelEdge, shape)
            .padding(5.dp),
    ) { content() }
}

/** A tool beside the shutter: an icon, blue while it is on. */
@Composable
private fun Tool(
    path: String,
    description: String,
    active: Boolean,
    size: Dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) CinemaTile else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        LineIcon(
            path = path,
            color = when {
                !enabled -> Color(0x40FFFFFF)
                active -> CinemaInk
                else -> Color.White
            },
        )
    }
}

/** The tools either side of the shutter, as a row upright and a column on its side. */
@Composable
fun ToolStrip(
    state: CameraUiState,
    viewModel: CameraViewModel,
    vertical: Boolean,
    review: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (vertical) {
        // Six tools and the shutter need 360 dp. A phone on its side may have less, so they give
        // up a little size each rather than lose the ones at the ends.
        BoxWithConstraints(modifier = modifier.background(ToolGround)) {
            val shutter = if (maxHeight < 372.dp) 60.dp else 72.dp
            val tool = ((maxHeight - shutter) / 6).coerceIn(38.dp, 48.dp)
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { Tools(state, viewModel, tool, shutter, review) }
        }
    } else {
        Row(
            modifier = modifier.background(ToolGround),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) { Tools(state, viewModel, 48.dp, 72.dp, review) }
    }
}

@Composable
private fun Tools(
    state: CameraUiState,
    viewModel: CameraViewModel,
    tool: Dp,
    shutter: Dp,
    review: @Composable () -> Unit,
) {
    val caps = state.capabilities
    val items: List<@Composable () -> Unit> = listOf(
        {
            Tool(ICON_GRID, "Grid", state.aids.grid != GridMode.OFF, tool) {
                val grids = GridMode.entries
                viewModel.updateAids { it.copy(grid = grids[(grids.indexOf(it.grid) + 1) % grids.size]) }
            }
        },
        {
            Tool(ICON_EYE, "Eye focus", state.settings.eyeFocus, tool, enabled = caps.supportsFaceFocus) {
                viewModel.toggleEyeFocus()
            }
        },
        {
            Tool(ICON_PEAK, "Focus peaking", state.aids.peaking, tool) {
                viewModel.updateAids { it.copy(peaking = !it.peaking) }
            }
        },
        {
            CinemaShutter(
                busy = state.busy || state.countdown != null,
                enabled = state.selectedLensId != null,
                running = state.sequence != null,
                onClick = viewModel::shutter,
                diameter = shutter,
            )
        },
        { Tool(ICON_MAG, "Magnifier", state.magnify, tool, onClick = viewModel::toggleMagnify) },
        { Tool(ICON_QR, "Read a QR code", state.qrScan, tool, onClick = viewModel::toggleQrScan) },
        { Box(modifier = Modifier.size(tool), contentAlignment = Alignment.Center) { review() } },
    )
    items.forEach { it() }
}

/** A grey ring round a red dot, which turns into a square while a run is going. */
@Composable
fun CinemaShutter(
    busy: Boolean,
    enabled: Boolean,
    running: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 72.dp,
) {
    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .clickable(enabled = enabled && !busy, onClick = onClick)
            .semantics { contentDescription = if (running) "Stop" else "Shutter" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            drawCircle(
                color = if (enabled) Color(0xFFD7D8DA) else Color(0x4DD7D8DA),
                radius = (size.minDimension - stroke) / 2f,
                style = Stroke(stroke),
            )
            val ink = if (enabled && !busy) CinemaRed else CinemaRed.copy(alpha = 0.4f)
            if (running) {
                val side = size.minDimension * 0.36f
                drawRoundRect(
                    color = ink,
                    topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f),
                    size = Size(side, side),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                )
            } else {
                drawCircle(color = ink, radius = size.minDimension * 0.36f)
            }
        }
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(26.dp), color = Color.White, strokeWidth = 2.dp)
        }
    }
}

/**
 * Every mode one tap away. Resting while a run or a countdown is going. On its side Settings is the
 * last tab; upright the eight modes need the whole row to be wide enough to hit, so Settings sits
 * beside the timecode instead.
 */
@Composable
fun ModeBar(
    state: CameraUiState,
    viewModel: CameraViewModel,
    vertical: Boolean,
    modifier: Modifier = Modifier,
    settings: Boolean = true,
) {
    val free = !state.busy && state.sequence == null && state.countdown == null
    @Composable
    fun Tab(icon: String, title: String, chosen: Boolean, enabled: Boolean, tabModifier: Modifier, onClick: () -> Unit) {
        Column(
            modifier = tabModifier
                .clip(RoundedCornerShape(7.dp))
                .background(if (chosen) CinemaTile else Color.Transparent)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = title },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val ink = when {
                chosen -> CinemaInk
                enabled -> Idle
                else -> Color(0x55C8C9CB)
            }
            LineIcon(icon, ink, size = 20.dp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(title, color = ink, fontSize = 9.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
    @Composable
    fun Tabs(tabModifier: Modifier) {
        SequenceMode.entries.forEach { mode ->
            Tab(
                icon = modeIcon(mode),
                title = modeTitle(mode),
                chosen = mode == state.sequenceMode,
                enabled = free || mode == state.sequenceMode,
                tabModifier = tabModifier,
            ) { viewModel.selectSequenceMode(mode) }
        }
        if (settings) {
            Tab(ICON_SETTINGS, "Settings", state.settingsOpen, true, tabModifier, viewModel::openSettings)
        }
    }
    if (vertical) {
        Column(modifier = modifier.background(ModeGround).padding(horizontal = 6.dp, vertical = 3.dp)) {
            Tabs(Modifier.fillMaxWidth().weight(1f))
        }
    } else {
        Row(modifier = modifier.background(ModeGround).padding(horizontal = 3.dp, vertical = 6.dp)) {
            Tabs(Modifier.weight(1f).height(54.dp))
        }
    }
}
