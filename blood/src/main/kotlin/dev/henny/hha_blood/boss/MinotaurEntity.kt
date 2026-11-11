package dev.henny.hha_blood.boss

import dev.henny.hha_blood.BloodParticles
import dev.henny.hha_blood.HhaBlood
import dev.henny.hha_blood.hollows.HeartVaultPiece
import dev.henny.hha_blood.logic.BloodFx
import net.minecraft.entity.EntityType
import net.minecraft.entity.attribute.DefaultAttributeContainer
import net.minecraft.entity.attribute.EntityAttributeModifier
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import kotlin.math.cos
import kotlin.math.sin

/**
 * Crimson Minotaur - "Der Schlächter" (stages 3 to 5 of the Slaughter Pit). A giant bull warrior with a
 * two-handed axe: cleaves (blood fissure), sweeps, gores, and above all charges through the arena - shields
 * break, risen labyrinth walls burst, and a pillar in his way smashes but stuns him (the moment to strike).
 * Every hit he lands makes him faster ("Raserei", up to 10 stacks; a stun resets it).
 * Stage 4 (Labyrinth): hunting through narrow corridors, whirlwind, thrown axe, leap slam.
 * Stage 5: he discards the axe and fights with fists, grabs, tackles, stomps and body slams.
 */
class MinotaurEntity(type: EntityType<out MinotaurEntity>, world: World) : PitBoss(type, world) {

    companion object {
        val FURY: TrackedData<Int> = DataTracker.registerData(MinotaurEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        val AXE_OUT: TrackedData<Boolean> = DataTracker.registerData(MinotaurEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)
        val BURNING: TrackedData<Boolean> = DataTracker.registerData(MinotaurEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)
        val BERSERK: TrackedData<Boolean> = DataTracker.registerData(MinotaurEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)

        const val THRONE = 0
        const val STAND = 1
        const val IDLE = 2
        const val CLEAVE = 3
        const val SWEEP = 4
        const val CHARGE_WINDUP = 5
        const val CHARGE = 6
        const val STUNNED = 7
        const val WHIRL = 8
        const val THROW = 9
        const val CATCH = 10
        const val LEAP = 11
        const val GORE = 12
        const val LABYRINTH = 13
        const val ROAR = 14
        const val KNEEL = 15
        const val DEATH = 16
        const val LAUGH = 17
        const val POINT = 18
        const val ABSORB = 19
        const val SNORT = 20
        const val HOWL_ROAR = 21
        const val JUMP_DOWN = 22
        const val GRAB = 23
        const val QUAKE = 24
        const val STUCK = 25
        // stage 4: sniffing for the hidden players
        const val SNIFF = 26
        // stage 5 berserker
        const val STOMP = 27
        const val TACKLE = 28
        const val SLAM = 29
        const val RAGE = 30
        const val SPLIT = 31
        const val DISCARD = 32
        const val PUNCH = 33
        const val UPPERCUT = 34

        /** Vanilla caps max health at 1024 - [toughness] gives him 2048 effective health before armour (previously 1280). */
        const val MAX_HEALTH = 1024.0
        /** GRAB: reach, then hold the victim overhead for [GRAB_HOLD] ticks, then the slam. */
        const val GRAB_WIND = 12
        const val GRAB_HOLD = 26
        const val QUAKE_WIND = 20
        /** A missed cleave buries the axe in the floor this long - the punish window. */
        const val STUCK_TICKS = 46
        /** Damage (after scaling) the others must deal while he holds someone to free them. */
        const val GRAB_BREAK = 35f
        const val RENDER_SCALE = 0.6f
        const val MAX_FURY = 10

        private val FURY_SPEED = HhaBlood.id("fury_speed")
        private val BERSERK_SPEED = HhaBlood.id("berserk_speed")

        fun createAttributes(): DefaultAttributeContainer.Builder = createHostileAttributes()
            .add(EntityAttributes.MAX_HEALTH, MAX_HEALTH)
            .add(EntityAttributes.ATTACK_DAMAGE, 14.0)
            .add(EntityAttributes.ARMOR, 14.0)
            .add(EntityAttributes.ARMOR_TOUGHNESS, 8.0)
            .add(EntityAttributes.MOVEMENT_SPEED, 0.29)
            .add(EntityAttributes.KNOCKBACK_RESISTANCE, 1.0)
            .add(EntityAttributes.FOLLOW_RANGE, 80.0)
            .add(EntityAttributes.STEP_HEIGHT, 1.6)
    }

    private var cdCleave = 0
    private var cdSweep = 40
    private var cdGore = 30
    private var cdCharge = 200
    private var stuckHints = 0
    private var cdWhirl = 120
    private var cdThrow = 160
    private var cdLeap = 140
    private var cdLabyrinth = 200
    private var cdHowl = 260
    private var cdSnort = 120
    private var cdGrab = 160
    private var cdQuake = 120
    private var gcd = 20
    /** Forced follow-up attack (combo), -1 = none. */
    private var next = -1
    /** Stage 5 below 15 % health: permanent full fury. */
    private var desperate = false
    private var grabbed: java.util.UUID? = null
    private var grabDamage = 0f
    private var lastHitTick = 0
    private var chargesLeft = 0
    private var chargeDir = Vec3d.ZERO
    private var stunTicks = 0
    private var foe: PlayerEntity? = null
    private var aim = Vec3d.ZERO
    private val hitThisAction = HashSet<java.util.UUID>()
    private var landed = false

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(FURY, 0)
        builder.add(AXE_OUT, false)
        builder.add(BURNING, false)
        builder.add(BERSERK, false)
    }

    var fury: Int
        get() = dataTracker.get(FURY)
        set(v) {
            val n = v.coerceIn(0, MAX_FURY)
            if (n == dataTracker.get(FURY)) return
            dataTracker.set(FURY, n)
            val attr = getAttributeInstance(EntityAttributes.MOVEMENT_SPEED) ?: return
            attr.removeModifier(FURY_SPEED)
            if (n > 0) attr.addTemporaryModifier(EntityAttributeModifier(FURY_SPEED, 0.07 * n, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE))
        }

    var axeOut: Boolean
        get() = dataTracker.get(AXE_OUT)
        set(v) = dataTracker.set(AXE_OUT, v)

    /** Stage 5: the Cerberus' hellfire burns in him. */
    var burning: Boolean
        get() = dataTracker.get(BURNING)
        set(v) = dataTracker.set(BURNING, v)

    /** Stage 5: the berserker ("Der letzte Stier") - faster, harder, new brutal attacks, red steam. */
    var berserk: Boolean
        get() = dataTracker.get(BERSERK)
        set(v) {
            dataTracker.set(BERSERK, v)
            if (v) { axeOut = true; next = -1 }
            val attr = getAttributeInstance(EntityAttributes.MOVEMENT_SPEED) ?: return
            attr.removeModifier(BERSERK_SPEED)
            if (v) attr.addTemporaryModifier(EntityAttributeModifier(BERSERK_SPEED, 0.2, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE))
        }

    // ---- stage 4 hunt state
    private var quarry: java.util.UUID? = null
    private var seenCache: PlayerEntity? = null
    private var lastSeen = -1000
    private var searchPos: Vec3d? = null
    private var urgent = false
    private var wanderPos: Vec3d? = null
    private var pathGoal: Vec3d? = null
    private var pathSince = 0
    private var huntTaunt = 160
    private var cdSniff = 0
    private var lineCd = 0
    private val lastLine = HashMap<String, Int>()
    /** Set by [PitFight] when a hidden player's melee hit counts as a backstab (handled once the hit lands). */
    var backstabBy: PlayerEntity? = null

    // ---- stage 5 berserker
    private var cdStomp = 60
    private var cdTackle = 40
    private var cdSlam = 100
    private var cdRage = 200
    private var cdSplit = 140
    private var splitWind = 26
    private val brawlUsed = HashMap<Int, Int>()

    /** Oldest eligible move first: fists cannot permanently starve stomps, grabs and earth-rips. */
    private fun chooseBrawl(d: Double, blocking: Boolean): Int {
        if (blocking && d < 5 && cdGrab <= 0) return GRAB
        val moves = ArrayList<Int>()
        if (d < 4.8 && cdCleave <= 0) moves += PUNCH
        if (d < 3.6 && cdGore <= 0) moves += UPPERCUT
        if (d < 12 && cdStomp <= 0) moves += STOMP
        if (d < 13 && cdGrab <= 0) moves += GRAB
        if (d < 24 && cdSplit <= 0) moves += SPLIT
        if (d in 5.0..18.0 && cdTackle <= 0) moves += TACKLE
        if (d > 10 && cdSlam <= 0) moves += SLAM
        if (d < 9 && cdRage <= 0 && rageTicks <= 0) moves += RAGE
        return moves.minByOrNull { brawlUsed[it] ?: Int.MIN_VALUE } ?: -1
    }
    private var rageTicks = 0
    private var slamTarget = Vec3d.ZERO
    private var slamChain = 0
    private var landAt = 0
    private var splitFrom = Vec3d.ZERO
    private var struckAny = false
    private var suplexAt = -1

