package dev.henny.hha_blood.hollows

import net.minecraft.block.AmethystClusterBlock
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.ChainBlock
import net.minecraft.block.LanternBlock
import net.minecraft.block.PillarBlock
import net.minecraft.block.StairsBlock
import net.minecraft.util.math.Direction
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The way to the Slaughter Pit (Mats: "kein klarer Weg/Eingang"). Part of the arena design
 * ([HeartVaultPiece.design]), so the rebuild of existing arenas places it too. Everything stays within
 * |dx|, |dz| <= 70 of the arena centre (the rebuild range).
 *
 *  - **Das Gerippe** (landmark): on the Nether surface above the entrance a plaza with a giant rib cage of bone
 *    arching over a square stairwell, glowing veins on the bones, burning braziers and two geysers of rising blood
 *    on the wings. The plaza sits at the first open air above the lava sea of the tower column (sampled from the
 *    Nether noise, [HollowsRegion.plazaY]) - the same in every run, also for already generated worlds.
 *  - **Der Abstieg**: a 19x19 stair tower with a 5 wide spiral stair (stair blocks, landings in the corners,
 *    lamps, railings) around a column of rising blood (the elevator), from the plaza down to the arena floor.
 *  - **Die Vorhalle**: a hall between the tower and the dome with the outer face of the titan skull - its mouth is
 *    the tunnel into the pit.
 *  - **Der Prozessionsring**: a paved, lit road around the whole arena (radius [RING_R]) with bone arches, lamp
 *    posts and glowing chevrons in the floor that point towards the tower. It cuts through the rock and through
 *    every cave around the arena, so whoever comes through the caves hits the road. It runs through the tower base
 *    (doors east/west) and through the alcove of the east tunnel. New worlds also get roads from far away
 *    ([HollowsTerrain]) that end on this ring.
 */
object PitApproach {
    /** Centre of the stair tower (dz from the arena centre). The tower covers |dx| <= 9, dz 52..70. */
    const val TOWER_DZ = 61
    const val TOWER_OUT = 9
    private const val LANE_MIN = 3
    private const val LANE_MAX = 7
    /** Rise per side and per full turn of the stair. */
    private const val SIDE_RISE = 5
    private const val TURN_RISE = 20

    /** The plaza (and its wings) on the surface: |tx| <= WING, |tz| <= TOWER_OUT. */
    const val WING = 17
    /** Air carved above the plaza level. */
    private const val CLEAR = 10

    /** Vestibule between the dome and the tower (air dz 48..51). */
    const val VEST_FACE = 47
    const val VEST_MAX = 51
    const val VEST_HALF = 5
    private const val VEST_TOP = 9

    /** The procession ring around the arena. */
    const val RING_R = 62.5
    const val RING_HALF = 2.2
    const val RING_OUT = 3.6

    /** The causeways end here (|dx|; the rebuild of old arenas covers |dx| <= 70). */
    const val BRIDGE_END = 69

    /** Highest dy above the plaza the landmark reaches. */
    const val LANDMARK_H = 34

    private val BRICKS get() = HollowsBlocks.BLOOD_STONE_BRICKS.defaultState
    private val POLISHED get() = HollowsBlocks.POLISHED_BLOOD_STONE.defaultState
    private val CHISELED get() = HollowsBlocks.CHISELED_BLOOD_STONE.defaultState
    private val STONE get() = HollowsBlocks.BLOOD_STONE.defaultState
    private val VEIN get() = HollowsBlocks.VEIN_STONE.defaultState
    private val AIR get() = Blocks.AIR.defaultState
    private val BONE get() = Blocks.BONE_BLOCK.defaultState
    private val GLOW get() = PitBlocks.BLOOD_GLOW.defaultState
    private val WALL get() = HollowsBlocks.BLOOD_STONE_BRICK_WALL.defaultState
    private val CHAIN get() = Blocks.IRON_CHAIN.defaultState.with(ChainBlock.AXIS, Direction.Axis.Y)
    private val HANGING get() = Blocks.LANTERN.defaultState.with(LanternBlock.HANGING, true)
    private val STANDING get() = Blocks.LANTERN.defaultState
    private val RISING get() = PitBlocks.BLOOD_FALL.defaultState.with(BloodFallBlock.RISING, true)
    private fun stairs(f: Direction): BlockState = HollowsBlocks.BLOOD_STONE_BRICK_STAIRS.defaultState.with(StairsBlock.FACING, f)

