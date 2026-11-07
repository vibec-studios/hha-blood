package dev.henny.hha_blood.hollows

import net.minecraft.block.AmethystClusterBlock
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.state.property.Properties
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.world.StructureWorldAccess
import net.minecraft.world.gen.feature.DefaultFeatureConfig
import net.minecraft.world.gen.feature.Feature
import net.minecraft.world.gen.feature.util.FeatureContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private fun isHollowsStone(s: BlockState) =
    s.isOf(HollowsBlocks.BLOOD_STONE) || s.isOf(HollowsBlocks.VEIN_STONE) || s.isOf(HollowsBlocks.BLOOD_CRYSTAL_BLOCK) || s.isOf(HollowsBlocks.CLOTTED_BLOOD)

/** Blood crystal clusters on cave walls/floors/ceilings, sometimes with a crystal block growth behind them. */
class BloodCrystalFeature : Feature<DefaultFeatureConfig>(DefaultFeatureConfig.CODEC) {
    override fun generate(context: FeatureContext<DefaultFeatureConfig>): Boolean {
        val world = context.world
        val random = context.random
        val origin = context.origin
        if (HollowsGen.nearArena(world, origin, 6)) return false
        var placed = false
        val growth = random.nextFloat() < 0.2f
        val tries = if (growth) 40 else 14
        for (t in 0 until tries) {
            val p = origin.add(random.nextInt(7) - 3, random.nextInt(5) - 2, random.nextInt(7) - 3)
            if (!world.getBlockState(p).isAir) continue
            val dirs = Direction.entries.shuffled(kotlin.random.Random(random.nextLong()))
            for (d in dirs) {
                val support = p.offset(d.opposite)
                val s = world.getBlockState(support)
                if (!isHollowsStone(s) || s.isOf(HollowsBlocks.CLOTTED_BLOOD)) continue
                if (growth && random.nextFloat() < 0.5f) world.setBlockState(support, HollowsBlocks.BLOOD_CRYSTAL_BLOCK.defaultState, HollowsGen.FLAGS)
                val block = if (random.nextFloat() < 0.55f) HollowsBlocks.BLOOD_CRYSTAL_CLUSTER else HollowsBlocks.SMALL_BLOOD_CRYSTAL
                world.setBlockState(p, block.defaultState.with(AmethystClusterBlock.FACING, d), HollowsGen.FLAGS)
                placed = true
                break
            }
        }
        return placed
    }
}

/**
 * Giant half-buried rib cages of bone (fits the "rib" style of the armour): a spine sunk into the floor
 * with vertebrae on top and arched ribs to both sides, smaller towards both ends.
 *
 * Kept inside the chunk and its direct neighbours (features may not write further), only built on
 * fairly flat floor, and every rib is only placed if its foot stands on solid ground and the whole arc
 * is free - no floating or half-cut bones.
 */
class RibBonesFeature : Feature<DefaultFeatureConfig>(DefaultFeatureConfig.CODEC) {
    override fun generate(context: FeatureContext<DefaultFeatureConfig>): Boolean {
        val world = context.world
        val random = context.random
        val cx = ((context.origin.x shr 4) shl 4) + 8
        val cz = ((context.origin.z shr 4) shl 4) + 8
        val start = BlockPos(cx + random.nextInt(5) - 2, context.origin.y, cz + random.nextInt(5) - 2)
        val base = floorY(world, start.x, start.y, start.z, 12) ?: return false
        if (base < HollowsGen.LAKE_Y + 1) return false
        if (HollowsGen.nearArena(world, BlockPos(start.x, base, start.z), 18)) return false

        val yaw = random.nextDouble() * Math.PI * 2
        val ax = cos(yaw); val az = sin(yaw)          // spine direction
        val px = -az; val pz = ax                     // sideways
        val half = 4 + random.nextInt(3)              // spine half length (<= 6)
        val width = 3.0 + random.nextDouble() * 1.5
        val height = 4.0 + random.nextDouble() * 2.5
        val spineAxis = if (abs(ax) > abs(az)) Direction.Axis.X else Direction.Axis.Z
        val sideAxis = if (spineAxis == Direction.Axis.X) Direction.Axis.Z else Direction.Axis.X
        val bone = Blocks.BONE_BLOCK.defaultState

        // the floor must be fairly flat along the spine
        for (s in -half..half) {
            val f = floorY(world, (start.x + ax * s).roundToInt(), base + 3, (start.z + az * s).roundToInt(), 6) ?: return false
            if (abs(f - base) > 1) return false
        }

        var placed = false
        // spine: sunk into the floor, vertebrae every second block on top
        for (s in -half..half) {
            val x = (start.x + ax * s).roundToInt(); val z = (start.z + az * s).roundToInt()
            val f = floorY(world, x, base + 3, z, 6) ?: continue
            val sunk = BlockPos(x, f - 1, z)
            if (isHollowsStone(world.getBlockState(sunk))) { world.setBlockState(sunk, bone.with(Properties.AXIS, spineAxis), HollowsGen.FLAGS); placed = true }
            if (s % 2 == 0 && world.getBlockState(BlockPos(x, f, z)).isAir) world.setBlockState(BlockPos(x, f, z), bone.with(Properties.AXIS, Direction.Axis.Y), HollowsGen.FLAGS)
        }
        // ribs
        var rib = -half + 1
        while (rib <= half - 1) {
            val k = 1.0 - (rib.toDouble() / half) * (rib.toDouble() / half) * 0.55
            val w = width * k; val h = height * k
            for (side in intArrayOf(-1, 1)) {
                val cells = LinkedHashMap<BlockPos, Direction.Axis>()
                var phi = 0.0
                var last: BlockPos? = null
                while (phi <= Math.PI * 0.45) {                   // open at the top like a real rib cage
                    val lat = w * cos(phi) * side
                    val up = h * sin(phi)
                    val p = BlockPos((start.x + ax * rib + px * lat).roundToInt(), (base + up).roundToInt(), (start.z + az * rib + pz * lat).roundToInt())
                    val axis = if (phi > Math.PI * 0.22) Direction.Axis.Y else sideAxis
                    // keep the rib face-connected: diagonal steps get a connecting block
                    val l = last
                    if (l != null && p != l) {
                        val diffs = (if (p.x != l.x) 1 else 0) + (if (p.y != l.y) 1 else 0) + (if (p.z != l.z) 1 else 0)
                        if (diffs >= 2 && p.y != l.y) cells.putIfAbsent(BlockPos(l.x, p.y, l.z), Direction.Axis.Y)
                        if (diffs >= 2 && p.y == l.y) cells.putIfAbsent(BlockPos(p.x, l.y, l.z), sideAxis)
                        if (diffs == 3) cells.putIfAbsent(BlockPos(p.x, p.y, l.z), axis)
                    }
                    cells.putIfAbsent(p, axis)
                    last = p
                    phi += 0.06
                }
                // foot: lowest cell must stand on solid ground (or be sunk into it)
                val foot = cells.keys.first()
                val below = world.getBlockState(foot.down())
                val footOk = !world.getBlockState(foot).isAir || (!below.isAir && below.fluidState.isEmpty)
                val free = cells.keys.all { val s = world.getBlockState(it); s.isAir || isHollowsStone(s) }
                if (!footOk || !free) continue
                for ((p, axis) in cells) {
                    if (abs((p.x shr 4) - (cx shr 4)) > 1 || abs((p.z shr 4) - (cz shr 4)) > 1) continue
                    world.setBlockState(p, bone.with(Properties.AXIS, axis), HollowsGen.FLAGS)
                    placed = true
                }
            }
            rib += 2
        }
        return placed
    }

