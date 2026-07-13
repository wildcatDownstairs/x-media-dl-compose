package net.xmediadl.app.ui

import androidx.compose.ui.graphics.Color

/**
 * App 的语义色板。
 *
 * 所有页面只引用这里的颜色角色，而不在业务 Composable 中重复定义主色。这样调整深色
 * 主题、对比度或品牌强调色时只需修改一个位置。
 */
object AppColors {
    val Background = Color(0xFF0A0A0A)
    val Surface = Color(0xFF161616)
    val Border = Color.White.copy(alpha = 0.16f)
    val BorderActive = Color.White.copy(alpha = 0.36f)
    val TextPrimary = Color(0xFFF0F0F0)
    val TextSecondary = Color(0xFF999999)
    val TextMuted = Color(0xFF555555)
    val Accent = Color(0xFFE7FF52)
    val AccentText = Color(0xFF0A0A0A)
    val Danger = Color(0xFFFF4D4D)
}
