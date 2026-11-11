package dev.henny.hha_blood.client.boss

import net.minecraft.entity.LivingEntity
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Animation helpers of the two pit bosses (client only): easing curves for keyframe tracks, a damped spring for
 * secondary motion (cloth, tails, ears, jaws) and [Motion] - what the client can see of an entity from frame to
 * frame (speed, vertical speed, turning, gait phase, action restarts, landing), kept per entity by the renderers.
 */
internal object Ease {
    private fun c(x: Float) = x.coerceIn(0f, 1f)

    /** Smoothstep. */
    fun inOut(x: Float): Float { val t = c(x); return t * t * (3f - 2f * t) }
    fun linear(x: Float): Float = c(x)
    fun inQuad(x: Float): Float { val t = c(x); return t * t }
    fun inCubic(x: Float): Float { val t = c(x); return t * t * t }
    fun outQuad(x: Float): Float { val t = c(x); return 1f - (1f - t) * (1f - t) }
    fun outCubic(x: Float): Float { val u = 1f - c(x); return 1f - u * u * u }

    /** Overshoots the target by ~10 % and settles (a strike that carries through). */
    fun outBack(x: Float): Float = back(x, 1.70158f)

    /** Bigger overshoot (~20 %). */
    fun outBackBig(x: Float): Float = back(x, 2.8f)

    private fun back(x: Float, s: Float): Float { val u = c(x) - 1f; return 1f + (s + 1f) * u * u * u + s * u * u }

    /** Pulls back a little before it goes (anticipation inside one segment). */
    fun inBack(x: Float): Float { val t = c(x); val s = 1.4f; return (s + 1f) * t * t * t - s * t * t }

    /** Heavy landing: falls in, bounces once, settles. */
    fun outBounce(x: Float): Float {
        val t = c(x)
        return when {
            t < 0.6f -> { val u = t / 0.6f; u * u }
            t < 0.85f -> { val u = (t - 0.6f) / 0.25f; 1f - 0.12f * sin(u * PI.toFloat()) }
            else -> { val u = (t - 0.85f) / 0.15f; 1f - 0.03f * sin(u * PI.toFloat()) }
        }
    }

    /** 0 -> 1 -> 0 bump over x in 0..1 (sine). */
    fun bump(x: Float): Float = sin(c(x) * PI.toFloat())

    /** Smooth multi-frequency wobble in -1..1 (for "alive" idle motion), [seed] decorrelates channels. */
    fun wobble(t: Float, seed: Float): Float =
        sin(t * 0.131f + seed) * 0.5f + sin(t * 0.0713f + seed * 2.31f) * 0.32f + sin(t * 0.293f + seed * 0.71f) * 0.18f

    /** A short pulse every [period] ticks (0..1, peaks at the start), deterministic per [seed]. */
    fun every(t: Float, period: Float, length: Float, seed: Float = 0f): Float {
        val k = ((t + seed * period) % period + period) % period
        return if (k < length) bump(k / length) else 0f
    }
}

/** Damped spring for secondary motion: [freq] in rad/tick, [damp] 1 = critical, < 1 wobbles. */
internal class Spring(private val freq: Float, private val damp: Float) {
    var x = 0f
        private set
    var v = 0f
        private set
    private var started = false

    fun step(target: Float, dt: Float): Float {
        if (!started) { x = target; v = 0f; started = true; return x }
        var left = dt.coerceIn(0f, 4f)
        while (left > 1e-4f) {
            val h = min(left, 0.25f)
            val a = freq * freq * (target - x) - 2f * damp * freq * v
            v += a * h
            x += v * h
            left -= h
        }
        return x
    }

    fun kick(dv: Float) { v += dv }
}

/**
 * What the client sees of a boss between two frames. [update] is called once per rendered frame (the renderers'
 * updateRenderState) and returns the frame time step in ticks (0 if the frame did not advance).
 */
