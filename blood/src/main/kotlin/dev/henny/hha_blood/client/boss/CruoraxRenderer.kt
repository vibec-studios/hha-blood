package dev.henny.hha_blood.client.boss

import dev.henny.hha_blood.HhaBlood
import dev.henny.hha_blood.boss.CruoraxEntity
import net.minecraft.client.model.ModelData
import net.minecraft.client.model.ModelPart
import net.minecraft.client.model.ModelPartBuilder
import net.minecraft.client.model.ModelTransform
import net.minecraft.client.model.TexturedModelData
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.entity.EntityRendererFactory
import net.minecraft.client.render.entity.MobEntityRenderer
import net.minecraft.client.render.entity.feature.EyesFeatureRenderer
import net.minecraft.client.render.entity.feature.FeatureRendererContext
import net.minecraft.client.render.entity.model.EntityModel
import net.minecraft.client.render.entity.model.EntityModelLayer
import net.minecraft.client.render.entity.state.LivingEntityRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Identifier
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.RotationAxis
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class CruoraxRenderState : LivingEntityRenderState() {
    var action = CruoraxEntity.FORMING
    var actionTime = 0f
    /** 0 = wings spread (flying), 1 = folded (landed). */
    var folded = 0f
    var jaw = 0f
    var neckYaw = 0f
    var neckPitch = 0f
    var bodyPitch = 0f
    /** 0..1 while forming out of blood. */
    var formation = 1f
    var phase3 = false
}

/**
 * Cruorax, the blood wyrm (texture 256 x 128, see tools/textures/GenCruorax.java). Model space is pixels
 * (16 = 1 block before the render scale), y down, the ground at y = 24, the head points to -Z.
 */
class CruoraxModel(root: ModelPart) : EntityModel<CruoraxRenderState>(root) {
    private val body = root.getChild("body")
    private val necks = listOf(body.getChild("neck1"), body.getChild("neck1").getChild("neck2"), body.getChild("neck1").getChild("neck2").getChild("neck3"))
    private val head = necks[2].getChild("head")
    private val jaw = head.getChild("jaw")
    private val tail: List<ModelPart>
    private val wingL = body.getChild("wing_left")
    private val wingR = body.getChild("wing_right")
    private val tipL = wingL.getChild("tip")
    private val tipR = wingR.getChild("tip")
    private val legs = listOf("front_left", "front_right", "hind_left", "hind_right").map { body.getChild(it) }

    init {
        val t = ArrayList<ModelPart>()
        var p = body.getChild("tail1")
        t += p
        for (i in 2..5) { p = p.getChild("tail$i"); t += p }
        tail = t
    }

    override fun setAngles(s: CruoraxRenderState) {
        super.setAngles(s)
        val age = s.age
        val fold = s.folded
        // ---- wings: big slow beats in flight, folded along the body when landed
        val beat = sin(age * 0.22f) * 0.75f * (1 - fold)
        val glide = 0.15f * (1 - fold)
        wingL.roll = -beat - glide
        wingR.roll = beat + glide
        tipL.roll = -beat * 0.6f
        tipR.roll = beat * 0.6f
        wingL.yaw = -1.25f * fold
        wingR.yaw = 1.25f * fold
        // tips stay pointing back, membranes then lie over the back instead of sticking out sideways
        tipL.yaw = 0.2f * fold
        tipR.yaw = -0.2f * fold
        wingL.pitch = 0.25f * fold
        wingR.pitch = 0.25f * fold
        // ---- neck + head: S-curve, spread the look direction over the segments
        val ny = MathHelper.clamp(s.neckYaw, -70f, 70f) * MathHelper.RADIANS_PER_DEGREE
        val np = MathHelper.clamp(s.neckPitch, -40f, 50f) * MathHelper.RADIANS_PER_DEGREE
        val breathe = sin(age * 0.08f) * 0.03f
        necks[0].pitch = -0.45f + np * 0.25f + breathe
        necks[1].pitch = 0.12f + np * 0.25f
        necks[2].pitch = 0.18f + np * 0.25f
        head.pitch = 0.2f + np * 0.25f - breathe
        for (n in necks) n.yaw = ny * 0.28f
        head.yaw = ny * 0.16f
        jaw.pitch = 0.05f + 0.75f * s.jaw
        // ---- tail: a slow wave
        for ((i, seg) in tail.withIndex()) {
            seg.yaw = sin(age * 0.11f - i * 0.7f) * (0.12f + i * 0.02f)
            seg.pitch = if (i == 0) 0.12f - 0.1f * fold else 0.04f * (1 - fold) + sin(age * 0.07f - i) * 0.03f
        }
        // ---- legs: tucked back in flight, walking on the ground
        val tuck = 1 - fold
        val walk = s.limbSwingAnimationProgress
        val amp = min(1f, s.limbSwingAmplitude) * fold
        for ((i, leg) in legs.withIndex()) {
            val phase = if (i == 0 || i == 3) 0f else MathHelper.PI
            leg.pitch = 0.95f * tuck + sin(walk * 0.6f + phase) * 0.6f * amp
        }
    }

