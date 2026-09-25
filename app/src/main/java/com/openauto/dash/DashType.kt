package com.openauto.dash

import androidx.compose.material3.Typography
import androidx.compose.ui.unit.sp

/*
 * The type scale for a screen read at arm's length while driving.
 *
 * Material's defaults are sized for a phone held 30 cm from the eyes; a head
 * unit sits at 60–70 cm and is glanced at, not read. Every role is lifted so
 * nothing on a tile goes under 14 sp, labels and chips sit at 16 sp and body
 * text at 18 sp. Hero numerals (speed, clock) are sized by their tile and are
 * not part of this scale. Installed once, in OpenAutoDashTheme, so every
 * `MaterialTheme.typography.*` reference on the dashboard follows it.
 */
internal val DashTypography: Typography = Typography().let { m ->
    m.copy(
        titleLarge = m.titleLarge.copy(fontSize = 24.sp, lineHeight = 30.sp),
        titleMedium = m.titleMedium.copy(fontSize = 20.sp, lineHeight = 26.sp),
        titleSmall = m.titleSmall.copy(fontSize = 18.sp, lineHeight = 24.sp),
        bodyLarge = m.bodyLarge.copy(fontSize = 18.sp, lineHeight = 24.sp),
        bodyMedium = m.bodyMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
        bodySmall = m.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = m.labelLarge.copy(fontSize = 16.sp, lineHeight = 22.sp),
        labelMedium = m.labelMedium.copy(fontSize = 16.sp, lineHeight = 20.sp),
        labelSmall = m.labelSmall.copy(fontSize = 14.sp, lineHeight = 18.sp)
    )
}
