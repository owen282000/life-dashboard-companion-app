package com.owen282000.lifedashboard.ui.theme

import androidx.compose.ui.graphics.Color

// Primary is the brand green, not a teal of its own. The Material primary role is rendered:
// text selection handles and the cursor pick it up, which a magenta test made visible. Teal
// next to the brand green was the same confusion the two greens had, one layer down.
// OnPrimary is the brand ground rather than white, because white on #30B77E is 2.56:1 while
// the dark ground is 6.97:1, and it matches how the icon shows green on dark.
val OnPrimary = Color(0xFF14181C)

// Background & Surface. SurfaceDark sits 4.8 dE from BackgroundDark, which is the separation a
// base and an elevated surface need; the brand grounds further down are gradient stops, not
// surfaces, and are deliberately closer together than that.
val BackgroundLight = Color(0xFFF5F7FA)
val BackgroundDark = Color(0xFF0D1117)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceDark = Color(0xFF161B22)
val SurfaceVariantLight = Color(0xFFF1F5F9)
val SurfaceVariantDark = Color(0xFF1E2530)

// The brand green from docs/brand (icon, banner, feature graphic). One green, one meaning.
// Health Connect used to carry its own #10B981, 3.4 dE away, which nobody could tell apart
// from this one but which meant the icon and the app disagreed about what "our green" is.
// Changing code is cheap and changing a published icon is not, so the brand value won.
val BrandGreen = Color(0xFF30B77E)

// Health Connect accent. An alias, not a second green: see BrandGreen above.
val HealthPrimary = BrandGreen
val HealthContainer = Color(0xFFD1FAE5)
val OnHealthContainer = Color(0xFF064E3B)
val HealthContainerDark = Color(0xFF064E3B)
val OnHealthContainerDark = Color(0xFFA7F3D0)

// Screen Time specific colors
val ScreenTimePrimary = Color(0xFF8B5CF6) // Purple (matching emerald subtlety)
val ScreenTimeContainer = Color(0xFFEDE9FE)
val OnScreenTimeContainer = Color(0xFF4C1D95)
val ScreenTimeContainerDark = Color(0xFF4C1D95)
val OnScreenTimeContainerDark = Color(0xFFDDD6FE)

// Status colors. Every container has a light and a dark pair: the light ones are the tint-100
// with a -800/-900 on-colour, the dark ones invert that to a -900 container with a -200
// on-colour, which clears 6.9:1 in every case. Measured, not assumed.
val Success = Color(0xFF22C55E)
val SuccessContainer = Color(0xFFDCFCE7)
val OnSuccessContainer = Color(0xFF166534)
val SuccessContainerDark = Color(0xFF14532D)
val OnSuccessContainerDark = Color(0xFFBBF7D0)

val Error = Color(0xFFEF4444)
val ErrorContainer = Color(0xFFFEE2E2)
val OnErrorContainer = Color(0xFF991B1B)
val ErrorContainerDark = Color(0xFF7F1D1D)
val OnErrorContainerDark = Color(0xFFFECACA)

val Warning = Color(0xFFF59E0B)
val WarningContainer = Color(0xFFFEF3C7)
val OnWarningContainer = Color(0xFF92400E)
val WarningContainerDark = Color(0xFF78350F)
val OnWarningContainerDark = Color(0xFFFDE68A)

// Text colors
val TextPrimary = Color(0xFF1F2937)
val TextSecondary = Color(0xFF6B7280)

// Dark mode text
val TextPrimaryDark = Color(0xFFF9FAFB)
val TextSecondaryDark = Color(0xFFD1D5DB)

// Logs tab accent: the third colour next to Health green and Screen Time purple
val LogsPrimary = Color(0xFF2F80ED)
val LogsContainer = Color(0xFFDBEAFE)
val OnLogsContainer = Color(0xFF1E3A8A)
val LogsContainerDark = Color(0xFF1E3A8A)
val OnLogsContainerDark = Color(0xFFBFDBFE)

// The three stops of the brand's radial gradient, identical in banner.html, icon.html,
// feature-graphic.html and the About hero. They sit within dE 5 of each other by design:
// merging them would flatten the gradient into a single flat fill, so they stay three.
val BrandGroundLight = Color(0xFF171D21)
val BrandGround = Color(0xFF14181C)
val BrandGroundDeep = Color(0xFF101C1C)
