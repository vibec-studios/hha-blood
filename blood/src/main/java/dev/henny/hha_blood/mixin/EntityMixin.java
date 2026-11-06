package dev.henny.hha_blood.mixin;

import dev.henny.hha_blood.logic.TrueInvisState;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {

    /** Blood Trail true invisibility: no sprint dust that would give you away. */
    @Inject(method = "shouldSpawnSprintingParticles", at = @At("HEAD"), cancellable = true)
    private void hhaBlood$noSprintParticles(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (self.getEntityWorld().isClient() && TrueInvisState.isHidden(self.getId())) {
            cir.setReturnValue(false);
        }
    }
}
