package com.stephane.naviplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mudita.mmd.black

/**
 * How this app marks the thing you are on.
 *
 * A filled black area is the most expensive mark an E-Ink panel has: it costs a
 * full refresh rather than a partial one, and a large one leaves a ghost behind
 * when the content under it changes. So a fill is for something transient - a
 * row under your finger, gone the moment you lift it - and never for something
 * persistent. Persistent state gets a rule and a weight, which cost almost
 * nothing to redraw and do not dominate the screen they sit on.
 *
 * The thickness is MMD's own: its tab indicator is 3dp and square-ended
 * (ActiveIndicatorHeight, RectangleShape), which is what PrimaryTabRowMMD draws
 * above these. Named once here so the next indicator cannot drift to 2dp.
 */
private val MARK_THICKNESS = 3.dp

/** Marks the selected cell in a row of destinations, along its bottom edge. */
@Composable
fun BoxScope.SelectionUnderline() {
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(MARK_THICKNESS)
            .background(black)
    )
}

/** Marks one row out of a list, down its leading edge. */
@Composable
fun BoxScope.LeadingMark() {
    Box(
        modifier = Modifier
            .align(Alignment.CenterStart)
            .fillMaxHeight()
            .width(MARK_THICKNESS)
            .background(black)
    )
}