internal class Motion {
    var time = Float.NaN
        private set
    private var lastX = 0.0
    private var lastY = 0.0
    private var lastZ = 0.0
    private var lastYaw = 0f
    /** Horizontal speed (blocks/tick, smoothed). */
    var speed = 0f
        private set
    /** Vertical speed (blocks/tick, smoothed, + = up). */
    var vy = 0f
        private set
    /** Turning speed of the body (degrees/tick, smoothed, + = turning right). */
    var turn = 0f
        private set
    /** Gait phase driven by the distance walked (radians, see [advanceGait]). */
    var gait = 0f
    /** Phase driven by turning on the spot. */
    var turnStep = 0f
    /** Free running phase (time based) for runs/spins. */
    var run = 0f

    /** Current action as seen last frame, the action before it and the frame time it started. */
    var action = Int.MIN_VALUE
        private set
    var previous = Int.MIN_VALUE
        private set
    private var lastAge = 0
    /** True for the frame in which a new action (or a restart of the same one) was seen. */
    var started = false
        private set
    /** Action time at which the boss touched the ground again in a jump (-1 = not yet). */
    var landT = -1f
    private var wasFalling = false
    /** Was it in the air (left the ground) during this action? */
    var airborne = false
        private set

    /** Pose of the previous frame and the cross-fade from it into a new action. */
    var lastPose: Any? = null
    var fromPose: Any? = null
    var blendStart = 0f
    var blendLen = 0f

    /** Free per-renderer extras (e.g. the alive heads when an attack started). */
    var mark = 0
    var markTime = -999f

    fun update(e: LivingEntity, tickDelta: Float, action: Int, actionAge: Int): Float {
        val now = e.age + tickDelta
        val pos = e.getLerpedPos(tickDelta)
        val yaw = net.minecraft.util.math.MathHelper.lerpAngleDegrees(tickDelta, e.lastBodyYaw, e.bodyYaw)
        started = false
        if (time.isNaN() || now < time - 0.01f || now - time > 40f) {
            time = now
            lastX = pos.x; lastY = pos.y; lastZ = pos.z; lastYaw = yaw
            action0(action, actionAge, now)
            return 0f
        }
        val dt = (now - time).coerceIn(0f, 4f)
        if (action != this.action || actionAge < lastAge) action0(action, actionAge, now)
        lastAge = actionAge
        if (dt <= 1e-4f) return 0f
        time = now
        val dx = pos.x - lastX
        val dy = pos.y - lastY
        val dz = pos.z - lastZ
        var dyaw = yaw - lastYaw
        while (dyaw > 180f) dyaw -= 360f
        while (dyaw < -180f) dyaw += 360f
        lastX = pos.x; lastY = pos.y; lastZ = pos.z; lastYaw = yaw
        val k = 1f - exp(-dt / 2.2f)
        val inst = (sqrt(dx * dx + dz * dz) / dt).toFloat()
        speed += (min(inst, 3f) - speed) * k
        vy += ((dy / dt).toFloat().coerceIn(-3f, 3f) - vy) * (1f - exp(-dt / 1.2f))
        turn += ((dyaw / dt).coerceIn(-60f, 60f) - turn) * (1f - exp(-dt / 2.5f))
        if (!e.isOnGround || abs(vy) > 0.08f) airborne = true
        return dt
    }

    private fun action0(a: Int, age: Int, now: Float) {
        previous = action
        action = a
        lastAge = age
        started = true
        landT = -1f
        wasFalling = false
        airborne = false
    }

    /** Advances the gait by [distance] blocks for a cycle of [cycle] blocks (at most [maxRate] radians per tick). */
    fun advanceGait(dt: Float, cycle: Float, maxRate: Float) {
        val rate = min(speed / cycle * 2f * PI.toFloat(), maxRate)
        gait = (gait + rate * dt) % (2f * PI.toFloat() * 64f)
    }

    /** Detects the landing of a jump that started at action time [from]: returns the action time of the landing or -1. */
    fun landing(e: LivingEntity, t: Float, from: Float, latest: Float): Float {
        if (landT >= 0f) return landT
        if (t < from) return -1f
        if (vy < -0.12f) wasFalling = true
        val down = t > from + 3f && (e.isOnGround && (wasFalling || t > from + 8f) || wasFalling && vy > -0.04f)
        if (down || t > latest) landT = t
        return landT
    }

    companion object {
        /** Unit-free knee/hip helpers. */
        fun rot(y: Float, z: Float, a: Float): FloatArray = floatArrayOf(y * cos(a) - z * sin(a), y * sin(a) + z * cos(a))
    }
}
