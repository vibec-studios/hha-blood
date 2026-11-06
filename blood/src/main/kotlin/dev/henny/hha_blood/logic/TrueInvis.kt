package dev.henny.hha_blood.logic

import com.mojang.datafixers.util.Pair
import dev.henny.hha_blood.net.TrueInvisPayload
import net.fabricmc.fabric.api.networking.v1.PlayerLookup
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.item.ItemStack
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import java.util.UUID

/**
 * "True" invisibility for Blood Trail: on top of the vanilla Invisibility effect every other player
 * is told that you wear and hold nothing, so armour and items in your hands vanish too.
 * The packets are only faked for the others - your own inventory is untouched. When it ends the
 * real equipment is sent again.
 *
 * Clients with the addon also get a [TrueInvisPayload] so they stop drawing sprint particles.
 */
object TrueInvis {

    private val SLOTS = listOf(
        EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
    )

    private class Entry(var ticksLeft: Int, var age: Int = 0)

    private val active = HashMap<UUID, Entry>()

    fun start(player: ServerPlayerEntity, ticks: Int) {
        if (ticks <= 0) return
        val existing = active[player.uuid]
        if (existing != null) {
            existing.ticksLeft = maxOf(existing.ticksLeft, ticks)
        } else {
            active[player.uuid] = Entry(ticks)
        }
        hide(player)
        broadcastFlag(player, true)
    }

    @JvmStatic
    fun isActive(uuid: UUID): Boolean = active.containsKey(uuid)

    /** Ends the true invisibility right now (Blood Trail: the double has risen out of the pool). */
    fun stop(player: ServerPlayerEntity) {
        if (active.remove(player.uuid) != null) {
            if (player.hasStatusEffect(StatusEffects.INVISIBILITY)) player.removeStatusEffect(StatusEffects.INVISIBILITY)
            reveal(player)
        }
    }

    fun reset() = active.clear()

    fun tick(server: MinecraftServer) {
        if (active.isEmpty()) return
        val it = active.entries.iterator()
        while (it.hasNext()) {
            val (uuid, entry) = it.next()
            val player = server.playerManager.getPlayer(uuid)
            if (player == null) {
                it.remove()
                continue
            }
            entry.ticksLeft--
            entry.age++
            // ends early when the time is up, the player dies or drinks milk
            if (entry.ticksLeft <= 0 || !player.isAlive || !player.hasStatusEffect(StatusEffects.INVISIBILITY)) {
                it.remove()
                reveal(player)
                continue
            }
            // re-send every tick: covers item switches and players that just walked into range
            hide(player)
            if (entry.age % 10 == 0) broadcastFlag(player, true)
        }
    }

    private fun hide(player: ServerPlayerEntity) {
        val packet = EntityEquipmentUpdateS2CPacket(player.id, SLOTS.map { Pair.of(it, ItemStack.EMPTY) })
        for (other in PlayerLookup.tracking(player)) {
            if (other !== player) other.networkHandler.sendPacket(packet)
        }
    }

    private fun reveal(player: ServerPlayerEntity) {
        val packet = EntityEquipmentUpdateS2CPacket(player.id, SLOTS.map { Pair.of(it, player.getEquippedStack(it).copy()) })
        for (other in PlayerLookup.tracking(player)) {
            if (other !== player) other.networkHandler.sendPacket(packet)
        }
        broadcastFlag(player, false)
    }

    /** Tells every client near you - and your own client (third person / F5) - to stop drawing your gear. */
    private fun broadcastFlag(player: ServerPlayerEntity, on: Boolean) {
        val payload = TrueInvisPayload(player.id, on)
        val receivers = PlayerLookup.tracking(player).toMutableSet()
        receivers.add(player)
        for (other in receivers) {
            if (ServerPlayNetworking.canSend(other, TrueInvisPayload.ID)) ServerPlayNetworking.send(other, payload)
        }
    }
}

/** Client-side list of entities that are fully invisible right now (filled by [TrueInvisPayload]). */
object TrueInvisState {
    private val hidden: MutableSet<Int> = java.util.Collections.synchronizedSet(HashSet())

    @JvmStatic
    fun isHidden(entityId: Int): Boolean = hidden.contains(entityId)

    fun set(entityId: Int, on: Boolean) {
        if (on) hidden.add(entityId) else hidden.remove(entityId)
    }

    fun clear() = hidden.clear()
}
