package dev.henny.hha_blood.hollows

import net.minecraft.util.math.BlockPos
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The old roads of the Vein Hollows (new worlds): five paved, lit processional roads that run from the far parts
 * of the region in gentle curves to the procession ring around the Slaughter Pit (PitApproach). Glowing chevrons in
 * the road point towards the pit. Each road has a station shaft up to the surface ([stations]), so wherever you
 * enter the region you find a road, and every road ends at the pit.
 *
 * Pure geometry (deterministic from the arena centre); carved by [HollowsTerrain], shafts by VeinShaftFeature.
 */
object HollowsRoads {
    const val COUNT = 5
    /** Roads start inside the procession ring and run outwards up to here. */
    const val START_R = 59.0
    const val END_R = HollowsRegion.BASE_RADIUS + HollowsRegion.EDGE_NOISE
    /** Distance of the station shafts from the pit. */
    private val STATION_R = doubleArrayOf(235.0, 300.0, 190.0, 330.0, 265.0)

    class Hit(
        /** Signed sideways distance from the centre line. */
        val side: Double,
        /** Half width of the road here. */
        val half: Double,
        /** Distance from the pit (the "along" coordinate). */
        val r: Double,
        /** Walking level (y of the feet). */
        val floor: Int,
        val index: Int,
    )

    private fun baseAngle(c: BlockPos, k: Int): Double {
        val seed = HollowsGen.rand(c.x, 7, c.z, 4242)
        var a = seed * 2 * PI + k * 2 * PI / COUNT
        // keep away from the stair tower in the south (90 degrees) and the kennel in the north (270 degrees)
        for (avoid in doubleArrayOf(PI / 2, 3 * PI / 2)) {
            val d = wrap(a - avoid)
            if (abs(d) < 0.38) a = avoid + (if (d >= 0) 0.38 else -0.38)
        }
        return a
    }

    /** Centre line angle of road [k] at distance [r] from the pit (gentle curves, straight near the ring). */
    fun angle(c: BlockPos, k: Int, r: Double): Double {
        val ph = k * 1.7 + HollowsGen.rand(c.x, k, c.z, 4243) * 6.0
        val amp = ((r - 64.0) / 140.0).coerceIn(0.0, 1.0)
        return baseAngle(c, k) + amp * (0.26 * sin(r / 97.0 + ph) + 0.11 * sin(r / 41.0 + 2 * ph))
    }

    fun floorAt(k: Int, r: Double): Int {
        val ramp = ((r - 70.0) / 50.0).coerceIn(0.0, 1.0)
        return HeartVaultPiece.FLOOR + (ramp * (2.4 * sin(r / 61.0 + k * 2.1) + 1.0 * sin(r / 23.0 + k))).roundToInt()
    }

    fun halfWidth(k: Int, r: Double): Double = 2.9 + 0.7 * sin(r / 31.0 + k * 1.3)

    private fun wrap(a: Double): Double {
        var d = a % (2 * PI)
        if (d > PI) d -= 2 * PI
        if (d < -PI) d += 2 * PI
        return d
    }

    /** The road at a column, null if none. */
    fun at(c: BlockPos, x: Int, z: Int): Hit? {
        val dx = (x - c.x).toDouble()
        val dz = (z - c.z).toDouble()
        val r = sqrt(dx * dx + dz * dz)
        if (r < START_R || r > END_R) return null
        val th = atan2(dz, dx)
        for (k in 0 until COUNT) {
            val side = wrap(th - angle(c, k, r)) * r
            val half = halfWidth(k, r)
            if (abs(side) <= half + 1.2) return Hit(side, half, r, floorAt(k, r), k)
        }
        return null
    }

    /** Station shafts: one per road (x, z, walking level). */
    fun stations(c: BlockPos): List<Triple<Int, Int, Int>> = (0 until COUNT).map { k ->
        val r = STATION_R[k]
        val a = angle(c, k, r)
        Triple(c.x + (cos(a) * r).roundToInt(), c.z + (sin(a) * r).roundToInt(), floorAt(k, r))
    }
}
