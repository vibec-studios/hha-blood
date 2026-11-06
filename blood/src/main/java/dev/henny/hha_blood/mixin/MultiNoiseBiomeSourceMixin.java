package dev.henny.hha_blood.mixin;

import com.mojang.datafixers.util.Pair;
import dev.henny.hha_blood.hollows.HollowsRegion;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.MultiNoiseBiomeSource;
import net.minecraft.world.biome.source.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts the Vein Hollows UNDER the normal Nether biomes (see {@link HollowsRegion}). Only the vanilla
 * Nether preset is touched; the biome itself is part of that preset through Fabric's NetherBiomes API
 * (with an unreachable noise point), so it is a "possible" biome there and its features are indexed.
 */
@Mixin(MultiNoiseBiomeSource.class)
public class MultiNoiseBiomeSourceMixin {

    @Shadow
    public boolean matchesInstance(net.minecraft.registry.RegistryKey<net.minecraft.world.biome.source.MultiNoiseBiomeSourceParameterList> parameterList) {
        throw new AssertionError();
    }

    @Shadow
    private MultiNoiseUtil.Entries<RegistryEntry<Biome>> getBiomeEntries() {
        throw new AssertionError();
    }

    /** 0 = unknown, 1 = Nether preset with our biome, 2 = anything else. */
    @Unique private volatile int hhaBlood$mode = 0;
    @Unique private volatile RegistryEntry<Biome> hhaBlood$hollows;

    @Inject(method = "getBiome", at = @At("HEAD"), cancellable = true)
    private void hhaBlood$hollows(int x, int y, int z, MultiNoiseUtil.MultiNoiseSampler noise, CallbackInfoReturnable<RegistryEntry<Biome>> cir) {
        if (y >= HollowsRegion.TOP_QUART) return;
        int mode = this.hhaBlood$mode;
        if (mode == 0) mode = this.hhaBlood$init();
        if (mode != 1) return;
        if (HollowsRegion.isCell(noise, x, z)) cir.setReturnValue(this.hhaBlood$hollows);
    }

    @Unique
    private int hhaBlood$init() {
        int mode = 2;
        if (this.matchesInstance(MultiNoiseBiomeSourceParameterLists.NETHER)) {
            for (Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>> e : this.getBiomeEntries().getEntries()) {
                if (e.getSecond().matchesKey(HollowsRegion.BIOME)) {
                    this.hhaBlood$hollows = e.getSecond();
                    mode = 1;
                    break;
                }
            }
        }
        this.hhaBlood$mode = mode;
        return mode;
    }
}