    companion object {
        val LAYER = EntityModelLayer(HhaBlood.id("cruorax"), "main")

        fun texturedModelData(): TexturedModelData {
            val data = ModelData()
            val root = data.root
            val spike = { b: ModelPartBuilder, x: Float, y: Float, z: Float -> b.uv(150, 40).cuboid(x - 1f, y, z, 2f, 4f, 3f) }

            val bodyB = ModelPartBuilder.create().uv(0, 0).cuboid(-8f, -6f, -14f, 16f, 12f, 28f)
            for (z in floatArrayOf(-11f, -4f, 3f, 10f)) spike(bodyB, 0f, -10f, z)
            val body = root.addChild("body", bodyB, ModelTransform.origin(0f, 6f, 0f))
            body.addChild("heart", ModelPartBuilder.create().uv(160, 40).cuboid(-3f, 3f, -11f, 6f, 4f, 6f), ModelTransform.NONE)

            fun neck() = spike(ModelPartBuilder.create().uv(88, 0).cuboid(-4f, -4f, -10f, 8f, 8f, 10f), 0f, -8f, -7f)
            val n1 = body.addChild("neck1", neck(), ModelTransform.origin(0f, -2f, -13f))
            val n2 = n1.addChild("neck2", neck(), ModelTransform.origin(0f, 0f, -9f))
            val n3 = n2.addChild("neck3", neck(), ModelTransform.origin(0f, 0f, -9f))
            val head = n3.addChild(
                "head",
                ModelPartBuilder.create().uv(0, 40).cuboid(-6f, -5f, -12f, 12f, 10f, 12f).uv(48, 40).cuboid(-4f, -2f, -22f, 8f, 5f, 10f),
                ModelTransform.origin(0f, 0f, -9f),
            )
            head.addChild("jaw", ModelPartBuilder.create().uv(84, 40).cuboid(-4f, 0f, -11f, 8f, 3f, 11f), ModelTransform.origin(0f, 3f, -11f))
            head.addChild("horn_left", ModelPartBuilder.create().uv(122, 40).cuboid(-1f, -1f, 0f, 2f, 2f, 12f), ModelTransform.of(4f, -4f, -3f, 0.45f, 0.3f, 0f))
            head.addChild("horn_right", ModelPartBuilder.create().uv(122, 40).mirrored().cuboid(-1f, -1f, 0f, 2f, 2f, 12f), ModelTransform.of(-4f, -4f, -3f, 0.45f, -0.3f, 0f))

            val tailSizes = floatArrayOf(8f, 7f, 6f, 5f, 4f)
            val tailUv = arrayOf(intArrayOf(0, 62), intArrayOf(40, 62), intArrayOf(78, 62), intArrayOf(114, 62), intArrayOf(148, 62))
            var parent = body
            for (i in 0 until 5) {
                val s = tailSizes[i]
                val b = ModelPartBuilder.create().uv(tailUv[i][0], tailUv[i][1]).cuboid(-s / 2, -s / 2, 0f, s, s, 12f)
                if (i < 4) spike(b, 0f, -s / 2 - 3f, 4f)
                if (i == 4) b.uv(180, 62).cuboid(-5f, -0.5f, 6f, 10f, 1f, 8f)
                parent = parent.addChild("tail${i + 1}", b, ModelTransform.origin(0f, if (i == 0) -1f else 0f, if (i == 0) 13f else 11.5f))
            }

            val wl = body.addChild(
                "wing_left",
                ModelPartBuilder.create().uv(124, 0).cuboid(0f, -1.5f, -1.5f, 20f, 3f, 3f).uv(0, 84).cuboid(0f, 0f, 0f, 20f, 1f, 24f),
                ModelTransform.origin(7f, -5f, -8f),
            )
            wl.addChild(
                "tip",
                ModelPartBuilder.create().uv(124, 6).cuboid(0f, -1f, -1f, 22f, 2f, 2f).uv(88, 84).cuboid(0f, 0f, 0f, 22f, 1f, 22f),
                ModelTransform.origin(20f, 0f, 0f),
            )
            val wr = body.addChild(
                "wing_right",
                ModelPartBuilder.create().uv(124, 0).mirrored().cuboid(-20f, -1.5f, -1.5f, 20f, 3f, 3f).uv(0, 84).mirrored().cuboid(-20f, 0f, 0f, 20f, 1f, 24f),
                ModelTransform.origin(-7f, -5f, -8f),
            )
            wr.addChild(
                "tip",
                ModelPartBuilder.create().uv(124, 6).mirrored().cuboid(-22f, -1f, -1f, 22f, 2f, 2f).uv(88, 84).mirrored().cuboid(-22f, 0f, 0f, 22f, 1f, 22f),
                ModelTransform.origin(-20f, 0f, 0f),
            )

            fun leg(name: String, x: Float, z: Float, hind: Boolean) {
                val upper = if (hind) ModelPartBuilder.create().uv(48, 110).cuboid(-2.5f, 0f, -2.5f, 5f, 6f, 5f)
                else ModelPartBuilder.create().uv(0, 110).cuboid(-2f, 0f, -2f, 4f, 6f, 4f)
                val lower = if (hind) ModelPartBuilder.create().uv(68, 110).cuboid(-2f, 0f, -2f, 4f, 5f, 4f)
                else ModelPartBuilder.create().uv(16, 110).cuboid(-1.5f, 0f, -1.5f, 3f, 5f, 3f)
                val foot = if (hind) ModelPartBuilder.create().uv(84, 110).cuboid(-2.5f, 0f, -5f, 5f, 2f, 7f)
                else ModelPartBuilder.create().uv(28, 110).cuboid(-2f, 0f, -4f, 4f, 2f, 6f)
                val u = body.addChild(name, if (x < 0) upper.mirrored() else upper, ModelTransform.origin(x, 5f, z))
                val l = u.addChild("lower", lower, ModelTransform.origin(0f, 6f, 0f))
                l.addChild("foot", foot, ModelTransform.origin(0f, 5f, 0f))
            }
            leg("front_left", 6f, -9f, false)
            leg("front_right", -6f, -9f, false)
            leg("hind_left", 6f, 9f, true)
            leg("hind_right", -6f, 9f, true)
            return TexturedModelData.of(data, 256, 128)
        }
    }
}

