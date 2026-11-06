package dev.henny.hha_blood.logic

import dev.henny.hha.api.set.SetState
import dev.henny.hha.config.HhaConfig
import dev.henny.hha_blood.BloodItems
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.item.ItemStack
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Arm
import net.minecraft.util.math.Vec3d
import kotlin.math.cos
import kotlin.math.sin

/** Passive effects of the Blood set. */
object BloodPassives {

    /** Called by HHA every server tick for players wearing at least one piece. */
    fun onTick(world: ServerWorld, player: ServerPlayerEntity, state: SetState) {
        val time = world.time

        // The armour "bleeds": drops form on the plates and fall, plus a thin crimson orbit.
        // Nothing while Blood Trail makes you truly invisible - it would give you away.
        if (state.anyPiece && !TrueInvis.isActive(player.uuid)) {
            if (time % 5L == 0L) {
                val a = world.random.nextDouble() * Math.PI * 2
                val r = 0.32 + world.random.nextDouble() * 0.1
                BloodFx.spawn(world, BloodFx.dropsFalling, player.x + cos(a) * r, player.y + 0.5 + world.random.nextDouble() * (player.height - 0.7), player.z + sin(a) * r)
            }
            if (time % 2L == 0L) {
                val a = time * 0.45
                val y = player.y + 0.15 + (time % 44L) / 44.0 * (player.height - 0.25)
                BloodFx.spawn(world, BloodFx.dust(BloodFx.BLOOD, 0.7f), player.x + cos(a) * 0.45, y, player.z + sin(a) * 0.45)
            }
            // full set: a faint glowing red haze around the armour (the redstone trims "glow")
            if (state.full) {
                if (time % 2L == 0L) {
                    val a = world.random.nextDouble() * Math.PI * 2
                    val r = 0.45 + world.random.nextDouble() * 0.35
                    val y = player.y + 0.1 + world.random.nextDouble() * player.height
                    BloodFx.spawn(world, BloodFx.dust(if (world.random.nextInt(3) == 0) 0xFF3A3A else BloodFx.BRIGHT, 0.45f), player.x + cos(a) * r, y, player.z + sin(a) * r)
                }
                if (time % 10L == 0L) {
                    val a = world.random.nextDouble() * Math.PI * 2
                    BloodFx.spawn(world, BloodFx.flame, player.x + cos(a) * 0.5, player.y + 0.2 + world.random.nextDouble() * 1.4, player.z + sin(a) * 0.5, 0, 0.0, 1.0, 0.0, 0.02)
                }
            }
        }

        if (state.helmet && time % 20L == 0L) refresh(player, StatusEffects.REGENERATION, 0)
        if (state.chestplate && time % 20L == 0L) refresh(player, StatusEffects.FIRE_RESISTANCE, 0)
        if (state.boots && time % 20L == 0L) refresh(player, StatusEffects.SPEED, 0)
    }

    private fun refresh(player: ServerPlayerEntity, effect: net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.effect.StatusEffect>, amp: Int) {
        EnduringEffects.withoutEnduring {
            player.addStatusEffect(StatusEffectInstance(effect, 60, amp, true, false, true))
        }
    }

    /**
     * Blood Sword / Blood Mace drip while you hold them: drops form on the blade / mace head
     * (roughly where the item is in third person) and fall to the ground.
     */
    fun tickHeldWeapons(server: MinecraftServer) {
        val time = server.ticks
        if (time % 4 != 0) return
        for (player in server.playerManager.playerList) {
            if (!player.isAlive || player.isSpectator || TrueInvis.isActive(player.uuid)) continue
            val world = player.entityWorld as? ServerWorld ?: continue
            drip(world, player, player.mainHandStack, player.mainArm, time)
            drip(world, player, player.offHandStack, player.mainArm.opposite, time)
        }
    }

    private fun drip(world: ServerWorld, player: ServerPlayerEntity, stack: ItemStack, arm: Arm, time: Int) {
        val sword = stack.isOf(BloodItems.BLOOD_SWORD)
        val mace = stack.isOf(BloodItems.BLOOD_MACE)
        if (!sword && !mace) return
        // not every 4th tick - a slow, irregular drip
        if (world.random.nextInt(if (sword) 3 else 2) != 0) return

        val yaw = Math.toRadians(player.bodyYaw.toDouble())
        val forward = Vec3d(-sin(yaw), 0.0, cos(yaw))
        val right = Vec3d(-cos(yaw), 0.0, -sin(yaw)).multiply(if (arm == Arm.RIGHT) 1.0 else -1.0)
        val sneak = if (player.isInSneakingPose) -0.3 else 0.0
        val hand = player.entityPos.add(0.0, 0.72 + sneak, 0.0).add(right.multiply(0.38)).add(forward.multiply(0.1))
        // the item points forward/up out of the hand
        val along = if (sword) world.random.nextDouble() * 0.75 else 0.55 + world.random.nextDouble() * 0.2
        val p = hand.add(forward.multiply(along * 0.75)).add(0.0, along * 0.35, 0.0)
        val spread = if (mace) 0.12 else 0.03
        BloodFx.spawn(world, BloodFx.drops, p.x, p.y, p.z, 1, spread, 0.02, spread, 0.0)
        if (mace && time % 12 == 0) BloodFx.spawn(world, BloodFx.dust(BloodFx.BRIGHT, 0.7f), p, 2, 0.18, 0.0)
    }

    @JvmStatic
    fun hasFallImmunity(player: ServerPlayerEntity): Boolean =
        HhaConfig.enabled("hha_blood.fall_immunity") && player.getEquippedStack(EquipmentSlot.FEET).isOf(BloodItems.BLOOD_BOOTS)
}
