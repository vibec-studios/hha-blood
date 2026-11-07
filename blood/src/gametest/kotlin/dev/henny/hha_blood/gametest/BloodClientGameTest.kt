package dev.henny.hha_blood.gametest

import dev.henny.hha_blood.boss.PitFight
import dev.henny.hha_blood.hollows.HeartVaultPiece
import dev.henny.hha_blood.hollows.HollowsRegion
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World

/**
 * Starts the real game, flies through the Slaughter Pit and plays the whole boss fight with screenshots
 * (blood/build/run/clientGameTest/screenshots). Mode via -Dhha_blood.test=arena|fight|all (default all).
 */
class BloodClientGameTest : FabricClientGameTest {

    private lateinit var c: BlockPos
    private lateinit var w: TestSingleplayerContext

    private fun cmd(s: String) = w.server.runCommand(s)

    private fun tp(dx: Double, dy: Double, dz: Double, yaw: Float, pitch: Float) =
        cmd("execute in minecraft:the_nether run tp @p ${c.x + dx} ${HeartVaultPiece.FLOOR + dy} ${c.z + dz} $yaw $pitch")

    private fun stage(): Int = w.server.computeOnServer<Int, RuntimeException> { PitFight.stage }
    private fun active(): Boolean = w.server.computeOnServer<Boolean, RuntimeException> { PitFight.active }
    private fun scene(): Boolean = w.server.computeOnServer<Boolean, RuntimeException> { PitFight.sceneRunning() }
    private fun info(): String = w.server.computeOnServer<String, RuntimeException> { PitFight.debugInfo(it.getWorld(World.NETHER)!!) }

    override fun runTest(context: ClientGameTestContext) {
        // mode: all | arena | fight | cerberus (model showcase), combinable ("cerberus,fight"); env HHA_BLOOD_TEST works too
        val mode = System.getProperty("hha_blood.test") ?: System.getenv("HHA_BLOOD_TEST") ?: "all"
        println("[gametest] mode $mode")
        context.worldBuilder()
            .adjustSettings { it.setGameMode(net.minecraft.client.gui.screen.world.WorldCreator.Mode.CREATIVE) }
            .create().use { world ->
                w = world
                world.clientWorld.waitForChunksRender()
                if (mode.contains("cerberus")) cerberusShowcase(context)
                if (mode == "cerberus") return@use
                c = world.server.computeOnServer<BlockPos, RuntimeException> { HollowsRegion.center() ?: BlockPos.ORIGIN }
                check(c != BlockPos.ORIGIN) { "no Slaughter Pit centre" }
                println("[gametest] Slaughter Pit at $c")
                cmd("gamemode spectator @p")
                tp(0.5, 20.0, 36.5, 180f, 22f)
                // the arena chunks generate now - wait for them and for the idle bosses
                context.waitTicks(100)
                world.clientWorld.waitForChunksRender()
                var n = 0
                while (n++ < 60 && world.server.computeOnServer<Boolean, RuntimeException> { PitFight.minotaur(it.getWorld(World.NETHER)!!) == null }) context.waitTicks(20)
                world.clientWorld.waitForChunksRender()
                context.waitTicks(40)
                if (mode == "all" || mode.contains("arena")) arena(context)
                if (mode == "all" || mode.contains("fight")) fight(context)
            }
    }

    private fun shot(context: ClientGameTestContext, name: String) {
        // command feedback in the chat would cover half the picture
        context.runOnClient<RuntimeException> { it.inGameHud.chatHud.clear(false) }
        context.takeScreenshot(name)
    }

    /** Skips the running cutscene the way a player does (jump key) and checks that we are still connected. */
    private fun skipByKey(context: ClientGameTestContext, label: String) {
        // a short tap must not skip
        context.getInput().holdKeyFor({ it.jumpKey }, 10)
        context.waitTicks(10)
        check(scene()) { "a short jump tap skipped $label" }
        // holding for 3 seconds does
        context.getInput().holdKey { it.jumpKey }
        context.waitTicks(35)
        shot(context, "skip_hold_${label.replace(' ', '_')}")
        context.waitTicks(31)
        context.getInput().releaseKey { it.jumpKey }
        var i = 0
        while (scene() && i++ < 20) context.waitTicks(5)
        val connected = context.computeOnClient<Boolean, RuntimeException> { it.networkHandler != null && it.world != null }
        println("[gametest] key skip $label: connected=$connected sceneRunning=${scene()}")
        check(connected) { "client disconnected after skipping $label" }
        check(!scene()) { "cutscene $label did not end after the skip key" }
    }

