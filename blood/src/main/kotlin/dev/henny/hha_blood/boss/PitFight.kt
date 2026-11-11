package dev.henny.hha_blood.boss

import dev.henny.hha.config.HhaConfig
import dev.henny.hha_blood.BloodEffects
import dev.henny.hha_blood.BloodItems
import dev.henny.hha_blood.HhaBlood
import dev.henny.hha_blood.hollows.HeartVaultPiece
import dev.henny.hha_blood.hollows.HollowsBlocks
import dev.henny.hha_blood.logic.Bleed
import dev.henny.hha_blood.logic.BloodFx
import dev.henny.hha_blood.logic.BloodHud
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.boss.BossBar
import net.minecraft.entity.boss.ServerBossBar
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.damage.DamageTypes
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.tag.DamageTypeTags
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Formatting
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import net.minecraft.world.World
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The Slaughter Pit fight (built after HHA's Hermes fight): the Crimson Minotaur waits on his throne, his
 * Blood Cerberus sleeps behind the portcullis. Whoever enters the dome starts it:
 *
 *  intro cutscene -> 1 Cerberus -> (it falls, the Minotaur makes it burn) -> 2 Cerberus unleashed ->
 *  (it dies, the Minotaur jumps down) -> 3 Minotaur -> (the labyrinth awakens) -> 4 Labyrinth ->
 *  (he takes the Cerberus' fire) -> 5 The last bull -> finale + rewards.
 *
 * Fair play like Hermes: a Totem of Undying pops as everywhere, without one you die for real (config
 * `boss_safe_knockout`: knocked out into the entrance tunnel instead); during cutscenes everybody is invulnerable and frozen; the arena cannot be
 * changed (PitArena); every change of the fight is put back afterwards. If everybody is out for 10 s, the
 * fight resets. Health and damage scale with the number of players; one hit can take at most `boss_max_hit`.
 */
object PitFight {

    const val IDLE = 0
    const val CERBERUS = 1
    const val UNLEASHED = 2
    const val MINOTAUR = 3
    const val LABYRINTH = 4
    const val LAST_BULL = 5

    /** Current stage (0 = no fight running or the intro). */
    var stage = IDLE
        private set
    var active = false
        private set

    private val participants = LinkedHashSet<UUID>()
    private val knockedOut = HashSet<UUID>()
    private val outside = HashMap<UUID, Int>()
    private var scene: SceneRun? = null
    private var scenes: PitScenes? = null
    private var minotaurId: UUID? = null
    private var cerberusId: UUID? = null
    private var transitionQueued = false
    private var abandonTicks = 0
    private var ticks = 0
    private var healthScale = 1f
    private var damageScale = 1f
    private var tauntIn = 400
    private var throwIn = 300
    private val paralyzed = HashMap<UUID, Int>()

    private val bar: ServerBossBar = (ServerBossBar(Text.translatable("entity.hha_blood.blood_cerberus"), BossBar.Color.RED, BossBar.Style.NOTCHED_10)
        .also { it.setDarkenSky(true); it.setThickenFog(true) }) as ServerBossBar

    /** One bar per Cerberus head (bleed, flame, howl) while the hound fights. */
    private val headBars: List<ServerBossBar> = listOf(BossBar.Color.RED, BossBar.Color.YELLOW, BossBar.Color.PURPLE).mapIndexed { i, color ->
        ServerBossBar(Text.translatable("boss.hha_blood.bar.head.$i"), color, BossBar.Style.NOTCHED_6)
    }

    private fun removeFromBars(p: ServerPlayerEntity) {
        bar.removePlayer(p)
        headBars.forEach { it.removePlayer(p) }
    }

    /** Head hits count fully, body hits only this much (the heads are the weak spots). */
    private const val BODY_FACTOR = 0.3f
    private var bodyHintShown = false

    /** A running cutscene: the script (one step per tick) and the end state (also used when it is skipped). */
    class SceneRun(val name: String, val steps: Iterator<Unit>, val skippable: Boolean, val finish: () -> Unit) {
        val votes = HashSet<UUID>()
    }

    // ------------------------------------------------------------------------------------- setup

    fun init() {
        PitArena.init()
        ServerTickEvents.END_SERVER_TICK.register(::tick)
        ServerLifecycleEvents.SERVER_STOPPING.register { server -> shutdown(server) }
        ServerLifecycleEvents.SERVER_STARTED.register { PitArena.resetSession() }
        ServerPlayNetworking.registerGlobalReceiver(CineSkipPayload.ID) { _, ctx -> voteSkip(ctx.player()) }
        ServerLivingEntityEvents.ALLOW_DEATH.register { entity, source, _ ->
            when {
                entity !is ServerPlayerEntity || !active || entity.uuid !in participants -> true
                // a Totem of Undying works as everywhere (Fabric asks before the totem is checked)
                hasTotem(entity) -> true
                // optional (config): knocked out instead of dying
                HhaConfig.enabled("hha_blood.boss_safe_knockout") -> !knockOut(entity, source)
                else -> true
            }
        }
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, _ ->
            if (entity is ServerPlayerEntity && entity.uuid in participants) onPlayerDied(entity)
        }
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, _, _ ->
            // during cutscenes nobody gets hurt
            !(entity is ServerPlayerEntity && active && scene != null && entity.uuid in participants)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> onLeave(handler.player) }
        UseBlockCallback.EVENT.register { player, world, hand, hit ->
            if (world.isClient || !world.getBlockState(hit.blockPos).isOf(HollowsBlocks.SANGUINE_ALTAR)) return@register ActionResult.PASS
            useAltar(world as ServerWorld, player, player.getStackInHand(hand))
        }
    }

    private fun nether(server: MinecraftServer): ServerWorld? = server.getWorld(World.NETHER)

    // ------------------------------------------------------------------------------------- players

    private fun lookup(world: ServerWorld, id: UUID): ServerPlayerEntity? =
        world.server!!.playerManager.getPlayer(id) ?: BossDebug.extraTargets.firstOrNull { it.uuid == id } as? ServerPlayerEntity

    /** Everyone in the fight that is online and alive (incl. the self-test fake player). */
    fun players(world: ServerWorld): List<ServerPlayerEntity> =
        participants.mapNotNull { lookup(world, it) }.filter { it.isAlive && it.entityWorld == world }

    /** Players the bosses attack. */
    fun targets(world: ServerWorld): List<PlayerEntity> =
        players(world).filter { !it.isSpectator && PitArena.inArena(it.entityPos) }

    fun nearestTarget(world: ServerWorld, from: Vec3d): PlayerEntity? = targets(world).minByOrNull { it.squaredDistanceTo(from) }

    fun hint(player: ServerPlayerEntity, key: String) = BloodHud.show(player, key, BloodHud.RED, ticks = 60)

    fun lineOfSight(world: ServerWorld, from: Vec3d, to: Vec3d): Boolean {
        val hit = world.raycast(RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, net.minecraft.block.ShapeContext.absent()))
        return hit.type == HitResult.Type.MISS
    }

    fun paralyze(world: ServerWorld, p: PlayerEntity, ticks: Int) {
        p.addStatusEffect(StatusEffectInstance(BloodEffects.PARALYZED, ticks, 0, false, true, true))
        paralyzed[p.uuid] = ticks
        if (p is ServerPlayerEntity) {
            Cine.paralyzed(p, ticks)
            BloodFx.soundFor(p, BossSounds.PARALYZE, 1f, 1f)
        }
        world.spawnParticles(net.minecraft.particle.DustParticleEffect(0x8A40FF, 1.2f), p.x, p.y + 1.0, p.z, 20, 0.4, 0.7, 0.4, 0.0)
    }

    private fun tickParalysis(world: ServerWorld) {
        if (paralyzed.isEmpty()) return
        val it = paralyzed.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.setValue(e.value - 1)
            val p = lookup(world, e.key)
            if (p != null && e.value > 0) {
                // hold them in place (no knockback slides)
                p.velocity = Vec3d(0.0, min(p.velocity.y, 0.0), 0.0)
                p.velocityDirty = true
                if (e.value % 4 == 0) world.spawnParticles(net.minecraft.particle.DustParticleEffect(0x8A40FF, 0.9f), p.x, p.y + 2.1, p.z, 3, 0.3, 0.1, 0.3, 0.0)
            }
            if (e.value <= 0 || p == null) it.remove()
        }
    }

    // ------------------------------------------------------------------------------------- bosses

    fun minotaur(world: ServerWorld): MinotaurEntity? = minotaurId?.let { world.getEntity(it) as? MinotaurEntity }
    fun cerberus(world: ServerWorld): CerberusEntity? = cerberusId?.let { world.getEntity(it) as? CerberusEntity }

    fun cerberusFighting(): Boolean = active && scene == null && (stage == CERBERUS || stage == UNLEASHED)
    fun minotaurFighting(): Boolean = active && scene == null && stage >= MINOTAUR

    private fun arenaBox(): Box {
        val m = PitArena.mid()
        return Box(m.x - 70, m.y - 6, m.z - 50, m.x + 50, m.y + 46, m.z + 50)
    }

    /** Finds (or spawns) both bosses at their resting places while no fight runs. */
    private fun ensureIdleBosses(world: ServerWorld) {
        val box = arenaBox()
        val minos = world.getEntitiesByClass(MinotaurEntity::class.java, box) { it.isAlive }
        val cerbs = world.getEntitiesByClass(CerberusEntity::class.java, box) { it.isAlive }
        minos.drop(1).forEach { it.discard() }
        cerbs.drop(1).forEach { it.discard() }
        val m = minos.firstOrNull() ?: spawn(world, BossContent.MINOTAUR)
        val c = cerbs.firstOrNull() ?: spawn(world, BossContent.CERBERUS)
        if (m != null) { minotaurId = m.uuid; restMinotaur(m) }
        if (c != null) { cerberusId = c.uuid; restCerberus(c) }
    }

    private fun <T : PitBoss> spawn(world: ServerWorld, type: net.minecraft.entity.EntityType<T>): T? {
        val e = type.create(world, SpawnReason.EVENT) ?: return null
        val p = if (e is MinotaurEntity) PitArena.at(HeartVaultPiece.THRONE_SEAT.x, HeartVaultPiece.THRONE_SEAT.y, HeartVaultPiece.THRONE_SEAT.z)
            else PitArena.at(HeartVaultPiece.KENNEL_SPAWN.x, 0.0, HeartVaultPiece.KENNEL_SPAWN.z)
        e.refreshPositionAndAngles(p.x, p.y, p.z, 0f, 0f)
        if (!world.spawnEntity(e)) return null
        HhaBlood.LOGGER.info("Slaughter Pit: spawned {} at {}", type.name.string, e.blockPos)
        return e
    }

    private fun restMinotaur(m: MinotaurEntity) {
        val p = PitArena.at(HeartVaultPiece.THRONE_SEAT.x, HeartVaultPiece.THRONE_SEAT.y, HeartVaultPiece.THRONE_SEAT.z)
        if (m.squaredDistanceTo(p) > 0.25 || m.action != MinotaurEntity.THRONE) {
            m.refreshPositionAndAngles(p.x, p.y, p.z, 0f, 0f)
            m.headYaw = 0f; m.bodyYaw = 0f
            m.act(MinotaurEntity.THRONE)
        }
        m.refreshStats()
        m.scripted = true
        m.health = m.maxHealth
        m.fury = 0; m.burning = false; m.axeOut = false; m.berserk = false
        m.setNoGravity(false)
    }

    private fun restCerberus(c: CerberusEntity) {
        val p = PitArena.at(HeartVaultPiece.KENNEL_SPAWN.x, 0.0, HeartVaultPiece.KENNEL_SPAWN.z)
        if (c.squaredDistanceTo(p) > 0.25 || c.action != CerberusEntity.SLEEP) {
            // the kennel lies under the balcony, he sleeps facing the gate (south)
            c.refreshPositionAndAngles(p.x, p.y, p.z, 0f, 0f)
            c.headYaw = 0f; c.bodyYaw = 0f
            c.act(CerberusEntity.SLEEP)
        }
        c.refreshStats()
        c.scripted = true
        c.health = c.maxHealth
        c.brokenMask = 0
        c.unleashed = false
        c.setNoGravity(false)
    }

    /** A boss was loaded from disk (chunk load / restart): a running fight does not survive that. */
    fun onBossLoaded(boss: PitBoss) {
        if (!active) boss.scripted = true
    }

    fun onBossRemoved(boss: PitBoss) {
        if (boss.uuid == cerberusId && active && stage <= UNLEASHED) HhaBlood.LOGGER.warn("Slaughter Pit: the Cerberus died outside the script")
        if (boss.uuid == minotaurId && active) HhaBlood.LOGGER.warn("Slaughter Pit: the Minotaur died outside the script")
    }

    // ------------------------------------------------------------------------------------- damage

    fun scaledDamage(base: Float): Float = base * damageScale * HhaConfig.num("hha_blood.boss_damage").toFloat()

    private fun floorFor(boss: PitBoss): Float = when {
        boss is MinotaurEntity && stage == MINOTAUR -> boss.maxHealth * 0.66f
        boss is MinotaurEntity && stage == LABYRINTH -> boss.maxHealth * 0.33f
        else -> 1f
    }

    /** How much of a hit on a boss counts (0 = deflected). Queues the next cutscene at a stage's end. */
    fun onBossDamaged(boss: PitBoss, source: DamageSource, amount: Float): Float {
        if (!active || scene != null || transitionQueued) return 0f
        val mine = when (boss) {
            is CerberusEntity -> stage == CERBERUS || stage == UNLEASHED
            is MinotaurEntity -> stage >= MINOTAUR
            else -> false
        }
        if (!mine) return 0f
        val attacker = source.attacker
        val world = boss.entityWorld as? ServerWorld ?: return 0f
        if (attacker is ServerPlayerEntity && attacker.uuid !in knockedOut && participants.add(attacker.uuid)) join(world, attacker)
        if (boss is CerberusEntity) return cerberusHit(world, boss, source, amount)
        val floor = floorFor(boss)
        // /kill (generic_kill) skips to the end of the stage - handy for testing
        if (source.isOf(DamageTypes.GENERIC_KILL)) {
            transitionQueued = true
            return max(0f, boss.health - floor)
        }
        var a = amount / healthScale * boss.toughness()
        // stage 4: a melee hit out of the blood is a backstab (x3, he reels); any attack brings you out of it
        if (boss is MinotaurEntity && stage in LABYRINTH..LAST_BULL && attacker is PlayerEntity && PitLabyrinth.isHidden(attacker)) {
            PitLabyrinth.reveal(world, attacker)
            if (source.source === attacker) {
                a *= PitLabyrinth.BACKSTAB
                boss.backstabBy = attacker
            }
        }
        if (boss is MinotaurEntity && boss.isStunned()) a *= 1.5f
        a = min(a, HhaConfig.num("hha_blood.boss_max_hit").toFloat())
        val allowed = min(a, boss.health - floor)
        if (allowed <= 0f) return 0f
        if (boss.health - allowed <= floor + 0.01f) transitionQueued = true
        return allowed
    }

    /**
     * The Cerberus is fought head by head: every hit goes to the head it lands on (aim at the heads - body hits
     * only count [BODY_FACTOR]). A head at 0 breaks: it hangs limp and its attack is gone. Stage 1 ends with two
     * broken heads, stage 2 (they re-ignite) with all three. Returns a token amount for vanilla's hurt flash,
     * -1 while the hound is still in its hurt frames.
     */
    private fun cerberusHit(world: ServerWorld, c: CerberusEntity, source: DamageSource, amount: Float): Float {
        if (source.isOf(DamageTypes.GENERIC_KILL)) {
            breakUntilStageEnds(world, c)
            return -1f
        }
        if (c.timeUntilRegen > 10) return -1f
        val (head, clean) = c.headFor(source)
        if (head < 0) return -1f
        var a = min(amount / healthScale * c.toughness(), HhaConfig.num("hha_blood.boss_max_hit").toFloat())
        if (!clean) a *= BODY_FACTOR
        c.headHp[head] -= a
        val hp = c.headPos(head)
        if (clean) {
            world.spawnParticles(net.minecraft.particle.ParticleTypes.CRIT, hp.x, hp.y, hp.z, 10, 0.3, 0.3, 0.3, 0.25)
            world.spawnParticles(dev.henny.hha_blood.BloodParticles.SPLAT, hp.x, hp.y, hp.z, 3, 0.25, 0.25, 0.25, 0.1)
        } else if (!bodyHintShown && source.attacker is ServerPlayerEntity) {
            bodyHintShown = true
            for (p in players(world)) hint(p, "boss.hha_blood.hint.heads")
        }
        if (c.headHp[head] <= 0f) breakHead(world, c, head)
        c.syncHealth()
        return 0.5f
    }

    private fun breakHead(world: ServerWorld, c: CerberusEntity, head: Int) {
        if (c.isHeadBroken(head)) return
        c.headHp[head] = 0f
        c.brokenMask = c.brokenMask or (1 shl head)
        val at = c.headPos(head)
        world.playSound(null, at.x, at.y, at.z, BossSounds.CERBERUS_WHIMPER, SoundCategory.HOSTILE, 3f, 0.9f + head * 0.1f)
        world.playSound(null, at.x, at.y, at.z, BossSounds.IMPACT_HEAVY, SoundCategory.HOSTILE, 2f, 1.3f)
        world.spawnParticles(dev.henny.hha_blood.BloodParticles.GLOB, at.x, at.y, at.z, 40, 0.5, 0.5, 0.5, 0.25)
        world.spawnParticles(net.minecraft.particle.ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 12, 0.4, 0.4, 0.4, 0.02)
        BloodFx.shake(world, at, 24.0, 1.0f, 10)
        for (p in players(world)) hint(p, "boss.hha_blood.hint.head_broken.$head")
        HhaBlood.LOGGER.info("Slaughter Pit: Cerberus head {} broken (stage {})", head, stage)
        c.syncHealth()
        if (c.brokenCount() >= (if (stage == CERBERUS) 2 else 3)) transitionQueued = true
        else c.stagger()
    }

    /** /kill and the debug skip: break heads until this stage is over. */
    private fun breakUntilStageEnds(world: ServerWorld, c: CerberusEntity) {
        val need = if (stage == CERBERUS) 2 else 3
        for (i in 0..2) if (c.brokenCount() < need && !c.isHeadBroken(i)) breakHead(world, c, i)
        transitionQueued = true
    }

    private fun syncHeadBars(world: ServerWorld) {
        val c = if ((stage == CERBERUS || stage == UNLEASHED) && scene == null && active) cerberus(world) else null
        for ((i, hb) in headBars.withIndex()) {
            hb.isVisible = c != null && !c.isHeadBroken(i)
            if (c != null) hb.percent = (c.headHp[i] / c.headMax).coerceIn(0f, 1f)
            if (ticks % 10 == 0) {
                for (p in players(world)) if (p !in hb.players && p.networkHandler != null) hb.addPlayer(p)
                for (p in hb.players.toList()) if (p.uuid !in participants) hb.removePlayer(p)
            }
        }
    }

    private fun updateScaling(world: ServerWorld) {
        val n = max(1, players(world).count { !it.isSpectator })
        healthScale = 1f + (n - 1) * HhaConfig.num("hha_blood.boss_health_per_player").toFloat()
        damageScale = 1f + (n - 1) * HhaConfig.num("hha_blood.boss_damage_per_player").toFloat()
    }

    // ------------------------------------------------------------------------------------- knock-out

    private fun hasTotem(p: ServerPlayerEntity): Boolean =
        net.minecraft.util.Hand.entries.any { p.getStackInHand(it).contains(net.minecraft.component.DataComponentTypes.DEATH_PROTECTION) }

    /** Died in the pit (no totem): out of the fight; the others fight on. */
    private fun onPlayerDied(player: ServerPlayerEntity) {
        HhaBlood.LOGGER.info("Slaughter Pit: {} died", player.name.string)
        participants.remove(player.uuid)
        outside.remove(player.uuid)
        paralyzed.remove(player.uuid)
        removeFromBars(player)
        Cine.reset(listOf(player))
        Cine.music(listOf(player), null)
    }

    /** Optional (config `boss_safe_knockout`): knocked out instead of dying - back to the entrance tunnel, nothing lost. */
    private fun knockOut(player: ServerPlayerEntity, cause: DamageSource): Boolean {
        val world = player.entityWorld as? ServerWorld ?: return false
        HhaBlood.LOGGER.info("Slaughter Pit: {} is knocked out ({})", player.name.string, cause.name)
        player.health = max(4f, player.maxHealth * 0.3f)
        player.extinguish()
        Bleed.clear(player.uuid)
        for (effect in player.statusEffects.map { it.effectType }) player.removeStatusEffect(effect)
        participants.remove(player.uuid)
        knockedOut += player.uuid
        paralyzed.remove(player.uuid)
        removeFromBars(player)
        Cine.reset(listOf(player))
        sendToTunnel(world, player)
        player.addStatusEffect(StatusEffectInstance(StatusEffects.REGENERATION, 200, 1))
        player.addStatusEffect(StatusEffectInstance(StatusEffects.RESISTANCE, 100, 4))
        player.sendMessage(Text.translatable("boss.hha_blood.knocked_out").formatted(Formatting.DARK_RED, Formatting.ITALIC), false)
        return true
    }

    private fun sendToTunnel(world: ServerWorld, player: ServerPlayerEntity) {
        val p = PitArena.at(0.5, 0.0, HeartVaultPiece.R + 14.5)
        player.teleport(world, p.x, p.y, p.z, emptySet(), 0f, 0f, false)
        player.velocity = Vec3d.ZERO
        player.velocityDirty = true
        player.fallDistance = 0.0
    }

    private fun onLeave(player: ServerPlayerEntity) {
        if (participants.remove(player.uuid)) {
            removeFromBars(player)
            // logs back in at the entrance, not in the middle of a fight
            val world = nether(player.entityWorld.server!!) ?: return
            if (player.entityWorld == world && PitArena.inArena(player.entityPos)) {
                val p = PitArena.at(0.5, 0.0, HeartVaultPiece.R + 14.5)
                player.refreshPositionAndAngles(p.x, p.y, p.z, 0f, 0f)
            }
        }
        paralyzed.remove(player.uuid)
    }

    private fun join(world: ServerWorld, player: ServerPlayerEntity) {
        participants += player.uuid
        outside.remove(player.uuid)
        bar.addPlayer(player)
        Cine.music(listOf(player), currentMusic())
        updateScaling(world)
    }

    // ------------------------------------------------------------------------------------- tick

    private fun tick(server: MinecraftServer) {
        val world = nether(server) ?: return
        if (PitArena.center() == null) return
        PitArena.tickUpgrade(world)
        tickParalysis(world)
        if (!active) {
            if (server.ticks % 20 != 0) return
            idleTick(world)
            return
        }
        try {
            fightTick(world)
        } catch (t: Throwable) {
            HhaBlood.LOGGER.error("Slaughter Pit: fight tick failed - resetting the fight", t)
            abort(world, false)
        }
    }

    private fun nearPlayers(world: ServerWorld, range: Double): List<PlayerEntity> {
        val m = PitArena.mid()
        return (world.players + BossDebug.extraTargets).filter { it.isAlive && it.squaredDistanceTo(m) < range * range }
    }

    private fun idleTick(world: ServerWorld) {
        val near = nearPlayers(world, 120.0)
        if (near.isEmpty()) return
        PitArena.checkUpgrade(world)
        if (!PitArena.settled()) return
        val m = PitArena.mid()
        // throne, kennel and middle must be loaded
        for (p in listOf(m, PitArena.at(0.5, 12.0, -39.0), PitArena.at(-55.0, 0.0, 0.5))) {
            if (!PitArena.ready(world, kotlin.math.floor(p.x).toInt() shr 4, kotlin.math.floor(p.z).toInt() shr 4)) return
        }
        val st = PitArena.state(world.server!!)
        if (st.changed.isNotEmpty()) { PitArena.restore(world); PitArena.closeGate(world) }
        if (st.defeated) {
            // after the victory the pit is empty until someone brings a Blood Offering
            world.getEntitiesByClass(PitBoss::class.java, arenaBox()) { it.isAlive }.forEach { it.discard() }
            return
        }
        ensureIdleBosses(world)
        knockedOut.clear()
        val inside = near.filterIsInstance<ServerPlayerEntity>().filter { !it.isSpectator && PitArena.inDome(it.entityPos) }
        if (inside.isNotEmpty()) begin(world, inside)
    }

    private fun begin(world: ServerWorld, inside: List<ServerPlayerEntity>) {
        val m = minotaur(world) ?: return
        val c = cerberus(world) ?: return
        active = true
        stage = IDLE
        ticks = 0
        abandonTicks = 0
        transitionQueued = false
        participants.clear()
        outside.clear()
        inside.forEach { participants += it.uuid }
        bodyHintShown = false
        updateScaling(world)
        bar.clearPlayers()
        val st = PitArena.state(world.server!!)
        HhaBlood.LOGGER.info("Slaughter Pit: fight starts ({} players, {} earlier victories)", inside.size, st.kills)
        scenes = PitScenes(world, m, c)
        runScene(scenes!!.intro(st.kills > 0))
    }

    private fun fightTick(world: ServerWorld) {
        ticks++
        val s = scene
        if (s != null) {
            if (s.steps.hasNext()) s.steps.next() else finishScene(world, false)
        }
        PitHazards.tick(world)
        PitLabyrinth.tick(world)
        if (ticks % 10 == 0) updateParticipants(world)
        if (ticks % 20 == 0) updateScaling(world)
        if (scene == null) {
            leash(world)
            if (transitionQueued) {
                transitionQueued = false
                startTransition(world)
            }
            if (stage == CERBERUS || stage == UNLEASHED) watchFromBalcony(world)
        }
        syncBar(world)
        if (!active) return
        if (players(world).none { PitArena.inArena(it.entityPos) }) {
            abandonTicks++
            if (abandonTicks >= 200) abort(world, true)
        } else abandonTicks = 0
    }

    private fun updateParticipants(world: ServerWorld) {
        // newcomers join, knocked-out players are sent back out
        for (p in nearPlayers(world, 60.0).filterIsInstance<ServerPlayerEntity>()) {
            if (p.isSpectator || !PitArena.inArena(p.entityPos)) continue
            if (p.uuid in knockedOut) {
                sendToTunnel(world, p)
                p.sendMessage(Text.translatable("boss.hha_blood.still_out").formatted(Formatting.DARK_RED), true)
                continue
            }
            if (p.uuid !in participants) {
                join(world, p)
                if (scene != null) Cine.start(listOf(p), false)
            }
        }
        // whoever stays outside the arena for 10 s leaves the fight
        for (p in players(world)) {
            if (PitArena.inArena(p.entityPos)) { outside.remove(p.uuid); continue }
            val t = (outside[p.uuid] ?: 0) + 10
            outside[p.uuid] = t
            if (t >= 200) {
                participants.remove(p.uuid)
                outside.remove(p.uuid)
                removeFromBars(p)
                Cine.reset(listOf(p))
            }
        }
    }

    private fun leash(world: ServerWorld) {
        for (b in listOfNotNull(minotaur(world), cerberus(world))) {
            if (b.scripted) continue
            if (!PitArena.inArena(b.entityPos) || b.y < PitArena.mid().y - 3) {
                val p = PitArena.at(0.5, 0.0, 0.5)
                b.refreshPositionAndAngles(p.x, p.y, p.z, b.yaw, 0f)
                b.velocity = Vec3d.ZERO
            }
        }
    }

    /** Stages 1/2: the Minotaur stands at his balcony and watches (and mocks). */
    private fun watchFromBalcony(world: ServerWorld) {
        val m = minotaur(world) ?: return
        val c = cerberus(world)
        val look = targets(world).firstOrNull()?.entityPos ?: c?.entityPos ?: return
        m.face(look, 6f)
        // now and then he hurls his axe down from the balcony to help his hound
        if (m.action == MinotaurEntity.THROW) {
            if (m.actionAge == 16) {
                val t = targets(world).minByOrNull { it.squaredDistanceTo(m.entityPos) }
                if (t != null) {
                    m.axeOut = true
                    m.sound(BossSounds.AXE_SWING, 3f, 0.6f)
                    val hand = m.entityPos.add(m.forward().multiply(1.4)).add(0.0, 2.8, 0.0)
                    PitHazards.throwAxe(m, hand, t.entityPos.add(t.velocity.multiply(8.0)).add(0.0, 1.0, 0.0), 22f)
                } else m.act(MinotaurEntity.IDLE)
            }
            if (m.actionAge > 150) { m.axeOut = false; m.act(MinotaurEntity.IDLE) }
            return
        }
        if (m.action == MinotaurEntity.CATCH) {
            if (m.actionAge > 10) m.act(MinotaurEntity.IDLE)
            return
        }
        if (--throwIn <= 0) {
            throwIn = 380 + world.random.nextInt(220)
            val hand = m.entityPos.add(0.0, 2.8, 0.0)
            if (targets(world).any { lineOfSight(world, hand, it.eyePos) && it.squaredDistanceTo(m.entityPos) < 45.0 * 45.0 }) {
                m.act(MinotaurEntity.THROW)
                m.sound(BossSounds.MINOTAUR_GRUNT, 3f, 0.8f)
                return
            }
        }
        if (--tauntIn <= 0) {
            tauntIn = 520 + world.random.nextInt(300)
            val key = "taunt.${1 + world.random.nextInt(5)}"
            Cine.dialog(players(world), Cine.SPEAKER_MINOTAUR, PitLines.key(key), PitLines.ticks(key))
            m.act(MinotaurEntity.LAUGH)
        } else if (m.action == MinotaurEntity.LAUGH && m.actionAge > 40) m.act(MinotaurEntity.IDLE)
    }

    private fun syncBar(world: ServerWorld) {
        syncHeadBars(world)
        val boss: PitBoss? = when (stage) {
            CERBERUS, UNLEASHED -> cerberus(world)
            MINOTAUR, LABYRINTH, LAST_BULL -> minotaur(world)
            else -> null
        }
        bar.isVisible = boss != null && scene == null
        if (boss == null) return
        bar.percent = (boss.health / boss.maxHealth).coerceIn(0f, 1f)
        val name: Text = when {
            boss is CerberusEntity && stage == UNLEASHED -> Text.translatable("boss.hha_blood.bar.unleashed")
            boss is CerberusEntity -> Text.translatable("entity.hha_blood.blood_cerberus")
            boss is MinotaurEntity && boss.fury > 0 -> Text.translatable("boss.hha_blood.bar.fury", Text.translatable("entity.hha_blood.crimson_minotaur"), boss.fury)
            else -> Text.translatable("entity.hha_blood.crimson_minotaur")
        }
        if (ticks % 10 == 0) {
            bar.name = name.copy().formatted(Formatting.DARK_RED)
            for (p in players(world)) if (p !in bar.players && p.networkHandler != null) bar.addPlayer(p)
            for (p in bar.players.toList()) if (p.uuid !in participants) bar.removePlayer(p)
        }
    }

    // ------------------------------------------------------------------------------------- scenes

    fun runScene(run: SceneRun, keepWalls: Boolean = false) {
        val world = scenes?.world ?: return
        scene = run
        if (run.name != "last_bull") PitLabyrinth.stop(world)
        if (keepWalls) PitHazards.clearKeepWalls() else PitHazards.clear(world)
        paralyzed.clear()
        for (b in listOfNotNull(minotaur(world), cerberus(world))) { b.scripted = true; b.navigation.stop() }
        // a thrown axe is gone with the hazards - he has it back in his hands
        minotaur(world)?.let { it.axeOut = it.berserk }
        Cine.start(players(world), run.skippable)
        HhaBlood.LOGGER.info("Slaughter Pit: cutscene {}", run.name)
    }

    private fun finishScene(world: ServerWorld, skipped: Boolean) {
        val s = scene ?: return
        scene = null
        if (skipped) Cine.fade(players(world), 0x000000, 1.0, 0.0, 16)
        s.finish()
        if (active) Cine.end(players(world))
    }

    private fun voteSkip(player: ServerPlayerEntity) {
        val s = scene ?: return
        if (!s.skippable || player.uuid !in participants) return
        s.votes += player.uuid
        val world = player.entityWorld as? ServerWorld ?: return
        val needed = players(world).count { it.networkHandler != null && it !in BossDebug.extraTargets }
        Cine.skipState(players(world), s.votes.size, needed)
        if (s.votes.size >= needed) {
            HhaBlood.LOGGER.info("Slaughter Pit: cutscene {} skipped", s.name)
            finishScene(world, true)
        }
    }

    fun sceneRunning(): Boolean = scene != null

    private fun startTransition(world: ServerWorld) {
        val sc = scenes ?: return
        when (stage) {
            CERBERUS -> runScene(sc.cerberusRises())
            UNLEASHED -> runScene(sc.cerberusFalls())
            MINOTAUR -> runScene(sc.labyrinth())
            LABYRINTH -> runScene(sc.lastBull(), keepWalls = true)
            LAST_BULL -> runScene(sc.finale())
        }
    }

    /** Called by the scenes' end states. */
    fun beginStage(world: ServerWorld, s: Int) {
        stage = s
        // the Cerberus is fought head by head: whole again at the start of each of its stages
        if (s == CERBERUS) cerberus(world)?.resetHeads(110f)
        if (s == UNLEASHED) cerberus(world)?.resetHeads(125f)
        transitionQueued = false
        tauntIn = 300
        if (s == LABYRINTH || s == LAST_BULL) PitLabyrinth.start(world) else PitLabyrinth.stop(world)
        throwIn = 260
        Cine.music(players(world), currentMusic())
        HhaBlood.LOGGER.info("Slaughter Pit: stage {}", s)
    }

    fun currentMusic(): SoundEvent? = when {
        !active -> null
        scene != null -> BossSounds.MUSIC_TENSION
        stage == CERBERUS -> BossSounds.MUSIC_CERBERUS
        stage == UNLEASHED -> BossSounds.MUSIC_CERBERUS_UNLEASHED
        stage == MINOTAUR || stage == LABYRINTH -> BossSounds.MUSIC_MINOTAUR
        stage == LAST_BULL -> BossSounds.MUSIC_MINOTAUR_FINAL
        else -> BossSounds.MUSIC_TENSION
    }

    /** Places the players for a cutscene on a half circle around [focus], facing it. */
    fun gather(world: ServerWorld, around: Vec3d, radius: Double, centerAngleDeg: Double) {
        val list = players(world)
        for ((i, p) in list.withIndex()) {
            val spread = if (list.size <= 1) 0.0 else (i.toDouble() / (list.size - 1) - 0.5) * 70.0
            val a = Math.toRadians(centerAngleDeg + spread)
            var pos = Vec3d(around.x + cos(a) * radius, PitArena.mid().y, around.z + sin(a) * radius)
            pos = PitArena.clampToFloor(pos, 3.0)
            val c = PitArena.center()
            if (c != null && HeartVaultPiece.pillarAt(pos.x - c.x, pos.z - c.z, 1.2) >= 0) pos = pos.add((around.x - pos.x) * 0.2, 0.0, (around.z - pos.z) * 0.2)
            val yaw = (Math.toDegrees(atan2(around.z - pos.z, around.x - pos.x)) - 90.0).toFloat()
            p.teleport(world, pos.x, pos.y, pos.z, emptySet(), yaw, 0f, false)
            p.velocity = Vec3d.ZERO
            p.velocityDirty = true
            p.fallDistance = 0.0
            p.extinguish()
        }
    }

    // ------------------------------------------------------------------------------------- end

    /** Everybody is out (or an error): the pit resets, both bosses go back to rest. */
    fun abort(world: ServerWorld, announce: Boolean) {
        HhaBlood.LOGGER.info("Slaughter Pit: fight reset (stage {})", stage)
        val viewers = (players(world) + nearPlayers(world, 140.0).filterIsInstance<ServerPlayerEntity>()).distinct()
        if (announce) Cine.dialog(viewers, Cine.SPEAKER_MINOTAUR, PitLines.key("reset"), PitLines.ticks("reset"))
        Cine.music(viewers, null)
        Cine.end(viewers)
        cleanup(world)
        // the bosses are put back to rest by the idle tick
        minotaur(world)?.let { restMinotaur(it) }
        cerberus(world)?.let { restCerberus(it) }
    }

    private fun cleanup(world: ServerWorld) {
        scene = null
        PitLabyrinth.stop(world)
        scenes?.cleanup()
        scenes = null
        PitHazards.clear(world)
        PitArena.restore(world)
        PitArena.closeGate(world)
        bar.clearPlayers()
        bar.isVisible = false
        headBars.forEach { it.clearPlayers(); it.isVisible = false }
        active = false
        stage = IDLE
        transitionQueued = false
        participants.clear()
        outside.clear()
        paralyzed.clear()
    }

    /** The Minotaur is beaten (end of the finale): rewards, then the pit falls silent. */
    fun victory(world: ServerWorld) {
        val st = PitArena.state(world.server!!)
        st.defeated = true
        st.kills++
        for (p in players(world)) {
            val n = st.playerKills.getOrDefault(p.uuid, 0)
            st.playerKills[p.uuid] = n + 1
            val loot = ArrayList<ItemStack>()
            if (n == 0) {
                // first victory: a full Blood armour's worth + the three heads of the Cerberus
                loot += ItemStack(BloodItems.BLOOD_INGOT, 6)
                loot += ItemStack(Items.NETHERITE_INGOT, 2)
                loot += ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 2)
                loot += ItemStack(HollowsBlocks.BLOOD_CRYSTAL_SHARD, 12)
                loot += ItemStack(BossContent.HEAD_BLEED)
                loot += ItemStack(BossContent.HEAD_FLAME)
                loot += ItemStack(BossContent.HEAD_HOWL)
                p.sendMessage(Text.translatable("boss.hha_blood.reward.first").formatted(Formatting.GOLD), false)
            } else {
                // every later victory: much less (Mats)
                if (world.random.nextFloat() < 0.5f) loot += ItemStack(BloodItems.BLOOD_INGOT, 1)
                loot += ItemStack(HollowsBlocks.BLOOD_CRYSTAL_SHARD, 3 + world.random.nextInt(4))
                p.sendMessage(Text.translatable("boss.hha_blood.reward.again").formatted(Formatting.GRAY), false)
            }
            for (stack in loot) {
                if (!p.giveItemStack(stack.copy())) p.dropItem(stack, false)
            }
            world.playSound(null, p.x, p.y, p.z, net.minecraft.sound.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1f, 0.8f)
        }
        st.markDirty()
        HhaBlood.LOGGER.info("Slaughter Pit: victory #{} ({} players)", st.kills, participants.size)
        Cine.end(players(world))
        cleanup(world)
    }

    private fun shutdown(server: MinecraftServer) {
        val world = nether(server)
        if (world != null && active) {
            // put the arena back before the world is saved
            cleanup(world)
            minotaur(world)?.let { restMinotaur(it) }
            cerberus(world)?.let { restCerberus(it) }
        }
        active = false
        scene = null
        participants.clear()
        knockedOut.clear()
        paralyzed.clear()
        minotaurId = null
        cerberusId = null
        PitArena.resetSession()
    }

    // ------------------------------------------------------------------------------------- altar

    private fun useAltar(world: ServerWorld, player: PlayerEntity, stack: ItemStack): ActionResult {
        val st = PitArena.state(world.server!!)
        if (active) {
            player.sendMessage(Text.translatable("boss.hha_blood.altar.awake").formatted(Formatting.DARK_RED), true)
            return ActionResult.SUCCESS
        }
        if (!st.defeated) {
            player.sendMessage(Text.translatable("boss.hha_blood.altar.waiting").formatted(Formatting.DARK_RED), true)
            return ActionResult.SUCCESS
        }
        if (!stack.isOf(BossContent.BLOOD_OFFERING)) {
            player.sendMessage(Text.translatable("boss.hha_blood.altar.need").formatted(Formatting.RED), true)
            return ActionResult.SUCCESS
        }
        if (!player.isCreative) stack.decrement(1)
        st.defeated = false
        st.markDirty()
        val altar = PitArena.at(HeartVaultPiece.ALTAR.x + 0.5, 1.0, HeartVaultPiece.ALTAR.z + 0.5)
        BloodFx.spawn(world, BloodFx.flame, altar, 50, 0.4, 0.9, 0.4, 0.05)
        BloodFx.sound(world, altar, net.minecraft.sound.SoundEvents.BLOCK_END_PORTAL_SPAWN, 1.2f, 0.6f)
        BloodFx.sound(world, altar, BossSounds.HEARTBEAT, 2f, 0.8f)
        // the idle tick spawns both again and the player is standing in the dome -> the fight starts
        return ActionResult.SUCCESS
    }

    // ------------------------------------------------------------------------------------- debug

    fun debugBreakHead(world: ServerWorld): String {
        if (!cerberusFighting()) return "no Cerberus stage"
        val c = cerberus(world) ?: return "no Cerberus"
        val h = c.aliveHeads().firstOrNull() ?: return "all heads broken"
        breakHead(world, c, h)
        return "head $h broken"
    }

    fun debugSkip(world: ServerWorld): String {
        if (!active) return "no fight"
        if (scene != null) { finishScene(world, true); return "scene skipped" }
        if (stage <= UNLEASHED) cerberus(world)?.let { breakUntilStageEnds(world, it) }
        else minotaur(world)?.let { it.health = floorFor(it) + 0.5f }
        transitionQueued = true
        return "stage $stage ends"
    }

    fun debugInfo(world: ServerWorld): String =
        "active=$active stage=$stage scene=${scene?.name} players=${participants.size} ko=${knockedOut.size} " +
            "minotaur=${minotaur(world)?.let { "%.0f/%.0f a${it.action} fury${it.fury}".format(it.health, it.maxHealth) }} " +
            "cerberus=${cerberus(world)?.let { "%.0f/%.0f a${it.action}".format(it.health, it.maxHealth) }}"

    fun debugReset(world: ServerWorld) {
        if (active) abort(world, false)
        val st = PitArena.state(world.server!!)
        st.defeated = false
        st.markDirty()
    }

    fun forgetBosses() { minotaurId = null; cerberusId = null }

    fun isFake(p: PlayerEntity): Boolean = p in BossDebug.extraTargets

    @Suppress("unused")
    private fun isBossDamage(source: DamageSource): Boolean = source.isIn(DamageTypeTags.IS_FIRE)
}
