package com.stephane.naviplayer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.mudita.mmd.black

/** Tall enough to take a thumb, and fixed so it cannot be squeezed to nothing. */
private val SCRUBBER_HEIGHT = 44.dp

/** The rail: an outlined bar, filled to the left of the playhead. */
private val RAIL_HEIGHT = 10.dp
private val RAIL_BORDER = 2.dp

/** Corner to corner. The diamond sits inside the rail's span at both ends. */
private val HEAD_SIZE = 16.dp

/**
 * The seek line, drawn here rather than taken from SliderMMD.
 *
 * SliderMMD measures a thumb, a track and an interactive minimum against each
 * other and settles on whatever height that produces. That is fine on a phone
 * with room to spare; on this screen it is one more thing that can quietly
 * resolve to nothing, and a seek line that is sometimes absent is worse than a
 * plain one. This is a fixed height, two rectangles and a diamond: it draws the
 * same every time, and it is the shape the design called for anyway - a round
 * handle at this size dithers into a smudge, where a square on its corner keeps
 * four hard edges.
 *
 * [fraction] is clamped, so an unknown duration can pass 0f and still get a
 * rail. Set [enabled] false in that case: there is nothing to seek within.
 */
@Composable
fun Scrubber(
    fraction: Float,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onScrub: (Float) -> Unit = {},
    onScrubFinished: () -> Unit = {},
) {
    val safe = fraction.coerceIn(0f, 1f)

    val gestures = if (!enabled) {
        Modifier
    } else {
        Modifier
            .pointerInput(Unit) {
                detectTapGestures { position ->
                    onScrub(fractionAt(position.x, size.width.toFloat(), HEAD_SIZE.toPx()))
                    onScrubFinished()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { position ->
                        onScrub(fractionAt(position.x, size.width.toFloat(), HEAD_SIZE.toPx()))
                    },
                    onDragEnd = onScrubFinished,
                    onDragCancel = onScrubFinished,
                ) { change, _ ->
                    onScrub(fractionAt(change.position.x, size.width.toFloat(), HEAD_SIZE.toPx()))
                }
            }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(SCRUBBER_HEIGHT)
            .then(gestures),
    ) {
        val head = HEAD_SIZE.toPx()
        val rail = RAIL_HEIGHT.toPx()
        val border = RAIL_BORDER.toPx()

        // The rail stops half a diamond short at each end, so the playhead is
        // still whole when it is at 0:00 or at the end.
        val left = head / 2f
        val span = (size.width - head).coerceAtLeast(0f)
        val top = (size.height - rail) / 2f

        drawRect(
            color = black,
            topLeft = Offset(left, top),
            size = Size(span, rail),
            style = Stroke(width = border),
        )
        if (safe > 0f) {
            drawRect(
                color = black,
                topLeft = Offset(left, top),
                size = Size(span * safe, rail),
            )
        }

        val centreX = left + span * safe
        val centreY = size.height / 2f
        val reach = head / 2f
        drawPath(
            path = Path().apply {
                moveTo(centreX, centreY - reach)
                lineTo(centreX + reach, centreY)
                lineTo(centreX, centreY + reach)
                lineTo(centreX - reach, centreY)
                close()
            },
            color = black,
        )
    }
}

/** Where a touch lands on the rail, which is inset by half a playhead. */
private fun fractionAt(x: Float, width: Float, headPx: Float): Float {
    val span = width - headPx
    if (span <= 0f) return 0f
    return ((x - headPx / 2f) / span).coerceIn(0f, 1f)
}
