package dev.henny.hha_blood.mixin;

import dev.henny.hha_blood.logic.Bleed;
import dev.henny.hha_blood.logic.BloodPassives;
import dev.henny.hha_blood.logic.EnduringEffects;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    /** Blood Boots: no fall damage. */
    @Inject(method = "handleFallDamage", at = @At("HEAD"), cancellable = true)
    private void hhaBlood$handleFall(double fallDistance, float damagePerBlock, DamageSource damageSource, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof ServerPlayerEntity player && BloodPassives.hasFallImmunity(player)) {
            cir.setReturnValue(false);
        }
    }

    /** Blood Sword Hemorrhage: bleeding entities take extra damage from every hit. */
    @ModifyVariable(
        method = "damage(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/entity/damage/DamageSource;F)Z",
        at = @At("HEAD"), argsOnly = true, ordinal = 0
    )
    private float hhaBlood$hemorrhage(float amount, ServerWorld world, DamageSource source) {
        LivingEntity self = (LivingEntity) (Object) this;
        float mult = Bleed.damageMultiplier(self);
        return mult > 1.0F && amount > 0.0F ? amount * mult : amount;
    }

    /** Blood Chestplate Enduring Effects: beneficial effects last much longer. */
    @ModifyVariable(
        method = "addStatusEffect(Lnet/minecraft/entity/effect/StatusEffectInstance;Lnet/minecraft/entity/Entity;)Z",
        at = @At("HEAD"), argsOnly = true, ordinal = 0
    )
    private StatusEffectInstance hhaBlood$enduring(StatusEffectInstance instance, StatusEffectInstance same, Entity source) {
        return EnduringEffects.extend((LivingEntity) (Object) this, instance);
    }
}
