package dev.henny.hha_blood.logic

import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.Cooldowns
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Targeting
import dev.henny.hha_blood.BloodItems
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Blood Chestplate set bonus "Nihil" and Blood Leggings "Blood Trail".
 *
 * Nihil: every enemy in range is stunned for ~3s while veins of blood are drained out of them
 * (lifesteal for you), then a Blood Nova rolls outwards and makes everybody bleed.
 * Blood Trail: dive into a pool of blood - short invisibility, enemies near you are slowed to a crawl.
 */
object BloodAbilities {

    private class NihilSession(
        val owner: UUID,
        val world: ServerWorld,
        val victims: List<UUID>,
        val total: Int,
        val range: Double,
        val drainPerTick: Float,
        val healRatio: Float,
    ) {
        var ticksLeft = total
        var novaTick = -1
        /** after the 3rd beat the victims hang still in the air, impaled on a spike */
        var pinned = false
        val noGravitySet = HashSet<UUID>()
        /** x/z where every victim was caught - they may only float straight up from there */
        val anchors = HashMap<UUID, Vec3d>()
    }

    private class PoolSession(
        val owner: UUID,
        val world: ServerWorld,
        val center: Vec3d,
        val radius: Double,
        val total: Int,
        val slowTicks: Int,
    ) {
        var ticksLeft = total
    }

    private val nihil = ArrayList<NihilSession>()
    private const val IMPALE_RUMBLE = 14
    /** rumble + thrust + settle of the impaling spike (see NihilFx) */
    private val IMPALE_RISE = NihilFx.riseTicks(IMPALE_RUMBLE)

    /** The three "Nihil!" beats: early, middle, and the impaling one a bit before the end of the drain. */
    private fun beatTick(total: Int, beat: Int): Int {
        // the 3rd beat starts the impaling thorn - early enough that its three stabs end ~10 ticks before the nova
        val third = (total - IMPALE_RISE - 10).coerceAtLeast(6)
        return when (beat) { 0 -> third / 4; 1 -> third * 2 / 3; else -> third }
    }

    /** Nihil drain time: the configured duration, stretched if the slow three-stab thorn needs more time. */
    private fun nihilTotal(duration: Int): Int = maxOf(duration, 30 + IMPALE_RISE + 10)
    private val pools = ArrayList<PoolSession>()
    private const val NOVA_TICKS = 14

    fun reset() {
        nihil.clear()
        pools.clear()
        NihilFx.reset()
        BloodDive.reset()
    }

    private fun enemies(world: ServerWorld, player: ServerPlayerEntity, center: Vec3d, range: Double): List<LivingEntity> =
        world.getEntitiesByClass(LivingEntity::class.java, Box.of(center, range * 2, range * 2, range * 2)) {
            it.isAlive && it !== player && Targeting.shouldHarm(player, it) && it.squaredDistanceTo(center) <= range * range
        }

    // ---------------------------------------------------------------- Nihil

