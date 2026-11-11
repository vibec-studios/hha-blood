package dev.henny.hha_blood.boss

import dev.henny.hha_blood.HhaBlood
import dev.henny.hha_blood.hollows.HeartVaultPiece
import dev.henny.hha_blood.hollows.PitApproach
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.FakePlayer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.world.World

/**
 * Testing helpers for the Slaughter Pit:
 *  - `/hhablood boss tp`       into the pit (Nether)
 *  - `/hhablood boss info`     fight state
 *  - `/hhablood boss skip`     skip the running cutscene / end the current stage
 *  - `/hhablood boss reset`    stop the fight, forget the victory (both bosses back to rest)
 *  - `/hhablood boss rebuild`  rebuild the arena from the design
 *  - `/hhablood boss pose <action> [unleashed] [broken]`  animate the nearest resting Cerberus (model check)
 *  - `/hhablood boss mpose <action> [fury] [burning]`  the same for the nearest Minotaur outside the fight
 *  - `/hhablood boss walk <speed> [blocks]`  the nearest boss outside the fight walks back and forth (gait check, 0 = stop)
 * Dev self-test: `<run>/hha_debug/bosstest` -> a fake player enters the pit, the whole fight runs through
 * (every stage fights for a while, then it is skipped), everything is logged.
 */
object BossDebug {
    /** Extra "players" for the dev self-test (fake players are not in world.players). */
    val extraTargets = ArrayList<PlayerEntity>()

    private var testTick = -1
    private var stageSince = 0
    private var lastStage = -1
    private var wasActive = false

