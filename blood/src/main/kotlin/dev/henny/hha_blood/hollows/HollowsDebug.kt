package dev.henny.hha_blood.hollows

import com.mojang.brigadier.arguments.IntegerArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.block.MapColor
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Debug/testing commands (op level 2):
 *  - `/hhablood hollows stats` - how much of the Nether underground is Vein Hollows
 *  - `/hhablood hollows slice <y> [radius]` - PNG of a horizontal cut at height y (map colours)
 *  - `/hhablood hollows cut [radius]` - PNG of a vertical cut (x/y) through the current position
 * PNGs are written to `<server dir>/hha_debug/`.
 */
object HollowsDebug {
    private var autoTestTicks = -1

    fun init() {
        // Dev self-test: if `<run dir>/hha_debug/autotest` exists, locate biome + arena after start, write
        // slices/cuts of the arena and stop the server again (used during development, harmless otherwise).
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register { server ->
            if (autoTestTicks == -1) {
                val f = server.getPath("hha_debug/autotest").toFile()
                autoTestTicks = if (f.exists()) { f.delete(); 0 } else -2
                if (autoTestTicks == 0) HhaBloodLog.info("[autotest] triggered by ${f.absolutePath}")
            }
            if (autoTestTicks >= 0 && ++autoTestTicks == 60) {
                try { autoTest(server) } catch (t: Throwable) { HhaBloodLog.error("hollows autotest failed", t) }
                server.stop(false)
            }
        }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                CommandManager.literal("hhablood").requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                    .then(
                        CommandManager.literal("hollows")
                            .then(CommandManager.literal("stats").executes { stats(it.source); 1 })
                            .then(
                                CommandManager.literal("slice").then(
                                    CommandManager.argument("y", IntegerArgumentType.integer(0, 127))
                                        .executes { slice(it.source, IntegerArgumentType.getInteger(it, "y"), 64); 1 }
                                        .then(
                                            CommandManager.argument("radius", IntegerArgumentType.integer(8, 160))
                                                .executes { slice(it.source, IntegerArgumentType.getInteger(it, "y"), IntegerArgumentType.getInteger(it, "radius")); 1 },
                                        ),
                                ),
                            )
                            .then(
                                CommandManager.literal("cut")
                                    .executes { cut(it.source, 64); 1 }
                                    .then(
                                        CommandManager.argument("radius", IntegerArgumentType.integer(8, 160))
                                            .executes { cut(it.source, IntegerArgumentType.getInteger(it, "radius")); 1 },
                                    ),
                            ),
                    ),
            )
        }
    }

    private fun nether(src: ServerCommandSource): ServerWorld = src.server.getWorld(World.NETHER)!!

    private val HhaBloodLog = dev.henny.hha_blood.HhaBlood.LOGGER

    private fun autoTest(server: net.minecraft.server.MinecraftServer) {
        val world = server.getWorld(World.NETHER)!!
        val sampler = world.chunkManager.noiseConfig.multiNoiseSampler
        val t00 = System.currentTimeMillis()
        val centre = HollowsRegion.center()
        HhaBloodLog.info("[autotest] region centre $centre, distance %.0f (${System.currentTimeMillis() - t00} ms)".format(centre?.let { Math.hypot(it.x.toDouble(), it.z.toDouble()) } ?: -1.0))
        if (centre != null) {
            var cells = 0
            var minD = 1e9; var maxD = 0.0
            for (qx in (centre.x shr 2) - 200..(centre.x shr 2) + 200) for (qz in (centre.z shr 2) - 200..(centre.z shr 2) + 200) {
                if (!HollowsRegion.isCell(sampler, qx, qz)) continue
                cells++
                val d = Math.hypot(qx * 4.0, qz * 4.0)
                minD = minOf(minD, d); maxD = maxOf(maxD, d)
            }
            HhaBloodLog.info("[autotest] region area %.2f km2, distance from 0/0 %.0f..%.0f".format(cells * 16 / 1e6, minD, maxD))
            var far = 0
            for (qx in -1500..1500 step 37) for (qz in -1500..1500 step 37) if (HollowsRegion.isCell(sampler, qx, qz)) far++
            HhaBloodLog.info("[autotest] region cells near spawn (+-6000 blocks): $far (must be 0)")
        }
        val t0 = System.currentTimeMillis()
        val biome = world.locateBiome({ it.matchesKey(HollowsRegion.BIOME) }, centre?.add(-150, 6, 90) ?: BlockPos(0, 16, 0), 800, 16, 64)
        HhaBloodLog.info("[autotest] nearest biome: ${biome?.first} (${System.currentTimeMillis() - t0} ms)")
        val structures = world.registryManager.getOrThrow(net.minecraft.registry.RegistryKeys.STRUCTURE)
        val vault = structures.getOrThrow(net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.STRUCTURE, dev.henny.hha_blood.HhaBlood.id("heart_vault")))
        val t1 = System.currentTimeMillis()
        val found = world.chunkManager.chunkGenerator.locateStructure(world, net.minecraft.registry.entry.RegistryEntryList.of(vault), BlockPos(0, 16, 0), 100, false)
        HhaBloodLog.info("[autotest] nearest heart vault: ${found?.first} (${System.currentTimeMillis() - t1} ms)")
        val dir = server.getPath("hha_debug").toFile()
        dir.mkdirs()
        biome?.first?.let { b ->
            for (y in intArrayOf(8, 14, 20)) writeSlice(world, BlockPos(b.x, y, b.z), 64, File(dir, "biome_y$y.png"))
            writeCut(world, b, 64, File(dir, "biome_cut.png"))
        }
        found?.first?.let { p ->
            val c = BlockPos(p.x + 8, HeartVaultPiece.FLOOR, p.z + 8)
            for (y in intArrayOf(HeartVaultPiece.FLOOR - 1, HeartVaultPiece.FLOOR, HeartVaultPiece.FLOOR + 3, HeartVaultPiece.FLOOR + 27)) writeSlice(world, BlockPos(c.x, y, c.z + 12), 72, File(dir, "vault_y$y.png"))
            writeCut(world, c, 72, File(dir, "vault_cut_x.png"))
            HhaBloodLog.info("[autotest] arena centre $c"); writeCutZ(world, BlockPos(c.x, 0, c.z + 20), 72, File(dir, "vault_cut_z.png"))
        }
        biome?.first?.let { floatingCheck(world, it, 72) }
        found?.first?.let { floatingCheck(world, BlockPos(it.x + 8, 10, it.z + 8), 72) }
        HhaBloodLog.info("[autotest] done, images in ${dir.absolutePath}")
    }

    /** Counts blood crystals without support and isolated bone blocks around [c] (floating-block bug check). */
    private fun floatingCheck(world: ServerWorld, c: BlockPos, radius: Int) {
        var crystals = 0; var floatingCrystals = 0; var bones = 0; var floatingBones = 0; var blood = 0
        val examples = ArrayList<BlockPos>()
        for (x in c.x - radius..c.x + radius) for (z in c.z - radius..c.z + radius) for (y in 2..60) {
            val p = BlockPos(x, y, z)
            val s = world.getBlockState(p)
            if (s.isOf(HollowsFluids.BLOOD_BLOCK)) blood++
            if (s.block is net.minecraft.block.AmethystClusterBlock) {
                crystals++
                val facing = s.get(net.minecraft.block.AmethystClusterBlock.FACING)
                if (world.getBlockState(p.offset(facing.opposite)).isAir) { floatingCrystals++; if (examples.size < 5) examples += p }
            } else if (s.isOf(net.minecraft.block.Blocks.BONE_BLOCK)) {
                bones++
                if (net.minecraft.util.math.Direction.entries.all { world.getBlockState(p.offset(it)).isAir }) { floatingBones++; if (examples.size < 5) examples += p }
            }
        }
        HhaBloodLog.info("[autotest] around $c: crystals $crystals (floating $floatingCrystals), bones $bones (isolated $floatingBones), blood blocks $blood, examples $examples")
    }

    private fun writeSlice(world: ServerWorld, c: BlockPos, radius: Int, f: File) {
        val size = radius * 2 + 1
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until size) for (j in 0 until size) img.setRGB(i, j, color(world, BlockPos(c.x - radius + i, c.y, c.z - radius + j)))
        ImageIO.write(img, "png", f)
    }

    private fun writeCut(world: ServerWorld, c: BlockPos, radius: Int, f: File) {
        val size = radius * 2 + 1
        val img = BufferedImage(size, 128, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until size) for (y in 0 until 128) img.setRGB(i, 127 - y, color(world, BlockPos(c.x - radius + i, y, c.z)))
        ImageIO.write(img, "png", f)
    }

    private fun writeCutZ(world: ServerWorld, c: BlockPos, radius: Int, f: File) {
        val size = radius * 2 + 1
        val img = BufferedImage(size, 128, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until size) for (y in 0 until 128) img.setRGB(i, 127 - y, color(world, BlockPos(c.x, y, c.z - radius + i)))
        ImageIO.write(img, "png", f)
    }

    private fun stats(src: ServerCommandSource) {
        nether(src)
        val c = HollowsRegion.center()
        src.sendFeedback({ Text.literal(if (c == null) "Vein Hollows: not determined yet" else "Vein Hollows / Heart Vault centre: ${c.x} ${c.y} ${c.z} (Nether), radius ~${HollowsRegion.BASE_RADIUS.toInt()}") }, false)
    }

    private fun color(world: ServerWorld, pos: BlockPos): Int {
        val s = world.getBlockState(pos)
        if (s.isAir) return 0x101010
        if (s.isOf(HollowsFluids.BLOOD_BLOCK)) return 0xc8102a
        if (s.isOf(net.minecraft.block.Blocks.LAVA)) return 0xff7a00
        val c = s.getMapColor(world, pos)
        return if (c == MapColor.CLEAR) 0x303030 else c.color
    }

    private fun slice(src: ServerCommandSource, y: Int, radius: Int) {
        val world = nether(src)
        val c = BlockPos.ofFloored(src.position)
        val size = radius * 2 + 1
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until size) for (j in 0 until size) {
            img.setRGB(i, j, color(world, BlockPos(c.x - radius + i, y, c.z - radius + j)))
        }
        write(src, img, "slice_${c.x}_${c.z}_y$y.png")
    }

    private fun cut(src: ServerCommandSource, radius: Int) {
        val world = nether(src)
        val c = BlockPos.ofFloored(src.position)
        val size = radius * 2 + 1
        val img = BufferedImage(size, 128, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until size) for (y in 0 until 128) {
            img.setRGB(i, 127 - y, color(world, BlockPos(c.x - radius + i, y, c.z)))
        }
        write(src, img, "cut_${c.x}_${c.z}.png")
    }

    private fun write(src: ServerCommandSource, img: BufferedImage, name: String) {
        val dir = src.server.getPath("hha_debug").toFile()
        dir.mkdirs()
        val f = File(dir, name)
        ImageIO.write(img, "png", f)
        src.sendFeedback({ Text.literal("wrote ${f.absolutePath}") }, false)
    }
}
