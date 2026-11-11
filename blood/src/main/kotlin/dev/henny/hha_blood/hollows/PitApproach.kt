package dev.henny.hha_blood.hollows

import net.minecraft.block.AmethystClusterBlock
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.ChainBlock
import net.minecraft.block.LanternBlock
import net.minecraft.block.PillarBlock
import net.minecraft.block.SkullBlock
import net.minecraft.block.StairsBlock
import net.minecraft.block.WallBannerBlock
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
 * The way to the Slaughter Pit (Mats: "kein klarer Weg/Eingang", then "der Weg unten vom Treppenturm bis zur Arena
 * soll laenger gehen, dramatischer"). Part of the arena design ([HeartVaultPiece.design]), so the rebuild of existing
 * arenas places it too. Everything stays within |dx| <= 70, dz -70..[MAX_DZ] of the arena centre (the rebuild range).
 *
 *  - **Das Gerippe** (landmark): on the Nether surface above the tower a plaza with a giant rib cage of bone
 *    arching over a square stairwell, glowing veins on the bones, burning braziers and two geysers of rising blood
 *    on the wings. The plaza sits at the first open air above the lava sea of the tower column (sampled from the
 *    Nether noise, [HollowsRegion.plazaY]) - the same in every run, also for already generated worlds.
 *  - **Der Abstieg**: a 19x19 stair tower with a 5 wide spiral stair (stair blocks, landings in the corners,
 *    lamps, railings) around a column of rising blood (the elevator), from the plaza down to [TOWER_BASE].
 *  - **Der Schlachtweg** (round "Weg laenger"): from the tower bottom a long processional hall descends to the pit,
 *    ~66 blocks, every section with its own rhythm, the fire gets sparser and the light redder towards the pit:
 *      1. Gang der Gefesselten - narrow and low, chained skeletons and fire bowls in niches, lanterns (warm light),
 *         four steps down at its end;
 *      2. Halle der Hoerner - wide and high, a nave of pillars crowned with horned bull skulls, blood flowing in
 *         channels along the aisles towards the pit, few braziers, glowing horn tips;
 *      3. Der Blutschlund - a chasm with a lake of blood, a bone bridge with rib arches (glowing keystones), blood
 *         pouring from wounds in the ceiling and from the channels, gibbets with skulls (an escape stair leads out
 *         of the lake);
 *      4. Die grosse Treppe - eight wide steps down with balustrade lamps and banners, the first view of the skull;
 *      5. Das Schaedeltor - a tall gate hall, the outer face of the titan skull as a horned bull skull with
 *         watching eyes, blood channels running into its mouth (= the tunnel into the dome), watching eyes in the
 *         walls. The procession ring enters it through arches from both sides.
 *    The hall is closed (an explicit envelope of walls and roof, no openings to the caves), so whoever comes down
 *    the tower walks the whole way; the heartbeat of the pit grows louder along it (client Heartbeat).
 *  - **Der Prozessionsring**: a paved, lit road around the whole arena (radius [RING_R]) with bone arches, lamp
 *    posts and glowing chevrons in the floor that point towards the gate hall. It cuts through the rock and through
 *    every cave around the arena, so whoever comes through the caves hits the road. It runs through the gate hall
 *    and through the alcove of the east tunnel. New worlds also get roads from far away ([HollowsTerrain]).
 *
 * v0.9 had the tower right behind a small vestibule (tower centre dz 61, see [legacy]); the rebuild clears it.
 */
object PitApproach {
    /** Centre of the stair tower (dz from the arena centre). The tower covers |dx| <= 9, dz 114..132. */
    const val TOWER_DZ = 123
    const val TOWER_OUT = 9
    /** Walking level (dy) at the bottom of the tower = the start of the processional hall. */
    const val TOWER_BASE = 12
    /** Southernmost dz the design touches (plaza foot). Must stay within 8 chunks of the start chunk (135). */
    const val MAX_DZ = TOWER_DZ + TOWER_OUT
    private const val LANE_MIN = 3
    private const val LANE_MAX = 7
    /** Rise per side and per full turn of the stair. */
    private const val SIDE_RISE = 5
    private const val TURN_RISE = 20

    /** The plaza (and its wings) on the surface: |tx| <= WING, |tz| <= TOWER_OUT. */
    const val WING = 17
    /** Air carved above the plaza level. */
    private const val CLEAR = 10

    // ---- the processional hall, sections from the pit to the tower (dz ranges, half width of the air, floor)
    /** The outer skull face (its mouth is the tunnel into the dome, HeartVaultPiece.tunnel ends at dz 46). */
    const val GATE_FACE = 47
    const val GATE_MIN = 48
    const val GATE_MAX = 65
    const val GATE_HALF = 9
    const val GATE_TOP = 15
    const val STAIR_MIN = 66
    const val STAIR_MAX = 75
    const val STAIR_HALF = 4
    const val CHASM_MIN = 76
    const val CHASM_MAX = 87
    const val CHASM_HALF = 7
    const val HORNS_MIN = 88
    const val HORNS_MAX = 99
    const val HORNS_HALF = 6
    const val CHAINS_MIN = 100
    const val CHAINS_MAX = TOWER_DZ - TOWER_OUT - 1
    const val CHAINS_HALF = 2
    /** Walking level of the bridge, the hall of horns and the top of the great stair. */
    const val MID_F = 8
    /** Highest air (dy) of the hall behind the gate hall. */
    const val HALL_TOP = 17

    private const val S_GATE = 0
    private const val S_STAIR = 1
    private const val S_CHASM = 2
    private const val S_HORNS = 3
    private const val S_CHAINS = 4
    private val SEC_MIN = intArrayOf(GATE_MIN, STAIR_MIN, CHASM_MIN, HORNS_MIN, CHAINS_MIN)
    private val SEC_MAX = intArrayOf(GATE_MAX, STAIR_MAX, CHASM_MAX, HORNS_MAX, CHAINS_MAX)
    private val SEC_HALF = intArrayOf(GATE_HALF, STAIR_HALF, CHASM_HALF, HORNS_HALF, CHAINS_HALF)

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
    private val FALL get() = PitBlocks.BLOOD_FALL.defaultState
    private val BLOOD get() = HollowsFluids.SOURCE
    private fun stairs(f: Direction): BlockState = HollowsBlocks.BLOOD_STONE_BRICK_STAIRS.defaultState.with(StairsBlock.FACING, f)
    private fun bone(axis: Direction.Axis): BlockState = BONE.with(PillarBlock.AXIS, axis)
    /** Skull looking towards the middle of the hall (rotation 4 = west, 12 = east). */
    private fun skullInwards(dx: Int): BlockState = Blocks.WITHER_SKELETON_SKULL.defaultState.with(SkullBlock.ROTATION, if (dx > 0) 4 else 12)
    private fun eye(f: Direction): BlockState = PitBlocks.WATCHING_EYE.defaultState.with(WatchingEyeBlock.FACING, f)
    private fun inwards(dx: Int): Direction = if (dx > 0) Direction.WEST else Direction.EAST

    /** Plaza walking level as dy (feet), at least 22 (= y 32, just above the lava sea). */
    fun plazaDy(): Int = HollowsRegion.plazaY() - HeartVaultPiece.FLOOR

    // ----------------------------------------------------------------------------------------- footprint

    fun inTowerFoot(dx: Int, dz: Int): Boolean = abs(dx) <= TOWER_OUT && abs(dz - TOWER_DZ) <= TOWER_OUT
    fun inPlazaFoot(dx: Int, dz: Int): Boolean = abs(dx) <= WING && abs(dz - TOWER_DZ) <= TOWER_OUT

    private fun section(dz: Int): Int {
        for (s in 0..4) if (dz in SEC_MIN[s]..SEC_MAX[s]) return s
        return -1
    }

    /** Columns of the processional hall (incl. its walls and the skull face). */
    fun inHallFoot(dx: Int, dz: Int): Boolean {
        if (dz == GATE_FACE) return abs(dx) <= GATE_HALF + 2
        val s = section(dz)
        return s >= 0 && abs(dx) <= SEC_HALF[s] + 2
    }

    /** Walking level (feet, dy) of the hall at dz (the great stair and the steps behind the hall of horns ramp). */
    fun hallFloor(dz: Int): Int = when (section(dz)) {
        S_STAIR -> min(dz - (STAIR_MIN - 1), MID_F)
        S_CHASM, S_HORNS -> MID_F
        S_CHAINS -> min(MID_F + 1 + dz - CHAINS_MIN, TOWER_BASE)
        else -> 0
    }

    fun ringLat(dx: Int, dz: Int): Double = abs(sqrt((dx * dx + dz * dz).toDouble()) - RING_R)

    /** Columns the approach uses (for HeartVaultPiece.columnUsed). */
    fun columnUsed(dx: Int, dz: Int): Boolean =
        inPlazaFoot(dx, dz) || inBridgeFoot(dx, dz) || inHallFoot(dx, dz) || ringLat(dx, dz) <= RING_OUT + 0.01

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
        if (abs(dx) <= GATE_HALF + 2 + margin && dz in (GATE_FACE - margin)..(CHAINS_MAX + margin) && dy in (-4 - margin)..(HALL_TOP + 2 + margin)) return true
        return dy in (-4 - margin)..(7 + margin) && ringLat(dx, dz) <= RING_OUT + margin
    }

    // ----------------------------------------------------------------------------------------- design

    /** The approach block at (dx, dy, dz) from the arena centre, null = not part of it. */
    fun block(dx: Int, dy: Int, dz: Int): BlockState? {
        if (inPlazaFoot(dx, dz)) {
            // under the wings the plaza only exists near the surface
            val v = plazaColumn(dx, dy, dz - TOWER_DZ)
            if (v != null || inTowerFoot(dx, dz)) return v
        }
        if (inHallFoot(dx, dz)) hall(dx, dy, dz)?.let { return it }
        if (inBridgeFoot(dx, dz)) bridge(dx, dy, dz - TOWER_DZ)?.let { return it }
        return ring(dx, dy, dz)
    }

    // ---- the stair tower

    private class Lane(val level: Int, val landing: Boolean, val facing: Direction, val along: Int)

    /**
     * Base level (0..19, relative to [TOWER_BASE]) of a lane cell, the stair goes round counter-clockwise seen from
     * above, starting in the south-east corner at the floor: south side westwards, west side northwards, north side
     * eastwards, east side southwards, then the next turn 20 higher.
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
        // the tower itself works relative to its bottom: y = level above TOWER_BASE, q = plaza level above it
        val y = dy - TOWER_BASE
        val q = p - TOWER_BASE
        if (y <= -2) return STONE
        if (m >= 8) return towerWall(tx, y, tz, q, m)
        if (m <= 1) {
            if (y == -1) return if (m == 0) GLOW else POLISHED
            return RISING
        }
        if (m == 2) {
            if (y == -1) return if (atx == 2 && atz == 2) GLOW else POLISHED
            // railing beside the stair (and its edge block), so nobody falls into the well
            val l = lane(tx + (if (atx == 2) Integer.signum(tx) else 0), tz + (if (atz == 2) Integer.signum(tz) else 0))
            var lv = l.level
            while (lv <= q) {
                if (lv >= 1 && y == lv) return WALL
                if (lv >= 1 && y == lv - 1) return BRICKS
                lv += TURN_RISE
            }
            return AIR
        }
        // the stair lanes (m 3..7)
        if (y == -1) return if (atx == 5 && atz == 5) GLOW else if ((tx + tz) and 1 == 0) POLISHED else BRICKS
        val l = lane(tx, tz)
        var lv = l.level
        while (lv <= q) {
            if (lv >= 1 && y == lv - 1) return if (l.landing) POLISHED else stairs(l.facing)
            if (lv >= 2 && y == lv - 2) return BRICKS
            lv += TURN_RISE
        }
        // the top landing: the stair goes on flat at plaza level for one side, the exit to the rim
        if (y == q - 1 && landingAt(tx, tz, q)) return if (m == LANE_MAX) CHISELED else POLISHED
        // a barrier where the landing ends (the stairwell below stays open)
        if (y == q - 1 && touchesLanding(tx, tz, q)) return BRICKS
        return AIR
    }

    /** Tower wall, [y] and [q] relative to the tower bottom. The only door leads north into the hall. */
    private fun towerWall(tx: Int, y: Int, tz: Int, q: Int, m: Int): BlockState {
        val atx = abs(tx); val atz = abs(tz)
        if (tz <= -8 && atx <= CHAINS_HALF && y in 0..5) return AIR
        if (tz <= -8 && ((atx == CHAINS_HALF + 1 && y in 0..5) || (atx <= CHAINS_HALF + 1 && y == 6))) return CHISELED
        if (y == q - 1) return CHISELED
        if (m == 9) return if (y == q - 2 || y % 8 == 7) POLISHED else BRICKS
        // inner face: a lamp above the middle of every flight, vein stripes in the corners
        if (atx != atz) {
            val inner = lane(if (atx == 8) tx - Integer.signum(tx) else tx, if (atz == 8) tz - Integer.signum(tz) else tz)
            if (!inner.landing && inner.along % 2 == 0) {
                var lv = inner.level
                while (lv <= q - 3) {
                    if (y == lv + 2) return GLOW
                    lv += TURN_RISE
                }
            }
        }
        if (atx == atz) return if (y % 3 == 0) VEIN else CHISELED
        // lights at the bottom of the stairwell (the door is cut out above)
        if (y == 2 && atx != atz) {
            val inner = lane(if (atx == 8) tx - Integer.signum(tx) else tx, if (atz == 8) tz - Integer.signum(tz) else tz)
            if (!inner.landing && inner.along % 2 == 0) return GLOW
        }
        return if (y % 8 == 7) POLISHED else BRICKS
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
        val q = p - TOWER_BASE
        if (m <= 1 && h <= 1) return RISING
        if (h == 0) {
            if (m == 2) return WALL
            // railing where the top landing ends, parapet around the stairwell (open towards the landing)
            if (m in LANE_MIN..LANE_MAX && touchesLanding(tx, tz, q)) return WALL
            if (m == 8 && !landingAt(tx - Integer.signum(tx) * (if (atx == 8) 1 else 0), tz - Integer.signum(tz) * (if (atz == 8) 1 else 0), q)) return WALL
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

    // ---- the processional hall ("Der Schlachtweg")

    /** Highest air of a section at |dx| (vaulted ceilings). */
    private fun topOf(s: Int, adx: Int): Int = when (s) {
        S_GATE -> GATE_TOP - max(0, adx - 8)
        S_HORNS -> HALL_TOP - max(0, adx - 4)
        S_CHAINS -> if (adx >= 2) HALL_TOP - 1 else HALL_TOP
        else -> HALL_TOP
    }

    private fun hall(dx: Int, dy: Int, dz: Int): BlockState? {
        val adx = abs(dx)
        if (dz == GATE_FACE) return if (dy in -4..GATE_TOP + 2) face(dx, dy) else null
        val s = section(dz)
        val hw = SEC_HALF[s]
        val roof = (if (s == S_GATE) GATE_TOP else HALL_TOP) + 2
        if (dy < -4 || dy > roof) return null
        // end walls where the next section is narrower (1 thick, the rock behind closes it)
        if (dz == SEC_MAX[s] && s < S_CHAINS && adx > SEC_HALF[s + 1]) return endWall(dx, dy, s)
        if (dz == SEC_MIN[s] && s > S_GATE && adx > SEC_HALF[s - 1]) return endWall(dx, dy, s)
        val f = hallFloor(dz)
        if (adx > hw) return sideWall(s, dx, dy, dz, f)
        val top = topOf(s, adx)
        if (dy > top) {
            // wounds in the chasm ceiling feed the blood falls
            if (s == S_CHASM && dy == top + 1 && chasmFall(dx, dz)) return GLOW
            return if (dy == top + 1) CHISELED else BRICKS
        }
        return when (s) {
            S_GATE -> gateHall(dx, dy, dz)
            S_STAIR -> greatStair(dx, dy, dz, f)
            S_CHASM -> chasm(dx, dy, dz)
            S_HORNS -> horns(dx, dy, dz)
            else -> chains(dx, dy, dz, f)
        }
    }

    private fun endWall(dx: Int, dy: Int, s: Int): BlockState =
        if (s == S_CHASM && dy in 9..15 && Math.floorMod(dx + dy, 5) == 0) VEIN else if (dy % 6 == 5) POLISHED else BRICKS

    /** Side walls (two thick), the inner face decorated per section. */
    private fun sideWall(s: Int, dx: Int, dy: Int, dz: Int, f: Int): BlockState {
        val adx = abs(dx)
        val hw = SEC_HALF[s]
        val inner = adx == hw + 1
        if (s == S_GATE) gateWall(dx, dy, dz, inner)?.let { return it }
        if (dy < f - 1 && s != S_CHASM) return STONE
        if (!inner) return if (dy % 6 == 5) POLISHED else BRICKS
        when (s) {
            S_STAIR -> {
                if (dy == f + 3 && (dz == 69 || dz == 72)) return GLOW
                if (dy == f - 1 || dy == HALL_TOP) return CHISELED
            }
            S_CHASM -> {
                if (dy == 11 && Math.floorMod(dz, 4) == 2) return GLOW
                if (dy == 7) return CHISELED
                if (HollowsGen.rand(dx, dy, dz, 51) < 0.14) return VEIN
                return if (dy < 0) STONE else BRICKS
            }
            S_HORNS -> {
                if (dy == MID_F || dy == 14) return CHISELED
                if (dy == 12 && Math.floorMod(dz, 4) == 0) return GLOW
                if (dy in 9..13 && Math.floorMod(dz, 4) == 2) return POLISHED
            }
            S_CHAINS -> chainNiche(dx, dy, dz, f)?.let { return it }
        }
        return if (dy % 6 == 5) POLISHED else BRICKS
    }

    // -- 1. Gang der Gefesselten (dz 100..113): narrow, low, chained skeletons and fire bowls, lanterns

    private fun chains(dx: Int, dy: Int, dz: Int, f: Int): BlockState {
        val adx = abs(dx)
        if (dy < f - 1) return STONE
        if (dy == f - 1) {
            if (dz < CHAINS_MIN + 3) return stairs(Direction.SOUTH)          // four steps down into the hall of horns
            if (adx == 0) return if (Math.floorMod(dz, 3) == 0) VEIN else POLISHED
            return if (Math.floorMod(dz - adx, 3) == 0) VEIN else BRICKS    // chevrons pointing to the pit
        }
        if (dx == 0 && dy == HALL_TOP && Math.floorMod(dz, 4) == 0 && dz >= CHAINS_MIN + 4) return HANGING
        return AIR
    }

    /** Niches in the walls of the first hall: chained skeletons and fire bowls (alternating, both sides). */
    private fun chainNiche(dx: Int, dy: Int, dz: Int, f: Int): BlockState? {
        if (dz < CHAINS_MIN + 3) return null
        val k = Math.floorMod(dz - (CHAINS_MIN + 3), 4)
        val h = dy - f
        if (k == 2) {
            // a chained skeleton: bones, the skull, shackle bars, the chain into the ceiling
            return when (h) {
                0 -> bone(Direction.Axis.Y)
                1 -> bone(Direction.Axis.Y)
                2 -> skullInwards(dx)
                3 -> Blocks.IRON_BARS.defaultState
                4 -> CHAIN
                else -> null
            }
        }
        if (k == 0) {
            // a fire bowl in the wall
            return when (h) {
                0 -> CHISELED
                1 -> Blocks.NETHERRACK.defaultState
                2 -> Blocks.FIRE.defaultState
                3 -> AIR
                4 -> CHISELED
                else -> null
            }
        }
        if (h == -1) return CHISELED
        return null
    }

    // -- 2. Halle der Hoerner (dz 88..99): nave of horned bull skull pillars, blood channels in the aisles

    private fun hornPillar(dz: Int): Boolean = dz == 90 || dz == 94 || dz == 98

    private fun horns(dx: Int, dy: Int, dz: Int): BlockState {
        val adx = abs(dx)
        val f = MID_F
        if (dy < f - 1) return STONE
        if (dy == f - 1) {
            if (adx == 5 && dz in (HORNS_MIN + 1)..(HORNS_MAX - 1)) return BLOOD           // channels flowing to the pit
            if (adx == 4 && hornPillar(dz)) return CHISELED
            if (adx <= 1) return if (Math.floorMod(dz - adx, 4) == 0) VEIN else POLISHED
            return if (adx == 4 || adx == 6) POLISHED else BRICKS
        }
        // railing along the edge of the chasm (the escape stair from the lake ends at dx 6)
        if (dz == HORNS_MIN && dy == f && (adx in 2..5 || dx == -6)) return WALL
        if (adx == 4 && hornPillar(dz)) {
            // a pillar crowned with a bull skull: bone head, glowing horn tips, a skull looking into the nave
            return when (dy - f) {
                0 -> CHISELED
                in 1..3 -> POLISHED
                4 -> VEIN
                5 -> POLISHED
                6 -> CHISELED
                7 -> bone(Direction.Axis.Y)
                8 -> skullInwards(dx)
                else -> AIR
            }
        }
        if (hornPillar(dz) && dy - f == 7 && (adx == 3 || adx == 5)) return bone(Direction.Axis.X)
        if (hornPillar(dz) && dy - f == 8 && (adx == 3 || adx == 5)) return GLOW
        // braziers against the walls (only four - the fire gets rarer)
        if (adx == 6 && (dz == 92 || dz == 96)) {
            when (dy - f) {
                0 -> return CHISELED
                1 -> return Blocks.NETHERRACK.defaultState
                2 -> return Blocks.FIRE.defaultState
            }
        }
        return AIR
    }

    // -- 3. Der Blutschlund (dz 76..87): chasm with a lake of blood, bone bridge, falls, gibbets

    private val GIBBETS = listOf(-4 to 78, 4 to 77, -4 to 85, 3 to 84)

    private fun chasmFall(dx: Int, dz: Int): Boolean = (dx == -6 && dz == 79) || (dx == -5 && dz == 84) || (dx == 5 && dz == 81)

    private fun rib(dz: Int): Boolean = dz == 77 || dz == 80 || dz == 83 || dz == 86

    private fun chasm(dx: Int, dy: Int, dz: Int): BlockState {
        val adx = abs(dx)
        val f = MID_F
        // escape stair out of the lake on the east side (up to the aisle of the hall of horns)
        if (dx >= 6 && dz >= CHASM_MIN + 2 && !(dx == 7 && dz == CHASM_MAX)) {
            val sy = -2 + (dz - (CHASM_MIN + 2))
            if (dy < sy) return STONE
            if (dy == sy) return stairs(Direction.SOUTH)
        }
        // bridge piers standing in the lake
        if (adx <= 1 && (dz == 80 || dz == 84) && dy in -4..5) return if (dy == -4) STONE else bone(Direction.Axis.Y)
        // the bone bridge with its railings and rib arches
        if (adx <= 1 && dy == f - 1) return if (dx == 0 && Math.floorMod(dz, 4) == 0) VEIN else bone(Direction.Axis.Z)
        if (adx <= 1 && dy == f - 2) return bone(Direction.Axis.X)
        if (adx == 2 && dy in (f - 2)..(f - 1)) return bone(Direction.Axis.Y)
        if (adx == 2 && dy == f) return if (rib(dz)) bone(Direction.Axis.Y) else WALL
        if (rib(dz)) {
            if (adx == 2 && dy in (f + 1)..(f + 2)) return bone(Direction.Axis.Y)
            if (adx == 1 && dy == f + 3) return bone(Direction.Axis.X)
            if (adx == 0 && dy == f + 4) return GLOW                          // glowing keystone
        }
        // the lake
        if (dy == -4) return STONE
        if (dy in -3..-2) return BLOOD
        // blood falls from wounds in the ceiling, spouts where the channels of the hall of horns end
        if (chasmFall(dx, dz) && dy >= -1) return FALL
        if (adx == 5 && dz == CHASM_MAX && dy in -1..(f - 1)) return FALL
        // gibbets: a cage of bars with a skull, hanging on a chain
        for ((gx, gz) in GIBBETS) {
            if (dx != gx || dz != gz) continue
            return when {
                dy in (f - 2)..(f - 1) -> Blocks.IRON_BARS.defaultState
                dy == f -> Blocks.WITHER_SKELETON_SKULL.defaultState.with(SkullBlock.ROTATION, if (gx > 0) 4 else 12)
                dy > f -> CHAIN
                else -> AIR
            }
        }
        return AIR
    }

    // -- 4. Die grosse Treppe (dz 66..75): eight wide steps down to the gate hall

    private fun greatStair(dx: Int, dy: Int, dz: Int, f: Int): BlockState {
        val adx = abs(dx)
        if (dy < f - 1) return STONE
        if (dy == f - 1) {
            if (dz > STAIR_MIN + 7) return if (adx == STAIR_HALF) CHISELED else if (adx <= 1 && Math.floorMod(dz, 2) == 0) VEIN else POLISHED
            return if (adx == STAIR_HALF) POLISHED else stairs(Direction.SOUTH)
        }
        // balustrade with blood lamps, the landing's railing along the chasm
        if (adx == STAIR_HALF && dy == f) return WALL
        if (adx == STAIR_HALF && dy == f + 1 && (dz == 67 || dz == 70 || dz == 73)) return GLOW
        if (dz == STAIR_MAX && adx in 2..3 && dy == f) return WALL
        // two glowing braziers at the top of the stair
        if (adx == 3 && dz == STAIR_MAX - 1 && dy == f) return CHISELED
        if (adx == 3 && dz == STAIR_MAX - 1 && dy == f + 1) return GLOW
        // banners of the pit on the walls
        if (adx == STAIR_HALF && dy == 13 && (dz == 68 || dz == 71)) {
            val banner = if (dz == 68) Blocks.RED_WALL_BANNER else Blocks.BLACK_WALL_BANNER
            return banner.defaultState.with(WallBannerBlock.FACING, inwards(dx))
        }
        return AIR
    }

    // -- 5. Das Schaedeltor (dz 47..65): gate hall with the horned skull face, eyes, channels into its mouth

    private fun gatePillar(adx: Int, dz: Int): Boolean = adx in 7..8 && (dz in 53..54 || dz in 57..58)

    private fun gateHall(dx: Int, dy: Int, dz: Int): BlockState {
        val adx = abs(dx)
        SKULL_HORNS[key(dx, dy, dz)]?.let { return it }
        if (dy < -1) return STONE
        if (dy == -1) {
            if (adx == 5 && dz in (GATE_MIN + 1)..(GATE_MAX - 2)) return BLOOD           // channels into the skull's mouth
            if (gatePillar(adx, dz)) return CHISELED
            if (adx <= 2) return if (Math.floorMod(dz - adx, 4) == 0) VEIN else POLISHED
            if (adx == GATE_HALF) return CHISELED
            return if (adx == 4 || adx == 6) POLISHED else BRICKS
        }
        if (gatePillar(adx, dz)) {
            val top = topOf(S_GATE, adx)
            return when {
                dy == 0 || dy == top -> CHISELED
                adx == 7 && dy in 3..9 && (dz == 53 || dz == 57) -> VEIN
                dy == 11 -> CHISELED
                else -> BRICKS
            }
        }
        // horns growing out of the pillars towards the nave
        if ((dz in 53..54 || dz in 57..58)) {
            if (adx == 6 && dy == 11) return bone(Direction.Axis.X)
            if (adx == 5 && dy == 12) return bone(Direction.Axis.Y)
            if (adx == 5 && dy == 13) return GLOW
        }
        // the brow ridge of the skull (one block in front of the face)
        if (dz == GATE_MIN && dy == 11 && adx in 2..6) return BONE
        // two glowing braziers flanking the mouth
        if (adx == 3 && dz == GATE_MIN + 1) {
            if (dy == 0) return CHISELED
            if (dy == 1) return GLOW
        }
        // blood lanterns on chains
        if (adx == 4 && (dz == 52 || dz == 60)) {
            if (dy == 10) return GLOW
            if (dy > 10) return CHAIN
        }
        return AIR
    }

    /** Gate hall walls: watching eyes, lamps, and the arches where the procession ring passes through. */
    private fun gateWall(dx: Int, dy: Int, dz: Int, inner: Boolean): BlockState? {
        if (dy > GATE_TOP + 2) return null
        val lat = ringLat(dx, dz)
        if (lat <= RING_HALF && dy in -1..5) {
            return when {
                dy == -1 -> if (lat < 1.0) POLISHED else BRICKS
                dy <= 4 -> AIR
                lat <= 1.3 -> AIR
                else -> CHISELED
            }
        }
        if (lat <= RING_HALF + 1.2 && dy in -1..6) return CHISELED
        if (lat <= RING_HALF && dy == 6) return CHISELED
        if (dy < -1) return STONE
        if (!inner) return null
        if (dy == 7 && (dz == 50 || dz == 55)) return eye(inwards(dx))
        if (dy == 3 && (dz == 52 || dz == 56)) return GLOW
        if (dy == -1 || dy == 11) return CHISELED
        return null
    }

    /**
     * The outer face of the titan skull at dz 47, a horned bull skull over the whole width of the gate hall: the
     * mouth is the tunnel into the dome (|dx| <= 1, dy 0..3), teeth above it, a dark nasal cavity, watching eyes in
     * the sockets, the horns sweep out into the hall ([SKULL_HORNS]).
     */
    private fun face(dx: Int, dy: Int): BlockState {
        val adx = abs(dx)
        SKULL_HORNS[key(dx, dy, GATE_FACE)]?.let { return it }
        if (dy <= -2) return STONE
        if (dy == -1) return POLISHED
        if (adx > GATE_HALF || dy > GATE_TOP) return BRICKS
        val w = when {
            dy <= 4 -> 4.5
            dy <= 8 -> 4.5 + (dy - 4) * 0.8
            else -> 8.6 - ((dy - 11.5) / 4.2) * ((dy - 11.5) / 4.2) * 3.0
        }
        if (adx > w) return if (dy == 11 || dy == 0) CHISELED else BRICKS
        // the mouth and the teeth
        if (adx <= 1 && dy in 0..3) return AIR
        if (dy == 4 && adx <= 3) return if (adx % 2 == 1) AIR else BONE
        // eye sockets with watching eyes, a glowing brow inside
        if (adx in 3..5 && dy == 9) return if (adx == 4) eye(Direction.SOUTH) else HollowsBlocks.BLOOD_STONE.defaultState
        if (adx in 3..5 && dy == 10) return VEIN
        // nasal cavity and nostrils
        if (adx <= 1 && dy in 5..7) return HollowsBlocks.BLOOD_STONE.defaultState
        if (adx == 2 && dy == 5) return HollowsBlocks.BLOOD_STONE.defaultState
        // cracks of dried blood on the bone
        val crack = abs(HollowsGen.rand(dx / 2, dy / 3, 7, 23) - 0.5) < 0.05 && Math.floorMod(dx + dy, 3) == 0
        return if (crack) HollowsBlocks.BLOOD_STONE.defaultState else BONE
    }

    /** The skull's horns: tubes along a curve from the cranium out and up into the gate hall, glowing tips. */
    private val SKULL_HORNS: Map<Long, BlockState> by lazy {
        val m = HashMap<Long, BlockState>()
        for (side in intArrayOf(-1, 1)) {
            // quadratic curve: base at the cranium, out to the side, then up and forward
            val p0 = doubleArrayOf(5.6, 12.0, 47.4)
            val p1 = doubleArrayOf(9.7, 11.2, 48.3)
            val p2 = doubleArrayOf(8.3, 14.6, 50.6)
            var t = 0.0
            while (t <= 1.0) {
                val a = (1 - t) * (1 - t); val b = 2 * (1 - t) * t; val c = t * t
                val cx = side * (a * p0[0] + b * p1[0] + c * p2[0])
                val cy = a * p0[1] + b * p1[1] + c * p2[1]
                val cz = a * p0[2] + b * p1[2] + c * p2[2]
                val rad = 1.35 - 0.9 * t
                val r = rad.toInt() + 1
                for (x in -r..r) for (y in -r..r) for (z in -r..r) {
                    val px = cx.roundToInt() + x; val py = cy.roundToInt() + y; val pz = cz.roundToInt() + z
                    if (pz < GATE_FACE || abs(px) > GATE_HALF || py > topOf(S_GATE, abs(px))) continue
                    val d = (px - cx) * (px - cx) + (py - cy) * (py - cy) + (pz - cz) * (pz - cz)
                    if (d > rad * rad + 0.15) continue
                    m[key(px, py, pz)] = if (t > 0.86) GLOW else BONE
                }
                t += 0.02
            }
        }
        m
    }

    // ---- the procession ring

    /** Bone arches every 30 degrees, lamp posts every 15 degrees in between. */
    private fun ring(dx: Int, dy: Int, dz: Int): BlockState? {
        if (dy < -4 || dy > 6) return null
        val r = sqrt((dx * dx + dz * dz).toDouble())
        val off = r - RING_R
        val lat = abs(off)
        if (lat > RING_OUT) return null
        if (inHallFoot(dx, dz)) return null
        if (HeartVaultPiece.alcoveDist(dx, dy, dz) < 4.8) return null
        if (dy <= -2) return STONE
        var deg = Math.toDegrees(atan2(dz.toDouble(), dx.toDouble()))
        if (deg < 0) deg += 360.0
        val s = deg * PI / 180.0 * RING_R                       // arc length
        // direction to the gate hall (south, 90 degrees): +1 = increasing angle
        val dir = if (deg > 90.0 && deg < 270.0) -1 else 1
        if (dy == -1) {
            if (lat > RING_HALF) return CHISELED
            // chevrons pointing to the gate hall
            val u = s - 7.0 * floor(s / 7.0) - 3.5
            if (lat <= 1.7 && abs(dir * u + lat) < 0.55) return VEIN
            return if (lat < 1.0) POLISHED else BRICKS
        }
        // arches
        val archDeg = Math.round(deg / 30.0) * 30.0
        val archDist = abs(deg - archDeg) * PI / 180.0 * r
        val arch = archDist < 0.6 && abs(archDeg - 90.0) > 20 && archDeg % 360.0 != 0.0   // not at the gate hall, not in the alcove
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

    // ----------------------------------------------------------------------------------------- the v0.9 approach

    /** v0.9: tower centre dz 61 (tower |dx| <= 9, plaza |dx| <= 17, |dz - 61| <= 9), causeways |dz - 61| <= 3. */
    const val OLD_TOWER_DZ = 61

    /** Columns where v0.9 had its tower, plaza, landmark and causeways. */
    fun legacyColumn(dx: Int, dz: Int): Boolean =
        (abs(dx) <= WING && abs(dz - OLD_TOWER_DZ) <= TOWER_OUT) || (abs(dx) in (WING + 1)..BRIDGE_END && abs(dz - OLD_TOWER_DZ) <= 3)

    /**
     * What replaces the v0.9 approach where the current design has nothing (only used by the rebuild of an arena that
     * still has it, see PitArena): the old tower shaft is filled with blood stone up to the top of the region's fill,
     * above that (old plaza, landmark, causeways over the lava sea) the natural Nether comes back ([natural] = the
     * noise column of the generator: netherrack, lava sea or air). Null = leave the block.
     */
    fun legacy(dx: Int, dy: Int, dz: Int, natural: () -> BlockState): BlockState? {
        if (!legacyColumn(dx, dz)) return null
        val y = dy + HeartVaultPiece.FLOOR
        if (y <= HollowsGen.FILL_TOP) return if (abs(dx) <= TOWER_OUT && abs(dz - OLD_TOWER_DZ) <= TOWER_OUT && dy >= -4) STONE else null
        return natural()
    }
}
