package dev.henny.hha_blood.mixin.client;

import dev.henny.hha_blood.client.cine.CinePlayer;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cutscene camera of the Slaughter Pit fight. */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void setPos(Vec3d pos);

    @Inject(method = "update", at = @At("TAIL"))
    private void hhaBlood$cinematicCamera(World area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickProgress, CallbackInfo ci) {
        CinePlayer.CameraPose pose = CinePlayer.cameraPose(tickProgress);
        if (pose != null) {
            setRotation(pose.yaw, pose.pitch);
            setPos(pose.pos);
        }
    }
}
