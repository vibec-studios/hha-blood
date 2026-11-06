package dev.henny.hha_blood

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

object BloodEffects {
    @JvmField val BLEEDING: RegistryEntry<StatusEffect> =
        Registry.registerReference(Registries.STATUS_EFFECT, HhaBlood.id("bleeding"), BleedingStatusEffect())

    fun init() {
        // touching the object registers the fields above
    }
}