    /** Plaza walking level as dy (feet), at least 22 (= y 32, just above the lava sea). */
    fun plazaDy(): Int = HollowsRegion.plazaY() - HeartVaultPiece.FLOOR

    // ----------------------------------------------------------------------------------------- footprint

    fun inTowerFoot(dx: Int, dz: Int): Boolean = abs(dx) <= TOWER_OUT && abs(dz - TOWER_DZ) <= TOWER_OUT
    fun inPlazaFoot(dx: Int, dz: Int): Boolean = abs(dx) <= WING && abs(dz - TOWER_DZ) <= TOWER_OUT
    fun inVestibule(dx: Int, dz: Int): Boolean = abs(dx) <= VEST_HALF && dz in VEST_FACE..VEST_MAX

    fun ringLat(dx: Int, dz: Int): Double = abs(sqrt((dx * dx + dz * dz).toDouble()) - RING_R)

    /** Columns the approach uses (for HeartVaultPiece.columnUsed). */
    fun columnUsed(dx: Int, dz: Int): Boolean =
        inPlazaFoot(dx, dz) || inBridgeFoot(dx, dz) || inVestibule(dx, dz) || ringLat(dx, dz) <= RING_OUT + 0.01

    /** Columns that reach above the dome (fast exit in design()). */
    fun highColumn(dx: Int, dz: Int): Boolean = inPlazaFoot(dx, dz) || inBridgeFoot(dx, dz)

    /** The causeways east and west of the plaza over the lava sea (up to the edge of the rebuild range). */
    fun inBridgeFoot(dx: Int, dz: Int): Boolean = abs(dx) in (WING + 1)..BRIDGE_END && abs(dz - TOWER_DZ) <= 3

    /** Occupied volume (decorations keep out, the arena protection covers it). */
    fun occupied(dx: Int, dy: Int, dz: Int, margin: Int): Boolean {
        if (abs(dx) <= WING + margin && abs(dz - TOWER_DZ) <= TOWER_OUT + margin && dy >= -4 - margin && dy <= plazaDy() + LANDMARK_H + margin) {
            // below the plaza only the tower itself, above it the plaza with the landmark
            if (abs(dx) <= TOWER_OUT + margin || dy >= plazaDy() - 4 - margin) return true
        }
        if (abs(dx) in (WING + 1 - margin)..(BRIDGE_END + margin) && abs(dz - TOWER_DZ) <= 3 + margin && dy in (plazaDy() - 3 - margin)..(plazaDy() + 4 + margin)) return true
        if (abs(dx) <= VEST_HALF + margin && dz in (VEST_FACE - margin)..(VEST_MAX + margin) && dy in (-4 - margin)..(VEST_TOP + margin)) return true
        return dy in (-4 - margin)..(7 + margin) && ringLat(dx, dz) <= RING_OUT + margin
    }

    // ----------------------------------------------------------------------------------------- design

    /** The approach block at (dx, dy, dz) from the arena centre, null = not part of it. */
    fun block(dx: Int, dy: Int, dz: Int): BlockState? {
        if (inPlazaFoot(dx, dz)) {
            // under the wings the procession ring passes (the wings themselves only exist near the surface)
            val v = plazaColumn(dx, dy, dz - TOWER_DZ)
            if (v != null || inTowerFoot(dx, dz)) return v
        }
        if (inVestibule(dx, dz)) vestibule(dx, dy, dz)?.let { return it }
        if (inBridgeFoot(dx, dz)) bridge(dx, dy, dz - TOWER_DZ)?.let { return it }
        return ring(dx, dy, dz)
    }

