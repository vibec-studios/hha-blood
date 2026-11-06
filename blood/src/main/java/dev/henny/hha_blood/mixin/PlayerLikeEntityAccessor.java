package dev.henny.hha_blood.mixin;

import net.minecraft.entity.PlayerLikeEntity;
import net.minecraft.entity.data.TrackedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Skin layer flags (hat, jacket, sleeves ...) - copied from the player onto the Blood Trail double. */
@Mixin(PlayerLikeEntity.class)
public interface PlayerLikeEntityAccessor {
    @Accessor("PLAYER_MODE_CUSTOMIZATION_ID")
    static TrackedData<Byte> hhaBloodCustomizationKey() {
        throw new AssertionError();
    }
}
