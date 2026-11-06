package dev.henny.hha_blood.client

import dev.henny.hha_blood.net.HudLinePayload
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

/**
 * The addon's status line (Blood Tracker distance/arrow, cooldown messages, "no enemies" ...),
 * drawn ABOVE HHA's cooldown icons. The vanilla action bar is at height - 68, exactly behind
 * those icons, which is why it could not be read.
 */
object HudLineOverlay {

    private var text: Text? = null
    private var color = 0xFF5555
    private var until = 0L
    private var shownAt = 0L

    fun show(p: HudLinePayload) {
        text = Text.translatable(p.key, *p.args.toTypedArray())
        color = p.color
        val now = System.currentTimeMillis()
        if (now > until) shownAt = now
        until = now + p.ticks * 50L
    }

    fun clear() {
        text = null
    }

    fun register() {
        HudRenderCallback.EVENT.register { context, _ -> render(context) }
    }

    private fun render(context: DrawContext) {
        val t = text ?: return
        val client = MinecraftClient.getInstance()
        if (client.player == null || client.options.hudHidden) return
        val now = System.currentTimeMillis()
        val left = until - now
        if (left <= 0) {
            text = null
            return
        }
        // fade in over 150 ms, fade out over the last 500 ms
        val alpha = (minOf(1.0, (now - shownAt) / 150.0) * minOf(1.0, left / 500.0)).coerceIn(0.0, 1.0)
        val a = (alpha * 255).toInt().coerceAtLeast(8)
        val tr = client.textRenderer
        val w = tr.getWidth(t)
        val x = context.scaledWindowWidth / 2 - w / 2
        val y = context.scaledWindowHeight - 98   // HHA icons start at height - 84
        context.fill(x - 3, y - 2, x + w + 3, y + 10, ((a * 0.45).toInt() shl 24))
        context.drawTextWithShadow(tr, t, x, y, (a shl 24) or (color and 0xFFFFFF))
    }
}