    // ---- the stair tower

    private class Lane(val level: Int, val landing: Boolean, val facing: Direction, val along: Int)

    /**
     * Base level (0..19) of a lane cell, the stair goes round counter-clockwise seen from above, starting in the
     * south-east corner at the floor: south side westwards, west side northwards, north side eastwards, east side
     * southwards, then the next turn 20 higher.
     */
    private fun lane(tx: Int, tz: Int): Lane {
        val corner = abs(tx) >= LANE_MIN && abs(tz) >= LANE_MIN
        if (corner) {
            return when {
                tx > 0 && tz > 0 -> Lane(0, true, Direction.WEST, 0)
                tx < 0 && tz > 0 -> Lane(SIDE_RISE, true, Direction.NORTH, 0)
                tx < 0 && tz < 0 -> Lane(2 * SIDE_RISE, true, Direction.EAST, 0)
                else -> Lane(3 * SIDE_RISE, true, Direction.SOUTH, 0)
            }
        }
        return when {
            tz >= LANE_MIN -> Lane(2 - tx + 1, false, Direction.WEST, 2 - tx)                         // south, westwards
            tx <= -LANE_MIN -> Lane(SIDE_RISE + 2 - tz + 1, false, Direction.NORTH, 2 - tz)            // west, northwards
            tz <= -LANE_MIN -> Lane(2 * SIDE_RISE + tx + 2 + 1, false, Direction.EAST, tx + 2)          // north, eastwards
            else -> Lane(3 * SIDE_RISE + tz + 2 + 1, false, Direction.SOUTH, tz + 2)                   // east, southwards
        }
    }

    private fun isLane(tx: Int, tz: Int): Boolean = max(abs(tx), abs(tz)) in LANE_MIN..LANE_MAX

    /** Lane cells right after the top of the stair (their next level lies 1..5 above the plaza) form the top landing. */
    private fun landingAt(tx: Int, tz: Int, p: Int): Boolean {
        if (!isLane(tx, tz)) return false
        var nl = lane(tx, tz).level
        while (nl <= p) nl += TURN_RISE
        return nl - p in 1..SIDE_RISE
    }

    private fun touchesLanding(tx: Int, tz: Int, p: Int): Boolean {
        if (!isLane(tx, tz) || landingAt(tx, tz, p)) return false
        if (stairTop(tx, tz, p)) return false
        return landingAt(tx + 1, tz, p) || landingAt(tx - 1, tz, p) || landingAt(tx, tz + 1, p) || landingAt(tx, tz - 1, p)
    }

    /** The stair cells just below the plaza (levels p-2..p): open, players walk there. */
    private fun stairTop(tx: Int, tz: Int, p: Int): Boolean {
        var lv = lane(tx, tz).level
        while (lv + TURN_RISE <= p) lv += TURN_RISE
        return lv in (p - 2)..p
    }

