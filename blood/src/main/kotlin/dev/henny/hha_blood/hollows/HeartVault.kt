package dev.henny.hha_blood.hollows

import com.mojang.serialization.MapCodec
import net.minecraft.block.AmethystClusterBlock
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.nbt.NbtCompound
import net.minecraft.structure.StructureContext
import net.minecraft.structure.StructurePiece
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.random.Random
import net.minecraft.world.StructureWorldAccess
import net.minecraft.world.gen.StructureAccessor
import net.minecraft.world.gen.chunk.ChunkGenerator
import net.minecraft.world.gen.structure.Structure
import net.minecraft.world.gen.structure.StructureType
import java.util.Optional
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Das Herzgewölbe" - the boss arena: a huge rib-vaulted dome under the lava sea with a blood lake,
 * the Blood Prince's throne + sacrificial altar in the north, a petrified heart hanging from the
 * apex, three tunnels and an entrance shaft (south) up to the open Nether.
 */
class HeartVaultStructure(config: Config) : Structure(config) {

    override fun getStructurePosition(context: Context): Optional<StructurePosition> {
        // there is exactly one arena per world (see HollowsRegion.center)
        val c = HollowsRegion.center() ?: return Optional.empty()
        val cp = context.chunkPos()
        if (cp.centerX != c.x || cp.centerZ != c.z) return Optional.empty()
        return Optional.of(StructurePosition(c) { it.addPiece(HeartVaultPiece(c)) })
    }
    override fun getType(): StructureType<*> = HollowsWorldgen.HEART_VAULT

    companion object {
        @JvmField val CODEC: MapCodec<HeartVaultStructure> = createCodec(::HeartVaultStructure)

        /** True if the entrance shaft at x/z comes out in open Nether air (noise-only column, no chunk needed). */
        fun shaftOpen(gen: net.minecraft.world.gen.chunk.ChunkGenerator, x: Int, z: Int, world: net.minecraft.world.HeightLimitView, noise: net.minecraft.world.gen.noise.NoiseConfig): Boolean {
            val column = gen.getColumnSample(x, z, world, noise)
            var run = 0
            for (y in 32..100) {
                run = if (column.getState(y).isAir) run + 1 else 0
                if (run >= 4) return true
            }
            return false
        }
    }
}

class HeartVaultPiece : StructurePiece {
    val origin: BlockPos

    constructor(origin: BlockPos) : super(HollowsWorldgen.HEART_VAULT_PIECE, 0, box(origin)) {
        this.origin = origin
    }

    constructor(nbt: NbtCompound) : super(HollowsWorldgen.HEART_VAULT_PIECE, nbt) {
        origin = BlockPos(nbt.getInt("cx", 0), nbt.getInt("cy", FLOOR), nbt.getInt("cz", 0))
    }

    override fun writeNbt(context: StructureContext, nbt: NbtCompound) {
        nbt.putInt("cx", origin.x); nbt.putInt("cy", origin.y); nbt.putInt("cz", origin.z)
    }

    override fun generate(
        world: StructureWorldAccess, structureAccessor: StructureAccessor, chunkGenerator: ChunkGenerator,
        random: Random, chunkBox: BlockBox, chunkPos: ChunkPos, pivot: BlockPos,
    ) {
        val pos = BlockPos.Mutable()
        val x0 = max(chunkBox.minX, boundingBox.minX); val x1 = min(chunkBox.maxX, boundingBox.maxX)
        val z0 = max(chunkBox.minZ, boundingBox.minZ); val z1 = min(chunkBox.maxZ, boundingBox.maxZ)
        val y0 = max(chunkBox.minY, boundingBox.minY); val y1 = min(chunkBox.maxY, boundingBox.maxY)
        for (x in x0..x1) for (z in z0..z1) {
            val dx = x - origin.x
            val dz = z - origin.z
            val r = sqrt((dx * dx + dz * dz).toDouble())
            if (r > R + SHELL + 1 && !nearTunnel(dx, dz)) continue
            for (y in y0..min(y1, FLOOR + (H + SHELL).toInt() + 2)) {
                val state = blockAt(dx, y - FLOOR, dz, r) ?: continue
                pos.set(x, y, z)
                world.setBlockState(pos, state, HollowsGen.FLAGS)
            }
        }
        val shaft = shaftCenter(origin)
        if (chunkBox.contains(BlockPos(shaft.x, chunkBox.minY, shaft.z))) {
            val top = HollowsGen.findOpening(world, shaft.x, shaft.z)
            if (top > 0) HollowsGen.buildShaft(world, shaft.x, shaft.z, FLOOR, top, chunkBox)
        }
    }

