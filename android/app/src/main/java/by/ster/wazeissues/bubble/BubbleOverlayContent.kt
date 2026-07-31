package by.ster.wazeissues.bubble

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import by.ster.wazeissues.R
import by.ster.wazeissues.ui.LengthGesture
import by.ster.wazeissues.ui.ReportController
import kotlin.math.hypot
import kotlin.math.roundToInt

private val SpeedSignRed = Color(0xFFE30613)
private val SpeedSignBg = Color(0xFFFFFFF8)
private val SpeedSignText = Color(0xFF1A1A1A)
private val SpeedEndGrey = Color(0xFF7A7A7A)
private val BumpAddBg = Color(0xFF1B7A3D)
private val BumpRemoveBg = Color(0xFFB3261E)
private val HubBg = Color(0xFF1565C0)
private val GeneralBg = Color(0xFFFFC107)
private val SpeedBtnBg = Color(0xFF0D47A1)
private val BubbleShadow = 4.dp

/** Fixed hub window — only the label changes with [phase]. */
@Composable
fun BubbleHubOverlay(
    reports: ReportController,
    phase: BubblePhase,
    hubDp: Float,
    onOpen: () -> Unit,
    onCollapse: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    @Suppress("UNUSED_PARAMETER")
    val unusedReports = reports
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val expanded = phase != BubblePhase.Collapsed
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(expanded) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var dragged = false
                        var total = 0.0
                        drag(down.id) { change ->
                            val dx = change.positionChange().x
                            val dy = change.positionChange().y
                            total += hypot(dx.toDouble(), dy.toDouble())
                            if (total > touchSlop) {
                                dragged = true
                                onDrag(dx, dy)
                            }
                            change.consume()
                        }
                        if (!dragged) {
                            if (expanded) onCollapse() else onOpen()
                        }
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        HubCircle(
            label = if (expanded) "X" else "WI",
            size = hubDp.dp,
        )
    }
}

/** Actions-only strip beside the hub (hub is a separate window). */
@Composable
fun BubbleActionsOverlay(
    reports: ReportController,
    direction: BubbleExpandDirection,
    metrics: BubbleMetrics,
    onGeneral: () -> Unit,
    onBumpAdd: () -> Unit,
    onBumpRemove: () -> Unit,
    onSpeed: () -> Unit,
) {
    @Suppress("UNUSED_PARAMETER")
    val unusedReports = reports
    val icon = metrics.iconDp.dp
    val gap = metrics.gapDp.dp
    data class Action(
        val bg: Color,
        val onClick: () -> Unit,
        val content: @Composable () -> Unit,
    )
    // From hub outward: remove → add → km → general.
    val fromHub =
        listOf(
            Action(BumpRemoveBg, onBumpRemove) {
                MiniBumpIcon(add = false, iconSize = icon * 0.55f)
            },
            Action(BumpAddBg, onBumpAdd) { MiniBumpIcon(add = true, iconSize = icon * 0.55f) },
            Action(SpeedBtnBg, onSpeed) {
                Text(
                    "km",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (metrics.iconDp * 0.26f).sp,
                )
            },
            Action(GeneralBg, onGeneral) {
                Text(
                    "!",
                    color = Color(0xFF212121),
                    fontWeight = FontWeight.Bold,
                    fontSize = (metrics.iconDp * 0.38f).sp,
                )
            },
        )
    // Paint order so the icon nearest the hub is listed last for Up/Left strips.
    val items =
        when (direction) {
            BubbleExpandDirection.Up, BubbleExpandDirection.Left -> fromHub.asReversed()
            BubbleExpandDirection.Down, BubbleExpandDirection.Right -> fromHub
        }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (direction) {
            BubbleExpandDirection.Up, BubbleExpandDirection.Down ->
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    items.forEach { a ->
                        ActionCircle(size = icon, bg = a.bg, onClick = a.onClick, content = a.content)
                    }
                }
            BubbleExpandDirection.Left, BubbleExpandDirection.Right ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    items.forEach { a ->
                        ActionCircle(size = icon, bg = a.bg, onClick = a.onClick, content = a.content)
                    }
                }
        }
    }
}

