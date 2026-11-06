package dev.henny.hha_blood.client

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient

/**
 * Short full-screen colour flash (the red flash when the Nihil thorn impales someone): starts at
 * [strength] and fades out quickly. Scaled down by the "Distortion Effects" option for sensitive players.
 */
object ScreenFlash {
    private var color = 0xFF1A1A
    private var strength = 0f
    private var startMs = 0L
    private var durationMs = 1L

    fun flash(newColor: Int, newStrength: Float, ticks: Int) {
        val now = System.currentTimeMillis()
        if (newStrength >= current(now)) {
            color = newColor and 0xFFFFFF
            strength = newStrength.coerceIn(0f, 1f)
            startMs = now
            durationMs = (ticks * 50L).coerceAtLeast(50L)
        }
    }

    fun clear() {
        strength = 0f
    }

    private fun current(now: Long): Float {
        val k = (now - startMs) / durationMs.toFloat()
        if (k >= 1f || strength <= 0f) return 0f
        // a hard peak, then a quick fade
        return strength * (1 - k) * (1 - k) * (1 - k)
    }

    fun register() {
        HudRenderCallback.EVENT.register { context, _ ->
            val client = MinecraftClient.getInstance()
            if (client.player == null) return@register
            val s = current(System.currentTimeMillis())
            if (s <= 0.003f) return@register
            val scale = 0.35f + 0.65f * client.options.distortionEffectScale.value.toFloat()
            val a = (s * scale * 200).toInt().coerceIn(0, 200)
            context.fill(0, 0, context.scaledWindowWidth, context.scaledWindowHeight, (a shl 24) or color)
        }
    }
}
