package dev.henny.hha_blood.boss

import dev.henny.hha_blood.BloodItems
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket
import net.minecraft.network.packet.s2c.play.TitleS2CPacket
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos

/**
 * Speech of the Blood Prince (for now as chat lines + a title card; round 4 brings real dialogue boxes
 * and cutscenes). Keys `boss.hha_blood.prince.<key>` in the lang files.
 */
object BloodPrinceLines {

    fun wearsBlood(p: PlayerEntity): Boolean =
        p.getEquippedStack(EquipmentSlot.CHEST).isOf(BloodItems.BLOOD_CHESTPLATE) ||
            p.getEquippedStack(EquipmentSlot.HEAD).isOf(BloodItems.BLOOD_HELMET) ||
            p.getEquippedStack(EquipmentSlot.LEGS).isOf(BloodItems.BLOOD_LEGGINGS)

    private fun line(key: String): Text =
        Text.empty()
            .append(Text.translatable("entity.hha_blood.blood_prince").formatted(Formatting.DARK_RED, Formatting.BOLD))
            .append(Text.literal(": ").formatted(Formatting.DARK_RED))
            .append(Text.translatable("boss.hha_blood.prince.$key").formatted(Formatting.RED, Formatting.ITALIC))

    /** To everyone in the arena. */
    fun say(world: ServerWorld, key: String) {
        val t = line(key)
        for (p in HeartVaultArena.playersInArena(world)) p.sendMessage(t, false)
    }

    /** To everyone near the arena (also players that just fled). */
    fun sayNear(world: ServerWorld, c: BlockPos, key: String) {
        val t = line(key)
        for (p in world.players) if (p.squaredDistanceTo(c.x + 0.5, c.y.toDouble(), c.z + 0.5) < 120.0 * 120.0) p.sendMessage(t, false)
    }

    /** Big title card when he wakes up. */
    fun title(world: ServerWorld, prince: BloodPrinceEntity) {
        for (p in HeartVaultArena.playersInArena(world)) {
            val sp = p as? ServerPlayerEntity ?: continue
            sp.networkHandler.sendPacket(TitleFadeS2CPacket(10, 60, 20))
            sp.networkHandler.sendPacket(SubtitleS2CPacket(Text.translatable("boss.hha_blood.prince.subtitle").formatted(Formatting.GRAY, Formatting.ITALIC)))
            sp.networkHandler.sendPacket(TitleS2CPacket((prince.displayName ?: Text.translatable("entity.hha_blood.blood_prince")).copy().formatted(Formatting.DARK_RED)))
        }
    }

    /** Title card when Cruorax has formed. */
    fun dragonTitle(world: ServerWorld, dragon: CruoraxEntity) {
        for (p in HeartVaultArena.playersInArena(world)) {
            val sp = p as? ServerPlayerEntity ?: continue
            sp.networkHandler.sendPacket(TitleFadeS2CPacket(10, 70, 20))
            sp.networkHandler.sendPacket(SubtitleS2CPacket(Text.translatable("boss.hha_blood.cruorax.subtitle").formatted(Formatting.GRAY, Formatting.ITALIC)))
            sp.networkHandler.sendPacket(TitleS2CPacket(Text.translatable("entity.hha_blood.cruorax").formatted(Formatting.DARK_RED)))
        }
    }
}