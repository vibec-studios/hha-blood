package dev.henny.hha_blood.boss

import dev.henny.hha_blood.BloodParticles
import dev.henny.hha_blood.logic.BloodFx
import net.minecraft.entity.EntityType
import net.minecraft.entity.attribute.DefaultAttributeContainer
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import kotlin.math.cos
import kotlin.math.sin

/**
 * Blood Cerberus - "Der dreiköpfige Wächter", the Minotaur's hound (stages 1 and 2 of the Slaughter Pit).
 * The right head (`bleed`) tears wounds that bleed, the middle head (`flame`) breathes fire, the left head
 * (`howl`) paralyses everyone who can see it. It chains its attacks: a howl is followed by a pounce on whoever
 * froze, a pounce by a snap of all three heads. Every head spits homing hellfire orbs at players who keep their
 * distance. Unleashed (stage 2) it burns: faster, a fire trail, fire novas, blood geysers and the ring of fire -
 * a closing circle of flames around a player, then it leaps inside and breathes with all three heads.
 */
class CerberusEntity(type: EntityType<out CerberusEntity>, world: World) : PitBoss(type, world) {

    companion object {
        val UNLEASHED: TrackedData<Boolean> = DataTracker.registerData(CerberusEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)
        /** Bit i set = head i (0 bleed, 1 flame, 2 howl) is broken: hangs limp, no glow, its attacks are gone. */
        val BROKEN: TrackedData<Int> = DataTracker.registerData(CerberusEntity::class.java, TrackedDataHandlerRegistry.INTEGER)

        const val SLEEP = 0
        const val WAKE = 1
        const val IDLE = 2
        const val BITE = 3
        const val BREATH = 4
        const val HOWL = 5
        const val POUNCE = 6
        const val SNAP3 = 7
        const val CHARGE = 8
        const val NOVA = 9
        const val GEYSERS = 10
        const val TRIPLE_BREATH = 11
        const val COLLAPSE = 12
        const val RISE = 13
        const val DEATH = 14
        const val GROWL = 15
        const val FIRE_RING = 16
        const val ORBS = 17

        const val MAX_HEALTH = 760.0
        /** Model scale used by the renderer (model units: 32 = 1 block before this). */
        const val RENDER_SCALE = 0.62f

        /** FIRE_RING timeline (shared with the renderer). */
        const val RING_WIND = 16
        const val RING_LEAP = 30
        const val RING_END = 56
        /** Ticks between the three spits of ORBS (before the unleashed factor). */
        const val ORB_STEP = 8

        fun createAttributes(): DefaultAttributeContainer.Builder = createHostileAttributes()
            .add(EntityAttributes.MAX_HEALTH, MAX_HEALTH)
            .add(EntityAttributes.ATTACK_DAMAGE, 10.0)
            .add(EntityAttributes.ARMOR, 10.0)
            .add(EntityAttributes.ARMOR_TOUGHNESS, 4.0)
            .add(EntityAttributes.MOVEMENT_SPEED, 0.36)
            .add(EntityAttributes.KNOCKBACK_RESISTANCE, 1.0)
            .add(EntityAttributes.FOLLOW_RANGE, 80.0)
            .add(EntityAttributes.STEP_HEIGHT, 1.6)
    }

