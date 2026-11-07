package dev.henny.hha_blood.mixin.client;

import dev.henny.hha_blood.client.cine.CinePlayer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** During cutscenes the HUD disappears; letterbox, dialogue and titles are always drawn on top. */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void hhaBlood$hideHud(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (CinePlayer.getActive()) {
            CinePlayer.renderOverlay(context, tickCounter.getTickProgress(false));
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void hhaBlood$overlay(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        CinePlayer.renderOverlay(context, tickCounter.getTickProgress(false));
    }
}
