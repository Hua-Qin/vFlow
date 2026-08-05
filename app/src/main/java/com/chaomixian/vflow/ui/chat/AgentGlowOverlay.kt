package com.chaomixian.vflow.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

// 彩虹光圈颜色（青/黄/橙/粉循环）
private val RainbowColors = listOf(
    Color(0xFFB0F2FF),
    Color(0xFFFAFAA3),
    Color(0xFFFFB472),
    Color(0xFFFB8DFF),
    Color(0xFFB0F2FF),
    Color(0xFFFB8DFF),
    Color(0xFFFFB472),
    Color(0xFFFAFAA3),
    Color(0xFFB0F2FF),
)

private val RainbowPositions = floatArrayOf(
    0f, 0.13f, 0.257f, 0.37f, 0.505f, 0.634f, 0.744f, 0.87f, 1f
)

/**
 * AI 工作时的屏幕边缘亮光特效。
 * 半透明压暗 + 彩虹色旋转 SweepGradient 光圈（带模糊）。
 * 参考 Eta 项目的 AgentOverlayGlow 实现，适配 Material 3。
 *
 * @param active 是否处于活跃状态（AI 正在工作中）
 * @param dimAlpha 背景压暗程度（0-1）
 */
@Composable
fun AgentGlowOverlay(
    active: Boolean,
    modifier: Modifier = Modifier,
    dimAlpha: Float = 0.15f,
) {
    if (!active) return

    val transition = rememberInfiniteTransition(label = "glow")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(5000), RepeatMode.Restart),
        label = "rotation",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                // 半透明压暗
                drawRect(color = Color.Black.copy(alpha = dimAlpha))

                // 彩虹光圈：SweepGradient 描边 + 模糊
                val w = size.width
                val h = size.height
                val cx = w / 2f
                val cy = h / 2f
                val strokePx = 40f
                val colorsArgb = RainbowColors.map { it.toArgb() }

                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = strokePx
                        maskFilter = android.graphics.BlurMaskFilter(
                            strokePx,
                            android.graphics.BlurMaskFilter.Blur.NORMAL,
                        )
                    }
                    val shader = android.graphics.SweepGradient(
                        cx, cy, colorsArgb.toIntArray(), RainbowPositions
                    )
                    val matrix = android.graphics.Matrix()
                    matrix.setRotate(rotation, cx, cy)
                    shader.setLocalMatrix(matrix)
                    paint.shader = shader
                    val rect = android.graphics.RectF(0f, 0f, w, h)
                    canvas.nativeCanvas.drawRoundRect(rect, 30f, 30f, paint)
                }
            }
    )
}
