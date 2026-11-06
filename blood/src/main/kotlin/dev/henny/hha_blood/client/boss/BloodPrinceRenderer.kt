package dev.henny.hha_blood.client.boss

import dev.henny.hha_blood.HhaBlood
import dev.henny.hha_blood.boss.BloodPrinceEntity
import net.minecraft.client.model.Dilation
import net.minecraft.client.model.ModelPart
import net.minecraft.client.model.ModelPartBuilder
import net.minecraft.client.model.ModelTransform
import net.minecraft.client.model.TexturedModelData
import net.minecraft.client.render.Frustum
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.entity.BipedEntityRenderer
import net.minecraft.client.render.entity.EntityRendererFactory
import net.minecraft.client.render.entity.feature.EyesFeatureRenderer
import net.minecraft.client.render.entity.feature.FeatureRendererContext
import net.minecraft.client.render.entity.model.BipedEntityModel
import net.minecraft.client.render.entity.model.EntityModelLayer
import net.minecraft.client.render.entity.state.BipedEntityRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Identifier
import net.minecraft.util.math.MathHelper
import kotlin.math.min
import kotlin.math.sin

/** Render state of the Blood Prince: the synced action + how long it has been running. */
class BloodPrinceRenderState : BipedEntityRenderState() {
    var action = BloodPrinceEntity.THRONE
    var actionTime = 0f
}

/**
 * Poses for the Prince's actions. Applied to the body model AND to the armour models, so the Blood
 * armour sleeves/legs always move together with the limbs.
 */
object PrincePose {
    private fun ease(t: Float) = t * t * (3 - 2 * t)

    fun apply(m: BipedEntityModel<BloodPrinceRenderState>, s: BloodPrinceRenderState) {
        val t = s.actionTime
        when (s.action) {
            BloodPrinceEntity.THRONE -> {
                // seated, one hand resting on the knee, sword hand on the armrest, head bowed
                m.head.pitch = 0.35f
                m.rightArm.pitch = -0.55f; m.rightArm.yaw = 0.1f
                m.leftArm.pitch = -0.75f; m.leftArm.yaw = -0.15f
            }
            BloodPrinceEntity.AWAKEN -> {
                if (t < 22) {
                    val k = ease(min(1f, t / 22f))
                    m.head.pitch = 0.35f - 0.75f * k
                    m.rightArm.pitch = -0.55f - 0.4f * k; m.leftArm.pitch = -0.75f - 0.2f * k
                } else {
                    // stands, spreads the arms and roars
                    val k = ease(min(1f, (t - 22) / 16f))
                    m.head.pitch = -0.45f * k
                    m.rightArm.pitch = -0.6f * k; m.rightArm.roll = 1.0f * k
                    m.leftArm.pitch = -0.6f * k; m.leftArm.roll = -1.0f * k
                }
            }
            BloodPrinceEntity.SLASH -> {
                if (t < 6) {
                    val k = ease(t / 6f)
                    m.rightArm.pitch = -2.7f * k; m.rightArm.yaw = -0.4f * k
                    m.body.yaw = -0.25f * k
                }
            }
            BloodPrinceEntity.LUNGE -> {
                if (t < 12) {
                    val k = ease(t / 12f)
                    m.body.pitch = 0.35f * k
                    m.head.pitch = -0.2f * k
                    m.rightArm.pitch = 0.9f * k; m.leftArm.pitch = 0.7f * k
                    m.rightLeg.pitch = -0.5f * k; m.leftLeg.pitch = 0.4f * k
                } else if (t < 24) {
                    m.body.pitch = 0.45f
                    m.rightArm.pitch = -1.6f; m.rightArm.yaw = -0.3f
                    m.leftArm.pitch = 0.9f
                    m.rightLeg.pitch = -0.8f; m.leftLeg.pitch = 0.7f
                }
            }
            BloodPrinceEntity.SAWS -> {
                val k = if (t < 13) ease(t / 13f) else 1f - ease(min(1f, (t - 13) / 8f))
                m.rightArm.pitch = if (t < 13) 0.9f * k else -1.5f; m.rightArm.yaw = if (t < 13) 0.6f * k else -0.3f
                m.leftArm.pitch = if (t < 13) 0.9f * k else -1.5f; m.leftArm.yaw = if (t < 13) -0.6f * k else 0.3f
            }
            BloodPrinceEntity.DIVE -> {
                m.rightArm.pitch = -0.4f; m.rightArm.roll = 0.5f
                m.leftArm.pitch = -0.4f; m.leftArm.roll = -0.5f
                m.head.pitch = 0.5f
            }
            BloodPrinceEntity.EMERGE -> {
                val k = ease(min(1f, t / 10f))
                m.rightArm.pitch = -3.0f * k
                m.leftArm.pitch = -0.8f * k; m.leftArm.roll = -0.6f * k
                m.head.pitch = -0.4f * k
            }
            BloodPrinceEntity.CYCLONE -> {
                val k = if (t < 20) ease(t / 20f) else 1f
                m.rightArm.pitch = -3.1f * k; m.rightArm.roll = 0.15f * k
                m.leftArm.pitch = -1.2f * k; m.leftArm.roll = -0.7f * k
                m.head.pitch = -0.6f * k
            }
            BloodPrinceEntity.TRANSFORM -> {
                // kneels, arms spread, then thrown back screaming as the blood lifts him
                val k = ease(min(1f, t / 50f))
                m.rightLeg.pitch = -1.3f * (1 - k) ; m.leftLeg.pitch = 0.2f * (1 - k)
                m.rightArm.pitch = -0.4f - 1.8f * k; m.rightArm.roll = 0.9f + 0.5f * k
                m.leftArm.pitch = -0.4f - 1.8f * k; m.leftArm.roll = -0.9f - 0.5f * k
                m.head.pitch = 0.6f - 1.4f * k
                m.body.pitch = 0.3f - 0.5f * k
            }
            BloodPrinceEntity.NIHIL -> {
                val k = ease(min(1f, t / 10f))
                val tremble = sin(t * 2.3f) * 0.05f
                m.rightArm.pitch = -2.6f * k + tremble; m.rightArm.roll = 0.6f * k
                m.leftArm.pitch = -2.6f * k - tremble; m.leftArm.roll = -0.6f * k
                m.head.pitch = -0.7f * k
            }
        }
    }
}

