package com.example.soul_knight_save_editor.unlock

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Original vector backdrop: sparse archive routes, index marks and open folder outlines. */
@Composable internal fun ArchiveBackdrop(mode: AssistantMode?, modifier: Modifier = Modifier) {
    val palette = MaterialTheme.colorScheme
    val top = when (mode) {
        null -> Color(0xFF51495F)
        AssistantMode.QUICK -> Color(0xFFF6F2FA)
        AssistantMode.EXPERT -> Color(0xFF241A32)
        AssistantMode.FREE -> Color(0xFF111113)
    }
    val bottom = if (mode == null) Color(0xFF302B39) else palette.background
    val ink = palette.onBackground.copy(alpha = if (mode == AssistantMode.QUICK) .055f else .065f)
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(top, bottom)))
        val tile = 180.dp.toPx()
        val line = Stroke(1.dp.toPx(), cap = StrokeCap.Round)
        for (row in -1..(size.height / tile).toInt()) {
            for (column in -1..(size.width / tile).toInt()) {
                val x = column * tile + if (row % 2 == 0) 0f else tile / 2
                val y = row * tile
                val route = Path().apply {
                    moveTo(x, y + tile * .18f)
                    lineTo(x + tile * .28f, y + tile * .18f)
                    quadraticTo(x + tile * .36f, y + tile * .18f, x + tile * .36f, y + tile * .26f)
                    lineTo(x + tile * .36f, y + tile * .43f)
                    quadraticTo(x + tile * .36f, y + tile * .51f, x + tile * .44f, y + tile * .51f)
                    lineTo(x + tile * .79f, y + tile * .51f)
                }
                drawPath(route, ink, style = line)
                drawCircle(ink, 3.dp.toPx(), Offset(x + tile * .79f, y + tile * .51f), style = line)
                val folder = Path().apply {
                    moveTo(x + tile * .10f, y + tile * .83f)
                    lineTo(x + tile * .10f, y + tile * .67f)
                    lineTo(x + tile * .23f, y + tile * .67f)
                    lineTo(x + tile * .28f, y + tile * .72f)
                    lineTo(x + tile * .47f, y + tile * .72f)
                    lineTo(x + tile * .47f, y + tile * .88f)
                    lineTo(x + tile * .17f, y + tile * .88f)
                }
                drawPath(folder, ink, style = line)
                repeat(3) { mark ->
                    val point = Offset(x + tile * .70f + mark * 7.dp.toPx(), y + tile * .84f)
                    drawLine(ink, point, point + Offset(0f, 5.dp.toPx()), line.width, StrokeCap.Round)
                }
            }
        }
    }
}

/** The free-mode card gets a single file/bracket illustration, never the page's repeating pattern. */
@Composable internal fun FreeCardArtwork(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val ink = Color.White.copy(alpha = .10f)
        val stroke = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
        val width = 80.dp.toPx()
        val x = size.width - width - 18.dp.toPx()
        val y = (size.height - width) / 2
        repeat(3) { sheet ->
            val shift = sheet * 9.dp.toPx()
            drawRoundRect(ink, Offset(x + shift - 20.dp.toPx(), y - shift), Size(width, width * 1.15f),
                CornerRadius(9.dp.toPx()), style = stroke)
        }
        val bracket = Path().apply {
            moveTo(x + width * .24f, y + width * .25f)
            lineTo(x + width * .04f, y + width * .46f)
            lineTo(x + width * .24f, y + width * .67f)
            moveTo(x + width * .62f, y + width * .25f)
            lineTo(x + width * .82f, y + width * .46f)
            lineTo(x + width * .62f, y + width * .67f)
        }
        drawPath(bracket, Color.White.copy(alpha = .16f), style = stroke)
    }
}
