package dev.henny.hha_blood.client.boss

import dev.henny.hha_blood.HhaBlood
import dev.henny.hha_blood.boss.CerberusEntity
import dev.henny.hha_blood.client.boss.Anim.mix
import dev.henny.hha_blood.client.boss.Anim.ramp
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.EntityRendererFactory
import net.minecraft.client.render.entity.LivingEntityRenderer
import net.minecraft.client.render.entity.MobEntityRenderer
import net.minecraft.client.render.entity.feature.FeatureRenderer
import net.minecraft.client.render.entity.feature.FeatureRendererContext
import net.minecraft.client.render.entity.model.EntityModel
import net.minecraft.client.render.entity.model.EntityModelLayer
import net.minecraft.client.render.entity.state.LivingEntityRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Identifier
import net.minecraft.util.math.RotationAxis
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class CerberusRenderState : LivingEntityRenderState() {
    var action = CerberusEntity.IDLE
    var t = 0f
    var unleashed = false
    var pose: Any? = null
    var drop = 0f
    var roll = 0f
    /** Broken heads (bit i = head i, see [CerberusEntity.brokenMask]): drawn with the broken skin and without glow. */
    var broken = 0
    /** How far each head has gone limp (0..1, eased on the client so a breaking head collapses instead of snapping). */
    val limp = FloatArray(3)

    /** Head of the current BITE, heads alive when the attack started (bit mask), action time a head broke (-1). */
    var biteHead = 0
    var aliveAtStart = 7
    var brokeAt = -999f
    // motion seen by the client (see Motion)
    var speed = 0f
    var vy = 0f
    var turn = 0f
    var gait = 0f
    var turnStep = 0f
    var landT = -1f
    /** Each head follows the target with its own lag (radians). */
    val look = FloatArray(3)
    var tail2 = 0f
    var tail3 = 0f
    var tailLash = 0f
}

/** Pose of the hound: index 0 = bleed head (right), 1 = flame head (middle), 2 = howl head (left). */
internal class CPose(
    val neckPitch: FloatArray = FloatArray(3), val neckYaw: FloatArray = FloatArray(3),
    val headPitch: FloatArray = FloatArray(3), val jaw: FloatArray = floatArrayOf(0.1f, 0.1f, 0.1f),
    /** front right, front left, back right, back left (upper, lower) */
    val leg: FloatArray = FloatArray(4), val lower: FloatArray = FloatArray(4),
    var bodyPitch: Float = 0f, var drop: Float = 0f, var roll: Float = 0f, var tailPitch: Float = 0f, var tailYaw: Float = 0f,
    /** Head tilt per head (radians) and ears (+ = pinned back). */
    val headRoll: FloatArray = FloatArray(3), val ear: FloatArray = FloatArray(3),
    /** Side sway of the body (radians). */
    var sway: Float = 0f,
    /** 1 = the paws decide pitch and height ([bodyPitch] and [drop] become offsets of the result), 0 = as given. */
    var ground: Float = 0f,
    /** 1 = only the hind paws stand (rearing up), the front is free. */
    var rear: Float = 0f,
    /** Extra height offset (blocks, + = lower) on top of the ground lock. */
    var sink: Float = 0f,
) {
    fun copy() = CPose(neckPitch.copyOf(), neckYaw.copyOf(), headPitch.copyOf(), jaw.copyOf(), leg.copyOf(), lower.copyOf(), bodyPitch, drop, roll, tailPitch, tailYaw,
        headRoll.copyOf(), ear.copyOf(), sway, ground, rear, sink)

    fun mixTo(o: CPose, t: Float): CPose {
        fun m(a: FloatArray, b: FloatArray) = FloatArray(a.size) { mix(a[it], b[it], t) }
        return CPose(m(neckPitch, o.neckPitch), m(neckYaw, o.neckYaw), m(headPitch, o.headPitch), m(jaw, o.jaw), m(leg, o.leg), m(lower, o.lower),
            mix(bodyPitch, o.bodyPitch, t), mix(drop, o.drop, t), mix(roll, o.roll, t), mix(tailPitch, o.tailPitch, t), mix(tailYaw, o.tailYaw, t),
            m(headRoll, o.headRoll), m(ear, o.ear), mix(sway, o.sway, t), mix(ground, o.ground, t).coerceIn(0f, 1f), mix(rear, o.rear, t).coerceIn(0f, 1f), mix(sink, o.sink, t))
    }

    fun with(change: CPose.() -> Unit): CPose = copy().apply(change)
}

internal class CKey(val t: Float, val p: CPose, val e: (Float) -> Float = Ease::inOut)

/**
 * Blood Cerberus model (tools/textures/GenCerberus.java -> CerberusModelData). Three instances are used: the skin pass
 * (broken necks hidden), the glow pass (broken necks hidden, so their eyes go dark) and the broken pass (only the broken
 * necks, drawn with the broken skin).
 * Motion: a diagonal trot that turns into a gallop when it is fast, the paws decide pitch and height of the body
 * (ground lock), the three heads move on their own (looking around, snapping out of phase, ears, jaws), every attack
 * has anticipation -> strike on the server's hit tick -> follow-through -> recovery.
 */
class CerberusModel(root: ModelPart, private val pass: Pass = Pass.SKIN) : EntityModel<CerberusRenderState>(root) {
    enum class Pass { SKIN, GLOW, BROKEN }

    private val body = root.getChild("body")
    private val bodyKids = CerberusModelData.BODY_CHILDREN.map { body.getChild(it) }
    private val names = arrayOf("bleed", "flame", "howl")
    private val necks = names.map { body.getChild("neck_$it") }
    private val heads = names.mapIndexed { i, n -> necks[i].getChild("head_$n") }
    private val jaws = names.mapIndexed { i, n -> heads[i].getChild("jaw_$n") }
    private val earsR = names.mapIndexed { i, n -> heads[i].getChild("head_${n}_ear_r") }
    private val earsL = names.mapIndexed { i, n -> heads[i].getChild("head_${n}_ear_l") }
    private val legNames = listOf("leg_front_right", "leg_front_left", "leg_back_right", "leg_back_left")
    private val legs = legNames.map { body.getChild(it) }
    private val lowers = legs.mapIndexed { i, l -> l.getChild(legNames[i] + "_lower") }
    private val tail = body.getChild("tail")
    private val tail2 = tail.getChild("tail2")
    private val tail3 = tail2.getChild("tail3")

