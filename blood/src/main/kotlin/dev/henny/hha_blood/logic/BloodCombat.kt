package dev.henny.hha_blood.logic

import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.Targeting
import dev.henny.hha.logic.UltraMode
import dev.henny.hha_blood.BloodItems
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvents
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import java.util.UUID

/** Melee hooks: Infection (Blood Leggings) and Blood Rush (Blood Boots). */
object BloodCombat {

    private val infectionReadyAt = HashMap<UUID, Long>()

    fun init() {
        AttackEntityCallback.EVENT.register { player, world, hand, entity, _ ->
            // Blood Mace: every hit sounds like the mace landing back in your hand after the Blood Cyclone
            if (world is ServerWorld && hand == Hand.MAIN_HAND && player.mainHandStack.isOf(BloodItems.BLOOD_MACE) &&
                entity is LivingEntity && !player.isSpectator
            ) {
                BloodFx.sound(world, entity.entityPos, SoundEvents.ITEM_MACE_SMASH_GROUND, 0.9f, 1.1f)
            }
            if (world is ServerWorld && player is ServerPlayerEntity && entity is LivingEntity &&
                hand == Hand.MAIN_HAND &&
                player.getAttackCooldownProgress(0.5f) > 0.9f &&
                Targeting.shouldHarm(player, entity)
            ) {
                onMeleeHit(world, player, entity)
            }
            ActionResult.PASS
        }
    }

    fun reset() = infectionReadyAt.clear()

    private fun onMeleeHit(world: ServerWorld, player: ServerPlayerEntity, target: LivingEntity) {
        tryBloodRush(world, player, target)   // before infection, so a fresh wound doesn't count
        tryInfection(world, player, target)
    }

    private fun tryInfection(world: ServerWorld, player: ServerPlayerEntity, target: LivingEntity) {
        if (!HhaConfig.enabled("hha_blood.infection")) return
        if (!player.getEquippedStack(EquipmentSlot.LEGS).isOf(BloodItems.BLOOD_LEGGINGS)) return
        val now = world.server?.ticks?.toLong() ?: return
        if (now < (infectionReadyAt[player.uuid] ?: 0L)) return
        if (world.random.nextDouble() > HhaConfig.num("hha_blood.infection_chance") / 100.0) return

        Bleed.apply(target, source = player)
        if (!UltraMode.isActive(player)) {
            infectionReadyAt[player.uuid] = now + HhaConfig.num("hha_blood.infection_cooldown").toLong().coerceAtLeast(0L)
        }
        val body = target.entityPos.add(0.0, target.height * 0.55, 0.0)
        // Slash marks + a burst of blood - clearly "the wound just opened".
        BloodFx.spawn(world, ParticleTypes.SWEEP_ATTACK, body, 1)
        BloodFx.wound(world, body, 3)
        BloodFx.spawn(world, BloodFx.fade(BloodFx.BRIGHT, BloodFx.BLACK, 1.5f), body, 18, 0.4, 0.05)
        BloodFx.sound(world, body, SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, 0.9f, 0.6f)
        BloodFx.sound(world, body, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.3f, 0.6f)
    }

    private fun tryBloodRush(world: ServerWorld, player: ServerPlayerEntity, target: LivingEntity) {
        if (!HhaConfig.enabled("hha_blood.blood_rush")) return
        if (!player.getEquippedStack(EquipmentSlot.FEET).isOf(BloodItems.BLOOD_BOOTS)) return
        if (!Bleed.isBleeding(target)) return
        EnduringEffects.withoutEnduring {
            player.addStatusEffect(StatusEffectInstance(StatusEffects.SPEED, HhaConfig.num("hha_blood.blood_rush_duration").toInt(), 1, false, true, true))
        }
        BloodFx.spawn(world, BloodFx.crumbs, player.x, player.y + 0.2, player.z, 10, 0.4, 0.1, 0.4, 0.12)
        BloodFx.spawn(world, BloodFx.dust(BloodFx.BRIGHT, 1.2f), player.x, player.y + 0.4, player.z, 10, 0.35, 0.25, 0.35, 0.0)
        BloodFx.sound(world, player.entityPos, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 1.4f)
    }
}
