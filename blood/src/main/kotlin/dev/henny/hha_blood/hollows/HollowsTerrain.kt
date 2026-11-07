package dev.henny.hha_blood.hollows

import dev.henny.hha_blood.hollows.HollowsGen.FILL_TOP
import dev.henny.hha_blood.hollows.HollowsGen.LAKE_Y
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.LanternBlock
import net.minecraft.util.math.BlockPos
import net.minecraft.world.chunk.Chunk
import net.minecraft.world.gen.noise.NoiseConfig
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Builds the Vein Hollows terrain of one chunk. Called from `NoiseChunkGeneratorMixin` at the end of the
 * SURFACE step - i.e. before carvers and before any feature of this or a neighbouring chunk runs, so
 * decorations that reach over chunk borders are never cut off by it.
 *
 * Every region column is filled with blood stone from y 1 to [FILL_TOP] (Nether lava pockets are gone, the lava
 * sea above stays). Caves (round "Biom weird", Mats) are shaped per column, not as 3D noise blobs:
 *  - an open-area mask: big caverns (low frequency noise) linked by a network of winding galleries (the zero
 *    lines of two noises), so the caves are connected and navigable;
 *  - a floor height field with terraces (ledges, steps), sinking into blood lakes in places;
 *  - a ceiling height that grows from ~5 in galleries to ~16 in the middle of caverns, rounded towards the walls,
 *    bumpy through a little 3D noise;
 *  - flared pillars in the big caverns, stalactites and a few stalagmites;
 *  - arteries: thin glowing vein lines that run over walls/ceilings (instead of vein patches everywhere),
 *    clotted blood only on lake shores;
 *  - the old roads ([HollowsRoads]) from far away to the pit, paved, lit, with chevrons pointing to the pit.
 * Only in columns at least two biome cells (8 blocks) inside the region, so the caves have a solid shell.
 */
object HollowsTerrain {

    /** Highest carved block (ceiling at least 2 blocks of blood stone below the lava sea). */
    private const val CAVE_TOP = 23
    private const val CAVE_BOTTOM = 5

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
        val centre = HollowsRegion.center()
        val pos = BlockPos.Mutable()
        val carved = BooleanArray(FILL_TOP + 2)
        val stone = HollowsBlocks.BLOOD_STONE.defaultState
        val blood = HollowsFluids.SOURCE
        val extra = arrayOfNulls<BlockState>(FILL_TOP + 2)

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
            val edge = ((v - 8f) / 40f).coerceIn(0f, 1f).toDouble()

            java.util.Arrays.fill(carved, false)
            java.util.Arrays.fill(extra, null)
            var lakeShore = false
            if (inset && edge > 0.0) {
                val col = column(n, x, z, edge)
                if (col != null) {
                    for (y in CAVE_BOTTOM..CAVE_TOP) carved[y] = carvedAt(n, x, y, z, col)
                    lakeShore = col.floor <= LAKE_Y + 1
                }
                if (centre != null) road(centre, x, z, carved, extra)
            }

