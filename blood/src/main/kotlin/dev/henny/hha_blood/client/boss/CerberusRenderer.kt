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
}

/** Pose of the hound: index 0 = bleed head (right), 1 = flame head (middle), 2 = howl head (left). */
internal class CPose(
    val neckPitch: FloatArray = FloatArray(3), val neckYaw: FloatArray = FloatArray(3),
    val headPitch: FloatArray = FloatArray(3), val jaw: FloatArray = floatArrayOf(0.1f, 0.1f, 0.1f),
    /** front right, front left, back right, back left (upper, lower) */
    val leg: FloatArray = FloatArray(4), val lower: FloatArray = FloatArray(4),
    var bodyPitch: Float = 0f, var drop: Float = 0f, var roll: Float = 0f, var tailPitch: Float = 0f, var tailYaw: Float = 0f,
) {
    fun copy() = CPose(neckPitch.copyOf(), neckYaw.copyOf(), headPitch.copyOf(), jaw.copyOf(), leg.copyOf(), lower.copyOf(), bodyPitch, drop, roll, tailPitch, tailYaw)

    fun mixTo(o: CPose, t: Float): CPose {
        fun m(a: FloatArray, b: FloatArray) = FloatArray(a.size) { mix(a[it], b[it], t) }
        return CPose(m(neckPitch, o.neckPitch), m(neckYaw, o.neckYaw), m(headPitch, o.headPitch), m(jaw, o.jaw), m(leg, o.leg), m(lower, o.lower),
            mix(bodyPitch, o.bodyPitch, t), mix(drop, o.drop, t), mix(roll, o.roll, t), mix(tailPitch, o.tailPitch, t), mix(tailYaw, o.tailYaw, t))
    }
}

/**
 * Blood Cerberus model (tools/textures/GenCerberus.java -> CerberusModelData). Three instances are used: the skin pass
 * (broken necks hidden), the glow pass (broken necks hidden, so their eyes go dark) and the broken pass (only the broken
 * necks, drawn with the broken skin).
 */
class CerberusModel(root: ModelPart, private val pass: Pass = Pass.SKIN) : EntityModel<CerberusRenderState>(root) {
    enum class Pass { SKIN, GLOW, BROKEN }

    private val body = root.getChild("body")
    private val bodyKids = CerberusModelData.BODY_CHILDREN.map { body.getChild(it) }
    private val names = arrayOf("bleed", "flame", "howl")
    private val necks = names.map { body.getChild("neck_$it") }
    private val heads = names.mapIndexed { i, n -> necks[i].getChild("head_$n") }
    private val jaws = names.mapIndexed { i, n -> heads[i].getChild("jaw_$n") }
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

