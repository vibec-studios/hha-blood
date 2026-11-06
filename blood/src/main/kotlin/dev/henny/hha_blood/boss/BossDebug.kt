package dev.henny.hha_blood.boss

import dev.henny.hha_blood.HhaBlood
import dev.henny.hha_blood.hollows.HeartVaultPiece
import dev.henny.hha_blood.hollows.HollowsRegion
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
import java.util.Optional

/**
 * Testing helpers for the boss:
 *  - `/hhablood boss tp`     teleport into the Heart Vault (Nether)
 *  - `/hhablood boss wake`   wake the Prince now
 *  - `/hhablood boss reset`  remove him, forget "defeated" -> he sits on his throne again
 *  - `/hhablood boss info`   state
 * Dev self-test: `<run>/hha_debug/bosstest` -> a (invulnerable) fake player stands in the arena, the
 * Prince fights it for a while, gets damaged into the enraged phase and killed; everything is logged.
 */
object BossDebug {
    /** Extra "players" for the dev self-test (fake players are not in world.players). */
    val extraTargets = ArrayList<PlayerEntity>()

    private var testTick = -1

    fun init() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                CommandManager.literal("hhablood").requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                    .then(
                        CommandManager.literal("boss")
                            .then(CommandManager.literal("tp").executes { tp(it.source); 1 })
                            .then(CommandManager.literal("wake").executes { wake(it.source); 1 })
                            .then(CommandManager.literal("reset").executes { reset(it.source); 1 })
                            .then(CommandManager.literal("info").executes { info(it.source); 1 }),
                    ),
            )
        }
        ServerTickEvents.END_SERVER_TICK.register(::selfTest)
    }

    private fun nether(src: ServerCommandSource): ServerWorld = src.server.getWorld(World.NETHER)!!

    private fun tp(src: ServerCommandSource) {
        val c = HollowsRegion.center() ?: return
        val p = src.player ?: return
        p.teleport(nether(src), c.x + 0.5, HeartVaultPiece.FLOOR.toDouble(), c.z + 22.5, emptySet(), 180f, 0f, true)
        src.sendFeedback({ Text.literal("Heart Vault: ${c.x} ${HeartVaultPiece.FLOOR} ${c.z}") }, false)
    }

    private fun wake(src: ServerCommandSource) {
        val w = nether(src)
        val st = HeartVaultArena.state(src.server)
        val boss = HeartVaultArena.prince(w) ?: HeartVaultArena.spawnPrince(w, st.also { it.defeated = false })
        boss?.awaken()
        src.sendFeedback({ Text.literal(if (boss == null) "could not spawn the Prince" else "the Prince wakes up") }, false)
    }

    private fun reset(src: ServerCommandSource) {
        val w = nether(src)
        val st = HeartVaultArena.state(src.server)
        HeartVaultArena.prince(w)?.discard()
        st.boss = Optional.empty(); st.defeated = false; st.markDirty()
        src.sendFeedback({ Text.literal("Heart Vault reset - the Prince returns to his throne when you come close") }, false)
    }

    private fun info(src: ServerCommandSource) {
        val w = nether(src)
        val st = HeartVaultArena.state(src.server)
        val b = HeartVaultArena.prince(w)
        src.sendFeedback({
            Text.literal("centre ${HollowsRegion.center()}, defeated ${st.defeated}, kills ${st.kills}, boss ${st.boss.orElse(null)} " +
                (b?.let { "action ${it.action} hp ${"%.0f".format(it.health)}/${"%.0f".format(it.maxHealth)}" } ?: "(not loaded)"))
        }, false)
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
        val c = HollowsRegion.center() ?: return
        try {
            when {
                testTick == 40 -> {
                    for (i in -4..4) for (j in -4..4) world.setChunkForced((c.x shr 4) + i, (c.z shr 4) + j, true)
                    val st = HeartVaultArena.state(server)
                    st.defeated = false; st.markDirty()
                    val fake = FakePlayer.get(world)
                    fake.refreshPositionAndAngles(c.x + 0.5, HeartVaultPiece.FLOOR.toDouble(), c.z + 8.5, 180f, 0f)
                    extraTargets += fake
                    HhaBlood.LOGGER.info("[bosstest] fake player at {}", fake.blockPos)
                }
                testTick > 40 -> {
                    val prince = HeartVaultArena.prince(world)
                    val dragon = HeartVaultArena.dragon(world)
                    if (testTick % 20 == 0) {
                        HhaBlood.LOGGER.info("[bosstest] t={} prince={} dragon={} hp={} pos={}", testTick, prince?.action, dragon?.action,
                            prince?.health ?: dragon?.health, (prince ?: dragon)?.blockPos)
                    }
                    // the fake player walks in a slow circle so the bosses have to chase it
                    extraTargets.firstOrNull()?.let { p ->
                        val a = testTick * 0.01
                        p.refreshPositionAndAngles(c.x + 0.5 + kotlin.math.cos(a) * 14, HeartVaultPiece.FLOOR.toDouble(), c.z + 0.5 + kotlin.math.sin(a) * 14 + 4, p.yaw, 0f)
                    }
                    if (prince != null && testTick in 600..800 && prince.health > prince.maxHealth * 0.5f) prince.damage(world, world.damageSources.generic(), prince.health - prince.maxHealth * 0.45f)
                    // the killing blow -> transformation into Cruorax
                    if (prince != null && testTick in 900..1100 && prince.action != BloodPrinceEntity.TRANSFORM) prince.damage(world, world.damageSources.generic(), prince.health + 5f)
                    if (dragon != null && testTick == 2000) dragon.health = dragon.maxHealth * 0.3f
                    if (dragon != null && dragon.isAlive && testTick in 3300..3400) dragon.damage(world, world.damageSources.genericKill(), Float.MAX_VALUE)
                    if (testTick == 3560) {
                        val st = HeartVaultArena.state(server)
                        HhaBlood.LOGGER.info("[bosstest] end: defeated={} kills={} items around={}", st.defeated, st.kills,
                            world.getEntitiesByClass(net.minecraft.entity.ItemEntity::class.java, net.minecraft.util.math.Box.of(net.minecraft.util.math.Vec3d.of(c), 160.0, 100.0, 160.0)) { true }.size)
                        extraTargets.clear()
                        server.stop(false)
                    }
                }            }
        } catch (t: Throwable) {
            HhaBlood.LOGGER.error("[bosstest] failed", t)
            testTick = -2
            server.stop(false)
        }
    }
}
