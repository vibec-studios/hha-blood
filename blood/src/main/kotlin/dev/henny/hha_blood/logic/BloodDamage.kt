package dev.henny.hha_blood.logic

import dev.henny.hha_blood.HhaBlood
import net.minecraft.entity.Entity
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.damage.DamageType
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.world.ServerWorld

/**
 * One damage type per Blood ability (data/hha_blood/damage_type), so every kill gets its own death message
 * (lang: death.attack.hha_blood.<type>[.player|.item]). They behave like a normal player attack (armour
 * counts, no difficulty scaling for players); only [BLEED] ignores armour like the magic damage it replaced.
 */
object BloodDamage {
    val NIHIL = key("nihil")
    val NIHIL_NOVA = key("nihil_nova")
    val NIHIL_SPIKE = key("nihil_spike")
    val BLOOD_TRAIL = key("blood_trail")
    val BLOOD_CYCLONE = key("blood_cyclone")
    val BLOODSAW = key("bloodsaw")
    val BLOOD_FOUNTAIN = key("blood_fountain")
    val BLEED = key("bleed")
    /** Bleeding whose source is gone (offline, dead): "X bled out". */
    val BLEED_OUT = key("bleed_out")

    private fun key(id: String): RegistryKey<DamageType> = RegistryKey.of(RegistryKeys.DAMAGE_TYPE, HhaBlood.id(id))

    /** A damage source of [type] caused by [attacker] (null = no killer, e.g. a bleed whose source is gone). */
    fun source(world: ServerWorld, type: RegistryKey<DamageType>, attacker: Entity?): DamageSource =
        DamageSource(world.registryManager.getOrThrow(RegistryKeys.DAMAGE_TYPE).getOrThrow(type), attacker)
}