    private var cdBite = 0
    private var cdSnap = 40
    private var cdBreath = 60
    private var cdHowl = 160
    private var cdPounce = 40
    private var cdCharge = 80
    private var cdNova = 100
    private var cdGeysers = 140
    private var cdTriple = 160
    private var cdRing = 120
    private var cdOrbs = 60
    private var gcd = 20
    /** Forced follow-up attack (combo), -1 = none. */
    private var next = -1
    private var foe: PlayerEntity? = null
    private var aim = Vec3d.ZERO
    private val hitThisAction = HashSet<java.util.UUID>()
    private var landed = false
    private var ringCenter = Vec3d.ZERO
    private var biteHead = 0
    private var snapHeads: List<Int> = listOf(0, 1, 2)

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(UNLEASHED, false)
        builder.add(BROKEN, 0)
    }

    var unleashed: Boolean
        get() = dataTracker.get(UNLEASHED)
        set(v) = dataTracker.set(UNLEASHED, v)

    /** Broken heads as a bit mask (see [BROKEN]). */
    var brokenMask: Int
        get() = dataTracker.get(BROKEN)
        set(v) = dataTracker.set(BROKEN, v and 7)

    fun isHeadBroken(i: Int): Boolean = (brokenMask shr i) and 1 == 1

    // ------------------------------------------------------------------------------------- heads (fight state)

    /** Health of each head this stage (server only). The entity's health only mirrors their sum for the bar. */
    val headHp = FloatArray(3)
    var headMax = 1f
        private set

    fun aliveHeads(): List<Int> = (0..2).filter { !isHeadBroken(it) }

    fun brokenCount(): Int = 3 - aliveHeads().size

    /** All three heads whole again with [max] health each (stage start). */
    fun resetHeads(max: Float) {
        headMax = max
        for (i in 0..2) headHp[i] = max
        brokenMask = 0
        syncHealth()
    }

    /** The entity's health shows how much of the three heads is left (never 0 - the fight ends it). */
    fun syncHealth() {
        val left = (0..2).sumOf { if (isHeadBroken(it)) 0.0 else headHp[it].coerceAtLeast(0f).toDouble() }
        health = (maxHealth * (left / (3.0 * headMax))).toFloat().coerceIn(1f, maxHealth)
    }

    override fun onHurtBy(source: net.minecraft.entity.damage.DamageSource, amount: Float) {
        // vanilla only took a token hit (hurt flash + sound) - the heads decide the real health
        if (PitFight.cerberusFighting()) syncHealth()
    }

    /**
     * Which living head a hit lands on, and whether it is a clean head hit. Melee: the attacker's line of sight
     * (passes within 1.25 blocks of a head). Projectiles and abilities: where the hit came from (within 1.7).
     * Everything else hits the body - it goes to the nearest living head, but only weakly.
     */
    fun headFor(source: net.minecraft.entity.damage.DamageSource): Pair<Int, Boolean> {
        val alive = aliveHeads()
        if (alive.isEmpty()) return -1 to false
        val attacker = source.attacker
        val direct = source.source
        val dist: (Int) -> Double
        val limit: Double
        if (attacker is net.minecraft.entity.LivingEntity && (direct == null || direct === attacker)) {
            val from = attacker.eyePos
            val to = from.add(attacker.rotationVector.multiply(7.0))
            dist = { i -> segmentDistance(headPos(i), from, to) }
            limit = 1.4
        } else {
            val at = direct?.let { it.entityPos.add(0.0, it.height * 0.5, 0.0) } ?: source.position ?: attacker?.entityPos ?: entityPos
            dist = { i -> headPos(i).distanceTo(at) }
            limit = 1.7
        }
        val best = alive.minBy(dist)
        return best to (dist(best) <= limit)
    }

    private fun segmentDistance(p: Vec3d, a: Vec3d, b: Vec3d): Double {
        val ab = b.subtract(a)
        val t = (p.subtract(a).dotProduct(ab) / ab.lengthSquared().coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
        return p.distanceTo(a.add(ab.multiply(t)))
    }

    /** A head just broke: it stumbles for a moment. */
    fun stagger() {
        navigation.stop()
        velocity = Vec3d.ZERO
        next = -1
        act(GROWL)
        gcd = 30
    }

    /** A random living head (for bites). */
    private fun anyHead(): Int = aliveHeads().let { if (it.isEmpty()) 1 else it[random.nextInt(it.size)] }

    override fun resetAction() {
        navigation.stop()
        velocity = Vec3d.ZERO
        next = -1
        act(IDLE)
    }

    override fun refreshStats() {
        getAttributeInstance(EntityAttributes.MAX_HEALTH)?.baseValue = MAX_HEALTH
        getAttributeInstance(EntityAttributes.MOVEMENT_SPEED)?.baseValue = 0.36
    }

    /** Head positions in world space (index 0 = bleed/right, 1 = flame/middle, 2 = howl/left). */
    fun headPos(i: Int): Vec3d {
        val f = forward()
        val side = Vec3d(f.z, 0.0, -f.x)          // the entity's left
        // rest pose of the model (GenCerberus); a broken head hangs limp, lower and further out
        val broken = isHeadBroken(i)
        val lateral = when (i) { 0 -> -1.09; 2 -> 1.09; else -> 0.0 } * (if (broken) 1.2 else 1.0)
        val up = if (broken) 1.8 else if (i == 1) 3.33 else 3.02
        val fwd = if (broken) 1.6 else when (i) { 0 -> 2.29; 1 -> 2.36; else -> 2.23 }
        return entityPos.add(f.multiply(fwd)).add(side.multiply(lateral)).add(0.0, up, 0.0)
    }

    private fun speedFactor(): Double = if (unleashed) 0.75 else 1.0

    private fun w(ticks: Int): Int = (ticks * speedFactor()).toInt().coerceAtLeast(4)

    // ------------------------------------------------------------------------------------- AI

    override fun mobTick(world: ServerWorld) {
        super.mobTick(world)
        if (scripted) { navigation.stop(); return }
        if (!PitFight.cerberusFighting()) {
            navigation.stop()
            return
        }
        cdBite--; cdSnap--; cdBreath--; cdHowl--; cdPounce--; cdCharge--; cdNova--; cdGeysers--; cdTriple--; cdRing--; cdOrbs--; gcd--
        if (unleashed && age % 4 == 0) {
            world.spawnParticles(BloodParticles.FLAME, x, y + 1.6, z, 3, 1.0, 0.6, 1.4, 0.01)
            if (age % 8 == 0) world.spawnParticles(ParticleTypes.LARGE_SMOKE, x, y + 2.0, z, 1, 0.8, 0.3, 1.0, 0.01)
        }
        // unleashed: its paws leave burning tracks (no kiting in circles)
        if (unleashed && action == IDLE && age % 7 == 0 && velocity.horizontalLengthSquared() > 0.01) {
            PitHazards.fire(this, Vec3d(x, PitArena.mid().y, z), 1.1, 70, 4f)
        }
        val target = PitFight.nearestTarget(world, entityPos)
        foe = target
        when (action) {
            IDLE -> idle(world, target)
            BITE -> tickBite(world)
            SNAP3 -> tickSnap(world)
            BREATH -> tickBreath(world)
            HOWL -> tickHowl(world)
            POUNCE -> tickPounce(world)
            CHARGE -> tickCharge(world)
            NOVA -> tickNova(world)
            GEYSERS -> tickGeysers(world)
            TRIPLE_BREATH -> tickTriple(world)
            FIRE_RING -> tickRing(world)
            ORBS -> tickOrbs(world)
            GROWL -> { navigation.stop(); if (actionAge > 36) act(IDLE) }
            else -> act(IDLE)
        }
        if (age % 26 == 0 && limbAnimator.speed > 0.2f) sound(BossSounds.CERBERUS_STEP, 1.4f, 0.9f + random.nextFloat() * 0.2f)
    }

    private fun idle(world: ServerWorld, target: PlayerEntity?) {
        if (target == null) { navigation.stop(); return }
        val d = horizontalDistance(target.entityPos)
        face(target.entityPos, 14f)
        if (next >= 0) {
            val n = next
            next = -1
            start(n, target)
            return
        }
        if (gcd > 0) {
            chase(target, d)
            return
        }
        val u = unleashed
        val choice = when {
            u && cdNova <= 0 && d < 8 -> NOVA
            u && cdRing <= 0 && d < 22 && !isHeadBroken(1) -> FIRE_RING
            d < 5.0 && cdSnap <= 0 && aliveHeads().size >= 2 -> SNAP3
            d < 4.5 && cdBite <= 0 -> BITE
            u && cdTriple <= 0 && d in 4.0..13.0 && aliveHeads().size >= 2 -> TRIPLE_BREATH
            cdHowl <= 0 && d < 15.0 && !isHeadBroken(2) && PitFight.lineOfSight(world, headPos(2), target.eyePos) -> HOWL
            u && cdGeysers <= 0 -> GEYSERS
            cdBreath <= 0 && d in 3.5..12.0 && !isHeadBroken(1) -> BREATH
            cdOrbs <= 0 && d > 9.0 -> ORBS
            cdPounce <= 0 && d in 6.0..22.0 -> POUNCE
            cdCharge <= 0 && d > 10.0 -> CHARGE
            else -> -1
        }
        if (choice < 0) { chase(target, d); return }
        start(choice, target)
    }

    private fun start(choice: Int, target: PlayerEntity) {
        val u = unleashed
        navigation.stop()
        hitThisAction.clear()
        aim = target.entityPos
        act(choice)
        when (choice) {
            BITE -> { cdBite = 18; gcd = w(12); biteHead = anyHead() }
            SNAP3 -> { cdSnap = 80; gcd = w(16); snapHeads = aliveHeads() }
            BREATH -> { cdBreath = 130; gcd = w(18); sound(BossSounds.CERBERUS_GROWL, 2.5f, 0.9f) }
            HOWL -> { cdHowl = if (u) 200 else 240; gcd = w(10) }
            POUNCE -> { cdPounce = 80; gcd = w(12) }
            CHARGE -> { cdCharge = 120; gcd = w(18); sound(BossSounds.CERBERUS_GROWL, 3f, 0.8f) }
            NOVA -> { cdNova = 180; gcd = w(22) }
            GEYSERS -> { cdGeysers = 220; gcd = w(16) }
            TRIPLE_BREATH -> { cdTriple = 200; gcd = w(24); sound(BossSounds.CERBERUS_GROWL, 3f, 0.7f) }
            FIRE_RING -> { cdRing = 340; cdTriple = 200; gcd = w(20); sound(BossSounds.CERBERUS_HOWL, 3f, 0.6f) }
            ORBS -> { cdOrbs = if (u) 110 else 140; gcd = w(14); sound(BossSounds.CERBERUS_GROWL, 2.5f, 1.1f) }
        }
    }

    private fun chase(target: PlayerEntity, d: Double) {
        if (d > 3.2) navigation.startMovingTo(target.x, target.y, target.z, if (unleashed) 1.4 else 1.15)
        else navigation.stop()
    }

    private fun foeOrAim(): Vec3d = foe?.entityPos ?: aim

    // ------------------------------------------------------------------------------------- bites

    private fun tickBite(world: ServerWorld) {
        val wind = w(10)
        if (actionAge < wind) face(foeOrAim(), 15f)
        if (actionAge == wind) {
            velocity = forward().multiply(0.6).add(0.0, 0.1, 0.0)
            sound(BossSounds.CERBERUS_BITE, 2.2f, 0.9f)
        }
        if (actionAge == wind + 2) biteHit(world, biteHead, 21f)
        if (actionAge >= wind + 10) act(IDLE)
    }

    /** One head snaps in front of it: [head] decides the extra effect. */
    private fun biteHit(world: ServerWorld, head: Int, dmg: Float) {
        val from = headPos(head)
        for (p in PitFight.targets(world)) {
            if (p.squaredDistanceTo(from) > 3.3 * 3.3 && !inCone(p.entityPos, entityPos, forward(), 4.3, 40.0)) continue
            breakShield(world, p, 60)
            when (head) {
                0 -> hurt(world, p, dmg, bleedTicks = 120)
                1 -> hurt(world, p, dmg, fireSeconds = 4f)
                else -> if (hurt(world, p, dmg)) p.addStatusEffect(net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.SLOWNESS, 50, 2))
            }
            world.spawnParticles(BloodParticles.SPLAT, p.x, p.y + 1.0, p.z, 4, 0.3, 0.3, 0.3, 0.1)
        }
        world.spawnParticles(ParticleTypes.CRIT, from.x, from.y, from.z, 6, 0.3, 0.2, 0.3, 0.1)
    }

    private fun tickSnap(world: ServerWorld) {
        val step = w(8)
        if (actionAge < step) face(foeOrAim(), 12f)
        for ((k, i) in snapHeads.withIndex()) {
            if (actionAge == step * (k + 1)) {
                // the living heads one after another (bleed, flame, howl)
                if (isHeadBroken(i)) continue
                velocity = forward().multiply(0.35)
                sound(BossSounds.CERBERUS_BITE, 2.2f, 0.8f + i * 0.15f)
                biteHit(world, i, 13f)
                face(foeOrAim(), 25f)
            }
        }
        if (actionAge >= step * snapHeads.size + 8) act(IDLE)
    }

    // ------------------------------------------------------------------------------------- breath

    private fun tickBreath(world: ServerWorld) {
        if (isHeadBroken(1)) { act(IDLE); return }
        val wind = w(16)
        if (actionAge < wind) {
            face(foeOrAim(), 10f)
            val h = headPos(1)
            if (actionAge % 2 == 0) world.spawnParticles(ParticleTypes.FLAME, h.x, h.y, h.z, 3, 0.2, 0.2, 0.2, 0.02)
            return
        }
        val t = actionAge - wind
        if (t == 0) sound(BossSounds.CERBERUS_FLAME, 3f, 1f)
        if (t < 48) {
            // the head follows the target - run sideways, and faster than before
            face(foeOrAim(), if (unleashed) 3.4f else 2.7f)
            val dir = forward().add(0.0, -0.18, 0.0)
            PitHazards.flameTick(world, this, headPos(1), dir, 13.0, 20.0, t, if (unleashed) 9f else 7f)
            if (t % 8 == 5) {
                val ground = entityPos.add(forward().multiply(7.0 + random.nextDouble() * 5.0))
                PitHazards.fire(this, Vec3d(ground.x, y, ground.z), 1.8, if (unleashed) 140 else 100)
            }
            if (t == 24) sound(BossSounds.CERBERUS_FLAME, 2.5f, 0.9f)
        }
        if (t >= 54) act(IDLE)
    }

    private fun tickTriple(world: ServerWorld) {
        val wind = w(18)
        if (actionAge < wind) {
            face(foeOrAim(), 10f)
            for (i in 0..2) { val h = headPos(i); world.spawnParticles(ParticleTypes.FLAME, h.x, h.y, h.z, 1, 0.15, 0.15, 0.15, 0.01) }
            return
        }
        val t = actionAge - wind
        if (t == 0) { sound(BossSounds.CERBERUS_FLAME, 3.5f, 0.85f); sound(BossSounds.CERBERUS_HOWL, 1.5f, 1.3f) }
        if (t < 52) {
            // all three heads sweep from right to left
            val sweep = Math.toRadians(-40.0 + 80.0 * t / 52.0)
            for (i in 0..2) {
                if (isHeadBroken(i)) continue
                val off = Math.toRadians(intArrayOf(-28, 0, 28)[i].toDouble()) + sweep
                val f = forward()
                val dir = Vec3d(f.x * cos(off) - f.z * sin(off), -0.15, f.x * sin(off) + f.z * cos(off))
                PitHazards.flameTick(world, this, headPos(i), dir, 11.0, 12.0, t + i, 8f, effect = intArrayOf(1, 0, 2)[i])
            }
        }
        if (t >= 58) act(IDLE)
    }

    // ------------------------------------------------------------------------------------- howl

    private fun tickHowl(world: ServerWorld) {
        if (isHeadBroken(2)) { act(IDLE); return }
        val wind = w(22)
        val radius = 16.0
        if (actionAge < wind) {
            face(foeOrAim(), 8f)
            if (actionAge % 4 == 0) warnRing(world, entityPos, radius * actionAge / wind, 0x8A40FF)
            return
        }
        if (actionAge == wind) {
            sound(BossSounds.CERBERUS_HOWL, 4f, 1f)
            val head = headPos(2)
            PitHazards.shockRing(this, entityPos, radius, 0.9, 0f, false)
            var frozen: PlayerEntity? = null
            for (p in PitFight.targets(world)) {
                if (p.squaredDistanceTo(entityPos) > radius * radius) continue
                // whoever has a pillar or a wall between himself and the howl is safe
                if (!PitFight.lineOfSight(world, head, p.eyePos)) continue
                PitFight.paralyze(world, p, if (unleashed) 55 else 45)
                hurt(world, p, 10f, kind = BossDamage.HELLFIRE)
                if (frozen == null) frozen = p
            }
            // combo: it pounces on whoever froze
            if (frozen != null) { next = POUNCE; foe = frozen; aim = frozen.entityPos }
        }
        if (actionAge >= wind + 14) act(IDLE)
    }

    // ------------------------------------------------------------------------------------- pounce

    private fun tickPounce(world: ServerWorld) {
        val wind = w(12)
        if (actionAge < wind) { face(foeOrAim(), 15f); return }
        if (actionAge == wind) {
            // aims where the target will be
            val lead = foe?.velocity?.multiply(12.0) ?: Vec3d.ZERO
            val target = PitArena.clampToFloor(foeOrAim().add(lead.x, 0.0, lead.z), 3.0)
            leapTo(target, 16)
            landed = false
            sound(BossSounds.CERBERUS_BARK, 3f, 0.8f)
        }
        if (actionAge > wind + 4 && !landed && (isOnGround || actionAge > wind + 40)) {
            landed = true
            sound(BossSounds.IMPACT_HEAVY, 2.2f, 1.2f)
            BloodFx.shake(world, entityPos, 14.0, 1.2f, 10)
            world.spawnParticles(ParticleTypes.EXPLOSION, x, y + 0.3, z, 2, 1.0, 0.2, 1.0, 0.0)
            var close = false
            for (p in PitFight.targets(world)) {
                val dsq = p.squaredDistanceTo(entityPos)
                if (dsq < 3.8 * 3.8) {
                    breakShield(world, p, 60)
                    if (hurt(world, p, 26f)) knock(p, entityPos, 1.0, 0.5)
                }
                if (dsq < 5.0 * 5.0) close = true
            }
            if (unleashed) PitHazards.shockRing(this, entityPos, 7.0, 0.5, 18f, true)
            // combo: whoever is still close gets the three heads
            if (close) next = if (cdSnap <= 20) SNAP3 else BITE
        }
        if (landed && actionAge > wind + 22) act(IDLE)
        if (actionAge > wind + 60) act(IDLE)
    }

    // ------------------------------------------------------------------------------------- charge

    private fun tickCharge(world: ServerWorld) {
        val wind = w(18)
        if (actionAge < wind) {
            face(foeOrAim(), 12f)
            if (actionAge % 3 == 0) {
                val f = forward()
                for (k in 1..12) {
                    val p = entityPos.add(f.multiply(k * 2.0))
                    world.spawnParticles(BloodFx.dust(0xFF2020, 1.2f), p.x, y + 0.15, p.z, 1, 0.1, 0.0, 0.1, 0.0)
                }
                world.spawnParticles(ParticleTypes.CLOUD, x, y + 0.2, z, 3, 0.8, 0.0, 0.8, 0.02)
            }
            return
        }
        val t = actionAge - wind
        if (t == 0) sound(BossSounds.CERBERUS_BARK, 3f, 0.7f)
        if (t < 28) {
            // steers a little after its target
            if (t < 14) face(foeOrAim(), 2.5f)
            val f = forward()
            val sp = if (unleashed) 1.15 else 0.95
            velocity = Vec3d(f.x * sp, velocity.y, f.z * sp)
            velocityDirty = true
            if (t % 5 == 0) sound(BossSounds.CERBERUS_STEP, 1.6f, 1.2f)
            if (unleashed && t % 3 == 0) PitHazards.fire(this, entityPos, 1.5, 110)
            for (p in PitFight.targets(world)) {
                if (p.uuid in hitThisAction) continue
                if (p.boundingBox.intersects(boundingBox.expand(0.6))) {
                    hitThisAction += p.uuid
                    breakShield(world, p)
                    if (hurt(world, p, 30f, bleedTicks = 80)) knock(p, entityPos, 1.6, 0.55)
                }
            }
            if (t > 3 && (horizontalCollision || PitArena.radius(entityPos.add(f.multiply(2.0))) > dev.henny.hha_blood.hollows.HeartVaultPiece.FIGHT_R - 1.5)) {
                velocity = Vec3d.ZERO
                sound(BossSounds.IMPACT_HEAVY, 1.5f, 1.3f)
                act(IDLE)
                gcd = w(24)
            }
            return
        }
        velocity = velocity.multiply(0.6, 1.0, 0.6)
        if (t > 34) act(IDLE)
    }

    // ------------------------------------------------------------------------------------- orbs

    private fun tickOrbs(world: ServerWorld) {
        val step = w(ORB_STEP)
        face(foeOrAim(), 10f)
        // three spits (bleed, flame, howl head); unleashed it spits a second volley
        val spits = if (unleashed) 6 else 3
        for (k in 0 until spits) {
            if (actionAge == step * (k + 1)) {
                val i = k % 3
                if (isHeadBroken(i)) continue
                val from = headPos(i)
                val t = foe
                val aimAt = (t?.entityPos ?: aim).add(0.0, 1.0, 0.0).add(t?.velocity?.multiply(6.0) ?: Vec3d.ZERO)
                PitHazards.orb(this, from, t, aimAt, i, 18f)
                sound(BossSounds.CERBERUS_FLAME, 2.2f, 1.4f + i * 0.1f)
                world.spawnParticles(BloodParticles.FLAME, from.x, from.y, from.z, 8, 0.2, 0.2, 0.2, 0.05)
            }
        }
        if (actionAge >= step * spits + 10) act(IDLE)
    }

    // ------------------------------------------------------------------------------------- stage 2

    private fun tickNova(world: ServerWorld) {
        if (actionAge == 1) sound(BossSounds.CERBERUS_GROWL, 3f, 0.7f)
        if (actionAge < 20 && actionAge % 4 == 0) warnRing(world, entityPos, 3.0 + actionAge * 0.4, 0xFF7020)
        if (actionAge == 20 || actionAge == 36) {
            sound(BossSounds.FIRE_BURST, 3f, 0.8f)
            sound(BossSounds.IMPACT_HEAVY, 2f, 1.1f)
            BloodFx.shake(world, entityPos, 18.0, 1.5f, 12)
            PitHazards.shockRing(this, entityPos, 18.0, 0.55, 24f, true)
        }
        if (actionAge >= 48) act(IDLE)
    }

    private fun tickGeysers(world: ServerWorld) {
        if (actionAge == 1) sound(BossSounds.CERBERUS_HOWL, 3f, 0.75f)
        if (actionAge == 16) {
            val players = PitFight.targets(world)
            for (p in players) {
                PitHazards.geyser(this, Vec3d(p.x, PitArena.mid().y, p.z), 24)
                val lead = p.velocity.multiply(14.0)
                PitHazards.geyser(this, PitArena.clampToFloor(Vec3d(p.x + lead.x, PitArena.mid().y, p.z + lead.z)), 30)
                val side = Vec3d(-lead.z, 0.0, lead.x)
                PitHazards.geyser(this, PitArena.clampToFloor(Vec3d(p.x + side.x, PitArena.mid().y, p.z + side.z)), 36)
            }
            repeat(7) {
                val a = random.nextDouble() * Math.PI * 2
                val r = 6.0 + random.nextDouble() * 28.0
                val m = PitArena.mid()
                PitHazards.geyser(this, Vec3d(m.x + cos(a) * r, m.y, m.z + sin(a) * r), 22 + random.nextInt(18))
            }
        }
        if (actionAge >= 36) act(IDLE)
    }

    /** Ring of fire around the target, then it leaps in and breathes with all three heads. */
    private fun tickRing(world: ServerWorld) {
        if (actionAge < RING_WIND && isHeadBroken(1)) { act(IDLE); return }
        if (actionAge < RING_WIND) {
            face(foeOrAim(), 10f)
            if (actionAge % 3 == 0) for (i in 0..2) { val h = headPos(i); world.spawnParticles(ParticleTypes.FLAME, h.x, h.y, h.z, 2, 0.15, 0.15, 0.15, 0.02) }
            return
        }
        if (actionAge == RING_WIND) {
            ringCenter = PitArena.clampToFloor(Vec3d(foeOrAim().x, PitArena.mid().y, foeOrAim().z), 9.0)
            PitHazards.fireRing(this, ringCenter, 7.5, 26, 26 + 150, 16f)
            sound(BossSounds.FIRE_BURST, 3f, 0.6f)
            for (p in PitFight.targets(world)) if (p is ServerPlayerEntity && p.squaredDistanceTo(ringCenter) < 12.0 * 12.0) PitFight.hint(p, "boss.hha_blood.hint.fire_ring")
        }
        if (actionAge == RING_LEAP) {
            leapTo(ringCenter.add(forward().multiply(-1.5)), 16)
            landed = false
            sound(BossSounds.CERBERUS_BARK, 3f, 0.7f)
        }
        if (actionAge > RING_LEAP + 4 && !landed && (isOnGround || actionAge > RING_LEAP + 30)) {
            landed = true
            sound(BossSounds.IMPACT_HEAVY, 2.4f, 1.0f)
            BloodFx.shake(world, entityPos, 14.0, 1.4f, 10)
            for (p in PitFight.targets(world)) if (p.squaredDistanceTo(entityPos) < 3.5 * 3.5) { breakShield(world, p); if (hurt(world, p, 22f)) knock(p, entityPos, 1.0, 0.4) }
        }
        if (actionAge > RING_LEAP) face(foeOrAim(), 12f)
        if (actionAge >= RING_END) {
            // straight into the triple breath
            hitThisAction.clear()
            act(TRIPLE_BREATH)
            cdTriple = 200
            sound(BossSounds.CERBERUS_GROWL, 3f, 0.7f)
        }
    }

    override fun onDeath(damageSource: net.minecraft.entity.damage.DamageSource) {
        super.onDeath(damageSource)
        PitFight.onBossRemoved(this)
    }

    override fun getAmbientSound() = if (scripted || action == SLEEP) null else BossSounds.CERBERUS_GROWL
    override fun getHurtSound(source: net.minecraft.entity.damage.DamageSource) = BossSounds.CERBERUS_HURT
    override fun getDeathSound() = BossSounds.CERBERUS_DEATH
    override fun getSoundVolume(): Float = 2.5f
    override fun getMinAmbientSoundDelay(): Int = 160
}
