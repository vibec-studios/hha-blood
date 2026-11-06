package dev.henny.hha_blood.client

import net.minecraft.client.MinecraftClient
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.math.RotationAxis
import kotlin.math.sin

/**
 * Camera shake for heavy impacts (the Nihil blood thorn). Applied on top of the vanilla hurt tilt
 * (GameRendererMixin), scaled by the player's "Distortion Effects" accessibility option.
 */
object CameraShake {
    private var strength = 0f
    private var startMs = 0L
    private var durationMs = 1L

    fun shake(newStrength: Float, ticks: Int) {
        val now = System.currentTimeMillis()
        // keep the stronger of the running and the new shake
        if (newStrength >= current(now)) {
            strength = newStrength.coerceAtMost(6f)
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
        return strength * (1 - k) * (1 - k)
    }

    @JvmStatic
    fun apply(matrices: MatrixStack) {
        val now = System.currentTimeMillis()
        val s = current(now)
        if (s <= 0.001f) return
        val scale = MinecraftClient.getInstance().options.distortionEffectScale.value.toFloat()
        if (scale <= 0f) return
        val t = now / 1000.0
        val amp = s * scale
        // two out-of-phase wobbles: fast roll + slightly slower pitch/yaw jitter
        val roll = (sin(t * 47.0) * 0.6 + sin(t * 83.0) * 0.4).toFloat() * amp
        val pitch = (sin(t * 61.0 + 1.3) * 0.6 + sin(t * 97.0) * 0.4).toFloat() * amp * 0.5f
        val yaw = (sin(t * 53.0 + 2.1)).toFloat() * amp * 0.35f
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(roll))
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch))
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw))
    }
}
