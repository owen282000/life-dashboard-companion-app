package com.owen282000.lifedashboard.ui.ld

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.owen282000.lifedashboard.R

val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold)
)

val SpaceMono = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold)
)

/** The styles of the boards, in sp, so the phone's font size scales them. Line heights are the boards'. */
object LDType {
    private val noPadding = PlatformTextStyle(includeFontPadding = false)
    private val centered = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

    /** Space Grotesk at any size, for what the named styles below do not cover. */
    fun sans(
        size: TextUnit,
        weight: FontWeight = FontWeight.Normal,
        lineHeight: TextUnit = size * 1.3f,
        letterSpacing: TextUnit = TextUnit.Unspecified
    ) = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = weight,
        fontSize = size,
        lineHeight = lineHeight,
        letterSpacing = letterSpacing,
        platformStyle = noPadding,
        lineHeightStyle = centered
    )

    /** Space Mono for codes, times and technical values. */
    fun mono(size: TextUnit, bold: Boolean = false, lineHeight: TextUnit = size * 1.5f) = TextStyle(
        fontFamily = SpaceMono,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontSize = size,
        lineHeight = lineHeight,
        platformStyle = noPadding,
        lineHeightStyle = centered
    )

    val heading = sans(30.sp, FontWeight.Bold, 34.sp, (-0.5).sp)
    val topBar = sans(20.sp, FontWeight.Bold, 26.sp)
    val sectionLabel = sans(14.sp, FontWeight.Medium, 18.sp)
    val note = sans(14.sp, lineHeight = 20.sp)
    val noteSmall = sans(13.sp, lineHeight = 19.sp)
    val noteLarge = sans(16.sp, lineHeight = 22.sp)
    val row = sans(15.sp, FontWeight.Medium, 20.sp)
    val rowSubtitle = sans(13.sp, lineHeight = 18.sp)
    val value = sans(14.sp, lineHeight = 20.sp)
    val tileTitle = sans(16.sp, FontWeight.Bold, 21.sp)
    val destination = sans(16.sp, FontWeight.Medium, 22.sp)
    val destinationStatus = sans(15.sp, lineHeight = 20.sp)
    val tab = sans(13.sp, lineHeight = 17.sp)
    val monoField = mono(14.sp, lineHeight = 20.sp)
    val monoValue = mono(12.5.sp, lineHeight = 19.sp)

    /** Material's own components in Space Grotesk, at Material's sizes. */
    internal val material: Typography = Typography().let { base ->
        Typography(
            displayLarge = base.displayLarge.copy(fontFamily = SpaceGrotesk),
            displayMedium = base.displayMedium.copy(fontFamily = SpaceGrotesk),
            displaySmall = base.displaySmall.copy(fontFamily = SpaceGrotesk),
            headlineLarge = base.headlineLarge.copy(fontFamily = SpaceGrotesk),
            headlineMedium = base.headlineMedium.copy(fontFamily = SpaceGrotesk),
            headlineSmall = base.headlineSmall.copy(fontFamily = SpaceGrotesk),
            titleLarge = base.titleLarge.copy(fontFamily = SpaceGrotesk),
            titleMedium = base.titleMedium.copy(fontFamily = SpaceGrotesk),
            titleSmall = base.titleSmall.copy(fontFamily = SpaceGrotesk),
            bodyLarge = base.bodyLarge.copy(fontFamily = SpaceGrotesk),
            bodyMedium = base.bodyMedium.copy(fontFamily = SpaceGrotesk),
            bodySmall = base.bodySmall.copy(fontFamily = SpaceGrotesk),
            labelLarge = base.labelLarge.copy(fontFamily = SpaceGrotesk),
            labelMedium = base.labelMedium.copy(fontFamily = SpaceGrotesk),
            labelSmall = base.labelSmall.copy(fontFamily = SpaceGrotesk)
        )
    }
}

/** For a line with " · " in it: the dot stays with the word before it when the line wraps. */
val String.ldKeepingSeparators: String get() = replace(" · ", " · ")
