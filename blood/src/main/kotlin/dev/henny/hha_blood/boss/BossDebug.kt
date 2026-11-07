package dev.henny.hha_blood.boss

import dev.henny.hha_blood.HhaBlood
import dev.henny.hha_blood.hollows.HeartVaultPiece
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
                            ),
                    ),
            )
        }
        ServerTickEvents.END_SERVER_TICK.register(::selfTest)
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
                for (i in -6..6) for (j in -6..6) world.setChunkForced((c.x shr 4) + i, (c.z shr 4) + j, true)
                PitFight.debugReset(world)
                val fake = FakePlayer.get(world)
                fake.refreshPositionAndAngles(c.x + 0.5, HeartVaultPiece.FLOOR.toDouble(), c.z + 70.5, 180f, 0f)
                extraTargets += fake
                HhaBlood.LOGGER.info("[bosstest] fake player waits in the tunnel at {}", fake.blockPos)
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
                fake.refreshPositionAndAngles(c.x + 0.5 + kotlin.math.cos(a) * 16, HeartVaultPiece.FLOOR.toDouble(), c.z + 0.5 + kotlin.math.sin(a) * 16, fake.yaw, 0f)
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
                if (mino != null && mino.action == MinotaurEntity.STUCK && mino.actionAge == 5) HhaBlood.LOGGER.info("[bosstest] minotaur axe stuck (stage {})", PitFight.stage)
                if (mino != null && mino.action == MinotaurEntity.CHARGE_WINDUP && mino.actionAge == 1) HhaBlood.LOGGER.info("[bosstest] minotaur charge (stage {})", PitFight.stage)
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

    private fun finish(server: MinecraftServer) {
        testTick = -2
        extraTargets.clear()
        server.stop(false)
    }
}
