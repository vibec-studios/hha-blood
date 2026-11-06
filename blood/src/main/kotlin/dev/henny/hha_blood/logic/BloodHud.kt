package dev.henny.hha_blood.logic

import dev.henny.hha_blood.net.HudLinePayload
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * Status messages of the addon. Shown by our client in a line ABOVE HHA's cooldown icons
 * (the vanilla action bar is hidden behind them). Falls back to the action bar for clients
 * without the addon.
 */
object BloodHud {
    const val RED = 0xFF5555
    const val DARK_RED = 0xAA0000
    const val GRAY = 0xAAAAAA

    fun show(player: ServerPlayerEntity, key: String, color: Int, vararg args: Any, ticks: Int = 40) {
        val strArgs = args.map { if (it is Text) it.string else it.toString() }
        if (ServerPlayNetworking.canSend(player, HudLinePayload.ID)) {
            ServerPlayNetworking.send(player, HudLinePayload(key, strArgs, color, ticks))
        } else {
            val fmt = when (color) {
                DARK_RED -> Formatting.DARK_RED
                GRAY -> Formatting.GRAY
                else -> Formatting.RED
            }
            player.sendMessage(Text.translatable(key, *strArgs.toTypedArray()).formatted(fmt), true)
        }
    }
}
