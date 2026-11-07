package dev.henny.hha_blood.hollows

import net.minecraft.block.BlockState
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.world.StructureWorldAccess
import net.minecraft.world.gen.feature.DefaultFeatureConfig
import net.minecraft.world.gen.feature.Feature
import net.minecraft.world.gen.feature.util.FeatureContext

/*
 * Living decorations of the Vein Hollows (round "Biom + Arena cooler"). All of them stay in their own chunk
 * column (or touch only direct neighbours) and keep away from the Slaughter Pit.
 */

private fun stoneLike(s: BlockState) =
    s.isOf(HollowsBlocks.BLOOD_STONE) || s.isOf(HollowsBlocks.VEIN_STONE) || s.isOf(HollowsBlocks.CLOTTED_BLOOD)

private fun inChunk(origin: BlockPos, p: BlockPos) = (p.x shr 4) == (origin.x shr 4) && (p.z shr 4) == (origin.z shr 4)

/** A patch of 2-5 watching eyes in a cave wall or ceiling, with veins spreading around them. */
class EyeClusterFeature : Feature<DefaultFeatureConfig>(DefaultFeatureConfig.CODEC) {
    override fun generate(context: FeatureContext<DefaultFeatureConfig>): Boolean {
        val world = context.world
        val random = context.random
        val origin = context.origin
        if (HollowsGen.nearArena(world, origin, 10)) return false
        val want = 2 + random.nextInt(4)
        var first: BlockPos? = null
        var placed = 0
        for (t in 0 until 90) {
            if (placed >= want) break
            val c = first ?: origin
            val spread = if (first == null) 6 else 2
            val p = c.add(random.nextInt(spread * 2 + 1) - spread, random.nextInt(5) - 2, random.nextInt(spread * 2 + 1) - spread)
            if (!inChunk(origin, p) || !stoneLike(world.getBlockState(p))) continue
            // the eye looks out of the wall into a cave (horizontal) or down from the ceiling
            val dirs = listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN).shuffled(kotlin.random.Random(random.nextLong()))
            val f = dirs.firstOrNull { d ->
                val front = p.offset(d)
                world.getBlockState(front).isAir && world.getBlockState(front.offset(d)).isAir && world.getFluidState(front).isEmpty
            } ?: continue
            world.setBlockState(p, PitBlocks.WATCHING_EYE.defaultState.with(WatchingEyeBlock.FACING, f), HollowsGen.FLAGS)
            placed++
            if (first == null) first = p
            // veins around the eye
            for (i in 0 until 6) {
                val q = p.add(random.nextInt(3) - 1, random.nextInt(3) - 1, random.nextInt(3) - 1)
                if (q != p && inChunk(origin, q) && world.getBlockState(q).isOf(HollowsBlocks.BLOOD_STONE) && exposed(world, q)) {
                    world.setBlockState(q, HollowsBlocks.VEIN_STONE.defaultState, HollowsGen.FLAGS)
                }
            }
        }
        return placed > 0
    }

    private fun exposed(world: StructureWorldAccess, p: BlockPos) = Direction.entries.any { world.getBlockState(p.offset(it)).isAir }
}

/**
 * Blood pouring from a glowing wound in the cave ceiling down into a lake or onto the floor (where it collects
 * in a small pool - only where the pool cannot run out).
 */
class CeilingBloodFallFeature : Feature<DefaultFeatureConfig>(DefaultFeatureConfig.CODEC) {
    override fun generate(context: FeatureContext<DefaultFeatureConfig>): Boolean {
        val world = context.world
        val random = context.random
        val x = ((context.origin.x shr 4) shl 4) + 3 + random.nextInt(10)
        val z = ((context.origin.z shr 4) shl 4) + 3 + random.nextInt(10)
        // find a ceiling: solid block with air below, scanning down from the top of the caves
        var ceiling = -1
        for (y in HollowsGen.CAVE_MAX + 2 downTo HollowsGen.CAVE_MIN + 4) {
            if (stoneLike(world.getBlockState(BlockPos(x, y, z))) && world.getBlockState(BlockPos(x, y - 1, z)).isAir) { ceiling = y; break }
        }
        if (ceiling < 0) return false
        var bottom = ceiling - 1
        while (bottom > 1 && world.getBlockState(BlockPos(x, bottom - 1, z)).isAir) bottom--
        val len = ceiling - bottom
        if (len < 5) return false
        if (HollowsGen.nearArena(world, BlockPos(x, bottom, z), 8)) return false
        val below = BlockPos(x, bottom - 1, z)
        val belowState = world.getBlockState(below)
        val lake = !world.getFluidState(below).isEmpty
        if (!lake && !stoneLike(belowState)) return false
        for (y in bottom until ceiling) world.setBlockState(BlockPos(x, y, z), PitBlocks.BLOOD_FALL.defaultState, HollowsGen.FLAGS)
        // the wound in the ceiling
        world.setBlockState(BlockPos(x, ceiling, z), PitBlocks.BLOOD_GLOW.defaultState, HollowsGen.FLAGS)
        for (d in Direction.Type.HORIZONTAL) {
            val q = BlockPos(x, ceiling, z).offset(d)
            if (stoneLike(world.getBlockState(q))) world.setBlockState(q, HollowsBlocks.VEIN_STONE.defaultState, HollowsGen.FLAGS)
        }
        // a pool where it lands on dry ground
        if (!lake && Direction.Type.HORIZONTAL.all { val q = below.offset(it); world.getBlockState(q).isSolidBlock(world, q) } &&
            world.getBlockState(below.down()).isSolidBlock(world, below.down())) {
            world.setBlockState(below, HollowsFluids.SOURCE, HollowsGen.FLAGS)
        }
        return true
    }
}

