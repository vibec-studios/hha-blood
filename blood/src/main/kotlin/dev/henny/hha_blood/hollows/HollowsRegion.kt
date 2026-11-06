package dev.henny.hha_blood.hollows

import dev.henny.hha_blood.HhaBlood
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import net.minecraft.world.biome.Biome
import net.minecraft.world.biome.source.util.MultiNoiseUtil
import java.util.Random
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the Vein Hollows are: there is exactly ONE region per world (Mats), around one Heart Vault arena,
 * 10 000 - 15 000 blocks from 0/0 in the Nether.
 *
 * The arena centre is derived from the world seed (direction + distance) and then moved to the nearest
 * spot (spiral search in chunk steps) where the entrance shaft can reach open Nether air - checked with
 * the noise-only column sample of the Nether generator, so it is known before any chunk exists and is
 * the same every time the world is loaded. Around it, every biome cell whose distance is below
 * [BASE_RADIUS] + noise belongs to the region (irregular blob, ~1 km across).
 *
 * Vanilla Nether biomes do not depend on height, so the biome is forced by a mixin on the Nether's
 * multi-noise biome source (`MultiNoiseBiomeSourceMixin`) below [TOP_Y]. The edge noise is the Nether's
 * own (seeded) temperature noise at shifted coordinates.
 *
 * Coordinates of the `cell*` functions are biome coordinates ("quarts", block >> 2).
 */
object HollowsRegion {
    @JvmField val BIOME: RegistryKey<Biome> = RegistryKey.of(RegistryKeys.BIOME, HhaBlood.id("vein_hollows"))

    /** Biome is forced below this block height (cave ceiling is far lower, the rest is lava sea + red fog above it). */
    const val TOP_Y = 64
    @JvmField val TOP_QUART = TOP_Y shr 2

    const val MIN_DIST = 10_700.0
    const val MAX_DIST = 14_300.0
    /** Region radius (blocks) = BASE_RADIUS + EDGE_NOISE * noise(-1..1). */
    const val BASE_RADIUS = 470.0
    const val EDGE_NOISE = 260.0
    private const val MAX_RADIUS = BASE_RADIUS + EDGE_NOISE + 8

    private const val SHIFT_X = 9001
    private const val SHIFT_Z = -7013

    @Volatile private var server: MinecraftServer? = null
    @Volatile private var center: BlockPos? = null
    private val lock = Any()
    private val computing: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    fun init() {
        ServerLifecycleEvents.SERVER_STARTING.register { s -> server = s; center = null }
        ServerLifecycleEvents.SERVER_STOPPED.register { server = null; center = null }
    }

    /**
     * Centre of THE Heart Vault (walking level y = arena floor), chunk-centre aligned. Null only while no
     * server / Nether exists yet.
     */
    @JvmStatic
    fun center(): BlockPos? {
        center?.let { return it }
        if (computing.get()) return null   // re-entrant call from the column sampling below
        synchronized(lock) {
            center?.let { return it }
            val s = server ?: return null
            val nether = s.getWorld(World.NETHER) ?: return null
            val seed = s.saveProperties.generatorOptions.seed
            computing.set(true)
            val c = try { findCenter(seed, nether) } finally { computing.set(false) }
            center = c
            HhaBlood.LOGGER.info("Vein Hollows / Heart Vault at {} {} (Nether)", c.x, c.z)
            return c
        }
    }

    private fun findCenter(seed: Long, nether: net.minecraft.server.world.ServerWorld): BlockPos {
        val r = Random(seed xor 0x5A4E6C1EL)
        val ang = r.nextDouble() * Math.PI * 2
        val dist = MIN_DIST + 400 + r.nextDouble() * (MAX_DIST - MIN_DIST - 800)
        val x0 = (cos(ang) * dist).toInt()
        val z0 = (sin(ang) * dist).toInt()
        val gen = nether.chunkManager.chunkGenerator
        val noise = nether.chunkManager.noiseConfig
        // search ring by ring (chunk steps, up to 25 chunks = 400 blocks away from the seed point)
        val cx = x0 shr 4
        val cz = z0 shr 4
        for (ring in 0..25) for (i in -ring..ring) for (j in -ring..ring) {
            if (maxOf(kotlin.math.abs(i), kotlin.math.abs(j)) != ring) continue
            val c = BlockPos(((cx + i) shl 4) + 8, HeartVaultPiece.FLOOR, ((cz + j) shl 4) + 8)
            val shaft = HeartVaultPiece.shaftCenter(c)
            if (HeartVaultStructure.shaftOpen(gen, shaft.x, shaft.z, nether, noise)) return c
        }
        return BlockPos(((x0 shr 4) shl 4) + 8, HeartVaultPiece.FLOOR, ((z0 shr 4) shl 4) + 8)
    }

    @JvmStatic
    fun cellValue(sampler: MultiNoiseUtil.MultiNoiseSampler, qx: Int, qz: Int): Float =
        MultiNoiseUtil.toFloat(sampler.sample(qx + SHIFT_X, 0, qz + SHIFT_Z).temperatureNoise())

    /** Blocks inside the region border at this cell (negative = outside). Cheap far away from the region. */
    @JvmStatic
    fun field(sampler: MultiNoiseUtil.MultiNoiseSampler, qx: Int, qz: Int): Float {
        val c = center() ?: return -1000f
        val dx = (qx * 4 + 2 - c.x).toDouble()
        val dz = (qz * 4 + 2 - c.z).toDouble()
        val d2 = dx * dx + dz * dz
        if (d2 > MAX_RADIUS * MAX_RADIUS) return -100f
        val n = cellValue(sampler, qx, qz).toDouble().coerceIn(-1.0, 1.0)
        return (BASE_RADIUS + EDGE_NOISE * n - sqrt(d2)).toFloat()
    }

    @JvmStatic
    fun isCell(sampler: MultiNoiseUtil.MultiNoiseSampler, qx: Int, qz: Int): Boolean = field(sampler, qx, qz) > 0f

    /** Smooth field value at a block column (bilinear between the cell samples). */
    fun blockField(sampler: MultiNoiseUtil.MultiNoiseSampler, x: Int, z: Int): Float {
        val fx = x / 4.0 - 0.5
        val fz = z / 4.0 - 0.5
        val qx = floor(fx).toInt()
        val qz = floor(fz).toInt()
        val tx = (fx - qx).toFloat()
        val tz = (fz - qz).toFloat()
        val a = field(sampler, qx, qz)
        val b = field(sampler, qx + 1, qz)
        val c = field(sampler, qx, qz + 1)
        val d = field(sampler, qx + 1, qz + 1)
        return (a * (1 - tx) + b * tx) * (1 - tz) + (c * (1 - tx) + d * tx) * tz
    }
}
