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
 * Black, for the one piece of type that names the screen you are on. Reserved
 * for that: a single heaviest mark reads as a title, while a column of them
 * reads as a wall.
 */
fun TextStyle.heavy(): TextStyle = copy(fontWeight = FontWeight.Black)

/**
 * Bold, for everything else that names rather than describes - the first line
 * of a row, and the labels on tabs, navigation and controls.
 *
 * Row titles were Black briefly and it was too dense: one of them is crisp, but
 * a screenful stacked four rows deep turns the list into a solid block. Bold
 * still separates a title from the line under it without that. The tracking
 * stops bold letterforms merging once the panel has dithered them, which is
 * what small labels suffer from.
 */
fun TextStyle.strong(): TextStyle = copy(
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.3.sp,
)

/**
 * The register for chrome: tabs, navigation, and the labels on controls.
 *
 * Set in caps at the call site with tracking opened up to match. Caps read as a
 * fixed row of marks rather than a word with a shape, which is what you want on
 * something you hit rather than read - and the extra tracking is not decorative,
 * it is what stops adjacent capitals closing into each other once the panel has
 * dithered them.
 *
 * Still Lato. MMD ships no second face, and borrowing a system monospace to get
 * a device look would mean leaving the design system to imitate it.
 */
fun TextStyle.chrome(): TextStyle = copy(
    fontWeight = FontWeight.Bold,
    letterSpacing = 1.0.sp,
)
