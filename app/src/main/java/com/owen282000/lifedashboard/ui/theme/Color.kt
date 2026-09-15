package com.owen282000.lifedashboard.ui.theme

import androidx.compose.ui.graphics.Color

// Primary - Teal/Cyan tones for health/wellness feel
val Primary = Color(0xFF00897B)
val PrimaryLight = Color(0xFF4DB6AC)
val PrimaryDark = Color(0xFF00695C)
val OnPrimary = Color(0xFFFFFFFF)

// Secondary - Warm orange for screen time
val Secondary = Color(0xFFFF7043)
val SecondaryLight = Color(0xFFFFAB91)
val SecondaryDark = Color(0xFFE64A19)

// Background & Surface
val BackgroundLight = Color(0xFFF5F7FA)
val BackgroundDark = Color(0xFF0D1117)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceDark = Color(0xFF161B22)

// The brand green from docs/brand (icon, banner, feature graphic). One green, one meaning.
// Health Connect used to carry its own #10B981, 3.4 dE away, which nobody could tell apart
// from this one but which meant the icon and the app disagreed about what "our green" is.
// Changing code is cheap and changing a published icon is not, so the brand value won.
val BrandGreen = Color(0xFF30B77E)

// Health Connect accent. An alias, not a second green: see BrandGreen above.
val HealthPrimary = BrandGreen

// Screen Time specific colors
val ScreenTimePrimary = Color(0xFF8B5CF6) // Purple (matching emerald subtlety)

// Status colors
val Success = Color(0xFF22C55E)

val Error = Color(0xFFEF4444)
val ErrorContainer = Color(0xFFFEE2E2)
val OnErrorContainer = Color(0xFF991B1B)

val Warning = Color(0xFFF59E0B)

// Text colors
val TextPrimary = Color(0xFF1F2937)
val TextSecondary = Color(0xFF6B7280)

// Dark mode text
val TextPrimaryDark = Color(0xFFF9FAFB)
val TextSecondaryDark = Color(0xFFD1D5DB)

// Logs tab accent: the third colour next to Health green and Screen Time purple
val LogsPrimary = Color(0xFF2F80ED)

// The three stops of the brand's radial gradient, identical in banner.html, icon.html,
// feature-graphic.html and the About hero. They sit within dE 5 of each other by design:
// merging them would flatten the gradient into a single flat fill, so they stay three.
val BrandGroundLight = Color(0xFF171D21)
val BrandGround = Color(0xFF14181C)
val BrandGroundDeep = Color(0xFF101C1C)