    override fun resetAction() {
        releaseGrab()
        navigation.stop()
        velocity = Vec3d.ZERO
        next = -1
        desperate = false
        act(IDLE)
    }

    override fun refreshStats() {
        getAttributeInstance(EntityAttributes.MAX_HEALTH)?.baseValue = MAX_HEALTH
        getAttributeInstance(EntityAttributes.MOVEMENT_SPEED)?.baseValue = 0.29
    }

    /** Windup factor: fury makes every attack come faster. */
    private fun w(ticks: Int): Int = (ticks * (1.0 - 0.035 * fury) * (if (burning) 0.85 else 1.0) * (if (berserk) 0.82 else 1.0)).toInt().coerceAtLeast(5)

    fun stage(): Int = PitFight.stage

    /** Every fury stack hits 4 % harder, the Cerberus' fire another 10 %. */
    override fun damageBonus(): Float = 0.75f * (1f + 0.04f * fury + (if (burning) 0.1f else 0f) + (if (berserk) 0.08f else 0f))

    override fun toughness(): Float = 0.5f

    override fun onPlayerHit(p: PlayerEntity) {
        if (scripted) return
        fury += 1
        lastHitTick = age
        if (fury % 3 == 0) sound(BossSounds.MINOTAUR_SNORT, 2f, 0.9f)
    }

    override fun onHurtBy(source: net.minecraft.entity.damage.DamageSource, amount: Float) {
        val w0 = entityWorld as? ServerWorld
        val stabber = backstabBy
        backstabBy = null
        if (w0 != null && stabber != null) backstabbed(w0, stabber)
        else if (stage() == PitFight.LABYRINTH) {
            // hit by someone he did not see: now he does
            (source.attacker as? PlayerEntity)?.let { if (!PitLabyrinth.isHidden(it)) { quarry = it.uuid; lastSeen = age; seenCache = it } }
        }
        if (grabbed == null) return
        grabDamage += amount
        if (grabDamage >= GRAB_BREAK) {
            // the others beat him until he lets go
            val w = entityWorld as? ServerWorld ?: return
            sound(BossSounds.MINOTAUR_HURT, 3f, 0.8f)
            releaseGrab()
            stun(w, 30)
        }
    }

    /** Drops whoever he holds (gently, no fall damage). */
    private fun releaseGrab() {
        val id = grabbed ?: return
        grabbed = null
        val w = entityWorld as? ServerWorld ?: return
        val p = PitFight.targets(w).firstOrNull { it.uuid == id } ?: w.getPlayerByUuid(id) ?: return
        p.fallDistance = 0.0
        p.velocity = Vec3d.ZERO
        p.velocityDirty = true
    }

    /** The thrown axe came back. */
    fun catchAxe() {
        if (berserk) return
        axeOut = false
        if (action == THROW) act(CATCH)
        sound(BossSounds.AXE_IMPACT, 1.2f, 1.5f)
    }

    // ------------------------------------------------------------------------------------- AI

    override fun mobTick(world: ServerWorld) {
        super.mobTick(world)
        if (scripted) { releaseGrab(); navigation.stop(); return }
        if (!PitFight.minotaurFighting()) { releaseGrab(); navigation.stop(); return }
        cdCleave--; cdSweep--; cdGore--; cdCharge--; cdWhirl--; cdThrow--; cdLeap--; cdLabyrinth--; cdHowl--; cdSnort--; cdGrab--; cdQuake--; gcd--
        cdStomp--; cdTackle--; cdSlam--; cdRage--; cdSplit--; cdSniff--; lineCd--
        backstabBy = null
        if (grabbed != null && action != GRAB) releaseGrab()
        if (rageTicks > 0) { rageTicks--; fury = MAX_FURY }
        if (berserk) {
            if (fury < 5) fury = 5
            berserkAura(world)
        }
        // stage 5, almost dead: he goes berserk (full fury for good)
        if (stage() >= 5 && !desperate && health < maxHealth * 0.15f) {
            desperate = true
            fury = MAX_FURY
            sound(BossSounds.MINOTAUR_ROAR, 4f, 0.7f)
            BloodFx.shake(world, entityPos, 40.0, 1.5f, 20)
            for (p in PitFight.targets(world)) if (p is net.minecraft.server.network.ServerPlayerEntity) PitFight.hint(p, "boss.hha_blood.hint.desperate")
        }
        if (desperate) fury = MAX_FURY
        // fury fades when he has not hit anyone for a while (never below 4 in stage 5)
        if (age - lastHitTick > 200 && age % 80 == 0) fury -= 1
        if (burning && fury < 4) fury = 4
        if (burning && age % 3 == 0) {
            world.spawnParticles(BloodParticles.FLAME, x, y + 3.3, z, 2, 0.4, 0.2, 0.4, 0.01)
            world.spawnParticles(ParticleTypes.FLAME, x, y + 2.0, z, 1, 0.6, 1.0, 0.6, 0.01)
        }
        if (fury >= 6 && age % 5 == 0) world.spawnParticles(BloodFx.dust(BloodFx.BRIGHT, 1.3f), x, y + 2.5, z, 2, 0.5, 0.8, 0.5, 0.0)
        val hunting = stage() == PitFight.LABYRINTH
        val target = if (hunting) seen(world) else PitFight.targets(world)
            .filter { !PitLabyrinth.isHidden(it) }.minByOrNull { it.squaredDistanceTo(entityPos) }
        foe = target
        if (hunting && target != null && action == SNIFF) act(IDLE)
        when (action) {
            IDLE -> if (hunting && target == null) hunt(world) else idle(world, target)
            SNIFF -> tickSniff(world)
            STOMP -> tickStomp(world)
            TACKLE -> tickTackle(world)
            SLAM -> tickSlam(world)
            RAGE -> tickRage(world)
            SPLIT -> tickSplit(world)
            PUNCH -> tickPunch(world)
            UPPERCUT -> tickUppercut(world)
            CLEAVE -> tickCleave(world)
            SWEEP -> tickSweep(world)
            GORE -> tickGore(world)
            CHARGE_WINDUP -> tickChargeWindup(world)
            CHARGE -> tickCharge(world)
            STUNNED -> tickStunned(world)
            STUCK -> tickStuck(world)
            WHIRL -> tickWhirl(world)
            THROW -> tickThrow(world)
            CATCH -> if (actionAge > 10) act(IDLE)
            LEAP -> tickLeap(world)
            LABYRINTH -> if (actionAge > 40) act(IDLE)
            HOWL_ROAR -> tickHowlRoar(world)
            SNORT -> tickSnort(world)
            GRAB -> tickGrab(world)
            QUAKE -> tickQuake(world)
            else -> act(IDLE)
        }
        if (action == IDLE && age % 22 == 0 && limbAnimator.speed > 0.25f) {
            sound(BossSounds.MINOTAUR_STEP, 1.8f, 0.85f + random.nextFloat() * 0.2f)
            BloodFx.shake(world, entityPos, 10.0, 0.25f, 4)
        }
    }

    private fun idle(world: ServerWorld, target: PlayerEntity?) {
        if (target == null) { navigation.stop(); return }
        val d = horizontalDistance(target.entityPos)
        face(target.entityPos, 10f + fury)
        if (axeOut && !berserk) {
            // without the axe: charge with the horns or wait for it
            chase(target, d)
            if (d < 3.6 && cdGore <= 0) start(GORE)
            return
        }
        if (next >= 0) {
            val n = next
            next = -1
            aim = target.entityPos
            start(n)
            return
        }
        if (gcd > 0) { chase(target, d); return }
        hitThisAction.clear()
        val s = stage()
        val b = berserk
        val choice = if (b) chooseBrawl(d, target.isBlocking) else when {
            // a raised shield invites the grab ("bricht durch die Deckung")
            cdGrab <= 0 && d < 4.2 && (target.isBlocking || random.nextFloat() < 0.35f) -> GRAB
            d < 3.4 && cdGore <= 0 -> GORE
            d < 5.2 && cdCleave <= 0 -> CLEAVE
            d < 5.0 && cdSweep <= 0 -> SWEEP
            PitHazards.wallsUp() && cdCharge <= 0 && d > 9.0 -> CHARGE_WINDUP
            s >= 4 && cdWhirl <= 0 && d < 9 -> WHIRL
            s >= 4 && cdLeap <= 0 && d > 10 -> LEAP
            s >= 4 && cdThrow <= 0 && d in 8.0..26.0 -> THROW
            cdCharge <= 0 && d > 14.0 -> CHARGE_WINDUP
            s == 3 && cdThrow <= 0 && d in 12.0..26.0 -> THROW
            else -> -1
        }
        if (choice < 0) { chase(target, d); return }
        aim = target.entityPos
        start(choice)
    }