/** 1-3 blood vents in a cave floor ("Blutschlot": step on it and it hurls you up). */
class BloodVentFeature : Feature<DefaultFeatureConfig>(DefaultFeatureConfig.CODEC) {
    override fun generate(context: FeatureContext<DefaultFeatureConfig>): Boolean {
        val world = context.world
        val random = context.random
        val origin = context.origin
        if (HollowsGen.nearArena(world, origin, 10)) return false
        var placed = 0
        val n = 1 + random.nextInt(3)
        for (t in 0 until 30) {
            if (placed >= n) break
            val x = ((origin.x shr 4) shl 4) + 2 + random.nextInt(12)
            val z = ((origin.z shr 4) shl 4) + 2 + random.nextInt(12)
            for (y in HollowsGen.CAVE_MAX downTo HollowsGen.LAKE_Y + 1) {
                val p = BlockPos(x, y, z)
                if (!stoneLike(world.getBlockState(p)) || !world.getBlockState(p.up()).isAir) continue
                // flush with the floor, at least 5 blocks of air above to fly
                val flat = Direction.Type.HORIZONTAL.all { stoneLike(world.getBlockState(p.offset(it))) }
                val room = (1..5).all { world.getBlockState(p.up(it)).isAir }
                if (flat && room) {
                    world.setBlockState(p, PitBlocks.BLOOD_VENT.defaultState, HollowsGen.FLAGS)
                    for (d in Direction.Type.HORIZONTAL) if (random.nextBoolean()) world.setBlockState(p.offset(d), HollowsBlocks.VEIN_STONE.defaultState, HollowsGen.FLAGS)
                    placed++
                }
                break
            }
        }
        return placed > 0
    }
}

/**
 * Glowing wounds in the cave ceiling with weeping vines hanging from them (light for the caves, round
 * "Biom weird": the caves were too dark). 1-3 per chunk, only where there are at least 4 blocks of air below.
 */
class HangingGlowFeature : Feature<DefaultFeatureConfig>(DefaultFeatureConfig.CODEC) {
    override fun generate(context: FeatureContext<DefaultFeatureConfig>): Boolean {
        val world = context.world
        val random = context.random
        val origin = context.origin
        if (HollowsGen.nearArena(world, origin, 6)) return false
        val sx = (origin.x shr 4) shl 4
        val sz = (origin.z shr 4) shl 4
        val want = 1 + random.nextInt(3)
        var placed = 0
        for (t in 0 until 24) {
            if (placed >= want) break
            val x = sx + random.nextInt(16)
            val z = sz + random.nextInt(16)
            var ceil = -1
            for (y in HollowsGen.CAVE_MAX + 2 downTo HollowsGen.CAVE_MIN + 4) {
                if (stoneLike(world.getBlockState(BlockPos(x, y, z))) && world.getBlockState(BlockPos(x, y - 1, z)).isAir) { ceil = y; break }
            }
            if (ceil < 0) continue
            var free = 0
            while (free < 12 && world.getBlockState(BlockPos(x, ceil - 1 - free, z)).isAir) free++
            if (free < 4) continue
            val top = BlockPos(x, ceil, z)
            world.setBlockState(top, PitBlocks.BLOOD_GLOW.defaultState, HollowsGen.FLAGS)
            for (d in Direction.Type.HORIZONTAL) {
                val q = top.offset(d)
                if (inChunk(origin, q) && stoneLike(world.getBlockState(q)) && random.nextBoolean()) world.setBlockState(q, HollowsBlocks.VEIN_STONE.defaultState, HollowsGen.FLAGS)
            }
            // vines from the wound and next to it
            for ((ox, oz) in listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val vx = x + ox; val vz = z + oz
                val anchor = BlockPos(vx, ceil, vz)
                if (!inChunk(origin, anchor) || (ox != 0 || oz != 0) && random.nextInt(3) != 0) continue
                if (world.getBlockState(anchor).isAir || !world.getBlockState(anchor.down()).isAir) continue
                val len = minOf(free - 3, 1 + random.nextInt(if (ox == 0 && oz == 0) 6 else 3))
                for (i in 1..len) {
                    val p = BlockPos(vx, ceil - i, vz)
                    if (!world.getBlockState(p).isAir) break
                    val state = if (i == len) net.minecraft.block.Blocks.WEEPING_VINES.defaultState.with(net.minecraft.state.property.Properties.AGE_25, 25)
                        else net.minecraft.block.Blocks.WEEPING_VINES_PLANT.defaultState
                    world.setBlockState(p, state, HollowsGen.FLAGS)
                }
            }
            placed++
        }
        return placed > 0
    }
}
