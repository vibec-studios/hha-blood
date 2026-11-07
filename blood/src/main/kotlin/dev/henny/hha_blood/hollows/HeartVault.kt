package dev.henny.hha_blood.hollows

import com.mojang.serialization.MapCodec
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.ChainBlock
import net.minecraft.block.PaneBlock
import net.minecraft.nbt.NbtCompound
import net.minecraft.structure.StructureContext
import net.minecraft.structure.StructurePiece
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
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
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Die Schlachtgrube" (the Slaughter Pit) - the boss arena of the Crimson Minotaur and his Blood Cerberus:
 * a colosseum dome under the Nether lava sea. Labyrinth inlay in the middle of the floor, a ring of eight
 * pillars (cover - the Minotaur's charge smashes through them), stands with braziers, the Minotaur's throne
 * balcony in the north (with the offering altar below), the Cerberus' kennel behind an iron portcullis in
 * the west, the entrance tunnel + spiral shaft in the south and a side tunnel to the east.
 *
 * [HeartVaultPiece.design] is the single source of truth for every block: worldgen uses it, and the fight
 * (boss/PitArena) uses it to upgrade old arenas and to restore everything the fight changed.
 * (Ids stay "heart_vault" so existing worlds keep their structure reference.)
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
            if (!columnUsed(dx, dz)) continue
            for (y in y0..min(y1, TOP_Y)) {
                val state = design(dx, y - FLOOR, dz) ?: continue
                pos.set(x, y, z)
                world.setBlockState(pos, state, HollowsGen.FLAGS)
            }
        }
        // (the old entrance shaft is replaced by the stair tower + landmark of PitApproach, part of design())
    }

    companion object {
        const val FLOOR = 10
        const val R = 44.0
        const val H = 42.0
        const val SHELL = 3.0
        const val SIDE_TUNNEL = 16
        /** Highest world y the arena design touches (the entrance landmark on the surface reaches up to here). */
        const val TOP_Y = 118

        /** Inner radius of the fighting floor (the stands start here). */
        const val FIGHT_R = 40.5
        /** The portcullis plane of the kennel gate (dz, under the balcony), the gate is |dx| <= 4 wide and dy 0..7 high. */
        const val GATE_Z = -33
        const val GATE_HALF = 4
        const val GATE_TOP = 7
        /** The kennel room: |dx| <= KENNEL_HALF, dz KENNEL_MIN_DZ..KENNEL_MAX_DZ, air dy 0..8, floor dy -1. */
        const val KENNEL_HALF = 7
        const val KENNEL_MIN_DZ = -58
        const val KENNEL_MAX_DZ = -34
        /** Pillar ring. */
        const val PILLAR_R = 34.0
        const val PILLAR_SIZE = 2.3
        /** The balcony floor (standing level) and the throne. */
        const val BALCONY_DY = 12
        const val THRONE_DZ = -40

        val PILLARS: List<Vec3d> = (0 until 8).map {
            val a = Math.toRadians(it * 45.0 + 22.5)
            Vec3d(cos(a) * PILLAR_R, 0.0, sin(a) * PILLAR_R)
        }

        // offsets (relative to the centre block, dy 0 = floor level) used by the fight
        val KENNEL_SPAWN = Vec3d(0.5, 0.0, -47.5)
        val THRONE_SEAT = Vec3d(0.5, BALCONY_DY.toDouble(), THRONE_DZ + 1.15)
        val ALTAR = BlockPos(0, 0, -30)

        fun shaftCenter(origin: BlockPos): BlockPos {
            val z = origin.z + R.toInt() + 14
            return BlockPos(origin.x, FLOOR, ((z shr 4) shl 4) + 8)
        }

        /** Does the arena design touch this column at all? */
        fun columnUsed(dx: Int, dz: Int): Boolean {
            val r = sqrt((dx * dx + dz * dz).toDouble())
            if (r <= R + SHELL + 1) return true
            if (dx in -66..-38 && abs(dz) <= 10) return true                                   // old west kennel (sealed)
            if (abs(dx) <= KENNEL_HALF + 1 && dz in (KENNEL_MIN_DZ - 1)..GATE_Z) return true    // kennel under the balcony
            if (abs(dz) <= 5 && dx in 0..(R.toInt() + SIDE_TUNNEL + 9)) return true            // east tunnel + alcove
            if (abs(dx) <= 3 && dz >= 0 && dz <= R + 30) return true                           // south tunnel
            return PitApproach.columnUsed(dx, dz)                                              // tower, landmark, vestibule, ring
        }

        /** Distance measure of the east alcove (< 4.8 = part of the alcove). */
        fun alcoveDist(dx: Int, dy: Int, dz: Int): Double {
            val along = dx - (R.toInt() + SIDE_TUNNEL + 3)
            if (abs(along) > 5 || abs(dz) > 5 || dy < -2 || dy > 7) return 99.0
            return sqrt((along * along + (dy - 2) * (dy - 2) * 1.3 + dz * dz).toDouble())
        }

        /**
         * True if (x, y, z) belongs to the arena centred at [origin] (dome, kennel, tunnels, alcove, shaft) or lies
         * within [margin] blocks of it. Decoration features use this to keep out, the fight to protect the arena.
         */
        fun occupied(origin: BlockPos, x: Int, y: Int, z: Int, margin: Int): Boolean {
            val dx = x - origin.x
            val dz = z - origin.z
            val dy = y - FLOOR
            val r = sqrt((dx * dx + dz * dz).toDouble())
            if (dy >= -8 - margin && ell(r, max(dy, 0).toDouble(), R + SHELL + margin, H + SHELL + margin) < 1.0) return true
            if (dx in (-66 - margin)..(-38 + margin) && abs(dz) <= 10 + margin && dy in (-4 - margin)..(12 + margin)) return true
            if (abs(dx) <= KENNEL_HALF + 1 + margin && dz in (KENNEL_MIN_DZ - 1 - margin)..(GATE_Z + 1 + margin) && dy in (-2 - margin)..(GATE_TOP + 2 + margin)) return true
            if (dy in (-3 - margin)..(9 + margin)) {
                if (abs(dz) <= 6 + margin && dx in -margin..(R.toInt() + SIDE_TUNNEL + 9 + margin)) return true
                val southEnd = shaftCenter(origin).z - origin.z
                if (abs(dx) <= 6 + margin && dz in 0..(southEnd + margin)) return true
            }
            val s = shaftCenter(origin)
            val sdx = x - s.x
            val sdz = z - s.z
            if (y >= FLOOR - 4 - margin && sdx * sdx + sdz * sdz <= (8 + margin) * (8 + margin)) return true
            return PitApproach.occupied(dx, dy, dz, margin)
        }

        private fun box(origin: BlockPos): BlockBox {
            val shaft = shaftCenter(origin)
            val rr = (R + SIDE_TUNNEL + 9).toInt()
            return BlockBox(origin.x - rr, FLOOR - 7, origin.z - rr, origin.x + rr, 122, max(origin.z + rr, shaft.z + 8))
        }

        // ------------------------------------------------------------------------------------- blocks

        private val BRICKS get() = HollowsBlocks.BLOOD_STONE_BRICKS.defaultState
        private val POLISHED get() = HollowsBlocks.POLISHED_BLOOD_STONE.defaultState
        private val CHISELED get() = HollowsBlocks.CHISELED_BLOOD_STONE.defaultState
        private val STONE get() = HollowsBlocks.BLOOD_STONE.defaultState
        private val VEIN get() = HollowsBlocks.VEIN_STONE.defaultState
        private val AIR get() = Blocks.AIR.defaultState
        private val BONE get() = Blocks.BONE_BLOCK.defaultState
        private val CHAIN get() = Blocks.IRON_CHAIN.defaultState.with(ChainBlock.AXIS, Direction.Axis.Y)
        private val WALL get() = HollowsBlocks.BLOOD_STONE_BRICK_WALL.defaultState
        private val LANTERN get() = Blocks.LANTERN.defaultState.with(net.minecraft.block.LanternBlock.HANGING, true)

        /** Iron bars of the portcullis, connected along the gate (east/west). */
        val BARS: BlockState get() = Blocks.IRON_BARS.defaultState.with(PaneBlock.EAST, true).with(PaneBlock.WEST, true)

        /**
         * The block of the arena design at a position relative to the centre (dy 0 = walking level),
         * null = not part of the arena (natural terrain stays).
         */
        fun design(dx: Int, dy: Int, dz: Int): BlockState? {
            // above the dome only the entrance landmark exists (fast exit, the rebuild asks every height)
            if (dy > H.toInt() + SHELL.toInt() + 1 && !PitApproach.highColumn(dx, dz)) return null
            special(dx, dy, dz)?.let { return it }
            val r = sqrt((dx * dx + dz * dz).toDouble())
            kennel(dx, dy, dz)?.let { return it }
            // the way in: stair tower + landmark, vestibule, procession ring (never inside the dome)
            if (r > R + SHELL - 0.5) PitApproach.block(dx, dy, dz)?.let { return it }
            oldKennel(dx, dy, dz, r)?.let { return it }
            alcove(dx, dy, dz)?.let { return it }
            tunnel(dx, dy, dz, r)?.let { return it }
            if (dy < -6) return null
            // ----- foundation / floor
            if (dy < 0) {
                if (r >= R + SHELL) return null
                if (dy < -1) return STONE
                return floorBlock(dx, dz, r)
            }
            val e = ell(r, dy.toDouble(), R, H)
            if (ell(r, dy.toDouble(), R + SHELL, H + SHELL) >= 1.0) return null
            titanSkull(dx, dy, dz, r)?.let { return it }
            gallery(dx, dy, dz, r, e)?.let { return it }
            if (e >= 1.0) {
                if (r < 7.0 && dy >= H - 2) return apexKnot(dx, dy, dz, r)
                if (ell(r, dy.toDouble(), R + 1, H + 1) < 1.0) return if (shellVein(dx, dy, dz, r)) VEIN else BRICKS
                return STONE
            }
            // ----- inside the dome
            balcony(dx, dy, dz)?.let { return it }
            pillar(dx, dy, dz)?.let { return it }
            pillarBrazier(dx, dy, dz)?.let { return it }
            stands(dx, dy, dz, r)?.let { return it }
            if (dx == ALTAR.x && dz == ALTAR.z && dy == 0) return HollowsBlocks.SANGUINE_ALTAR.defaultState
            hangingChain(dx, dy, dz)?.let { return it }
            // bone ribs from the top of the galleries up the dome + a bone ring where they end
            if (ell(r, dy.toDouble(), R - 2.2, H - 2.2) >= 1.0 && dy in 18..(H.toInt() - 6)) {
                if (dy >= H - 8 && dy <= H - 7) return BONE
                val ang = atan2(dz.toDouble(), dx.toDouble())
                for (k in 0 until 10) {
                    val ra = (36.0 * k) * PI / 180   // the throne (north, 270 deg) lies exactly between two ribs
                    if (angDiff(ang, ra) * r < 1.15) return BONE
                }
            }
            return AIR
        }

        // ------------------------------------------------------------------------- galleries, channels, skull

        private val GLOW get() = PitBlocks.BLOOD_GLOW.defaultState
        private val FALL get() = PitBlocks.BLOOD_FALL.defaultState

        /** Inner face of the galleries (arcade wall above the stands). */
        private const val GAL_R = 41.2
        /** Blood channels in the gallery wall (blood pours down from the dome onto the podium). */
        private val CHANNELS = doubleArrayOf(45.0, 135.0, 225.0, 315.0)
        private const val CHANNEL_TOP = 15

        private fun deg(dx: Int, dz: Int): Double {
            val a = Math.toDegrees(atan2(dz.toDouble(), dx.toDouble()))
            return if (a < 0) a + 360 else a
        }

        private fun degDiff(a: Double, b: Double): Double {
            val d = abs(a - b) % 360.0
            return if (d > 180) 360 - d else d
        }

        /** Inner / outer radius of the dome shell at height dy. */
        private fun innerR(dy: Int) = R * sqrt(max(0.0, 1 - (dy / H) * (dy / H)))
        private fun outerR(dy: Int) = (R + SHELL) * sqrt(max(0.0, 1 - (dy / (H + SHELL)) * (dy / (H + SHELL))))

        /** Sectors without niches: balcony + kennel (north), skull (south), east tunnel. */
        private fun noNiche(deg: Double): Boolean =
            degDiff(deg, 270.0) < 17 || degDiff(deg, 90.0) < 17 || degDiff(deg, 0.0) < 9

        private fun inChannel(deg: Double, r: Double): Boolean = CHANNELS.any { degDiff(deg, it) * PI / 180 * r < 1.05 }

        /** Niche level: 1 = arcade (dy 7..11, arched, 3 wide), 2 = loges in the dome curve (dy 14..17), 0 = none. */
        private fun nicheAt(dx: Int, dy: Int, dz: Int): Int {
            if (dy !in 7..17) return 0
            val r = sqrt((dx * dx + dz * dz).toDouble())
            val deg = deg(dx, dz)
            if (noNiche(deg)) return 0
            if (dy in 7..11) {
                val front = min(GAL_R, innerR(dy))
                if (r < front - 0.01 || r >= min(GAL_R + 1.8, outerR(dy) - 2.3)) return 0
                val k = Math.floorMod(Math.round((deg - 7.5) / 15.0).toInt(), 24)
                val a = k * 15.0 + 7.5
                val half = when (dy) { 11 -> 0.55; 10 -> 1.1; else -> 1.55 }
                return if (degDiff(deg, a) * PI / 180 * r < half) 1 else 0
            }
            if (dy in 14..17) {
                val front = innerR(dy)
                if (r < front - 0.01 || r >= outerR(dy) - 2.3) return 0
                val k = Math.floorMod(Math.round(deg / 15.0).toInt(), 24)
                val a = k * 15.0
                if (CHANNELS.any { degDiff(a, it) < 8 }) return 0
                val half = if (dy == 17) 0.55 else 1.05
                return if (degDiff(deg, a) * PI / 180 * r < half) 2 else 0
            }
            return 0
        }

        /**
         * The galleries: a solid arcade wall above the stands (dy 6..14, face at [GAL_R]) with arched niches full of
         * watching eyes and skeleton spectators, a cornice, blood lamps between the niches, loges in the dome curve
         * above, and four channels where blood pours from the dome down onto the podium.
         */
        private fun gallery(dx: Int, dy: Int, dz: Int, r: Double, e: Double): BlockState? {
            if (dy < 4 || dy > 19 || r < GAL_R - 1.0) return null
            val deg = deg(dx, dz)
            if (degDiff(deg, 270.0) < 17) return null        // the balcony keeps the plain dome
            // blood channels (dy 4..CHANNEL_TOP), one block deep into the wall
            if (inChannel(deg, r) && dy in 4..CHANNEL_TOP && r >= GAL_R - 0.2 && r < GAL_R + 1.0) return FALL
            if (nicheAt(dx, dy, dz) > 0) return AIR
            if (dy < 6 || e >= 1.0) return null               // walkway below, shell behind the wall
            // cornice above the arcade (sticks out over the walkway)
            if (dy == 12 && r >= GAL_R - 0.6 && !inChannel(deg, r)) return if (r < GAL_R) WALL_TOP else CHISELED
            if (r < GAL_R) return null
            return when (dy) {
                6, 12 -> CHISELED
                13 -> POLISHED
                else -> {
                    // pilasters (polished) between the niches, bricks elsewhere
                    val k = (deg - 7.5) / 15.0
                    val f = abs(k - Math.round(k))
                    if (f > 0.42 && dy in 7..11) POLISHED else BRICKS
                }
            }
        }

        private val WALL_TOP: BlockState get() = HollowsBlocks.BLOOD_STONE_BRICK_SLAB.defaultState
            .with(net.minecraft.block.SlabBlock.TYPE, net.minecraft.block.enums.SlabType.TOP)

        /** Where the heart's vessels grow into the dome: a knot of veins around the anchor. */
        private fun apexKnot(dx: Int, dy: Int, dz: Int, r: Double): BlockState {
            val n = HollowsGen.rand(dx, dy, dz, 41)
            return if (n < 0.55) VEIN else HollowsBlocks.BLOOD_STONE.defaultState
        }

        /**
         * A giant skull built into the south wall around the entrance tunnel: you enter the pit through its jaws.
         * Deep eye sockets with a watching eye and a glowing core, nasal cavity, a row of upper teeth over the
         * tunnel, the cranium leaning forward over the stands. Never inside FIGHT_R below dy 14, never through the
         * outer shell.
         */
        private fun titanSkull(dx: Int, dy: Int, dz: Int, r: Double): BlockState? {
            if (dz < 30 || abs(dx) > 12 || dy < 4 || dy > 26) return null
            val u = abs(dx).toDouble()
            val half = when {
                dy >= 14 -> 11.0 * sqrt(max(0.0, 1 - ((dy - 14) / 11.5) * ((dy - 14) / 11.5)))
                dy >= 7 -> 10.5 - (13 - dy) * 0.45
                else -> 6.5
            }
            if (u > half) return null
            // the face: slightly rounded; above dy 14 it follows the dome, one block in front of it
            var front = 40.7 + 0.008 * u * u
            if (dy >= 14) front = min(front, innerR(dy) - 1.0)
            if (dy < 14 && r < FIGHT_R + 0.1) return null
            // in front of the face: open (the galleries do not cover the skull)
            if (dz < front - 0.01) return if (r >= GAL_R - 1.0 && ell(r, dy.toDouble(), R, H) < 1.0) AIR else null
            val depth = dz - front
            // eye sockets: two blocks deep, veined at the back (a watching eye sits in the middle, see SPECIAL)
            val sx = (u - 5.0) / 2.8
            val sy = (dy - 13.0) / 2.4
            if (sx * sx + sy * sy < 1.0) return if (depth < 2.0) AIR else if (depth < 3.0) VEIN else null
            // nasal cavity: narrow at the top, wider below
            if (dy in 8..10 && u <= (11 - dy) * 0.6 + 0.01) return if (depth < 1.5) AIR else if (depth < 2.5) HollowsBlocks.BLOOD_STONE.defaultState else null
            // upper teeth over the tunnel mouth (dy 4..5), gums above
            if (dy <= 5 && Math.floorMod(dx, 2) == 1) return if (depth < 1.0) AIR else if (depth < 2.0) HollowsBlocks.BLOOD_STONE.defaultState else null
            if (ell(r, dy.toDouble(), R + 1.2, H + 1.2) >= 1.0) return null      // keep the outer shell intact
            // cracks of dried blood on the bone
            val crack = abs(HollowsGen.rand(dx / 2, dy / 3, 0, 23) - 0.5) < 0.05 && Math.floorMod(dx + dy, 3) == 0
            return if (crack) HollowsBlocks.BLOOD_STONE.defaultState else BONE
        }

        // ------------------------------------------------------------------------- special single blocks

        private fun key(dx: Int, dy: Int, dz: Int): Long = ((dx + 512).toLong() shl 40) or ((dy + 512).toLong() shl 20) or (dz + 512).toLong()

        private fun unkey(k: Long) = Triple(((k ushr 40) and 0xfffff).toInt() - 512, ((k ushr 20) and 0xfffff).toInt() - 512, (k and 0xfffff).toInt() - 512)

        private fun special(dx: Int, dy: Int, dz: Int): BlockState? {
            if (abs(dx) > 70 || abs(dz) > 70) return null
            return SPECIAL[key(dx, dy, dz)]
        }

        private fun facingToCentre(dx: Int, dz: Int): Direction =
            if (abs(dx) > abs(dz)) (if (dx > 0) Direction.WEST else Direction.EAST) else (if (dz > 0) Direction.NORTH else Direction.SOUTH)

        private fun eye(f: Direction): BlockState = PitBlocks.WATCHING_EYE.defaultState.with(WatchingEyeBlock.FACING, f)

        /** Eyes, skeleton spectators, lamps, banners and the heart's anchor - computed once. */
        private val SPECIAL: Map<Long, BlockState> by lazy { buildSpecial() }

        /** Signature positions (dx, dy, dz) that only this design has - used by PitArena.checkUpgrade. */
        val NEW_SIGNATURES: List<Triple<Int, Int, Int>> by lazy {
            val lamps = SPECIAL.entries.filter { it.value.isOf(PitBlocks.BLOOD_GLOW) && unkey(it.key).second == 9 }.map { unkey(it.key) }.sortedBy { it.first * 1000 + it.third }.take(2)
            listOf(Triple(0, H.toInt() - 1, 0)) + lamps + listOf(Triple(0, 18, 40)) + APPROACH_SIGNATURES
        }

        /**
         * Signature positions of the way in (round "klarer Weg"): glow in the middle of the stair tower floor, the
         * eyes of the outer skull face, a vestibule lamp, the procession ring floor in the west (through the sealed
         * old kennel) and in the north (behind the kennel). Any three wrong = the arena is rebuilt.
         */
        val APPROACH_SIGNATURES: List<Triple<Int, Int, Int>> = listOf(
            Triple(0, -1, PitApproach.TOWER_DZ),
            Triple(2, 6, PitApproach.VEST_FACE),
            Triple(-2, 6, PitApproach.VEST_FACE),
            Triple(PitApproach.VEST_HALF, 3, 49),
            Triple(-62, -1, 0),
            Triple(0, -1, -62),
        )

        private fun buildSpecial(): Map<Long, BlockState> {
            val m = HashMap<Long, BlockState>()
            // the heart hangs from the apex
            m[key(0, H.toInt() - 1, 0)] = PitBlocks.PIT_HEART.defaultState
            for (k in 0 until 24) {
                // arcade niches: skeleton spectators or three watching eyes
                val a1 = k * 15.0 + 7.5
                if (!noNiche(a1)) {
                    val rad = Math.toRadians(a1)
                    if (k % 3 == 0) {
                        val x = (cos(rad) * (GAL_R + 0.9)).roundToInt(); val z = (sin(rad) * (GAL_R + 0.9)).roundToInt()
                        if (nicheAt(x, 8, z) == 1 && nicheAt(x, 7, z) == 1) {
                            m[key(x, 7, z)] = BONE
                            // skull rotation 0 = looking south, 4 = west: look at the middle of the pit
                            val rot = Math.floorMod(Math.round(atan2(x.toDouble(), -z.toDouble()) / (2 * PI) * 16).toInt(), 16)
                            m[key(x, 8, z)] = Blocks.WITHER_SKELETON_SKULL.defaultState.with(net.minecraft.block.SkullBlock.ROTATION, rot)
                        }
                    } else {
                        for ((lat, h) in listOf(-1 to 8, 1 to 8, 0 to 10)) placeEye(m, rad, lat, h, 1)
                    }
                }
                // blood lamps in the pilasters between the arcade niches
                val al = k * 15.0
                if (!noNiche(al) && CHANNELS.none { degDiff(al, it) < 4 }) {
                    val rad = Math.toRadians(al)
                    val x = (cos(rad) * (GAL_R + 0.5)).roundToInt(); val z = (sin(rad) * (GAL_R + 0.5)).roundToInt()
                    if (nicheAt(x, 9, z) == 0 && sqrt((x * x + z * z).toDouble()) >= GAL_R) m[key(x, 9, z)] = GLOW
                    // loges above: one eye each
                    if (CHANNELS.none { degDiff(al, it) < 8 }) placeEye(m, rad, 0, 15, 2)
                }
            }
            // a glowing mouth above every blood channel
            for (c in CHANNELS) {
                val rad = Math.toRadians(c)
                val x = (cos(rad) * (GAL_R + 0.4)).roundToInt(); val z = (sin(rad) * (GAL_R + 0.4)).roundToInt()
                m[key(x, CHANNEL_TOP + 1, z)] = GLOW
            }
            // the titan skull: a watching eye in the back of each socket
            for (s in intArrayOf(-1, 1)) m[key(s * 5, 13, 43)] = eye(Direction.NORTH)
            // banners under the balcony
            for (x in intArrayOf(-6, 6)) m[key(x, BALCONY_DY - 2, -32)] = Blocks.RED_WALL_BANNER.defaultState.with(net.minecraft.block.WallBannerBlock.FACING, Direction.SOUTH)
            for (x in intArrayOf(-8, 8)) m[key(x, BALCONY_DY - 2, -32)] = Blocks.BLACK_WALL_BANNER.defaultState.with(net.minecraft.block.WallBannerBlock.FACING, Direction.SOUTH)
            // eyes in the back wall of the kennel
            for (x in intArrayOf(-4, 0, 4)) m[key(x, 5, KENNEL_MIN_DZ - 1)] = eye(Direction.SOUTH)
            return m
        }

        /** Puts an eye into the back wall of a niche: walks outwards from the niche to the first wall block. */
        private fun placeEye(m: HashMap<Long, BlockState>, rad: Double, lat: Int, dy: Int, level: Int) {
            val start = if (level == 1) GAL_R + 0.6 else innerR(dy) + 0.4
            var x = (cos(rad) * start - sin(rad) * lat).roundToInt()
            var z = (sin(rad) * start + cos(rad) * lat).roundToInt()
            if (nicheAt(x, dy, z) != level) return
            val f = facingToCentre(x, z)
            for (i in 0 until 4) {
                val nx = x - f.offsetX; val nz = z - f.offsetZ
                if (nicheAt(nx, dy, nz) == level) { x = nx; z = nz; continue }
                // (nx, nz) is the wall behind the niche: the eye looks out of it
                if (ell(sqrt((nx * nx + nz * nz).toDouble()), dy.toDouble(), R + SHELL - 1.5, H + SHELL - 1.5) >= 1.0) return
                m[key(nx, dy, nz)] = eye(f)
                return
            }
        }

        /** Labyrinth inlay in the middle, glowing ring, spokes, pillar ring and the rough outer sand. */
        private fun floorBlock(dx: Int, dz: Int, r: Double): BlockState {
            if (r >= FIGHT_R) return BRICKS
            if (abs(dx - ALTAR.x) <= 1 && abs(dz - ALTAR.z) <= 1) return CHISELED
            if (r < 2.2) return PitBlocks.BLOOD_GLOW.defaultState      // where the heart bleeds onto the floor
            if (r < 15.6) return labyrinth(dx, dz, r)
            if (r < 16.8) return VEIN
            if (r in 33.4..34.6) return CHISELED
            val ang = atan2(dz.toDouble(), dx.toDouble())
            if (r in 31.9..33.4) for (k in 0 until 8) if (angDiff(ang, k * PI / 4) * r < 0.55) return PitBlocks.BLOOD_GLOW.defaultState
            if (r < 33.4) {
                for (k in 0 until 16) if (angDiff(ang, k * PI / 8) * r < 0.55) return if (k % 2 == 0) VEIN else POLISHED
                return BRICKS
            }
            // outer ring: rough stone with a few glowing veins
            return if (HollowsGen.rand(dx, 0, dz, 21) < 0.07) VEIN else STONE
        }

        private val RINGS = doubleArrayOf(4.0, 6.5, 9.0, 11.5, 14.0)

        /** A circular labyrinth: glowing vein walls (rings with gaps + radial walls) on polished paths. */
        private fun labyrinth(dx: Int, dz: Int, r: Double): BlockState {
            val ang = atan2(dz.toDouble(), dx.toDouble())
            for ((i, k) in RINGS.withIndex()) {
                val gap = Math.toRadians(i * 137.0 + 90.0)
                if (abs(r - k) < 0.5) return if (angDiff(ang, gap) * r < 1.3) POLISHED else VEIN
                if (i < RINGS.size - 1 && r > k && r < RINGS[i + 1] && angDiff(ang, gap + PI + i * 0.35) * r < 0.5) return VEIN
            }
            return POLISHED
        }

        /**
         * Arteries in the dome: four big ones run from the heart's knot at the apex down to the blood channels
         * (the heart pumps the blood that pours into the pit), four thinner ones in between end halfway down.
         */
        private fun shellVein(dx: Int, dy: Int, dz: Int, r: Double): Boolean {
            val deg = deg(dx, dz)
            for (k in 0 until 8) {
                val main = k % 2 == 1
                if (!main && dy < 24) continue
                if (main && dy < CHANNEL_TOP) continue
                val base = k * 45.0
                val a = base + 7.0 * sin(dy * 0.19 + k * 1.7) * min(1.0, r / 20.0)
                val width = if (main) 1.15 else 0.6
                if (degDiff(deg, a) * PI / 180 * r < width) return true
            }
            return HollowsGen.rand(dx, dy, dz, 9) < 0.02
        }

        /** Index of the pillar at this column, or -1. */
        fun pillarAt(dx: Double, dz: Double, extra: Double = 0.0): Int {
            for ((i, p) in PILLARS.withIndex()) {
                val ddx = dx - p.x
                val ddz = dz - p.z
                if (ddx * ddx + ddz * ddz < (PILLAR_SIZE + extra) * (PILLAR_SIZE + extra)) return i
            }
            return -1
        }

        private fun pillar(dx: Int, dy: Int, dz: Int): BlockState? {
            if (dy == 0 && pillarAt(dx.toDouble(), dz.toDouble(), 0.6) >= 0 && pillarAt(dx.toDouble(), dz.toDouble()) < 0) return POLISHED
            val i = pillarAt(dx.toDouble(), dz.toDouble())
            if (i < 0) return null
            if (dy % 7 == 0) return CHISELED
            // a glowing vein strip on the side facing the middle of the arena
            val p = PILLARS[i]
            val ox = dx - p.x
            val oz = dz - p.z
            val inward = -(ox * p.x + oz * p.z) / PILLAR_R
            val side = abs(ox * p.z - oz * p.x) / PILLAR_R
            if (inward > 1.4 && side < 0.75) return VEIN
            return BRICKS
        }

        /** A fire bowl on a corbel at the inner side of every pillar (lights the fighting floor). */
        private fun pillarBrazier(dx: Int, dy: Int, dz: Int): BlockState? {
            if (dy !in 4..6) return null
            for (p in PILLARS) {
                val bx = (p.x * (1 - 2.9 / PILLAR_R)).roundToInt()
                val bz = (p.z * (1 - 2.9 / PILLAR_R)).roundToInt()
                if (dx != bx || dz != bz) continue
                return when (dy) { 4 -> CHISELED; 5 -> Blocks.NETHERRACK.defaultState; else -> Blocks.FIRE.defaultState }
            }
            return null
        }

        /** Podium wall (dy 0..3) with braziers on its cap, a second tier above, crystals on the tiers. */
        private fun stands(dx: Int, dy: Int, dz: Int, r: Double): BlockState? {
            if (r < FIGHT_R) return null
            val ang = atan2(dz.toDouble(), dx.toDouble())
            if (dy <= 3) {
                if (dy == 3) {
                    if (r < 41.6 && brazier(ang)) return Blocks.NETHERRACK.defaultState
                    return CHISELED
                }
                if (dy == 1 && r < 41.5) {
                    for (k in 0 until 24) if (angDiff(ang, k * PI / 12) * r < 0.5) return VEIN
                }
                return BRICKS
            }
            if (dy == 4 && r < 41.6 && brazier(ang)) return Blocks.FIRE.defaultState
            if (dy <= 5 && r >= 42.2) return if (dy == 5) POLISHED else BRICKS
            if (dy == 6 && r >= 42.2 && r < 43.2 && HollowsGen.rand(dx, dy, dz, 4) < 0.04) return HollowsBlocks.BLOOD_CRYSTAL_CLUSTER.defaultState
            return null
        }

        /** Braziers every 30 degrees, not above the gate, the tunnels or under the balcony. */
        private fun brazier(ang: Double): Boolean {
            for (k in 0 until 12) {
                val a = k * PI / 6 + PI / 12
                if (angDiff(ang, a) * 41.0 < 0.6) {
                    val deg = Math.toDegrees(a)
                    // skip the ones next to the balcony (north, 255..285 deg)
                    return !(deg > 250 && deg < 290) && CHANNELS.none { degDiff(deg, it) < 5 }
                }
            }
            return false
        }

        /** Twelve chains hanging from the dome with bone hooks at their ends (a slaughterhouse). */
        private fun hangingChain(dx: Int, dy: Int, dz: Int): BlockState? {
            for (k in 0 until 12) {
                val a = Math.toRadians(k * 30.0 + 15.0)
                val cx = (cos(a) * 22).roundToInt()
                val cz = (sin(a) * 22).roundToInt()
                if (dx != cx || dz != cz) continue
                val end = 24 + (k % 3) * 3
                if (dy > end) return CHAIN
                if (dy == end) return LANTERN
            }
            return null
        }

        /** The Minotaur's balcony in the north: platform, parapet, corner pillars, throne, braziers, chains. */
        private fun balcony(dx: Int, dy: Int, dz: Int): BlockState? {
            val adx = abs(dx)
            if (dz > -32 || adx > 9 || dy > 22) return null
            val b = BALCONY_DY
            if (dy in (b - 2)..(b - 1) && adx <= 8 && dz <= -33) return if (dy == b - 1 && (dz == -33 || adx == 8)) POLISHED else BRICKS
            if (dz in -35..-33 && adx in 6..8 && dy in 0..(b - 3)) return if (dy % 4 == 0) CHISELED else BRICKS
            if (dy == b && dz == -33 && adx in 3..8) return WALL
            if (dy == b && adx == 8 && dz in -38..-34) return WALL
            // throne
            if (adx <= 1 && dz == THRONE_DZ && dy == b) return POLISHED
            if (adx <= 2 && dz in (THRONE_DZ - 2)..(THRONE_DZ - 1) && dy in b..(b + 7)) return if (dy == b + 7) CHISELED else BRICKS
            if (adx == 2 && dz == THRONE_DZ && dy in b..(b + 1)) return POLISHED
            if (adx == 3 && dz in (THRONE_DZ - 2)..(THRONE_DZ - 1) && dy in (b + 2)..(b + 8)) return BONE
            if (adx == 4 && dz == THRONE_DZ - 1 && dy in (b + 6)..(b + 9)) return BONE
            if (dx == 0 && dz == THRONE_DZ - 1 && dy == b + 8) return HollowsBlocks.BLOOD_CRYSTAL_CLUSTER.defaultState
            // braziers next to the throne
            if (adx == 6 && dz == THRONE_DZ - 1 && dy == b) return Blocks.NETHERRACK.defaultState
            if (adx == 6 && dz == THRONE_DZ - 1 && dy == b + 1) return Blocks.FIRE.defaultState
            return null
        }

        /**
         * The Cerberus' kennel, directly below the Minotaur's balcony in the north: the portcullis (iron bars at
         * [GATE_Z]) in the front wall under the balcony platform, behind it a long room that runs through the dome
         * wall into the rock (always below dy 9 - the balcony and the throne stay untouched). Bones, chains with
         * shackles, crystal lights and watching eyes, with a clear walkway |dx| <= 2 from the spawn to the gate.
         */
        private fun kennel(dx: Int, dy: Int, dz: Int): BlockState? {
            val adx = abs(dx)
            // a bone arch in front of the gate, under the edge of the balcony
            if (dz == GATE_Z + 1) {
                if (dy == GATE_TOP + 2 && adx <= 3) return BONE
                if (dy == GATE_TOP + 1 && adx == 3) return BONE
                return null
            }
            if (adx > KENNEL_HALF + 1 || dz > GATE_Z || dz < KENNEL_MIN_DZ - 1 || dy < -2 || dy > GATE_TOP + 2) return null
            // front wall with the portcullis
            if (dz == GATE_Z) {
                if (dy == -1 || dy == -2) return if (adx <= GATE_HALF) POLISHED else BRICKS
                if (adx <= GATE_HALF && dy in 0..GATE_TOP) return BARS
                if (adx == GATE_HALF + 1 && dy == GATE_TOP + 1) return GLOW
                if (adx == GATE_HALF + 1 || dy >= GATE_TOP + 1) return CHISELED
                return BRICKS
            }
            val inside = adx <= KENNEL_HALF && dz in KENNEL_MIN_DZ..KENNEL_MAX_DZ
            if (inside && dy in 0..8) return kennelInterior(dx, dy, dz)
            if (inside && dy == -1) {
                if (adx <= 2) return POLISHED
                val n = HollowsGen.rand(dx, 0, dz, 31)
                return if (n < 0.12) BONE else if (n < 0.18) VEIN else STONE
            }
            // walls, floor base and ceiling (dy 9, under the balcony platform)
            if (dy in 2..6 && (adx == KENNEL_HALF + 1 || dz == KENNEL_MIN_DZ - 1) && Math.floorMod(dz + dx + dy, 4) == 0) return VEIN
            return BRICKS
        }

        private fun kennelInterior(dx: Int, dy: Int, dz: Int): BlockState {
            val adx = abs(dx)
            // bone piles in the back corners
            val corner = (KENNEL_HALF - adx) + (dz - KENNEL_MIN_DZ)
            if (adx >= 3 && corner <= 4 && dy <= 2 - corner / 1.5) return BONE
            // chains with shackles from the ceiling, lanterns over the walkway edge
            if (adx == 5 && (dz == -38 || dz == -44 || dz == -50) && dy >= 5) return CHAIN
            if (adx == 5 && (dz == -38 || dz == -44 || dz == -50) && dy == 4) return Blocks.IRON_BARS.defaultState
            if (adx == 4 && (dz == -41 || dz == -53) && dy >= 7) return CHAIN
            if (adx == 4 && (dz == -41 || dz == -53) && dy == 6) return LANTERN
            // crystal lights in the corners
            if (dy == 0 && adx == KENNEL_HALF && (dz == KENNEL_MAX_DZ - 1 || dz == KENNEL_MIN_DZ + 1)) return HollowsBlocks.BLOOD_CRYSTAL_CLUSTER.defaultState
            return AIR
        }

        /**
         * The old west kennel (until v0.7.2): its room and passage are filled with blood stone so existing worlds
         * get a sealed wall there (the gate hole itself becomes normal dome wall again).
         */
        private fun oldKennel(dx: Int, dy: Int, dz: Int, r: Double): BlockState? {
            if (dx !in -66..-38 || abs(dz) > 10 || dy < -4 || dy > 12) return null
            return if (ell(r, max(dy, 0).toDouble(), R + SHELL, H + SHELL) >= 1.0) STONE else null
        }

        /** The round chamber at the end of the east tunnel (blood crystal in the middle). */
        private fun alcove(dx: Int, dy: Int, dz: Int): BlockState? {
            val along = dx - (R.toInt() + SIDE_TUNNEL + 3)
            if (abs(along) > 5 || abs(dz) > 5 || dy < -2 || dy > 7) return null
            val d = sqrt((along * along + (dy - 2) * (dy - 2) * 1.3 + dz * dz).toDouble())
            return when {
                d >= 4.8 -> null
                // the procession ring runs through the alcove
                d >= 3.8 && PitApproach.ringLat(dx, dz) <= PitApproach.RING_HALF -> if (dy == -1) POLISHED else if (dy in 0..4) AIR else BRICKS
                dy <= -1 -> if (dy == -1 && d < 4.0) POLISHED else BRICKS
                d >= 3.8 -> if (HollowsGen.rand(dx, dy, dz, 3) < 0.25) VEIN else BRICKS
                along == 0 && dz == 0 && dy == 0 -> HollowsBlocks.BLOOD_CRYSTAL_CLUSTER.defaultState
                else -> AIR
            }
        }

        /** South tunnel to the entrance shaft and the east side tunnel. */
        private fun tunnel(dx: Int, dy: Int, dz: Int, r: Double): BlockState? {
            if (dy < -1 || dy > 5) return null
            // the arena centre is always a chunk centre (x/z = 16k + 8), so the shaft is always 64 blocks south
            // south: through the skull and the dome shell up to the vestibule (PitApproach, outer skull face at dz 47)
            val southEnd = PitApproach.VEST_FACE - 1
            val along: Int
            val lat: Int
            when {
                dz >= 0 && abs(dx) <= 2 && dz in 39..southEnd -> { along = dz; lat = abs(dx) }
                abs(dz) <= 2 && dx in 39..(R.toInt() + SIDE_TUNNEL) -> { along = dx; lat = abs(dz) }
                else -> return null
            }
            if (r < FIGHT_R - 0.5) return null
            if (along > R + 3 && dy == 5) return BRICKS
            return when {
                dy == -1 -> if (lat <= 1) POLISHED else BRICKS
                lat == 2 -> if (along > R + 3) (if (along % 6 == 0 && dy == 2) VEIN else BRICKS) else null
                dy == 4 && lat == 1 -> if (along > R + 3) CHISELED else AIR
                dy == 5 -> null
                else -> AIR
            }
        }

        fun ell(r: Double, dy: Double, rr: Double, hh: Double) = (r / rr) * (r / rr) + (dy / hh) * (dy / hh)

        private fun angDiff(a: Double, b: Double): Double {
            var d = (a - b) % (2 * PI)
            if (d < -PI) d += 2 * PI
            if (d > PI) d -= 2 * PI
            return abs(d)
        }
    }
}