    private fun nearTunnel(dx: Int, dz: Int): Boolean {
        if (abs(dz) <= 5 && abs(dx) <= R + SIDE_TUNNEL + 9) return true          // east / west
        if (abs(dx) <= 3 && dz in 0..(shaftCenter(origin).z - origin.z)) return true // south
        return false
    }

    // ------------------------------------------------------------------------------------- geometry

    /** Block for a position relative to the centre (dy = 0 is the walking level), null = keep. */
    private fun blockAt(dx: Int, dy: Int, dz: Int, r: Double): BlockState? {
        alcove(dx, dy, dz)?.let { return it }
        tunnel(dx, dy, dz, r)?.let { return it }
        if (dy < -6) return null
        val bricks = HollowsBlocks.BLOOD_STONE_BRICKS.defaultState
        // ----- foundation / floor
        if (dy < 0) {
            if (r >= R + SHELL) return null
            // small ritual basin of blood in the middle (one block deep) instead of the old lake
            if (r < POOL_R) return if (dy == -1) HollowsFluids.SOURCE else HollowsBlocks.BLOOD_STONE.defaultState
            if (dy < -1) return HollowsBlocks.BLOOD_STONE.defaultState
            return floorBlock(dx, dz, r)
        }
        val e = ell(r, dy.toDouble(), R, H)
        if (e >= 1.0) {
            if (ell(r, dy.toDouble(), R + SHELL, H + SHELL) >= 1.0) return null
            if (ell(r, dy.toDouble(), R + 1, H + 1) < 1.0) {
                return if (vein(dx, dy, dz)) HollowsBlocks.VEIN_STONE.defaultState else bricks
            }
            return HollowsBlocks.BLOOD_STONE.defaultState
        }
        // ----- inside the dome
        heart(dx, dy, dz)?.let { return it }
        throne(dx, dy, dz)?.let { return it }
        // low rim around the basin
        if (dy == 0 && r >= POOL_R && r < POOL_R + 1.0) return HollowsBlocks.BLOOD_STONE_BRICK_SLAB.defaultState
        if (dy == 0 && r in (R - 8.0)..(R - 3.0) && HollowsGen.rand(dx, 0, dz, 5) < 0.03) {
            return HollowsBlocks.BLOOD_CRYSTAL_CLUSTER.defaultState
        }
        // ribs along the inner wall + a bone ring where they end
        if (ell(r, dy.toDouble(), R - 2.2, H - 2.2) >= 1.0 && dy <= H - 6) {
            if (dy >= H - 8 && dy <= H - 7) return Blocks.BONE_BLOCK.defaultState
            val ang = atan2(dz.toDouble(), dx.toDouble())
            for (k in 0 until 10) {
                val ra = (18.0 + 36.0 * k) * PI / 180
                if (angDiff(ang, ra) * r < 1.15) return Blocks.BONE_BLOCK.defaultState
            }
        }
        return Blocks.AIR.defaultState
    }

    private fun floorBlock(dx: Int, dz: Int, r: Double): BlockState {
        if (r < POOL_R + 1.0) return HollowsBlocks.BLOOD_STONE_BRICKS.defaultState
        if (dx == 0 && dz in (-18 - THRONE_SHIFT)..-5) return HollowsBlocks.VEIN_STONE.defaultState       // vein path to the altar
        if (r >= R) return HollowsBlocks.BLOOD_STONE_BRICKS.defaultState
        // rune rings
        if (r in 7.0..8.0 || r in 18.0..19.0 || r in 31.0..32.0) return HollowsBlocks.CHISELED_BLOOD_STONE.defaultState
        val ang = atan2(dz.toDouble(), dx.toDouble())
        if (r > 8.0) for (k in 0 until 12) {
            if (angDiff(ang, k * PI / 6 + PI / 12) * r < 0.55) return HollowsBlocks.VEIN_STONE.defaultState
        }
        return HollowsBlocks.POLISHED_BLOOD_STONE.defaultState
    }

    private fun vein(dx: Int, dy: Int, dz: Int): Boolean {
        val ang = atan2(dz.toDouble(), dx.toDouble())
        val f = (ang / (2 * PI) * 10 + 0.5 + 0.06 * sin(dy * 0.33 + ang * 3)) % 1.0
        val ff = if (f < 0) f + 1 else f
        if (abs(ff - 0.5) < 0.035) return true
        // a few side branches
        return HollowsGen.rand(dx, dy, dz, 9) < 0.03
    }