        // poses are checked against the floor by the preview in GenCerberus (pose_*.png) - keep the numbers in sync
        private val IDLE = CPose(neckYaw = floatArrayOf(0.08f, 0f, -0.08f), jaw = floatArrayOf(0.12f, 0.1f, 0.16f), tailPitch = -0.1f)
        private val SLEEP = CPose(
            neckPitch = floatArrayOf(0.95f, 1.0f, 0.95f), neckYaw = floatArrayOf(0.4f, 0f, -0.4f), headPitch = floatArrayOf(0.2f, 0.15f, 0.2f),
            jaw = floatArrayOf(0f, 0f, 0f), leg = floatArrayOf(0.6f, 0.6f, -1.3f, -1.3f), lower = floatArrayOf(-2.1f, -2.1f, 2.9f, 2.9f),
            bodyPitch = 0.1f, drop = 0.85f, tailPitch = -0.5f, tailYaw = 0.9f,
        )
        private val CROUCH = CPose(
            neckPitch = floatArrayOf(0.3f, 0.3f, 0.3f), neckYaw = floatArrayOf(0.15f, 0f, -0.15f), headPitch = floatArrayOf(0.1f, 0.1f, 0.1f),
            jaw = floatArrayOf(0.4f, 0.45f, 0.45f), leg = floatArrayOf(-0.35f, -0.35f, -0.3f, -0.3f), lower = floatArrayOf(0.7f, 0.7f, 0.5f, 0.5f),
            drop = 0.3f, bodyPitch = 0.08f, tailPitch = 0.3f,
        )
        private val HOWL_UP = CPose(
            neckPitch = floatArrayOf(-0.45f, -0.5f, -0.9f), neckYaw = floatArrayOf(0.3f, 0f, -0.3f), headPitch = floatArrayOf(-0.3f, -0.35f, -0.65f),
            jaw = floatArrayOf(0.5f, 0.55f, 0.95f), bodyPitch = -0.12f, tailPitch = 0.4f,
        )
        private val LYING = CPose(
            neckPitch = floatArrayOf(0.75f, 0.85f, 0.4f), neckYaw = floatArrayOf(0.45f, 0.1f, 0.55f), headPitch = floatArrayOf(0.4f, 0.3f, 0.3f),
            jaw = floatArrayOf(0.35f, 0.5f, 0.3f), leg = floatArrayOf(-0.3f, -0.1f, 0.4f, 0.2f), lower = floatArrayOf(0.2f, 0.1f, 0.2f, 0.1f),
            drop = 0f, roll = 85f, tailPitch = 0.6f, tailYaw = 0.3f,
        )
    }

    override fun setAngles(s: CerberusRenderState) {
        super.setAngles(s)
        val p = s.pose as? CPose ?: pose(s)
        body.pitch = p.bodyPitch
        for (i in 0..2) {
            necks[i].pitch = p.neckPitch[i]
            necks[i].yaw = p.neckYaw[i]
            heads[i].pitch = p.headPitch[i]
            jaws[i].pitch = p.jaw[i]
        }
        for (i in 0..3) {
            legs[i].pitch = p.leg[i]
            lowers[i].pitch = p.lower[i]
        }
        tail.pitch = p.tailPitch
        tail.yaw = p.tailYaw
        tail2.pitch = p.tailPitch * 0.4f + sin(s.age * 0.15f) * 0.1f
        tail3.yaw = p.tailYaw * 0.5f + sin(s.age * 0.2f) * 0.15f
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
        val w = if (s.unleashed) 0.75f else 1f
        fun snap(base: CPose, head: Int, start: Float): CPose {
            val c = base.copy()
            val up = ramp(t, start - 6f, start - 1f) * (1f - ramp(t, start, start + 2f))
            val strike = ramp(t, start - 1f, start + 1f) * (1f - ramp(t, start + 4f, start + 9f))
            c.neckPitch[head] += -0.35f * up + 0.55f * strike
            c.headPitch[head] += 0.25f * strike
            c.jaw[head] = 0.1f + 0.75f * up + 0.1f * strike
            return c
        }
        val p: CPose = when (s.action) {
            CerberusEntity.SLEEP -> SLEEP.copy().also { val b = sin(s.age * 0.05f) * 0.03f; for (i in 0..2) it.headPitch[i] += b }
            CerberusEntity.WAKE -> SLEEP.mixTo(IDLE, ramp(t, 0f, 30f)).also { for (i in 0..2) it.jaw[i] = 0.15f + 0.35f * ramp(t, 10f, 25f) }
            CerberusEntity.GROWL -> IDLE.mixTo(CROUCH, ramp(t, 0f, 8f)).also { for (i in 0..2) it.jaw[i] = 0.35f + 0.3f * sin(t * 0.9f + i * 2.1f) }
            CerberusEntity.BITE -> snap(IDLE, 0, 10f * w + 1f)
            CerberusEntity.SNAP3 -> {
                val step = 8f * w
                snap(snap(snap(IDLE, 0, step), 1, step * 2), 2, step * 3)
            }
            CerberusEntity.BREATH -> {
                val wind = 16f * w
                val c = IDLE.copy()
                val rear = ramp(t, 0f, wind - 2f) * (1f - ramp(t, wind - 2f, wind + 2f))
                val breathe = ramp(t, wind - 2f, wind + 2f) * (1f - ramp(t, wind + 48f, wind + 56f))
                c.neckPitch[1] += -0.5f * rear + 0.15f * breathe
                c.headPitch[1] += -0.3f * rear + 0.12f * breathe
                c.jaw[1] = 0.1f + 0.3f * rear + 0.85f * breathe + sin(t * 1.7f) * 0.04f * breathe
                c.neckYaw[0] += 0.3f * breathe; c.neckYaw[2] -= 0.3f * breathe
                c
            }
            CerberusEntity.TRIPLE_BREATH -> {
                val wind = 18f * w
                val c = IDLE.copy()
                val rear = ramp(t, 0f, wind) * (1f - ramp(t, wind, wind + 3f))
                val breathe = ramp(t, wind, wind + 3f) * (1f - ramp(t, wind + 52f, wind + 60f))
                val sweep = mix(-0.5f, 0.5f, ramp(t, wind, wind + 52f)) * breathe
                for (i in 0..2) {
                    c.neckPitch[i] += -0.45f * rear + 0.1f * breathe
                    c.jaw[i] = 0.1f + 0.35f * rear + 0.85f * breathe
                    c.neckYaw[i] += sweep + (1 - i) * 0.25f * breathe
                }
                c
            }
            CerberusEntity.HOWL, CerberusEntity.GEYSERS -> {
                val wind = if (s.action == CerberusEntity.HOWL) 22f * w else 16f
                IDLE.mixTo(HOWL_UP, ramp(t, 0f, wind)).mixTo(IDLE, ramp(t, wind + 18f, wind + 26f))
            }
            CerberusEntity.POUNCE -> {
                val wind = 12f * w
                when {
                    t < wind -> IDLE.mixTo(CROUCH, ramp(t, 0f, wind))
                    t < wind + 22f -> leap()
                    else -> CROUCH.mixTo(IDLE, ramp(t, wind + 22f, wind + 34f))
                }
            }
            CerberusEntity.CHARGE -> {
                val wind = 18f * w
                if (t < wind) CROUCH.copy().also { it.leg[0] += sin(t * 0.8f) * 0.3f } else IDLE.copy().also {
                    for (i in 0..2) { it.neckPitch[i] = 0.3f; it.jaw[i] = 0.6f }
                    it.tailPitch = -0.5f
                }
            }
            CerberusEntity.NOVA -> when {
                t < 20f -> IDLE.mixTo(IDLE.copy().also { it.bodyPitch = -0.55f; it.leg[0] = -0.9f; it.leg[1] = -0.7f; it.leg[2] = 0.55f; it.leg[3] = 0.55f; for (i in 0..2) it.jaw[i] = 0.7f }, ramp(t, 0f, 16f))
                t < 36f -> CROUCH.mixTo(IDLE.copy().also { it.bodyPitch = -0.4f; it.leg[0] = -0.7f; it.leg[1] = -0.6f; it.leg[2] = 0.4f; it.leg[3] = 0.4f }, ramp(t, 22f, 33f))
                else -> CROUCH.mixTo(IDLE, ramp(t, 38f, 50f))
            }
            CerberusEntity.ORBS -> {
                val step = CerberusEntity.ORB_STEP * w
                var c = IDLE
                for (k in 0 until (if (s.unleashed) 6 else 3)) c = snap(c, k % 3, step * (k + 1))
                c
            }
            CerberusEntity.FIRE_RING -> {
                val rw = CerberusEntity.RING_WIND.toFloat()
                val leap = CerberusEntity.RING_LEAP.toFloat()
                when {
                    t < rw -> IDLE.mixTo(HOWL_UP, ramp(t, 0f, rw))
                    t < leap -> HOWL_UP.mixTo(CROUCH, ramp(t, rw + 4f, leap))
                    t < leap + 18f -> leap()
                    else -> CROUCH.mixTo(IDLE, ramp(t, leap + 18f, CerberusEntity.RING_END.toFloat()))
                }
            }
            CerberusEntity.COLLAPSE -> IDLE.mixTo(LYING, ramp(t, 0f, 14f)).also { val b = sin(s.age * 0.3f) * 0.04f; it.neckPitch[1] += b }
            CerberusEntity.RISE -> LYING.mixTo(HOWL_UP, ramp(t, 0f, 22f)).mixTo(IDLE, ramp(t, 40f, 60f))
            CerberusEntity.DEATH -> IDLE.mixTo(LYING, ramp(t, 0f, 26f)).also { for (i in 0..2) { it.neckPitch[i] += 0.25f * ramp(t, 20f, 50f); it.jaw[i] = 0.5f } }
            else -> IDLE.copy().also { for (i in 0..2) { it.jaw[i] = 0.12f + 0.1f * max(0f, sin(s.age * 0.12f + i * 2f)); it.headPitch[i] += sin(s.age * 0.08f + i * 1.7f) * 0.06f } }
        }
        // gait: diagonal pairs (front right + back left, front left + back right)
        val lying = s.action == CerberusEntity.SLEEP || s.action == CerberusEntity.COLLAPSE || s.action == CerberusEntity.DEATH || s.action == CerberusEntity.WAKE
        if (!lying) {
            val amp = s.limbSwingAmplitude.coerceAtMost(1.2f)
            val ph = s.limbSwingAnimationProgress * 0.55f
            val a = cos(ph) * 0.75f * amp
            val b = cos(ph + PI.toFloat()) * 0.75f * amp
            p.leg[0] += a; p.leg[3] += a; p.leg[1] += b; p.leg[2] += b
            p.lower[0] += max(0f, sin(ph)) * 0.6f * amp; p.lower[3] += max(0f, sin(ph)) * 0.6f * amp
            p.lower[1] += max(0f, -sin(ph)) * 0.6f * amp; p.lower[2] += max(0f, -sin(ph)) * 0.6f * amp
            for (i in 0..2) p.neckPitch[i] += sin(ph * 2f + i) * 0.05f * amp
            p.drop -= kotlin.math.abs(sin(ph)) * 0.05f * amp
            // the heads look towards the target
            val look = (s.relativeHeadYaw * (PI.toFloat() / 180f)).coerceIn(-0.7f, 0.7f)
            for (i in 0..2) p.neckYaw[i] += look * 0.8f
            for (i in 0..2) p.headPitch[i] += (s.pitch * (PI.toFloat() / 180f)) * 0.4f
        }
        // broken heads hang limp: neck down and outwards, jaw slack, swaying with the steps and a slow pendulum
        for (i in 0..2) {
            val k = s.limp[i]
            if (k <= 0f) continue
            val sway = sin(s.age * 0.06f + i * 2.3f) * 0.07f + sin(s.limbSwingAnimationProgress * 0.55f * 2f) * 0.06f * s.limbSwingAmplitude.coerceAtMost(1f)
            val out = when (i) { 0 -> 0.35f; 2 -> -0.35f; else -> 0f }
            p.neckPitch[i] = mix(p.neckPitch[i], 1.0f + sway * 0.5f, k)
            p.neckYaw[i] = mix(p.neckYaw[i], out + sway, k)
            p.headPitch[i] = mix(p.headPitch[i], 0.5f, k)
            p.jaw[i] = mix(p.jaw[i], 0.6f + sin(s.age * 0.09f + i) * 0.04f, k)
        }
        p.bodyPitch += sin(s.age * 0.06f) * 0.015f
        p.tailYaw += sin(s.age * 0.1f) * 0.25f
        return p
    }

    private fun leap() = CPose(
        neckPitch = floatArrayOf(-0.2f, -0.25f, -0.2f), neckYaw = floatArrayOf(0.2f, 0f, -0.2f), jaw = floatArrayOf(0.8f, 0.85f, 0.85f),
        leg = floatArrayOf(-1.15f, -1.15f, 1.0f, 1.0f), lower = floatArrayOf(0.2f, 0.2f, -0.2f, -0.2f), bodyPitch = -0.1f, tailPitch = -0.3f,
    )
}