    private fun plazaColumn(tx: Int, dy: Int, tz: Int): BlockState? {
        val p = plazaDy()
        if (dy < -4 || dy > p + LANDMARK_H) return null
        val atx = abs(tx); val atz = abs(tz)
        val m = max(atx, atz)
        if (dy >= p) return above(tx, dy, tz, p, m)
        if (m > TOWER_OUT) return wing(tx, dy, tz, p)
        if (dy <= -2) return STONE
        if (m >= 8) return towerWall(tx, dy, tz, p, m)
        if (m <= 1) {
            if (dy == -1) return if (m == 0) GLOW else POLISHED
            return RISING
        }
        if (m == 2) {
            if (dy == -1) return if (atx == 2 && atz == 2) GLOW else POLISHED
            // railing beside the stair (and its edge block), so nobody falls into the well
            val l = lane(tx + (if (atx == 2) Integer.signum(tx) else 0), tz + (if (atz == 2) Integer.signum(tz) else 0))
            var lv = l.level
            while (lv <= p) {
                if (lv >= 1 && dy == lv) return WALL
                if (lv >= 1 && dy == lv - 1) return BRICKS
                lv += TURN_RISE
            }
            return AIR
        }
        // the stair lanes (m 3..7)
        if (dy == -1) return if (atx == 5 && atz == 5) GLOW else if ((tx + tz) and 1 == 0) POLISHED else BRICKS
        val l = lane(tx, tz)
        var lv = l.level
        while (lv <= p) {
            if (lv >= 1 && dy == lv - 1) return if (l.landing) POLISHED else stairs(l.facing)
            if (lv >= 2 && dy == lv - 2) return BRICKS
            lv += TURN_RISE
        }
        // the top landing: the stair goes on flat at plaza level for one side, the exit to the rim
        if (dy == p - 1 && landingAt(tx, tz, p)) return if (m == LANE_MAX) CHISELED else POLISHED
        // a barrier where the landing ends (the stairwell below stays open)
        if (dy == p - 1 && touchesLanding(tx, tz, p)) return BRICKS
        return AIR
    }

    private fun towerWall(tx: Int, dy: Int, tz: Int, p: Int, m: Int): BlockState {
        val atx = abs(tx); val atz = abs(tz)
        // door to the vestibule (north) and the two doors of the procession ring (east/west)
        if (tz <= -8 && atx <= 3 && dy in 0..5) return AIR
        if (tz <= -8 && ((atx == 4 && dy in 0..5) || (atx <= 4 && dy == 6))) return CHISELED
        if (atx >= 8 && tz in -1..2 && dy in 0..4) return AIR
        if (atx >= 8 && ((tz == -2 || tz == 3) && dy in 0..4 || tz in -2..3 && dy == 5)) return CHISELED
        if (dy == p - 1) return CHISELED
        if (m == 9) return if (dy == p - 2 || dy % 8 == 7) POLISHED else BRICKS
        // inner face: a lamp above the middle of every flight, vein stripes in the corners
        if (atx != atz) {
            val inner = lane(if (atx == 8) tx - Integer.signum(tx) else tx, if (atz == 8) tz - Integer.signum(tz) else tz)
            if (!inner.landing && inner.along % 2 == 0) {
                var lv = inner.level
                while (lv <= p - 3) {
                    if (dy == lv + 2) return GLOW
                    lv += TURN_RISE
                }
            }
        }
        if (atx == atz) return if (dy % 3 == 0) VEIN else CHISELED
        // lights at the bottom of the stairwell (the doors are cut out above)
        if (dy == 2 && atx != atz) {
            val inner = lane(if (atx == 8) tx - Integer.signum(tx) else tx, if (atz == 8) tz - Integer.signum(tz) else tz)
            if (!inner.landing && inner.along % 2 == 0) return GLOW
        }
        return if (dy % 8 == 7) POLISHED else BRICKS
    }

    /** The plaza wings over the lava sea: floor, a slab below, buttresses down to the blood stone. */
    private fun wing(tx: Int, dy: Int, tz: Int, p: Int): BlockState? {
        val atx = abs(tx); val atz = abs(tz)
        if (dy == p - 1) {
            if (atx == WING || atz == TOWER_OUT) return CHISELED
            return if ((atx + atz) % 4 == 0) VEIN else if ((atx + atz) and 1 == 0) POLISHED else BRICKS
        }
        if (dy >= p - 3) return BRICKS
        val buttress = (atx == 12 || atx == 16) && (atz == 3 || atz == 8)
        if (buttress && dy >= HollowsGen.FILL_TOP - HeartVaultPiece.FLOOR) return if (dy % 4 == 0) CHISELED else BRICKS
        return null
    }

