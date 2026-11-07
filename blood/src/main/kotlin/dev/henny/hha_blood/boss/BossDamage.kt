package dev.henny.hha_blood.boss

import dev.henny.hha_blood.HhaBlood
import net.minecraft.entity.Entity
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.damage.DamageType
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.world.ServerWorld

/**
 * The bosses' own damage types (data/hha_blood/damage_type): all bypass the armour points (PitBoss.hurt takes
 * off a part itself), hellfire and crush also bypass shields and cause no knockback (see the damage type tags).
 */
object BossDamage {
    const val SLAUGHTER = 0
    const val HELLFIRE = 1
    const val CRUSH = 2

    private val KEYS = listOf("slaughter", "hellfire", "crush").map { RegistryKey.of(RegistryKeys.DAMAGE_TYPE, HhaBlood.id(it)) }

    fun key(kind: Int): RegistryKey<DamageType> = KEYS[kind.coerceIn(0, KEYS.size - 1)]

    fun source(world: ServerWorld, kind: Int, attacker: Entity): DamageSource =
        DamageSource(world.registryManager.getOrThrow(RegistryKeys.DAMAGE_TYPE).getOrThrow(key(kind)), attacker)
}
