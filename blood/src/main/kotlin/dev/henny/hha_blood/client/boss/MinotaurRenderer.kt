package dev.henny.hha_blood.client.boss

import dev.henny.hha_blood.HhaBlood
import dev.henny.hha_blood.boss.MinotaurEntity
import dev.henny.hha_blood.client.boss.Anim.mix
import dev.henny.hha_blood.client.boss.Anim.ramp
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class MinotaurRenderState : LivingEntityRenderState() {
    var action = MinotaurEntity.IDLE
    var t = 0f
    var fury = 0
    var axeOut = false
    var burning = false
    var windup = 18f
    /** Whole-body transform computed by the model pose (blocks / degrees). */
    var drop = 0f
    var tilt = 0f
    var back = 0f
    /** Pose of this frame (computed in updateRenderState, applied in setAngles). */
    var pose: Any? = null
}

/** A full pose of the Minotaur (radians); blended between keyframes per action. */
internal class MPose(
    var armR: Float = 0f, var foreR: Float = 0f, var axe: Float = 0f, var armRRoll: Float = 0f, var armRYaw: Float = 0f,
    var armL: Float = 0f, var foreL: Float = 0f, var armLRoll: Float = 0f, var armLYaw: Float = 0f,
    var torsoPitch: Float = 0f, var torsoYaw: Float = 0f, var headPitch: Float = 0f, var jaw: Float = 0f,
    var legR: Float = 0f, var shinR: Float = 0f, var legL: Float = 0f, var shinL: Float = 0f,
    var drop: Float = 0f, var tilt: Float = 0f, var back: Float = 0f,
) {
    fun copy() = MPose(armR, foreR, axe, armRRoll, armRYaw, armL, foreL, armLRoll, armLYaw, torsoPitch, torsoYaw, headPitch, jaw, legR, shinR, legL, shinL, drop, tilt, back)

    fun mixTo(o: MPose, t: Float): MPose = MPose(
        mix(armR, o.armR, t), mix(foreR, o.foreR, t), mix(axe, o.axe, t), mix(armRRoll, o.armRRoll, t), mix(armRYaw, o.armRYaw, t),
        mix(armL, o.armL, t), mix(foreL, o.foreL, t), mix(armLRoll, o.armLRoll, t), mix(armLYaw, o.armLYaw, t),
        mix(torsoPitch, o.torsoPitch, t), mix(torsoYaw, o.torsoYaw, t), mix(headPitch, o.headPitch, t), mix(jaw, o.jaw, t),
        mix(legR, o.legR, t), mix(shinR, o.shinR, t), mix(legL, o.legL, t), mix(shinL, o.shinL, t),
        mix(drop, o.drop, t), mix(tilt, o.tilt, t), mix(back, o.back, t),
    )
}

/**
 * Crimson Minotaur model (geometry + texture: tools/textures/GenMinotaur.java -> MinotaurModelData).
 * The axe hangs at the right fist: in rest pose it points forward (-Z), so the total pitch of
 * arm + forearm + axe decides where the blade is (0 = forward, -PI/2 = up, -PI = backwards over the head).
 */
class MinotaurModel(root: ModelPart) : EntityModel<MinotaurRenderState>(root) {
    private val body = root.getChild("body")
    private val torso = body.getChild("torso")
    private val head = torso.getChild("head")
    private val jaw = head.getChild("jaw")
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