    private fun above(tx: Int, dy: Int, tz: Int, p: Int, m: Int): BlockState? {
        val h = dy - p
        landmark()[key(tx, h, tz)]?.let { return it }
        val atx = abs(tx); val atz = abs(tz)
        if (m <= 1 && h <= 1) return RISING
        if (h == 0) {
            if (m == 2) return WALL
            // railing where the top landing ends, parapet around the stairwell (open towards the landing)
            if (m in LANE_MIN..LANE_MAX && touchesLanding(tx, tz, p)) return WALL
            if (m == 8 && !landingAt(tx - Integer.signum(tx) * (if (atx == 8) 1 else 0), tz - Integer.signum(tz) * (if (atz == 8) 1 else 0), p)) return WALL
            // parapet around the plaza, open in the middle of every side
            if ((atz == TOWER_OUT && atx > 2 && atx % 5 != 0) || (atx == WING && atz > 2)) return WALL
            // braziers on the wings
            if (brazier(atx, atz)) return CHISELED
        }
        if (h == 1 && brazier(atx, atz)) return Blocks.NETHERRACK.defaultState
        if (h == 2 && brazier(atx, atz)) return Blocks.FIRE.defaultState
        if (h <= CLEAR) return AIR
        return null
    }

    private fun brazier(atx: Int, atz: Int) = (atx == 11 || atx == 15) && atz == 7

    // ---- the landmark (computed once per plaza height)

    private fun key(x: Int, y: Int, z: Int): Long = ((x + 512).toLong() shl 40) or ((y + 512).toLong() shl 20) or (z + 512).toLong()

    @Volatile private var cache: Pair<Int, Map<Long, BlockState>>? = null

    private fun landmark(): Map<Long, BlockState> {
        val p = plazaDy()
        val air = HollowsRegion.plazaAir()
        val c = cache
        if (c != null && c.first == p * 1000 + air) return c.second
        val m = buildLandmark(air)
        cache = (p * 1000 + air) to m
        return m
    }

