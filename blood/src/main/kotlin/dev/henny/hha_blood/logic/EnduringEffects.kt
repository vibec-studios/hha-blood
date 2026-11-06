package dev.henny.hha_blood.logic

import dev.henny.hha.config.HhaConfig
import dev.henny.hha_blood.BloodItems
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.effect.StatusEffectCategory
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.server.network.ServerPlayerEntity

/**
 * Enduring Effects (Blood Chestplate): every beneficial status effect that reaches the wearer
 * lasts [multiplier] times longer. Weaving always lasts 8 minutes and Turtle Master's Resistance 1 minute
 * (config `enduring_weaving_ticks` / `enduring_turtle_ticks`). Harmful effects and the short ambient
 * refresh effects armour sets keep re-applying are left alone.
 *
 * Hooked in through `LivingEntityMixin` (modifies the instance passed to `addStatusEffect`).
 */
object EnduringEffects {

    /** Set while the addon applies its own effects so they are not stretched. */
    @JvmField
    var suppress: Boolean = false

    inline fun withoutEnduring(block: () -> Unit) {
        val before = suppress
        suppress = true
        try {
            block()
        } finally {
            suppress = before
        }
    }

    @JvmStatic
    fun extend(entity: LivingEntity, instance: StatusEffectInstance): StatusEffectInstance {
        if (suppress || entity !is ServerPlayerEntity) return instance
        if (!HhaConfig.enabled("hha_blood.enduring_effects")) return instance
        if (!entity.getEquippedStack(EquipmentSlot.CHEST).isOf(BloodItems.BLOOD_CHESTPLATE)) return instance
        if (instance.isInfinite || instance.isAmbient) return instance

        val type = instance.effectType

        // fixed durations for two special effects (never shortened)
        if (type == StatusEffects.WEAVING) return fixed(instance, HhaConfig.num("hha_blood.enduring_weaving_ticks"))
        if (isTurtleMaster(instance)) return fixed(instance, HhaConfig.num("hha_blood.enduring_turtle_ticks"))
        if (isTurtleSlowness(instance)) {
            // Turtle Master's Slowness only lasts half as long (20 s -> 10 s, long 40 s -> 20 s); level unchanged
            val factor = HhaConfig.num("hha_blood.enduring_turtle_slow_factor").coerceIn(0.0, 1.0)
            val ticks = (instance.duration * factor).toInt().coerceAtLeast(1)
            return StatusEffectInstance(instance.effectType, ticks, instance.amplifier, instance.isAmbient, instance.shouldShowParticles(), instance.shouldShowIcon())
        }

        if (type.value().category != StatusEffectCategory.BENEFICIAL) return instance

        // Resistance from (enchanted) golden apples keeps its normal duration (Mats: lasted far too long);
        // the apple is still the active item while its effects are applied
        if (type == StatusEffects.RESISTANCE && isEatingGoldenApple(entity)) return instance

        val multiplier = HhaConfig.num("hha_blood.enduring_multiplier")
        if (multiplier <= 1.0) return instance
        val cap = HhaConfig.num("hha_blood.enduring_max_ticks").toLong().coerceAtLeast(1L)
        val stretched = (instance.duration * multiplier).toLong().coerceAtMost(cap).toInt()
        if (stretched <= instance.duration) return instance

        return StatusEffectInstance(type, stretched, instance.amplifier, instance.isAmbient, instance.shouldShowParticles(), instance.shouldShowIcon())
    }

    private fun isEatingGoldenApple(entity: LivingEntity): Boolean {
        val stack = entity.activeItem
        return stack.isOf(net.minecraft.item.Items.GOLDEN_APPLE) || stack.isOf(net.minecraft.item.Items.ENCHANTED_GOLDEN_APPLE)
    }

    private fun fixed(instance: StatusEffectInstance, ticks: Double): StatusEffectInstance {
        val target = ticks.toInt()
        if (target <= instance.duration) return instance
        return StatusEffectInstance(instance.effectType, target, instance.amplifier, instance.isAmbient, instance.shouldShowParticles(), instance.shouldShowIcon())
    }

    /**
     * Turtle Master's Resistance part (Resistance III/IV, 20-40 s). Its Slowness is harmful and stays
     * short, so you keep the Resistance for a full minute without crawling.
     */
    private fun isTurtleMaster(instance: StatusEffectInstance): Boolean =
        instance.effectType == StatusEffects.RESISTANCE && instance.amplifier >= 2 && instance.duration <= 900

    /**
     * Turtle Master's Slowness part: Slowness IV (normal/long) or VI (strong) for 5-45 s (lingering
     * clouds give a quarter of that). The lower bound keeps Blood Trail's short Slowness VI (4 s) out.
     */
    private fun isTurtleSlowness(instance: StatusEffectInstance): Boolean =
        instance.effectType == StatusEffects.SLOWNESS && (instance.amplifier == 3 || instance.amplifier == 5) &&
            instance.duration in 100..900
}
