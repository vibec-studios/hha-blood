package dev.henny.hha_blood.client

import dev.henny.hha_blood.HhaBlood
import dev.henny.hha_blood.logic.TrueInvisState
import dev.henny.hha_blood.net.HudLinePayload
import dev.henny.hha_blood.net.ShakePayload
import dev.henny.hha_blood.net.ScreenFlashPayload
import dev.henny.hha_blood.net.HuntedPayload
import dev.henny.hha_blood.net.TrackerListPayload
import dev.henny.hha_blood.net.TrueInvisPayload
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

/**
 * Client side of the Blood addon. The armour abilities have NO own keys: they run through HHA's
 * ability keys (Ability 1 = Nihil, Ability 2 = Blood Trail, Ability 4 = Blood Tracker, see BloodAddon).
 */
class HhaBloodClient : ClientModInitializer {

    override fun onInitializeClient() {
        BloodParticleFactories.register()
        // Vein Hollows / Slaughter Pit decoration: watching eyes, the beating heart, blood falls, rising motes
        dev.henny.hha_blood.client.hollows.HollowsClient.register()
        // Slaughter Pit bosses
        net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(
            dev.henny.hha_blood.client.boss.MinotaurModel.LAYER,
        ) { dev.henny.hha_blood.client.boss.MinotaurModelData.create() }
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha_blood.boss.BossContent.MINOTAUR) {
            dev.henny.hha_blood.client.boss.MinotaurRenderer(it)
        }
        net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(
            dev.henny.hha_blood.client.boss.CerberusModel.LAYER,
        ) { dev.henny.hha_blood.client.boss.CerberusModelData.create() }
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha_blood.boss.BossContent.CERBERUS) {
            dev.henny.hha_blood.client.boss.CerberusRenderer(it)
        }
        // cutscenes + boss music
        ClientPlayNetworking.registerGlobalReceiver(dev.henny.hha_blood.boss.CinePayload.ID) { payload, _ -> dev.henny.hha_blood.client.cine.CinePlayer.handle(payload) }
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register { dev.henny.hha_blood.client.cine.CinePlayer.tick() }
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlocks(
            net.minecraft.client.render.BlockRenderLayer.CUTOUT,
            dev.henny.hha_blood.boss.BossContent.HEAD_BLEED_BLOCK,
            dev.henny.hha_blood.boss.BossContent.HEAD_FLAME_BLOCK,
            dev.henny.hha_blood.boss.BossContent.HEAD_HOWL_BLOCK,
            dev.henny.hha_blood.hollows.HollowsBlocks.BLOOD_CRYSTAL_CLUSTER,
            dev.henny.hha_blood.hollows.HollowsBlocks.SMALL_BLOOD_CRYSTAL,
        )
        // clotted blood: translucent jelly like the honey block
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlock(
            dev.henny.hha_blood.hollows.HollowsBlocks.CLOTTED_BLOOD, net.minecraft.client.render.BlockRenderLayer.TRANSLUCENT,
        )
        // blood fluid: own (untinted) textures, translucent like water
        val bloodFluids = dev.henny.hha_blood.hollows.HollowsFluids
        net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry.INSTANCE.register(
            bloodFluids.BLOOD, bloodFluids.FLOWING_BLOOD,
            net.fabricmc.fabric.api.client.render.fluid.v1.SimpleFluidRenderHandler(
                HhaBlood.id("block/blood_still"), HhaBlood.id("block/blood_flow"),
            ),
        )
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putFluids(
            net.minecraft.client.render.BlockRenderLayer.TRANSLUCENT, bloodFluids.BLOOD, bloodFluids.FLOWING_BLOOD,
        )

        ClientPlayNetworking.registerGlobalReceiver(TrackerListPayload.ID) { payload, ctx ->
            val client = ctx.client()
            if (client.currentScreen == null) client.setScreen(TrackerScreen(payload))
        }
        ClientPlayNetworking.registerGlobalReceiver(TrueInvisPayload.ID) { payload, _ ->
            TrueInvisState.set(payload.entityId, payload.active)
        }
        ClientPlayNetworking.registerGlobalReceiver(HuntedPayload.ID) { payload, _ -> HuntedOverlay.active = payload.active }
        ClientPlayNetworking.registerGlobalReceiver(HudLinePayload.ID) { payload, _ -> HudLineOverlay.show(payload) }
        ClientPlayNetworking.registerGlobalReceiver(ShakePayload.ID) { payload, _ -> CameraShake.shake(payload.strength, payload.ticks) }
        ClientPlayNetworking.registerGlobalReceiver(ScreenFlashPayload.ID) { payload, _ -> ScreenFlash.flash(payload.color, payload.strength, payload.ticks) }
        ScreenFlash.register()
        HuntedOverlay.register()
        HudLineOverlay.register()
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            TrueInvisState.clear()
            HuntedOverlay.active = false
            HudLineOverlay.clear()
            CameraShake.clear()
            ScreenFlash.clear()
            dev.henny.hha_blood.client.cine.CinePlayer.reset()
        }
    }
}