private class CruoraxGlow(ctx: FeatureRendererContext<CruoraxRenderState, CruoraxModel>) :
    EyesFeatureRenderer<CruoraxRenderState, CruoraxModel>(ctx) {
    override fun getEyesTexture(): RenderLayer = LAYER

    companion object {
        private val LAYER: RenderLayer = RenderLayers.eyes(HhaBlood.id("textures/entity/cruorax_glow.png"))
    }
}

class CruoraxRenderer(ctx: EntityRendererFactory.Context) :
    MobEntityRenderer<CruoraxEntity, CruoraxRenderState, CruoraxModel>(ctx, CruoraxModel(ctx.getPart(CruoraxModel.LAYER)), 3.0f) {

    init {
        addFeature(CruoraxGlow(this))
    }

    override fun createRenderState(): CruoraxRenderState = CruoraxRenderState()

    override fun getTexture(state: CruoraxRenderState): Identifier = TEXTURE

    override fun updateRenderState(entity: CruoraxEntity, state: CruoraxRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        state.action = entity.action
        state.actionTime = entity.actionTime(tickDelta)
        state.folded = entity.foldAmount(tickDelta)
        state.jaw = entity.jawAmount(tickDelta)
        state.neckYaw = state.relativeHeadYaw
        state.neckPitch = state.pitch * 0.6f
        state.bodyPitch = entity.bodyTilt(tickDelta)
        state.formation = entity.formation(tickDelta)
        state.phase3 = entity.isPhase3
    }

    override fun setupTransforms(state: CruoraxRenderState, matrices: MatrixStack, bodyYaw: Float, baseHeight: Float) {
        super.setupTransforms(state, matrices, bodyYaw, baseHeight)
        if (state.bodyPitch != 0f) {
            matrices.translate(0f, 2.2f, 0f)
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(state.bodyPitch))
            matrices.translate(0f, -2.2f, 0f)
        }
    }

    override fun scale(state: CruoraxRenderState, matrices: MatrixStack) {
        val s = CruoraxEntity.SCALE * max(0.05f, state.formation)
        matrices.scale(s, s, s)
    }

    companion object {
        val TEXTURE: Identifier = HhaBlood.id("textures/entity/cruorax.png")
    }
}
