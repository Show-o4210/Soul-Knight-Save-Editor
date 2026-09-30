package com.example.soul_knight_save_editor.unlock

import android.graphics.Paint
import android.graphics.Typeface
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.isActive

/** Full-screen title Easter egg; closes without changing navigation or save drafts. */
@Composable internal fun ShowcheerEgg(firstDiscovery: Boolean, onDismiss: () -> Unit) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var letters by remember { mutableStateOf(emptyList<CheerLetter>()) }
    var elapsed by remember { mutableDoubleStateOf(0.0) }
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val reduced = remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val latestDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(viewport, density, reduced) {
        if (viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
        val physics = CheerPhysics(viewport.width / density.toDouble(), viewport.height / density.toDouble(), reduced)
        elapsed = 0.0
        letters = physics.bodies.map { it.copy() }
        var previous: Long? = null
        while (isActive && !physics.done) {
            val frame = withFrameNanos { it }
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { previous = null; continue }
            previous?.let { physics.advance((frame - it).coerceAtLeast(0) / 1_000_000_000.0) }
            previous = frame
            elapsed = physics.elapsed
            letters = physics.bodies.map { it.copy() }
        }
        latestDismiss()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val letterPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) } }
        val messagePaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL) } }
        Canvas(Modifier.fillMaxSize().background(Color(0xFF1D1330)).clickable(onClick = onDismiss)
            .testTag("showcheer-egg").semantics { contentDescription = "SHOWCHEER 彩蛋，点击任意位置退出" }.onSizeChanged { viewport = it }) {
            val width = size.width / density
            val height = size.height / density
            val radius = minOf(24f, width / (CheerPhysics.WORD.length * 2 + 2), height / 12)
            val formed = reduced || elapsed >= CheerPhysics.FORM_AT - 1e-9
            if (formed && !reduced) repeat(18) { index ->
                val phase = (elapsed - CheerPhysics.FORM_AT) * (.35 + index * .015)
                drawCircle(if (index % 2 == 0) Color(0x66FFD54F) else Color(0x557EE081),
                    (2.5f + index % 3) * density, Offset(size.width * ((index * 37 % 100) / 100f),
                        size.height * (.18f + (index * 23 % 65) / 100f) - phase.toFloat() * 24 * density))
            }
            drawContext.canvas.nativeCanvas.apply {
                letterPaint.textSize = radius * 1.75f * density
                letterPaint.color = 0xFFFFD54F.toInt()
                val baseline = -(letterPaint.ascent() + letterPaint.descent()) / 2
                letters.forEach { letter ->
                    val x = letter.x.toFloat() * density; val y = letter.y.toFloat() * density
                    save(); rotate(letter.angle.toFloat(), x, y)
                    drawText(letter.char.toString(), x, y + baseline, letterPaint); restore()
                }
                if (formed) {
                    val opacity = if (reduced) 1.0 else ((elapsed - CheerPhysics.FORM_AT) / .35).coerceIn(0.0, 1.0)
                    messagePaint.textSize = minOf(15f * density, size.width / 23)
                    messagePaint.color = android.graphics.Color.argb((opacity * 194).toInt(), 255, 255, 255)
                    drawText(if (firstDiscovery) "骑士档案馆 · 保持好奇。" else "欢迎回来，骑士。", size.width / 2, size.height * .53f, messagePaint)
                    messagePaint.textSize = 11f * density
                    messagePaint.color = 0x66FFFFFF
                    drawText("点击任意位置退出", size.width / 2, size.height * .88f, messagePaint)
                }
            }
        }
    }
}
