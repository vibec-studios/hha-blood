package dev.henny.hha_blood.mixin.client;

import dev.henny.hha_blood.logic.TrueInvisState;
import net.minecraft.client.item.ItemModelManager;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blood Trail true invisibility on the render side: armour, held items and head items are not drawn
 * for a fully invisible entity - also for yourself in third person (F5) and in the inventory preview.
 * (Other players additionally get empty equipment packets from the server, see TrueInvis.)
 */
@Mixin(BipedEntityRenderer.class)
public abstract class BipedEntityRendererMixin {

    @Inject(method = "updateBipedRenderState", at = @At("TAIL"))
    private static void hhaBlood$hideEquipment(LivingEntity entity, BipedEntityRenderState state, float tickProgress, ItemModelManager itemModelResolver, CallbackInfo ci) {
        if (!TrueInvisState.isHidden(entity.getId())) return;
        state.equippedHeadStack = ItemStack.EMPTY;
        state.equippedChestStack = ItemStack.EMPTY;
        state.equippedLegsStack = ItemStack.EMPTY;
        state.equippedFeetStack = ItemStack.EMPTY;
        state.rightHandItem = ItemStack.EMPTY;
        state.leftHandItem = ItemStack.EMPTY;
        state.rightHandItemState.clear();
        state.leftHandItemState.clear();
        state.headItemRenderState.clear();
        state.wearingSkullType = null;
        state.wearingSkullProfile = null;
    }
}
