package dev.henny.hha_blood.hollows

import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.state.property.Properties
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.noise.PerlinNoiseSampler
import net.minecraft.util.math.random.Random
import net.minecraft.world.StructureWorldAccess
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

/** Shared helpers for the Vein Hollows generators (deterministic hashing, noise, the entrance shafts). */
object HollowsGen {
    /** Cave space: carved between these heights, the region is filled with blood stone from 1 to [FILL_TOP]. */
    const val CAVE_MIN = 6
    const val CAVE_MAX = 22
    const val FILL_TOP = 26
    /** Carved space at or below this height becomes blood lakes (tinted water, see the biome water colour). */
    const val LAKE_Y = 7

    const val FLAGS = 2 // NOTIFY_LISTENERS only - no neighbour updates during generation

    fun hash(x: Int, y: Int, z: Int, salt: Int = 0): Long {
        var h = x * 3129871L xor (z * 116129781L) xor (y * 6542989L) xor (salt * 0x9E3779B97F4A7C15uL.toLong())
        h = h * h * 42317861L + h * 11L
        h = h xor (h ushr 29)
        h *= -0x40a7b892e31b1a47L
        return h xor (h ushr 32)
    }

    /** Uniform 0..1 from a position. */
    fun rand(x: Int, y: Int, z: Int, salt: Int = 0): Double = ((hash(x, y, z, salt) ushr 11).toDouble() / (1L shl 53).toDouble())

    class Noises(seed: Long) {
        private val r = Random.create(seed xor 0x48484121L)
        val a = PerlinNoiseSampler(r)
        val b = PerlinNoiseSampler(r)
        val c = PerlinNoiseSampler(r)
        val d = PerlinNoiseSampler(r)
    }

    @Volatile private var cached: Pair<Long, Noises>? = null

    fun noises(seed: Long): Noises {
        val c = cached
        if (c != null && c.first == seed) return c.second
        val n = Noises(seed)
        cached = seed to n
        return n
    }

    fun set(world: StructureWorldAccess, pos: BlockPos, state: BlockState, clip: BlockBox?) {
        if (clip != null && !clip.contains(pos)) return
        world.setBlockState(pos, state, FLAGS)
    }

    fun isAir(world: StructureWorldAccess, x: Int, y: Int, z: Int) = world.getBlockState(BlockPos(x, y, z)).isAir

    /**
     * Arena centres of all Heart Vaults that reference the chunks around [pos] (structure references are
     * known long before features run).
     */
    fun arenasNear(world: StructureWorldAccess, pos: BlockPos): List<BlockPos> {
        val out = ArrayList<BlockPos>(1)
        val pcx = pos.x shr 4
        val pcz = pos.z shr 4
        for (i in -1..1) for (j in -1..1) {
            if (!world.isChunkLoaded(pcx + i, pcz + j)) continue
            val chunk = world.getChunk(pcx + i, pcz + j)
            for ((structure, starts) in chunk.structureReferences) {
                if (structure.type !== HollowsWorldgen.HEART_VAULT) continue
                val it = starts.iterator()
                while (it.hasNext()) {
                    val cp = net.minecraft.util.math.ChunkPos(it.nextLong())
                    val c = BlockPos(cp.centerX, HeartVaultPiece.FLOOR, cp.centerZ)
                    if (c !in out) out += c
                }
            }
        }
        return out
    }

    /** True if [pos] lies in or within [margin] blocks of a nearby Heart Vault arena. */
    fun nearArena(world: StructureWorldAccess, pos: BlockPos, margin: Int): Boolean =
        arenasNear(world, pos).any { HeartVaultPiece.occupied(it, pos.x, pos.y, pos.z, margin) }

    /** Lowest height >= 32 (above the lava sea) with 3 blocks of air - where a shaft can come out. -1 if none. */
    fun findOpening(world: StructureWorldAccess, x: Int, z: Int): Int {
        for (y in 32..110) {
            if (isAir(world, x, y, z) && isAir(world, x, y + 1, z) && isAir(world, x, y + 2, z)) return y
        }
        return -1
    }

