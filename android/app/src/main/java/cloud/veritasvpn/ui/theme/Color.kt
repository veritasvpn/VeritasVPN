package cloud.veritasvpn.ui.theme

import androidx.compose.ui.graphics.Color

// Veritas brand palette (matches website/css/style.css)
// Gradient: cyan -> royal on charcoal ink

val Cyan = Color(0xFF09C7F5)
val CyanHover = Color(0xFF4AD9FA)
val CyanSoft = Color(0x2409C7F5)
val Royal = Color(0xFF0756D9)
val RoyalHover = Color(0xFF2877EE)
val BlueDeep = Color(0xFF06265C)

val Ink = Color(0xFF010814)
val Ink2 = Color(0xFF06101F)
val Ink3 = Color(0xFF0A1729)
val CardBg = Color(0xFF081527)
val CardElevated = Color(0xFF0B1C32)

val Paper = Color(0xFFFFFFFF)
val PaperMuted = Color(0xFFADC3DB)
val PaperDim = Color(0xFF7189A5)

val Line = Color(0x332167A8)
val LineStrong = Color(0x66408FD4)

val SuccessGreen = Cyan
val ErrorRed = Color(0xFFFF6B7A)
val WarningOrange = Color(0xFFFFB74D)

val GradientCyanToRoyal = listOf(Cyan, Royal)
val GradientDarkToCyan = listOf(Ink, BlueDeep)
val GradientSurface = listOf(Ink, Ink2, BlueDeep.copy(alpha = 0.62f))
