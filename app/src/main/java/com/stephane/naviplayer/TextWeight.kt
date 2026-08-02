package com.stephane.naviplayer

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * A weight hierarchy on top of MMD's size scale.
 *
 * MMD sets all ten styles in eInkTypography to Medium, and its Lato family
 * ships no medium file - so weight 500 resolves down to lato_regular and the
 * whole interface renders in one weight. Hierarchy then has to come from size
 * alone, which is a slight difference on a small screen, and regular Lato's
 * thin strokes are the first thing an E-Ink panel loses.
 *
 * MMD bundles lato_bold and lato_black and its scale uses neither. Both are
 * real faces, so nothing here is synthesised - which matters, because a
 * synthesised bold is the regular outline smeared outwards and dithers into
 * mush.
 *
 * Applied to what names a thing and not to what describes it: bold everywhere
 * would be the same as bold nowhere.
 */

/**
 * Black, for type big enough to carry it - screen titles and the first line of
 * a row. Its counters stay open at 18sp and up, and it is the heaviest mark the
 * panel can hold cleanly.
 */
fun TextStyle.heavy(): TextStyle = copy(fontWeight = FontWeight.Black)

/**
 * Bold, for the small labels on tabs, navigation and controls. Black closes up
 * at label sizes and turns to a blob once dithered, so these get one step less
 * weight and a little tracking instead, which stops bold letterforms merging
 * into each other.
 */
fun TextStyle.strong(): TextStyle = copy(
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.3.sp,
)
