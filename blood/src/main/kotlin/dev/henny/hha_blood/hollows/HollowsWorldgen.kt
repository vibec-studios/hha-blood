package dev.henny.hha_blood.hollows

import dev.henny.hha_blood.HhaBlood
import net.fabricmc.fabric.api.biome.v1.NetherBiomes
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.structure.StructurePieceType
import net.minecraft.world.biome.source.util.MultiNoiseUtil
import net.minecraft.world.gen.feature.DefaultFeatureConfig
import net.minecraft.world.gen.feature.Feature
import net.minecraft.world.gen.structure.StructureType

/**
 * World generation of the Vein Hollows: biome placement, the cave carver, decorations and the
 * "Heart Vault" boss arena structure. Data side: `data/hha_blood/worldgen/...`.
 */
object HollowsWorldgen {

    lateinit var CRYSTALS: Feature<DefaultFeatureConfig> private set
    lateinit var RIBS: Feature<DefaultFeatureConfig> private set
    lateinit var SHAFT: Feature<DefaultFeatureConfig> private set

    lateinit var HEART_VAULT: StructureType<HeartVaultStructure> private set
    lateinit var HEART_VAULT_PIECE: StructurePieceType private set
    lateinit var SINGLE_ARENA: net.minecraft.world.gen.chunk.placement.StructurePlacementType<SingleArenaPlacement> private set

    fun init() {
        CRYSTALS = feature("blood_crystals", BloodCrystalFeature())
        RIBS = feature("rib_bones", RibBonesFeature())
        SHAFT = feature("vein_shaft", VeinShaftFeature())

        HollowsRegion.init()
        SINGLE_ARENA = Registry.register(Registries.STRUCTURE_PLACEMENT, HhaBlood.id("single_arena"), net.minecraft.world.gen.chunk.placement.StructurePlacementType { SingleArenaPlacement.CODEC })
        HEART_VAULT = Registry.register(Registries.STRUCTURE_TYPE, HhaBlood.id("heart_vault"), StructureType { HeartVaultStructure.CODEC })
        HEART_VAULT_PIECE = Registry.register(
            Registries.STRUCTURE_PIECE, HhaBlood.id("heart_vault"),
            StructurePieceType { _, nbt -> HeartVaultPiece(nbt) },
        )

        // Part of the Nether preset, but at a noise point the Nether never reaches (the real placement is
        // done by MultiNoiseBiomeSourceMixin) - this only makes it a "possible" biome of the Nether.
        NetherBiomes.addNetherBiome(
            HollowsRegion.BIOME,
            MultiNoiseUtil.createNoiseHypercube(3.0f, 3.0f, 0.0f, 0.0f, 0.0f, 3.0f, 1.0f),
        )
    }

    private fun <F : Feature<DefaultFeatureConfig>> feature(name: String, f: F): F =
        Registry.register(Registries.FEATURE, HhaBlood.id(name), f)
}