    companion object {
        val LAYER = EntityModelLayer(HhaBlood.id("blood_cerberus"), "main")

        /** World blocks: the hound falls onto its left side around this edge (the outer side of its left shoulder). */
        const val ROLL_PIVOT = 0.95f

        /** Model unit in blocks. */
        internal const val UNIT = CerberusEntity.RENDER_SCALE / 16f
        private const val BODY_Y = -26f
        private const val FLOOR = 24f
        /** Hip pivots (y, z) relative to the body pivot: front, back. */
        private val HIP = arrayOf(floatArrayOf(-4f, -24f), floatArrayOf(-8f, 22f))
        /** Lower leg pivot (z, y) relative to the hip, and the contact points of the lower leg (z, y; pivot, toe, heel). */
        private val KNEE = arrayOf(floatArrayOf(1f, 32f), floatArrayOf(6f, 44f))
        private val PAW = arrayOf(
            arrayOf(floatArrayOf(0f, 0f), floatArrayOf(-19f, 22f), floatArrayOf(3f, 22f)),
            arrayOf(floatArrayOf(0f, 0f), floatArrayOf(-16f, 14f), floatArrayOf(5f, 14f)),
        )

        // poses are checked against the floor by the preview in GenCerberus (pose_*.png) - keep the numbers in sync
        private val IDLE = CPose(neckYaw = floatArrayOf(0.08f, 0f, -0.08f), jaw = floatArrayOf(0.12f, 0.1f, 0.16f), tailPitch = -0.1f, ground = 1f)
        private val SLEEP = CPose(
            neckPitch = floatArrayOf(0.95f, 1.0f, 0.95f), neckYaw = floatArrayOf(0.4f, 0f, -0.4f), headPitch = floatArrayOf(0.2f, 0.15f, 0.2f),
            jaw = floatArrayOf(0f, 0f, 0f), leg = floatArrayOf(0.6f, 0.6f, -1.3f, -1.3f), lower = floatArrayOf(-2.1f, -2.1f, 2.9f, 2.9f),
            bodyPitch = 0.1f, drop = 0.85f, tailPitch = -0.5f, tailYaw = 0.9f, ear = floatArrayOf(0.35f, 0.35f, 0.35f),
        )
        private val CROUCH = CPose(
            neckPitch = floatArrayOf(0.3f, 0.3f, 0.3f), neckYaw = floatArrayOf(0.15f, 0f, -0.15f), headPitch = floatArrayOf(0.1f, 0.1f, 0.1f),
            jaw = floatArrayOf(0.4f, 0.45f, 0.45f), leg = floatArrayOf(-0.35f, -0.35f, -0.3f, -0.3f), lower = floatArrayOf(0.7f, 0.7f, 0.5f, 0.5f),
            drop = 0.3f, bodyPitch = 0.08f, tailPitch = 0.3f, ear = floatArrayOf(0.4f, 0.4f, 0.4f), ground = 1f,
        )
        private val HOWL_UP = CPose(
            neckPitch = floatArrayOf(-0.45f, -0.5f, -0.9f), neckYaw = floatArrayOf(0.3f, 0f, -0.3f), headPitch = floatArrayOf(-0.3f, -0.35f, -0.65f),
            jaw = floatArrayOf(0.5f, 0.55f, 0.95f), bodyPitch = -0.12f, tailPitch = 0.4f, ear = floatArrayOf(0.5f, 0.5f, 0.6f), ground = 1f,
        )
        private val LYING = CPose(
            neckPitch = floatArrayOf(0.75f, 0.85f, 0.4f), neckYaw = floatArrayOf(0.45f, 0.1f, 0.55f), headPitch = floatArrayOf(0.4f, 0.3f, 0.3f),
            jaw = floatArrayOf(0.35f, 0.5f, 0.3f), leg = floatArrayOf(-0.3f, -0.1f, 0.4f, 0.2f), lower = floatArrayOf(0.2f, 0.1f, 0.2f, 0.1f),
            drop = 0f, roll = 85f, tailPitch = 0.6f, tailYaw = 0.3f, ear = floatArrayOf(0.5f, 0.5f, 0.5f),
        )
        /** Sitting back on the haunches (howls). */
        private val SIT_BACK = IDLE.with {
            leg[2] = -0.95f; leg[3] = -0.95f; lower[2] = 1.75f; lower[3] = 1.75f
            leg[0] = 0.12f; leg[1] = 0.12f
            bodyPitch = -0.1f; tailPitch = 0.5f
        }
        /** Low, coiled to spring: front legs bent, hind legs gathered. */
        private val COIL = CROUCH.with {
            leg[0] = -0.55f; leg[1] = -0.55f; lower[0] = 1.05f; lower[1] = 1.05f
            leg[2] = -0.55f; leg[3] = -0.55f; lower[2] = 0.95f; lower[3] = 0.95f
            for (i in 0..2) { neckPitch[i] = 0.45f; headPitch[i] = -0.15f; jaw[i] = 0.35f }
            tailPitch = 0.1f
        }
        /** Up on the hind legs, front paws in the air. */
        private val REAR = IDLE.with {
            bodyPitch = -0.62f; rear = 1f
            leg[0] = -0.75f; leg[1] = -0.95f; lower[0] = 1.1f; lower[1] = 0.85f
            leg[2] = 0.15f; leg[3] = 0.15f; lower[2] = 0.35f; lower[3] = 0.35f
            for (i in 0..2) { neckPitch[i] = -0.1f; headPitch[i] = -0.25f; jaw[i] = 0.75f; ear[i] = 0.5f }
            tailPitch = 0.6f
        }
        /** Front paws slammed down: braced and low. */
        private val SLAM = IDLE.with {
            leg[0] = -0.3f; leg[1] = -0.3f; lower[0] = 0.75f; lower[1] = 0.75f
            leg[2] = -0.2f; leg[3] = -0.2f; lower[2] = 0.45f; lower[3] = 0.45f
            for (i in 0..2) { neckPitch[i] = 0.5f; headPitch[i] = 0.1f; jaw[i] = 0.85f; ear[i] = 0.6f }
            sink = 0.06f; tailPitch = -0.3f
        }

        private fun k(t: Float, p: CPose, e: (Float) -> Float = Ease::inOut) = CKey(t, p, e)

        private fun track(t: Float, vararg keys: CKey): CPose {
            if (t <= keys[0].t) return keys[0].p.copy()
            for (i in 1 until keys.size) {
                val b = keys[i]
                if (t < b.t) {
                    val a = keys[i - 1]
                    val x = if (b.t > a.t) (t - a.t) / (b.t - a.t) else 1f
                    return a.p.mixTo(b.p, b.e(x))
                }
            }
            return keys.last().p.copy()
        }

        /** Lowest point (root units, + = down) of leg [i] for body pitch [p]. */
        private fun pawLow(i: Int, upper: Float, lower: Float, p: Float): Float {
            val f = if (i < 2) 0 else 1
            val hy = HIP[f][0]; val hz = HIP[f][1]
            val y0 = BODY_Y + hy * cos(p) - hz * sin(p)
            val a = upper + p
            val kz = KNEE[f][0]; val ky = KNEE[f][1]
            val kneeY = y0 + ky * cos(a) - kz * sin(a)
            val b = a + lower
            var low = kneeY
            for (pt in PAW[f]) low = max(low, kneeY + pt[1] * cos(b) - pt[0] * sin(b))
            return low
        }

        /**
         * Ground lock: turns the body (pitch) so that front and hind paws both reach the floor and lowers it onto them
         * ([CPose.rear] = 1: only the hind paws count). Writes the result into bodyPitch and drop (blocks).
         */
        internal fun settle(p: CPose) {
            if (p.ground <= 0f) { p.drop += p.sink; p.sink = 0f; return }
            var pitch = p.bodyPitch
            for (it in 0 until 3) {
                val front = max(pawLow(0, p.leg[0], p.lower[0], pitch), pawLow(1, p.leg[1], p.lower[1], pitch))
                val back = max(pawLow(2, p.leg[2], p.lower[2], pitch), pawLow(3, p.leg[3], p.lower[3], pitch))
                pitch += (back - front) / 46f * (1f - p.rear) * 0.95f
            }
            val front = max(pawLow(0, p.leg[0], p.lower[0], pitch), pawLow(1, p.leg[1], p.lower[1], pitch))
            val back = max(pawLow(2, p.leg[2], p.lower[2], pitch), pawLow(3, p.leg[3], p.lower[3], pitch))
            val low = mix(max(front, back), back, p.rear)
            val auto = (FLOOR - low) * UNIT
            p.bodyPitch = mix(p.bodyPitch, pitch, p.ground)
            p.drop = mix(p.drop, auto, p.ground) + p.sink
            p.sink = 0f
        }

        /** Head [h] bites: draws back, the jaw opens, snaps forward and shut exactly at [close], tears, comes back. */
        private fun bite(c: CPose, h: Int, close: Float, t: Float, size: Float = 1f) {
            val lunge = close - 2f
            val back = ramp(t, close - 8f, lunge) * (1f - ramp(t, lunge, close - 0.5f))
            val strike = Ease.outBack(((t - lunge) / 2.5f).coerceIn(0f, 1f)) * (1f - ramp(t, close + 3f, close + 9f))
            c.neckPitch[h] += (-0.4f * back + 0.55f * strike) * size
            c.headPitch[h] += (-0.25f * back + 0.3f * strike) * size
            val open = 0.1f + 0.85f * ramp(t, close - 7f, close - 1f)
            c.jaw[h] = if (t < close) open else mix(0.95f, 0.05f, ramp(t, close, close + 0.8f)) + 0.08f * ramp(t, close + 4f, close + 9f)
            c.ear[h] += 0.5f * (back + strike)
            // tearing: the head shakes from side to side
            val tear = if (t > close) Ease.bump((t - close) / 7f) else 0f
            c.neckYaw[h] += sin((t - close) * 1.9f) * 0.22f * tear * size
            c.headRoll[h] += sin((t - close) * 1.9f + 0.8f) * 0.25f * tear * size
            // the other heads make room
            for (o in 0..2) if (o != h) c.neckYaw[o] += (if (o < h) 0.16f else -0.16f) * (back + strike) * size
            // the body: weight back, then a lunge
            c.leg[0] += (-0.1f * back - 0.22f * strike) * size; c.leg[1] += (-0.1f * back - 0.22f * strike) * size
            c.lower[0] += 0.25f * back * size; c.lower[1] += 0.25f * back * size
            c.bodyPitch += (-0.05f * back + 0.06f * strike) * size
        }
    }

