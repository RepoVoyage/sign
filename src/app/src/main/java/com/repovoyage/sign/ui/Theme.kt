package com.repovoyage.sign.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 语桥主题（品牌：logo 蓝青渐变手语声波 #1666D6 / #17D1E8 / 底 #F6F8FB）。
 * 设计方向：Swiss 极简 + 信任感（skill design-system 匹配），色板按用户
 * 品牌覆盖；关键文本对比度已校验（light ≥4.6:1，dark ≥10:1）。
 * 浅色 primary 用加深蓝 #1259C0（6.14:1），深色 primary 用亮青 #4FD8EE
 * （10.8:1）；logo 原蓝/青保留在容器色与渐变点缀中。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF1259C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E6FF),
    onPrimaryContainer = Color(0xFF001B3F),
    secondary = Color(0xFF0E7C8C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC4F0F6),
    onSecondaryContainer = Color(0xFF001F24),
    tertiary = Color(0xFF44576E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD6E3F5),
    onTertiaryContainer = Color(0xFF0E1725),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6F8FB),
    onBackground = Color(0xFF101826),
    surface = Color(0xFFF6F8FB),
    onSurface = Color(0xFF101826),
    surfaceVariant = Color(0xFFE1E6EE),
    onSurfaceVariant = Color(0xFF444B57),
    surfaceTint = Color(0xFF1259C0),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFCFE),
    surfaceContainer = Color(0xFFF0F3F8),
    surfaceContainerHigh = Color(0xFFEAEFF5),
    surfaceContainerHighest = Color(0xFFE4E9F0),
    outline = Color(0xFF747B87),
    outlineVariant = Color(0xFFC4CAD4),
    scrim = Color.Black,
    inverseSurface = Color(0xFF2C313A),
    inverseOnSurface = Color(0xFFF0F2F6),
    inversePrimary = Color(0xFFAFC6FF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FD8EE),
    onPrimary = Color(0xFF00363E),
    primaryContainer = Color(0xFF004E5A),
    onPrimaryContainer = Color(0xFF9CF0FF),
    secondary = Color(0xFF7FD4E2),
    onSecondary = Color(0xFF00363D),
    secondaryContainer = Color(0xFF004F58),
    onSecondaryContainer = Color(0xFFC4F0F6),
    tertiary = Color(0xFFBFC8DC),
    onTertiary = Color(0xFF2A3140),
    tertiaryContainer = Color(0xFF3F4859),
    onTertiaryContainer = Color(0xFFDCE4F8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1520),
    onBackground = Color(0xFFE3E8F0),
    surface = Color(0xFF0E1520),
    onSurface = Color(0xFFE3E8F0),
    surfaceVariant = Color(0xFF404857),
    onSurfaceVariant = Color(0xFFBFC8DC),
    surfaceTint = Color(0xFF4FD8EE),
    surfaceContainerLowest = Color(0xFF090F19),
    surfaceContainerLow = Color(0xFF111925),
    surfaceContainer = Color(0xFF151D2A),
    surfaceContainerHigh = Color(0xFF1F2836),
    surfaceContainerHighest = Color(0xFF293241),
    outline = Color(0xFF8A919E),
    outlineVariant = Color(0xFF404857),
    scrim = Color.Black,
    inverseSurface = Color(0xFFE3E8F0),
    inverseOnSurface = Color(0xFF2C313A),
    inversePrimary = Color(0xFF1259C0),
)

/**
 * 字幕优先的排版（frontend-design 重构）：字幕是 hero——22sp/30sp + 0.3sp
 * 字距（远距离可读）；草稿/历史 18sp；chrome 标签 12sp 起（≥12sp 红线）。
 * CJK 不 bundling 显示字体（包体），刻意以尺度/字重对比承载个性。
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
        bodyLarge = bodyLarge.copy(fontSize = 16.sp, lineHeight = 26.sp),
        bodyMedium = bodyMedium.copy(lineHeight = 22.sp),
        labelSmall = labelSmall.copy(fontSize = 12.sp),
    )
}

@Composable
fun SignTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = YuqiaoTypography,
        content = content,
    )
}