    fun castNihil(player: ServerPlayerEntity) {
        if (!HhaConfig.enabled("hha_blood.nihil")) return
        val world = player.entityWorld as? ServerWorld ?: return
        val chest = player.getEquippedStack(EquipmentSlot.CHEST)
        if (!chest.isOf(BloodItems.BLOOD_CHESTPLATE) || player.itemCooldownManager.isCoolingDown(chest)) return

        val range = HhaConfig.num("hha_blood.nihil_range")
        val victims = enemies(world, player, player.entityPos, range)
        if (victims.isEmpty()) {
            BloodHud.show(player, "hha_blood.msg.no_targets", BloodHud.DARK_RED)
            return
        }

        Cooldowns.set(player, chest, "hha_blood.nihil_cooldown")
        val duration = HhaConfig.num("hha_blood.nihil_duration").toInt().coerceAtLeast(1)
        val total = nihilTotal(duration)
        val drain = HhaConfig.num("hha_blood.nihil_drain").toFloat() / total
        val healRatio = HhaConfig.num("hha_blood.nihil_lifesteal").toFloat()

        for (v in victims) {
            v.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, total + 5, 255, false, false, true))
            v.addStatusEffect(StatusEffectInstance(StatusEffects.MINING_FATIGUE, total + 5, 255, false, false, true))
            v.addStatusEffect(StatusEffectInstance(StatusEffects.BLINDNESS, total + 5, 0, false, false, true))
            // slowly rising into the air like after a shulker bullet (Levitation); no horizontal movement
            v.addStatusEffect(StatusEffectInstance(StatusEffects.LEVITATION, total + 14 + 5, liftLevel() - 1, false, true, true))
        }
        val session = NihilSession(player.uuid, world, victims.map { it.uuid }, total, range, drain, healRatio)
        for (v in victims) session.anchors[v.uuid] = v.entityPos
        nihil += session

        val pos = player.entityPos
        // 3D: a spinning saw ring traps every victim, spikes erupt around the caster
        NihilFx.startRings(world, victims)
        BloodFx.spawn(world, ParticleTypes.EXPLOSION_EMITTER, pos.add(0.0, 1.0, 0.0), 1)
        for (i in 0 until 40) {
            val a = i / 40.0 * PI * 2
            BloodFx.spawn(world, BloodFx.flame, pos.x + cos(a) * 0.8, pos.y + 0.15, pos.z + sin(a) * 0.8, 0, cos(a), 0.1, sin(a), 0.45)
        }
        BloodFx.ring(world, pos.add(0.0, 0.2, 0.0), 1.5, BloodFx.dust(BloodFx.BRIGHT, 1.4f), 28, 0.0, 0.05)
        BloodFx.ring(world, pos.add(0.0, 0.4, 0.0), 3.0, BloodFx.dust(BloodFx.DARK, 1.6f), 40, 0.0, 0.08)
        BloodFx.spawn(world, BloodFx.crumbs, pos.add(0.0, 1.2, 0.0), 40, 0.6, 0.5)
        BloodFx.spawn(world, BloodFx.splat, pos.add(0.0, 1.0, 0.0), 6, 0.6, 0.0)
        BloodFx.spawn(world, ParticleTypes.SONIC_BOOM, pos.add(0.0, 1.0, 0.0), 1)
        // Mohg-style "Nihil!" (always plays; the optional voice line comes on top):
        // a deep growl, a ritual chant chord, a resonating bell and blood flames igniting
        BloodFx.sound(world, pos, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, 0.9f, 0.5f)
        BloodFx.sound(world, pos, SoundEvents.ENTITY_WARDEN_ROAR, 0.7f, 0.55f)
        BloodFx.sound(world, pos, SoundEvents.ENTITY_EVOKER_PREPARE_SUMMON, 1.0f, 0.5f)
        BloodFx.sound(world, pos, SoundEvents.BLOCK_BELL_RESONATE, 1.6f, 0.5f)
        BloodFx.sound(world, pos, SoundEvents.ENTITY_BLAZE_SHOOT, 1.0f, 0.5f)
        BloodFx.sound(world, pos, SoundEvents.BLOCK_TRIAL_SPAWNER_OMINOUS_ACTIVATE, 0.8f, 0.6f)
        BloodFx.sound(world, pos, SoundEvents.AMBIENT_CRIMSON_FOREST_MOOD, 1.0f, 0.5f)
        if (HhaConfig.enabled("hha_blood.nihil_voice")) BloodFx.sound(world, pos, BloodSounds.NIHIL_VOICE, 2.0f, 1.0f)
    }

    /**
     * The three "Nihil!" beats during the drain (like Mohg's chant): every beat is a booming
     * shock wave, a pillar of blood flames on the caster and a jolt through every victim.
     */
    private fun nihilBeat(session: NihilSession, owner: ServerPlayerEntity, beat: Int) {
        val world = session.world
        val center = owner.entityPos
        // bass boom + bell toll + a burst of blood flames
        BloodFx.sound(world, center, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 1.0f, 0.45f + beat * 0.07f)
        BloodFx.sound(world, center, SoundEvents.BLOCK_BELL_USE, 0.9f, 0.5f + beat * 0.03f)
        BloodFx.sound(world, center, SoundEvents.ENTITY_BLAZE_SHOOT, 1.0f, 0.5f + beat * 0.05f)
        BloodFx.sound(world, center, SoundEvents.ITEM_FIRECHARGE_USE, 0.9f, 0.55f)
        BloodFx.sound(world, center, SoundEvents.ENTITY_EVOKER_CAST_SPELL, 0.9f, 0.45f + beat * 0.05f)
        BloodFx.sound(world, center, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 2.0f, 0.5f + beat * 0.15f)
        // pillar of blood flames
        for (layer in 0 until 3) {
            BloodFx.column(world, center, 3.2 + beat * 0.6, 0.45 - layer * 0.12, 18, BloodFx.dust(if (layer == 0) BloodFx.BRIGHT else BloodFx.DARK, 1.7f - layer * 0.3f))
        }
        BloodFx.spawn(world, BloodFx.splat, center.add(0.0, 1.2, 0.0), 4, 0.5, 0.0)
        // burst of blood flames: a flaming ring that rushes outwards + flames shooting up
        for (i in 0 until 32) {
            val a = i / 32.0 * PI * 2 + beat
            val r = 1.0 + beat * 0.4
            BloodFx.spawn(world, BloodFx.flame, center.x + cos(a) * r, center.y + 0.1, center.z + sin(a) * r, 0, cos(a), 0.25, sin(a), 0.22)
        }
        BloodFx.spawn(world, BloodFx.flame, center.add(0.0, 0.8, 0.0), 24, 0.35, 0.9, 0.35, 0.03)
        if (beat == 2) {
            // 3rd beat: the victims stop in mid-air and a spike slowly drives up into each of them;
            // it stays inside them until the Blood Nova is over, then they drop
            session.pinned = true
            val elapsed = session.total - session.ticksLeft
            val hold = (session.total - elapsed - IMPALE_RISE).coerceAtLeast(0) + NOVA_TICKS + 4
            for (id in session.victims) {
                val v = world.getEntity(id) as? LivingEntity ?: continue
                if (!v.isAlive) continue
                pin(session, v)
                NihilFx.impale(world, owner, v, IMPALE_RUMBLE, hold, HhaConfig.num("hha_blood.nihil_spike_damage").toFloat())
            }
            BloodFx.sound(world, center, SoundEvents.ENTITY_EVOKER_FANGS_ATTACK, 1.2f, 0.5f)
        }
        // shock ring
        BloodFx.ring(world, center.add(0.0, 0.2, 0.0), 1.5 + beat, BloodFx.dust(BloodFx.BRIGHT, 1.8f), 36, beat * 0.5, 0.18)
        BloodFx.ring(world, center.add(0.0, 0.4, 0.0), 1.0 + beat, BloodFx.fade(BloodFx.BRIGHT, BloodFx.BLACK, 1.5f), 24, -beat * 0.5, 0.12)
        for (id in session.victims) {
            val v = world.getEntity(id) as? LivingEntity ?: continue
            if (!v.isAlive) continue
            BloodFx.wound(world, v.entityPos.add(0.0, v.height * 0.6, 0.0), 2)
        }
    }

    /**
     * Holds a victim still in mid-air (impaled): Levitation off, no gravity for mobs; players get a
     * zero-velocity packet every tick (their movement is client side, so that is what keeps them up).
     */
    private fun pin(session: NihilSession, e: LivingEntity) {
        if (e.hasStatusEffect(StatusEffects.LEVITATION)) e.removeStatusEffect(StatusEffects.LEVITATION)
        if (e is ServerPlayerEntity) {
            Fx.launchPlayer(e, pull(session, e, 0.0))
        } else {
            snap(session, e)
            if (!e.hasNoGravity()) {
                e.setNoGravity(true)
                session.noGravitySet += e.uuid
            }
            e.velocity = Vec3d.ZERO
            e.velocityDirty = true
        }
    }

    /** Lets the impaled victims fall again. */
    private fun release(session: NihilSession) {
        for (id in session.noGravitySet) (session.world.getEntity(id) as? LivingEntity)?.setNoGravity(false)
        session.noGravitySet.clear()
        session.pinned = false
    }

    /**
     * While levitating the victims may only float straight up: horizontal motion is cancelled every tick
     * and any drift is pulled back to where they were caught. Players steer in the air on their own client
     * (Slowness does not affect air control), so they get a velocity packet every tick: back to the anchor
     * horizontally, the Levitation I rise speed vertically.
     */
    private fun freeze(session: NihilSession, e: LivingEntity) {
        if (e is ServerPlayerEntity) {
            Fx.launchPlayer(e, pull(session, e, 0.05 * liftLevel()))
            return
        }
        snap(session, e)
        e.velocity = Vec3d(0.0, e.velocity.y, 0.0)
        e.velocityDirty = true
    }

    /** Levitation level of the Nihil victims (3 = they float up about 5 blocks before the impaling beat). */
    private fun liftLevel(): Int = HhaConfig.num("hha_blood.nihil_lift_level").toInt().coerceIn(1, 10)

    /** Velocity that pulls a player back onto their anchor column, with vertical speed [vy]. */
    private fun pull(session: NihilSession, e: LivingEntity, vy: Double): Vec3d {
        val a = session.anchors[e.uuid] ?: return Vec3d(0.0, vy, 0.0)
        return Vec3d((a.x - e.x) * 0.5, vy, (a.z - e.z) * 0.5)
    }

    /** Mobs: put them back onto their anchor column if they drifted. */
    private fun snap(session: NihilSession, e: LivingEntity) {
        val a = session.anchors[e.uuid] ?: return
        if ((a.x - e.x) * (a.x - e.x) + (a.z - e.z) * (a.z - e.z) > 0.0025) e.setPosition(a.x, e.y, a.z)
    }

    private fun tickNihil(session: NihilSession) {
        val world = session.world
        val owner = world.getPlayerByUuid(session.owner) as? ServerPlayerEntity
        if (owner == null || !owner.isAlive) {
            NihilFx.burstRings(session.victims)
            release(session)
            nihil.remove(session)
            return
        }
        val center = owner.entityPos
        val chestPos = center.add(0.0, owner.height * 0.6, 0.0)

        // ---- Blood Nova (after the drain) -----------------------------------------
        if (session.novaTick >= 0) {
            novaFrame(session, owner)
            session.novaTick++
            if (session.pinned) for (id in session.victims) (world.getEntity(id) as? LivingEntity)?.let { if (it.isAlive) pin(session, it) }
            if (session.novaTick >= NOVA_TICKS) {
                release(session)
                nihil.remove(session)
            }
            return
        }

        val elapsed = session.total - session.ticksLeft
        val progress = elapsed.toDouble() / session.total
        val phase = (elapsed * 0.07) % 1.0
        for (beat in 0 until 3) {
            if (elapsed == beatTick(session.total, beat)) nihilBeat(session, owner, beat)
        }

        // ---- drain every enemy; i-frames are reset so the damage really lands ------------
        var drained = 0f
        for (id in session.victims) {
            val v = world.getEntity(id) as? LivingEntity ?: continue
            if (!v.isAlive) continue
            if (session.pinned) pin(session, v) else freeze(session, v)
            val body = v.entityPos.add(0.0, v.height * 0.55, 0.0)
            if (elapsed % 3 == 0) BloodFx.spawn(world, BloodFx.flame, v.entityPos.add(0.0, 0.1, 0.0), 2, v.width * 0.4, 0.05, v.width * 0.4, 0.01)
            BloodFx.vein(world, body, chestPos, phase, 7, 0.9 + progress * 0.4)
            if (elapsed % 2 == 0) {
                BloodFx.spawn(world, BloodFx.dust(BloodFx.BRIGHT, 1.2f), body, 3, 0.25, 0.0)
                BloodFx.spawn(world, BloodFx.dropsFalling, body, 1, 0.2)
            }
            if (elapsed % 5 == 0) {
                val dmg = session.drainPerTick * 5
                v.timeUntilRegen = 0
                v.damage(world, world.damageSources.playerAttack(owner), dmg)
                drained += dmg
                BloodFx.spawn(world, BloodFx.crumbs, body, 4, 0.2, 0.06)
            }
        }
        if (drained > 0f) {
            owner.heal(drained * session.healRatio)
            BloodFx.spawn(world, BloodFx.dust(BloodFx.BRIGHT, 1.0f), chestPos, 4, 0.3, 0.0)
        }

        // ---- rotating, shrinking ring of blood + rising spiral around the caster ---------
        val ringRadius = session.range * (1.0 - progress * 0.75)
        BloodFx.ring(world, center.add(0.0, 0.15, 0.0), ringRadius, BloodFx.dust(BloodFx.DARK, 1.5f), 36, elapsed * 0.12)
        // blood flames licking up around the caster and along the ring
        BloodFx.spawn(world, BloodFx.flame, center.add(0.0, 0.2, 0.0), 3, 0.7, 0.1, 0.7, 0.01)
        if (elapsed % 2 == 0) BloodFx.ring(world, center.add(0.0, 0.1, 0.0), ringRadius, BloodFx.flame, 10, -elapsed * 0.1)
        if (elapsed % 2 == 0) BloodFx.ring(world, center.add(0.0, 0.2, 0.0), ringRadius, BloodFx.dust(BloodFx.BRIGHT, 1.0f), 12, -elapsed * 0.2)
        for (arm in 0 until 3) {
            val a = elapsed * 0.35 + arm * 2.0 * PI / 3.0
            val h = ((elapsed * 0.09) + arm / 3.0) % 1.0 * 2.6
            BloodFx.spawn(world, BloodFx.dust(BloodFx.BRIGHT, 1.3f), center.x + cos(a) * 0.9, center.y + h, center.z + sin(a) * 0.9)
        }
        // blood vortex: three arms of blood flames spiralling up around the caster, tightening over time
        for (arm in 0 until 3) {
            val a = -elapsed * 0.45 + arm * 2.0 * PI / 3.0
            val h = ((elapsed * 0.11) + arm / 3.0) % 1.0
            val rad = 1.4 - h * 0.9
            BloodFx.spawn(world, BloodFx.flame, center.x + cos(a) * rad, center.y + h * 3.6, center.z + sin(a) * rad)
        }
        // blood rain over the whole area + a red haze of crimson spores
        if (elapsed % 2 == 0) {
            for (k in 0 until 4) {
                val a = world.random.nextDouble() * PI * 2
                val d = Math.sqrt(world.random.nextDouble()) * session.range * 0.7
                BloodFx.spawn(world, BloodFx.dropsFalling, center.x + cos(a) * d, center.y + 5.0 + world.random.nextDouble() * 2.0, center.z + sin(a) * d)
            }
            BloodFx.spawn(world, ParticleTypes.CRIMSON_SPORE, center.add(0.0, 1.5, 0.0), 8, session.range * 0.4, 1.2, session.range * 0.4, 0.0)
        }
        if (elapsed % 10 == 5) {
            BloodFx.sound(world, center, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.4f, (0.6 + progress * 0.9).toFloat())
        }
        // crackling blood flames the whole time
        if (elapsed % 6 == 0) BloodFx.sound(world, center, SoundEvents.BLOCK_FIRE_AMBIENT, 1.2f, 0.6f)
        if (elapsed % 14 == 7) BloodFx.sound(world, center, SoundEvents.ENTITY_BLAZE_BURN, 0.6f, 0.5f)

        session.ticksLeft--
        if (session.ticksLeft <= 0) {
            startNova(session, owner)
        }
    }

    private fun startNova(session: NihilSession, owner: ServerPlayerEntity) {
        val world = session.world
        val center = owner.entityPos
        val damage = HhaConfig.num("hha_blood.nihil_nova_damage").toFloat()
        val duration = HhaConfig.num("hha_blood.bleed_duration").toInt() + 40
        val dps = HhaConfig.num("hha_blood.bleed_dps").toFloat() * 1.25f
        for (e in enemies(world, owner, center, session.range)) {
            e.timeUntilRegen = 0
            e.damage(world, world.damageSources.playerAttack(owner), damage)
            Bleed.apply(e, duration, dps, owner)
            BloodFx.column(world, e.entityPos, 3.2, 0.35, 16, BloodFx.dust(BloodFx.BRIGHT, 1.6f))
            BloodFx.wound(world, e.entityPos.add(0.0, e.height * 0.6, 0.0), 3)
        }
        session.novaTick = 0
        NihilFx.burstRings(session.victims)
        BloodFx.spawn(world, ParticleTypes.EXPLOSION_EMITTER, center.add(0.0, 1.0, 0.0), 1)
        BloodFx.spawn(world, ParticleTypes.SONIC_BOOM, center.add(0.0, 1.0, 0.0), 1)
        BloodFx.spawn(world, BloodFx.crumbs, center.add(0.0, 1.0, 0.0), 70, 0.5, 0.7)
        BloodFx.spawn(world, BloodFx.splat, center.add(0.0, 1.0, 0.0), 10, 1.2, 0.0)
        BloodFx.sound(world, center, SoundEvents.ENTITY_GENERIC_EXPLODE, 1.0f, 0.7f)
        BloodFx.sound(world, center, SoundEvents.ENTITY_RAVAGER_ROAR, 1.2f, 0.55f)
        BloodFx.sound(world, center, SoundEvents.BLOCK_BELL_RESONATE, 1.6f, 0.6f)
        BloodFx.sound(world, center, SoundEvents.ENTITY_BLAZE_SHOOT, 1.2f, 0.45f)
        BloodFx.sound(world, center, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 1.0f, 0.5f)
        BloodFx.sound(world, center, SoundEvents.ITEM_TRIDENT_THUNDER, 0.5f, 0.7f)
        BloodFx.sound(world, center, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 0.6f)
    }

    /** One frame of the expanding Blood Nova: two thick shock rings, spikes and flying chunks. */
    private fun novaFrame(session: NihilSession, owner: ServerPlayerEntity) {
        val world = session.world
        val center = owner.entityPos
        val t = (session.novaTick + 1).toDouble() / NOVA_TICKS
        val r = session.range * t
        val points = (24 + r * 12).toInt()
        BloodFx.ring(world, center.add(0.0, 0.15, 0.0), r, BloodFx.dust(BloodFx.BRIGHT, 1.8f), points, session.novaTick * 0.3)
        BloodFx.ring(world, center.add(0.0, 0.3, 0.0), r * 0.92, BloodFx.dust(BloodFx.DARK, 1.6f), points, -session.novaTick * 0.2)
        BloodFx.ring(world, center.add(0.0, 0.6, 0.0), r * 0.85, BloodFx.fade(BloodFx.BRIGHT, BloodFx.BLACK, 1.2f), points / 2)
        BloodFx.ring(world, center.add(0.0, 0.1, 0.0), r, BloodFx.flame, points / 2, session.novaTick * 0.17)
        // chunks shooting out of the wave front
        for (i in 0 until 6) {
            val a = world.random.nextDouble() * PI * 2
            BloodFx.spawn(world, BloodFx.crumbs, center.x + cos(a) * r, center.y + 0.3, center.z + sin(a) * r, 5, 0.1, 0.1, 0.1, 0.35)
        }
        if (session.novaTick % 3 == 0) {
            BloodFx.sound(world, center.add(cos(session.novaTick.toDouble()) * r, 0.0, sin(session.novaTick.toDouble()) * r), SoundEvents.BLOCK_HONEY_BLOCK_BREAK, 1.0f, 0.5f)
        }
    }

    // ----------------------------------------------------------- Blood Trail

    fun castBloodTrail(player: ServerPlayerEntity) {
        if (!HhaConfig.enabled("hha_blood.blood_trail")) return
        val world = player.entityWorld as? ServerWorld ?: return
        val legs = player.getEquippedStack(EquipmentSlot.LEGS)
        if (!legs.isOf(BloodItems.BLOOD_LEGGINGS) || player.itemCooldownManager.isCoolingDown(legs)) return

        Cooldowns.set(player, legs, "hha_blood.blood_trail_cooldown")
        val invis = HhaConfig.num("hha_blood.blood_trail_duration").toInt()
        val range = HhaConfig.num("hha_blood.blood_trail_range")
        val damage = HhaConfig.num("hha_blood.blood_trail_damage").toFloat()
        val slowTicks = HhaConfig.num("hha_blood.blood_trail_slow").toInt()

        EnduringEffects.withoutEnduring {
            player.addStatusEffect(StatusEffectInstance(StatusEffects.INVISIBILITY, invis + 2, 0, false, false, true))
            player.addStatusEffect(StatusEffectInstance(StatusEffects.SPEED, invis, 1, false, false, true))
        }
        // true invisibility: armour and held items disappear for everybody else too
        TrueInvis.start(player, invis + 2)   // the rise out of the blood (BloodDive) ends it
        // ... and what everybody sees instead: your double sinking into a pool of blood
        BloodDive.start(world, player, minOf(invis, 50), invis)

        val center = player.entityPos
        for (e in enemies(world, player, center, range)) {
            e.timeUntilRegen = 0
            e.damage(world, world.damageSources.playerAttack(player), damage)
            e.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, slowTicks, 5, false, true, true)) // Slowness VI
            Bleed.apply(e, source = player)
            BloodFx.wound(world, e.entityPos.add(0.0, 0.6, 0.0), 2)
        }
        pools += PoolSession(player.uuid, world, center, range, minOf(invis, 50), slowTicks)

        // dive effect: rings collapsing downwards into the pool + eruption of blood
        for (i in 0..5) {
            BloodFx.ring(world, center.add(0.0, 2.2 - i * 0.4, 0.0), 1.3 - i * 0.12, BloodFx.dust(BloodFx.BRIGHT, 1.4f), 18)
        }
        BloodFx.spawn(world, BloodFx.crumbs, center.add(0.0, 0.3, 0.0), 50, range * 0.4, 0.2, range * 0.4, 0.25)
        BloodFx.spawn(world, BloodFx.splat, center.add(0.0, 0.5, 0.0), 8, 0.8, 0.2, 0.8, 0.0)
        BloodFx.ring(world, center.add(0.0, 0.1, 0.0), range, BloodFx.dust(BloodFx.BLOOD, 1.8f), 44, 0.0, 0.04)
        BloodFx.sound(world, center, SoundEvents.BLOCK_HONEY_BLOCK_BREAK, 1.1f, 0.6f)
        BloodFx.sound(world, center, SoundEvents.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.8f, 0.5f)
        BloodFx.sound(world, center, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.2f, 0.5f)
    }

    private fun tickPool(pool: PoolSession) {
        val world = pool.world
        val owner = world.getPlayerByUuid(pool.owner) as? ServerPlayerEntity
        val elapsed = pool.total - pool.ticksLeft
        // the puddle itself
        if (elapsed % 2 == 0) {
            for (i in 0 until (pool.radius * 10).toInt()) {
                val a = world.random.nextDouble() * PI * 2
                val d = Math.sqrt(world.random.nextDouble()) * pool.radius
                BloodFx.spawn(world, BloodFx.dust(if (i % 3 == 0) BloodFx.DARK else BloodFx.BLOOD, 1.5f), pool.center.x + cos(a) * d, pool.center.y + 0.06, pool.center.z + sin(a) * d)
            }
            BloodFx.ring(world, pool.center.add(0.0, 0.08, 0.0), pool.radius, BloodFx.dust(BloodFx.BRIGHT, 1.2f), 28, elapsed * 0.15)
        }
        // bubbling surface
        if (elapsed % 5 == 0) {
            val a = world.random.nextDouble() * PI * 2
            val d = world.random.nextDouble() * pool.radius
            BloodFx.spawn(world, BloodFx.crumbs, pool.center.x + cos(a) * d, pool.center.y + 0.1, pool.center.z + sin(a) * d, 4, 0.1, 0.1, 0.1, 0.15)
            BloodFx.sound(world, pool.center, SoundEvents.BLOCK_HONEY_BLOCK_SLIDE, 0.5f, 0.5f)
        }
        // enemies that stay inside keep crawling
        if (owner != null && elapsed % 10 == 0) {
            for (e in enemies(world, owner, pool.center, pool.radius)) {
                e.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, 30.coerceAtMost(pool.slowTicks), 5, false, true, true))
            }
        }
        pool.ticksLeft--
    }

    // ------------------------------------------------------------------ tick

    fun tick(@Suppress("UNUSED_PARAMETER") server: MinecraftServer) {
        NihilFx.tick()
        BloodDive.tick(server)
        if (nihil.isNotEmpty()) {
            for (s in ArrayList(nihil)) tickNihil(s)
        }
        if (pools.isNotEmpty()) {
            val it = pools.iterator()
            while (it.hasNext()) {
                val p = it.next()
                tickPool(p)
                if (p.ticksLeft <= 0) it.remove()
            }
        }
    }
}