    private fun heart(dx: Int, dy: Int, dz: Int): BlockState? {
        val hy = H - 7.0
        val d = sqrt(dx * dx + (dy - hy) * (dy - hy) * 1.15 + dz * dz.toDouble())
        if (d < 3.3) return HollowsBlocks.BLOOD_CRYSTAL_BLOCK.defaultState
        if (d < 4.6) return HollowsBlocks.VEIN_STONE.defaultState
        if (dy > hy && dx * dx + dz * dz < 2.6) return HollowsBlocks.VEIN_STONE.defaultState // stalk to the apex
        if (dx == 0 && dz == 0 && dy == (hy - 5).toInt()) return HollowsBlocks.BLOOD_CRYSTAL_CLUSTER.defaultState.with(AmethystClusterBlock.FACING, Direction.DOWN)
        // four arteries down to the wall
        for (k in 0 until 4) {
            val a = PI / 4 + k * PI / 2
            val wy = H * 0.42
            val wr = R * sqrt(1 - (wy / H) * (wy / H)) + 1
            if (segDist(dx.toDouble(), dy.toDouble(), dz.toDouble(), 0.0, hy, 0.0, cos(a) * wr, wy, sin(a) * wr) < 1.35) {
                return HollowsBlocks.VEIN_STONE.defaultState
            }
        }
        return null
    }

    /** Stepped dais with throne in the north (-Z), sacrificial altar in front of it. */
    private fun throne(dx: Int, dy: Int, dz0: Int): BlockState? {
        val dz = dz0 + THRONE_SHIFT   // laid out for the old radius 30, shifted to the north wall
        val ax = abs(dx)
        val polished = HollowsBlocks.POLISHED_BLOOD_STONE.defaultState
        val bricks = HollowsBlocks.BLOOD_STONE_BRICKS.defaultState
        val chiseled = HollowsBlocks.CHISELED_BLOOD_STONE.defaultState
        if (dx == 0 && dz == -17 && dy == 0) return HollowsBlocks.SANGUINE_ALTAR.defaultState
        if (ax == 9 && dz == -24) {
            if (dy in 0..7) return Blocks.BONE_BLOCK.defaultState
            if (dy == 8) return HollowsBlocks.BLOOD_CRYSTAL_CLUSTER.defaultState
        }
        // dais levels
        if (dy == 0 && ax <= 7 && dz in -29..-19) return if (dz == -19 || ax == 7) polished else bricks
        if (dy == 1 && ax <= 6 && dz in -29..-21) return if (dz == -21 || ax == 6) polished else bricks
        if (dy == 2 && ax <= 5 && dz in -29..-23) return if (dz == -23 || ax == 5) polished else chiseled
        // throne
        if (ax <= 1 && dz in -26..-25 && dy == 3) return polished                       // seat
        if (ax <= 1 && dz == -27 && dy in 3..8) return if (dy == 8) chiseled else bricks // backrest
        if (dx == 0 && dz == -27 && dy == 9) return HollowsBlocks.BLOOD_CRYSTAL_CLUSTER.defaultState
        if (ax == 2 && dz in -27..-25 && dy in 3..4) return polished                     // arm rests
        if (ax == 2 && dz == -27 && dy in 5..9) return Blocks.BONE_BLOCK.defaultState     // crown spikes
        if (ax == 2 && dz == -27 && dy == 10) return HollowsBlocks.SMALL_BLOOD_CRYSTAL.defaultState
        if (ax <= 2 && dz == -28 && dy in 3..6) return bricks
        return null
    }

    /** Small round chambers at the end of the east/west tunnels (blood crystal in the middle). */
    private fun alcove(dx: Int, dy: Int, dz: Int): BlockState? {
        val along = abs(dx) - (R.toInt() + SIDE_TUNNEL + 3)
        if (abs(along) > 5 || abs(dz) > 5 || dy < -2 || dy > 7) return null
        val d = sqrt((along * along + (dy - 2) * (dy - 2) * 1.3 + dz * dz).toDouble())
        return when {
            d >= 4.8 -> null
            dy <= -1 -> if (dy == -1 && d < 4.0) HollowsBlocks.POLISHED_BLOOD_STONE.defaultState else HollowsBlocks.BLOOD_STONE_BRICKS.defaultState
            d >= 3.8 -> if (HollowsGen.rand(dx, dy, dz, 3) < 0.25) HollowsBlocks.VEIN_STONE.defaultState else HollowsBlocks.BLOOD_STONE_BRICKS.defaultState
            along == 0 && dz == 0 && dy == 0 -> HollowsBlocks.BLOOD_CRYSTAL_CLUSTER.defaultState
            else -> Blocks.AIR.defaultState
        }
    }

