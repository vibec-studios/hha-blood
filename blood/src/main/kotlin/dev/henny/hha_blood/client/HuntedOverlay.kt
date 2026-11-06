package dev.henny.hha_blood.client

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import kotlin.math.exp

/**
 * Blood Tracker prey feedback: while someone hunts you, the screen edges pulse dark red
 * like a slow heartbeat (double beat every ~2 s). Faint on purpose - it should unsettle, not blind.
 */
object HuntedOverlay {

    @Volatile
    var active: Boolean = false

    private var fade = 0f

    fun register() {
        HudRenderCallback.EVENT.register { context, _ -> render(context) }
    }

    private fun render(context: DrawContext) {
        val client = MinecraftClient.getInstance()
        if (client.player == null || client.options.hudHidden) {
            fade = 0f
            return
        }
        // smooth in/out
        fade = if (active) (fade + 0.02f).coerceAtMost(1f) else (fade - 0.02f).coerceAtLeast(0f)
        if (fade <= 0f) return

        val t = (System.currentTimeMillis() % 2000L) / 2000.0
        // "lub-dub": two short peaks
        val beat = exp(-((t - 0.08) * 22).let { it * it }) + 0.6 * exp(-((t - 0.26) * 22).let { it * it })
        val strength = (0.18 + 0.5 * beat) * fade
        val w = context.scaledWindowWidth
        val h = context.scaledWindowHeight
        val bands = 14
        val bandSize = 2
        for (i in 0 until bands) {
            val falloff = 1.0 - i.toDouble() / bands
            val alpha = (strength * falloff * falloff * 200).toInt().coerceIn(0, 255)
            if (alpha <= 0) continue
            val color = (alpha shl 24) or 0x8A0010
            val o = i * bandSize
            context.fill(o, o, w - o, o + bandSize, color)                 // top
            context.fill(o, h - o - bandSize, w - o, h - o, color)         // bottom
            context.fill(o, o + bandSize, o + bandSize, h - o - bandSize, color)          // left
            context.fill(w - o - bandSize, o + bandSize, w - o, h - o - bandSize, color)  // right
        }
    }
}
