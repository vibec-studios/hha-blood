package dev.henny.hha_blood.client.boss

import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.feature.FeatureRenderer
import net.minecraft.client.render.entity.feature.FeatureRendererContext
import net.minecraft.client.render.entity.model.EntityModel
import net.minecraft.client.render.entity.state.LivingEntityRenderState
import net.minecraft.client.util.math.MatrixStack

/** Glow mask over a boss model: full bright (eyes render layer), texture and tint chosen per frame. */
class BossGlowFeature<S : LivingEntityRenderState, M : EntityModel<S>>(
    context: FeatureRendererContext<S, M>,
    private val layer: (S) -> RenderLayer,
    private val tint: (S) -> Int,
) : FeatureRenderer<S, M>(context) {
    override fun render(matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int, state: S, limbAngle: Float, limbDistance: Float) {
        if (state.invisible) return
        queue.getBatchingQueue(1).submitModel(contextModel, state, matrices, layer(state), 0xF000F0, OverlayTexture.DEFAULT_UV, tint(state), null, state.outlineColor, null)
    }
}

/** Small animation helpers shared by the boss models. */
object Anim {
    fun clamp01(x: Float): Float = x.coerceIn(0f, 1f)

    /** Smoothstep 0..1 of [t] between [a] and [b]. */
    fun ramp(t: Float, a: Float, b: Float): Float {
        if (b <= a) return if (t >= b) 1f else 0f
        val x = clamp01((t - a) / (b - a))
        return x * x * (3f - 2f * x)
    }

    fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** ARGB grey tint with brightness [v] (0..1) and a colour multiplier. */
    fun tint(v: Float, r: Float = 1f, g: Float = 1f, b: Float = 1f): Int {
        val k = clamp01(v)
        val rr = (255 * k * r).toInt().coerceIn(0, 255)
        val gg = (255 * k * g).toInt().coerceIn(0, 255)
        val bb = (255 * k * b).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }
}