    /**
     * Model showcase of the Blood Cerberus (mode "cerberus"): a row of hounds on a platform high above the overworld
     * spawn - normal, unleashed, broken heads, asleep - then one hound plays its animations (`/hhablood boss pose`).
     */
    private fun cerberusShowcase(context: ClientGameTestContext) {
        val ow = "execute in minecraft:overworld run "
        val y = 200
        cmd("gamemode spectator @p")
        cmd("${ow}tp @p 20.5 ${y + 6} 22.5 180 20")
        context.waitTicks(40)
        w.clientWorld.waitForChunksRender()
        cmd("time set noon")
        cmd("weather clear")
        cmd("${ow}fill -12 ${y - 1} -14 56 ${y - 1} 14 minecraft:polished_blackstone")
        cmd("${ow}fill -12 ${y - 2} -14 56 ${y - 2} 14 minecraft:blackstone")
        // A normal, B unleashed, C bleed+howl broken, D all heads broken, E asleep
        val xs = listOf(0, 11, 22, 33, 44)
        for (x in xs) cmd("${ow}summon hha_blood:blood_cerberus ${x + 0.5} $y 0.5 {NoAI:1b,Rotation:[0f,0f]}")
        context.waitTicks(10)
        fun pose(x: Int, a: String, unleashed: Boolean = false, broken: Int = 0) =
            cmd("execute in minecraft:overworld positioned ${x + 0.5} $y 0.5 run hhablood boss pose $a $unleashed $broken")
        pose(0, "idle"); pose(11, "idle", true); pose(22, "idle", false, 5); pose(33, "idle", false, 7); pose(44, "sleep")
        context.runOnClient<RuntimeException> { it.options.hudHidden = true }
        fun look(px: Double, py: Double, pz: Double, tx: Double, ty: Double, tz: Double, name: String, wait: Int = 25) {
            val vx = tx - px; val vy = ty - py; val vz = tz - pz
            val yaw = Math.toDegrees(Math.atan2(-vx, vz)).toFloat()
            val pitch = Math.toDegrees(-Math.atan2(vy, Math.sqrt(vx * vx + vz * vz))).toFloat()
            cmd("${ow}tp @p $px $py $pz $yaw $pitch")
            context.waitTicks(wait)
            shot(context, name)
        }
        look(22.5, y + 7.0, 20.0, 22.5, y + 1.0, 0.5, "cer_lineup", 40)
        val labels = listOf("normal", "unleashed", "broken_bleed_howl", "broken_all", "sleep")
        for ((k, x) in xs.withIndex()) {
            look(x + 4.5, y + 3.0, 6.5, x + 0.5, y + 1.6, 0.5, "cer_${labels[k]}_34")
        }
        look(-6.5, y + 2.0, 7.5, 0.5, y + 1.8, 0.0, "cer_normal_left34")
        look(8.5, y + 1.6, 0.5, 0.5, y + 1.6, 0.5, "cer_normal_side")
        look(0.5, y + 2.6, 7.0, 0.5, y + 2.2, 0.5, "cer_normal_front")
        look(-5.5, y + 4.0, -6.5, 0.5, y + 1.6, 0.5, "cer_normal_back")
        look(11.5, y + 2.6, 7.0, 11.5, y + 2.2, 0.5, "cer_unleashed_front")
        look(22.5, y + 2.6, 7.0, 22.5, y + 1.8, 0.5, "cer_broken_front")
        // the animations, each caught at its key moment (ticks after the start)
        val anims = listOf("growl" to 12, "bite" to 11, "snap3" to 17, "breath" to 30, "triple_breath" to 34, "howl" to 28, "pounce" to 18,
            "orbs" to 9, "nova" to 15, "fire_ring" to 36, "charge" to 26, "collapse" to 30, "death" to 50, "rise" to 18, "wake" to 18)
        cmd("${ow}tp @p 5.0 ${y + 2.8} 7.5 145 12")
        context.waitTicks(10)
        for ((a, at) in anims) {
            pose(0, a, a == "nova" || a == "fire_ring")
            context.waitTicks(at)
            shot(context, "cer_anim_$a")
        }
        // lying poses from the floor: they must lie on the platform, not in it
        pose(0, "collapse")
        look(0.5, y + 0.6, 8.0, 0.5, y + 0.6, 0.5, "cer_collapse_front", 30)
        look(8.5, y + 0.6, 0.5, 0.5, y + 0.6, 0.5, "cer_collapse_side", 5)
        pose(44, "sleep")
        look(44.5, y + 0.6, 8.0, 44.5, y + 0.6, 0.5, "cer_sleep_front", 10)
        look(52.5, y + 0.6, 0.5, 44.5, y + 0.6, 0.5, "cer_sleep_side", 5)
        // a head breaks: it collapses and goes dark; the unleashed hound re-ignites it
        pose(0, "idle")
        look(4.5, y + 3.0, 6.5, 0.5, y + 1.6, 0.5, "cer_break_before", 20)
        pose(0, "idle", false, 2)
        context.waitTicks(4)
        shot(context, "cer_break_falling")
        context.waitTicks(30)
        shot(context, "cer_break_hanging")
        pose(0, "bite", false, 2)
        context.waitTicks(11)
        shot(context, "cer_break_bite")
        // night: only torchlight, closer to the dark pit
        cmd("time set midnight")
        for (x in xs) cmd("${ow}setblock ${x + 3} $y 4 minecraft:soul_lantern")
        pose(0, "idle"); pose(11, "idle", true); pose(22, "idle", false, 5)
        look(22.5, y + 7.0, 20.0, 22.5, y + 1.0, 0.5, "cer_night_lineup", 40)
        look(4.5, y + 3.0, 6.5, 0.5, y + 1.6, 0.5, "cer_night_normal_34")
        look(15.5, y + 3.0, 6.5, 11.5, y + 1.6, 0.5, "cer_night_unleashed_34")
        look(26.5, y + 3.0, 6.5, 22.5, y + 1.6, 0.5, "cer_night_broken_34")
        cmd("time set noon")
        context.runOnClient<RuntimeException> { it.options.hudHidden = false }
        println("[gametest] cerberus showcase done")
    }

