package dev.henny.hha_blood.mixin.client;

import dev.henny.hha_blood.client.CameraShake;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Camera shake of heavy Blood impacts, applied right after the vanilla hurt tilt (world view and hand). */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "tiltViewWhenHurt", at = @At("TAIL"))
    private void hhaBlood$shake(MatrixStack matrices, float tickProgress, CallbackInfo ci) {
        CameraShake.apply(matrices);
    }
}
