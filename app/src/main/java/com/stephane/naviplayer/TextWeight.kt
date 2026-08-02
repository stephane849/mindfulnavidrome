package com.stephane.naviplayer

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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
 * The register for chrome: tabs, navigation, section headings, the labels on
 * controls, and anything counted.
 *
 * A second face, deliberately. MMD ships only Lato, so this is the one place
 * the app steps outside the design system - and it earns it: chrome is not
 * prose. It is a fixed set of words you hit rather than read, and a monospaced
 * face makes them a rack of switches instead of a sentence. Counts and
 * positions stop shuffling sideways as their digits change, too, which is what
 * tabular figures are for and Lato has none.
 *
 * Set in caps at the call site. Less tracking than the proportional version
 * needed: a monospaced glyph already carries its own side bearings, so opening
 * it further would only make the words fall apart.
 *
 * FontFamily.Monospace rather than a bundled file - it resolves to whatever the
 * device ships and costs nothing to carry. If the Kompakt's mono turns out to
 * be poor, bundling a known face is the fix.
 */
fun TextStyle.chrome(bold: Boolean = true): TextStyle = copy(
    fontFamily = FontFamily.Monospace,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    letterSpacing = 0.5.sp,
)