    private fun start(choice: Int) {
        if (berserk) brawlUsed[choice] = age
        navigation.stop()
        hitThisAction.clear()
        act(choice)
        when (choice) {
            PUNCH -> { cdCleave = 110; gcd = 18; sound(BossSounds.MINOTAUR_GRUNT, 2.5f, 0.8f) }
            UPPERCUT -> { cdGore = 70; gcd = 18; sound(BossSounds.MINOTAUR_GRUNT, 2.5f, 0.7f) }
            CLEAVE -> { cdCleave = 30; gcd = w(14); sound(BossSounds.MINOTAUR_GRUNT, 2f, 0.9f) }
            SWEEP -> { cdSweep = 40; gcd = w(12); sound(BossSounds.MINOTAUR_GRUNT, 2f, 0.8f) }
            GORE -> { cdGore = 50; gcd = w(10) }
            CHARGE_WINDUP -> {
                cdCharge = when { PitHazards.wallsUp() -> 240; stage() >= 5 -> 300; else -> 360 }; gcd = w(22)
                chargesLeft = if (burning) 2 else 1
                sound(BossSounds.MINOTAUR_CHARGE, 3f, 1f)
                sound(BossSounds.MINOTAUR_SNORT, 2.5f, 0.85f)
            }
            WHIRL -> { cdWhirl = 220; gcd = w(24); sound(BossSounds.MINOTAUR_ROAR, 2f, 1.2f) }
            THROW -> { cdThrow = 240; gcd = w(16) }
            LEAP -> { cdLeap = 200; gcd = w(24); sound(BossSounds.MINOTAUR_GRUNT, 2.5f, 0.7f) }
            LABYRINTH -> { cdLabyrinth = if (burning) 600 else 700; gcd = w(24) }
            HOWL_ROAR -> { cdHowl = 320; gcd = w(20) }
            SNORT -> { cdSnort = 180; gcd = w(20) }
            GRAB -> { cdGrab = if (berserk) 180 else 260; gcd = w(20); grabDamage = 0f; sound(BossSounds.MINOTAUR_GRUNT, 2.5f, 0.75f) }
            QUAKE -> { cdQuake = if (desperate) 260 else 400; gcd = w(24); sound(BossSounds.MINOTAUR_ROAR, 3.5f, 0.75f) }
            SNIFF -> { cdSniff = 100; gcd = 0 }
            STOMP -> { cdStomp = 150; gcd = w(16); sound(BossSounds.MINOTAUR_GRUNT, 2.8f, 0.7f) }
            TACKLE -> { cdTackle = 90; gcd = w(10); struckAny = false; sound(BossSounds.MINOTAUR_SNORT, 2.8f, 1.15f) }
            SLAM -> { cdSlam = 150; gcd = w(18); sound(BossSounds.MINOTAUR_GRUNT, 3f, 0.6f) }
            RAGE -> { cdRage = 360; gcd = w(12) }
            SPLIT -> { cdSplit = 160; gcd = w(18); splitWind = 26; sound(BossSounds.MINOTAUR_GRUNT, 2.8f, 0.8f) }
        }
        // the berserker barely breathes between attacks
        if (berserk) gcd = (gcd * 0.7).toInt()
    }

    private fun chase(target: PlayerEntity, d: Double) {
        if (d > 2.6) navigation.startMovingTo(target.x, target.y, target.z, 1.15) else navigation.stop()
    }

    /** Combo: after a hit he goes on with [follow] if the target is still within [range]. */
    private fun combo(follow: Int, range: Double, chance: Float) {
        val t = foe ?: return
        if (horizontalDistance(t.entityPos) > range) return
        if (random.nextFloat() < chance + fury * 0.07f) next = follow
    }

    private fun foeOrAim(): Vec3d = foe?.entityPos ?: aim

    // ------------------------------------------------------------------------------------- melee

