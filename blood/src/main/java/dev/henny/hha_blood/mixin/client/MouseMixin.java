package dev.henny.hha_blood.mixin.client;

import dev.henny.hha_blood.client.cine.CinePlayer;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the view direction during cutscenes, so the player is not twisted afterwards. */
@Mixin(Mouse.class)
public abstract class MouseMixin {

    @Inject(method = "updateMouse", at = @At("HEAD"), cancellable = true)
    private void hhaBlood$freezeLook(double timeDelta, CallbackInfo ci) {
        if (CinePlayer.getActive()) {
            ci.cancel();
        }
    }
}
