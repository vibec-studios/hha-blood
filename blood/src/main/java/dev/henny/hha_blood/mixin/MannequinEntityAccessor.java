package dev.henny.hha_blood.mixin;

import net.minecraft.entity.decoration.MannequinEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Blood Trail dive: the sinking double is a vanilla mannequin without its "NPC" label that cannot be pushed. */
@Mixin(MannequinEntity.class)
public interface MannequinEntityAccessor {
    @Invoker("setHideDescription")
    void hhaBloodSetHideDescription(boolean hide);

    @Invoker("setImmovable")
    void hhaBloodSetImmovable(boolean immovable);
}
