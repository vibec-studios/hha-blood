package dev.henny.hha_blood.mixin.client;

import dev.henny.hha_blood.client.CameraShake;
import dev.henny.hha_blood.client.cine.CinePlayer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Camera shake of heavy Blood impacts (after the vanilla hurt tilt) and the cutscene field of view. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "tiltViewWhenHurt", at = @At("TAIL"))
    private void hhaBlood$shake(MatrixStack matrices, float tickProgress, CallbackInfo ci) {
        CameraShake.apply(matrices);
    }

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void hhaBlood$cinematicFov(Camera camera, float tickProgress, boolean changingFov, CallbackInfoReturnable<Float> cir) {
        if (CinePlayer.getActive()) {
            cir.setReturnValue(CinePlayer.fov(cir.getReturnValueF(), tickProgress));
        }
    }
}