    /** East/west side tunnels and the south tunnel to the entrance shaft. */
    private fun tunnel(dx: Int, dy: Int, dz: Int, r: Double): BlockState? {
        if (dy < -1 || dy > 5) return null
        val southEnd = shaftCenter(origin).z - origin.z
        val along: Int
        val lat: Int
        when {
            dz >= 0 && abs(dx) <= 2 && dz in (R.toInt() - 3)..southEnd -> { along = dz; lat = abs(dx) }
            abs(dz) <= 2 && abs(dx) in (R.toInt() - 3)..(R.toInt() + SIDE_TUNNEL) -> { along = abs(dx); lat = abs(dz) }
            else -> return null
        }
        if (r < R - 2.5) return null
        if (along > R + 3 && dy == 5) return HollowsBlocks.BLOOD_STONE_BRICKS.defaultState
        return when {
            dy == -1 -> if (lat <= 1) HollowsBlocks.POLISHED_BLOOD_STONE.defaultState else HollowsBlocks.BLOOD_STONE_BRICKS.defaultState
            lat == 2 -> if (along > R + 3) (if (along % 6 == 0 && dy == 2) HollowsBlocks.VEIN_STONE.defaultState else HollowsBlocks.BLOOD_STONE_BRICKS.defaultState) else null
            dy == 4 && lat == 1 -> if (along > R + 3) HollowsBlocks.CHISELED_BLOOD_STONE.defaultState else Blocks.AIR.defaultState
            dy == 5 -> null
            else -> Blocks.AIR.defaultState
        }
    }

    companion object {
        const val FLOOR = 10
        const val R = 44.0
        const val H = 42.0
        const val SHELL = 3.0
        /** The throne/dais/altar were designed for radius 30 - this moves them to the north wall. */
        const val THRONE_SHIFT = 14
        /** Ritual basin of blood in the middle (Mats: the big lake was too much). */
        const val POOL_R = 3.2
        const val SIDE_TUNNEL = 16

        fun shaftCenter(origin: BlockPos): BlockPos {
            val z = origin.z + R.toInt() + 14
            return BlockPos(origin.x, FLOOR, ((z shr 4) shl 4) + 8)
        }

        /**
         * True if (x, y, z) belongs to the arena centred at [origin] (dome, tunnels, alcoves, shaft) or lies
         * within [margin] blocks of it. Decoration features use this to keep out of the arena.
         */
        fun occupied(origin: BlockPos, x: Int, y: Int, z: Int, margin: Int): Boolean {
            val dx = x - origin.x
            val dz = z - origin.z
            val dy = y - FLOOR
            val r = sqrt((dx * dx + dz * dz).toDouble())
            if (dy >= -8 - margin && ell(r, max(dy, 0).toDouble(), R + SHELL + margin, H + SHELL + margin) < 1.0) return true
            if (dy in (-3 - margin)..(9 + margin)) {
                if (abs(dz) <= 6 + margin && abs(dx) <= R + SIDE_TUNNEL + 9 + margin) return true
                val southEnd = shaftCenter(origin).z - origin.z
                if (abs(dx) <= 6 + margin && dz in 0..(southEnd + margin)) return true
            }
            val s = shaftCenter(origin)
            val sdx = x - s.x
            val sdz = z - s.z
            return y >= FLOOR - 4 - margin && sdx * sdx + sdz * sdz <= (8 + margin) * (8 + margin)
        }

        private fun box(origin: BlockPos): BlockBox {
            val shaft = shaftCenter(origin)
            val rr = (R + SIDE_TUNNEL + 9).toInt()
            return BlockBox(origin.x - rr, FLOOR - 7, origin.z - rr, origin.x + rr, 122, max(origin.z + rr, shaft.z + 8))
        }

        private fun ell(r: Double, dy: Double, rr: Double, hh: Double) = (r / rr) * (r / rr) + (dy / hh) * (dy / hh)

        private fun angDiff(a: Double, b: Double): Double {
            var d = (a - b) % (2 * PI)
            if (d < -PI) d += 2 * PI
            if (d > PI) d -= 2 * PI
            return abs(d)
        }

        private fun segDist(px: Double, py: Double, pz: Double, ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double {
            val vx = bx - ax; val vy = by - ay; val vz = bz - az
            val t = (((px - ax) * vx + (py - ay) * vy + (pz - az) * vz) / (vx * vx + vy * vy + vz * vz)).coerceIn(0.0, 1.0)
            val qx = ax + vx * t - px; val qy = ay + vy * t - py; val qz = az + vz * t - pz
            return sqrt(qx * qx + qy * qy + qz * qz)
        }
    }
}
