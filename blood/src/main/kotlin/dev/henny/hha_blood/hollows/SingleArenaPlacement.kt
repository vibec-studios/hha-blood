package dev.henny.hha_blood.hollows

import com.mojang.serialization.MapCodec
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement
import net.minecraft.world.gen.chunk.placement.SpreadType
import net.minecraft.world.gen.chunk.placement.StructurePlacementType

/**
 * Structure placement with exactly one start chunk: the Heart Vault chunk from [HollowsRegion.center].
 *
 * Extends random_spread on purpose: `/locate` and the structure lookups only know concentric rings and
 * random spread. Every grid cell of this "spread" answers with the same start chunk, so `/locate` finds
 * the arena from anywhere (also 15 000 blocks away) in its very first cell.
 */
class SingleArenaPlacement : RandomSpreadStructurePlacement(64, 8, SpreadType.LINEAR, 1847261153) {

    override fun getStartChunk(seed: Long, chunkX: Int, chunkZ: Int): ChunkPos {
        val c = HollowsRegion.center() ?: return ChunkPos(Int.MAX_VALUE / 32, Int.MAX_VALUE / 32)
        return ChunkPos(c.x shr 4, c.z shr 4)
    }

    override fun getType(): StructurePlacementType<*> = HollowsWorldgen.SINGLE_ARENA

    companion object {
        @JvmField val CODEC: MapCodec<SingleArenaPlacement> = MapCodec.unit { SingleArenaPlacement() }
    }
}