    override fun setAngles(s: CerberusRenderState) {
        super.setAngles(s)
        val p = s.pose as? CPose ?: pose(s)
        body.pitch = p.bodyPitch
        body.roll = p.sway
        for (i in 0..2) {
            necks[i].pitch = p.neckPitch[i]
            necks[i].yaw = p.neckYaw[i]
            heads[i].pitch = p.headPitch[i]
            heads[i].roll = p.headRoll[i]
            jaws[i].pitch = p.jaw[i]
            earsR[i].pitch -= p.ear[i]
            earsL[i].pitch -= p.ear[i]
        }
        for (i in 0..3) {
            legs[i].pitch = p.leg[i]
            lowers[i].pitch = p.lower[i]
        }
        tail.pitch = p.tailPitch
        tail.yaw = p.tailYaw
        tail2.pitch = p.tailPitch * 0.4f + sin(s.age * 0.15f) * 0.1f
        tail2.yaw = s.tail2
        tail3.yaw = p.tailYaw * 0.5f + sin(s.age * 0.2f) * 0.15f + s.tail3
        tail3.pitch = s.tailLash
        // which parts this pass draws
        val only = pass == Pass.BROKEN
        body.hidden = only
        for (k in bodyKids) k.visible = !only
        for (i in 0..2) {
            val broken = (s.broken shr i) and 1 == 1
            necks[i].visible = if (only) broken else !broken
        }
    }