        /** Ready stance: axe held up in front, left hand near the shaft. */
        private val READY = MPose(armR = -0.25f, foreR = -0.75f, axe = 0.15f, armRRoll = 0.08f, armL = -0.55f, foreL = -0.7f, armLYaw = -0.35f, armLRoll = -0.1f, headPitch = 0.12f)
        private val RAISED = MPose(armR = -2.95f, foreR = -0.35f, axe = 0.55f, armL = -2.6f, foreL = -0.5f, armLYaw = -0.4f, torsoPitch = -0.22f, headPitch = -0.15f, jaw = 0.25f, back = -0.05f)
        private val CHOPPED = MPose(armR = -0.95f, foreR = -0.25f, axe = 1.75f, armL = -0.9f, foreL = -0.3f, armLYaw = -0.5f, torsoPitch = 0.42f, headPitch = 0.2f, jaw = 0.35f, legR = -0.35f, shinR = 0.35f, legL = 0.25f, drop = 0.18f)
        private val SIT = MPose(armR = -0.45f, foreR = -0.95f, axe = 0.9f, armL = -0.4f, foreL = -1.0f, legR = -1.5f, shinR = 1.5f, legL = -1.5f, shinL = 1.5f, headPitch = 0.25f, drop = 0.62f, back = 0.42f)
        private val KNEEL = MPose(armR = -0.55f, foreR = -0.45f, axe = 1.45f, armL = -0.2f, foreL = -0.6f, torsoPitch = 0.35f, headPitch = 0.55f, legR = -1.35f, shinR = 1.35f, legL = 0.15f, shinL = 1.45f, drop = 0.72f)
        private val ROAR = MPose(armR = -0.7f, foreR = -0.6f, axe = 0.3f, armRRoll = 0.75f, armL = -0.4f, foreL = -0.5f, armLRoll = -0.85f, torsoPitch = -0.25f, headPitch = -0.6f, jaw = 0.75f)
        private val CROUCH = MPose(armR = -0.15f, foreR = -0.5f, axe = 0.6f, armL = 0.2f, foreL = -0.4f, torsoPitch = 0.45f, headPitch = 0.55f, legR = -0.75f, shinR = 0.95f, legL = -0.55f, shinL = 0.85f, drop = 0.42f)
        private val CHARGE = MPose(armR = 0.55f, foreR = -0.9f, axe = 0.9f, armL = 0.6f, foreL = -0.8f, torsoPitch = 0.55f, headPitch = 0.75f, jaw = 0.2f, tilt = 18f)
        private val ABSORB = MPose(armR = -2.0f, foreR = -0.3f, axe = 0.4f, armRRoll = 1.0f, armL = -2.0f, foreL = -0.3f, armLRoll = -1.0f, torsoPitch = -0.35f, headPitch = -0.7f, jaw = 0.8f)
    }

    override fun setAngles(s: MinotaurRenderState) {
        super.setAngles(s)
        val pose = s.pose as? MPose ?: pose(s)
        apply(s, pose)
    }

    /** The pose of the current action + walking + breathing. */
    internal fun pose(s: MinotaurRenderState): MPose {
        val t = s.t
        val w = s.windup
        val p: MPose = when (s.action) {
            MinotaurEntity.THRONE -> SIT.copy().also { it.headPitch += sin(s.age * 0.05f) * 0.04f }
            MinotaurEntity.STAND -> SIT.mixTo(READY, ramp(t, 4f, 40f))
            MinotaurEntity.CLEAVE, MinotaurEntity.QUAKE -> when {
                t < w - 2 -> READY.mixTo(RAISED, ramp(t, 0f, w - 4))
                t < w + 2 -> RAISED.mixTo(CHOPPED, ramp(t, w - 2, w + 1))
                else -> CHOPPED.mixTo(READY, ramp(t, w + 6, w + 16))
            }
            MinotaurEntity.LABYRINTH -> when {
                t < 15 -> READY.mixTo(RAISED, ramp(t, 0f, 13f))
                t < 20 -> RAISED.mixTo(CHOPPED, ramp(t, 15f, 18f))
                else -> CHOPPED.mixTo(READY, ramp(t, 28f, 40f))
            }
            MinotaurEntity.SWEEP -> {
                val sweep = MPose(armR = -1.45f, foreR = -0.1f, axe = 1.5f, armRRoll = 0.2f, armL = -1.3f, foreL = -0.3f, armLYaw = -0.6f, torsoPitch = 0.15f)
                when {
                    t < w -> READY.mixTo(sweep.copy().also { it.torsoYaw = 1.05f }, ramp(t, 0f, w - 1))
                    t < w + 5 -> sweep.copy().also { it.torsoYaw = mix(1.05f, -1.25f, ramp(t, w, w + 4)); it.jaw = 0.4f }
                    else -> sweep.copy().also { it.torsoYaw = -1.25f }.mixTo(READY, ramp(t, w + 6, w + 14))
                }
            }
            MinotaurEntity.GORE -> when {
                t < w -> READY.mixTo(CROUCH, ramp(t, 0f, w))
                t < w + 5 -> CROUCH.mixTo(READY.copy().also { it.headPitch = -0.7f; it.torsoPitch = -0.2f; it.jaw = 0.3f }, ramp(t, w, w + 3))
                else -> READY.copy().also { it.headPitch = -0.7f }.mixTo(READY, ramp(t, w + 5, w + 12))
            }
            MinotaurEntity.CHARGE_WINDUP -> CROUCH.copy().also {
                // pawing the ground with the right hoof
                it.legR += sin(t * 0.55f) * 0.35f
                it.shinR += abs(sin(t * 0.55f)) * 0.3f
                it.headPitch += sin(t * 0.3f) * 0.08f
            }
            MinotaurEntity.CHARGE -> CHARGE.copy()
            MinotaurEntity.STUCK -> {
                // bent over the buried axe, tugging at it; at the end it comes out
                val k = sin(t * 0.85f)
                val tug = CHOPPED.copy().also {
                    it.torsoPitch += 0.08f + k * 0.07f
                    it.armR += k * 0.07f; it.armL += k * 0.07f
                    it.headPitch = 0.05f + k * 0.12f
                    it.jaw = 0.45f + abs(k) * 0.2f
                    it.legL += 0.15f
                }
                val end = MinotaurEntity.STUCK_TICKS.toFloat()
                if (t < end - 3) tug else tug.mixTo(RAISED, ramp(t, end - 3, end))
            }
            MinotaurEntity.STUNNED -> KNEEL.copy().also {
                it.headPitch = 0.3f + sin(t * 0.25f) * 0.25f
                it.torsoYaw = sin(t * 0.17f) * 0.15f
                it.axe = 1.9f
            }
            MinotaurEntity.WHIRL -> MPose(armR = -1.5f, foreR = 0f, axe = 1.55f, armRRoll = 0.4f, armL = -1.45f, foreL = 0f, armLRoll = -0.5f, torsoPitch = 0.1f, headPitch = 0.2f, jaw = 0.3f)
            MinotaurEntity.THROW -> when {
                t < w -> READY.mixTo(MPose(armR = -2.7f, foreR = -0.6f, axe = 0.4f, armL = -0.6f, foreL = -0.4f, torsoPitch = -0.15f, torsoYaw = 0.5f), ramp(t, 0f, w - 2))
                t < w + 4 -> MPose(armR = -1.2f, foreR = 0f, axe = 0.4f, armL = 0.3f, torsoPitch = 0.3f, torsoYaw = -0.4f, jaw = 0.5f)
                else -> MPose(armR = -0.3f, foreR = -0.4f, armL = -0.2f, foreL = -0.3f, headPitch = -0.2f + sin(t * 0.2f) * 0.1f, jaw = 0.3f)
            }
            MinotaurEntity.CATCH -> MPose(armR = -2.1f, foreR = -0.3f, axe = 0.5f, armL = -0.3f, headPitch = -0.2f).mixTo(READY, ramp(t, 3f, 10f))
            MinotaurEntity.LEAP -> when {
                t < w -> READY.mixTo(CROUCH, ramp(t, 0f, w))
                t < w + 30 -> RAISED.copy().also { it.legR = -0.6f; it.shinR = 0.9f; it.legL = 0.3f; it.shinL = 0.6f }
                else -> CHOPPED.mixTo(READY, ramp(t, w + 30, w + 40))
            }
            MinotaurEntity.ROAR, MinotaurEntity.HOWL_ROAR -> READY.mixTo(ROAR, ramp(t, 0f, 8f)).also {
                it.headPitch += sin(t * 0.9f) * 0.05f
                it.jaw = ROAR.jaw * ramp(t, 4f, 10f) + sin(t * 1.3f) * 0.05f
            }
            MinotaurEntity.KNEEL -> READY.mixTo(KNEEL, ramp(t, 0f, 18f))
            MinotaurEntity.DEATH -> KNEEL.copy().also {
                val f = ramp(t, 10f, 40f)
                it.tilt = 82f * f
                it.armR = mix(KNEEL.armR, -0.2f, f); it.axe = 2.2f
                it.headPitch = 0.3f + 0.5f * f
                it.jaw = 0.4f * f
            }
            MinotaurEntity.LAUGH -> READY.copy().also {
                it.headPitch = -0.35f + sin(t * 1.1f) * 0.08f
                it.torsoPitch = -0.1f + sin(t * 1.1f) * 0.05f
                it.jaw = 0.35f + sin(t * 1.4f) * 0.2f
            }
            MinotaurEntity.POINT -> READY.mixTo(READY.copy().also { it.armL = -1.65f; it.foreL = 0f; it.armLYaw = 0.1f; it.headPitch = 0.3f; it.jaw = 0.4f }, ramp(t, 0f, 8f))
            MinotaurEntity.ABSORB -> READY.mixTo(ABSORB, ramp(t, 0f, 20f)).also { it.jaw = ABSORB.jaw * ramp(t, 10f, 30f) }
            MinotaurEntity.SNORT -> CROUCH.copy().also { it.drop = 0.15f; it.legR = 0f; it.shinR = 0f; it.legL = 0f; it.shinL = 0f; it.jaw = 0.25f; it.headPitch = 0.35f }
            MinotaurEntity.GRAB -> {
                val reach = MPose(armR = -0.3f, foreR = -0.7f, axe = 0.3f, armL = -1.55f, foreL = -0.1f, armLYaw = 0.25f, torsoPitch = 0.35f, headPitch = 0.25f, jaw = 0.3f)
                val hold = MPose(armR = -0.5f, foreR = -0.8f, axe = 0.4f, armRRoll = 0.3f, armL = -2.85f, foreL = -0.25f, armLRoll = -0.15f, torsoPitch = -0.2f, headPitch = -0.35f, jaw = 0.55f, back = -0.05f)
                val slam = MPose(armR = -0.4f, foreR = -0.6f, axe = 0.6f, armL = -0.5f, foreL = -0.2f, torsoPitch = 0.55f, headPitch = 0.35f, jaw = 0.6f, legR = -0.4f, shinR = 0.4f, drop = 0.22f)
                val hEnd = w + MinotaurEntity.GRAB_HOLD
                when {
                    t < w -> READY.mixTo(reach, ramp(t, 0f, w))
                    t < hEnd -> reach.mixTo(hold, ramp(t, w, w + 6f)).also { it.armL += sin(t * 0.8f) * 0.05f }
                    t < hEnd + 4 -> hold.mixTo(slam, ramp(t, hEnd - 1f, hEnd + 1f))
                    else -> slam.mixTo(READY, ramp(t, hEnd + 6f, hEnd + 16f))
                }
            }
            MinotaurEntity.JUMP_DOWN -> when {
                t < 8 -> READY.mixTo(CROUCH, ramp(t, 0f, 7f))
                else -> RAISED.copy().also { it.legR = -0.7f; it.shinR = 1.0f; it.legL = -0.2f; it.shinL = 0.6f }
            }
            else -> READY.copy()
        }
        val pose = p
        // walking / running on top of the action pose
        val amp = s.limbSwingAmplitude.coerceAtMost(1f)
        val ph = s.limbSwingAnimationProgress
        val running = s.action == MinotaurEntity.CHARGE
        val stride = if (running) 1.0f else 0.65f
        val freq = if (running) 0.55f else 0.45f
        val walkR = cos(ph * freq) * stride * amp
        val walkL = cos(ph * freq + PI.toFloat()) * stride * amp
        val canWalk = s.action != MinotaurEntity.THRONE && s.action != MinotaurEntity.KNEEL && s.action != MinotaurEntity.DEATH && s.action != MinotaurEntity.STUNNED
        if (canWalk) {
            pose.legR += walkR
            pose.legL += walkL
            pose.shinR += (if (walkR < 0) -walkR * 0.9f else 0f)
            pose.shinL += (if (walkL < 0) -walkL * 0.9f else 0f)
            pose.armL += -walkL * 0.25f
            pose.torsoYaw += sin(ph * freq) * 0.06f * amp
            pose.drop -= abs(sin(ph * freq)) * 0.06f * amp
        }

        // breathing
        val breath = sin(s.age * 0.07f)
        pose.torsoPitch += breath * 0.02f
        return pose
    }

    private fun apply(s: MinotaurRenderState, pose: MPose) {
        val ph = s.limbSwingAnimationProgress
        val amp = s.limbSwingAmplitude.coerceAtMost(1f)
        val running = s.action == MinotaurEntity.CHARGE
        val walkR = cos(ph * (if (running) 0.55f else 0.45f)) * (if (running) 1.0f else 0.65f) * amp
        body.pitch = 0f
        torso.pitch = pose.torsoPitch
        torso.yaw = pose.torsoYaw
        // head follows the target (limited) on top of the action
        val look = if (s.action == MinotaurEntity.THRONE || s.action == MinotaurEntity.DEATH) 0f else 1f
        head.yaw = (s.relativeHeadYaw * (PI.toFloat() / 180f)).coerceIn(-0.9f, 0.9f) * look - pose.torsoYaw * 0.6f
        head.pitch = pose.headPitch + (s.pitch * (PI.toFloat() / 180f)) * 0.5f * look
        jaw.pitch = pose.jaw
        armR.pitch = pose.armR; armR.roll = pose.armRRoll; armR.yaw = pose.armRYaw
        foreR.pitch = pose.foreR
        axe.pitch = pose.axe
        axe.visible = !s.axeOut
        armL.pitch = pose.armL; armL.roll = pose.armLRoll; armL.yaw = pose.armLYaw
        foreL.pitch = pose.foreL
        legR.pitch = pose.legR; shinR.pitch = pose.shinR
        legL.pitch = pose.legL; shinL.pitch = pose.shinL
        // cloth and tail swing behind the motion
        val sway = sin(s.age * 0.09f) * 0.05f
        cape.pitch = 0.12f + abs(walkR) * 0.25f + (if (running) 0.6f else 0f) + sway - pose.torsoPitch * 0.8f
        capeLower.pitch = 0.08f + sway * 0.7f
        loinF.pitch = minOf(pose.legR, pose.legL).coerceAtMost(0f) * 0.7f
        loinB.pitch = maxOf(pose.legR, pose.legL).coerceAtLeast(0f) * 0.7f + 0.05f
        tail.pitch = 0.25f + sin(s.age * 0.11f) * 0.08f
        tail.yaw = sin(s.age * 0.13f) * 0.25f
        tailTip.pitch = sin(s.age * 0.11f + 1f) * 0.15f
    }
}