class CerberusRenderer(ctx: EntityRendererFactory.Context) :
    MobEntityRenderer<CerberusEntity, CerberusRenderState, CerberusModel>(ctx, CerberusModel(ctx.getPart(CerberusModel.LAYER)), 1.6f) {

    /** client-side easing of the limp heads per entity */
    private val limpOf = WeakHashMap<CerberusEntity, FloatArray>()

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
        state.action = entity.action
        state.t = entity.actionTime(tickDelta)
        state.unleashed = entity.unleashed
        state.deathTime = 0f
        state.broken = entity.brokenMask
        // ease the limp heads (about half a second to collapse, a little slower to lift a re-ignited head)
        val limp = limpOf.getOrPut(entity) { FloatArray(4) { if (it < 3 && entity.isHeadBroken(it)) 1f else 0f } }
        val now = entity.age + tickDelta
        val dt = (now - limp[3]).coerceIn(0f, 5f)
        limp[3] = now
        for (i in 0..2) {
            val target = if (entity.isHeadBroken(i)) 1f else 0f
            limp[i] = if (target > limp[i]) (limp[i] + dt * 0.1f).coerceAtMost(1f) else (limp[i] - dt * 0.06f).coerceAtLeast(0f)
            state.limp[i] = ramp(limp[i], 0f, 1f)
        }
        val pose = model.pose(state)
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
