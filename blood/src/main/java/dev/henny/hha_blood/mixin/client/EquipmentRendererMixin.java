package dev.henny.hha_blood.mixin.client;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.entity.equipment.EquipmentRenderer;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Glowing armour trims: the Blood armour has a second equipment layer `hha_blood:blood_glow`
 * (only the trim pixels, see assets/hha_blood/equipment/blood.json). That layer is drawn with the
 * "eyes" render layer - the one spider/enderman eyes use - so it ignores world light and glows,
 * day and night. Its tint pulses slowly (bright <-> brighter) like a heartbeat.
 */
@Mixin(EquipmentRenderer.class)
public abstract class EquipmentRendererMixin {

    private static final String RENDER =
        "render(Lnet/minecraft/client/render/entity/equipment/EquipmentModel$LayerType;Lnet/minecraft/registry/RegistryKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/util/Identifier;II)V";

    /** set while the arguments of the glow layer's submitModel call are evaluated */
    @Unique
    private static boolean hhaBlood$glowPending = false;

    @Redirect(
        method = RENDER,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/RenderLayers;armorCutoutNoCull(Lnet/minecraft/util/Identifier;)Lnet/minecraft/client/render/RenderLayer;")
    )
    private RenderLayer hhaBlood$glowLayer(Identifier texture) {
        if ("hha_blood".equals(texture.getNamespace()) && texture.getPath().endsWith("blood_glow.png")) {
            hhaBlood$glowPending = true;
            return RenderLayers.eyes(texture);
        }
        return RenderLayers.armorCutoutNoCull(texture);
    }

    @ModifyArg(
        method = RENDER,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/command/RenderCommandQueue;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/RenderLayer;IIILnet/minecraft/client/texture/Sprite;ILnet/minecraft/client/render/command/ModelCommandRenderer$CrumblingOverlayCommand;)V"),
        index = 6
    )
    private int hhaBlood$glowPulse(int tint) {
        if (!hhaBlood$glowPending) return tint;
        hhaBlood$glowPending = false;
        // slow pulse, ~1.8 s per cycle, never dimmer than 65 %
        double t = (System.currentTimeMillis() % 1800L) / 1800.0;
        double f = 0.65 + 0.35 * (0.5 + 0.5 * Math.sin(t * Math.PI * 2));
        int v = (int) Math.round(255 * f);
        return 0xFF000000 | (v << 16) | (v << 8) | v;
    }
}