    /**
     * Five giant ribs over the stairwell (planes tx = -12..12), open at the top like a real rib cage, glowing
     * veins on the bones, crystal-capped glowing tips, chains with lanterns, and two geysers of rising blood.
     * Keys are (tx, height above the plaza, tz).
     */
    private fun buildLandmark(airAbove: Int): Map<Long, BlockState> {
        val m = HashMap<Long, BlockState>()
        val h0 = (airAbove - 4).coerceIn(15, 26).toDouble()
        val boneY = BONE.with(PillarBlock.AXIS, Direction.Axis.Y)
        for (ribX in intArrayOf(-12, -6, 0, 6, 12)) {
            val hr = h0 * when (abs(ribX)) { 0 -> 1.0; 6 -> 0.86; else -> 0.68 }
            for (side in intArrayOf(-1, 1)) {
                val phiMax = 1.42
                var phi = 0.0
                var tip: Triple<Int, Int, Int>? = null
                while (phi <= phiMax) {
                    val t = phi / phiMax
                    val cz = side * 8.6 * cos(phi)
                    val cy = hr * sin(phi) - 0.5
                    val rad = 1.45 - 0.75 * t
                    val r = rad.toInt() + 1
                    for (x in -2..2) for (y in -r..r) for (z in -r..r) {
                        val px = ribX + x; val py = cy.roundToInt() + y; val pz = cz.roundToInt() + z
                        if (abs(pz) > TOWER_OUT || py < 0) continue
                        val ddx = (px - ribX) / 1.15; val ddy = py - cy; val ddz = pz - cz
                        if (ddx * ddx + ddy * ddy + ddz * ddz > rad * rad) continue
                        val k = key(px, py, pz)
                        // glowing veins: a spiral line along the outside of the bone
                        val vein = abs(((phi * 9.0 + (px - ribX) * 0.7) % 2.0) - 1.0) < 0.12 && ddy * ddy + ddz * ddz > (rad - 0.9) * (rad - 0.9)
                        m[k] = if (vein) GLOW else boneY
                    }
                    tip = Triple(ribX, cy.roundToInt(), cz.roundToInt())
                    phi += 0.02
                }
                tip?.let { (x, y, z) ->
                    m[key(x, y, z)] = GLOW
                    m[key(x, y + 1, z)] = HollowsBlocks.BLOOD_CRYSTAL_CLUSTER.defaultState.with(AmethystClusterBlock.FACING, Direction.UP)
                    m.remove(key(x, y + 2, z))
                }
                // a chain with a lantern under the middle of the outer ribs
                if (ribX != 0) {
                    val phi2 = 0.95
                    val cz = (side * 8.6 * cos(phi2)).roundToInt()
                    val under = (hr * sin(phi2) - 0.5 - 1.45 + 0.75 * (phi2 / phiMax)).toInt()
                    for (y in under - 4..under) if (!m.containsKey(key(ribX, y, cz))) m[key(ribX, y, cz)] = if (y == under - 4) HANGING else CHAIN
                }
            }
        }
        // geysers of rising blood on the wings: a jet from a 4 high plinth (too high to climb into it)
        val gh = (h0 + 6).toInt().coerceAtMost(LANDMARK_H)
        for (s in intArrayOf(-1, 1)) {
            val gx = s * 15
            for (y in 4..gh) m[key(gx, y, 0)] = RISING
            for (x in -1..1) for (z in -1..1) for (y in 0..3) {
                val corner = x != 0 && z != 0
                m[key(gx + x, y, z)] = when {
                    x == 0 && z == 0 -> if (y == 3) GLOW else VEIN
                    y == 3 -> CHISELED
                    corner -> boneY
                    y == 0 -> GLOW
                    else -> BRICKS
                }
            }
            for (x in intArrayOf(-1, 1)) for (z in intArrayOf(-1, 1)) {
                m[key(gx + x, 4, z)] = HollowsBlocks.BLOOD_CRYSTAL_CLUSTER.defaultState.with(AmethystClusterBlock.FACING, Direction.UP)
            }
        }
        return m
    }

    // ---- the causeways

    /**
     * A causeway at plaza level from each wing over the lava sea: 5 wide, railings with lanterns, piers into the lava.
     * Where the netherrack rises above it, a passage is cut.
     */
    private fun bridge(dx: Int, dy: Int, tz: Int): BlockState? {
        val p = plazaDy()
        val ax = abs(dx); val atz = abs(tz)
        val h = dy - p
        if (h < -3 || h > 4) {
            // piers every 10 blocks down into the blood stone
            if (atz <= 1 && ax % 10 == 5 && dy >= HollowsGen.FILL_TOP - HeartVaultPiece.FLOOR && h < -3) return if (dy % 4 == 0) CHISELED else BRICKS
            return null
        }
        if (h == -3) return if (atz <= 2 && ax % 10 in 4..6) BRICKS else null
        if (h == -2) return BRICKS
        if (h == -1) return when {
            atz == 3 -> CHISELED
            atz == 0 && ax % 4 == 0 -> VEIN
            atz <= 1 -> POLISHED
            else -> BRICKS
        }
        if (atz == 3) {
            val post = ax % 8 == 0
            return when (h) {
                0 -> WALL
                1 -> if (post) STANDING else AIR
                else -> AIR
            }
        }
        return AIR
    }

    // ---- the vestibule with the outer face of the titan skull

