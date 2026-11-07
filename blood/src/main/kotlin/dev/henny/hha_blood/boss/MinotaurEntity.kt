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
 * Stage 4 (Labyrinth): walls rise, whirlwind, thrown axe, leap slam. Stage 5 (the Cerberus' fire in him):
 * burning charges in a row, the paralysing roar and a fiery snort.
 */
class MinotaurEntity(type: EntityType<out MinotaurEntity>, world: World) : PitBoss(type, world) {

    companion object {
        val FURY: TrackedData<Int> = DataTracker.registerData(MinotaurEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        val AXE_OUT: TrackedData<Boolean> = DataTracker.registerData(MinotaurEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)
        val BURNING: TrackedData<Boolean> = DataTracker.registerData(MinotaurEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)

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

        /** Vanilla caps max health at 1024 - [toughness] makes him take 20 % less (about 1280 effective). */
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
    private fun w(ticks: Int): Int = (ticks * (1.0 - 0.035 * fury) * (if (burning) 0.85 else 1.0)).toInt().coerceAtLeast(5)

    fun stage(): Int = PitFight.stage

    /** Every fury stack hits 4 % harder, the Cerberus' fire another 10 %. */
    override fun damageBonus(): Float = 1f + 0.04f * fury + (if (burning) 0.1f else 0f)

    override fun toughness(): Float = 0.8f

    override fun onPlayerHit(p: PlayerEntity) {
        if (scripted) return
        fury += 1
        lastHitTick = age
        if (fury % 3 == 0) sound(BossSounds.MINOTAUR_SNORT, 2f, 0.9f)
    }

    override fun onHurtBy(source: net.minecraft.entity.damage.DamageSource, amount: Float) {
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
        if (grabbed != null && action != GRAB) releaseGrab()
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
        val target = PitFight.nearestTarget(world, entityPos)
        foe = target
        when (action) {
            IDLE -> idle(world, target)
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
            LABYRINTH -> tickLabyrinth(world)
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
        if (axeOut) {
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
        val choice = when {
            s >= 4 && cdLabyrinth <= 0 && !PitHazards.wallsUp() -> LABYRINTH
            s >= 5 && cdQuake <= 0 && d < 20 -> QUAKE
            s >= 5 && cdHowl <= 0 && d < 16 -> HOWL_ROAR
            // a raised shield invites the grab ("bricht durch die Deckung")
            cdGrab <= 0 && d < 4.2 && (target.isBlocking || random.nextFloat() < 0.35f) -> GRAB
            d < 3.4 && cdGore <= 0 -> GORE
            d < 5.2 && cdCleave <= 0 -> CLEAVE
            d < 5.0 && cdSweep <= 0 -> SWEEP
            PitHazards.wallsUp() && cdCharge <= 0 && d > 9.0 -> CHARGE_WINDUP
            s >= 5 && cdSnort <= 0 && d in 3.0..9.0 -> SNORT
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
        navigation.stop()
        hitThisAction.clear()
        act(choice)
        when (choice) {
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
            GRAB -> { cdGrab = 260; gcd = w(20); grabDamage = 0f; sound(BossSounds.MINOTAUR_GRUNT, 2.5f, 0.75f) }
            QUAKE -> { cdQuake = if (desperate) 260 else 400; gcd = w(24); sound(BossSounds.MINOTAUR_ROAR, 3.5f, 0.75f) }
        }
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
                if (!inCone(p.entityPos, entityPos, f, 5.4, 32.0)) continue
                breakShield(world, p)
                if (hurt(world, p, 45f, bleedTicks = 80)) { struck = true; knock(p, entityPos, 0.6, 0.3) }
            }
            PitHazards.fissure(this, impact, f, if (stage() >= 4) 10 else 7, 20f)
            PitArena.smashWalls(world, impact, 2.0)
            if (struck) combo(SWEEP, 5.6, 0.45f)
            else if (!desperate || random.nextFloat() < 0.5f) { act(STUCK); return }
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
        if (actionAge == wind + 4) combo(GORE, 3.8, 0.3f)
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
        if (actionAge >= STUCK_TICKS) {
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

    private fun tickLabyrinth(world: ServerWorld) {
        if (actionAge == 1) sound(BossSounds.MINOTAUR_ROAR, 3.5f, 0.9f)
        if (actionAge == 18) {
            sound(BossSounds.AXE_IMPACT, 3.5f, 0.6f)
            BloodFx.shake(world, entityPos, 60.0, 1.8f, 30)
            PitHazards.raiseWalls(world, this, 360)
            for (p in PitFight.targets(world)) if (p is net.minecraft.server.network.ServerPlayerEntity) PitFight.hint(p, "boss.hha_blood.hint.labyrinth")
        }
        if (actionAge >= 40) { act(IDLE); cdCharge = 80 }
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
        val wind = w(GRAB_WIND)
        if (actionAge < wind) { face(foeOrAim(), 14f); return }
        if (actionAge == wind) {
            val victim = PitFight.targets(world)
                .filter { inCone(it.entityPos, entityPos, forward(), 4.4, 50.0) && kotlin.math.abs(it.y - y) < 2.5 }
                .minByOrNull { it.squaredDistanceTo(entityPos) }
            if (victim == null) {
                // grabbed nothing: stumbles forward
                velocity = forward().multiply(0.4)
                sound(BossSounds.AXE_SWING, 2f, 0.5f)
                gcd = w(10)
                act(IDLE)
                return
            }
            grabbed = victim.uuid
            grabDamage = 0f
            breakShield(world, victim, 120)
            sound(BossSounds.MINOTAUR_ROAR, 3f, 1.1f)
            if (victim is net.minecraft.server.network.ServerPlayerEntity) PitFight.hint(victim, "boss.hha_blood.hint.grab")
            for (p in PitFight.targets(world)) if (p != victim && p is net.minecraft.server.network.ServerPlayerEntity) PitFight.hint(p, "boss.hha_blood.hint.grab_other")
        }
        val id = grabbed
        val victim = id?.let { g -> PitFight.targets(world).firstOrNull { it.uuid == g } }
        if (victim == null) {
            if (actionAge > wind) { releaseGrab(); act(IDLE) }
            return
        }
        val t = actionAge - wind
        if (t < GRAB_HOLD) {
            // held up high, crushed a little
            face(victim.entityPos, 0f)
            val hold = fistPos().add(0.0, -0.9, 0.0)
            victim.teleport(world, hold.x, hold.y, hold.z, emptySet(), victim.yaw, victim.pitch, false)
            victim.velocity = Vec3d.ZERO
            victim.velocityDirty = true
            victim.fallDistance = 0.0
            if (t % 8 == 4) {
                hurt(world, victim, 5f, ignoreFrames = true)
                world.spawnParticles(BloodParticles.SPLAT, victim.x, victim.y + 1.0, victim.z, 4, 0.3, 0.3, 0.3, 0.1)
                sound(BossSounds.MINOTAUR_SNORT, 2f, 0.7f)
            }
            return
        }
        if (t == GRAB_HOLD) {
            // the slam
            val ground = PitArena.clampToFloor(entityPos.add(forward().multiply(2.4)), 2.0)
            victim.teleport(world, ground.x, ground.y, ground.z, emptySet(), victim.yaw, victim.pitch, false)
            victim.velocity = Vec3d.ZERO
            victim.velocityDirty = true
            victim.fallDistance = 0.0
            grabbed = null
            hurt(world, victim, 42f, bleedTicks = 100, ignoreFrames = true)
            sound(BossSounds.IMPACT_HEAVY, 3.5f, 0.7f)
            sound(BossSounds.AXE_IMPACT, 2.5f, 0.6f)
            BloodFx.shake(world, ground, 24.0, 2.0f, 14)
            world.spawnParticles(ParticleTypes.EXPLOSION, ground.x, ground.y + 0.3, ground.z, 2, 0.6, 0.2, 0.6, 0.0)
            world.spawnParticles(net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, dev.henny.hha_blood.hollows.HollowsBlocks.POLISHED_BLOOD_STONE.defaultState),
                ground.x, ground.y + 0.2, ground.z, 40, 1.0, 0.2, 1.0, 0.3)
            PitHazards.shockRing(this, ground, 8.0, 0.55, 16f, burning)
        }
        if (t >= GRAB_HOLD + 16) act(IDLE)
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