            for (y in 1..FILL_TOP) {
                pos.set(x, y, z)
                if (chunk.getBlockState(pos).isOf(Blocks.BEDROCK)) continue
                val e = extra[y]
                val state = when {
                    e != null -> e
                    carved[y] && y <= LAKE_Y -> blood
                    carved[y] -> Blocks.AIR.defaultState
                    // blood lakes get crusted shores
                    lakeShore && carved[y + 1] && y >= LAKE_Y && n.d.sample(x / 6.0, y / 6.0, z / 6.0) > -0.05 -> HollowsBlocks.CLOTTED_BLOOD.defaultState
                    y in CAVE_BOTTOM - 1..CAVE_TOP + 1 && artery(n, x, y, z) -> HollowsBlocks.VEIN_STONE.defaultState
                    else -> stone
                }
                chunk.setBlockState(pos, state, 0)
            }
        }
    }

    // ------------------------------------------------------------------------------------------- caves

    private class Column(val floor: Double, val ceil: Double, val bottom: Double, val top: Double, val cavern: Double) {
        // per column: pillar (distance, radius), stalactite length at this column, stalagmite length (0 = none)
        var pillarD = 99.0; var pillarR = 0.0
        var stalactite = 0.0; var stalagmite = 0.0
    }

    private fun smooth(t: Double): Double { val c = t.coerceIn(0.0, 1.0); return c * c * (3 - 2 * c) }

    /** Floor/ceiling of the cave in this column, null = solid rock. */
    private fun column(n: HollowsGen.Noises, x: Int, z: Int, edge: Double): Column? {
        // open-area mask: caverns + two networks of winding galleries
        val chamber = n.c.sample(x / 104.0, 0.0, z / 104.0) + 0.35 * n.a.sample(x / 37.0, 51.0, z / 37.0)
        val g1 = n.a.sample(x / 71.0, 11.3, z / 71.0)
        val g2 = n.b.sample(x / 53.0, 23.7, z / 53.0)
        val cav = (chamber - 0.06) / 0.22
        val gal = max((0.05 - abs(g1)) / 0.05, (0.042 - abs(g2)) / 0.042)
        var open = max(cav, gal * 0.9)
        // shrink towards the region border (solid shell)
        open = open * min(1.0, edge * 1.6) - (1.0 - edge) * 0.4
        if (open <= 0.0) return null
        val s = min(1.0, open * 1.5)

        // floor: rolling height field with terraces (ledges); dips into blood lakes
        val fr = 9.6 + 8.5 * n.d.sample(x / 58.0, 3.0, z / 58.0) + 2.6 * n.b.sample(x / 15.0, 40.0, z / 15.0)
        val t = fr / 3.0
        val tf = floor(t)
        val floorY = (3.0 * (tf + smooth((t - tf - 0.62) / 0.38))).coerceIn(CAVE_BOTTOM.toDouble(), 15.0)
        // ceiling: low in galleries, high in caverns
        val cavern = smooth((chamber + 0.02) / 0.4)
        val height = 5.0 + 11.5 * cavern + 2.2 * n.c.sample(x / 19.0, 70.0, z / 19.0) + 2.0 * smooth(open - 0.5)
        val ceil = min(CAVE_TOP + 0.5, floorY + max(4.2, height))
        val h = ceil - floorY
        // rounded cross-section towards the walls
        val bottom = floorY + (1 - s) * (1 - s) * h * 0.45
        val top = ceil - Math.pow(1 - s, 1.4) * h * 0.55
        if (top - bottom < 1.2) return null
        val c = Column(floorY, ceil, bottom, top, cavern)
        if (h >= 8.0 && cavern > 0.35) nearest(x, z, 22, 0.55, 1.4, 2.3)?.let { c.pillarD = it.first; c.pillarR = it.second }
        nearest(x, z, 6, 0.32, 0.0, 0.0)?.let { if (it.first < 1.35) c.stalactite = (1.5 + HollowsGen.rand(it.third.first, 1, it.third.second, 61) * min(6.0, h * 0.45)) * (1.0 - it.first / 1.35) }
        if (floorY > LAKE_Y + 1) nearest(x, z, 9, 0.16, 0.0, 0.0)?.let { if (it.first < 1.1) c.stalagmite = (1.0 + HollowsGen.rand(it.third.first, 2, it.third.second, 62) * min(3.5, h * 0.3)) * (1.0 - it.first / 1.1) }
        return c
    }

    private fun carvedAt(n: HollowsGen.Noises, x: Int, y: Int, z: Int, c: Column): Boolean {
        val bump = 1.7 * n.a.sample(x / 9.0, y / 6.0 + 100.0, z / 9.0)
        if (y + 0.5 < c.bottom - bump * 0.35 || y + 0.5 > c.top + bump) return false
        val h = c.ceil - c.floor
        // pillars (flared at floor and ceiling) in tall caverns, stalactites, a few stalagmites
        if (c.pillarR > 0.0) {
            val q = (y + 0.5 - (c.floor + c.ceil) / 2) / (h / 2)
            if (c.pillarD < c.pillarR * (1.0 + 1.5 * q * q * q * q)) return false
        }
        if (c.stalactite > 0.0 && y + 0.5 > c.top - c.stalactite) return false
        if (c.stalagmite > 0.0 && y + 0.5 < c.floor + c.stalagmite) return false
        return true
    }

    /**
     * Nearest jittered grid point (cell size [cell], existing with probability [chance]):
     * (distance, radius, cell point) or null.
     */
    private fun nearest(x: Int, z: Int, cell: Int, chance: Double, r0: Double, r1: Double): Triple<Double, Double, Pair<Int, Int>>? {
        val gx = Math.floorDiv(x, cell); val gz = Math.floorDiv(z, cell)
        var best: Triple<Double, Double, Pair<Int, Int>>? = null
        for (i in gx - 1..gx + 1) for (j in gz - 1..gz + 1) {
            if (HollowsGen.rand(i, cell, j, 71) >= chance) continue
            val px = i * cell + 1.5 + HollowsGen.rand(i, cell, j, 72) * (cell - 3)
            val pz = j * cell + 1.5 + HollowsGen.rand(i, cell, j, 73) * (cell - 3)
            val d = sqrt((x + 0.5 - px) * (x + 0.5 - px) + (z + 0.5 - pz) * (z + 0.5 - pz))
            if (best == null || d < best.first) best = Triple(d, r0 + HollowsGen.rand(i, cell, j, 74) * r1, Pair(px.toInt(), pz.toInt()))
        }
        return best
    }

    /** Glowing arteries: where a thin noise sheet cuts the rock, its trace on the cave surface is a vein line. */
    private fun artery(n: HollowsGen.Noises, x: Int, y: Int, z: Int): Boolean =
        abs(n.d.sample(x / 29.0 + 300.0, y / 21.0, z / 29.0)) < 0.015

    // ------------------------------------------------------------------------------------------- roads

    private val LANTERN: BlockState get() = Blocks.LANTERN.defaultState.with(LanternBlock.HANGING, false)

    /** Carves the old road through this column (overrides the caves): 5-6 high, causeway below, paved, lit. */
    private fun road(centre: BlockPos, x: Int, z: Int, carved: BooleanArray, extra: Array<BlockState?>) {
        val hit = HollowsRoads.at(centre, x, z) ?: return
        val side = abs(hit.side)
        if (side > hit.half + 1.2) return
        val f = hit.floor
        val inner = side <= hit.half
        val head = if (side < hit.half - 1.2) 6 else if (inner) 5 else 3
        for (y in f..min(f + head - 1, CAVE_TOP)) carved[y] = true
        // a causeway under the road (over lakes and dips)
        for (y in max(1, f - 3) until f) carved[y] = false
        if (!inner) {
            extra[f - 1] = HollowsBlocks.CHISELED_BLOOD_STONE.defaultState            // curb
            return
        }
        val u = hit.r - 8.0 * floor(hit.r / 8.0) - 4.0
        extra[f - 1] = when {
            side <= 1.7 && abs(u - side) < 0.55 -> HollowsBlocks.VEIN_STONE.defaultState   // chevrons pointing to the pit
            side < 1.2 -> HollowsBlocks.POLISHED_BLOOD_STONE.defaultState
            else -> HollowsBlocks.BLOOD_STONE_BRICKS.defaultState
        }
        // lamp posts every 16 blocks, alternating sides
        val seg = floor(hit.r / 16.0).toInt()
        val postSide = if (seg % 2 == 0) 1.0 else -1.0
        if (hit.r - seg * 16.0 < 1.0 && hit.side * postSide > 0 && side in (hit.half - 1.3)..(hit.half - 0.3)) {
            extra[f] = HollowsBlocks.BLOOD_STONE_BRICK_WALL.defaultState
            extra[f + 1] = LANTERN
        }
    }
}
