package com.example.soul_knight_save_editor.unlock

import kotlin.math.*
import kotlin.random.Random

data class CheerTap(val triggered: Boolean = false, val firstDiscovery: Boolean = false, val hint: String? = null)

/** Header lifetime is the session; history changes the greeting, not the initial seven-tap threshold. */
class CheerTrigger(private var discovered: Boolean) {
    private var lastAt: Long? = null
    private var count = 0
    private var replay = false
    fun tap(now: Long): CheerTap {
        count = if (lastAt?.let { now >= it && now - it <= 1200 } == true) count + 1 else 1
        lastAt = now
        val threshold = if (replay) 3 else 7
        if (count >= threshold) {
            val first = !discovered
            discovered = true
            replay = true
            count = 0
            return CheerTap(triggered = true, firstDiscovery = first)
        }
        return CheerTap(hint = if (replay) null else when (threshold - count) {
            3 -> "再点击 3 次……"; 2 -> "好像有东西松动了"; 1 -> "最后一下！"; else -> null
        })
    }
}

data class CheerLetter(val char: Char, var x: Double, var y: Double, var vx: Double, var vy: Double,
    var angle: Double, var spin: Double)

/** Adapted from the supplied SHOWCHEER portable core: fixed-step logical-pixel physics. */
class CheerPhysics(val width: Double, val height: Double, val reducedMotion: Boolean = false) {
    companion object {
        const val WORD = "SHOWCHEER"
        const val STEP = 1.0 / 120
        const val FORM_AT = 5.25
        const val DURATION = 8.0
    }
    val radius = minOf(24.0, width / (WORD.length * 2 + 2), height / 12)
    val fontSize get() = radius * 1.75
    var elapsed = 0.0
        private set
    private var accumulator = 0.0
    val formed get() = reducedMotion || elapsed >= FORM_AT - 1e-9
    val done get() = elapsed >= DURATION - 1e-9
    val bodies: List<CheerLetter>
    init {
        require(width.isFinite() && height.isFinite() && width > 0 && height > 0)
        val random = Random(20260905)
        bodies = WORD.mapIndexed { index, char ->
            CheerLetter(char, width * (.12 + random.nextDouble() * .76), -radius * (1.5 + index * 1.25),
                random.nextDouble() * 360 - 180, random.nextDouble() * 100, random.nextDouble() * 70 - 35, random.nextDouble() * 150 - 75)
        }
        if (reducedMotion) form()
    }
    fun targetX(index: Int) = width / 2 + (index - (WORD.length - 1) / 2.0) * min(width / (WORD.length + 1), radius * 1.75)
    private fun form() = bodies.forEachIndexed { index, b ->
        b.x = targetX(index); b.y = height * .46; b.vx = 0.0; b.vy = 0.0; b.angle = 0.0; b.spin = 0.0
    }
    fun advance(seconds: Double) {
        require(seconds.isFinite() && seconds >= 0)
        accumulator += min(seconds, .1)
        while (!done && accumulator + 1e-12 >= STEP) {
            accumulator = max(0.0, accumulator - STEP)
            elapsed = min(DURATION, elapsed + STEP)
            step()
        }
    }
    private fun constrain(b: CheerLetter) {
        if (b.x < radius) { b.x = radius; b.vx = abs(b.vx) * .72 }
        if (b.x > width - radius) { b.x = width - radius; b.vx = -abs(b.vx) * .72 }
        val floor = height - radius * 1.8
        if (b.y > floor) { b.y = floor; b.vy = -abs(b.vy) * .58; b.vx *= .88 }
    }
    private fun step() {
        if (formed) { form(); return }
        val dropping = elapsed < 3.25 - 1e-9
        bodies.forEachIndexed { index, b ->
            if (dropping) b.vy += 1750 * STEP else {
                b.vx += ((targetX(index) - b.x) * 100 - b.vx * 18) * STEP
                b.vy += ((height * .46 - b.y) * 100 - b.vy * 18) * STEP
                b.spin *= exp(-8 * STEP); b.angle *= exp(-8 * STEP)
            }
            b.x += b.vx * STEP; b.y += b.vy * STEP; b.angle += b.spin * STEP
            if (dropping) constrain(b)
        }
        if (!dropping) return
        for (i in bodies.indices) for (j in i + 1 until bodies.size) {
            val a = bodies[i]; val b = bodies[j]
            val dx = b.x - a.x; val dy = b.y - a.y
            val distance = hypot(dx, dy); val minimum = radius * 1.45
            if (distance >= minimum) continue
            val nx = if (distance > 1e-9) dx / distance else 1.0
            val ny = if (distance > 1e-9) dy / distance else 0.0
            val overlap = (minimum - distance) / 2
            a.x -= nx * overlap; a.y -= ny * overlap; b.x += nx * overlap; b.y += ny * overlap
            val relative = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny
            if (relative < 0) {
                val impulse = -relative * .82
                a.vx -= impulse * nx; a.vy -= impulse * ny; b.vx += impulse * nx; b.vy += impulse * ny
            }
        }
        bodies.forEach(::constrain)
    }
}