    private fun arena(context: ClientGameTestContext) {
        /** Teleports to (dx, dy, dz) relative to the pit centre and looks at (tx, ty, tz). */
        fun look(context: ClientGameTestContext, dx: Double, dy: Double, dz: Double, tx: Double, ty: Double, tz: Double, name: String) {
            val vx = tx - dx; val vy = ty - dy; val vz = tz - dz
            val yaw = Math.toDegrees(Math.atan2(-vx, vz)).toFloat()
            val pitch = Math.toDegrees(-Math.atan2(vy, Math.sqrt(vx * vx + vz * vz))).toFloat()
            tp(dx, dy, dz, yaw, pitch)
            context.waitTicks(30)
            w.clientWorld.waitForChunksRender()
            shot(context, name)
        }

        /** A cave floor in the Vein Hollows 90-220 blocks from the pit (air above solid ground, open space ahead). */
        fun findCave(world: net.minecraft.server.world.ServerWorld): IntArray? {
            val pos = BlockPos.Mutable()
            for (dist in 90..220 step 10) for (k in 0 until 16) {
                val ang = k * Math.PI / 8
                val x = c.x + (Math.cos(ang) * dist).toInt(); val z = c.z + (Math.sin(ang) * dist).toInt()
                world.getChunk(x shr 4, z shr 4)
                if (!world.getBiome(BlockPos(x, 14, z)).matchesKey(HollowsRegion.BIOME)) continue
                for (y in 10..17) {
                    pos.set(x, y - 1, z)
                    if (!world.getBlockState(pos).isSolidBlock(world, pos)) continue
                    var ok = true
                    for (h in 0..5) { pos.set(x, y + h, z); if (!world.getBlockState(pos).isAir) { ok = false; break } }
                    if (!ok) continue
                    var open = 0
                    for (i in -6..6) { pos.set(x + i, y + 2, z + 8); if (world.getBlockState(pos).isAir) open++ }
                    if (open >= 6) return intArrayOf(x, y, z)
                }
            }
            return null
        }

        context.runOnClient<RuntimeException> { it.options.hudHidden = true }
        tp(0.5, 20.0, 36.5, 180f, 22f); context.waitTicks(30); w.clientWorld.waitForChunksRender(); shot(context, "arena_from_south")
        tp(0.5, 22.0, -30.5, 0f, 30f); context.waitTicks(30); w.clientWorld.waitForChunksRender(); shot(context, "arena_from_north")
        tp(30.5, 18.0, 0.5, 90f, 25f); context.waitTicks(30); w.clientWorld.waitForChunksRender(); shot(context, "arena_from_east")
        tp(0.5, 34.0, 0.5, 0f, 89f); context.waitTicks(30); w.clientWorld.waitForChunksRender(); shot(context, "arena_floor_top")
        tp(0.5, 13.5, -30.0, 180f, 8f); context.waitTicks(30); shot(context, "throne_front")
        tp(5.5, 12.0, -27.0, 150f, 18f); context.waitTicks(20); shot(context, "throne_side")
        // the kennel lies under the balcony (north): gate from the arena, then the room from just behind the bars
        tp(0.5, 3.0, -18.0, 180f, 8f); context.waitTicks(30); shot(context, "kennel_gate")
        tp(-3.5, 4.0, -35.0, 170f, 12f); context.waitTicks(30); shot(context, "kennel_inside")
        tp(20.5, 2.0, 25.5, 135f, 0f); context.waitTicks(30); shot(context, "pillars_ground")
        tp(0.5, 0.0, 62.5, 180f, 0f); context.waitTicks(30); shot(context, "entrance_tunnel")
        // round "Biom + Arena cooler": the heart, the galleries with eyes and spectators, the skull gate, blood channels
        look(context, 9.0, 23.0, 9.0, 0.5, 31.0, 0.5, "heart_close")
        look(context, 0.5, 1.6, 22.0, 0.5, 30.0, 0.5, "heart_from_floor")
        look(context, 17.0, 29.0, 3.0, 0.5, 29.0, 0.5, "heart_side")
        val a = Math.toRadians(22.5)
        look(context, Math.cos(a) * 35.0, 7.5, Math.sin(a) * 35.0, Math.cos(a) * 42.0, 9.0, Math.sin(a) * 42.0, "gallery_eyes")
        look(context, Math.cos(a) * 38.5, 9.5, Math.sin(a) * 38.5, Math.cos(a) * 42.0, 9.0, Math.sin(a) * 42.0, "gallery_niche_close")
        val b = Math.toRadians(52.5)
        look(context, Math.cos(b) * 30.0, 8.0, Math.sin(b) * 30.0, Math.cos(Math.toRadians(45.0)) * 42.0, 8.0, Math.sin(Math.toRadians(45.0)) * 42.0, "blood_channel")
        look(context, 0.5, 9.0, 18.0, 0.5, 12.0, 42.0, "titan_skull")
        look(context, -20.0, 30.0, -20.0, 20.0, 4.0, 20.0, "arena_high_diagonal")
        look(context, 0.5, 1.6, 40.0, 0.5, 6.0, 0.5, "arena_from_skull_mouth")
        // the biome: a cave next to the arena, with a test row of the new blocks
        val cave = w.server.computeOnServer<IntArray, RuntimeException> { srv -> findCave(srv.getWorld(World.NETHER)!!) ?: IntArray(0) }.takeIf { it.isNotEmpty() }
        println("[gametest] cave spot: ${cave?.joinToString()}")
        if (cave != null) {
            val (x, y, z) = Triple(cave[0], cave[1], cave[2])
            cmd("execute in minecraft:the_nether run tp @p ${x + 0.5} ${y + 0.6} ${z + 0.5} 0 10")
            context.waitTicks(60); w.clientWorld.waitForChunksRender(); context.waitTicks(20)
            for (yaw in listOf(0, 120, 240)) {
                cmd("execute in minecraft:the_nether run tp @p ${x + 0.5} ${y + 0.6} ${z + 0.5} $yaw 8")
                context.waitTicks(25); shot(context, "cave_$yaw")
            }
            // showcase: eye, vent, glow, rising blood column
            cmd("execute in minecraft:the_nether run fill ${x - 3} ${y - 1} ${z + 4} ${x + 3} ${y + 4} ${z + 7} hha_blood:blood_stone")
            cmd("execute in minecraft:the_nether run fill ${x - 3} ${y} ${z + 4} ${x + 3} ${y + 4} ${z + 4} minecraft:air")
            cmd("execute in minecraft:the_nether run setblock ${x - 2} ${y + 1} ${z + 5} hha_blood:watching_eye[facing=north]")
            cmd("execute in minecraft:the_nether run setblock ${x - 1} ${y + 2} ${z + 5} hha_blood:watching_eye[facing=north]")
            cmd("execute in minecraft:the_nether run setblock ${x} ${y - 1} ${z + 4} hha_blood:blood_vent")
            cmd("execute in minecraft:the_nether run setblock ${x + 1} ${y + 3} ${z + 5} hha_blood:blood_glow")
            cmd("execute in minecraft:the_nether run fill ${x + 2} ${y} ${z + 4} ${x + 2} ${y + 4} ${z + 4} hha_blood:blood_fall[rising=true]")
            cmd("execute in minecraft:the_nether run fill ${x - 3} ${y} ${z + 4} ${x - 3} ${y + 4} ${z + 4} hha_blood:blood_fall")
            cmd("execute in minecraft:the_nether run tp @p ${x + 0.5} ${y + 1.2} ${z + 0.5} 0 15")
            context.waitTicks(40); shot(context, "cave_showcase")
            cmd("execute in minecraft:the_nether run tp @p ${x - 1.5} ${y + 1.6} ${z + 2.5} 0 0")
            context.waitTicks(20); shot(context, "eye_close")
        }
        // biome features around the pit (generates a ring of chunks): count them, show a shaft elevator
        val stats = w.server.computeOnServer<String, RuntimeException> { srv ->
            val nw = srv.getWorld(World.NETHER)!!
            val counts = HashMap<String, Int>()
            var shaft: BlockPos? = null
            val p = BlockPos.Mutable()
            for (cx in -12..12) for (cz in -12..12) {
                if (Math.abs(cx) < 5 && Math.abs(cz) < 5) continue
                val chunk = nw.getChunk((c.x shr 4) + cx, (c.z shr 4) + cz)
                for (x in 0..15) for (z in 0..15) for (y in 2..60) {
                    p.set(chunk.pos.startX + x, y, chunk.pos.startZ + z)
                    val st = chunk.getBlockState(p)
                    if (st.isAir || st.isOf(net.minecraft.block.Blocks.NETHERRACK) || st.isOf(net.minecraft.block.Blocks.LAVA)) continue
                    val id = net.minecraft.registry.Registries.BLOCK.getId(st.block)
                    if (id.namespace != "hha_blood") continue
                    val name = if (st.isOf(dev.henny.hha_blood.hollows.PitBlocks.BLOOD_FALL) && st.get(dev.henny.hha_blood.hollows.BloodFallBlock.RISING)) "rising_blood" else id.path
                    counts[name] = (counts[name] ?: 0) + 1
                    if (name == "rising_blood" && shaft == null && listOf(p.east(), p.west(), p.north(), p.south()).all { nw.getBlockState(it).isOf(st.block) }) shaft = p.toImmutable()
                }
            }
            "${counts.filterKeys { it in setOf("watching_eye", "blood_vent", "blood_fall", "rising_blood", "blood_glow") }} shaft=$shaft"
        }
        println("[gametest] biome features: $stats")
        Regex("shaft=BlockPos\\{x=(-?\\d+), y=(-?\\d+), z=(-?\\d+)\\}").find(stats)?.let { m ->
            val (sx, sy, sz) = m.destructured
            cmd("execute in minecraft:the_nether run tp @p ${sx.toInt() + 2.6} ${sy.toInt() + 1.0} ${sz.toInt() + 0.5} 90 -40")
            context.waitTicks(60); w.clientWorld.waitForChunksRender(); context.waitTicks(20)
            shot(context, "shaft_elevator")
        }
        // round "klarer Weg": the landmark on the surface, the stair tower, the vestibule, the procession ring, the old roads
        var waitRebuild = 0
        while (waitRebuild++ < 40 && w.server.computeOnServer<Boolean, RuntimeException> { dev.henny.hha_blood.boss.PitArena.upgrading() }) context.waitTicks(10)
        val p = w.server.computeOnServer<Int, RuntimeException> { dev.henny.hha_blood.hollows.PitApproach.plazaDy() }.toDouble()
        val tz = dev.henny.hha_blood.hollows.PitApproach.TOWER_DZ.toDouble()
        println("[gametest] plaza dy $p (y ${HeartVaultPiece.FLOOR + p})")
        look(context, 38.0, p + 14.0, tz + 34.0, 0.5, p + 10.0, tz, "landmark_far")
        look(context, -66.0, p + 12.0, tz - 30.0, 0.5, p + 10.0, tz, "landmark_from_north_west")
        look(context, 60.0, p + 1.6, tz, 0.5, p + 6.0, tz, "causeway_east")
        look(context, -30.0, p + 6.0, tz - 2.0, 0.5, p + 12.0, tz, "landmark_west")
        look(context, 0.5, p + 26.0, tz + 30.0, 0.5, p, tz, "landmark_above")
        look(context, 12.5, p + 1.6, tz + 6.0, 0.5, p + 8.0, tz - 2.0, "plaza_ribs")
        look(context, 5.5, p + 1.6, tz - 5.5, 0.5, -1.0, tz, "stair_from_top")
        look(context, 5.5, 1.6, tz - 5.0, 0.0, p, tz + 2.0, "stair_from_floor")
        look(context, 0.5, 1.6, tz - 4.0, 0.5, 4.0, 47.0, "vestibule_skull")
        look(context, 0.5, 0.0, 58.5, 0.5, 1.6, 47.0, "ko_spot")
        for ((deg, name) in listOf(200.0 to "ring_west", 330.0 to "ring_north_east", 135.0 to "ring_south_west")) {
            val a = Math.toRadians(deg)
            val ahead = Math.toRadians(deg + if (deg > 90 && deg < 270) -9.0 else 9.0)
            look(context, Math.cos(a) * 62.5, 1.6, Math.sin(a) * 62.5, Math.cos(ahead) * 62.5, 1.2, Math.sin(ahead) * 62.5, name)
        }
        // the old roads in fresh terrain + a station shaft
        val roadPts = w.server.computeOnServer<List<DoubleArray>, RuntimeException> { _ ->
            val out = ArrayList<DoubleArray>()
            for ((k, r) in listOf(0 to 120.0, 2 to 170.0)) {
                val a = dev.henny.hha_blood.hollows.HollowsRoads.angle(c, k, r)
                val a2 = dev.henny.hha_blood.hollows.HollowsRoads.angle(c, k, r - 14)
                out += doubleArrayOf(Math.cos(a) * r, (dev.henny.hha_blood.hollows.HollowsRoads.floorAt(k, r) - HeartVaultPiece.FLOOR).toDouble(), Math.sin(a) * r,
                    Math.cos(a2) * (r - 14), Math.sin(a2) * (r - 14))
            }
            dev.henny.hha_blood.hollows.HollowsRoads.stations(c).firstOrNull()?.let { (sx, sz, f) -> out += doubleArrayOf((sx - c.x).toDouble(), (f - HeartVaultPiece.FLOOR).toDouble(), (sz - c.z).toDouble()) }
            out
        }
        for ((i, pt) in roadPts.withIndex()) {
            if (pt.size == 5) {
                tp(pt[0], pt[1] + 1.6, pt[2], 0f, 0f); context.waitTicks(60); w.clientWorld.waitForChunksRender()
                look(context, pt[0], pt[1] + 1.6, pt[2], pt[3], pt[1] + 1.0, pt[4], "road_$i")
            } else {
                tp(pt[0] + 5.5, pt[1] + 2.0, pt[2] + 0.5, 0f, 0f); context.waitTicks(60); w.clientWorld.waitForChunksRender()
                look(context, pt[0] + 5.5, pt[1] + 2.0, pt[2] + 0.5, pt[0] + 0.5, pt[1] + 3.0, pt[2] + 0.5, "road_station")
            }
        }
        // more caves of the region (fresh terrain), slices for the overview
        val caves = w.server.computeOnServer<List<IntArray>, RuntimeException> { srv ->
            val nw = srv.getWorld(World.NETHER)!!
            val out = ArrayList<IntArray>()
            val pos = BlockPos.Mutable()
            for ((dist, ang) in listOf(140 to 0.7, 190 to 2.4, 250 to 3.9, 160 to 5.3)) {
                val x = c.x + (Math.cos(ang) * dist).toInt(); val z = c.z + (Math.sin(ang) * dist).toInt()
                var best: IntArray? = null
                for (ox in -24..24 step 4) for (oz in -24..24 step 4) {
                    nw.getChunk((x + ox) shr 4, (z + oz) shr 4)
                    for (y in 6..16) {
                        pos.set(x + ox, y - 1, z + oz)
                        if (!nw.getBlockState(pos).isSolidBlock(nw, pos)) continue
                        pos.set(x + ox, y, z + oz)
                        if (!nw.getBlockState(pos).isAir) continue
                        var hgt = 0
                        while (hgt < 20) { pos.set(x + ox, y + hgt, z + oz); if (!nw.getBlockState(pos).isAir) break; hgt++ }
                        if (hgt >= 4 && (best == null || hgt > best[3])) best = intArrayOf(x + ox, y, z + oz, hgt)
                        break
                    }
                }
                best?.let { out += it }
            }
            // horizontal slices around the pit (y 9, 13, 18) for the overview
            val dir = srv.getPath("hha_debug").toFile(); dir.mkdirs()
            for (y in intArrayOf(9, 13, 18)) {
                val rad = 160
                val img = java.awt.image.BufferedImage(rad * 2 + 1, rad * 2 + 1, java.awt.image.BufferedImage.TYPE_INT_RGB)
                for (i in 0..rad * 2) for (j in 0..rad * 2) {
                    pos.set(c.x - rad + i, y, c.z - rad + j)
                    val st = nw.getBlockState(pos)
                    val col = when {
                        st.isAir -> 0x101010
                        st.isOf(dev.henny.hha_blood.hollows.HollowsFluids.BLOOD_BLOCK) -> 0xc8102a
                        st.isOf(dev.henny.hha_blood.hollows.HollowsBlocks.VEIN_STONE) || st.isOf(dev.henny.hha_blood.hollows.PitBlocks.BLOOD_GLOW) -> 0xff3040
                        else -> st.getMapColor(nw, pos).color
                    }
                    img.setRGB(i, j, col)
                }
                javax.imageio.ImageIO.write(img, "png", java.io.File(dir, "pit_slice_y$y.png"))
            }
            println("[gametest] slices in ${dir.absolutePath}")
            out
        }
        for ((i, cv) in caves.withIndex()) {
            println("[gametest] cave2 spot ${cv.joinToString()}")
            cmd("execute in minecraft:the_nether run tp @p ${cv[0] + 0.5} ${cv[1] + 1.6} ${cv[2] + 0.5} 0 0")
            context.waitTicks(50); w.clientWorld.waitForChunksRender(); context.waitTicks(10)
            for (yaw in listOf(45, 225)) {
                cmd("execute in minecraft:the_nether run tp @p ${cv[0] + 0.5} ${cv[1] + 1.6} ${cv[2] + 0.5} $yaw 5")
                context.waitTicks(20); shot(context, "cave2_${i}_$yaw")
            }
        }
        println("[gametest] arena shots done: ${info()}")
        context.runOnClient<RuntimeException> { it.options.hudHidden = false }
    }