@Composable
fun BubbleSpeedOverlay(
    reports: ReportController,
    onBack: () -> Unit,
    onCollapse: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val state by reports.state.collectAsStateWithLifecycle()
    MaterialTheme {
        SpeedPanel(
            lengthGesture = state.lengthGesture,
            onBack = onBack,
            onCollapse = onCollapse,
            onSpeedTap = { kmh ->
                reports.reportSpeed(kmh)
                onCollapse()
            },
            onLengthStart = { reports.beginLengthGesture(it) },
            onLengthUpdate = { reports.updateLengthGesture(it) },
            onLengthEnd = {
                reports.finishLengthGesture()
                onCollapse()
            },
            onOpenApp = onOpenApp,
        )
    }
}

@Composable
private fun ActionCircle(
    size: Dp,
    bg: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(size)
                .shadow(BubbleShadow, CircleShape)
                .clip(CircleShape)
                .background(bg)
                .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun HubCircle(
    label: String,
    size: Dp,
) {
    Box(
        modifier =
            Modifier
                .size(size)
                .shadow(BubbleShadow, CircleShape)
                .clip(CircleShape)
                .background(HubBg)
                .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.32f).sp,
        )
    }
}

@Composable
private fun MiniBumpIcon(
    add: Boolean,
    iconSize: Dp,
) {
    Canvas(Modifier.size(iconSize)) {
        val w = this.size.width
        val h = this.size.height
        val bump =
            Path().apply {
                moveTo(w * 0.08f, h * 0.72f)
                quadraticTo(w * 0.5f, h * 0.18f, w * 0.92f, h * 0.72f)
                lineTo(w * 0.08f, h * 0.72f)
                close()
            }
        drawPath(path = bump, color = Color.White)
        val cx = w * 0.82f
        val cy = h * 0.88f
        val r = h * 0.1f
        drawLine(
            color = Color.White,
            start = Offset(cx - r, cy),
            end = Offset(cx + r, cy),
            strokeWidth = h * 0.06f,
            cap = StrokeCap.Round,
        )
        if (add) {
            drawLine(
                color = Color.White,
                start = Offset(cx, cy - r),
                end = Offset(cx, cy + r),
                strokeWidth = h * 0.06f,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun SpeedPanel(
    lengthGesture: LengthGesture?,
    onBack: () -> Unit,
    onCollapse: () -> Unit,
    onSpeedTap: (Int) -> Unit,
    onLengthStart: (Int) -> Boolean,
    onLengthUpdate: (Int) -> Unit,
    onLengthEnd: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val speeds = listOf(20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 0)
    val density = LocalDensity.current
    Surface(
        color = Color(0xF2FFFFFF),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 6.dp,
        modifier =
            Modifier
                .fillMaxSize()
                .padding(4.dp),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.back))
                }
                TextButton(onClick = onOpenApp) {
                    Text(stringResource(R.string.bubble_open_app), fontSize = 12.sp)
                }
            }
            lengthGesture?.let { gesture ->
                LengthBanner(gesture)
                Spacer(Modifier.height(4.dp))
            }
            val edgePadPx = with(density) { 16.dp.toPx() }
            var panelCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
            Column(
                Modifier
                    .weight(1f)
                    .onGloballyPositioned { panelCoords = it },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                speeds.chunked(4).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        row.forEach { kmh ->
                            if (kmh == 0) {
                                EndSpeedButton(
                                    onClick = { onSpeedTap(0) },
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                SpeedButton(
                                    kmh = kmh,
                                    onClick = { onSpeedTap(kmh) },
                                    onLengthGestureStart = { onLengthStart(kmh) },
                                    onLengthGestureUpdate = { rootX ->
                                        val left =
                                            panelCoords?.localToRoot(Offset.Zero)?.x ?: 0f
                                        val width =
                                            panelCoords?.size?.width?.toFloat()
                                                ?: with(density) { 220.dp.toPx() }
                                        val usable = (width - 2 * edgePadPx).coerceAtLeast(1f)
                                        val t =
                                            ((rootX - left - edgePadPx) / usable).coerceIn(0f, 1f)
                                        val meters =
                                            (t * ReportController.LENGTH_MAX_M /
                                                ReportController.LENGTH_STEP_M)
                                                .roundToInt() *
                                                ReportController.LENGTH_STEP_M
                                        onLengthUpdate(meters)
                                    },
                                    onLengthGestureEnd = onLengthEnd,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            TextButton(
                onClick = onCollapse,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(R.string.bubble_close_menu))
            }
        }
    }
}

@Composable
private fun LengthBanner(gesture: LengthGesture) {
    val lengthLabel =
        if (gesture.lengthM == 0) {
            stringResource(R.string.length_unlimited)
        } else {
            stringResource(R.string.length_meters, gesture.lengthM)
        }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                stringResource(R.string.length_gesture_title, gesture.kmh),
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
            Text(lengthLabel, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Slider(
                value = gesture.lengthM.toFloat(),
                onValueChange = {},
                valueRange =
                    ReportController.LENGTH_MIN_M.toFloat()..ReportController.LENGTH_MAX_M.toFloat(),
                steps =
                    (ReportController.LENGTH_MAX_M - ReportController.LENGTH_MIN_M) /
                        ReportController.LENGTH_STEP_M - 1,
                enabled = false,
            )
        }
    }
}

@Composable
private fun SpeedButton(
    kmh: Int,
    onClick: () -> Unit,
    onLengthGestureStart: () -> Boolean,
    onLengthGestureUpdate: (rootX: Float) -> Unit,
    onLengthGestureEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(
        modifier =
            modifier
                .aspectRatio(1f)
                .clip(CircleShape)
                .onGloballyPositioned { coords = it }
                .pointerInput(kmh) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val longPress = awaitLongPressOrCancellation(down.id)
                        if (longPress == null) {
                            val stillDown =
                                currentEvent.changes.any { it.id == down.id && it.pressed }
                            if (stillDown) {
                                do {
                                    val event = awaitPointerEvent()
                                    val change =
                                        event.changes.firstOrNull { it.id == down.id } ?: break
                                } while (change.pressed)
                            } else {
                                onClick()
                            }
                            return@awaitEachGesture
                        }
                        if (!onLengthGestureStart()) {
                            do {
                                val event = awaitPointerEvent()
                                val change =
                                    event.changes.firstOrNull { it.id == longPress.id } ?: break
                            } while (change.pressed)
                            return@awaitEachGesture
                        }
                        fun emitFrom(local: Offset) {
                            val rootX = coords?.localToRoot(local)?.x ?: local.x
                            onLengthGestureUpdate(rootX)
                        }
                        emitFrom(longPress.position)
                        drag(longPress.id) { change ->
                            emitFrom(change.position)
                            change.consume()
                        }
                        onLengthGestureEnd()
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(1.dp)
                    .clip(CircleShape)
                    .background(SpeedSignBg)
                    .border(width = 3.dp, color = SpeedSignRed, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$kmh",
                color = SpeedSignText,
                fontSize = if (kmh >= 100) 14.sp else 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun EndSpeedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .aspectRatio(1f)
                .clip(CircleShape)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(1.dp)
                    .clip(CircleShape)
                    .background(SpeedSignBg)
                    .border(width = 2.5.dp, color = SpeedEndGrey, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                drawLine(
                    color = SpeedEndGrey,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                    strokeWidth = size.minDimension * 0.18f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
