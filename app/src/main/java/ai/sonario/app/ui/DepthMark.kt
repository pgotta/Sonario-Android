package ai.sonario.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * The depth-charge mark: Sonario desktop's signature hero element (a sinking
 * charge with a blinking light and sonar rings). Rebuilt as a small Compose
 * canvas so the phone app keeps the same nautical identity.
 */
@Composable
fun DepthMark(modifier: Modifier = Modifier, markSize: androidx.compose.ui.unit.Dp = 72.dp) {
    val transition = rememberInfiniteTransition(label = "depth")

    // Sonar ring expansion
    val ring by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "ring")

    // Blinking charge light
    val blink by transition.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "blink")

    Canvas(modifier = modifier.size(markSize)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxR = size.minDimension / 2f

        // sonar rings
        val rings = 3
        for (i in 0 until rings) {
            val phase = (ring + i.toFloat() / rings) % 1f
            val r = maxR * phase
            val alpha = (1f - phase) * 0.6f
            drawCircle(
                color = SonarioColors.Teal.copy(alpha = alpha),
                radius = r, center = Offset(cx, cy),
                style = Stroke(width = 1.5.dp.toPx()))
        }

        // the charge barrel
        val barrelW = size.width * 0.34f
        val barrelH = size.height * 0.30f
        val left = cx - barrelW / 2f
        val top = cy - barrelH / 2f
        drawRoundRect(
            brush = Brush.horizontalGradient(
                listOf(SonarioColors.Abyss, SonarioColors.Panel2, SonarioColors.Abyss)),
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(barrelW, barrelH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))

        // the blinking light on top
        drawCircle(
            color = SonarioColors.Green.copy(alpha = blink),
            radius = 3.dp.toPx(),
            center = Offset(cx, top - 3.dp.toPx()))
    }
}