    internal fun pose(s: CerberusRenderState): CPose {
        val t = s.t
        val u = s.unleashed
        fun w(ticks: Int): Float = (ticks * (if (u) 0.75 else 1.0)).toInt().coerceAtLeast(4).toFloat()
        fun alive(i: Int) = (s.aliveAtStart shr i) and 1 == 1
        val p: CPose = when (s.action) {
            CerberusEntity.SLEEP -> SLEEP.copy().also {
                // slow breaths (each head on its own), an ear twitches, a paw kicks in a dream
                for (i in 0..2) {
                    it.headPitch[i] += sin(s.age * 0.05f + i * 1.3f) * 0.03f
                    it.ear[i] -= 0.35f * Ease.every(s.age, 83f + i * 29f, 4f, i * 0.37f)
                    it.jaw[i] += 0.04f * max(0f, sin(s.age * 0.05f + i * 1.3f))
                }
                it.lower[0] += 0.25f * Ease.every(s.age, 170f, 6f, 0.2f)
                it.drop -= sin(s.age * 0.05f) * 0.01f
                it.tailYaw += Ease.every(s.age, 120f, 10f, 0.6f) * 0.25f
            }
            CerberusEntity.WAKE -> {
                // the heads wake one after another (howl, bleed, flame), a yawn, front up, hind up, a stretch
                val c = SLEEP.copy()
                val order = intArrayOf(5, 0, 10)
                for (i in 0..2) {
                    val o = order[i].toFloat()
                    val up = Ease.outBack(((t - o) / 9f).coerceIn(0f, 1f))
                    c.neckPitch[i] = mix(SLEEP.neckPitch[i], 0.15f, up)
                    c.neckYaw[i] = mix(SLEEP.neckYaw[i], IDLE.neckYaw[i], up)
                    c.headPitch[i] = mix(SLEEP.headPitch[i], -0.1f, up)
                    c.ear[i] = mix(0.35f, -0.25f, up)
                    c.jaw[i] = 0.9f * Ease.bump((t - o - 6f) / 10f)
                    c.headRoll[i] = 0.25f * Ease.bump((t - o - 6f) / 10f) * (if (i == 2) -1f else 1f)
                }
                val frontUp = Ease.inOut((t - 16f) / 10f)
                val hindUp = Ease.inOut((t - 22f) / 10f)
                for (l in 0..1) { c.leg[l] = mix(SLEEP.leg[l], 0f, frontUp); c.lower[l] = mix(SLEEP.lower[l], 0f, frontUp) }
                for (l in 2..3) { c.leg[l] = mix(SLEEP.leg[l], 0f, hindUp); c.lower[l] = mix(SLEEP.lower[l], 0f, hindUp) }
                val standing = mix(frontUp, hindUp, 0.5f)
                c.ground = standing
                c.drop = SLEEP.drop * (1f - standing)
                c.bodyPitch = mix(SLEEP.bodyPitch, -0.2f, frontUp) * (1f - hindUp)
                c.tailPitch = mix(SLEEP.tailPitch, IDLE.tailPitch, hindUp); c.tailYaw = mix(SLEEP.tailYaw, 0f, hindUp)
                // the stretch (play bow), then a shake
                val bow = Ease.bump((t - 30f) / 14f)
                c.leg[0] -= 0.5f * bow; c.leg[1] -= 0.5f * bow; c.lower[0] += 0.15f * bow; c.lower[1] += 0.15f * bow
                c.sway = sin(t * 1.5f) * 0.07f * Ease.bump((t - 44f) / 10f)
                for (i in 0..2) c.headRoll[i] += sin(t * 1.5f + i) * 0.3f * Ease.bump((t - 44f) / 10f)
                c
            }
            CerberusEntity.GROWL -> {
                if (abs(s.brokeAt) < 4f) {
                    // a head just broke: it stumbles away from it, the others shake and whine
                    val side = if ((s.broken and 1) != 0 && (s.broken and 4) == 0) 1f else -1f
                    val c = IDLE.copy()
                    val hit = Ease.bump(t / 14f)
                    c.sway = side * 0.12f * hit + sin(t * 0.9f) * 0.04f * hit
                    c.leg[0] += 0.25f * hit; c.leg[2] -= 0.2f * hit; c.lower[0] += 0.5f * hit; c.lower[3] += 0.4f * hit
                    c.sink = 0.08f * hit
                    for (i in 0..2) {
                        c.neckPitch[i] += 0.25f * hit
                        c.headRoll[i] += sin(t * 1.3f + i * 1.7f) * 0.3f * ramp(t, 6f, 10f) * (1f - ramp(t, 20f, 30f))
                        c.jaw[i] = 0.2f + 0.25f * Ease.every(t, 9f, 5f, i * 0.33f)
                        c.ear[i] = 0.6f
                    }
                    c.tailPitch = -0.6f
                    c
                } else {
                    // the three barks of the intro (ticks 0, 6, 12): each head snaps up and barks, then they snarl
                    val c = IDLE.mixTo(CROUCH, ramp(t, 0f, 8f))
                    val barkAt = floatArrayOf(0f, 12f, 6f)
                    for (i in 0..2) {
                        val b = Ease.bump((t - barkAt[i]) / 5f)
                        c.neckPitch[i] += -0.45f * b
                        c.headPitch[i] += -0.25f * b
                        c.jaw[i] = mix(0.3f + 0.15f * sin(t * 0.9f + i * 2.1f), 0.95f, b)
                        c.ear[i] = 0.5f
                        c.neckYaw[i] += Ease.wobble(t * 3f, i * 2.3f) * 0.12f
                    }
                    c.bodyPitch += 0.04f * Ease.bump((t - 12f) / 5f)
                    c
                }
            }
            CerberusEntity.BITE -> IDLE.copy().also { bite(it, s.biteHead.coerceIn(0, 2), w(10) + 2f, t) }
            CerberusEntity.SNAP3 -> {
                val step = w(8)
                val c = IDLE.copy()
                var k = 0
                for (i in 0..2) if (alive(i)) { bite(c, i, step * (k + 1), t, 0.85f); k++ }
                c
            }
            CerberusEntity.BREATH -> {
                val wind = w(16)
                val rear = IDLE.with {
                    neckPitch[1] = -0.6f; headPitch[1] = -0.38f; jaw[1] = 0.42f; ear[1] = 0.4f
                    neckYaw[0] = 0.3f; neckYaw[2] = -0.3f; bodyPitch = -0.06f; sink = -0.04f
                    leg[0] = -0.12f; leg[1] = -0.12f
                }
                val blow = IDLE.with {
                    neckPitch[1] = 0.2f; headPitch[1] = 0.12f; jaw[1] = 0.98f; ear[1] = 0.7f
                    neckYaw[0] = 0.45f; neckYaw[2] = -0.45f; neckPitch[0] = 0.15f; neckPitch[2] = 0.15f; jaw[0] = 0.45f; jaw[2] = 0.45f
                    leg[0] = -0.22f; leg[1] = -0.22f; lower[0] = 0.2f; lower[1] = 0.2f; leg[2] = 0.12f; leg[3] = 0.12f
                    bodyPitch = 0.05f; tailPitch = 0.35f
                }
                track(t, k(0f, IDLE), k(wind - 2f, rear), k(wind + 2f, blow, Ease::outBack), k(wind + 48f, blow), k(wind + 54f, IDLE)).also {
                    val f = ramp(t, wind, wind + 2f) * (1f - ramp(t, wind + 46f, wind + 50f))
                    it.jaw[1] += sin(t * 1.7f) * 0.04f * f
                    it.neckPitch[1] += sin(t * 1.3f) * 0.03f * f
                    it.jaw[0] += sin(t * 2.1f) * 0.06f * f; it.jaw[2] += sin(t * 2.3f + 1f) * 0.06f * f
                    val shake = Ease.bump((t - wind - 48f) / 6f)
                    it.neckYaw[1] += sin(t * 2.2f) * 0.2f * shake
                }
            }
            CerberusEntity.TRIPLE_BREATH -> {
                val wind = w(18)
                val c = IDLE.copy()
                // the heads rear back one after another, then all three breathe and sweep (as the server's flames:
                // from its left to its right, the side heads a little inwards)
                val offs = floatArrayOf(-0.49f, 0f, 0.49f)
                val delay = floatArrayOf(2f, 4f, 0f)
                val sweep = mix(-0.7f, 0.7f, ramp(t, wind, wind + 52f)) * (1f - ramp(t, wind + 52f, wind + 58f))
                for (i in 0..2) {
                    val rear = ramp(t, delay[i], wind - 1f) * (1f - ramp(t, wind - 1f, wind + 1f))
                    val breathe = Ease.outBack(((t - wind + 1f) / 3f).coerceIn(0f, 1f)) * (1f - ramp(t, wind + 50f, wind + 58f))
                    c.neckPitch[i] += -0.5f * rear + 0.12f * breathe
                    c.headPitch[i] += -0.3f * rear + 0.08f * breathe
                    c.jaw[i] = 0.1f + 0.35f * rear + 0.85f * breathe + sin(t * 1.9f + i) * 0.04f * breathe
                    c.neckYaw[i] = mix(c.neckYaw[i], sweep * 0.9f + offs[i] * 0.25f, breathe)
                    c.ear[i] = 0.6f * (rear + breathe)
                }
                val brace = ramp(t, wind - 2f, wind + 2f) * (1f - ramp(t, wind + 50f, wind + 58f))
                c.leg[0] -= 0.2f * brace; c.leg[1] -= 0.2f * brace; c.leg[2] += 0.12f * brace; c.leg[3] += 0.12f * brace
                c.sway = -sweep * 0.05f
                c
            }
            CerberusEntity.HOWL -> {
                val wind = w(22)
                val gather = SIT_BACK.with {
                    neckPitch[2] = 0.35f; headPitch[2] = 0.2f; jaw[2] = 0.05f; ear[2] = 0.3f
                    neckPitch[0] = 0.25f; neckPitch[1] = 0.25f; jaw[0] = 0.45f; jaw[1] = 0.45f; ear[0] = 0.6f; ear[1] = 0.6f
                }
                val howl = SIT_BACK.with {
                    neckPitch[2] = -0.95f; headPitch[2] = -0.7f; jaw[2] = 0.98f; ear[2] = 0.7f; neckYaw[2] = -0.2f
                    neckPitch[0] = 0.3f; neckPitch[1] = 0.3f; neckYaw[0] = 0.35f; neckYaw[1] = 0.15f; jaw[0] = 0.55f; jaw[1] = 0.55f; ear[0] = 0.7f; ear[1] = 0.7f
                    bodyPitch = -0.2f
                }
                track(t, k(0f, IDLE), k(wind * 0.55f, gather), k(wind - 1f, gather.with { neckPitch[2] = 0.2f }), k(wind + 1.5f, howl, Ease::outBackBig), k(wind + 12f, howl), k(wind + 16f, IDLE)).also {
                    val f = ramp(t, wind, wind + 2f) * (1f - ramp(t, wind + 11f, wind + 14f))
                    it.neckPitch[2] += sin(t * 1.6f) * 0.04f * f
                    it.jaw[2] += sin(t * 2.4f) * 0.03f * f
                    it.jaw[0] += sin(t * 2.0f) * 0.08f * ramp(t, 4f, 8f); it.jaw[1] += sin(t * 2.2f + 1f) * 0.08f * ramp(t, 4f, 8f)
                }
            }
            CerberusEntity.GEYSERS -> {
                val wind = 16f
                val howl = SIT_BACK.with {
                    for (i in 0..2) { neckPitch[i] = HOWL_UP.neckPitch[i] - 0.2f; headPitch[i] = HOWL_UP.headPitch[i] - 0.1f; jaw[i] = 0.9f; ear[i] = 0.7f; neckYaw[i] = HOWL_UP.neckYaw[i] }
                    bodyPitch = -0.2f
                }
                track(t, k(0f, IDLE), k(wind - 2f, SIT_BACK.with { for (i in 0..2) { neckPitch[i] = 0.35f; jaw[i] = 0.2f } }), k(wind + 1f, howl, Ease::outBack), k(wind + 14f, howl), k(36f, IDLE)).also {
                    val f = ramp(t, wind, wind + 2f) * (1f - ramp(t, wind + 12f, wind + 16f))
                    for (i in 0..2) it.neckPitch[i] += sin(t * 1.5f + i * 2f) * 0.04f * f
                }
            }
            CerberusEntity.POUNCE -> {
                val wind = w(12)
                val launch = IDLE.with {
                    leg[0] = -1.15f; leg[1] = -1.05f; lower[0] = 0.25f; lower[1] = 0.3f; leg[2] = 0.95f; leg[3] = 1.0f; lower[2] = -0.25f; lower[3] = -0.25f
                    bodyPitch = -0.18f; ground = 0f; drop = 0f; tailPitch = -0.35f
                    for (i in 0..2) { neckPitch[i] = -0.2f; headPitch[i] = 0.05f; jaw[i] = 0.85f; ear[i] = 0.8f }
                }
                val fall = launch.with { bodyPitch = 0.16f; leg[0] = -0.7f; leg[1] = -0.75f; leg[2] = -0.25f; leg[3] = -0.2f; lower[2] = 0.4f; lower[3] = 0.4f; for (i in 0..2) neckPitch[i] = 0.15f }
                val land = SLAM.with { for (i in 0..2) jaw[i] = 0.6f }
                when {
                    t < wind -> track(t, k(0f, IDLE), k(wind - 1f, COIL)).also {
                        // the butt wiggles before the jump
                        it.sway = sin(t * 1.7f) * 0.05f * ramp(t, 2f, wind - 2f)
                        it.tailPitch += sin(t * 1.7f) * 0.2f
                    }
                    s.landT < 0f -> track(t, k(wind - 1f, COIL), k(wind + 1.5f, launch, Ease::outCubic)).mixTo(fall, ramp(-s.vy, 0.05f, 0.5f) * ramp(t, wind + 3f, wind + 5f))
                    else -> track(t, k(s.landT, fall), k(s.landT + 1.5f, land, Ease::inQuad), k(s.landT + 4f, land.with { sink = 0.1f }, Ease::outCubic), k(s.landT + 12f, IDLE))
                }
            }
            CerberusEntity.CHARGE -> {
                val wind = w(18)
                if (t < wind) {
                    // low, snarling, the right front paw scrapes the floor
                    val c = IDLE.mixTo(COIL, Ease.inOut(t / 6f))
                    val sc = (t % 9f) / 9f
                    c.leg[0] += (if (sc < 0.45f) -0.45f * Ease.inOut(sc / 0.45f) else -0.45f + 0.75f * Ease.outCubic((sc - 0.45f) / 0.55f)) * (1f - ramp(t, wind - 4f, wind - 1f))
                    c.lower[0] += 0.5f * Ease.bump(sc / 0.45f)
                    for (i in 0..2) { c.jaw[i] = 0.35f + 0.12f * sin(t * 1.1f + i * 2f); c.ear[i] = 0.8f; c.neckPitch[i] += 0.1f }
                    c.tailPitch = -0.2f + sin(t * 0.9f) * 0.15f
                    c
                } else track(t, k(wind, COIL), k(wind + 3f, IDLE.with { for (i in 0..2) { neckPitch[i] = 0.35f; headPitch[i] = -0.15f; jaw[i] = 0.65f; ear[i] = 0.9f }; tailPitch = -0.5f }, Ease::outCubic),
                    k(wind + 27f, IDLE.with { for (i in 0..2) { neckPitch[i] = 0.35f; headPitch[i] = -0.15f; jaw[i] = 0.65f; ear[i] = 0.9f }; tailPitch = -0.5f }),
                    // the skid: braced front legs, weight back
                    k(wind + 30f, IDLE.with { leg[0] = -0.5f; leg[1] = -0.45f; lower[0] = -0.1f; leg[2] = -0.35f; leg[3] = -0.3f; lower[2] = 0.6f; lower[3] = 0.6f; for (i in 0..2) { neckPitch[i] = -0.1f; jaw[i] = 0.4f } }, Ease::outCubic),
                    k(wind + 36f, IDLE))
            }
            CerberusEntity.NOVA -> track(t,
                k(0f, IDLE), k(17f, REAR, Ease::inOut), k(20f, SLAM, Ease::inCubic), k(24f, CROUCH, Ease::outCubic),
                k(33f, REAR.with { bodyPitch = -0.45f }, Ease::inOut), k(36f, SLAM, Ease::inCubic), k(40f, CROUCH, Ease::outCubic), k(50f, IDLE),
            ).also {
                // pawing the air while up
                val upr = ramp(t, 8f, 15f) * (1f - ramp(t, 16f, 19f)) + ramp(t, 27f, 31f) * (1f - ramp(t, 32f, 35f))
                it.leg[0] += sin(t * 1.4f) * 0.3f * upr; it.leg[1] += sin(t * 1.4f + 2f) * 0.3f * upr
            }
            CerberusEntity.ORBS -> {
                val step = w(CerberusEntity.ORB_STEP)
                val c = IDLE.copy()
                for (k in 0 until (if (u) 6 else 3)) {
                    val h = k % 3
                    val at = step * (k + 1)
                    // draw back, spit (jaw wide, the head snaps forward) and the recoil
                    val draw = ramp(t, at - 5f, at - 0.5f) * (1f - ramp(t, at - 0.5f, at + 0.5f))
                    val spit = Ease.bump((t - at + 0.5f) / 5f)
                    c.neckPitch[h] += -0.3f * draw + 0.3f * spit
                    c.headPitch[h] += -0.2f * draw - 0.15f * spit
                    c.jaw[h] = max(c.jaw[h], 0.15f + 0.4f * draw + 0.8f * spit)
                    c.ear[h] += 0.5f * (draw + spit)
                }
                c
            }
            CerberusEntity.FIRE_RING -> {
                val rw = CerberusEntity.RING_WIND.toFloat()
                val leap = CerberusEntity.RING_LEAP.toFloat()
                val summon = HOWL_UP.with { for (i in 0..2) { neckPitch[i] = -0.7f; jaw[i] = 0.9f } }
                val launch = IDLE.with {
                    leg[0] = -1.1f; leg[1] = -1.0f; leg[2] = 0.95f; leg[3] = 1.0f; lower[2] = -0.25f; lower[3] = -0.25f; bodyPitch = -0.15f; ground = 0f; drop = 0f
                    for (i in 0..2) { neckPitch[i] = -0.1f; jaw[i] = 0.8f; ear[i] = 0.8f }
                }
                when {
                    t < rw + 1f -> track(t, k(0f, IDLE), k(rw - 1f, summon), k(rw + 1f, summon.with { for (i in 0..2) neckPitch[i] = 0.35f }, Ease::inCubic)).also {
                        for (i in 0..2) it.neckPitch[i] += sin(t * 1.6f + i * 2f) * 0.05f * ramp(t, 4f, rw - 2f)
                    }
                    t < leap -> track(t, k(rw + 1f, summon.with { for (i in 0..2) neckPitch[i] = 0.35f }), k(rw + 6f, COIL), k(leap - 1f, COIL)).also {
                        it.sway = sin(t * 1.7f) * 0.05f * ramp(t, rw + 6f, leap - 3f)
                    }
                    s.landT < 0f -> track(t, k(leap - 1f, COIL), k(leap + 1.5f, launch, Ease::outCubic)).mixTo(launch.with { bodyPitch = 0.16f; leg[0] = -0.7f; leg[1] = -0.75f; leg[2] = -0.25f; leg[3] = -0.2f },
                        ramp(-s.vy, 0.05f, 0.5f) * ramp(t, leap + 3f, leap + 5f))
                    else -> track(t, k(s.landT, launch), k(s.landT + 1.5f, SLAM, Ease::inQuad), k(s.landT + 8f, IDLE.with { for (i in 0..2) { jaw[i] = 0.45f; neckPitch[i] = -0.2f } }),
                        k(CerberusEntity.RING_END.toFloat(), IDLE.with { for (i in 0..2) { neckPitch[i] = -0.4f; jaw[i] = 0.45f } }))
                }
            }
            CerberusEntity.COLLAPSE -> {
                // the legs give way (front first), it rolls onto its side, then lies there breathing hard
                val buckle = IDLE.with { leg[0] = 0.3f; leg[1] = 0.25f; lower[0] = -0.9f; lower[1] = -0.8f; leg[2] = -0.25f; leg[3] = -0.25f; lower[2] = 0.6f; lower[3] = 0.6f
                    for (i in 0..2) { neckPitch[i] = 0.6f; jaw[i] = 0.5f; ear[i] = 0.6f }; ground = 0f; drop = 0.35f; bodyPitch = 0.2f }
                track(t, k(0f, IDLE), k(6f, buckle, Ease::inQuad), k(16f, LYING, Ease::outBounce)).also {
                    val heave = sin(s.age * 0.3f)
                    it.neckPitch[1] += heave * 0.04f
                    it.drop += heave * 0.012f * ramp(t, 16f, 20f)
                    // now and then a head tries to rise and sinks back
                    for (i in 0..2) {
                        val tryUp = Ease.every(s.age, 70f + i * 23f, 18f, i * 0.4f) * ramp(t, 22f, 28f)
                        it.neckPitch[i] -= 0.35f * tryUp
                        it.jaw[i] += 0.2f * tryUp
                    }
                }
            }
            CerberusEntity.RISE -> {
                // convulsions, it rolls up onto its paws, rears up and howls with all three heads, slams down (tick 46)
                val gather = COIL.with { for (i in 0..2) { neckPitch[i] = 0.4f; jaw[i] = 0.6f } }
                val rearHowl = REAR.with { for (i in 0..2) { neckPitch[i] = HOWL_UP.neckPitch[i] - 0.15f; headPitch[i] = HOWL_UP.headPitch[i]; neckYaw[i] = HOWL_UP.neckYaw[i]; jaw[i] = 0.98f } }
                track(t, k(0f, LYING), k(8f, LYING), k(20f, gather, Ease::outBack), k(30f, rearHowl, Ease::outCubic), k(44f, rearHowl),
                    k(46f, SLAM, Ease::inCubic), k(52f, CROUCH, Ease::outCubic), k(62f, IDLE)).also {
                    val jerk = (1f - ramp(t, 6f, 9f))
                    for (i in 0..2) it.neckPitch[i] -= 0.3f * Ease.every(t, 3.3f + i * 0.7f, 1.6f) * jerk
                    it.leg[2] += 0.3f * Ease.every(t, 2.7f, 1.4f) * jerk
                    val howl = ramp(t, 28f, 31f) * (1f - ramp(t, 43f, 45f))
                    for (i in 0..2) it.neckPitch[i] += sin(t * 1.7f + i * 1.9f) * 0.05f * howl
                    // shakes the flames off
                    it.sway = sin(t * 1.6f) * 0.08f * Ease.bump((t - 52f) / 10f)
                }
            }
            CerberusEntity.DEATH -> {
                // it staggers, the front legs buckle, it rolls over; the heads die one after another, the last breaths fade
                val stagger = IDLE.with { leg[0] = 0.15f; leg[3] = -0.25f; lower[3] = 0.4f; sway = 0.08f; for (i in 0..2) { neckPitch[i] = 0.4f; jaw[i] = 0.5f; ear[i] = 0.7f } }
                val buckle = IDLE.with { leg[0] = 0.35f; leg[1] = 0.3f; lower[0] = -1.0f; lower[1] = -0.9f; leg[2] = -0.2f; leg[3] = -0.2f; lower[2] = 0.6f; lower[3] = 0.6f
                    for (i in 0..2) { neckPitch[i] = 0.7f; jaw[i] = 0.55f; ear[i] = 0.6f }; ground = 0f; drop = 0.38f; bodyPitch = 0.22f }
                val dead = LYING.with { for (i in 0..2) { neckPitch[i] += 0.25f; jaw[i] = 0.5f } }
                track(t, k(0f, IDLE), k(7f, stagger), k(14f, buckle, Ease::inQuad), k(26f, LYING, Ease::outBounce)).also {
                    it.sway += sin(t * 0.8f) * 0.05f * (1f - ramp(t, 8f, 14f))
                    val die = floatArrayOf(32f, 40f, 26f)
                    for (i in 0..2) {
                        // a last lift of the head, then it drops
                        val last = Ease.bump((t - die[i] + 6f) / 6f)
                        it.neckPitch[i] = mix(it.neckPitch[i], dead.neckPitch[i], ramp(t, die[i], die[i] + 4f)) - 0.3f * last * ramp(t, 20f, 26f)
                        it.jaw[i] = mix(it.jaw[i], 0.5f, ramp(t, die[i], die[i] + 3f))
                    }
                    it.drop += sin(s.age * 0.25f) * 0.01f * ramp(t, 26f, 30f) * (1f - ramp(t, 40f, 60f))
                }
            }
            else -> IDLE.copy().also {
                // alive: each head looks around on its own, snaps a glance now and then, ears flick, jaws pant or snarl
                for (i in 0..2) {
                    it.neckYaw[i] += Ease.wobble(s.age * 0.8f, i * 3.1f) * 0.16f + (if (i == 1) 0f else 0.25f * Ease.every(s.age, 90f + i * 23f, 9f, i * 0.3f) * (1f - i))
                    it.headPitch[i] += Ease.wobble(s.age * 0.6f, i * 1.7f + 5f) * 0.08f
                    it.headRoll[i] += Ease.wobble(s.age * 0.4f, i * 2.9f + 1f) * 0.12f
                    it.ear[i] -= 0.4f * Ease.every(s.age, 61f + i * 17f, 5f, i * 0.21f)
                    it.jaw[i] = if (u) 0.25f + 0.1f * sin(s.age * 0.85f + i * 2f) else 0.12f + 0.1f * max(0f, sin(s.age * 0.12f + i * 2f)) + 0.2f * Ease.every(s.age, 113f + i * 31f, 12f, i * 0.5f)
                }
            }
        }
        // gait: a diagonal trot (front right + back left, front left + back right), a gallop when it is fast
        val lying = s.action == CerberusEntity.SLEEP || s.action == CerberusEntity.COLLAPSE || s.action == CerberusEntity.DEATH || s.action == CerberusEntity.WAKE ||
            s.action == CerberusEntity.RISE
        val airborne = (s.action == CerberusEntity.POUNCE || s.action == CerberusEntity.FIRE_RING) && p.ground < 0.5f
        if (!lying && !airborne) {
            val gallop = ramp(s.speed, 0.3f, 0.55f)
            val trot = ramp(s.speed, 0.015f, 0.08f) * (1f - gallop)
            val ph = s.gait
            if (trot > 0f) {
                val a = 0.4f
                val phases = floatArrayOf(ph, ph + PI.toFloat(), ph + PI.toFloat(), ph)
                for (l in 0..3) {
                    val f = phases[l]
                    p.leg[l] += -a * cos(f) * trot
                    p.lower[l] += (if (l < 2) 0.95f else 0.6f) * max(0f, -sin(f)) * trot
                }
                for (i in 0..2) p.neckPitch[i] += sin(ph * 2f + i) * 0.04f * trot
                p.sway += sin(ph) * 0.03f * trot
            }
            if (gallop > 0f) {
                // rotary gallop: the front pair and the hind pair almost together, the spine flexes
                val offs = floatArrayOf(0.12f, 0f, 0.5f, 0.62f)
                for (l in 0..3) {
                    val f = ph + offs[l] * 2f * PI.toFloat()
                    p.leg[l] += -(if (l < 2) 0.72f else 0.66f) * cos(f) * gallop
                    p.lower[l] += (if (l < 2) 1.1f else 0.75f) * max(0f, -sin(f)) * gallop
                }
                p.bodyPitch += 0.1f * sin(ph) * gallop
                p.sink -= 0.08f * max(0f, sin(ph + 1.2f)) * gallop
                // the heads stay steady while the body pumps
                for (i in 0..2) { p.neckPitch[i] -= 0.1f * sin(ph) * gallop; p.ear[i] += 0.5f * gallop }
                p.tailPitch -= 0.35f * gallop
            }
            // turning on the spot: small steps
            val tw = ramp(abs(s.turn), 2f, 6f) * (1f - trot - gallop).coerceAtLeast(0f)
            if (tw > 0f) {
                val f = s.turnStep
                val phases = floatArrayOf(f, f + PI.toFloat(), f + PI.toFloat(), f)
                for (l in 0..3) { p.leg[l] += -0.15f * cos(phases[l]) * tw; p.lower[l] += 0.5f * max(0f, -sin(phases[l])) * tw }
            }
            // the heads look towards the target, each with its own lag, and lead into turns
            val lead = (s.turn * (PI.toFloat() / 180f)).coerceIn(-0.5f, 0.5f)
            for (i in 0..2) p.neckYaw[i] += s.look[i] * 0.8f + lead * 0.6f
            for (i in 0..2) p.headPitch[i] += (s.pitch * (PI.toFloat() / 180f)) * 0.4f
        }
        // broken heads hang limp: neck down and outwards, jaw slack, swaying with the steps and a slow pendulum
        for (i in 0..2) {
            val k = s.limp[i]
            if (k <= 0f) continue
            val sway = sin(s.age * 0.06f + i * 2.3f) * 0.07f + sin(s.gait * 2f) * 0.06f * ramp(s.speed, 0.02f, 0.12f)
            val out = when (i) { 0 -> 0.35f; 2 -> -0.35f; else -> 0f }
            p.neckPitch[i] = mix(p.neckPitch[i], 1.0f + sway * 0.5f, k)
            p.neckYaw[i] = mix(p.neckYaw[i], out + sway, k)
            p.headPitch[i] = mix(p.headPitch[i], 0.5f, k)
            p.headRoll[i] = mix(p.headRoll[i], (if (i == 2) -0.3f else 0.3f) + sway, k)
            p.jaw[i] = mix(p.jaw[i], 0.6f + sin(s.age * 0.09f + i) * 0.04f, k)
            p.ear[i] = mix(p.ear[i], 0.7f, k)
        }
        // breathing (faster and heavier unleashed)
        val br = sin(s.age * (if (u) 0.11f else 0.06f))
        p.bodyPitch += br * 0.015f
        p.sink += br * 0.008f
        p.tailYaw += sin(s.age * 0.1f) * 0.25f
        return p
    }
}

