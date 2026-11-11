package dev.henny.hha_blood.client.boss

import dev.henny.hha_blood.HhaBlood
import dev.henny.hha_blood.boss.Cine
import dev.henny.hha_blood.boss.MinotaurEntity
import dev.henny.hha_blood.client.boss.Anim.mix
import dev.henny.hha_blood.client.boss.Anim.ramp
import dev.henny.hha_blood.client.cine.CinePlayer
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.entity.EntityRendererFactory
import net.minecraft.client.render.entity.MobEntityRenderer
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

class MinotaurRenderState : LivingEntityRenderState() {
    var action = MinotaurEntity.IDLE
    var t = 0f
    var fury = 0
    var axeOut = false
    var burning = false
    var berserk = false
    /** Second and third beat of the stomp chain (server w(26), w(44)). */
    var beat2 = 26f
    var beat3 = 44f
    /** Windup of the current attack in ticks - exactly the server's `w()` (hit frames line up with the damage). */
    var windup = 18f
    /** Windup of a charge (first charge 26, a repeated one 14 - known from the previous action). */
    var chargeWind = 26f
    /** Whole-body transform computed by the model pose (blocks / degrees). */
    var drop = 0f
    var tilt = 0f
    var back = 0f
    var lean = 0f
    /** Pose of this frame (computed in updateRenderState, applied in setAngles). */
    var pose: Any? = null

    // what the client saw of the motion (see Motion)
    var speed = 0f
    var vy = 0f
    var turn = 0f
    var gait = 0f
    var turnStep = 0f
    var run = 0f
    /** Action time of the landing of a jump (LEAP), -1 while in the air. */
    var landT = -1f
    /** 0..1 mouth movement while one of his lines types out in a dialogue box. */
    var talk = 0f

    // secondary motion (springs, radians)
    var capeP = 0f
    var capeL = 0f
    var loinF = 0f
    var loinB = 0f
    var tailP = 0f
    var tailY = 0f
    var tailTip = 0f
    var earR = 0f
    var earL = 0f
    var jawJig = 0f
}

/**
 * A full pose of the Minotaur (radians); blended between keyframes per action.
 * [ground] 1 = the legs decide the height (the lowest hoof or knee stands on the floor, [drop] is ignored),
 * 0 = [drop] is used as is (sitting, airborne). [sink] is added on top in both cases. [lean] = whole-body roll (degrees).
 */
internal class MPose(
    var armR: Float = 0f, var foreR: Float = 0f, var axe: Float = 0f, var armRRoll: Float = 0f, var armRYaw: Float = 0f,
    var armL: Float = 0f, var foreL: Float = 0f, var armLRoll: Float = 0f, var armLYaw: Float = 0f,
    var torsoPitch: Float = 0f, var torsoYaw: Float = 0f, var headPitch: Float = 0f, var jaw: Float = 0f,
    var legR: Float = 0f, var shinR: Float = 0f, var legL: Float = 0f, var shinL: Float = 0f,
    var drop: Float = 0f, var tilt: Float = 0f, var back: Float = 0f,
    var torsoRoll: Float = 0f, var headYaw: Float = 0f, var headRoll: Float = 0f, var lean: Float = 0f,
    var ground: Float = 0f, var sink: Float = 0f,
) {
    fun copy() = MPose(armR, foreR, axe, armRRoll, armRYaw, armL, foreL, armLRoll, armLYaw, torsoPitch, torsoYaw, headPitch, jaw, legR, shinR, legL, shinL, drop, tilt, back,
        torsoRoll, headYaw, headRoll, lean, ground, sink)

    fun mixTo(o: MPose, t: Float): MPose = MPose(
        mix(armR, o.armR, t), mix(foreR, o.foreR, t), mix(axe, o.axe, t), mix(armRRoll, o.armRRoll, t), mix(armRYaw, o.armRYaw, t),
        mix(armL, o.armL, t), mix(foreL, o.foreL, t), mix(armLRoll, o.armLRoll, t), mix(armLYaw, o.armLYaw, t),
        mix(torsoPitch, o.torsoPitch, t), mix(torsoYaw, o.torsoYaw, t), mix(headPitch, o.headPitch, t), mix(jaw, o.jaw, t),
        mix(legR, o.legR, t), mix(shinR, o.shinR, t), mix(legL, o.legL, t), mix(shinL, o.shinL, t),
        mix(drop, o.drop, t), mix(tilt, o.tilt, t), mix(back, o.back, t),
        mix(torsoRoll, o.torsoRoll, t), mix(headYaw, o.headYaw, t), mix(headRoll, o.headRoll, t), mix(lean, o.lean, t),
        mix(ground, o.ground, t).coerceIn(0f, 1f), mix(sink, o.sink, t),
    )

    /** A changed copy. */
    fun with(change: MPose.() -> Unit): MPose = copy().apply(change)
}

/** One keyframe: at action time [t] the pose is [p]; [e] eases the segment that ends here. */
internal class MKey(val t: Float, val p: MPose, val e: (Float) -> Float = Ease::inOut)

/**
 * Crimson Minotaur model (geometry + texture: tools/textures/GenMinotaur.java -> MinotaurModelData).
 * The axe hangs at the right fist: in rest pose it points forward (-Z), so the total pitch of
 * arm + forearm + axe decides where the blade is (0 = forward, -PI/2 = up, -PI = backwards over the head).
 * Attacks are keyframe tracks timed on the server's windup `w`: anticipation -> fast strike that reaches the hit
 * pose exactly at the hit tick -> overshoot -> settle -> recovery. Walking is a real gait (planted hooves: the
 * height comes from the legs, the knee lifts in the swing), with weight shift, arm swing and head bob on top.
 */
class MinotaurModel(root: ModelPart) : EntityModel<MinotaurRenderState>(root) {
    private val body = root.getChild("body")
    private val torso = body.getChild("torso")
    private val head = torso.getChild("head")
    private val jaw = head.getChild("jaw")
    private val earR = head.getChild("ear_right")
    private val earL = head.getChild("ear_left")
    private val armR = torso.getChild("arm_right")
    private val foreR = armR.getChild("forearm_right")
    private val axe = foreR.getChild("axe")
    private val armL = torso.getChild("arm_left")
    private val foreL = armL.getChild("forearm_left")
    private val cape = torso.getChild("cape")
    private val capeLower = cape.getChild("cape_lower")
    private val legR = body.getChild("leg_right")
    private val shinR = legR.getChild("shin_right")
    private val legL = body.getChild("leg_left")
    private val shinL = legL.getChild("shin_left")
    private val tail = body.getChild("tail")
    private val tailTip = tail.getChild("tail_tip")
    private val loinF = body.getChild("loin_front")
    private val loinB = body.getChild("loin_back")

