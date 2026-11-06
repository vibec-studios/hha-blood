package dev.henny.hha_blood.hollows

import dev.henny.hha_blood.hollows.HollowsGen.CAVE_MAX
import dev.henny.hha_blood.hollows.HollowsGen.CAVE_MIN
import dev.henny.hha_blood.hollows.HollowsGen.FILL_TOP
import dev.henny.hha_blood.hollows.HollowsGen.LAKE_Y
import net.minecraft.block.Blocks
import net.minecraft.util.math.BlockPos
import net.minecraft.world.chunk.Chunk
import net.minecraft.world.gen.noise.NoiseConfig

/**
 * Builds the Vein Hollows terrain of one chunk. Called from `NoiseChunkGeneratorMixin` at the end of the
 * SURFACE step - i.e. before carvers and before any feature of this or a neighbouring chunk runs, so
 * decorations that reach over chunk borders are never cut off by it (that was the "cut off" bug when
 * this was a raw-generation feature).
 *
 * Every region column is filled with blood stone from y 1 to [FILL_TOP] (Nether lava pockets are gone,
 * the lava sea above stays); caves are carved between [CAVE_MIN] and [CAVE_MAX], only in columns at least
 * two biome cells (8 blocks) inside the region, so the caves always have a solid shell. The Nether cave
 * carvers cannot cut into it (blood stone is not carver-replaceable).
 */
object HollowsTerrain {

    fun apply(seed: Long, noiseConfig: NoiseConfig, chunk: Chunk) {
        val cp = chunk.pos
        val cx = cp.startX
        val cz = cp.startZ
        // only chunks that actually contain the biome (i.e. the vanilla Nether preset)
        var hasBiome = false
        for (i in 0..3) for (j in 0..3) {
            if (chunk.getBiomeForNoiseGen((cx shr 2) + i, 2, (cz shr 2) + j).matchesKey(HollowsRegion.BIOME)) hasBiome = true
        }
        if (!hasBiome) return

        val sampler = noiseConfig.multiNoiseSampler
        val q0x = (cx shr 2) - 3
        val q0z = (cz shr 2) - 3
        val cells = Array(10) { i -> FloatArray(10) { j -> HollowsRegion.field(sampler, q0x + i, q0z + j) } }
        fun inCell(i: Int, j: Int) = cells[i][j] > 0f

        val n = HollowsGen.noises(seed)
        val pos = BlockPos.Mutable()
        val carved = BooleanArray(FILL_TOP + 2)
        val stone = HollowsBlocks.BLOOD_STONE.defaultState
        val blood = HollowsFluids.SOURCE

        for (lx in 0..15) for (lz in 0..15) {
            val x = cx + lx
            val z = cz + lz
            val ci = (lx shr 2) + 3
            val cj = (lz shr 2) + 3
            if (!inCell(ci, cj)) continue
            var inset = true
            for (i in ci - 2..ci + 2) for (j in cj - 2..cj + 2) if (!inCell(i, j)) inset = false

            val fx = x / 4.0 - 0.5 - q0x
            val fz = z / 4.0 - 0.5 - q0z
            val ix = fx.toInt(); val iz = fz.toInt()
            val tx = (fx - ix).toFloat(); val tz = (fz - iz).toFloat()
            val v = (cells[ix][iz] * (1 - tx) + cells[ix + 1][iz] * tx) * (1 - tz) +
                (cells[ix][iz + 1] * (1 - tx) + cells[ix + 1][iz + 1] * tx) * tz
            // round off the 4x4 biome-cell steps of the region border (only ever shrinks the filled area)
            if (v < -2.5f + 2.0f * n.d.sample(x / 5.0, 0.0, z / 5.0).toFloat()) continue
            val edge = ((v - 8f) / 40f).coerceIn(0f, 1f)

            val chamber = n.c.sample(x / 72.0, 0.0, z / 72.0).coerceAtLeast(0.0)
            for (y in 1..FILL_TOP) carved[y] = inset && y in CAVE_MIN..CAVE_MAX && density(n, x, y, z, edge, chamber)
            carved[0] = false; carved[FILL_TOP + 1] = false

            for (y in 1..FILL_TOP) {
                pos.set(x, y, z)
                if (chunk.getBlockState(pos).isOf(Blocks.BEDROCK)) continue
                val state = when {
                    carved[y] && y <= LAKE_Y -> blood
                    carved[y] -> Blocks.AIR.defaultState
                    carved[y + 1] && y >= LAKE_Y &&
                        n.d.sample(x / 9.0, y / 9.0, z / 9.0) > 0.12 -> HollowsBlocks.CLOTTED_BLOOD.defaultState
                    y > 1 && carved[y - 1] && n.d.sample(x / 7.0, y / 5.0 + 40.0, z / 7.0) > 0.28 -> HollowsBlocks.VEIN_STONE.defaultState
                    else -> stone
                }
                chunk.setBlockState(pos, state, 0)
            }
        }
    }

    private fun density(n: HollowsGen.Noises, x: Int, y: Int, z: Int, edge: Float, chamber: Double): Boolean {
        if (edge <= 0f) return false
        val d = n.a.sample(x / 30.0, y / 13.0, z / 30.0) * 0.7 + n.b.sample(x / 12.0, y / 7.0, z / 12.0) * 0.3
        val yh = (y - 14.0) / 8.5
        val bias = yh * yh * 0.45
        val threshold = 0.05 - chamber * 0.45 + (1.0 - edge) * 0.5
        return d - bias > threshold
    }
}