class CerberusRenderer(ctx: EntityRendererFactory.Context) :
    MobEntityRenderer<CerberusEntity, CerberusRenderState, CerberusModel>(ctx, CerberusModel(ctx.getPart(CerberusModel.LAYER)), 1.6f) {

    /** Client-side animation memory per Cerberus: limp heads, motion, per-head look lag, tail springs, cross-fades. */
    private class Memory(e: CerberusEntity) {
        val limp = FloatArray(4) { if (it < 3 && e.isHeadBroken(it)) 1f else 0f }
        val m = Motion()
        val look = arrayOf(Spring(0.22f, 0.7f), Spring(0.3f, 0.75f), Spring(0.26f, 0.65f))
        val tail2 = Spring(0.3f, 0.3f)
        val tail3 = Spring(0.4f, 0.25f)
        var lastBroken = e.brokenMask
        var brokeTime = -999f
    }

    private val memory = WeakHashMap<CerberusEntity, Memory>()

    init {
        val brokenModel = CerberusModel(ctx.getPart(CerberusModel.LAYER), CerberusModel.Pass.BROKEN)
        val glowModel = CerberusModel(ctx.getPart(CerberusModel.LAYER), CerberusModel.Pass.GLOW)
        // broken heads: same pose, broken skin, normal light (no glow)
        addFeature(PassFeature(this, brokenModel, { RenderLayers.entityCutoutNoCull(BROKEN) }, { _, s -> s.light }, { -1 }, { it.broken != 0 }))
        // glow (eyes layer = full bright); the texture is already coloured, the tint only flickers
        addFeature(PassFeature(this, glowModel, { if (it.unleashed) GLOW_UNLEASHED else GLOW }, { _, _ -> 0xF000F0 }, { s ->
            when {
                s.action == CerberusEntity.SLEEP -> Anim.tint(0.4f)
                s.unleashed -> Anim.tint(0.86f + 0.14f * sin(s.age * 0.5f) * sin(s.age * 0.17f))
                else -> Anim.tint(0.82f + 0.18f * sin(s.age * 0.09f))
            }
        }, { true }, batch = 1))
    }

    override fun createRenderState(): CerberusRenderState = CerberusRenderState()

    override fun getTexture(state: CerberusRenderState): Identifier = TEXTURE

    override fun updateRenderState(entity: CerberusEntity, state: CerberusRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        val mem = memory.getOrPut(entity) { Memory(entity) }
        val m = mem.m
        val dt = m.update(entity, tickDelta, entity.action, entity.actionAge)
        state.action = entity.action
        state.t = entity.actionTime(tickDelta)
        state.unleashed = entity.unleashed
        state.deathTime = 0f
        state.broken = entity.brokenMask
        state.biteHead = entity.bitingHead
        // a head that breaks now: remember when (the stagger animation), the snaps keep the heads alive at their start
        if (entity.brokenMask and mem.lastBroken.inv() != 0) mem.brokeTime = m.time
        mem.lastBroken = entity.brokenMask
        if (m.started) { m.mark = entity.brokenMask.inv() and 7; m.markTime = m.time }
        state.aliveAtStart = m.mark
        state.brokeAt = if (mem.brokeTime > -100f) mem.brokeTime - m.markTime else -999f
        // ease the limp heads (about half a second to collapse, a little slower to lift a re-ignited head)
        val limp = mem.limp
        val now = entity.age + tickDelta
        val ldt = (now - limp[3]).coerceIn(0f, 5f)
        limp[3] = now
        for (i in 0..2) {
            val target = if (entity.isHeadBroken(i)) 1f else 0f
            limp[i] = if (target > limp[i]) (limp[i] + ldt * 0.1f).coerceAtMost(1f) else (limp[i] - ldt * 0.06f).coerceAtLeast(0f)
            state.limp[i] = ramp(limp[i], 0f, 1f)
        }
        state.speed = m.speed
        state.vy = m.vy
        state.turn = m.turn
        // trot: one cycle per 3 blocks (paws planted), gallop: per 6 blocks, at most one cycle per 7 ticks
        val cycle = mix(3.0f, 6.0f, ramp(m.speed, 0.3f, 0.55f))
        m.advanceGait(dt, cycle, 0.9f)
        m.turnStep += abs(m.turn) * dt * (2f * PI.toFloat() / 45f)
        state.gait = m.gait
        state.turnStep = m.turnStep
        val leapFrom = when (entity.action) {
            CerberusEntity.POUNCE -> (12 * (if (entity.unleashed) 0.75 else 1.0)).toInt().coerceAtLeast(4).toFloat()
            CerberusEntity.FIRE_RING -> CerberusEntity.RING_LEAP.toFloat()
            else -> -1f
        }
        state.landT = if (leapFrom >= 0f) m.landing(entity, state.t, leapFrom + 4f, leapFrom + 34f) else -1f
        // each head turns towards the target with its own lag
        val look = (state.relativeHeadYaw * (PI.toFloat() / 180f)).coerceIn(-0.7f, 0.7f)
        for (i in 0..2) state.look[i] = mem.look[i].step(look, dt)
        state.tail2 = mem.tail2.step(-(m.turn * (PI.toFloat() / 180f)).coerceIn(-0.5f, 0.5f) * 0.8f, dt)
        state.tail3 = mem.tail3.step(-mem.tail2.v * 3f, dt)
        state.tailLash = (mem.tail3.v * 2f).coerceIn(-0.4f, 0.4f)

        var pose = model.pose(state)
        if (m.started) {
            m.fromPose = m.lastPose
            m.blendStart = m.time
            m.blendLen = when (entity.action) {
                CerberusEntity.COLLAPSE, CerberusEntity.DEATH, CerberusEntity.RISE, CerberusEntity.WAKE -> 6f
                CerberusEntity.GROWL -> 3f
                else -> 4.5f
            }
        }
        // the ground lock runs before the cross-fade, so both ends of a blend stand on the floor
        CerberusModel.settle(pose)
        val from = m.fromPose as? CPose
        if (from != null) {
            val k = (m.time - m.blendStart) / m.blendLen
            if (k >= 1f) m.fromPose = null else pose = from.mixTo(pose, Ease.inOut(k))
        }
        m.lastPose = pose
        state.pose = pose
        state.drop = pose.drop
        state.roll = pose.roll
    }

    override fun setupTransforms(state: CerberusRenderState, matrices: MatrixStack, bodyYaw: Float, baseHeight: Float) {
        super.setupTransforms(state, matrices, bodyYaw, baseHeight)
        if (state.drop != 0f) matrices.translate(0f, -state.drop, 0f)
        if (state.roll != 0f) {
            // falls onto its left side: a positive roll tips it towards -x, so it turns around that outer edge
            // (the left shoulder) - turning around the centre would put half of it into the floor
            matrices.translate(-CerberusModel.ROLL_PIVOT, 0f, 0f)
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(state.roll))
            matrices.translate(CerberusModel.ROLL_PIVOT, 0f, 0f)
        }
    }

    override fun scale(state: CerberusRenderState, matrices: MatrixStack) {
        matrices.scale(CerberusEntity.RENDER_SCALE, CerberusEntity.RENDER_SCALE, CerberusEntity.RENDER_SCALE)
    }

    /** Renders [passModel] (one of the extra model instances) over the hound with its own layer/light/tint. */
    private class PassFeature(
        context: FeatureRendererContext<CerberusRenderState, CerberusModel>,
        private val passModel: CerberusModel,
        private val layer: (CerberusRenderState) -> RenderLayer,
        private val lightOf: (Int, CerberusRenderState) -> Int,
        private val tint: (CerberusRenderState) -> Int,
        private val active: (CerberusRenderState) -> Boolean,
        private val batch: Int = 0,
    ) : FeatureRenderer<CerberusRenderState, CerberusModel>(context) {
        override fun render(matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int, state: CerberusRenderState, limbAngle: Float, limbDistance: Float) {
            if (state.invisible || !active(state)) return
            val overlay = if (batch == 0) LivingEntityRenderer.getOverlay(state, 0f) else OverlayTexture.DEFAULT_UV
            queue.getBatchingQueue(batch).submitModel(passModel, state, matrices, layer(state), lightOf(light, state), overlay, tint(state), null, state.outlineColor, null)
        }
    }

    companion object {
        val TEXTURE: Identifier = HhaBlood.id("textures/entity/cerberus.png")
        private val BROKEN: Identifier = HhaBlood.id("textures/entity/cerberus_broken.png")
        private val GLOW: RenderLayer = RenderLayers.eyes(HhaBlood.id("textures/entity/cerberus_glow.png"))
        private val GLOW_UNLEASHED: RenderLayer = RenderLayers.eyes(HhaBlood.id("textures/entity/cerberus_glow_unleashed.png"))
    }
}