    fun init() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                CommandManager.literal("hhablood").requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                    .then(
                        CommandManager.literal("boss")
                            .then(CommandManager.literal("tp").executes { tp(it.source); 1 })
                            .then(CommandManager.literal("info").executes { msg(it.source, PitFight.debugInfo(nether(it.source))); 1 })
                            .then(CommandManager.literal("breakhead").executes { msg(it.source, PitFight.debugBreakHead(nether(it.source))); 1 })
                            .then(CommandManager.literal("skip").executes { msg(it.source, PitFight.debugSkip(nether(it.source))); 1 })
                            .then(CommandManager.literal("reset").executes { PitFight.debugReset(nether(it.source)); msg(it.source, "Slaughter Pit reset"); 1 })
                            .then(CommandManager.literal("rebuild").executes { PitArena.forceRebuild(); msg(it.source, "rebuilding the arena"); 1 })
                            .then(
                                CommandManager.literal("pose").then(
                                    CommandManager.argument("action", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests { _, b -> net.minecraft.command.CommandSource.suggestMatching(POSES.keys, b) }
                                        .executes { pose(it.source, it.getArgument("action", String::class.java), false, 0); 1 }
                                        .then(
                                            CommandManager.argument("unleashed", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                                .executes { pose(it.source, it.getArgument("action", String::class.java), it.getArgument("unleashed", Boolean::class.java), 0); 1 }
                                                .then(
                                                    CommandManager.argument("broken", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 7))
                                                        .executes { pose(it.source, it.getArgument("action", String::class.java), it.getArgument("unleashed", Boolean::class.java), it.getArgument("broken", Int::class.java)); 1 },
                                                ),
                                        ),
                                ),
                            )
                            .then(
                                CommandManager.literal("mpose").then(
                                    CommandManager.argument("action", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests { _, b -> net.minecraft.command.CommandSource.suggestMatching(MPOSES.keys, b) }
                                        .executes { mpose(it.source, it.getArgument("action", String::class.java), 0, false); 1 }
                                        .then(
                                            CommandManager.argument("fury", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, MinotaurEntity.MAX_FURY))
                                                .executes { mpose(it.source, it.getArgument("action", String::class.java), it.getArgument("fury", Int::class.java), false); 1 }
                                                .then(
                                                    CommandManager.argument("burning", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                                        .executes { mpose(it.source, it.getArgument("action", String::class.java), it.getArgument("fury", Int::class.java), it.getArgument("burning", Boolean::class.java)); 1 },
                                                ),
                                        ),
                                ),
                            )
                            .then(
                                CommandManager.literal("walk").then(
                                    CommandManager.argument("speed", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.0, 2.0))
                                        .executes { walk(it.source, it.getArgument("speed", Double::class.java), 8.0); 1 }
                                        .then(
                                            CommandManager.argument("blocks", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(1.0, 40.0))
                                                .executes { walk(it.source, it.getArgument("speed", Double::class.java), it.getArgument("blocks", Double::class.java)); 1 },
                                        ),
                                ),
                            ),
                    ),
            )
        }
        ServerTickEvents.END_SERVER_TICK.register(::selfTest)
        ServerTickEvents.END_SERVER_TICK.register { tickWalkers() }
    }

    /** Animations of the Cerberus for `/hhablood boss pose` (looking at the model; does nothing during a fight). */
    private val POSES = mapOf(
        "sleep" to CerberusEntity.SLEEP, "wake" to CerberusEntity.WAKE, "idle" to CerberusEntity.IDLE, "bite" to CerberusEntity.BITE,
        "breath" to CerberusEntity.BREATH, "howl" to CerberusEntity.HOWL, "pounce" to CerberusEntity.POUNCE, "snap3" to CerberusEntity.SNAP3,
        "charge" to CerberusEntity.CHARGE, "nova" to CerberusEntity.NOVA, "geysers" to CerberusEntity.GEYSERS, "triple_breath" to CerberusEntity.TRIPLE_BREATH,
        "collapse" to CerberusEntity.COLLAPSE, "rise" to CerberusEntity.RISE, "death" to CerberusEntity.DEATH, "growl" to CerberusEntity.GROWL,
        "fire_ring" to CerberusEntity.FIRE_RING, "orbs" to CerberusEntity.ORBS,
    )

    /** Sets the nearest Cerberus (64 blocks) to an animation, the unleashed look and broken heads (bit 0 bleed, 1 flame, 2 howl). */
    private fun pose(src: ServerCommandSource, name: String, unleashed: Boolean, broken: Int) {
        val a = POSES[name] ?: run { msg(src, "unknown pose $name, one of ${POSES.keys}"); return }
        val world = src.world
        val c = world.getEntitiesByClass(CerberusEntity::class.java, net.minecraft.util.math.Box.of(src.position, 128.0, 128.0, 128.0)) { it.isAlive }
            .minByOrNull { it.squaredDistanceTo(src.position) } ?: run { msg(src, "no Cerberus within 64 blocks"); return }
        if (PitFight.active && c.uuid == PitFight.cerberus(world)?.uuid) { msg(src, "the fight is running - pose only works on a resting Cerberus"); return }
        c.navigation.stop()
        c.unleashed = unleashed
        c.brokenMask = broken
        c.act(a)
        msg(src, "Cerberus ${c.blockPos}: $name unleashed=$unleashed broken=$broken")
    }

    /** Animations of the Minotaur for `/hhablood boss mpose` (model check; only for a Minotaur outside the fight). */
    private val MPOSES = mapOf(
        "throne" to MinotaurEntity.THRONE, "stand" to MinotaurEntity.STAND, "idle" to MinotaurEntity.IDLE, "cleave" to MinotaurEntity.CLEAVE,
        "sweep" to MinotaurEntity.SWEEP, "charge_windup" to MinotaurEntity.CHARGE_WINDUP, "charge" to MinotaurEntity.CHARGE, "stunned" to MinotaurEntity.STUNNED,
        "whirl" to MinotaurEntity.WHIRL, "throw" to MinotaurEntity.THROW, "catch" to MinotaurEntity.CATCH, "leap" to MinotaurEntity.LEAP, "gore" to MinotaurEntity.GORE,
        "labyrinth" to MinotaurEntity.LABYRINTH, "roar" to MinotaurEntity.ROAR, "kneel" to MinotaurEntity.KNEEL, "death" to MinotaurEntity.DEATH,
        "laugh" to MinotaurEntity.LAUGH, "point" to MinotaurEntity.POINT, "absorb" to MinotaurEntity.ABSORB, "snort" to MinotaurEntity.SNORT,
        "howl_roar" to MinotaurEntity.HOWL_ROAR, "jump_down" to MinotaurEntity.JUMP_DOWN, "grab" to MinotaurEntity.GRAB, "quake" to MinotaurEntity.QUAKE,
        "stuck" to MinotaurEntity.STUCK, "sniff" to MinotaurEntity.SNIFF, "stomp" to MinotaurEntity.STOMP, "tackle" to MinotaurEntity.TACKLE,
        "slam" to MinotaurEntity.SLAM, "rage" to MinotaurEntity.RAGE, "split" to MinotaurEntity.SPLIT,
        "discard" to MinotaurEntity.DISCARD, "punch" to MinotaurEntity.PUNCH, "uppercut" to MinotaurEntity.UPPERCUT,
    )

    /** Sets the nearest Minotaur (64 blocks) outside the fight to an animation (with fury stacks / the hellfire look). */
    private fun mpose(src: ServerCommandSource, name: String, fury: Int, burning: Boolean) {
        val a = MPOSES[name] ?: run { msg(src, "unknown pose $name, one of ${MPOSES.keys}"); return }
        val m = src.world.getEntitiesByClass(MinotaurEntity::class.java, net.minecraft.util.math.Box.of(src.position, 128.0, 128.0, 128.0)) { it.isAlive }
            .minByOrNull { it.squaredDistanceTo(src.position) } ?: run { msg(src, "no Minotaur within 64 blocks"); return }
        if (PitFight.active && m.uuid == PitFight.minotaur(src.world)?.uuid) { msg(src, "the fight is running - mpose only works on a Minotaur outside it"); return }
        m.navigation.stop()
        m.fury = fury
        m.burning = burning
        m.berserk = burning   // the stage 5 look: hellfire + berserker
        m.axeOut = burning
        m.act(a)
        msg(src, "Minotaur ${m.blockPos}: $name fury=$fury burning=$burning")
    }

    /** A boss walking back and forth for `/hhablood boss walk` (start, facing, speed, track length, progress). */
    private class Walker(val start: net.minecraft.util.math.Vec3d, val yaw: Float, val speed: Double, val length: Double) {
        var s = 0.0
        var dir = 1
        var turn = 0
    }

    private val walkers = HashMap<PitBoss, Walker>()

    private fun walk(src: ServerCommandSource, speed: Double, blocks: Double) {
        val b = src.world.getEntitiesByClass(PitBoss::class.java, net.minecraft.util.math.Box.of(src.position, 128.0, 128.0, 128.0)) { it.isAlive }
            .minByOrNull { it.squaredDistanceTo(src.position) } ?: run { msg(src, "no boss within 64 blocks"); return }
        if (PitFight.active) { msg(src, "the fight is running"); return }
        val old = walkers.remove(b)
        if (speed <= 0.0) {
            if (old != null) b.refreshPositionAndAngles(old.start.x, old.start.y, old.start.z, old.yaw, 0f)
            msg(src, "stopped")
            return
        }
        walkers[b] = Walker(old?.start ?: b.entityPos, old?.yaw ?: b.yaw, speed, blocks)
        msg(src, "${b.type.untranslatedName} walks $blocks blocks at $speed blocks/tick")
    }

    /** Moves the walking bosses (like a cutscene glide: the client animates the steps from the motion). */
    private fun tickWalkers() {
        val it = walkers.entries.iterator()
        while (it.hasNext()) {
            val (b, w) = it.next()
            if (b.isRemoved) { it.remove(); continue }
            if (w.turn > 0) {
                // turns round on the spot at the end of the track (12 degrees a tick)
                w.turn--
                val y = b.yaw + 12f
                b.yaw = y; b.bodyYaw = y; b.headYaw = y
                if (w.turn == 0) { val f = if (w.dir > 0) w.yaw else w.yaw + 180f; b.yaw = f; b.bodyYaw = f; b.headYaw = f }
                continue
            }
            w.s += w.speed * w.dir
            if (w.s > w.length || w.s < 0.0) { w.dir = -w.dir; w.s = w.s.coerceIn(0.0, w.length); w.turn = 15; continue }
            val r = Math.toRadians(w.yaw.toDouble())
            val p = w.start.add(-kotlin.math.sin(r) * w.s, 0.0, kotlin.math.cos(r) * w.s)
            b.setPosition(p)
            b.velocity = net.minecraft.util.math.Vec3d.ZERO
        }
    }

    private fun nether(src: ServerCommandSource): ServerWorld = src.server.getWorld(World.NETHER)!!

    private fun msg(src: ServerCommandSource, s: String) = src.sendFeedback({ Text.literal(s) }, false)

    private fun tp(src: ServerCommandSource) {
        val c = PitArena.center() ?: return
        val p = src.player ?: return
        p.teleport(nether(src), c.x + 0.5, HeartVaultPiece.FLOOR.toDouble(), c.z + 50.5, emptySet(), 180f, 0f, true)
        msg(src, "Slaughter Pit: ${c.x} ${HeartVaultPiece.FLOOR} ${c.z} (walk north into the dome)")
    }

    // ------------------------------------------------------------------------------------- self-test

    private fun selfTest(server: MinecraftServer) {
        if (testTick == -1) {
            val f = server.getPath("hha_debug/bosstest").toFile()
            testTick = if (f.exists()) { f.delete(); 0 } else -2
            if (testTick == 0) HhaBlood.LOGGER.info("[bosstest] started")
        }
        if (testTick < 0) return
        testTick++
        val world = server.getWorld(World.NETHER) ?: return
        val c = PitArena.center() ?: return
        try {
            if (testTick == 40) {
                for (i in -6..6) for (j in -6..((PitApproach.MAX_DZ + 8) shr 4)) world.setChunkForced((c.x shr 4) + i, (c.z shr 4) + j, true)
                PitFight.debugReset(world)
                val fake = FakePlayer.get(world)
                fake.refreshPositionAndAngles(c.x + 0.5, HeartVaultPiece.FLOOR.toDouble(), c.z + 60.5, 180f, 0f)
                extraTargets += fake
                HhaBlood.LOGGER.info("[bosstest] fake player waits in the gate hall at {}", fake.blockPos)
            }
            if (testTick < 40) return
            val fake = extraTargets.firstOrNull() ?: return
            if (testTick == 120) {
                // simulate an arena of an older version: break signature blocks, the check must rebuild them
                for (p in HeartVaultPiece.PILLARS.take(4)) world.setBlockState(net.minecraft.util.math.BlockPos(c.x + p.x.toInt(), HeartVaultPiece.FLOOR + 3, c.z + p.z.toInt()), net.minecraft.block.Blocks.AIR.defaultState)
                world.setBlockState(net.minecraft.util.math.BlockPos(c.x, HeartVaultPiece.FLOOR - 1, c.z), net.minecraft.block.Blocks.NETHERRACK.defaultState)
                PitArena.resetSession()
                HhaBlood.LOGGER.info("[bosstest] sabotaged 5 signature blocks, waiting for the rebuild")
            }
            // after the bosses are spawned (idle tick), walk into the dome
            if (testTick == 200) fake.refreshPositionAndAngles(c.x + 0.5, HeartVaultPiece.FLOOR.toDouble(), c.z + 20.5, 180f, 0f)
            if (testTick % 20 == 0) {
                HhaBlood.LOGGER.info("[bosstest] t={} {} hazards fake={} hp={}", testTick, PitFight.debugInfo(world), fake.blockPos, fake.health)
            }
            if (PitFight.stage != lastStage) { lastStage = PitFight.stage; stageSince = testTick }
            // the fake player runs circles while a stage is fought
            if (PitFight.active && !PitFight.sceneRunning() && PitFight.stage > 0) {
                val a = testTick * 0.02
                // in the labyrinth the fake player stays in its corridor (labyrinthTest moves it)
                if (PitFight.stage == PitFight.LABYRINTH) labyrinthTest(world, fake, testTick - stageSince)
                else fake.refreshPositionAndAngles(c.x + 0.5 + kotlin.math.cos(a) * 16, HeartVaultPiece.FLOOR.toDouble(), c.z + 0.5 + kotlin.math.sin(a) * 16, fake.yaw, 0f)
                fake.health = fake.maxHealth
                // head mechanic: one clean head hit, one body hit, then break head 0 by hand
                if (PitFight.stage == PitFight.CERBERUS && testTick - stageSince in 300..420 && (testTick - stageSince) % 20 == 0) {
                    PitFight.cerberus(world)?.let { cb ->
                        val n = (testTick - stageSince - 300) / 20
                        val head = cb.headPos(0)
                        val body = cb.entityPos.add(0.0, cb.height * 0.4, 0.0)
                        val from = if (n == 1) body.subtract(cb.rotationVector.multiply(4.0)) else head.add(cb.rotationVector.multiply(3.0))
                        val look = (if (n == 1) body else head).subtract(from)
                        fake.refreshPositionAndAngles(from.x, from.y - fake.standingEyeHeight, from.z,
                            (Math.toDegrees(kotlin.math.atan2(-look.x, look.z))).toFloat(),
                            (-Math.toDegrees(kotlin.math.atan2(look.y, kotlin.math.sqrt(look.x * look.x + look.z * look.z)))).toFloat())
                        fake.headYaw = fake.yaw
                        val before = cb.headHp.toList()
                        cb.timeUntilRegen = 0
                        cb.damage(world, world.damageSources.playerAttack(fake), 40f)
                        HhaBlood.LOGGER.info("[bosstest] head hit #{} ({}): heads {} -> {} broken={} hp={}", n, if (n == 1) "body" else "head 0",
                            before, cb.headHp.toList(), cb.brokenMask, cb.health)
                    }
                }
                val mino = PitFight.minotaur(world)
                if (mino != null && PitFight.stage == PitFight.LAST_BULL) {
                    check(mino.berserk && mino.axeOut) { "Phase V got its axe back" }
                    check(mino.action in setOf(MinotaurEntity.IDLE, MinotaurEntity.STUNNED, MinotaurEntity.PUNCH,
                        MinotaurEntity.UPPERCUT, MinotaurEntity.STOMP, MinotaurEntity.TACKLE, MinotaurEntity.SLAM,
                        MinotaurEntity.RAGE, MinotaurEntity.SPLIT, MinotaurEntity.GRAB)) { "Armed Phase V action ${mino.action}" }
                    if (testTick - stageSince == 30) {
                        check(PitLabyrinth.canUse(fake)) { "Blood Trail lost in Phase V" }
                        PitLabyrinth.cast(fake as net.minecraft.server.network.ServerPlayerEntity)
                        check(PitLabyrinth.isHidden(fake)) { "Blood Trail failed in Phase V" }
                        HhaBlood.LOGGER.info("[bosstest] phase V unarmed, Blood Trail works")
                    }
                }
                if (mino != null && mino.action == MinotaurEntity.STUCK && mino.actionAge == 5) HhaBlood.LOGGER.info("[bosstest] minotaur axe stuck (stage {})", PitFight.stage)
                if (mino != null && mino.action == MinotaurEntity.CHARGE_WINDUP && mino.actionAge == 1) HhaBlood.LOGGER.info("[bosstest] minotaur charge (stage {})", PitFight.stage)
                if (mino != null && mino.actionAge == 1 && (mino.action in MinotaurEntity.SNIFF..MinotaurEntity.SPLIT || (PitFight.stage == PitFight.LAST_BULL && mino.action != MinotaurEntity.IDLE)))
                    HhaBlood.LOGGER.info("[bosstest] minotaur action {} (stage {}, berserk={} fury={})", mino.action, PitFight.stage, mino.berserk, mino.fury)
                if (mino != null && mino.actionAge == 1 && mino.action == MinotaurEntity.GRAB && mino.berserk) HhaBlood.LOGGER.info("[bosstest] minotaur berserk grab (suplex)")
                if (testTick - stageSince == 700) HhaBlood.LOGGER.info("[bosstest] skip stage {}: {}", PitFight.stage, PitFight.debugSkip(world))
            }
            if (PitFight.active) wasActive = true
            if (testTick % 200 == 0 && !PitFight.active && !wasActive) fake.refreshPositionAndAngles(c.x + 0.5, HeartVaultPiece.FLOOR.toDouble(), c.z + 20.5, 180f, 0f)
            if (wasActive && !PitFight.active) {
                val st = PitArena.state(server)
                HhaBlood.LOGGER.info("[bosstest] end at t={}: defeated={} kills={} inventory={}", testTick, st.defeated, st.kills,
                    (0 until fake.inventory.size()).map { fake.inventory.getStack(it) }.filter { !it.isEmpty }.joinToString { "${it.count}x ${it.item}" })
                HhaBlood.LOGGER.info("[bosstest] arena after the fight: pillar={} centre={} gate={} changed={}",
                    world.getBlockState(net.minecraft.util.math.BlockPos(c.x + HeartVaultPiece.PILLARS[0].x.toInt(), HeartVaultPiece.FLOOR + 3, c.z + HeartVaultPiece.PILLARS[0].z.toInt())).block,
                    world.getBlockState(net.minecraft.util.math.BlockPos(c.x, HeartVaultPiece.FLOOR - 1, c.z)).block,
                    world.getBlockState(net.minecraft.util.math.BlockPos(c.x, HeartVaultPiece.FLOOR + 2, c.z + HeartVaultPiece.GATE_Z)).block,
                    PitArena.state(server).changed.size)
                finish(server)
            }
            if (testTick > 14000) { HhaBlood.LOGGER.error("[bosstest] timeout"); finish(server) }
        } catch (t: Throwable) {
            HhaBlood.LOGGER.error("[bosstest] failed", t)
            finish(server)
        }
    }

    /** Test both generated routes and real block clearance for the Minotaur's body, including corners. */
    fun verifyLabyrinth(world: ServerWorld): String {
        var minRoute = Int.MAX_VALUE
        var minCells = Int.MAX_VALUE
        for (seed in 0L until 16L) {
            val layout = checkNotNull(PitLabyrinth.layout(seed))
            check(layout.cells.size >= 200) { "Maze too small: ${layout.cells.size}" }
            check(layout.deadEnds >= 15) { "Maze lost its branches: ${layout.deadEnds}" }
            val longest = layout.routes.values.maxOrNull() ?: 0
            check(longest >= 120) { "Maze routes too short: $longest" }
            minRoute = minOf(minRoute, longest)
            minCells = minOf(minCells, layout.cells.size)
        }
        val c = checkNotNull(PitArena.center())
        val queue = java.util.ArrayDeque<Pair<Int, Int>>()
        val reached = HashSet<Pair<Int, Int>>()
        fun clear(x: Int, z: Int): Boolean {
            if (x * x + z * z > 40 * 40) return false
            for (dx in -1..1) for (dz in -1..1) for (dy in 0..3) {
                val p = net.minecraft.util.math.BlockPos(c.x + x + dx, HeartVaultPiece.FLOOR + dy, c.z + z + dz)
                if (!world.getBlockState(p).getCollisionShape(world, p).isEmpty) return false
            }
            return true
        }
        reached += 0 to 0
        queue.add(0 to 0)
        while (queue.isNotEmpty()) {
            val (x, z) = queue.removeFirst()
            for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val n = x + dx to z + dz
                if (n in reached || !clear(n.first, n.second)) continue
                reached += n
                queue.add(n)
            }
        }
        // Current layout has a different random seed; use the runtime cell list through spreadSpots.
        val spots = PitLabyrinth.spreadSpots(PitArena.mid(), 500, -0.1, 0.0, world.random).distinct()
        check(spots.all { (kotlin.math.floor(it.x).toInt() - c.x to kotlin.math.floor(it.z).toInt() - c.z) in reached }) {
            "A maze corridor is not reachable by the Minotaur"
        }
        return "16 seeds: >=$minCells cells, longest routes >=$minRoute blocks; ${spots.size} real corridors reachable"
    }

    /** Stage 4 script: sink into the blood, stay unseen, backstab, make noise, sink again. */
    private fun labyrinthTest(world: net.minecraft.server.world.ServerWorld, fake: net.minecraft.entity.player.PlayerEntity, st: Int) {
        val mino = PitFight.minotaur(world) ?: return
        val c = PitArena.center() ?: return
        fun dist() = String.format("%.1f", kotlin.math.sqrt(fake.squaredDistanceTo(mino)))
        when (st) {
            2 -> {
                HhaBlood.LOGGER.info("[bosstest] {}", verifyLabyrinth(world))
                var n = 0
                for (x in -32..32) for (z in -32..32) {
                    if (!world.getBlockState(net.minecraft.util.math.BlockPos(c.x + x, HeartVaultPiece.FLOOR + 2, c.z + z)).isAir) n++
                }
                HhaBlood.LOGGER.info("[bosstest] labyrinth: {} solid blocks at head height (r<32), maze={} fake={} minotaur={} dist={}",
                    n, PitLabyrinth.hasMaze(), fake.blockPos, mino.blockPos, dist())
            }
            40 -> {
                PitLabyrinth.cast(fake as net.minecraft.server.network.ServerPlayerEntity)
                HhaBlood.LOGGER.info("[bosstest] labyrinth: cast -> hidden={} canUse={}", PitLabyrinth.isHidden(fake), PitLabyrinth.canUse(fake))
            }
            100, 150 -> HhaBlood.LOGGER.info("[bosstest] labyrinth: hidden={} minotaur action={} at {} dist={}", PitLabyrinth.isHidden(fake), mino.action, mino.blockPos, dist())
            160 -> {
                // sneak up behind him and stab
                val behind = mino.entityPos.subtract(mino.forward().multiply(2.9))
                fake.refreshPositionAndAngles(behind.x, mino.y, behind.z, mino.yaw, 0f)
                val hp = mino.health
                mino.timeUntilRegen = 0
                mino.damage(world, world.damageSources.playerAttack(fake), 12f)
                HhaBlood.LOGGER.info("[bosstest] labyrinth: backstab hp {} -> {} (lost {}), action={} stunned={} hidden after={}",
                    hp, mino.health, hp - mino.health, mino.action, mino.isStunned(), PitLabyrinth.isHidden(fake))
            }
            161 -> {
                // a normal hit for comparison (he is stunned now: x1.5)
                val hp = mino.health
                mino.timeUntilRegen = 0
                mino.damage(world, world.damageSources.playerAttack(fake), 12f)
                HhaBlood.LOGGER.info("[bosstest] labyrinth: normal hit while stunned lost {}", hp - mino.health)
            }
            300 -> {
                // far away in a corridor, then running loudly
                val spot = mino.entityPos.add(12.0, 0.0, 0.0)
                fake.refreshPositionAndAngles(spot.x, spot.y, spot.z, 0f, 0f)
                HhaBlood.LOGGER.info("[bosstest] labyrinth: fake moved to {} dist={} (sprinting from now)", fake.blockPos, dist())
            }
            450 -> {
                PitLabyrinth.cast(fake as net.minecraft.server.network.ServerPlayerEntity)
                HhaBlood.LOGGER.info("[bosstest] labyrinth: second cast -> hidden={}", PitLabyrinth.isHidden(fake))
            }
        }
        if (st in 301..420) {
            fake.isSprinting = true
            fake.velocity = net.minecraft.util.math.Vec3d(0.2, 0.0, 0.1)
        } else if (st == 421) {
            fake.isSprinting = false
            fake.velocity = net.minecraft.util.math.Vec3d.ZERO
        }
    }

    private fun finish(server: MinecraftServer) {
        testTick = -2
        extraTargets.clear()
        server.stop(false)
    }
}