    /** Height of the first air block above solid, dry ground, scanning down from [y] (max [depth] blocks). */
    private fun floorY(world: StructureWorldAccess, x: Int, y: Int, z: Int, depth: Int): Int? {
        var yy = y
        for (i in 0..depth) {
            val here = world.getBlockState(BlockPos(x, yy, z))
            val below = world.getBlockState(BlockPos(x, yy - 1, z))
            if (here.isAir && !below.isAir && below.fluidState.isEmpty) return yy
            yy--
        }
        return null
    }
}

/**
 * Entrance shafts ("Adern") from a cave up through the ceiling and the lava sea to the open Nether.
 * Placed at the centre of the chunk, so the whole shaft stays in this chunk.
 */
class VeinShaftFeature : Feature<DefaultFeatureConfig>(DefaultFeatureConfig.CODEC) {
    override fun generate(context: FeatureContext<DefaultFeatureConfig>): Boolean {
        val world = context.world
        val sampler = world.toServerWorld().chunkManager.noiseConfig.multiNoiseSampler
        // a station shaft on every old road (HollowsRoads): an entrance with a road that leads to the pit
        val centre = HollowsRegion.center()
        val station = centre?.let { c -> HollowsRoads.stations(c).firstOrNull { (sx, sz, _) -> sx shr 4 == context.origin.x shr 4 && sz shr 4 == context.origin.z shr 4 } }
        if (station != null) {
            val (sx, sz, floor) = station
            val qx = sx shr 2; val qz = sz shr 2
            for (i in -3..3) for (j in -3..3) if (!HollowsRegion.isCell(sampler, qx + i, qz + j)) return false
            val top = HollowsGen.findOpening(world, sx, sz)
            if (top < 0) return false
            HollowsGen.buildShaft(world, sx, sz, floor, top, null)
            return true
        }
        // otherwise a random shaft in one chunk out of 30
        if (context.random.nextInt(30) != 0) return false
        val cx = ((context.origin.x shr 4) shl 4) + 8
        val cz = ((context.origin.z shr 4) shl 4) + 8
        // well inside the region only (the tube must sit above carved caves)
        val qx = cx shr 2; val qz = cz shr 2
        for (i in -3..3) for (j in -3..3) if (!HollowsRegion.isCell(sampler, qx + i, qz + j)) return false
        var bottom = -1
        for (y in HollowsGen.CAVE_MAX downTo HollowsGen.LAKE_Y + 1) {
            if (HollowsGen.isAir(world, cx, y, cz) && !HollowsGen.isAir(world, cx, y - 1, cz) &&
                world.getFluidState(BlockPos(cx, y - 1, cz)).isEmpty) { bottom = y; break }
        }
        if (bottom < 0) return false
        if (HollowsGen.nearArena(world, BlockPos(cx, bottom, cz), 10)) return false
        val top = HollowsGen.findOpening(world, cx, cz)
        if (top < 0) return false
        HollowsGen.buildShaft(world, cx, cz, bottom, top, null)
        return true
    }
}