    private fun tickCleave(world: ServerWorld) {
        val wind = w(18)
        if (actionAge < wind - 4) face(foeOrAim(), 9f)
        if (actionAge == wind - 2) sound(BossSounds.AXE_SWING, 2.5f, 0.75f)
        if (actionAge == wind) {
            val f = forward()
            val impact = entityPos.add(f.multiply(3.4))
            sound(BossSounds.AXE_IMPACT, 3f, 0.9f)
            BloodFx.shake(world, impact, 16.0, 1.6f, 10)
            world.spawnParticles(net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, dev.henny.hha_blood.hollows.HollowsBlocks.POLISHED_BLOOD_STONE.defaultState),
                impact.x, y + 0.2, impact.z, 30, 0.8, 0.2, 0.8, 0.25)
            world.spawnParticles(BloodParticles.SPLAT, impact.x, y + 0.3, impact.z, 6, 0.6, 0.1, 0.6, 0.1)
            var struck = false
            for (p in PitFight.targets(world)) {
                if (!canSee(p) || !inCone(p.entityPos, entityPos, f, 5.4, 32.0)) continue
                breakShield(world, p)
                if (hurt(world, p, 45f, bleedTicks = 80)) { struck = true; knock(p, entityPos, 0.6, 0.3) }
            }
            PitHazards.fissure(this, impact, f, if (stage() >= 4) 10 else 7, 20f)
            PitArena.smashWalls(world, impact, 2.0)
            if (struck) combo(SWEEP, 5.6, 0.45f)
            else if (random.nextFloat() < (when { desperate -> 0.5f; berserk -> 0.6f; else -> 1f })) { act(STUCK); return }
        }
        if (actionAge >= wind + 12) act(IDLE)
    }

    private fun tickSweep(world: ServerWorld) {
        val wind = w(14)
        if (actionAge < wind) face(foeOrAim(), 10f)
        if (actionAge == wind - 1) sound(BossSounds.AXE_SWING, 2.8f, 0.65f)
        if (actionAge in wind..(wind + 3)) {
            for (p in PitFight.targets(world)) {
                if (p.uuid in hitThisAction) continue
                if (!inCone(p.entityPos, entityPos, forward(), 5.6, 100.0)) continue
                hitThisAction += p.uuid
                breakShield(world, p)
                if (hurt(world, p, 32f)) knock(p, entityPos, 1.5, 0.45)
            }
            val f = forward()
            for (k in -4..4) {
                val a = Math.toRadians(k * 22.0)
                val dx = f.x * cos(a) - f.z * sin(a)
                val dz = f.x * sin(a) + f.z * cos(a)
                world.spawnParticles(ParticleTypes.SWEEP_ATTACK, x + dx * 3.6, y + 1.6, z + dz * 3.6, 1, 0.0, 0.0, 0.0, 0.0)
            }
        }
        if (actionAge == wind + 4) { if (berserk && random.nextBoolean()) combo(TACKLE, 9.0, 0.35f) else combo(GORE, 3.8, 0.3f) }
        if (actionAge >= wind + 12) act(IDLE)
    }

    private fun tickGore(world: ServerWorld) {
        val wind = w(9)
        if (actionAge < wind) face(foeOrAim(), 14f)
        if (actionAge == wind) {
            sound(BossSounds.MINOTAUR_SNORT, 2.2f, 1.1f)
            velocity = forward().multiply(0.5)
            for (p in PitFight.targets(world)) {
                if (!inCone(p.entityPos, entityPos, forward(), 3.8, 50.0)) continue
                breakShield(world, p)
                if (hurt(world, p, 26f)) { knock(p, entityPos, 0.5, 1.05) }
            }
            if (berserk) combo(CLEAVE, 5.2, 0.35f)
        }
        if (actionAge >= wind + 12) act(IDLE)
    }

    // ------------------------------------------------------------------------------------- charge

    private fun tickChargeWindup(world: ServerWorld) {
        val wind = w(if (chargesLeft < (if (burning) 2 else 1)) 14 else 26)
        val target = foeOrAim()
        face(target, 9f)
        if (actionAge % 6 == 0) {
            sound(BossSounds.MINOTAUR_STEP, 1.6f, 0.7f)
            world.spawnParticles(ParticleTypes.CLOUD, x, y + 0.1, z, 6, 0.8, 0.0, 0.8, 0.03)
        }
        if (actionAge % 3 == 0) {
            // red line on the floor where he will run
            val f = forward()
            for (k in 1..18) {
                val p = entityPos.add(f.multiply(k * 1.8))
                if (PitArena.radius(p) > HeartVaultPiece.FIGHT_R) break
                world.spawnParticles(BloodFx.dust(0xFF1A1A, 1.6f), p.x, y + 0.12, p.z, 1, 0.12, 0.0, 0.12, 0.0)
            }
        }
        if (actionAge >= wind) {
            chargeDir = forward()
            act(CHARGE)
            sound(BossSounds.MINOTAUR_ROAR, 3f, 1.15f)
        }
    }

    private fun tickCharge(world: ServerWorld) {
        val t = actionAge
        val speed = (if (burning) 1.45 else 1.25) + fury * 0.035
        velocity = Vec3d(chargeDir.x * speed, velocity.y, chargeDir.z * speed)
        velocityDirty = true
        if (t % 4 == 0) {
            sound(BossSounds.MINOTAUR_STEP, 2.2f, 1.1f)
            BloodFx.shake(world, entityPos, 12.0, 0.5f, 4)
        }
        world.spawnParticles(ParticleTypes.CLOUD, x, y + 0.2, z, 2, 0.6, 0.1, 0.6, 0.02)
        if (burning && t % 2 == 0) PitHazards.fire(this, entityPos, 1.4, 120, 2.5f)
        // through the labyrinth walls
        if (PitArena.smashWalls(world, entityPos.add(chargeDir.multiply(1.6)).add(0.0, 0.0, 0.0), 1.9) > 0) {
            sound(BossSounds.IMPACT_HEAVY, 1.8f, 1.3f)
        }
        for (p in PitFight.targets(world)) {
            if (p.uuid in hitThisAction) continue
            if (p.boundingBox.intersects(boundingBox.expand(0.8))) {
                hitThisAction += p.uuid
                breakShield(world, p, 120)
                if (hurt(world, p, 55f)) knock(p, entityPos, 2.4, 0.7)
            }
        }
        val ahead = entityPos.add(chargeDir.multiply(1.8))
        val c = PitArena.center()
        val pillar = if (c != null) HeartVaultPiece.pillarAt(ahead.x - c.x, ahead.z - c.z, 0.4) else -1
        val wall = PitArena.radius(ahead) > HeartVaultPiece.FIGHT_R - 0.8
        if (t > 2 && (pillar >= 0 || wall || horizontalCollision)) {
            velocity = Vec3d.ZERO
            sound(BossSounds.IMPACT_HEAVY, 3.5f, 0.8f)
            BloodFx.shake(world, entityPos, 24.0, 2.2f, 16)
            if (pillar >= 0) {
                PitArena.smashPillar(world, ahead, 2.7)
                stun(world, 55)
                PitHazards.rockfall(this, world, 3 + stage(), 30f)
            } else if (wall || horizontalCollision) {
                stun(world, 38)
                // the whole dome shakes: rubble comes down
                PitHazards.rockfall(this, world, 4 + stage() * 2, 30f)
                for (pl in PitFight.targets(world)) if (pl is net.minecraft.server.network.ServerPlayerEntity) PitFight.hint(pl, "boss.hha_blood.hint.rockfall")
            }
            return
        }
        if (t > 34) {
            // missed: slide, maybe go again (stage 5 runs three times in a row)
            chargesLeft--
            if (chargesLeft > 0 && foe != null) { act(CHARGE_WINDUP); hitThisAction.clear() } else act(IDLE)
        }
    }

    private fun stun(world: ServerWorld, ticks: Int) {
        releaseGrab()
        next = -1
        stunTicks = ticks
        fury = if (desperate) MAX_FURY else 0
        chargesLeft = 0
        act(STUNNED)
        sound(BossSounds.MINOTAUR_HURT, 3f, 0.7f)
        world.spawnParticles(ParticleTypes.EXPLOSION, x, y + 1.5, z, 2, 0.8, 0.8, 0.8, 0.0)
        for (pl in PitFight.targets(world)) {
            if (pl is net.minecraft.server.network.ServerPlayerEntity) {
                PitFight.hint(pl, "boss.hha_blood.hint.stunned")
            }
        }
    }

    /** Stunned: takes more damage. */
    fun isStunned(): Boolean = action == STUNNED || action == STUCK

    /** A missed cleave: the axe is buried in the floor and he tugs at it - free hits (x1.5) until it comes out. */
    private fun tickStuck(world: ServerWorld) {
        navigation.stop()
        velocity = Vec3d(0.0, velocity.y, 0.0)
        val axeAt = entityPos.add(forward().multiply(3.2))
        val stone = net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, dev.henny.hha_blood.hollows.HollowsBlocks.POLISHED_BLOOD_STONE.defaultState)
        if (actionAge == 2 && stuckHints < 2) {
            stuckHints++
            for (p in PitFight.targets(world)) if (p is net.minecraft.server.network.ServerPlayerEntity) PitFight.hint(p, "boss.hha_blood.hint.stuck")
        }
        if (actionAge % 9 == 3) {
            sound(BossSounds.MINOTAUR_GRUNT, 2.2f, 0.65f + random.nextFloat() * 0.25f)
            world.spawnParticles(stone, axeAt.x, y + 0.2, axeAt.z, 8, 0.25, 0.05, 0.25, 0.12)
        }
        if (actionAge % 4 == 0) world.spawnParticles(ParticleTypes.CRIT, x, y + 4.0, z, 1, 0.4, 0.1, 0.4, 0.0)
        if (actionAge >= (if (berserk) 32 else STUCK_TICKS)) {
            // rips it out: rubble bursts around the axe
            sound(BossSounds.AXE_IMPACT, 2.6f, 1.25f)
            sound(BossSounds.MINOTAUR_SNORT, 2.5f, 0.9f)
            BloodFx.shake(world, axeAt, 12.0, 0.8f, 6)
            world.spawnParticles(stone, axeAt.x, y + 0.3, axeAt.z, 30, 0.6, 0.2, 0.6, 0.3)
            for (p in PitFight.targets(world)) {
                if (p.squaredDistanceTo(axeAt) > 2.6 * 2.6) continue
                if (hurt(world, p, 14f)) knock(p, axeAt, 0.8, 0.45)
            }
            act(IDLE)
            gcd = w(8)
        }
    }

    private fun tickStunned(world: ServerWorld) {
        navigation.stop()
        if (actionAge % 5 == 0) {
            for (i in 0 until 3) {
                val a = actionAge * 0.3 + i * 2.1
                world.spawnParticles(ParticleTypes.CRIT, x + cos(a) * 0.9, y + 4.2, z + sin(a) * 0.9, 1, 0.0, 0.0, 0.0, 0.0)
            }
        }
        if (actionAge >= stunTicks) {
            sound(BossSounds.MINOTAUR_SNORT, 2.5f, 0.8f)
            act(IDLE)
            gcd = 10
        }
    }

    // ------------------------------------------------------------------------------------- stage 4+

    private fun tickWhirl(world: ServerWorld) {
        val wind = w(12)
        if (actionAge < wind) { face(foeOrAim(), 8f); return }
        val t = actionAge - wind
        if (t < 54) {
            yaw += 38f; bodyYaw = yaw; headYaw = yaw
            val target = foeOrAim()
            val d = target.subtract(entityPos)
            val dir = Vec3d(d.x, 0.0, d.z).normalize()
            velocity = Vec3d(dir.x * 0.32, velocity.y, dir.z * 0.32)
            velocityDirty = true
            if (t % 14 == 0) sound(BossSounds.AXE_SPIN, 2.5f, 0.9f)
            if (t % 3 == 0) for (k in 0 until 8) {
                val a = Math.toRadians(yaw.toDouble() + k * 45.0)
                world.spawnParticles(ParticleTypes.SWEEP_ATTACK, x + cos(a) * 3.4, y + 1.4, z + sin(a) * 3.4, 1, 0.0, 0.0, 0.0, 0.0)
            }
            if (t % 6 == 0) for (p in PitFight.targets(world)) {
                if (p.squaredDistanceTo(entityPos) < 4.8 * 4.8 && kotlin.math.abs(p.y - y) < 3) {
                    breakShield(world, p)
                    if (hurt(world, p, 16f, ignoreFrames = true)) knock(p, entityPos, 1.1, 0.3)
                }
            }
            PitArena.smashWalls(world, entityPos, 3.2)
            return
        }
        // dizzy for a moment afterwards
        velocity = Vec3d.ZERO
        if (t >= 54 + 18) act(IDLE)
    }

    private fun tickThrow(world: ServerWorld) {
        val wind = w(16)
        if (actionAge < wind) { face(foeOrAim(), 10f); return }
        if (actionAge == wind) {
            axeOut = true
            sound(BossSounds.AXE_SWING, 3f, 0.6f)
            val hand = entityPos.add(forward().multiply(1.4)).add(0.0, 2.8, 0.0)
            val target = foe?.let { it.entityPos.add(it.velocity.multiply(8.0)).add(0.0, 1.0, 0.0) } ?: aim.add(0.0, 1.0, 0.0)
            PitHazards.throwAxe(this, hand, target, 30f)
        }
        // waits (and roars) until the axe returns: catchAxe() switches to CATCH
        if (actionAge > wind) face(foeOrAim(), 6f)
        if (actionAge == wind + 14) sound(BossSounds.MINOTAUR_ROAR, 2f, 1.3f)
        if (actionAge > wind + 120) { axeOut = false; act(IDLE) }
    }

    private fun tickLeap(world: ServerWorld) {
        val wind = w(14)
        if (actionAge < wind) { face(foeOrAim(), 12f); return }
        if (actionAge == wind) {
            leapTo(PitArena.clampToFloor(foeOrAim(), 3.0), 22)
            landed = false
            sound(BossSounds.MINOTAUR_ROAR, 2.5f, 1.25f)
        }
        if (actionAge > wind + 4 && !landed && (isOnGround || actionAge > wind + 50)) {
            landed = true
            sound(BossSounds.IMPACT_HEAVY, 3.5f, 0.75f)
            sound(BossSounds.AXE_IMPACT, 3f, 0.7f)
            BloodFx.shake(world, entityPos, 26.0, 2.4f, 18)
            world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, x, y + 0.2, z, 1, 0.0, 0.0, 0.0, 0.0)
            for (p in PitFight.targets(world)) {
                if (p.squaredDistanceTo(entityPos) < 3.4 * 3.4) { breakShield(world, p); if (hurt(world, p, 40f)) knock(p, entityPos, 1.6, 0.8) }
            }
            PitHazards.shockRing(this, entityPos, 15.0, 0.65, 26f, burning)
            PitArena.smashWalls(world, entityPos, 3.5)
        }
        if (landed && actionAge > wind + 40) act(IDLE)
        if (actionAge > wind + 90) act(IDLE)
    }

    private fun tickHowlRoar(world: ServerWorld) {
        val wind = w(22)
        if (actionAge < wind) {
            if (actionAge % 4 == 0) warnRing(world, entityPos, 18.0 * actionAge / wind, 0x8A40FF)
            return
        }
        if (actionAge == wind) {
            sound(BossSounds.MINOTAUR_ROAR, 4f, 0.8f)
            sound(BossSounds.CERBERUS_HOWL, 2.5f, 0.7f)
            PitHazards.shockRing(this, entityPos, 18.0, 1.0, 0f, true)
            val head = entityPos.add(0.0, 3.6, 0.0)
            for (p in PitFight.targets(world)) {
                if (p.squaredDistanceTo(entityPos) > 18.0 * 18.0) continue
                if (!PitFight.lineOfSight(world, head, p.eyePos)) continue
                PitFight.paralyze(world, p, 40)
            }
        }
        if (actionAge >= wind + 16) {
            act(IDLE)
            gcd = 0
        }
    }

    private fun tickSnort(world: ServerWorld) {
        val wind = w(12)
        if (actionAge < wind) { face(foeOrAim(), 10f); return }
        val t = actionAge - wind
        if (t == 0) { sound(BossSounds.MINOTAUR_SNORT, 3f, 0.7f); sound(BossSounds.CERBERUS_FLAME, 2.5f, 1.2f) }
        if (t < 36) {
            face(foeOrAim(), 3f)
            val nose = entityPos.add(forward().multiply(1.3)).add(0.0, 3.2, 0.0)
            PitHazards.flameTick(world, this, nose, forward().add(0.0, -0.35, 0.0), 9.0, 18.0, t, 8f)
        }
        if (t >= 44) act(IDLE)
    }

    // ------------------------------------------------------------------------------------- grab

    /** Where a grabbed player hangs: in his left fist, high over his head. */
    private fun fistPos(): Vec3d {
        val f = forward()
        val left = Vec3d(f.z, 0.0, -f.x)
        return entityPos.add(f.multiply(1.2)).add(left.multiply(0.9)).add(0.0, 2.9, 0.0)
    }

    private fun tickGrab(world: ServerWorld) {
        val wind = if (berserk) 24 else w(GRAB_WIND)
        if (actionAge < wind) {
            if (!berserk || actionAge < wind - 9) face(foeOrAim(), 10f)
            // the berserker lunges for you
            if (berserk && actionAge >= wind - 8 && foe != null && horizontalDistance(foe!!.entityPos) > 3.0) {
                val f = forward()
                velocity = Vec3d(f.x * 1.05, velocity.y, f.z * 1.05)
                velocityDirty = true
            }
            return
        }
        if (actionAge == wind) {
            suplexAt = -1
            val victim = PitFight.targets(world)
                .filter { inCone(it.entityPos, entityPos, forward(), 4.4, 50.0) && kotlin.math.abs(it.y - y) < 2.5 }
                .minByOrNull { it.squaredDistanceTo(entityPos) }
            if (victim == null) {
                // grabbed nothing: stumbles forward
                velocity = forward().multiply(0.4)
                sound(if (berserk) BossSounds.MINOTAUR_GRUNT else BossSounds.AXE_SWING, 2f, 0.5f)
                gcd = w(10)
                if (berserk) stun(world, 24) else act(IDLE)
                return
            }
            grabbed = victim.uuid
            grabDamage = 0f
            breakShield(world, victim, 120)
            sound(BossSounds.MINOTAUR_ROAR, 3f, 1.1f)
            if (victim is net.minecraft.server.network.ServerPlayerEntity) PitFight.hint(victim, "boss.hha_blood.hint.grab")
            for (p in PitFight.targets(world)) if (p != victim && p is net.minecraft.server.network.ServerPlayerEntity) PitFight.hint(p, "boss.hha_blood.hint.grab_other")
        }
        val t = actionAge - wind
        val id = grabbed
        val victim = id?.let { g -> PitFight.targets(world).firstOrNull { it.uuid == g } }
        if (victim == null) {
            if (suplexAt < 0 && t > 0 && t < GRAB_HOLD + 2) { releaseGrab(); act(IDLE); return }
            if (t >= (if (suplexAt >= 0) suplexAt + 18 else GRAB_HOLD + 16)) act(IDLE)
            return
        }
        if (t < GRAB_HOLD) {
            // held up high, crushed a little
            face(victim.entityPos, 0f)
            hold(world, victim, fistPos().add(0.0, -0.9, 0.0))
            if (t % 8 == 4) {
                hurt(world, victim, 5f, ignoreFrames = true)
                world.spawnParticles(BloodParticles.SPLAT, victim.x, victim.y + 1.0, victim.z, 4, 0.3, 0.3, 0.3, 0.1)
                sound(BossSounds.MINOTAUR_SNORT, 2f, 0.7f)
            }
            return
        }
        if (!berserk) {
            // the slam
            slamVictim(world, victim, PitArena.clampToFloor(entityPos.add(forward().multiply(2.4)), 2.0), 42f, 8.0)
            suplexAt = t
            return
        }
        // berserker: leaps up with the victim over his head and piledrives them into the floor
        if (t == GRAB_HOLD) {
            val f = forward()
            velocity = Vec3d(f.x * 0.2, 0.95, f.z * 0.2)
            velocityDirty = true
            sound(BossSounds.MINOTAUR_ROAR, 3.5f, 0.85f)
            world.spawnParticles(ParticleTypes.CLOUD, x, y + 0.2, z, 16, 0.8, 0.1, 0.8, 0.05)
        }
        hold(world, victim, entityPos.add(0.0, 4.4, 0.0))
        if (t > GRAB_HOLD + 4 && (isOnGround || t > GRAB_HOLD + 40)) {
            slamVictim(world, victim, PitArena.clampToFloor(entityPos.add(forward().multiply(1.6)), 2.0), 54f, 13.0)
            BloodFx.screenFlash(world, entityPos, 30.0, 0xC01010, 0.3f, 8)
            PitHazards.rockfall(this, world, 4, 24f)
            suplexAt = t
        }
    }

    private fun hold(world: ServerWorld, victim: PlayerEntity, at: Vec3d) {
        victim.teleport(world, at.x, at.y, at.z, emptySet(), victim.yaw, victim.pitch, false)
        victim.velocity = Vec3d.ZERO
        victim.velocityDirty = true
        victim.fallDistance = 0.0
    }

    private fun slamVictim(world: ServerWorld, victim: PlayerEntity, ground: Vec3d, damage: Float, ring: Double) {
        hold(world, victim, ground)
        grabbed = null
        hurt(world, victim, damage, bleedTicks = 100, ignoreFrames = true)
        sound(BossSounds.IMPACT_HEAVY, 3.5f, 0.7f)
        sound(if (berserk) BossSounds.IMPACT_HEAVY else BossSounds.AXE_IMPACT, 2.5f, 0.6f)
        BloodFx.shake(world, ground, 24.0, 2.0f, 14)
        world.spawnParticles(ParticleTypes.EXPLOSION, ground.x, ground.y + 0.3, ground.z, 2, 0.6, 0.2, 0.6, 0.0)
        world.spawnParticles(net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, dev.henny.hha_blood.hollows.HollowsBlocks.POLISHED_BLOOD_STONE.defaultState),
            ground.x, ground.y + 0.2, ground.z, 40, 1.0, 0.2, 1.0, 0.3)
        PitHazards.shockRing(this, ground, ring, 0.55, 16f, burning)
    }

    // ------------------------------------------------------------------------------------- quake (stage 5)

    /** The axe into the ground: blood fissures in all directions and the ceiling comes down. */
    private fun tickQuake(world: ServerWorld) {
        val wind = w(QUAKE_WIND)
        if (actionAge < wind - 4) {
            face(foeOrAim(), 8f)
            if (actionAge % 4 == 0) warnRing(world, entityPos, 3.0 + actionAge * 0.3, 0xFF2020)
        }
        if (actionAge == wind - 2) sound(BossSounds.AXE_SWING, 3f, 0.55f)
        if (actionAge == wind) {
            val f = forward()
            sound(BossSounds.AXE_IMPACT, 4f, 0.6f)
            sound(BossSounds.IMPACT_HEAVY, 4f, 0.6f)
            BloodFx.shake(world, entityPos, 60.0, 2.6f, 26)
            world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, x, y + 0.2, z, 1, 0.0, 0.0, 0.0, 0.0)
            val n = if (desperate) 10 else 8
            val turn = random.nextDouble() * 0.4
            for (k in 0 until n) {
                val a = turn + k * 2 * Math.PI / n
                val dir = Vec3d(f.x * cos(a) - f.z * sin(a), 0.0, f.x * sin(a) + f.z * cos(a))
                PitHazards.fissure(this, entityPos.add(dir.multiply(1.5)), dir, 14, 22f)
            }
            for (p in PitFight.targets(world)) if (p.squaredDistanceTo(entityPos) < 4.0 * 4.0) { breakShield(world, p); if (hurt(world, p, 36f)) knock(p, entityPos, 1.6, 0.7) }
            PitHazards.rockfall(this, world, if (desperate) 12 else 8, 30f)
            PitArena.smashWalls(world, entityPos, 3.5)
        }
        if (actionAge >= wind + 26) act(IDLE)
    }

    // ------------------------------------------------------------------------------------- stage 4: the hunt

    /** A line of his (group.1..count, no direct repeat). [force] ignores the pause between lines. */
    private fun say(world: ServerWorld, group: String, count: Int, force: Boolean) {
        if (lineCd > 0 && !force) return
        var n = 1 + random.nextInt(count)
        if (count > 1 && n == lastLine[group]) n = 1 + n % count
        lastLine[group] = n
        val key = "$group.$n"
        val t = PitLines.ticks(key)
        Cine.dialog(PitFight.players(world), Cine.SPEAKER_MINOTAUR, PitLines.key(key), t)
        lineCd = t + 30
    }

    /** Who he sees in the maze (checked every 4 ticks): not the ones in the blood, unless he bumps into them. */
    private fun seen(world: ServerWorld): PlayerEntity? {
        if (age % 4 != 0) {
            val c = seenCache
            return if (c != null && c.isAlive && !PitLabyrinth.isHidden(c)) c else null
        }
        seenCache = perceive(world)
        return seenCache
    }

    private fun perceive(world: ServerWorld): PlayerEntity? {
        val eye = eyePos
        val all = PitFight.targets(world)
        var best: PlayerEntity? = null
        var bestD = Double.MAX_VALUE
        for (p in all) {
            val d = p.squaredDistanceTo(entityPos)
            val sees = if (PitLabyrinth.isHidden(p)) d < 2.4 * 2.4 && kotlin.math.abs(p.y - y) < 3
                else d < 5.5 * 5.5 || (d < 48.0 * 48.0 && PitFight.lineOfSight(world, eye, p.eyePos))
            if (sees && d < bestD) { best = p; bestD = d }
        }
        val q = quarry?.let { id -> all.firstOrNull { it.uuid == id } }
        if (best != null) {
            if (PitLabyrinth.isHidden(best)) {
                // bumped into someone in the blood
                PitLabyrinth.reveal(world, best)
                (best as? net.minecraft.server.network.ServerPlayerEntity)?.let { PitFight.hint(it, "boss.hha_blood.hint.found") }
            }
            if (quarry != best.uuid && age - lastSeen > 80) {
                say(world, "found", 4, true)
                sound(BossSounds.MINOTAUR_ROAR, 3f, 1.1f)
                HhaBlood.LOGGER.info("[labyrinth] minotaur found {}", best.name.string)
            }
            quarry = best.uuid
            lastSeen = age
            searchPos = null
            return best
        }
        // a visible quarry is followed around corners for a moment, a hidden one is gone
        if (q != null && !PitLabyrinth.isHidden(q) && age - lastSeen < 70) return q
        if (q != null) { searchPos = q.entityPos; urgent = true; quarry = null }
        return null
    }

    /** Somebody sank into the blood (Ability 2 in the labyrinth). */
    fun onVanish(world: ServerWorld, p: PlayerEntity) {
        if (stage() != PitFight.LABYRINTH) return
        if (quarry == p.uuid || seenCache == p) {
            quarry = null
            seenCache = null
            searchPos = p.entityPos
            urgent = true
            say(world, "vanish", 3, true)
        }
    }

    /** A hit out of the blood: he reels (stunned), the attacker is seen again. */
    private fun backstabbed(world: ServerWorld, p: PlayerEntity) {
        sound(BossSounds.MINOTAUR_HURT, 4f, 0.6f)
        world.spawnParticles(BloodParticles.SPLAT, x, y + 2.5, z, 12, 0.6, 0.6, 0.6, 0.2)
        world.spawnParticles(ParticleTypes.CRIT, x, y + 2.5, z, 20, 0.6, 0.8, 0.6, 0.3)
        BloodFx.shake(world, entityPos, 16.0, 1.0f, 8)
        (p as? net.minecraft.server.network.ServerPlayerEntity)?.let { PitFight.hint(it, "boss.hha_blood.hint.backstab") }
        stun(world, 40)
        say(world, "backstab", 3, true)
        quarry = p.uuid
        lastSeen = age
        seenCache = p
        HhaBlood.LOGGER.info("[labyrinth] backstab by {}", p.name.string)
    }

    /** Nobody in sight: listens, follows noises and scents, wanders the corridors, taunts. */
    private fun hunt(world: ServerWorld) {
        if (age % 5 == 0) {
            val n = PitLabyrinth.listen(world, entityPos)
            if (n != null) {
                val d = horizontalDistance(n)
                if (d > 11.0 && cdCharge <= 0 && !axeOut) {
                    // roars and runs at the sound - straight through the walls
                    say(world, "heard", 3, true)
                    aim = n
                    searchPos = n
                    urgent = true
                    HhaBlood.LOGGER.info("[labyrinth] minotaur charges at a noise")
                    start(CHARGE_WINDUP)
                    return
                }
                if (!urgent) say(world, "heard", 3, false)
                searchPos = n
                urgent = true
            }
        }
        if (--huntTaunt <= 0) {
            huntTaunt = 220 + random.nextInt(160)
            say(world, "hunt", 10, false)
        }
        val goal = searchPos ?: wanderPos ?: PitLabyrinth.randomCell(random)?.also { wanderPos = it } ?: return
        if (goal != pathGoal || age % 30 == 0) {
            if (goal != pathGoal) pathSince = age
            navigation.startMovingTo(goal.x, goal.y, goal.z, if (urgent) 1.2 else 0.75)
            pathGoal = goal
        }
        val arrived = horizontalDistance(goal) < 2.6
        val stuck = navigation.isIdle && age - pathSince > 30
        if (arrived || stuck) {
            if (searchPos != null) {
                searchPos = null
                urgent = false
                start(SNIFF)
            } else {
                wanderPos = null
                if (cdSniff <= 0 && random.nextFloat() < 0.5f) start(SNIFF)
            }
        }
    }

    /** Stands still and sniffs, head swinging - then he has a hunch where someone is. */
    private fun tickSniff(world: ServerWorld) {
        navigation.stop()
        velocity = Vec3d(0.0, velocity.y, 0.0)
        val t = actionAge
        yaw += (sin(t * 0.22) * 5.0).toFloat()
        bodyYaw = yaw
        headYaw = yaw
        if (t % 9 == 2) {
            sound(BossSounds.MINOTAUR_SNORT, 1.8f, 1.3f + random.nextFloat() * 0.25f)
            val nose = entityPos.add(forward().multiply(1.2)).add(0.0, 3.4, 0.0)
            world.spawnParticles(ParticleTypes.CLOUD, nose.x, nose.y, nose.z, 3, 0.15, 0.05, 0.15, 0.02)
        }
        if (t == 30) {
            val p = PitLabyrinth.scent(world, entityPos, 26.0)
            if (p != null && random.nextFloat() < 0.75f) {
                searchPos = p.entityPos.add(random.nextDouble() * 6 - 3, 0.0, random.nextDouble() * 6 - 3)
                urgent = false
                if (random.nextFloat() < 0.5f) say(world, "scent", 4, false)
            } else wanderPos = PitLabyrinth.randomCell(random)
        }
        if (t >= 40) act(IDLE)
    }

    // ------------------------------------------------------------------------------------- stage 5: the berserker

    /** Alternating left/right hooks, then a committed overhead fist. A dodge leaves a long recovery. */
    private fun tickPunch(world: ServerWorld) {
        val t = actionAge
        val beats = intArrayOf(14, 28, 62)
        navigation.stop()
        if (t < 11 || t in 19..24 || t in 34..48) face(foeOrAim(), 7f)
        if (t == 11 || t == 25 || t == 59) {
            velocity = forward().multiply(0.32).add(0.0, velocity.y, 0.0)
            velocityDirty = true
            sound(net.minecraft.sound.SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, 2f, 0.65f)
        }
        for ((k, beat) in beats.withIndex()) if (t == beat) {
            val impact = entityPos.add(forward().multiply(2.7)).add(0.0, if (k == 2) 0.6 else 1.8, 0.0)
            world.spawnParticles(ParticleTypes.CLOUD, impact.x, impact.y, impact.z, 10, 0.35, 0.25, 0.35, 0.05)
            sound(BossSounds.IMPACT_HEAVY, 2.2f, 1.25f - k * 0.2f)
            for (p in PitFight.targets(world)) {
                if (!canSee(p) || !inCone(p.entityPos, entityPos, forward(), 4.8, 48.0)) continue
                if (k == 2) breakShield(world, p)
                if (hurt(world, p, if (k == 2) 30f else 18f, kind = BossDamage.CRUSH))
                    knock(p, entityPos, if (k == 2) 1.1 else 0.35, 0.25)
            }
            BloodFx.shake(world, impact, 14.0, if (k == 2) 0.9f else 0.35f, 5)
        }
        if (t >= 88) { act(IDLE); gcd = 6 }
    }

    /** A low windup, then the whole body drives an empty fist upward; punish the missed swing. */
    private fun tickUppercut(world: ServerWorld) {
        val t = actionAge
        navigation.stop()
        if (t < 13) face(foeOrAim(), 8f)
        if (t == 18) {
            sound(BossSounds.IMPACT_HEAVY, 2.8f, 1.1f)
            for (p in PitFight.targets(world)) {
                if (!canSee(p) || !inCone(p.entityPos, entityPos, forward(), 4.0, 42.0)) continue
                breakShield(world, p)
                if (hurt(world, p, 32f, kind = BossDamage.CRUSH)) knock(p, entityPos, 0.55, 1.0)
            }
            world.spawnParticles(ParticleTypes.CLOUD, x, y + 2.3, z, 15, 0.8, 0.6, 0.8, 0.08)
        }
        if (t >= 42) { act(IDLE); gcd = 4 }
    }

    /** Red steam off his back and shoulders, steaming breath. */
    private fun berserkAura(world: ServerWorld) {
        val f = forward()
        val side = Vec3d(f.z, 0.0, -f.x)
        if (age % 2 == 0) for (s in listOf(-1.0, 1.0)) {
            val p = entityPos.add(side.multiply(0.75 * s)).add(0.0, 3.3, 0.0).subtract(f.multiply(0.35))
            world.spawnParticles(ParticleTypes.WHITE_SMOKE, p.x, p.y, p.z, 0, (random.nextDouble() - 0.5) * 0.3, 1.0, (random.nextDouble() - 0.5) * 0.3, 0.09)
            world.spawnParticles(BloodFx.dust(0xB01010, 1.8f), p.x, p.y + 0.2, p.z, 1, 0.2, 0.25, 0.2, 0.0)
        }
        if (age % 5 == 0) {
            val nose = entityPos.add(f.multiply(1.0)).add(0.0, 3.35, 0.0)
            world.spawnParticles(ParticleTypes.WHITE_SMOKE, nose.x, nose.y, nose.z, 0, f.x, -0.2, f.z, 0.08)
        }
        if (rageTicks > 0 && age % 2 == 0) world.spawnParticles(BloodFx.dust(BloodFx.BRIGHT, 2.2f), x, y + 2.0, z, 3, 0.7, 1.2, 0.7, 0.0)
    }

    /** Three stomps, each shock ring wider than the last - the third one after a hop, with falling rocks. */
    private fun tickStomp(world: ServerWorld) {
        val beats = intArrayOf(18, 40, 76)
        val t = actionAge
        navigation.stop()
        if (t < beats[0] - 5) face(foeOrAim(), 7f)
        if (t == beats[2] - 13) {
            velocity = Vec3d(0.0, 0.5, 0.0)
            velocityDirty = true
            sound(BossSounds.MINOTAUR_ROAR, 3f, 1.0f)
        }
        for ((k, b) in beats.withIndex()) {
            if (t == b - 3) warnRing(world, entityPos, 3.0 + k, 0xFF2020)
            if (t != b) continue
            sound(BossSounds.IMPACT_HEAVY, 3f + k * 0.5f, 0.95f - k * 0.12f)
            if (k == 2) sound(BossSounds.IMPACT_HEAVY, 3.5f, 0.6f)
            BloodFx.shake(world, entityPos, 20.0 + k * 10, 1.0f + k * 0.6f, 8 + k * 4)
            world.spawnParticles(net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, dev.henny.hha_blood.hollows.HollowsBlocks.POLISHED_BLOOD_STONE.defaultState),
                x, y + 0.1, z, 30 + k * 20, 1.2, 0.1, 1.2, 0.3)
            if (k == 2) world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, x, y + 0.2, z, 1, 0.0, 0.0, 0.0, 0.0)
            PitHazards.shockRing(this, entityPos, 12.0 + k * 12.0, 0.6 + k * 0.08, 18f + k * 4f, false)
            for (p in PitFight.targets(world)) {
                if (p.squaredDistanceTo(entityPos) > (2.8 + k * 0.6) * (2.8 + k * 0.6)) continue
                breakShield(world, p)
                if (hurt(world, p, 22f + k * 6f)) knock(p, entityPos, 1.0 + k * 0.3, 0.5)
            }
            if (k == 2) {
                // The shock travels through the floor: jump it, then punish his recovery.
                PitArena.smashWalls(world, entityPos, 3.0)
            }
        }
        if (t >= beats[2] + 26) {
            act(IDLE)
            combo(SLAM, 40.0, 0.25f)
        }
    }

    /** Shoulder tackle: a short, very fast dash - whoever it hits flies (and may be grabbed after). */
    private fun tickTackle(world: ServerWorld) {
        val wind = w(10)
        val t = actionAge
        if (t < wind) {
            face(foeOrAim(), 16f)
            if (t % 3 == 0) world.spawnParticles(ParticleTypes.CLOUD, x, y + 0.1, z, 4, 0.6, 0.0, 0.6, 0.03)
            return
        }
        if (t == wind) {
            chargeDir = forward()
            sound(BossSounds.MINOTAUR_CHARGE, 2.5f, 1.3f)
        }
        val dash = t - wind
        if (dash < 9) {
            velocity = Vec3d(chargeDir.x * 1.55, velocity.y, chargeDir.z * 1.55)
            velocityDirty = true
            world.spawnParticles(ParticleTypes.CLOUD, x, y + 0.2, z, 2, 0.5, 0.1, 0.5, 0.02)
            for (p in PitFight.targets(world)) {
                if (p.uuid in hitThisAction || !p.boundingBox.intersects(boundingBox.expand(0.9))) continue
                hitThisAction += p.uuid
                breakShield(world, p, 120)
                if (hurt(world, p, 34f)) { struckAny = true; knock(p, entityPos, 2.0, 0.6) }
                sound(BossSounds.IMPACT_HEAVY, 2.5f, 1.2f)
            }
            PitArena.smashWalls(world, entityPos.add(chargeDir.multiply(1.4)), 1.6)
            val ahead = entityPos.add(chargeDir.multiply(1.6))
            val c = PitArena.center()
            val blocked = (c != null && HeartVaultPiece.pillarAt(ahead.x - c.x, ahead.z - c.z, 0.4) >= 0) || PitArena.radius(ahead) > HeartVaultPiece.FIGHT_R - 0.8
            if (!blocked) return
        }
        // the dash is over (or ran into a pillar / the stands)
        velocity = Vec3d(0.0, velocity.y, 0.0)
        if (struckAny) combo(GRAB, 6.0, 0.45f)
        act(IDLE)
        gcd = w(8)
    }

    /** War leap: a huge arc onto the target, a crater, two shock rings and a star of fissures. */
    private fun tickSlam(world: ServerWorld) {
        val wind = w(16)
        val t = actionAge
        if (t < wind) {
            face(foeOrAim(), 12f)
            if (t % 3 == 0) world.spawnParticles(ParticleTypes.CLOUD, x, y + 0.1, z, 5, 0.8, 0.0, 0.8, 0.04)
            return
        }
        if (t == wind) {
            slamTarget = PitArena.clampToFloor(foeOrAim(), 3.0)
            leapTo(slamTarget, 28)
            landed = false
            sound(BossSounds.MINOTAUR_ROAR, 3.5f, 0.8f)
            world.spawnParticles(ParticleTypes.EXPLOSION, x, y + 0.2, z, 1, 0.0, 0.0, 0.0, 0.0)
        }
        if (!landed) {
            if (t % 3 == 0) warnRing(world, slamTarget, 4.5, 0xFF2020)
            if (t > wind + 6 && (isOnGround || t > wind + 60)) {
                landed = true
                landAt = t
                sound(BossSounds.IMPACT_HEAVY, 4f, 0.6f)
                sound(BossSounds.IMPACT_HEAVY, 3.5f, 0.55f)
                BloodFx.shake(world, entityPos, 40.0, 2.8f, 22)
                BloodFx.screenFlash(world, entityPos, 24.0, 0xC01010, 0.25f, 8)
                world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, x, y + 0.2, z, 1, 0.0, 0.0, 0.0, 0.0)
                world.spawnParticles(net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, dev.henny.hha_blood.hollows.HollowsBlocks.POLISHED_BLOOD_STONE.defaultState),
                    x, y + 0.2, z, 80, 2.0, 0.3, 2.0, 0.4)
                for (p in PitFight.targets(world)) {
                    if (p.squaredDistanceTo(entityPos) < 4.0 * 4.0) { breakShield(world, p); if (hurt(world, p, 46f)) knock(p, entityPos, 1.8, 0.9) }
                }
                PitHazards.shockRing(this, entityPos, 9.0, 0.8, 24f, false)
                PitHazards.shockRing(this, entityPos, 30.0, 0.6, 22f, false)
                // His landing drives the floor wave; no unrelated rain of rocks.
                PitArena.smashWalls(world, entityPos, 3.5)
            }
        }
        if (landed && t > landAt + 28) {
            act(IDLE)
            if (desperate && slamChain < 1) { slamChain++; next = SLAM } else slamChain = 0
            return
        }
        if (t > wind + 100) act(IDLE)
    }

    /** Rage roar: everyone near is thrown back, his fury burns at its peak for 10 s. */
    private fun tickRage(world: ServerWorld) {
        val wind = w(14)
        val t = actionAge
        navigation.stop()
        velocity = Vec3d(0.0, velocity.y, 0.0)
        if (t < wind) {
            if (t % 3 == 0) warnRing(world, entityPos, 2.0 + 8.0 * t / wind, 0xFF3010)
            world.spawnParticles(ParticleTypes.WHITE_SMOKE, x, y + 3.2, z, 0, 0.0, 1.0, 0.0, 0.15)
            return
        }
        if (t == wind) {
            sound(BossSounds.MINOTAUR_ROAR, 5f, 0.6f)
            sound(BossSounds.IMPACT_HEAVY, 3f, 0.5f)
            BloodFx.shake(world, entityPos, 50.0, 2.2f, 24)
            BloodFx.screenFlash(world, entityPos, 40.0, 0xC01010, 0.35f, 10)
            world.spawnParticles(BloodFx.dust(BloodFx.BRIGHT, 2.5f), x, y + 2.0, z, 80, 2.0, 1.5, 2.0, 0.0)
            world.spawnParticles(ParticleTypes.LARGE_SMOKE, x, y + 1.5, z, 40, 1.5, 1.0, 1.5, 0.15)
            for (p in PitFight.targets(world)) {
                if (p.squaredDistanceTo(entityPos) > 10.0 * 10.0) continue
                breakShield(world, p)
                hurt(world, p, 14f, kind = BossDamage.HELLFIRE)
                knock(p, entityPos, 2.6, 0.7)
            }
            rageTicks = 200
            fury = MAX_FURY
        }
        if (t >= wind + 22) { act(IDLE); gcd = 0 }
    }

    /** Both empty fists hammer the floor: a warned impact, widening shockwaves and falling rubble. */
    private fun tickSplit(world: ServerWorld) {
        val wind = splitWind
        val t = actionAge
        navigation.stop()
        velocity = Vec3d(0.0, velocity.y, 0.0)
        val tip = entityPos.add(forward().multiply(2.5))
        if (t < wind - 4) face(foeOrAim(), 6f)
        if (t < wind && t % 3 == 0) warnRing(world, tip, 3.5, 0xFF3010)
        if (t == wind) {
            sound(BossSounds.IMPACT_HEAVY, 4f, 0.55f)
            sound(BossSounds.MINOTAUR_GRUNT, 3f, 0.6f)
            BloodFx.shake(world, tip, 35.0, 2.0f, 18)
            world.spawnParticles(net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK,
                dev.henny.hha_blood.hollows.HollowsBlocks.POLISHED_BLOOD_STONE.defaultState),
                tip.x, tip.y + 0.1, tip.z, 70, 1.5, 0.15, 1.5, 0.3)
            for (p in PitFight.targets(world)) {
                if (p.squaredDistanceTo(tip) > 3.5 * 3.5) continue
                breakShield(world, p)
                if (hurt(world, p, 36f, kind = BossDamage.CRUSH)) knock(p, tip, 1.3, 0.65)
            }
            PitHazards.shockRing(this, tip, 14.0, 0.65, 22f, false)
            splitFrom = Vec3d(tip.x, HeartVaultPiece.FLOOR.toDouble(), tip.z)
        }
        // Fingers buried in the floor: dust warns of the delayed earth-rip, then he tears upward.
        if (t in wind + 1 until wind + 20 && t % 3 == 0) {
            warnRing(world, splitFrom, 5.0 + (t - wind) * 0.6, 0xC08050)
            world.spawnParticles(ParticleTypes.CLOUD, splitFrom.x, splitFrom.y + 0.2, splitFrom.z, 12, 2.5, 0.1, 2.5, 0.03)
        }
        if (t == wind + 20) {
            sound(BossSounds.IMPACT_HEAVY, 4f, 0.45f)
            BloodFx.shake(world, splitFrom, 55.0, 2f, 16)
            PitHazards.shockRing(this, splitFrom, 40.0, 0.7, 22f, false)
        }
        if (t >= wind + 48) { act(IDLE); gcd = 6 }
    }
    override fun onDeath(damageSource: DamageSource) {
        super.onDeath(damageSource)
        PitFight.onBossRemoved(this)
    }

    override fun getAmbientSound() = if (scripted || action == THRONE) null else BossSounds.MINOTAUR_SNORT
    override fun getHurtSound(source: DamageSource) = BossSounds.MINOTAUR_HURT
    override fun getDeathSound() = BossSounds.MINOTAUR_DEATH
    override fun getSoundVolume(): Float = 2.5f
    override fun getMinAmbientSoundDelay(): Int = 200
}
