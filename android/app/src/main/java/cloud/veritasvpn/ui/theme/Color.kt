package cloud.veritasvpn.ui.theme

import androidx.compose.ui.graphics.Color

// Frozen Veritas brand palette (matches website/css/style.css).
// Premium surfaces may only change opacity, elevation, and gradients of these hues.
// Gradient: cyan -> royal on charcoal ink
//
// Cyan            #09C7F5
// CyanHover       #4AD9FA
// CyanSoft        #09C7F5 @ 14% (0x24)
// Royal           #0756D9
// RoyalHover      #2877EE
// BlueDeep        #06265C
// Ink             #010814
// Ink2            #06101F
// Ink3            #0A1729
// CardBg          #081527
// CardElevated    #0B1C32
// Paper           #FFFFFF
// PaperMuted      #ADC3DB
// PaperDim        #7189A5
// Line            #2167A8 @ 20% (0x33)
// LineStrong      #408FD4 @ 40% (0x66)
// SuccessGreen    Cyan (#09C7F5)
// ErrorRed        #FF6B7A
// WarningOrange   #FFB74D

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
