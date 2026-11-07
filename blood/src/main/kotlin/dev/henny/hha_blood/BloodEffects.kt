package dev.henny.hha_blood

import net.minecraft.entity.attribute.EntityAttributeModifier
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.effect.StatusEffect
import net.minecraft.entity.effect.StatusEffectCategory
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.entry.RegistryEntry

/**
 * "Bleeding" as a real status effect: the bleeding player sees its icon (top right / inventory),
 * everybody sees its effect particles - our falling blood drops instead of potion swirls.
 * It only SHOWS the bleeding; damage and timing are handled by logic.Bleed (milk ends both).
 */
class BleedingStatusEffect : StatusEffect(StatusEffectCategory.HARMFUL, 0x9B0A12, BloodParticles.DRIP_FALL)

/** Paralysed by the howl of the Blood Cerberus: no walking, no jumping, weak blows. */
class ParalyzedStatusEffect : StatusEffect(StatusEffectCategory.HARMFUL, 0x6A2ACF)

object BloodEffects {
    @JvmField val BLEEDING: RegistryEntry<StatusEffect> =
        Registry.registerReference(Registries.STATUS_EFFECT, HhaBlood.id("bleeding"), BleedingStatusEffect())

    @JvmField val PARALYZED: RegistryEntry<StatusEffect> =
        Registry.registerReference(
            Registries.STATUS_EFFECT, HhaBlood.id("paralyzed"),
            ParalyzedStatusEffect()
                .addAttributeModifier(EntityAttributes.MOVEMENT_SPEED, HhaBlood.id("paralyzed_speed"), -1.0, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
                .addAttributeModifier(EntityAttributes.JUMP_STRENGTH, HhaBlood.id("paralyzed_jump"), -1.0, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
                .addAttributeModifier(EntityAttributes.ATTACK_SPEED, HhaBlood.id("paralyzed_attack"), -0.8, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL),
        )

    fun init() {
        // touching the object registers the fields above
    }
}
