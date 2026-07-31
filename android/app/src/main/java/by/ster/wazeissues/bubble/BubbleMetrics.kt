package by.ster.wazeissues.bubble

import android.content.res.Resources
import kotlin.math.min

/** Screen-relative bubble sizes (dp). Hub is separate from the actions strip. */
data class BubbleMetrics(
    val iconDp: Float,
    val hubDp: Float,
    val gapDp: Float,
    /** Extra room around circles so Compose shadows are not clipped by the window. */
    val shadowPadDp: Float,
    /** Actions-only strip (no hub), including shadow pad. */
    val actionsWidthDp: Float,
    val actionsHeightDp: Float,
    val speedWidthDp: Float,
    val speedHeightDp: Float,
) {
    companion object {
        fun from(resources: Resources, direction: BubbleExpandDirection): BubbleMetrics {
            val dm = resources.displayMetrics
            val density = dm.density.coerceAtLeast(0.5f)
            val minPx = min(dm.widthPixels, dm.heightPixels)
            val minDp = minPx / density
            val icon = (minDp * 0.14f).coerceIn(56f, 80f)
            val gap = icon * 0.12f
            val hub = icon
            val shadowPad = 6f
            val actionsSpan = 4 * icon + 3 * gap
            val (actionsInnerW, actionsInnerH) =
                when (direction) {
                    BubbleExpandDirection.Up, BubbleExpandDirection.Down -> icon to actionsSpan
                    BubbleExpandDirection.Left, BubbleExpandDirection.Right -> actionsSpan to icon
                }
            return BubbleMetrics(
                iconDp = icon,
                hubDp = hub,
                gapDp = gap,
                shadowPadDp = shadowPad,
                actionsWidthDp = actionsInnerW + 2 * shadowPad,
                actionsHeightDp = actionsInnerH + 2 * shadowPad,
                speedWidthDp = (minDp * 0.55f).coerceIn(220f, 320f),
                speedHeightDp = (minDp * 0.72f).coerceIn(280f, 420f),
            )
        }
    }
}