    companion object {
        val LAYER = EntityModelLayer(HhaBlood.id("crimson_minotaur"), "main")

        /** Model unit in blocks (1/16 * render scale). */
        internal const val UNIT = MinotaurEntity.RENDER_SCALE / 16f
        /** Hip to sole in the rest pose (model units). */
        private const val LEG_REST = 40f
        /** Outline points of shin + hoof (z, y relative to the knee; kneepad, toe, heel) for the floor contact. */
        private val SHIN_POINTS = arrayOf(floatArrayOf(-11.5f, -4f), floatArrayOf(-11.5f, 4f), floatArrayOf(-11f, 20f), floatArrayOf(7f, 20f), floatArrayOf(6f, 0f))

        /** Ready stance: axe held up in front, left hand near the shaft, left hoof forward, knees soft. */
        private val READY = MPose(armR = -0.25f, foreR = -0.75f, axe = 0.15f, armRRoll = 0.08f, armL = -0.55f, foreL = -0.7f, armLYaw = -0.35f, armLRoll = -0.1f,
            torsoPitch = 0.06f, headPitch = 0.12f, legL = -0.18f, shinL = 0.24f, legR = 0.14f, shinR = 0.1f, ground = 1f)
        private val BRAWL = READY.with { armR = -0.85f; foreR = -1.25f; armRRoll = 0.3f; armL = -1.0f; foreL = -1.3f; armLYaw = 0.1f; armLRoll = -0.3f; torsoPitch = 0.18f }
        private val RAISED = MPose(armR = -2.95f, foreR = -0.35f, axe = 0.55f, armL = -2.6f, foreL = -0.5f, armLYaw = -0.4f, torsoPitch = -0.22f, headPitch = -0.15f, jaw = 0.25f, back = -0.05f,
            legL = -0.24f, shinL = 0.2f, legR = 0.2f, shinR = 0.06f, ground = 1f)
        /** Top of the backswing: arched back, the axe far behind the head, trembling with tension. */
        private val RAISED_PEAK = RAISED.with { armR = -3.12f; foreR = -0.45f; axe = 0.5f; armL = -2.78f; torsoPitch = -0.34f; headPitch = -0.3f; jaw = 0.5f; back = -0.1f }
        private val CHOPPED = MPose(armR = -0.95f, foreR = -0.25f, axe = 1.75f, armL = -0.9f, foreL = -0.3f, armLYaw = -0.5f, torsoPitch = 0.42f, headPitch = 0.2f, jaw = 0.35f,
            legL = -0.5f, shinL = 0.62f, legR = 0.32f, shinR = 0.12f, ground = 1f)
        /** Follow-through of a chop: everything a little further down (squash), the blade in the floor. */
        private val CHOP_DEEP = CHOPPED.with { armR = -0.75f; axe = 1.85f; armL = -0.7f; torsoPitch = 0.6f; headPitch = 0.36f; jaw = 0.55f; legL = -0.66f; shinL = 0.9f; legR = 0.44f; shinR = 0.22f }
        private val SIT = MPose(armR = -0.45f, foreR = -0.95f, axe = 0.9f, armL = -0.4f, foreL = -1.0f, legR = -1.5f, shinR = 1.5f, legL = -1.5f, shinL = 1.5f, headPitch = 0.25f, drop = 0.62f, back = 0.42f)
        private val KNEEL = MPose(armR = -0.55f, foreR = -0.45f, axe = 1.45f, armL = -0.2f, foreL = -0.6f, torsoPitch = 0.35f, headPitch = 0.55f, legR = -1.35f, shinR = 1.35f, legL = 0.15f, shinL = 1.45f,
            drop = 0.72f, ground = 1f)
        private val ROAR = MPose(armR = -0.7f, foreR = -0.6f, axe = 0.3f, armRRoll = 0.75f, armL = -0.4f, foreL = -0.5f, armLRoll = -0.85f, torsoPitch = -0.25f, headPitch = -0.6f, jaw = 0.75f,
            legL = -0.3f, shinL = 0.35f, legR = 0.3f, shinR = 0.12f, ground = 1f)
        /** Gathering breath before a roar: hunched, arms in, head low. */
        private val INHALE = MPose(armR = -0.5f, foreR = -0.95f, axe = 0.6f, armRRoll = 0.3f, armL = -0.35f, foreL = -0.95f, armLRoll = -0.3f, torsoPitch = 0.36f, headPitch = 0.6f, jaw = 0.05f,
            legL = -0.45f, shinL = 0.7f, legR = 0.3f, shinR = 0.42f, ground = 1f)
        private val ROAR_BIG = MPose(armR = -0.95f, foreR = -0.4f, axe = 0.3f, armRRoll = 1.1f, armL = -0.75f, foreL = -0.3f, armLRoll = -1.15f, torsoPitch = -0.4f, headPitch = -0.85f, jaw = 0.95f,
            legL = -0.38f, shinL = 0.45f, legR = 0.36f, shinR = 0.16f, ground = 1f)
        private val CROUCH = MPose(armR = -0.15f, foreR = -0.5f, axe = 0.6f, armL = 0.2f, foreL = -0.4f, torsoPitch = 0.45f, headPitch = 0.55f, legR = -0.75f, shinR = 0.95f, legL = -0.55f, shinL = 0.85f,
            drop = 0.42f, ground = 1f)
        private val CROUCH_DEEP = MPose(armR = -0.4f, foreR = -0.9f, axe = 0.8f, armL = -0.2f, foreL = -0.75f, torsoPitch = 0.52f, headPitch = 0.45f, jaw = 0.2f,
            legL = -0.9f, shinL = 1.3f, legR = -0.55f, shinR = 1.2f, ground = 1f)
        private val CHARGE = MPose(armR = 0.55f, foreR = -0.9f, axe = 0.9f, armL = 0.6f, foreL = -0.8f, torsoPitch = 0.5f, headPitch = 0.8f, jaw = 0.25f, tilt = 14f, ground = 1f)
        private val ABSORB = MPose(armR = -2.0f, foreR = -0.3f, axe = 0.4f, armRRoll = 1.0f, armL = -2.0f, foreL = -0.3f, armLRoll = -1.0f, torsoPitch = -0.35f, headPitch = -0.7f, jaw = 0.8f,
            legL = -0.3f, shinL = 0.35f, legR = 0.3f, shinR = 0.15f, ground = 1f)
        /** Airborne: knees pulled up, the axe high behind the head. */
        private val AIR = RAISED_PEAK.with { legL = -1.0f; shinL = 1.45f; legR = -0.45f; shinR = 1.3f; torsoPitch = -0.15f; ground = 0f; drop = 0f }
        /** Falling onto the target: legs reach for the floor, the axe comes over. */
        private val DESCEND = RAISED.with { armR = -2.2f; axe = 0.6f; armL = -2.0f; torsoPitch = 0.1f; headPitch = 0.2f; jaw = 0.5f; legL = -0.55f; shinL = 0.6f; legR = 0.05f; shinR = 0.5f; ground = 0f; drop = 0f }
        private val LAND = CHOP_DEEP.with { legL = -1.0f; shinL = 1.45f; legR = 0.15f; shinR = 1.35f; torsoPitch = 0.66f; jaw = 0.7f }

        private fun k(t: Float, p: MPose, e: (Float) -> Float = Ease::inOut) = MKey(t, p, e)

        /** Pose on a keyframe track at time [t]. */
        private fun track(t: Float, vararg keys: MKey): MPose {
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

        /** Lowest point of a leg below its hip (model units) for thigh [a] and knee [b]: the hoof or the kneepad. */
        internal fun legLow(a: Float, b: Float): Float {
            val ky = 20f * cos(a) - 2f * sin(a)
            val s = a + b
            var low = ky
            for (pt in SHIN_POINTS) low = max(low, ky + pt[1] * cos(s) - pt[0] * sin(s))
            return low
        }

        /** Height correction (blocks) that puts the lowest hoof/knee on the floor. */
        internal fun groundDrop(p: MPose): Float = (LEG_REST - max(legLow(p.legR, p.shinR), legLow(p.legL, p.shinL))) * UNIT
    }

    override fun setAngles(s: MinotaurRenderState) {
        super.setAngles(s)
        val pose = s.pose as? MPose ?: pose(s)
        apply(s, pose)
    }

    /** The cleave family (CLEAVE, QUAKE, LABYRINTH): lift, coil, chop into the floor exactly at [w], carry through, recover. */
    private fun chop(t: Float, w: Float, big: Boolean, recoverAt: Float): MPose {
        val peak = if (big) RAISED_PEAK.with { armL = -3.0f; headPitch = -0.55f; jaw = 0.85f; torsoPitch = -0.4f } else RAISED_PEAK
        val deep = if (big) CHOP_DEEP.with { legL = -0.95f; shinL = 1.35f; legR = 0.35f; shinR = 0.9f; torsoPitch = 0.7f; jaw = 0.8f } else CHOP_DEEP
        val p = track(t,
            k(0f, READY),
            k(w * 0.42f, RAISED, Ease::outCubic),
            k(w - 2.5f, peak),
            k(w, CHOPPED, Ease::inCubic),
            k(w + 2f, deep, Ease::outCubic),
            k(w + 5f, CHOPPED.with { torsoPitch = 0.5f }),
            k(recoverAt - 5f, CHOPPED),
            k(recoverAt, READY),
        )
        // tension at the top of the backswing
        val shiver = ramp(t, w * 0.42f, w - 3f) * (1f - ramp(t, w - 3f, w - 2f))
        p.armR += sin(t * 2.9f) * 0.035f * shiver
        p.armL += sin(t * 2.9f + 1f) * 0.03f * shiver
        p.headPitch += sin(t * 2.3f) * 0.03f * shiver
        return p
    }

    /** The pose of the current action + walking + breathing. */
    internal fun pose(s: MinotaurRenderState): MPose {
        val ready = if (s.berserk) BRAWL else READY
        val t = s.t
        val w = s.windup
        val p: MPose = when (s.action) {
            MinotaurEntity.THRONE -> SIT.copy().also {
                // a king at rest: slow breath, the head turns now and then
                it.headPitch += sin(s.age * 0.05f) * 0.04f + Ease.every(s.age, 140f, 30f) * 0.15f
                it.headYaw = Ease.wobble(s.age * 0.5f, 1.3f) * 0.35f
                it.torsoPitch += sin(s.age * 0.05f) * 0.02f
            }
            MinotaurEntity.STAND -> {
                // lean forward, push up on the knees, rise to full height, roll the shoulders
                val lean = SIT.with { torsoPitch = 0.55f; headPitch = 0.35f; armR = -0.15f; foreR = -1.25f; armL = -0.15f; foreL = -1.3f; back = 0.3f }
                val half = CROUCH_DEEP.with { torsoPitch = 0.4f; headPitch = 0.1f; armL = -0.5f; foreL = -0.9f; ground = 1f }
                val tall = ready.with { torsoPitch = -0.12f; headPitch = -0.2f; armRRoll = 0.3f; armLRoll = -0.35f; jaw = 0.3f }
                track(t, k(0f, SIT), k(10f, lean), k(22f, half, Ease::inOut), k(32f, tall, Ease::outBack), k(44f, ready))
            }
            MinotaurEntity.CLEAVE -> chop(t, w, false, w + 12f)
            MinotaurEntity.QUAKE -> chop(t, w, true, w + 26f).also { q ->
                // he stays down in the crater a moment before he rises
                if (t > w) q.headPitch += sin(t * 1.9f) * 0.03f * (1f - ramp(t, w + 6f, w + 16f))
            }
            MinotaurEntity.LABYRINTH -> chop(t, 18f, true, 40f)
            MinotaurEntity.SWEEP -> {
                val base = MPose(armR = -1.45f, foreR = -0.1f, axe = 1.5f, armRRoll = 0.2f, armL = -1.3f, foreL = -0.3f, armLYaw = -0.6f, torsoPitch = 0.15f,
                    legL = -0.28f, shinL = 0.38f, legR = 0.22f, shinR = 0.16f, ground = 1f)
                val coil = base.with { torsoYaw = 1.2f; torsoRoll = -0.08f; torsoPitch = 0.22f; legL = -0.38f; shinL = 0.6f; legR = 0.3f; shinR = 0.32f; jaw = 0.2f }
                val mid = base.with { torsoYaw = 0f; jaw = 0.45f; torsoPitch = 0.2f }
                val end = base.with { torsoYaw = -1.5f; torsoRoll = 0.12f; armRRoll = 0.45f; jaw = 0.5f; legL = -0.45f; shinL = 0.65f; legR = 0.42f; shinR = 0.2f }
                track(t,
                    k(0f, ready),
                    k(w * 0.55f, base.with { torsoYaw = 0.8f }, Ease::outCubic),
                    k(w - 1f, coil),
                    k(w + 1f, mid, Ease::inQuad),
                    k(w + 3.5f, end, Ease::outCubic),
                    k(w + 6.5f, end.with { torsoYaw = -1.3f; torsoRoll = 0.06f }),
                    k(w + 12f, ready),
                )
            }
            MinotaurEntity.GORE -> {
                val low = MPose(armR = -0.15f, foreR = -0.55f, axe = 0.6f, armL = 0.25f, foreL = -0.45f, torsoPitch = 0.55f, headPitch = 0.78f, jaw = 0.15f,
                    legL = -0.65f, shinL = 0.95f, legR = 0.35f, shinR = 0.55f, ground = 1f)
                val thrust = MPose(armR = -0.45f, foreR = -0.6f, axe = 0.3f, armL = -0.1f, foreL = -0.3f, torsoPitch = -0.25f, headPitch = -0.8f, jaw = 0.45f,
                    legL = -0.45f, shinL = 0.22f, legR = 0.45f, shinR = 0.06f, back = -0.25f, ground = 1f)
                track(t,
                    k(0f, ready),
                    k(w - 1f, low),
                    k(w + 0.5f, thrust, Ease::inQuad),
                    k(w + 2.5f, thrust.with { headPitch = -1.0f; torsoPitch = -0.36f; back = -0.32f }, Ease::outCubic),
                    k(w + 5f, thrust),
                    k(w + 12f, ready),
                ).also { if (t > w + 2f) it.headYaw += sin(t * 1.6f) * 0.12f * (1f - ramp(t, w + 4f, w + 9f)) }
            }
            MinotaurEntity.CHARGE_WINDUP -> {
                // a bull before the charge: low, horns forward, the right hoof scrapes the floor, snorting
                val cw = s.chargeWind
                val low = MPose(armR = 0.35f, foreR = -0.6f, axe = 1.0f, armL = 0.4f, foreL = -0.55f, torsoPitch = 0.5f, headPitch = 0.82f, jaw = 0.12f,
                    legL = -0.42f, shinL = 0.62f, legR = 0.32f, shinR = 0.45f, ground = 1f)
                val c = ready.mixTo(low, Ease.inOut(t / 6f))
                val paw = 1f - ramp(t, cw - 5f, cw - 2f)
                val u = (t % 12f) / 12f
                val lift = if (u < 0.4f) Ease.inOut(u / 0.4f) else 0f
                val drag = if (u >= 0.4f && u < 0.62f) Ease.outCubic((u - 0.4f) / 0.22f) else if (u >= 0.62f) 1f else 0f
                val scrape = if (u < 0.4f) 0.32f - 0.6f * lift else -0.28f + 0.85f * drag
                c.legR = mix(c.legR, scrape, paw * ramp(t, 3f, 6f))
                c.shinR += (if (u < 0.4f) 0.55f * Ease.bump(u / 0.4f) else 0f) * paw
                c.torsoPitch += 0.05f * Ease.bump(drag) * paw
                c.headPitch += sin(t * 0.45f) * 0.08f
                c.headYaw += sin(t * 0.31f) * 0.12f * paw
                c.jaw += 0.2f * Ease.every(t, 12f, 4f, 0.45f)
                // the last moment: everything coils
                val coil = ramp(t, cw - 5f, cw)
                c.torsoPitch += 0.08f * coil; c.headPitch += 0.12f * coil
                c.legL += -0.15f * coil; c.shinL += 0.25f * coil
                c
            }
            MinotaurEntity.CHARGE -> CHARGE.copy().also { it.jaw += sin(t * 0.9f) * 0.08f }
            MinotaurEntity.STUCK -> {
                // bent over the buried axe: three heaves on the shaft (the grunts), then it comes out with a jerk
                val total = 1.43f
                val u = ((t - 3f) % 9f + 9f) % 9f / 9f
                val heave = if (t < 3f) 0f else if (u < 0.28f) Ease.outCubic(u / 0.28f) else 1f - Ease.inOut((u - 0.28f) / 0.72f)
                val c = CHOP_DEEP.copy()
                c.torsoPitch = 0.62f - 0.24f * heave
                c.back = 0.12f * heave
                c.armR = -0.75f - 0.3f * heave
                c.armL = -0.7f - 0.3f * heave
                c.axe = total - c.torsoPitch - c.armR - c.foreR
                c.headPitch = 0.36f - 0.6f * heave
                c.jaw = 0.3f + 0.45f * heave
                c.legL = -0.6f - 0.08f * heave; c.shinL = 0.85f
                c.legR = 0.42f + 0.1f * heave; c.shinR = 0.22f
                val end = MinotaurEntity.STUCK_TICKS.toFloat()
                if (t < end - 4f) c else track(t, k(end - 4f, c), k(end - 1f, c.with { torsoPitch = 0.75f; headPitch = 0.6f }), k(end, RAISED.with { torsoPitch = -0.3f }, Ease::outBack))
            }
            MinotaurEntity.STUNNED -> {
                val recoil = MPose(armR = -0.9f, foreR = -0.3f, axe = 0.9f, armRRoll = 0.35f, armL = -0.6f, foreL = -0.2f, armLRoll = -0.45f, torsoPitch = -0.35f, headPitch = -0.7f, jaw = 0.6f,
                    legL = 0.12f, shinL = 0.2f, legR = -0.28f, shinR = 0.4f, back = 0.35f, ground = 1f)
                val stumble = recoil.with { torsoPitch = 0.2f; headPitch = 0.4f; legL = -0.6f; shinL = 0.9f; legR = 0.1f; shinR = 0.6f; back = 0.15f; armL = -0.9f; lean = -5f }
                val dazed = KNEEL.with { axe = 1.9f; headPitch = 0.45f }
                track(t, k(0f, ready), k(2.5f, recoil, Ease::outCubic), k(7f, stumble), k(13f, dazed, Ease::outBounce)).also {
                    // dizzy: the head lolls in a slow circle, the torso sways
                    val d = ramp(t, 10f, 16f)
                    it.headPitch += sin(t * 0.21f) * 0.22f * d
                    it.headRoll += sin(t * 0.17f + 0.6f) * 0.25f * d
                    it.headYaw += cos(t * 0.21f) * 0.2f * d
                    it.torsoYaw += sin(t * 0.13f) * 0.12f * d
                    it.torsoRoll += sin(t * 0.17f + 1f) * 0.08f * d
                    it.jaw += (0.1f + sin(t * 0.3f) * 0.08f) * d
                }
            }
            MinotaurEntity.WHIRL -> {
                val coil = MPose(armR = -1.5f, foreR = 0f, axe = 1.55f, armRRoll = 0.5f, armL = -1.2f, foreL = -0.4f, armLRoll = -0.3f, torsoYaw = -1.1f, torsoPitch = 0.25f, headPitch = 0.3f,
                    legL = -0.42f, shinL = 0.72f, legR = 0.36f, shinR = 0.5f, ground = 1f)
                val spin = MPose(armR = -1.55f, foreR = 0.05f, axe = 1.5f, armRRoll = 0.95f, armL = -1.4f, foreL = 0f, armLRoll = -0.9f, torsoPitch = 0.12f, headPitch = 0.15f, jaw = 0.4f,
                    legL = -0.15f, shinL = 0.3f, legR = 0.15f, shinR = 0.3f, ground = 1f)
                val dizzy = ready.with { armR = -0.55f; foreR = -0.3f; axe = 1.6f; armL = -0.15f; foreL = -0.3f; headPitch = 0.4f; jaw = 0.3f }
                val end = w + 54f
                val p0 = track(t, k(0f, ready), k(w - 1f, coil), k(w + 2f, spin, Ease::outBack), k(end - 2f, spin), k(end + 4f, dizzy, Ease::outCubic), k(end + 18f, ready))
                // quick steps while it spins, then a wobble
                val sp = ramp(t, w, w + 3f) * (1f - ramp(t, end - 2f, end + 2f))
                p0.legR += sin(t * 1.1f) * 0.3f * sp; p0.legL -= sin(t * 1.1f) * 0.3f * sp
                p0.shinR += max(0f, sin(t * 1.1f)) * 0.6f * sp; p0.shinL += max(0f, -sin(t * 1.1f)) * 0.6f * sp
                p0.lean += 6f * sp
                val dz = ramp(t, end, end + 3f) * (1f - ramp(t, end + 12f, end + 18f))
                p0.lean += sin(t * 0.45f) * 6f * dz
                p0.headRoll += sin(t * 0.45f + 1f) * 0.3f * dz
                p0.torsoRoll += sin(t * 0.45f) * 0.1f * dz
                p0
            }
            MinotaurEntity.THROW -> {
                val backP = MPose(armR = -2.75f, foreR = -0.7f, axe = 0.35f, armRRoll = 0.25f, armL = -1.2f, foreL = -0.15f, armLYaw = 0.15f, torsoPitch = -0.2f, torsoYaw = 0.65f, torsoRoll = -0.06f,
                    jaw = 0.2f, legL = -0.35f, shinL = 0.26f, legR = 0.3f, shinR = 0.2f, back = 0.05f, ground = 1f)
                val rel = MPose(armR = -1.0f, foreR = 0f, axe = 0.4f, armL = 0.35f, foreL = -0.2f, torsoPitch = 0.35f, torsoYaw = -0.55f, torsoRoll = 0.06f, headPitch = 0.1f, jaw = 0.55f,
                    legL = -0.55f, shinL = 0.66f, legR = 0.4f, shinR = 0.15f, back = -0.1f, ground = 1f)
                val wait = MPose(armR = -0.3f, foreR = -0.45f, armL = -0.25f, foreL = -0.35f, torsoPitch = 0.05f, headPitch = -0.1f, jaw = 0.2f,
                    legL = -0.16f, shinL = 0.2f, legR = 0.12f, shinR = 0.1f, ground = 1f)
                val roar = ROAR_BIG.with { armR = -0.6f; armL = -0.6f }
                track(t,
                    k(0f, ready),
                    k(w * 0.6f, backP, Ease::outCubic),
                    k(w - 2f, backP.with { armR = -2.92f; torsoYaw = 0.75f; back = 0.08f }),
                    k(w, rel, Ease::inCubic),
                    k(w + 2.5f, rel.with { armR = -0.45f; torsoPitch = 0.46f; torsoYaw = -0.72f }, Ease::outCubic),
                    k(w + 9f, wait),
                    k(w + 12f, INHALE.with { armR = -0.4f; foreR = -0.6f }),
                    k(w + 15f, roar, Ease::outBack),
                    k(w + 30f, roar),
                    k(w + 38f, wait),
                ).also {
                    if (t > w + 14f && t < w + 32f) { it.jaw += sin(t * 2.1f) * 0.05f; it.headPitch += sin(t * 1.7f) * 0.04f }
                    if (t > w + 38f) { it.armL += Ease.every(t, 50f, 16f) * -0.9f; it.headPitch += sin(t * 0.08f) * 0.05f }
                }
            }
            MinotaurEntity.CATCH -> track(t,
                k(0f, MPose(armR = -2.3f, foreR = -0.3f, axe = 0.5f, armL = -0.3f, headPitch = -0.2f, ground = 1f, legL = -0.16f, shinL = 0.2f, legR = 0.12f, shinR = 0.1f)),
                k(2f, MPose(armR = -2.65f, foreR = -0.45f, axe = 0.7f, armL = -0.2f, headPitch = -0.1f, torsoPitch = -0.12f, back = 0.08f, ground = 1f, legL = -0.1f, shinL = 0.2f, legR = 0.2f, shinR = 0.1f), Ease::outCubic),
                k(10f, ready, Ease::outBack),
            )
            MinotaurEntity.LEAP, MinotaurEntity.SLAM -> {
                val launch = RAISED.with { legL = 0.25f; shinL = 0.1f; legR = 0.45f; shinR = 0.2f; torsoPitch = -0.28f; ground = 0f; drop = 0f }
                when {
                    t < w -> track(t, k(0f, ready), k(w - 1f, CROUCH_DEEP)).also { it.armR += sin(t * 2.5f) * 0.02f * ramp(t, w - 4f, w - 1f) }
                    s.landT < 0f -> {
                        // up: legs push off, then tuck; down: the axe comes over as it falls
                        val up = track(t, k(w - 1f, CROUCH_DEEP), k(w + 1.5f, launch, Ease::outCubic), k(w + 7f, AIR))
                        val fall = ramp(-s.vy, 0.1f, 0.7f)
                        up.mixTo(DESCEND, fall * ramp(t, w + 5f, w + 8f))
                    }
                    else -> {
                        val l = s.landT
                        track(t, k(l, DESCEND), k(l + 1.5f, LAND, Ease::inQuad), k(l + 4f, LAND.with { torsoPitch = 0.56f }, Ease::outCubic), k(l + 12f, CHOPPED), k(l + 22f, ready))
                    }
                }.also { if (s.berserk) {
                    it.armL = it.armR; it.foreL = it.foreR
                    it.armLYaw = 0f; it.armRRoll = 0.3f; it.armLRoll = -0.3f
                } }
            }
            MinotaurEntity.ROAR -> track(t, k(0f, ready), k(5f, INHALE), k(10f, ROAR_BIG, Ease::outBackBig), k(40f, ROAR_BIG), k(56f, ROAR)).also {
                val shout = ramp(t, 9f, 12f) * (1f - 0.6f * ramp(t, 40f, 56f))
                it.jaw += sin(t * 2.1f) * 0.06f * shout
                it.headPitch += sin(t * 1.7f) * 0.05f * shout
                it.headYaw += sin(t * 0.23f) * 0.25f * ramp(t, 16f, 30f)
                it.armR += sin(t * 2.6f) * 0.04f * shout; it.armL += sin(t * 2.6f + 1f) * 0.04f * shout
            }
            MinotaurEntity.HOWL_ROAR -> track(t, k(0f, ready), k(w * 0.6f, INHALE), k(w - 1f, INHALE.with { torsoPitch = 0.44f; headPitch = 0.7f }), k(w + 1.5f, ROAR_BIG, Ease::outBackBig),
                k(w + 12f, ROAR_BIG), k(w + 16f, ready)).also {
                val shout = ramp(t, w, w + 2f) * (1f - ramp(t, w + 12f, w + 16f))
                it.jaw += sin(t * 2.1f) * 0.06f * shout
                it.headPitch += sin(t * 1.7f) * 0.05f * shout
                it.armR += sin(t * 2.6f) * 0.05f * shout; it.armL += sin(t * 2.6f + 1f) * 0.05f * shout
                // swelling chest while he draws breath
                it.torsoPitch -= 0.06f * Ease.bump(t / w) * (1f - ramp(t, w - 2f, w))
            }
            MinotaurEntity.KNEEL -> {
                val buckle = ready.with { legL = -0.5f; shinL = 0.95f; legR = 0.3f; shinR = 0.8f; torsoPitch = 0.3f; headPitch = 0.4f; armR = -0.5f; axe = 1.1f }
                track(t, k(0f, ready), k(6f, buckle, Ease::inQuad), k(16f, KNEEL, Ease::outBounce)).also {
                    // heavy breath, the head hangs
                    val b = sin(s.age * 0.11f)
                    it.torsoPitch += b * 0.035f * ramp(t, 14f, 20f)
                    it.headPitch += b * 0.04f + Ease.wobble(s.age, 4.1f) * 0.05f
                    it.armL += b * 0.03f
                }
            }
            MinotaurEntity.DEATH -> {
                // a last breath, a hand reaching out, then he topples forward: falls faster and faster and hits the
                // floor at tick 36 (the scene plays the impact there), bounces once and lies still
                val gasp = KNEEL.with { headPitch = -0.3f; torsoPitch = 0.1f; armL = -1.25f; foreL = -0.15f; jaw = 0.55f }
                val falling = KNEEL.with { headPitch = 0.1f; armL = -1.6f; foreL = 0f; armR = -0.4f; axe = 2.0f; jaw = 0.4f; tilt = 60f }
                val lying = KNEEL.with { headPitch = 0.8f; armL = -2.6f; foreL = -0.1f; armLRoll = -0.4f; armR = -0.2f; axe = 2.3f; jaw = 0.42f; tilt = 86f }
                track(t, k(0f, KNEEL), k(10f, gasp, Ease::outCubic), k(16f, gasp.with { headPitch = 0f }), k(30f, falling, Ease::inQuad),
                    k(36f, lying, Ease::inQuad), k(39f, lying.with { tilt = 80f; headPitch = 0.6f }, Ease::outQuad), k(43f, lying, Ease::inQuad)).also {
                    it.armL += sin(t * 0.6f) * 0.12f * ramp(t, 8f, 12f) * (1f - ramp(t, 16f, 22f))
                    // the last breaths fade out
                    it.torsoPitch += sin(s.age * 0.09f) * 0.02f * (1f - ramp(t, 44f, 70f))
                }
            }
            MinotaurEntity.LAUGH -> {
                // "HA - HA - HA": the shoulders bounce on each burst, the head goes back, a hand on the belly
                val base = ready.with { armL = -0.45f; foreL = -1.35f; armLYaw = -0.65f; armR = 0.05f; foreR = -0.35f; axe = 1.25f; torsoPitch = -0.12f; headPitch = -0.38f; jaw = 0.3f }
                val c = ready.mixTo(base, Ease.outCubic(t / 6f))
                val burst = Ease.every(t, 7f, 5f)
                c.torsoPitch += -0.07f * burst
                c.headPitch += -0.12f * burst
                c.jaw += 0.35f * burst
                c.armL += 0.06f * burst
                c.sink = 0.02f * burst
                c
            }
            MinotaurEntity.POINT -> {
                val wind = ready.with { armL = -0.2f; foreL = -1.1f; armLYaw = -0.5f; torsoYaw = 0.25f; headPitch = 0f }
                val point = ready.with { armL = -1.65f; foreL = 0f; armLYaw = 0.1f; torsoYaw = -0.2f; torsoPitch = 0.15f; headPitch = 0.3f; jaw = 0.4f; legL = -0.32f; shinL = 0.4f }
                track(t, k(0f, ready), k(4f, wind), k(9f, point, Ease::outBackBig)).also {
                    // two jabs at the hound
                    val jab = Ease.bump((t - 18f) / 4f) + Ease.bump((t - 26f) / 4f)
                    it.armL -= 0.18f * jab; it.torsoPitch += 0.06f * jab; it.jaw += 0.15f * jab
                }
            }
            MinotaurEntity.ABSORB -> track(t, k(0f, ready), k(8f, INHALE.with { headPitch = 0.3f }), k(22f, ABSORB, Ease::outCubic)).also {
                it.jaw = ABSORB.jaw * ramp(t, 10f, 30f)
                // the fire fills him: trembling that grows, the chest arches
                val grow = ramp(t, 18f, 56f)
                it.armR += sin(t * 2.3f) * 0.06f * grow; it.armL += sin(t * 2.3f + 1.4f) * 0.06f * grow
                it.headPitch += sin(t * 1.9f) * 0.05f * grow - 0.15f * grow
                it.torsoPitch -= 0.12f * grow
                it.jaw += sin(t * 2.7f) * 0.05f * grow
            }
            MinotaurEntity.SNORT -> {
                val breathIn = ready.with { torsoPitch = -0.14f; headPitch = -0.42f; jaw = 0f; armRRoll = 0.25f; armLRoll = -0.25f }
                val blow = ready.with { torsoPitch = 0.28f; headPitch = 0.38f; jaw = 0.28f; legL = -0.35f; shinL = 0.45f; legR = 0.25f; shinR = 0.18f }
                track(t, k(0f, ready), k(w - 1f, breathIn), k(w + 1.5f, blow, Ease::outBack), k(w + 36f, blow), k(w + 44f, ready)).also {
                    val f = ramp(t, w, w + 2f) * (1f - ramp(t, w + 34f, w + 38f))
                    it.headPitch += sin(t * 2.2f) * 0.035f * f
                    it.headYaw += sin(t * 0.35f) * 0.1f * f
                    it.torsoPitch += sin(t * 1.3f) * 0.02f * f
                }
            }
            MinotaurEntity.GRAB -> {
                val wind = MPose(armR = -0.3f, foreR = -0.7f, axe = 0.3f, armL = 0.35f, foreL = -0.9f, armLYaw = 0.2f, torsoPitch = 0.18f, torsoYaw = -0.35f, headPitch = 0.3f, jaw = 0.15f,
                    legL = -0.38f, shinL = 0.62f, legR = 0.26f, shinR = 0.4f, ground = 1f)
                val reach = MPose(armR = -0.3f, foreR = -0.7f, axe = 0.3f, armL = -1.55f, foreL = -0.1f, armLYaw = 0.25f, torsoPitch = 0.35f, torsoYaw = 0.2f, headPitch = 0.25f, jaw = 0.3f,
                    legL = -0.55f, shinL = 0.72f, legR = 0.4f, shinR = 0.2f, ground = 1f)
                val hold = MPose(armR = -0.5f, foreR = -0.8f, axe = 0.4f, armRRoll = 0.3f, armL = -2.85f, foreL = -0.25f, armLRoll = -0.15f, torsoPitch = -0.2f, headPitch = -0.35f, jaw = 0.55f, back = -0.05f,
                    legL = -0.22f, shinL = 0.26f, legR = 0.25f, shinR = 0.1f, ground = 1f)
                val high = hold.with { armL = -3.05f; torsoPitch = -0.32f; headPitch = -0.5f; jaw = 0.7f; back = 0.02f }
                val slam = MPose(armR = -0.4f, foreR = -0.6f, axe = 0.6f, armL = -0.5f, foreL = -0.2f, torsoPitch = 0.55f, headPitch = 0.35f, jaw = 0.6f,
                    legL = -0.7f, shinL = 1.0f, legR = 0.4f, shinR = 0.42f, ground = 1f)
                val hEnd = w + MinotaurEntity.GRAB_HOLD
                if (s.berserk && t >= hEnd) {
                    val lift = AIR.with { armR = -2.9f; armL = -2.9f; foreR = -0.3f; foreL = -0.3f; armLYaw = 0f; jaw = 0.65f }
                    val crash = slam.with { armR = -0.8f; armL = -0.8f; foreR = -0.1f; foreL = -0.1f; armLYaw = 0f }
                    if (s.landT < 0f) lift else track(t, k(s.landT, crash), k(s.landT + 5f, crash), k(s.landT + 18f, ready))
                } else track(t,
                    k(0f, ready), k(w * 0.65f, wind), k(w, reach, Ease::inCubic), k(w + 6f, hold, Ease::inOut),
                    k(hEnd - 4f, hold), k(hEnd - 1f, high), k(hEnd + 0.5f, slam, Ease::inCubic), k(hEnd + 3f, slam.with { torsoPitch = 0.68f; jaw = 0.75f }, Ease::outCubic),
                    k(hEnd + 7f, slam), k(hEnd + 16f, ready),
                ).also {
                    if (t > w + 4f && t < hEnd - 3f) {
                        // crushing: a squeeze on every damage tick
                        val sq = Ease.every(t - w, 8f, 4f, -0.5f)
                        it.foreL -= 0.25f * sq; it.headPitch += 0.15f * sq; it.jaw += 0.2f * sq; it.torsoPitch += 0.05f * sq
                        it.armL += sin(t * 0.8f) * 0.05f
                    }
                }
            }
            MinotaurEntity.JUMP_DOWN -> {
                // cutscene: crouch (8 ticks), jump (the scene glides him 24 ticks), slam down on landing (tick 32)
                val launch = RAISED.with { legL = 0.25f; shinL = 0.1f; legR = 0.45f; shinR = 0.2f; torsoPitch = -0.28f; ground = 0f; drop = 0f }
                track(t, k(0f, ready), k(7f, CROUCH_DEEP), k(9.5f, launch, Ease::outCubic), k(16f, AIR), k(25f, AIR.with { legL = -0.8f; legR = -0.3f }),
                    k(31f, DESCEND), k(33f, LAND, Ease::inQuad), k(36f, LAND.with { torsoPitch = 0.58f }, Ease::outCubic), k(42f, CROUCH.with { ground = 1f }), k(50f, ready))
            }
            MinotaurEntity.SNIFF -> {
                // stage 4: nose up, short sharp sniffs, the head sweeps over the corridors
                val nose = ready.with { headPitch = -0.34f; torsoPitch = -0.07f; jaw = 0.04f; armRRoll = 0.12f; armLRoll = -0.1f }
                ready.mixTo(nose, ramp(t, 0f, 6f) * (1f - ramp(t, 34f, 40f))).also {
                    val sn = Ease.every(t - 2f, 9f, 3.5f)
                    it.headPitch -= 0.13f * sn
                    it.jaw += 0.1f * sn
                    it.torsoPitch -= 0.03f * sn
                    it.headYaw += sin(t * 0.22f) * 0.32f
                }
            }
            MinotaurEntity.STOMP -> {
                // berserker: right stomp, left stomp, a hop and both hooves - each beat lands on the server's tick
                val b0 = w
                val b1 = s.beat2
                val b2 = s.beat3
                val liftR = ready.with { legR = -1.05f; shinR = 1.25f; legL = 0.1f; torsoPitch = -0.14f; armRRoll = 0.4f; armLRoll = -0.5f; headPitch = -0.25f; jaw = 0.35f; ground = 1f }
                val stompR = ready.with { legR = 0.05f; shinR = 0.12f; legL = 0.3f; shinL = 0.4f; torsoPitch = 0.4f; headPitch = 0.38f; jaw = 0.7f; armR = -0.55f; armL = -0.15f; armLRoll = -0.3f; ground = 1f }
                val liftL = ready.with { legL = -1.05f; shinL = 1.25f; legR = 0.1f; torsoPitch = -0.14f; armRRoll = 0.45f; armLRoll = -0.4f; headPitch = -0.25f; jaw = 0.35f; ground = 1f }
                val stompL = ready.with { legL = 0.05f; shinL = 0.12f; legR = 0.3f; shinR = 0.4f; torsoPitch = 0.4f; headPitch = 0.38f; jaw = 0.7f; armR = -0.4f; armL = -0.45f; armRRoll = 0.3f; ground = 1f }
                val hop = AIR.with { armR = -2.4f; armL = -2.2f; legL = -0.7f; shinL = 1.2f; legR = -0.7f; shinR = 1.2f }
                track(t,
                    k(0f, ready), k(b0 - 5f, liftR, Ease::outCubic), k(b0, stompR, Ease::inCubic), k(b0 + 3f, stompR.with { torsoPitch = 0.48f }, Ease::outCubic),
                    k(b1 - 5f, liftL, Ease::outCubic), k(b1, stompL, Ease::inCubic), k(b1 + 3f, stompL.with { torsoPitch = 0.48f }, Ease::outCubic),
                    k(b2 - 14f, CROUCH_DEEP), k(b2 - 10f, hop, Ease::outCubic), k(b2 - 2f, DESCEND), k(b2, LAND, Ease::inQuad),
                    k(b2 + 4f, LAND.with { torsoPitch = 0.62f; jaw = 0.85f }, Ease::outCubic), k(b2 + 26f, ready),
                ).also {
                    // rage tremor between the beats
                    it.armR += sin(t * 3.1f) * 0.03f
                    it.armL += sin(t * 2.7f + 1f) * 0.03f
                }
            }
            MinotaurEntity.TACKLE -> {
                // shoulder first: crouch and turn the left shoulder forward, explode out of it, recover
                val coil = CROUCH.with { torsoYaw = -0.45f; armL = 0.35f; foreL = -1.1f; armLRoll = -0.3f; headPitch = 0.65f; ground = 1f }
                val ram = CHARGE.with { torsoYaw = -0.55f; armL = -0.2f; foreL = -1.2f; armLRoll = -0.55f; headPitch = 0.75f; jaw = 0.45f; tilt = 10f }
                track(t, k(0f, ready), k(w - 1f, coil), k(w + 1.5f, ram, Ease::outCubic), k(w + 9f, ram), k(w + 16f, ready))
            }
            MinotaurEntity.RAGE -> {
                // berserker roar: draws breath, then roars with the arms flung out, the whole body shaking
                val out = ROAR_BIG.with { armRRoll = 1.3f; armLRoll = -1.35f; armR = -1.15f; armL = -1.0f; torsoPitch = -0.45f; headPitch = -0.95f }
                track(t, k(0f, ready), k(w * 0.6f, INHALE), k(w - 1f, INHALE.with { torsoPitch = 0.46f; headPitch = 0.72f }),
                    k(w + 1.5f, out, Ease::outBackBig), k(w + 16f, out), k(w + 22f, ready)).also {
                    val f = ramp(t, w, w + 2f) * (1f - ramp(t, w + 15f, w + 20f))
                    it.armR += sin(t * 3.3f) * 0.07f * f
                    it.armL += sin(t * 3.0f + 1f) * 0.07f * f
                    it.headRoll += sin(t * 2.6f) * 0.06f * f
                    it.jaw += sin(t * 4f) * 0.05f * f
                }
            }
            MinotaurEntity.DISCARD -> {
                val wind = ready.with { armR = -1.0f; foreR = -0.8f; armRRoll = 0.9f; armL = 0.2f; torsoYaw = 0.6f; torsoPitch = -0.15f }
                val release = ready.with { armR = -0.9f; foreR = 0.05f; armRRoll = 1.7f; armL = 0.1f; torsoYaw = -0.55f; headYaw = -0.4f; jaw = 0.35f }
                track(t, k(0f, ready), k(10f, wind), k(14f, release, Ease::outCubic), k(23f, release), k(38f, BRAWL))
            }
            MinotaurEntity.PUNCH -> {
                val leftBack = BRAWL.with { torsoYaw = -0.55f; armL = -0.7f; foreL = -1.2f; armLRoll = -0.8f; headPitch = 0.25f }
                val leftHit = BRAWL.with { torsoYaw = 0.5f; armL = -1.5f; foreL = -0.15f; armLYaw = 0.35f; torsoPitch = 0.25f; legL = -0.45f }
                val rightBack = BRAWL.with { torsoYaw = 0.6f; armR = -0.6f; foreR = -1.3f; armRRoll = 0.8f }
                val rightHit = BRAWL.with { torsoYaw = -0.55f; armR = -1.6f; foreR = -0.12f; torsoPitch = 0.25f; legR = -0.4f }
                val hammerUp = BRAWL.with { armR = -2.9f; foreR = -0.5f; torsoPitch = -0.3f; headPitch = -0.2f; jaw = 0.4f }
                val hammerDown = BRAWL.with { armR = -1.0f; foreR = -0.05f; torsoPitch = 0.6f; headPitch = 0.4f; legL = -0.7f; shinL = 0.85f; jaw = 0.6f }
                track(t, k(0f, BRAWL), k(10f, leftBack), k(14f, leftHit, Ease::inCubic), k(19f, BRAWL),
                    k(24f, rightBack), k(28f, rightHit, Ease::inCubic), k(34f, BRAWL), k(48f, hammerUp), k(58f, hammerUp.with { torsoPitch = -0.4f; jaw = 0.6f }),
                    k(62f, hammerDown, Ease::inCubic), k(70f, hammerDown), k(88f, BRAWL))
            }
            MinotaurEntity.UPPERCUT -> {
                val coil = CROUCH_DEEP.with { armR = 0.3f; foreR = -0.9f; armL = -1.1f; foreL = -1.2f; torsoYaw = 0.6f; headPitch = 0.4f }
                val hit = BRAWL.with { armR = -2.5f; foreR = -0.9f; torsoPitch = -0.4f; torsoYaw = -0.5f; headPitch = -0.4f; legR = -0.25f; jaw = 0.6f }
                track(t, k(0f, BRAWL), k(12f, coil), k(18f, hit, Ease::inCubic), k(23f, hit), k(42f, BRAWL))
            }
            MinotaurEntity.SPLIT -> {
                val up = RAISED_PEAK.with { armR = -2.85f; armL = -2.85f; foreR = -0.65f; foreL = -0.65f; armRRoll = 0.3f; armLRoll = -0.3f; armLYaw = 0f; jaw = 0.5f }
                val down = LAND.with { armR = -1.0f; armL = -1.0f; foreR = -0.05f; foreL = -0.05f; armRRoll = 0.25f; armLRoll = -0.25f; armLYaw = 0f; torsoPitch = 0.7f }
                track(t, k(0f, BRAWL), k(w - 4f, up), k(w - 1f, up), k(w, down, Ease::inCubic),
                    k(w + 16f, down), k(w + 20f, up.with { torsoPitch = -0.42f; jaw = 0.85f }, Ease::outCubic), k(w + 28f, up), k(w + 48f, BRAWL))
            }
            else -> ready.copy().also {
                // idle life: weight shifts slowly from hoof to hoof, the axe is re-gripped, a snort tosses the head
                val shift = Ease.wobble(s.age * 0.7f, 0.4f)
                it.legL += shift * 0.06f; it.shinL += max(0f, shift) * 0.1f
                it.legR -= shift * 0.04f; it.shinR += max(0f, -shift) * 0.1f
                it.torsoRoll += shift * 0.03f
                val grip = Ease.every(s.age, 97f, 14f, 0.3f)
                it.axe += 0.18f * grip; it.foreR -= 0.08f * grip
                val snort = Ease.every(s.age, 131f, 9f, 0.7f)
                it.headPitch -= 0.22f * snort; it.jaw += 0.18f * snort
                it.headYaw += Ease.wobble(s.age, 2.2f) * 0.12f
            }
        }
        val pose = p
        // the gait on top of the action pose
        val canWalk = s.action != MinotaurEntity.THRONE && s.action != MinotaurEntity.KNEEL && s.action != MinotaurEntity.DEATH && s.action != MinotaurEntity.STUNNED &&
            s.action != MinotaurEntity.STAND && s.action != MinotaurEntity.JUMP_DOWN && !(s.action == MinotaurEntity.GRAB && s.berserk && t >= w + MinotaurEntity.GRAB_HOLD) && !((s.action == MinotaurEntity.LEAP || s.action == MinotaurEntity.SLAM) && t >= w) && s.action != MinotaurEntity.STOMP
        val running = s.action == MinotaurEntity.CHARGE
        val gw = if (canWalk) (if (running) ramp(s.speed, 0.05f, 0.3f) else ramp(s.speed, 0.012f, 0.07f)) else 0f
        if (gw > 0f) {
            val a = if (running) 0.85f else 0.52f
            val knee = if (running) 1.45f else 1.05f
            val phR = s.gait
            val phL = phR + PI.toFloat()
            val thR = -a * cos(phR)
            val thL = -a * cos(phL)
            pose.legR += thR * gw
            pose.legL += thL * gw
            pose.shinR += (0.08f * max(0f, sin(phR)) + knee * max(0f, -sin(phR))) * gw
            pose.shinL += (0.08f * max(0f, sin(phL)) + knee * max(0f, -sin(phL))) * gw
            val arms = if (running) 0.8f else 0.4f
            pose.armL += -thL * arms * gw
            pose.armR += -thR * arms * 0.4f * gw
            pose.torsoYaw += 0.12f * thL * gw
            pose.torsoRoll += -0.05f * sin(phR) * gw
            pose.headRoll += 0.03f * sin(phR) * gw
            pose.headPitch += 0.05f * sin(2f * phR) * gw
            if (running) pose.torsoPitch += 0.08f * cos(2f * phR) * gw
            // heavy steps land on the jaw and the ears (see the springs)
        }
        // turning on the spot: small steps, the torso and head lead into the turn
        val tw = ramp(abs(s.turn), 1.5f, 5f) * (1f - gw) * (if (canWalk && s.action != MinotaurEntity.WHIRL) 1f else 0f)
        if (tw > 0f) {
            val ph = s.turnStep
            pose.legR += -0.18f * cos(ph) * tw
            pose.legL += 0.18f * cos(ph) * tw
            pose.shinR += 0.7f * max(0f, -sin(ph)) * tw
            pose.shinL += 0.7f * max(0f, sin(ph)) * tw
        }
        if (s.action != MinotaurEntity.WHIRL && s.action != MinotaurEntity.DEATH && s.action != MinotaurEntity.THRONE) {
            val lead = (s.turn * (PI.toFloat() / 180f)).coerceIn(-0.6f, 0.6f)
            pose.torsoYaw += lead * 0.5f
            pose.headYaw += lead * 0.7f
        }

        // breathing (slower and deeper when he is hurt/furious)
        val breath = sin(s.age * (0.07f + 0.004f * s.fury))
        pose.torsoPitch += breath * 0.02f
        pose.armRRoll += breath * 0.015f
        pose.armLRoll -= breath * 0.015f
        return pose
    }

    private fun apply(s: MinotaurRenderState, pose: MPose) {
        body.pitch = 0f
        torso.pitch = pose.torsoPitch
        torso.yaw = pose.torsoYaw
        torso.roll = pose.torsoRoll
        // head follows the target (limited) on top of the action
        val look = if (s.action == MinotaurEntity.THRONE || s.action == MinotaurEntity.DEATH || s.action == MinotaurEntity.STUNNED) 0f else 1f
        head.yaw = (s.relativeHeadYaw * (PI.toFloat() / 180f)).coerceIn(-0.9f, 0.9f) * look - pose.torsoYaw * 0.7f + pose.headYaw
        head.pitch = pose.headPitch + (s.pitch * (PI.toFloat() / 180f)) * 0.5f * look
        head.roll = pose.headRoll - pose.torsoRoll * 0.5f
        jaw.pitch = (pose.jaw + s.jawJig + s.talk * 0.32f).coerceAtLeast(0f)
        earR.roll += s.earR
        earL.roll -= s.earL
        armR.pitch = pose.armR; armR.roll = pose.armRRoll; armR.yaw = pose.armRYaw
        foreR.pitch = pose.foreR
        axe.pitch = pose.axe
        axe.visible = !s.axeOut && !s.berserk
        armL.pitch = pose.armL; armL.roll = pose.armLRoll; armL.yaw = pose.armLYaw
        foreL.pitch = pose.foreL
        legR.pitch = pose.legR; shinR.pitch = pose.shinR
        legL.pitch = pose.legL; shinL.pitch = pose.shinL
        // cloth and tail: springs (see MinotaurRenderer.secondary) - they swing behind the motion
        cape.pitch = s.capeP - pose.torsoPitch * 0.8f
        capeLower.pitch = s.capeL
        loinF.pitch = s.loinF
        loinB.pitch = s.loinB
        tail.pitch = s.tailP
        tail.yaw = s.tailY
        tailTip.pitch = s.tailTip
    }
}

class MinotaurRenderer(ctx: EntityRendererFactory.Context) :
    MobEntityRenderer<MinotaurEntity, MinotaurRenderState, MinotaurModel>(ctx, MinotaurModel(ctx.getPart(MinotaurModel.LAYER)), 1.2f) {

    /** Client-only animation memory per Minotaur (motion, springs, cross-fades). */
    private class Memory {
        val m = Motion()
        var wind = 12f
        var chargeWind = 26f
        var beat2 = 26f
        var beat3 = 44f
        val capeP = Spring(0.32f, 0.32f)
        val capeL = Spring(0.42f, 0.28f)
        val loinF = Spring(0.55f, 0.38f)
        val loinB = Spring(0.55f, 0.38f)
        val tailP = Spring(0.3f, 0.25f)
        val tailY = Spring(0.28f, 0.22f)
        val tailTip = Spring(0.45f, 0.25f)
        val earR = Spring(0.6f, 0.22f)
        val earL = Spring(0.6f, 0.22f)
        val jaw = Spring(0.8f, 0.3f)
        val talk = Spring(0.9f, 0.7f)
        var lastDrop = Float.NaN
        var dropVel = 0f
        var lastHead = 0f
    }

    private val memory = WeakHashMap<MinotaurEntity, Memory>()

    init {
        addFeature(BossGlowFeature(this, { GLOW_LAYER }, { s ->
            val pulse = 0.82f + 0.18f * sin(s.age * 0.12f)
            val fury = 0.7f + 0.03f * s.fury
            when {
                // the berserker: eyes and veins burn red, fast pulse
                s.berserk -> Anim.tint(0.85f + 0.15f * sin(s.age * 0.45f), 1f, 0.32f, 0.22f)
                s.burning -> Anim.tint(pulse * 1.0f, 1f, 0.78f, 0.45f)
                else -> Anim.tint(pulse * fury)
            }
        }))
    }

    override fun createRenderState(): MinotaurRenderState = MinotaurRenderState()

    override fun getTexture(state: MinotaurRenderState): Identifier = TEXTURE

    /** The server's windup `w(base)` (MinotaurEntity.w: Double maths, truncated, at least 5 ticks). */
    private fun w(base: Int, e: MinotaurEntity): Float =
        (base * (1.0 - 0.035 * e.fury) * (if (e.burning) 0.85 else 1.0) * (if (e.berserk) 0.82 else 1.0)).toInt().coerceAtLeast(5).toFloat()

    override fun updateRenderState(entity: MinotaurEntity, state: MinotaurRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        val mem = memory.getOrPut(entity) { Memory() }
        val m = mem.m
        val dt = m.update(entity, tickDelta, entity.action, entity.actionAge)
        state.action = entity.action
        state.t = entity.actionTime(tickDelta)
        state.fury = entity.fury
        state.axeOut = entity.axeOut
        state.burning = entity.burning
        state.berserk = entity.berserk
        if (m.started) {
            // frozen for the whole action: a hit raises the fury, but the server timed this attack with the old one
            mem.wind = when (entity.action) {
                MinotaurEntity.CLEAVE -> w(18, entity)
                MinotaurEntity.SWEEP -> w(14, entity)
                MinotaurEntity.GORE -> w(9, entity)
                MinotaurEntity.THROW -> w(16, entity)
                MinotaurEntity.LEAP -> w(14, entity)
                MinotaurEntity.GRAB -> if (entity.berserk) 24f else w(MinotaurEntity.GRAB_WIND, entity)
                MinotaurEntity.QUAKE -> w(MinotaurEntity.QUAKE_WIND, entity)
                MinotaurEntity.HOWL_ROAR -> w(22, entity)
                MinotaurEntity.SNORT -> w(12, entity)
                MinotaurEntity.WHIRL -> w(12, entity)
                MinotaurEntity.TACKLE -> w(10, entity)
                MinotaurEntity.SLAM -> w(16, entity)
                MinotaurEntity.SPLIT -> 26f
                MinotaurEntity.STOMP -> 18f
                MinotaurEntity.RAGE -> w(14, entity)
                else -> w(12, entity)
            }
            mem.chargeWind = if (m.previous == MinotaurEntity.CHARGE) w(14, entity) else w(26, entity)
            mem.beat2 = 40f
            mem.beat3 = 76f
        }
        state.windup = mem.wind
        state.chargeWind = mem.chargeWind
        state.beat2 = mem.beat2
        state.beat3 = mem.beat3
        state.speed = m.speed
        state.vy = m.vy
        state.turn = m.turn
        // gait: one cycle per 3.2 blocks (walking - the hooves stay planted), running at most one cycle per 8 ticks
        val running = entity.action == MinotaurEntity.CHARGE
        m.advanceGait(dt, if (running) 6.5f else 3.2f, if (running) 0.8f else 0.75f)
        m.turnStep += abs(m.turn) * dt * (2f * PI.toFloat() / 50f)
        state.gait = m.gait
        state.turnStep = m.turnStep
        state.landT = when {
            entity.action == MinotaurEntity.GRAB && entity.berserk -> m.landing(entity, state.t, mem.wind + MinotaurEntity.GRAB_HOLD + 4f, mem.wind + MinotaurEntity.GRAB_HOLD + 40f)
            entity.action == MinotaurEntity.LEAP || entity.action == MinotaurEntity.SLAM -> m.landing(entity, state.t, mem.wind + 4f, mem.wind + 40f)
            else -> -1f
        }
        // his lines in a dialogue box move his mouth
        state.talk = mem.talk.step(CinePlayer.mouth(Cine.SPEAKER_MINOTAUR, entity.age + tickDelta), dt).coerceIn(0f, 1f) *
            (if (entity.action == MinotaurEntity.DEATH || entity.action == MinotaurEntity.ROAR || entity.action == MinotaurEntity.HOWL_ROAR) 0f else 1f)
        // the vanilla death tilt is replaced by our own DEATH pose
        state.deathTime = 0f

        var pose = model.pose(state)
        // cross-fade into a new action from wherever the last one left the body (no pops between actions)
        if (m.started) {
            m.fromPose = m.lastPose
            m.blendStart = m.time
            m.blendLen = when (entity.action) {
                MinotaurEntity.STUNNED, MinotaurEntity.CATCH -> 2.5f
                MinotaurEntity.DEATH, MinotaurEntity.KNEEL, MinotaurEntity.STAND -> 6f
                MinotaurEntity.STUCK -> 2f
                else -> 4.5f
            }
        }
        val from = m.fromPose as? MPose
        if (from != null) {
            val k = (m.time - m.blendStart) / m.blendLen
            if (k >= 1f) m.fromPose = null else pose = from.mixTo(pose, Ease.inOut(k))
        }
        m.lastPose = pose
        state.pose = pose
        val drop = mix(pose.drop, MinotaurModel.groundDrop(pose), pose.ground) + pose.sink
        state.drop = drop
        state.tilt = pose.tilt
        state.back = pose.back
        state.lean = pose.lean
        secondary(mem, state, pose, drop, dt)
    }

    /** Cloth, loincloth, tail, ears and jaw follow the body with springs: they lag, swing past and settle. */
    private fun secondary(mem: Memory, s: MinotaurRenderState, p: MPose, drop: Float, dt: Float) {
        if (dt > 0f) {
            if (!mem.lastDrop.isNaN()) {
                val v = (mem.lastDrop - drop) / dt          // + = the body moves up
                val acc = (v - mem.dropVel) / dt
                mem.dropVel = v
                // a heavy step or a landing (sudden stop of a downward motion) shakes the jaw and the ears
                if (acc > 0.01f) { mem.jaw.kick(acc * 1.6f); mem.earR.kick(acc * 3f); mem.earL.kick(acc * 3f) }
            }
            mem.lastDrop = drop
            val headMove = (p.headPitch - mem.lastHead) / dt
            mem.lastHead = p.headPitch
            mem.earR.kick(-headMove * 0.25f * dt); mem.earL.kick(-headMove * 0.25f * dt)
        }
        val run = (s.speed * 1.6f).coerceAtMost(1.1f)
        val rise = (-s.vy * 1.2f).coerceIn(-0.4f, 0.9f)
        val sway = Ease.wobble(s.age * 1.3f, 0.2f) * 0.05f
        s.capeP = mem.capeP.step(0.12f + run + rise * 0.8f + sway + (if (s.action == MinotaurEntity.CHARGE) 0.5f else 0f), dt)
        s.capeL = mem.capeL.step(0.08f + sway * 0.7f + rise * 0.4f - mem.capeP.v * 2.5f, dt)
        s.loinF = mem.loinF.step(minOf(p.legR, p.legL).coerceAtMost(0f) * 0.75f - rise * 0.3f + run * 0.2f, dt)
        s.loinB = mem.loinB.step(maxOf(p.legR, p.legL).coerceAtLeast(0f) * 0.75f + 0.05f + rise * 0.3f + run * 0.25f, dt)
        val turn = (s.turn * (PI.toFloat() / 180f)).coerceIn(-0.6f, 0.6f)
        s.tailP = mem.tailP.step(0.25f + sin(s.age * 0.11f) * 0.08f + run * 0.5f + rise * 0.6f, dt)
        s.tailY = mem.tailY.step(sin(s.age * 0.13f) * 0.25f - turn * 1.2f + sin(s.gait) * 0.15f * ramp(s.speed, 0.02f, 0.1f), dt)
        s.tailTip = mem.tailTip.step(sin(s.age * 0.11f + 1f) * 0.15f - mem.tailP.v * 2f, dt)
        s.earR = mem.earR.step(0f, dt).coerceIn(-0.6f, 0.6f)
        s.earL = mem.earL.step(0f, dt).coerceIn(-0.6f, 0.6f)
        s.jawJig = mem.jaw.step(0f, dt).coerceIn(-0.1f, 0.35f)
    }

    override fun setupTransforms(state: MinotaurRenderState, matrices: MatrixStack, bodyYaw: Float, baseHeight: Float) {
        super.setupTransforms(state, matrices, bodyYaw, baseHeight)
        if (state.drop != 0f) matrices.translate(0f, -state.drop, 0f)
        if (state.back != 0f) matrices.translate(0f, 0f, -state.back)
        if (state.tilt != 0f) matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(state.tilt))
        if (state.lean != 0f) matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(state.lean))
    }

    override fun scale(state: MinotaurRenderState, matrices: MatrixStack) {
        matrices.scale(MinotaurEntity.RENDER_SCALE, MinotaurEntity.RENDER_SCALE, MinotaurEntity.RENDER_SCALE)
    }

    companion object {
        val TEXTURE: Identifier = HhaBlood.id("textures/entity/minotaur.png")
        private val GLOW_LAYER: RenderLayer = RenderLayers.eyes(HhaBlood.id("textures/entity/minotaur_glow.png"))
    }
}