    private fun fight(context: ClientGameTestContext) {
        cmd("gamemode creative @p")
        tp(0.5, 0.0, 36.5, 180f, 0f)
        // the intro starts as soon as we are in the dome
        var i = 0
        while (!active() && i++ < 40) context.waitTicks(10)
        println("[gametest] fight active=${active()} ${info()}")
        // intro: screenshots every 30 ticks while it runs
        i = 0
        while (scene() && i < 60) { context.waitTicks(30); shot(context, "intro_${i++}") }
        println("[gametest] after intro: ${info()}")
        for (s in 1..5) {
            // fight the stage for a while
            for (k in 0 until 6) {
                // stage 1: break one head halfway (broken-head look + head bars)
                if (s == 1 && k == 3) { cmd("hhablood boss breakhead"); context.waitTicks(10); shot(context, "stage1_headbroken") }
                context.waitTicks(50); shot(context, "stage${s}_$k")
            }
            println("[gametest] stage $s: ${info()}")
            cmd("hhablood boss skip")
            context.waitTicks(10)
            i = 0
            // the finale (Hermes' cameo) runs to the end, the other scenes are skipped with the key
            val shots = if (s == 5) 70 else 2
            while (scene() && i < shots) { context.waitTicks(if (s == 5) 25 else 30); shot(context, "scene${s}_${i++}") }
            if (scene()) skipByKey(context, "scene $s")
            println("[gametest] after scene $s: ${info()}")
            if (!active()) break
        }
        context.waitTicks(40)
        shot(context, "victory")
        println("[gametest] end: ${info()}")
        val inv = w.server.computeOnServer<String, RuntimeException> { srv ->
            val p = srv.playerManager.playerList.first()
            (0 until p.inventory.size()).map { p.inventory.getStack(it) }.filter { !it.isEmpty }.joinToString { "${it.count}x ${it.item}" }
        }
        println("[gametest] rewards: $inv")

        // second fight: short intro (skipped), then a knock-out -> the fight must reset on its own
        cmd("hhablood boss reset")
        tp(0.5, 0.0, 62.5, 180f, 0f)
        context.waitTicks(60)
        tp(0.5, 0.0, 30.5, 180f, 0f)
        var k = 0
        while (!active() && k++ < 40) context.waitTicks(10)
        context.waitTicks(40)
        shot(context, "again_intro")
        skipByKey(context, "second intro")
        context.waitTicks(60)
        println("[gametest] second fight: ${info()}")
        // real damage against a fully geared player (netherite + Protection IV): the Cerberus attacks for a while
        System.setProperty("hha_blood.debugDamage", "1")
        for (slot in listOf("head" to "helmet", "chest" to "chestplate", "legs" to "leggings", "feet" to "boots")) {
            cmd("item replace entity @p armor.${slot.first} with minecraft:netherite_${slot.second}[minecraft:enchantments={\"minecraft:protection\":4}]")
        }
        cmd("item replace entity @p weapon.offhand with minecraft:totem_of_undying")
        cmd("gamemode survival @p")
        val state = {
            w.server.computeOnServer<String, RuntimeException> { srv ->
                val p = srv.playerManager.playerList.first()
                "alive=${p.isAlive} hp=${"%.1f".format(p.health)} armour=${p.armor} offhand=${p.offHandStack.item} pos=${p.blockPos} dz=${p.blockPos.z - c.z}"
            }
        }
        // the Cerberus attacks until it pops the totem (or 40 s are over)
        var popped = false
        for (i in 0 until 40) {
            context.waitTicks(20)
            if (i % 6 == 0) shot(context, "real_damage_$i")
            val st = state()
            println("[gametest] under attack ${i + 1}s: $st")
            if (!st.contains("offhand=minecraft:totem_of_undying")) { popped = st.contains("alive=true"); break }
        }
        println("[gametest] totem popped by a boss hit and survived: $popped ${info()}")
        shot(context, "totem_popped")
        // without a totem: a real death, the fight goes on without this player and resets when nobody is left
        cmd("effect clear @p")
        cmd("item replace entity @p weapon.offhand with minecraft:air")
        if (state().contains("alive=true")) cmd("damage @p 1000 minecraft:generic")
        context.waitTicks(20)
        println("[gametest] lethal hit without totem: ${state()} ${info()}")
        shot(context, "died")
        context.runOnClient<RuntimeException> { it.player?.requestRespawn(); it.setScreen(null) }
        context.waitTicks(40)
        println("[gametest] respawned: ${state()}")
        System.clearProperty("hha_blood.debugDamage")
        context.waitTicks(230)
        println("[gametest] after abandon: ${info()}")
        cmd("execute in minecraft:the_nether run tp @p ${c.x + 0.5} ${HeartVaultPiece.FLOOR} ${c.z + 62.5}")
        context.waitTicks(20)
        cmd("gamemode spectator @p")
        tp(0.5, 20.0, 36.5, 180f, 22f)
        context.waitTicks(40)
        shot(context, "reset_arena")
        // trophies: placed and worn
        cmd("gamemode creative @p")
        for ((k, h) in listOf("bleed", "flame", "howl").withIndex()) {
            cmd("execute in minecraft:the_nether run setblock ${c.x - 1 + k} ${HeartVaultPiece.FLOOR} ${c.z + 52} hha_blood:cerberus_head_$h[facing=south]")
            cmd("execute in minecraft:the_nether run setblock ${c.x - 1 + k} ${HeartVaultPiece.FLOOR + 4} ${c.z + 53} minecraft:lantern[hanging=true]")
        }
        tp(0.5, 1.0, 55.0, 180f, 25f)
        context.waitTicks(20)
        shot(context, "trophies_placed")
        cmd("item replace entity @p armor.head with hha_blood:cerberus_head_flame")
        context.getInput().pressKey { it.togglePerspectiveKey }
        context.getInput().pressKey { it.togglePerspectiveKey }
        context.waitTicks(10)
        shot(context, "trophy_worn")
        context.getInput().pressKey { it.togglePerspectiveKey }
        deathMessages()
    }

