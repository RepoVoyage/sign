package com.repovoyage.sign.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 语桥主题（apple-design 重构，2026-09-24）：iOS HIG 质感。
 * 分层逻辑：浅 = 灰底 #F2F2F7 + 白卡片；深 = 纯黑底 + #1C1C1E 卡片——
 * 卡片永远比背景亮，靠灰阶差分层，不用描边/投影（卡片侧 elevation 0）。
 * tint 用 Apple systemBlue（#007AFF 加深至 #0066D6 保 AA 4.9:1）；
 * 警示橙/错误红同为系统色加深版；开关绿独立于 tint（见 [appleSwitchColors]）。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF0066D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9EBFF),
    onPrimaryContainer = Color(0xFF00264F),
    secondary = Color(0xFF0E7C8C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9EBFF),
    onSecondaryContainer = Color(0xFF0055B8),
    tertiary = Color(0xFFB25000),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE9DC),
    onTertiaryContainer = Color(0xFF4A1F00),
    error = Color(0xFFD70015),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFF2F2F7),
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFE9E9EE),
    onSurfaceVariant = Color(0xFF6E6E73),
    surfaceTint = Color(0xFF0066D6),
    surfaceContainerLowest = Color.White,      // iOS 分组卡片
    surfaceContainerLow = Color(0xFFFBFBFD),
    surfaceContainer = Color(0xFFF7F7FA),
    surfaceContainerHigh = Color(0xFFF0F0F5),  // 弹窗
    surfaceContainerHighest = Color(0xFFE9E9EE),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFFE3E3E8),        // 发丝分隔线
    scrim = Color.Black,
    inverseSurface = Color(0xFF1C1C1E),
    inverseOnSurface = Color(0xFFF2F2F7),
    inversePrimary = Color(0xFF0A84FF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF003A75),
    onPrimaryContainer = Color(0xFFD9EBFF),
    secondary = Color(0xFF40C8E0),
    onSecondary = Color(0xFF00282E),
    secondaryContainer = Color(0xFF18314B),
    onSecondaryContainer = Color(0xFF7FBFFF),
    tertiary = Color(0xFFFF9F0A),
    onTertiary = Color(0xFF3A1F00),
    tertiaryContainer = Color(0xFF4A3000),
    onTertiaryContainer = Color(0xFFFFE1B3),
    error = Color(0xFFFF453A),
    onError = Color.White,
    errorContainer = Color(0xFF680E08),
    onErrorContainer = Color(0xFFFFD6D1),
    background = Color.Black,
    onBackground = Color(0xFFF2F2F7),
    surface = Color.Black,
    onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF3A3A3C),
    onSurfaceVariant = Color(0xFF98989D),
    surfaceTint = Color(0xFF0A84FF),
    surfaceContainerLowest = Color(0xFF1C1C1E),  // iOS 深色分组卡片（比黑底亮）
    surfaceContainerLow = Color(0xFF232327),
    surfaceContainer = Color(0xFF28282C),
    surfaceContainerHigh = Color(0xFF2C2C2E),    // 弹窗
    surfaceContainerHighest = Color(0xFF3A3A3C),
    outline = Color(0xFF636366),
    outlineVariant = Color(0xFF38383C),
    scrim = Color.Black,
    inverseSurface = Color(0xFFF2F2F7),
    inverseOnSurface = Color(0xFF1C1C1E),
    inversePrimary = Color(0xFF0066D6),
)

/**
 * 字幕优先的排版（字幕是 hero：22sp/30sp + 0.3sp 字距，远距离可读）。
 * chrome 按 SF Pro 尺度：Body 17 / Subheadline 15 / Footnote 13 / Caption 12
 *（≥12sp 红线保留）；CJK 不 bundling 显示字体（包体），以尺度/字重对比承载个性。
 */
object YuqiaoType {
    val subtitle = TextStyle(
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.3.sp,
        fontWeight = FontWeight.Medium,
    )
    val subtitleDraft = TextStyle(
        fontSize = 18.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.2.sp,
        fontWeight = FontWeight.Normal,
    )
}

private val YuqiaoTypography = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(
            fontSize = 17.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        titleSmall = titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
        bodyMedium = bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
        bodySmall = bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelMedium = labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        labelSmall = labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
    )
}

/** iOS 圆角：卡片 12（medium）、输入框 10（extraSmall）、弹窗 14（extraLarge） */
private val YuqiaoShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
)

/** iOS 开关绿（HIG：开关开态独立于 tint；浅 #34C759 / 深 #30D158） */
@Composable
fun appleSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedTrackColor = if (isSystemInDarkTheme()) Color(0xFF30D158) else Color(0xFF34C759),
    checkedThumbColor = Color.White,
)

@Composable
fun SignTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = YuqiaoTypography,
        shapes = YuqiaoShapes,
        content = content,
    )
}