/** Body model: biped + crown, cape and shoulder spikes (texture 128 x 64). */
class BloodPrinceModel(root: ModelPart) : BipedEntityModel<BloodPrinceRenderState>(root) {
    private val cape: ModelPart = body.getChild("cape")

    override fun setAngles(state: BloodPrinceRenderState) {
        super.setAngles(state)
        PrincePose.apply(this, state)
        // the cape trails behind when walking and flutters a little
        val swing = MathHelper.clamp(state.limbSwingAmplitude, 0f, 1f)
        cape.pitch = 0.08f + swing * 0.55f + sin(state.age * 0.09f) * 0.04f
        if (state.action == BloodPrinceEntity.THRONE) cape.pitch = 0.0f
        if (state.action == BloodPrinceEntity.LUNGE && state.actionTime >= 12) cape.pitch = 1.1f
    }

    companion object {
        val LAYER = EntityModelLayer(HhaBlood.id("blood_prince"), "main")

        fun texturedModelData(): TexturedModelData {
            val data = getModelData(Dilation.NONE, 0f)
            val root = data.root
            val head = root.getChild("head")
            head.addChild(
                "crown",
                ModelPartBuilder.create()
                    .uv(64, 0).cuboid(-5f, -11f, -5f, 10f, 3f, 1f)
                    .uv(64, 4).cuboid(-5f, -11f, 4f, 10f, 3f, 1f)
                    .uv(64, 8).cuboid(4f, -11f, -4f, 1f, 3f, 8f)
                    .uv(84, 8).cuboid(-5f, -11f, -4f, 1f, 3f, 8f)
                    .uv(104, 0).cuboid(-4.5f, -14f, -5f, 1f, 4f, 1f)
                    .uv(108, 0).cuboid(3.5f, -14f, -5f, 1f, 4f, 1f)
                    .uv(112, 0).cuboid(-2.5f, -14f, 4f, 1f, 4f, 1f)
                    .uv(116, 0).cuboid(1.5f, -14f, 4f, 1f, 4f, 1f)
                    .uv(120, 0).cuboid(-0.5f, -15f, -5f, 1f, 5f, 1f),
                ModelTransform.NONE,
            )
            root.getChild("body").addChild(
                "cape",
                ModelPartBuilder.create().uv(64, 24).cuboid(-6f, 0f, 0f, 12f, 22f, 1f),
                ModelTransform.origin(0f, 0f, 3.1f),
            )
            root.getChild("right_arm").addChild(
                "spike", ModelPartBuilder.create().uv(96, 24).cuboid(-3.5f, -7f, -1f, 2f, 4f, 2f), ModelTransform.NONE,
            )
            root.getChild("left_arm").addChild(
                "spike", ModelPartBuilder.create().uv(104, 24).cuboid(1.5f, -7f, -1f, 2f, 4f, 2f), ModelTransform.NONE,
            )
            return TexturedModelData.of(data, 128, 64)
        }
    }
}


private class PrinceEyes(ctx: FeatureRendererContext<BloodPrinceRenderState, BloodPrinceModel>) :
    EyesFeatureRenderer<BloodPrinceRenderState, BloodPrinceModel>(ctx) {
    override fun getEyesTexture(): RenderLayer = LAYER

    companion object {
        private val LAYER: RenderLayer = RenderLayers.eyes(HhaBlood.id("textures/entity/blood_prince_eyes.png"))
    }
}

class BloodPrinceRenderer(ctx: EntityRendererFactory.Context) :
    BipedEntityRenderer<BloodPrinceEntity, BloodPrinceRenderState, BloodPrinceModel>(ctx, BloodPrinceModel(ctx.getPart(BloodPrinceModel.LAYER)), 0.9f) {

    init {
        // no armour layer: he wears his own robe, crown and cape (Mats: the armour looked odd on him)
        addFeature(PrinceEyes(this))
    }

    override fun createRenderState(): BloodPrinceRenderState = BloodPrinceRenderState()

    override fun getTexture(state: BloodPrinceRenderState): Identifier = TEXTURE

    override fun updateRenderState(entity: BloodPrinceEntity, state: BloodPrinceRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        state.action = entity.action
        state.actionTime = entity.actionTime(tickDelta)
        state.rightArmPose = BipedEntityModel.ArmPose.ITEM
        if (state.action == BloodPrinceEntity.THRONE || (state.action == BloodPrinceEntity.AWAKEN && state.actionTime < 22)) state.hasVehicle = true
        if (state.action == BloodPrinceEntity.DIVE) state.isInSneakingPose = true
    }

    override fun scale(state: BloodPrinceRenderState, matrices: MatrixStack) {
        matrices.scale(BloodPrinceEntity.SCALE, BloodPrinceEntity.SCALE, BloodPrinceEntity.SCALE)
    }

    override fun shouldRender(entity: BloodPrinceEntity, frustum: Frustum, x: Double, y: Double, z: Double): Boolean =
        !entity.hiddenForRender && super.shouldRender(entity, frustum, x, y, z)

    companion object {
        val TEXTURE: Identifier = HhaBlood.id("textures/entity/blood_prince.png")
    }
}