    private fun vestibule(dx: Int, dy: Int, dz: Int): BlockState? {
        if (dy < -4 || dy > VEST_TOP) return null
        val adx = abs(dx)
        if (dy <= -2) return STONE
        if (dz == VEST_FACE) {
            // the skull face on the outside of the dome: its mouth is the tunnel into the pit
            if (dy == -1) return POLISHED
            if (adx <= 1 && dy in 0..3) return AIR
            if (dy == 4 && adx <= 2) return if (adx == 1) AIR else BONE        // teeth
            if (adx in 2..3 && dy in 6..7) return GLOW                          // eyes
            if (adx == 0 && dy == 5) return STONE                               // nasal hole
            if (adx <= 4) return if (dy == VEST_TOP) BRICKS else BONE
            return BRICKS
        }
        if (dy == -1) return if (adx == 0 && dz % 2 == 1) GLOW else if (adx <= 1) POLISHED else BRICKS
        if (adx == VEST_HALF || dy == VEST_TOP) {
            if (adx == VEST_HALF && dy == 3 && dz == 49) return GLOW
            if (adx == VEST_HALF && dy in 0..VEST_TOP - 1 && (dz == 48 || dz == VEST_MAX)) return CHISELED
            return BRICKS
        }
        // chains with lanterns, skull posts at the tower door
        if (adx == 3 && dz == 49 && dy >= 6) return CHAIN
        if (adx == 3 && dz == 49 && dy == 5) return HANGING
        if (adx == 4 && dz == VEST_MAX && dy == 0) return BONE
        if (adx == 4 && dz == VEST_MAX && dy == 1) return Blocks.WITHER_SKELETON_SKULL.defaultState.with(net.minecraft.block.SkullBlock.ROTATION, 8)
        return AIR
    }

    // ---- the procession ring

    /** Bone arches every 30 degrees, lamp posts every 15 degrees in between. */
    private fun ring(dx: Int, dy: Int, dz: Int): BlockState? {
        if (dy < -4 || dy > 6) return null
        val r = sqrt((dx * dx + dz * dz).toDouble())
        val off = r - RING_R
        val lat = abs(off)
        if (lat > RING_OUT) return null
        if (inTowerFoot(dx, dz)) return null
        if (HeartVaultPiece.alcoveDist(dx, dy, dz) < 4.8) return null
        if (dy <= -2) return STONE
        var deg = Math.toDegrees(atan2(dz.toDouble(), dx.toDouble()))
        if (deg < 0) deg += 360.0
        val s = deg * PI / 180.0 * RING_R                       // arc length
        // direction to the tower (south, 90 degrees): +1 = increasing angle
        val dir = if (deg > 90.0 && deg < 270.0) -1 else 1
        if (dy == -1) {
            if (lat > RING_HALF) return CHISELED
            // chevrons pointing to the tower
            val u = s - 7.0 * floor(s / 7.0) - 3.5
            if (lat <= 1.7 && abs(dir * u + lat) < 0.55) return VEIN
            return if (lat < 1.0) POLISHED else BRICKS
        }
        // arches
        val archDeg = Math.round(deg / 30.0) * 30.0
        val archDist = abs(deg - archDeg) * PI / 180.0 * r
        val arch = archDist < 0.6 && abs(archDeg - 90.0) > 20 && archDeg % 360.0 != 0.0   // not at the tower, not in the alcove
        if (arch) {
            if (lat in 1.6..2.8 && dy in 0..4) return BONE.with(PillarBlock.AXIS, Direction.Axis.Y)
            if (lat <= 2.8 && dy == 5) return if (lat < 0.8) CHISELED else BONE.with(PillarBlock.AXIS, Direction.Axis.Z)
            if (lat < 0.6 && dy == 4) return HANGING
        }
        // lamp posts at the inner edge, every 15 degrees between the arches
        val postDeg = Math.round((deg - 15.0) / 30.0) * 30.0 + 15.0
        val post = abs(deg - postDeg) * PI / 180.0 * r < 0.55
        if (post && off < 0 && lat in 1.5..2.6) {
            if (dy == 0) return WALL
            if (dy == 1) return STANDING
        }
        if (lat <= RING_HALF && dy in 0..4) return AIR
        if (lat <= 1.3 && dy == 5) return AIR
        return null
    }
}
