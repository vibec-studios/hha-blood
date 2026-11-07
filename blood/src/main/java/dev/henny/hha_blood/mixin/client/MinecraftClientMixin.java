package dev.henny.hha_blood.mixin.client;

import dev.henny.hha_blood.client.cine.CinePlayer;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No key actions (attack, hotbar, inventory) while a cutscene runs. */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Inject(method = "handleInputEvents", at = @At("HEAD"), cancellable = true)
    private void hhaBlood$lockActions(CallbackInfo ci) {
        if (CinePlayer.getActive()) {
            ci.cancel();
        }
    }
}