    /**
     * An entrance shaft ("Ader"): blood stone tube with a spiral stair, from a cave floor at [bottom] up
     * to [top] (first open air above the lava sea), crowned with bone spikes and blood crystals.
     * The lowest 4 blocks are open between four legs, so the shaft opens into the cave/tunnel.
     * Centre column has a two-deep blood pool at the bottom to land in when jumping down.
     * Above the pool a column of rising blood carries you up (sneak to sink slowly, no fall damage).
     */
    fun buildShaft(world: StructureWorldAccess, cx: Int, cz: Int, bottom: Int, top: Int, clip: BlockBox?) {
        val pos = BlockPos.Mutable()
        val stepStart = bottom + 1
        for (dx in -7..7) for (dz in -7..7) {
            val r = sqrt((dx * dx + dz * dz).toDouble())
            if (r >= 6.6) continue
            var ang = atan2(dz.toDouble(), dx.toDouble())
            if (ang < 0) ang += 2 * PI
            val frac = ang / (2 * PI)
            val sector = floor(frac * 16).toInt()
            val stripe = abs((ang * 3 / PI) % 1.0 - 0.5) < 0.09
            val leg = abs(((ang + PI / 4) / (PI / 2)) % 1.0 - 0.5) < 0.16
            val spikeAt = abs((ang / (PI / 3)) % 1.0 - 0.5) > 0.4 // 6 spikes
            val spikeLen = 3 + (hash(cx + dx, 0, cz + dz, 77) and 3L).toInt()
            for (y in bottom - 2..top + 9) {
                pos.set(cx + dx, y, cz + dz)
                val state: BlockState? = when {
                    // pool + floor
                    y < bottom - 1 -> if (r < 3.0) HollowsBlocks.BLOOD_STONE_BRICKS.defaultState else null
                    y == bottom - 1 -> when {
                        r < 1.6 -> HollowsFluids.SOURCE
                        r < 4.6 -> HollowsBlocks.POLISHED_BLOOD_STONE.defaultState
                        else -> null
                    }
                    y == bottom && r < 1.6 -> HollowsFluids.SOURCE
                    y == bottom && r < 2.6 -> HollowsBlocks.BLOOD_STONE_BRICKS.defaultState
                    // wall (open at the bottom between 4 legs)
                    r >= 3.0 && r < 4.6 && y <= top + 2 ->
                        if (y < bottom + 4 && !leg) Blocks.AIR.defaultState
                        else if (stripe) HollowsBlocks.VEIN_STONE.defaultState
                        else HollowsBlocks.BLOOD_STONE_BRICKS.defaultState
                    // rim
                    r >= 4.6 && r < 5.6 && y in top..top + 2 -> HollowsBlocks.BLOOD_STONE_BRICKS.defaultState
                    r >= 3.0 && r < 5.6 && y == top + 3 -> HollowsBlocks.CHISELED_BLOOD_STONE.defaultState
                    // crown spikes
                    spikeAt && r >= 4.0 && r < 5.2 && y > top + 3 && y <= top + 3 + spikeLen -> Blocks.BONE_BLOCK.defaultState
                    spikeAt && r >= 4.0 && r < 5.2 && y == top + 4 + spikeLen -> HollowsBlocks.BLOOD_CRYSTAL_CLUSTER.defaultState
                    // the middle: a column of rising blood - an elevator up to the Nether (sneak to sink slowly)
                    r < 1.6 && y > bottom && y <= top + 2 -> PitBlocks.BLOOD_FALL.defaultState.with(BloodFallBlock.RISING, true)
                    // inside: spiral steps, otherwise air
                    r < 3.0 && y > bottom && y <= top + 2 ->
                        if (r >= 1.6 && Math.floorMod(y - stepStart - sector, 16) == 0) HollowsBlocks.POLISHED_BLOOD_STONE.defaultState
                        else Blocks.AIR.defaultState
                    r < 3.0 && y > top + 2 && y <= top + 8 -> Blocks.AIR.defaultState
                    else -> null
                }
                if (state != null) set(world, pos.toImmutable(), state, clip)
            }
        }
    }
}