class MinotaurRenderer(ctx: EntityRendererFactory.Context) :
    MobEntityRenderer<MinotaurEntity, MinotaurRenderState, MinotaurModel>(ctx, MinotaurModel(ctx.getPart(MinotaurModel.LAYER)), 1.2f) {

    init {
        addFeature(BossGlowFeature(this, { GLOW_LAYER }, { s ->
            val pulse = 0.82f + 0.18f * sin(s.age * 0.12f)
            val fury = 0.7f + 0.03f * s.fury
            if (s.burning) Anim.tint(pulse * 1.0f, 1f, 0.78f, 0.45f) else Anim.tint(pulse * fury)
        }))
    }

    override fun createRenderState(): MinotaurRenderState = MinotaurRenderState()

    override fun getTexture(state: MinotaurRenderState): Identifier = TEXTURE

    override fun updateRenderState(entity: MinotaurEntity, state: MinotaurRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        state.action = entity.action
        state.t = entity.actionTime(tickDelta)
        state.fury = entity.fury
        state.axeOut = entity.axeOut
        state.burning = entity.burning
        state.windup = when (entity.action) {
            MinotaurEntity.CLEAVE -> 18f
            MinotaurEntity.SWEEP -> 14f
            MinotaurEntity.GORE -> 9f
            MinotaurEntity.THROW -> 16f
            MinotaurEntity.LEAP -> 14f
            MinotaurEntity.GRAB -> MinotaurEntity.GRAB_WIND.toFloat()
            MinotaurEntity.QUAKE -> MinotaurEntity.QUAKE_WIND.toFloat()
            else -> 12f
        } * (1f - 0.035f * entity.fury) * (if (entity.burning) 0.85f else 1f)
        // the vanilla death tilt is replaced by our own DEATH pose
        state.deathTime = 0f
        val pose = model.pose(state)
        state.pose = pose
        state.drop = pose.drop
        state.tilt = pose.tilt
        state.back = pose.back
    }

    override fun setupTransforms(state: MinotaurRenderState, matrices: MatrixStack, bodyYaw: Float, baseHeight: Float) {
        super.setupTransforms(state, matrices, bodyYaw, baseHeight)
        if (state.drop != 0f) matrices.translate(0f, -state.drop, 0f)
        if (state.back != 0f) matrices.translate(0f, 0f, -state.back)
        if (state.tilt != 0f) matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(state.tilt))
    }

    override fun scale(state: MinotaurRenderState, matrices: MatrixStack) {
        matrices.scale(MinotaurEntity.RENDER_SCALE, MinotaurEntity.RENDER_SCALE, MinotaurEntity.RENDER_SCALE)
    }

    companion object {
        val TEXTURE: Identifier = HhaBlood.id("textures/entity/minotaur.png")
        private val GLOW_LAYER: RenderLayer = RenderLayers.eyes(HhaBlood.id("textures/entity/minotaur_glow.png"))
    }
}
