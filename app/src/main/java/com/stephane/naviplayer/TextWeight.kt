package com.stephane.naviplayer

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * A weight hierarchy on top of MMD's size scale.
 *
 * MMD sets every style in eInkTypography to Medium, and its Lato family ships
 * no medium file - so weight 500 resolves down to lato_regular and the whole
 * interface renders in one weight. Hierarchy then has to come from size alone,
 * which on a small screen is a slight difference, and regular Lato's thin
 * strokes are the first thing an E-Ink panel loses.
 *
 * MMD does bundle lato_bold, so [strong] gets a real bold face rather than a
 * synthesised one, which is what stays crisp after the panel has dithered it.
 * Applied to what names a thing - screen titles, headings, the first line of a
 * row, the label on a control - and deliberately not to what merely describes
 * it, since bold everywhere is the same as bold nowhere.
 */
fun TextStyle.strong(): TextStyle = copy(fontWeight = FontWeight.Bold)