    /** Every Blood ability and boss damage type must resolve and have a translated death message (with and without killer). */
    private fun deathMessages() {
        val ids = listOf("nihil", "nihil_nova", "nihil_spike", "blood_trail", "blood_cyclone", "bloodsaw", "blood_fountain", "bleed", "bleed_out",
            "slaughter", "hellfire", "crush")
        val out = w.server.computeOnServer<String, RuntimeException> { srv ->
            val world = srv.getWorld(World.NETHER)!!
            val p = srv.playerManager.playerList.first()
            val killer = net.minecraft.entity.EntityType.ZOMBIE.create(world, net.minecraft.entity.SpawnReason.COMMAND)!!
            killer.customName = net.minecraft.text.Text.literal("Henny")
            val reg = world.registryManager.getOrThrow(net.minecraft.registry.RegistryKeys.DAMAGE_TYPE)
            ids.joinToString("\n") { id ->
                val key = net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.DAMAGE_TYPE, dev.henny.hha_blood.HhaBlood.id(id))
                val entry = reg.getOptional(key).orElse(null)
                if (entry == null) "MISSING TYPE $id" else {
                    val alone = net.minecraft.entity.damage.DamageSource(entry).getDeathMessage(p)
                    val by = net.minecraft.entity.damage.DamageSource(entry, killer).getDeathMessage(p)
                    val lang = net.minecraft.util.Language.getInstance()
                    // with a killer the base key gets the killer as %2$s (".player" is only for "while fighting"), so it must name him
                    val ok = lang.hasTranslation("death.attack.hha_blood.$id") && lang.hasTranslation("death.attack.hha_blood.$id.player") &&
                        // all types except bleed_out always have a killer; bleed_out never has one
                        (if (id == "bleed_out") !alone.string.contains("%") else !by.string.contains("%") && by.string.contains("Henny"))
                    "${if (ok) "ok" else "UNTRANSLATED"} $id: '${alone.string}' / '${by.string}'"
                }
            }
        }
        println("[gametest] death messages:\n$out")
        check(!out.contains("MISSING") && !out.contains("UNTRANSLATED")) { "death messages incomplete" }
    }
}
