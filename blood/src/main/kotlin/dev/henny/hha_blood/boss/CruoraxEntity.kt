package dev.henny.hha_blood.boss

import dev.henny.hha_blood.hollows.HeartVaultPiece
import dev.henny.hha_blood.hollows.HollowsRegion
import dev.henny.hha_blood.logic.BloodFx
import dev.henny.hha_blood.logic.NihilFx
import net.minecraft.entity.EntityType
import net.minecraft.entity.attribute.DefaultAttributeContainer
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.boss.BossBar
import net.minecraft.entity.boss.ServerBossBar
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.damage.DamageTypes
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.entity.mob.HostileEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.particle.ParticleTypes
import net.minecraft.registry.tag.DamageTypeTags
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvents
import net.minecraft.storage.ReadView
import net.minecraft.storage.WriteView
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cruorax, the Blood Wyrm - phase 2 and 3 of the Heart Vault fight. Rises out of the Blood Prince's
 * blood after phase 1. Flies through the dome (no pathfinding: steered with velocity, no-clip, kept
 * inside the dome), lands now and then - while on the ground its heart is exposed (more damage).
 *
 * Phase 2: swoops, blood breath (leaves bleeding pools), blood-crystal stalactite rain, landings with
 * claw + tail sweep. Phase 3 (< 35 % health): also heartbeat shock waves and the Great Nihil.
 */
class CruoraxEntity(type: EntityType<out CruoraxEntity>, world: World) : HostileEntity(type, world) {

    companion object {
        val ACTION: TrackedData<Int> = DataTracker.registerData(CruoraxEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        val ACTION_AGE: TrackedData<Int> = DataTracker.registerData(CruoraxEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        val PHASE3: TrackedData<Boolean> = DataTracker.registerData(CruoraxEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)

        const val FORMING = 0
        const val FLY = 1
        const val SWOOP = 2
        const val BREATH = 3
        const val STALACTITES = 4
        const val LANDING = 5
        const val GROUND = 6
        const val CLAW = 7
        const val TAIL = 8
        const val TAKEOFF = 9
        const val HEARTBEAT = 10
        const val NIHIL = 11
        const val DYING = 12

        const val SCALE = 3.0f
        const val MAX_HEALTH = 700.0
        const val FORM_TICKS = 90

        fun createAttributes(): DefaultAttributeContainer.Builder = createHostileAttributes()
            .add(EntityAttributes.MAX_HEALTH, MAX_HEALTH)
            .add(EntityAttributes.ARMOR, 10.0)
            .add(EntityAttributes.ARMOR_TOUGHNESS, 6.0)
            .add(EntityAttributes.ATTACK_DAMAGE, 14.0)
            .add(EntityAttributes.KNOCKBACK_RESISTANCE, 1.0)
            .add(EntityAttributes.FOLLOW_RANGE, 96.0)
            .add(EntityAttributes.MOVEMENT_SPEED, 0.25)
    }

    val bossBar: ServerBossBar = ServerBossBar(Text.translatable("entity.hha_blood.cruorax").formatted(Formatting.DARK_RED), BossBar.Color.RED, BossBar.Style.NOTCHED_12)
        .also { it.setDarkenSky(true); it.setThickenFog(true) } as ServerBossBar

    private var ticks = 0
    private var foe: PlayerEntity? = null
    private var circleAngle = 0.0
    private var attackCd = 80
    private var swoopCd = 60
    private var breathCd = 140
    private var stalCd = 300
    private var landCd = 360
    private var heartCd = 0
    private var nihilCd = 0
    private var groundAttacks = 0
    private var swoopFrom: Vec3d = Vec3d.ZERO
    private var swoopTo: Vec3d = Vec3d.ZERO
    private val swoopHit = HashSet<java.util.UUID>()
    private var landAt: Vec3d = Vec3d.ZERO
    private var nihilVictims: List<PlayerEntity> = emptyList()

    // client-side smoothing for the model
    private var foldNow = 0f; private var foldLast = 0f
    private var jawNow = 0f; private var jawLast = 0f
    private var tiltNow = 0f; private var tiltLast = 0f

    init {
        experiencePoints = 600
        setPersistent()
        setNoGravity(true)
        noClip = true
    }

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(ACTION, FORMING)
        builder.add(ACTION_AGE, 0)
        builder.add(PHASE3, false)
    }

    val action: Int get() = dataTracker.get(ACTION)
    val isPhase3: Boolean get() = dataTracker.get(PHASE3)
    fun actionTime(tickDelta: Float): Float = age - dataTracker.get(ACTION_AGE) + tickDelta

    private fun setAction(a: Int) {
        dataTracker.set(ACTION, a)
        dataTracker.set(ACTION_AGE, age)
        ticks = 0
    }

    private val grounded: Boolean get() = action in intArrayOf(GROUND, CLAW, TAIL, HEARTBEAT, LANDING, DYING) || (action == TAKEOFF && ticks < 6)

    // ------------------------------------------------------------------------------------- client animation values

    fun foldAmount(td: Float) = MathHelper.lerp(td, foldLast, foldNow)
    fun jawAmount(td: Float) = MathHelper.lerp(td, jawLast, jawNow)
    fun bodyTilt(td: Float) = MathHelper.lerp(td, tiltLast, tiltNow)
    fun formation(td: Float): Float = if (action == FORMING) min(1f, actionTime(td) / FORM_TICKS) else 1f

    override fun tick() {
        super.tick()
        if (entityWorld.isClient) {
            foldLast = foldNow; jawLast = jawNow; tiltLast = tiltNow
            val a = action
            val wantFold = if (a == GROUND || a == CLAW || a == TAIL || a == HEARTBEAT || a == DYING || (a == LANDING && actionTime(0f) > 25)) 1f else 0f
            foldNow += (wantFold - foldNow) * 0.15f
            val wantJaw = when (a) {
                BREATH -> if (actionTime(0f) > 12) 1f else 0.3f
                FORMING -> if (actionTime(0f) > FORM_TICKS - 25) 1f else 0f
                CLAW, NIHIL, HEARTBEAT -> 0.7f
                else -> 0.08f
            }
            jawNow += (wantJaw - jawNow) * 0.2f
            val v = velocity
            val horiz = sqrt(v.x * v.x + v.z * v.z)
            val wantTilt = if (wantFold > 0.5f) 0f else (-atan2(v.y, horiz.coerceAtLeast(0.05)) * 180 / PI).toFloat().coerceIn(-40f, 40f) * 0.8f
            tiltNow += (wantTilt - tiltNow) * 0.12f
        }
    }

    // ------------------------------------------------------------------------------------- basics

    override fun cannotDespawn(): Boolean = true
    override fun canImmediatelyDespawn(distanceSquared: Double): Boolean = false
    override fun canBreatheInWater(): Boolean = true
    override fun isPushable(): Boolean = false
    override fun canUsePortals(allowVehicles: Boolean): Boolean = false
    override fun isCollidable(other: net.minecraft.entity.Entity?): Boolean = false

    override fun writeCustomData(view: WriteView) {
        super.writeCustomData(view)
        view.putBoolean("Phase3", isPhase3)
    }

    override fun readCustomData(view: ReadView) {
        super.readCustomData(view)
        dataTracker.set(PHASE3, view.getBoolean("Phase3", false))
        setAction(FLY)
    }

    override fun damage(world: ServerWorld, source: DamageSource, amount: Float): Boolean {
        if (source.isOf(DamageTypes.OUT_OF_WORLD) || source.isOf(DamageTypes.GENERIC_KILL)) return super.damage(world, source, amount)
        if (action == FORMING || action == DYING) return false
        if (source.isIn(DamageTypeTags.IS_FALL) || source.isOf(DamageTypes.IN_WALL) || source.isOf(DamageTypes.DROWN) || source.isOf(DamageTypes.CRAMMING) ||
            source.isIn(DamageTypeTags.IS_FIRE)) return false
        // the heart is exposed while it stands on the ground
        val exposed = action == GROUND || action == CLAW || action == TAIL || action == HEARTBEAT
        val amt = if (exposed) amount * 1.6f else amount
        val ok = super.damage(world, source, amt)
        if (ok && exposed) {
            BloodFx.spawn(world, BloodFx.flame, entityPos.add(0.0, 1.5, 0.0), 8, 1.0, 0.5, 1.0, 0.05)
            BloodFx.sound(world, entityPos, SoundEvents.BLOCK_AMETHYST_BLOCK_HIT, 2.0f, 0.5f)
        }
        return ok
    }

    override fun onDeath(damageSource: DamageSource) {
        super.onDeath(damageSource)
        bossBar.isVisible = false
        DragonAttacks.clearOwner(uuid)
    }

    override fun updatePostDeath() {
        deathTime++
        val w = entityWorld as? ServerWorld ?: return
        setNoGravity(false); noClip = false
        val body = entityPos.add(0.0, 2.0, 0.0)
        BloodFx.spawn(w, BloodFx.crumbs, body, 24, 2.5, 1.5, 2.5, 0.3)
        BloodFx.spawn(w, BloodFx.flame, body, 10, 2.0, 1.5, 2.0, 0.04)
        if (deathTime % 8 == 0) BloodFx.spawn(w, ParticleTypes.EXPLOSION, body.add(random.nextGaussian() * 2, random.nextDouble() * 2, random.nextGaussian() * 2), 1)
        if (deathTime % 15 == 0) BloodFx.sound(w, body, SoundEvents.ENTITY_ENDER_DRAGON_HURT, 3.0f, 0.5f)
        if (deathTime == 1) {
            BloodPrinceLines.say(w, "death")
            BloodFx.sound(w, body, SoundEvents.ENTITY_ENDER_DRAGON_DEATH, 4.0f, 0.7f)
        }
        if (deathTime >= 100 && !isRemoved) {
            BloodFx.spawn(w, ParticleTypes.EXPLOSION_EMITTER, body, 2)
            BloodFx.spawn(w, BloodFx.crumbs, body, 200, 3.0, 2.0, 3.0, 0.6)
            BloodFx.screenFlash(w, body, 60.0, 0xB00010, 0.6f, 14)
            HeartVaultArena.onDragonDeath(w, this)
            remove(RemovalReason.KILLED)
        }
    }

    override fun onRemoved() {
        super.onRemoved()
        bossBar.clearPlayers()
        DragonAttacks.clearOwner(uuid)
    }

    // ------------------------------------------------------------------------------------- arena helpers

    private fun center(): Vec3d {
        val c = HollowsRegion.center() ?: return entityPos
        return Vec3d(c.x + 0.5, HeartVaultPiece.FLOOR.toDouble(), c.z + 0.5)
    }

    private fun floorY() = HeartVaultPiece.FLOOR.toDouble()

    /** Keeps a point inside the dome (with a margin for the body/wings). */
    private fun clampToDome(p: Vec3d, margin: Double = 9.0): Vec3d {
        val c = center()
        var dx = p.x - c.x
        var dz = p.z - c.z
        val y = p.y.coerceIn(floorY(), floorY() + HeartVaultPiece.H - 9)
        val dy = y - floorY()
        val maxR = (HeartVaultPiece.R - margin) * sqrt((1 - (dy / (HeartVaultPiece.H - 2)) * (dy / (HeartVaultPiece.H - 2))).coerceAtLeast(0.05))
        val r = sqrt(dx * dx + dz * dz)
        if (r > maxR && r > 0.01) { dx *= maxR / r; dz *= maxR / r }
        return Vec3d(c.x + dx, y, c.z + dz)
    }

    /** Fly towards [target] (smooth steering), turn the body into the flight direction. */
    private fun steer(target: Vec3d, speed: Double, agility: Double = 0.08) {
        val to = clampToDome(target).subtract(entityPos)
        val want = if (to.length() > 0.3) to.normalize().multiply(min(speed, to.length() * 0.4 + 0.05)) else Vec3d.ZERO
        velocity = velocity.lerp(want, agility)
        faceDirection(velocity, 6f)
    }

    private fun faceDirection(dir: Vec3d, step: Float) {
        if (dir.horizontalLengthSquared() < 1.0E-4) return
        val want = (atan2(-dir.x, dir.z) * 180 / PI).toFloat()
        val y = MathHelper.stepUnwrappedAngleTowards(yaw, want, step)
        yaw = y; bodyYaw = y
        headYaw = MathHelper.stepUnwrappedAngleTowards(headYaw, want, step * 1.5f)
    }

    private fun lookAtPos(p: Vec3d, step: Float = 8f) {
        val d = p.subtract(eyePos)
        val want = (atan2(-d.x, d.z) * 180 / PI).toFloat()
        headYaw = MathHelper.stepUnwrappedAngleTowards(headYaw, want, step)
        // the body follows slowly so the neck never twists more than ~60 degrees
        if (MathHelper.angleBetween(headYaw, bodyYaw) > 55f) { bodyYaw = MathHelper.stepUnwrappedAngleTowards(bodyYaw, headYaw, 4f); yaw = bodyYaw }
        val horiz = sqrt(d.x * d.x + d.z * d.z)
        pitch = MathHelper.clamp((-atan2(d.y, horiz) * 180 / PI).toFloat(), -40f, 60f)
    }

    private fun pickFoe(world: ServerWorld): PlayerEntity? {
        val list = HeartVaultArena.playersInArena(world)
        val f = if (foe != null && foe!!.isAlive && foe in list && random.nextFloat() > 0.02f) foe else list.randomOrNull()
        foe = f
        return f
    }

    // ------------------------------------------------------------------------------------- AI

    override fun mobTick(world: ServerWorld) {
        super.mobTick(world)
        ticks++
        attackCd--; swoopCd--; breathCd--; stalCd--; landCd--; heartCd--; nihilCd--
        bossBar.percent = health / maxHealth
        noClip = true
        setNoGravity(true)
        if (!isPhase3 && health < maxHealth * 0.35f && action != FORMING) enterPhase3(world)
        when (action) {
            FORMING -> tickForming(world)
            FLY -> tickFly(world)
            SWOOP -> tickSwoop(world)
            BREATH -> tickBreath(world)
            STALACTITES -> tickStalactites(world)
            LANDING -> tickLanding(world)
            GROUND -> tickGround(world)
            CLAW -> tickClaw(world)
            TAIL -> tickTail(world)
            TAKEOFF -> tickTakeoff(world)
            HEARTBEAT -> tickHeartbeat(world)
            NIHIL -> tickNihil(world)
        }
        // safety: never leave the dome
        val clamped = clampToDome(entityPos, 6.0)
        if (clamped.squaredDistanceTo(entityPos) > 4.0) setPosition(clamped.x, clamped.y, clamped.z)
        if (age % 3 == 0) BloodFx.spawn(world, BloodFx.crumbs, entityPos.add(0.0, 2.0, 0.0), 2, 1.5, 0.6, 1.5, 0.0)
    }

    private fun cd(t: Int): Int = if (isPhase3) (t * 0.7).toInt() else t

    private fun enterPhase3(world: ServerWorld) {
        dataTracker.set(PHASE3, true)
        BloodPrinceLines.say(world, "phase3")
        BloodFx.sound(world, entityPos, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, 5.0f, 0.5f)
        BloodFx.screenFlash(world, entityPos, 80.0, 0x900010, 0.5f, 16)
        BloodFx.shake(world, entityPos, 80.0, 2.5f, 30)
        heartCd = 40; nihilCd = 200
    }

    private fun tickForming(world: ServerWorld) {
        velocity = Vec3d.ZERO
        val c = center()
        val at = c.add(0.0, 10.0, 0.0)
        if (ticks == 1) setPosition(at.x, at.y, at.z)
        // blood streams in from everywhere
        for (k in 0 until 6) {
            val a = random.nextDouble() * PI * 2
            val r = 6.0 + random.nextDouble() * 18.0
            val from = Vec3d(c.x + cos(a) * r, floorY() + 0.2, c.z + sin(a) * r)
            BloodFx.vein(world, from, entityPos.add(0.0, 2.0, 0.0), ticks / 6.0, 3, 1.6)
        }
        BloodFx.spawn(world, BloodFx.flame, entityPos.add(0.0, 2.0, 0.0), 6, 2.0, 2.0, 2.0, 0.05)
        if (ticks % 20 == 0) BloodFx.sound(world, entityPos, SoundEvents.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 3.0f, 0.4f)
        if (ticks == FORM_TICKS - 20) {
            BloodPrinceLines.dragonTitle(world, this)
        }
        if (ticks >= FORM_TICKS) {
            BloodFx.sound(world, entityPos, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, 6.0f, 0.55f)
            BloodFx.shake(world, entityPos, 90.0, 3.0f, 30)
            BloodFx.screenFlash(world, entityPos, 90.0, 0xA00010, 0.5f, 12)
            BloodFx.ring(world, Vec3d(c.x, floorY() + 0.2, c.z), 12.0, BloodFx.flame, 80)
            for (p in HeartVaultArena.playersInArena(world)) push(p, p.entityPos.subtract(entityPos).multiply(1.0, 0.0, 1.0).normalize().multiply(1.4).add(0.0, 0.6, 0.0))
            setAction(FLY)
        }
    }

    private fun tickFly(world: ServerWorld) {
        val c = center()
        val f = pickFoe(world)
        circleAngle += if (isPhase3) 0.022 else 0.016
        val r = HeartVaultPiece.R * 0.55
        val h = floorY() + 17 + sin(age * 0.02) * 4
        val target = Vec3d(c.x + cos(circleAngle) * r, h, c.z + sin(circleAngle) * r)
        steer(target, if (isPhase3) 0.75 else 0.6)
        if (f != null && ticks % 40 == 0) BloodFx.sound(world, entityPos, SoundEvents.ENTITY_ENDER_DRAGON_FLAP, 3.0f, 0.6f)
        if (f == null || attackCd > 0 || ticks < 20) return
        // choose the next attack
        val opts = ArrayList<Pair<Int, Int>>()
        if (isPhase3 && nihilCd <= 0) opts += NIHIL to 4
        if (isPhase3 && heartCd <= 0) opts += HEARTBEAT to 4
        if (swoopCd <= 0) opts += SWOOP to 5
        if (breathCd <= 0) opts += BREATH to 4
        if (stalCd <= 0) opts += STALACTITES to 3
        if (landCd <= 0) opts += LANDING to 3
        if (opts.isEmpty()) return
        var roll = random.nextInt(opts.sumOf { it.second })
        val pick = opts.first { roll -= it.second; roll < 0 }.first
        attackCd = cd(50)
        when (pick) {
            SWOOP -> { swoopCd = cd(160); swoopFrom = entityPos; swoopHit.clear() }
            BREATH -> breathCd = cd(260)
            STALACTITES -> stalCd = cd(520)
            LANDING -> { landCd = cd(600); landAt = landingSpot(f) }
            HEARTBEAT -> { heartCd = cd(520); landAt = Vec3d(c.x, floorY(), c.z) }
            NIHIL -> nihilCd = cd(800)
        }
        setAction(pick)
    }

    private fun landingSpot(f: PlayerEntity): Vec3d {
        val c = center()
        val toward = Vec3d(f.x - c.x, 0.0, f.z - c.z)
        val d = min(toward.length(), HeartVaultPiece.R * 0.45)
        val p = if (toward.length() > 0.1) c.add(toward.normalize().multiply(d * 0.6)) else c
        return Vec3d(p.x, floorY(), p.z)
    }

    /** Wings back, dives at the target, ploughs through everything in the way and pulls up again. */
    private fun tickSwoop(world: ServerWorld) {
        val f = foe ?: run { setAction(FLY); return }
        if (ticks < 18) {
            // gain a little height and line up
            steer(entityPos.add(0.0, 3.0, 0.0).add(f.entityPos.subtract(entityPos).multiply(1.0, 0.0, 1.0).normalize().multiply(-2.0)), 0.5, 0.12)
            lookAtPos(f.entityPos)
            if (ticks == 2) BloodFx.sound(world, entityPos, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, 4.0f, 0.8f)
            if (ticks == 17) swoopTo = f.entityPos.add(f.velocity.multiply(8.0)).add(0.0, 1.0, 0.0)
            return
        }
        val dir = swoopTo.subtract(entityPos)
        if (ticks < 60 && dir.length() > 2.0) {
            velocity = velocity.lerp(dir.normalize().multiply(if (isPhase3) 1.7 else 1.45), 0.35)
            faceDirection(velocity, 25f)
        } else {
            steer(entityPos.add(velocity.multiply(1.0, 0.0, 1.0).normalize().multiply(8.0)).add(0.0, 10.0, 0.0), 0.9, 0.15)
        }
        BloodFx.spawn(world, BloodFx.fade(BloodFx.BRIGHT, BloodFx.BLACK, 2.0f), entityPos.add(0.0, 2.0, 0.0), 4, 1.2, 0.6, 1.2, 0.0)
        for (p in HeartVaultArena.playersInArena(world)) {
            if (p.squaredDistanceTo(entityPos.add(0.0, 1.5, 0.0)) < 4.5 * 4.5 && swoopHit.add(p.uuid)) {
                hurt(world, p, attackValue() + 2f, 100)
                push(p, velocity.multiply(1.0, 0.0, 1.0).normalize().multiply(1.6).add(0.0, 0.8, 0.0))
                BloodFx.sound(world, p.entityPos, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, 2.0f, 0.5f)
            }
        }
        if (ticks >= 85) setAction(FLY)
    }

    /** Hovers and spews a stream of blood at the target; the blood lands as bleeding pools. */
    private fun tickBreath(world: ServerWorld) {
        val f = foe ?: run { setAction(FLY); return }
        velocity = velocity.multiply(0.85)
        lookAtPos(f.entityPos)
        if (ticks == 2) BloodFx.sound(world, entityPos, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, 4.0f, 0.6f)
        if (ticks in 14..70) {
            val mouth = mouthPos()
            val aim = f.entityPos.add(f.velocity.multiply(4.0)).add(0.0, 0.5, 0.0).subtract(mouth).normalize()
            if (ticks % 2 == 0) DragonAttacks.breath(world, this, mouth, aim.add(random.nextGaussian() * 0.05, random.nextGaussian() * 0.05, random.nextGaussian() * 0.05))
            if (ticks % 6 == 0) BloodFx.sound(world, mouth, SoundEvents.ENTITY_ENDER_DRAGON_SHOOT, 2.0f, 0.5f)
        }
        if (ticks >= 82) setAction(FLY)
    }

    fun mouthPos(): Vec3d {
        val look = Vec3d.fromPolar(pitch, headYaw)
        return entityPos.add(0.0, 3.2, 0.0).add(look.multiply(7.5))
    }

    /** Rams the ceiling - blood-crystal stalactites come down (marked on the floor first). */
    private fun tickStalactites(world: ServerWorld) {
        val c = center()
        val top = Vec3d(c.x, floorY() + HeartVaultPiece.H - 10, c.z)
        if (ticks < 40) { steer(top, 0.9, 0.12); return }
        if (ticks == 40) {
            velocity = Vec3d(0.0, 0.6, 0.0)
            BloodFx.sound(world, entityPos, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 4.0f, 0.5f)
            BloodFx.sound(world, entityPos, SoundEvents.BLOCK_POINTED_DRIPSTONE_LAND, 4.0f, 0.5f)
            BloodFx.shake(world, entityPos, 90.0, 2.5f, 26)
            BloodFx.spawn(world, BloodFx.crumbs, entityPos.add(0.0, 5.0, 0.0), 80, 4.0, 1.0, 4.0, 0.3)
        }
        if (ticks in 42..100 && ticks % 4 == 0) {
            val players = HeartVaultArena.playersInArena(world)
            // one on every player + random ones
            for (p in players) if (ticks % 12 == 2) DragonAttacks.stalactite(world, this, Vec3d(p.x, floorY(), p.z))
            val a = random.nextDouble() * PI * 2
            val r = sqrt(random.nextDouble()) * (HeartVaultPiece.R - 6)
            DragonAttacks.stalactite(world, this, Vec3d(c.x + cos(a) * r, floorY(), c.z + sin(a) * r))
        }
        if (ticks > 40) velocity = velocity.multiply(0.8)
        if (ticks >= 110) setAction(FLY)
    }

    /** Glides down to a landing spot. */
    private fun tickLanding(world: ServerWorld) {
        val to = landAt.subtract(entityPos)
        if (to.length() > 1.0 && ticks < 80) {
            val want = to.normalize().multiply(min(0.7, to.length() * 0.12 + 0.1))
            velocity = velocity.lerp(want, 0.15)
            faceDirection(Vec3d(to.x, 0.0, to.z), 6f)
        } else {
            velocity = Vec3d.ZERO
            setPosition(landAt.x, landAt.y, landAt.z)
            BloodFx.sound(world, entityPos, SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY, 4.0f, 0.5f)
            BloodFx.shake(world, entityPos, 50.0, 2.0f, 14)
            BloodFx.spawn(world, BloodFx.crumbs, entityPos.add(0.0, 0.3, 0.0), 60, 3.0, 0.2, 3.0, 0.3)
            for (p in HeartVaultArena.playersInArena(world)) {
                if (p.squaredDistanceTo(entityPos) < 36.0) { hurt(world, p, 8f, 40); push(p, p.entityPos.subtract(entityPos).normalize().multiply(1.2).add(0.0, 0.5, 0.0)) }
            }
            groundAttacks = 0
            setAction(GROUND)
        }
    }


    /** On the ground: turns to the target, claws or sweeps with the tail, then takes off again. */
    private fun tickGround(world: ServerWorld) {
        velocity = Vec3d.ZERO
        val f = pickFoe(world)
        if (f != null) {
            lookAtPos(f.entityPos, 5f)
            // crawl slowly towards the target
            val to = Vec3d(f.x - x, 0.0, f.z - z)
            if (to.length() > 7.0) { val s = to.normalize().multiply(0.12); setPosition(x + s.x, floorY(), z + s.z) }
        }
        if (ticks % 30 == 0) BloodFx.sound(world, entityPos, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 4.0f, 0.6f)
        BloodFx.spawn(world, BloodFx.flame, entityPos.add(0.0, 1.6, 0.0).add(Vec3d.fromPolar(0f, bodyYaw).multiply(1.5)), 1, 0.3)
        if (ticks < 15 || f == null) return
        val dist = f.distanceTo(this)
        if (groundAttacks >= 4 || ticks > 200) { setAction(TAKEOFF); return }
        val behind = Vec3d.fromPolar(0f, bodyYaw).dotProduct(f.entityPos.subtract(entityPos).multiply(1.0, 0.0, 1.0).normalize()) < -0.2
        when {
            (behind && dist < 10) || (dist < 6.5 && random.nextFloat() < 0.35f) -> { groundAttacks++; setAction(TAIL) }
            dist < 9.0 -> { groundAttacks++; setAction(CLAW) }
        }
    }

    private fun tickClaw(world: ServerWorld) {
        velocity = Vec3d.ZERO
        foe?.let { lookAtPos(it.entityPos, 6f) }
        if (ticks == 4) BloodFx.sound(world, entityPos, SoundEvents.ENTITY_RAVAGER_ATTACK, 3.0f, 0.5f)
        if (ticks == 12) {
            val fwd = Vec3d.fromPolar(0f, bodyYaw)
            val front = entityPos.add(fwd.multiply(5.0))
            for (p in HeartVaultArena.playersInArena(world)) {
                val to = p.entityPos.subtract(entityPos).multiply(1.0, 0.0, 1.0)
                if (to.length() < 9.0 && to.normalize().dotProduct(fwd) > 0.35 && kotlin.math.abs(p.y - y) < 4) {
                    hurt(world, p, attackValue(), 100)
                    push(p, fwd.multiply(1.2).add(0.0, 0.5, 0.0))
                }
            }
            BloodFx.spawn(world, ParticleTypes.SWEEP_ATTACK, front.add(0.0, 1.0, 0.0), 6, 2.0, 0.4, 2.0, 0.0)
            BloodFx.spawn(world, BloodFx.crumbs, front.add(0.0, 0.5, 0.0), 30, 2.0, 0.3, 2.0, 0.3)
            BloodFx.sound(world, front, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 3.0f, 0.5f)
            BloodFx.shake(world, front, 20.0, 0.8f, 8)
        }
        if (ticks >= 24) setAction(GROUND)
    }

    private fun tickTail(world: ServerWorld) {
        velocity = Vec3d.ZERO
        if (ticks < 14) {
            // telegraph: ring of blood dust around it
            BloodFx.ring(world, entityPos.add(0.0, 0.15, 0.0), 8.5, BloodFx.dust(BloodFx.BRIGHT, 1.6f), 40)
            if (ticks == 2) BloodFx.sound(world, entityPos, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, 3.0f, 1.2f)
        }
        if (ticks in 14..24) {
            bodyYaw += 36f; yaw = bodyYaw; headYaw = bodyYaw
            if (ticks == 16) {
                for (p in HeartVaultArena.playersInArena(world)) {
                    val to = p.entityPos.subtract(entityPos).multiply(1.0, 0.0, 1.0)
                    if (to.length() < 9.0 && kotlin.math.abs(p.y - y) < 4) {
                        hurt(world, p, attackValue() - 2f, 60)
                        push(p, to.normalize().multiply(2.0).add(0.0, 0.7, 0.0))
                    }
                }
                BloodFx.sound(world, entityPos, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, 3.0f, 0.4f)
                BloodFx.ring(world, entityPos.add(0.0, 0.4, 0.0), 8.0, BloodFx.crumbs, 60, speed = 0.2)
            }
        }
        if (ticks >= 34) setAction(GROUND)
    }

    private fun tickTakeoff(world: ServerWorld) {
        if (ticks == 1) {
            BloodFx.sound(world, entityPos, SoundEvents.ENTITY_ENDER_DRAGON_FLAP, 4.0f, 0.5f)
            BloodFx.spawn(world, ParticleTypes.EXPLOSION, entityPos.add(0.0, 0.5, 0.0), 3, 3.0, 0.2, 3.0, 0.0)
            for (p in HeartVaultArena.playersInArena(world)) {
                if (p.squaredDistanceTo(entityPos) < 64.0) push(p, p.entityPos.subtract(entityPos).multiply(1.0, 0.0, 1.0).normalize().multiply(1.0).add(0.0, 0.3, 0.0))
            }
        }
        velocity = velocity.lerp(Vec3d(0.0, 0.55, 0.0), 0.2)
        if (ticks >= 30) setAction(FLY)
    }

    /** Phase 3: lands in the middle, its heart beats - shock rings run over the floor (jump over them). */
    private fun tickHeartbeat(world: ServerWorld) {
        val c = center()
        val landing = Vec3d(c.x, floorY(), c.z)
        if (entityPos.squaredDistanceTo(landing) > 1.0 && ticks < 60) {
            val to = landing.subtract(entityPos)
            velocity = velocity.lerp(to.normalize().multiply(min(0.8, to.length() * 0.15 + 0.1)), 0.18)
            faceDirection(Vec3d(to.x, 0.0, to.z), 6f)
            dataTracker.set(ACTION_AGE, age)   // the beat timer starts only once it is down
            ticks = 0
            return
        }
        velocity = Vec3d.ZERO
        if (ticks == 1) {
            setPosition(landing.x, landing.y, landing.z)
            BloodFx.sound(world, entityPos, SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY, 4.0f, 0.4f)
            BloodPrinceLines.say(world, "heartbeat")
        }
        val beats = 4
        val period = 34
        val beat = ticks / period
        val bt = ticks % period
        if (beat < beats) {
            if (bt == 0) {
                BloodFx.sound(world, entityPos, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 6.0f, 0.5f)
                BloodFx.sound(world, entityPos, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 2.0f, 0.5f)
                BloodFx.shake(world, entityPos, 80.0, 1.6f, 10)
            }
            DragonAttacks.shockRing(world, this, Vec3d(c.x, floorY(), c.z), bt * 1.45, bt == period - 1)
        }
        if (ticks >= beats * period + 10) { landCd = cd(300); setAction(TAKEOFF) }
    }

    /** Phase 3: the Great Nihil - hovers in the middle, pulls everyone up and drains them, then thorns. */
    private fun tickNihil(world: ServerWorld) {
        val c = center()
        val hover = Vec3d(c.x, floorY() + 16, c.z)
        steer(hover, 0.6, 0.12)
        if (ticks == 1) {
            nihilVictims = HeartVaultArena.playersInArena(world)
            BloodPrinceLines.say(world, "nihil")
            BloodFx.sound(world, entityPos, SoundEvents.ENTITY_EVOKER_PREPARE_SUMMON, 4.0f, 0.4f)
            BloodFx.sound(world, entityPos, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, 5.0f, 0.4f)
        }
        val core = entityPos.add(0.0, 2.0, 0.0)
        if (ticks in 10..100) {
            for (p in nihilVictims) {
                if (!p.isAlive) continue
                val to = core.subtract(p.entityPos)
                val flat = Vec3d(to.x, 0.0, to.z)
                val pull = if (flat.length() > 9.0) flat.normalize().multiply(0.13) else Vec3d.ZERO
                val vy = ((floorY() + 7 - p.y) * 0.06).coerceIn(-0.08, 0.16)
                push(p, Vec3d(pull.x, vy, pull.z))
                if (ticks % 3 == 0) BloodFx.vein(world, p.entityPos.add(0.0, 1.0, 0.0), core, ticks / 10.0, 5)
            }
            BloodFx.spawn(world, BloodFx.flame, core, 6, 3.0, 2.0, 3.0, 0.03)
        }
        if (ticks == 40 || ticks == 60 || ticks == 80) {
            var drained = 0f
            for (p in nihilVictims) {
                if (!p.isAlive) continue
                p.timeUntilRegen = 0
                if (hurt(world, p, 6f, 80)) drained += 6f
                BloodFx.ring(world, p.entityPos.add(0.0, 0.1, 0.0), 2.0, BloodFx.flame, 24)
            }
            heal(drained * 2.5f)
            BloodFx.sound(world, entityPos, SoundEvents.BLOCK_BELL_USE, 5.0f, 0.4f + ticks / 200f)
            BloodFx.sound(world, entityPos, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 2.0f, 1.2f)
        }
        if (ticks == 88) {
            for (p in nihilVictims.filter { it.isAlive }) NihilFx.impale(world, this, p, 10, 24, 10f)
        }
        if (ticks >= 130) setAction(FLY)
    }

    // ------------------------------------------------------------------------------------- helpers

    private fun attackValue(): Float = (getAttributeValue(EntityAttributes.ATTACK_DAMAGE) * if (isPhase3) 1.15 else 1.0).toFloat()

    fun hurt(world: ServerWorld, p: PlayerEntity, amount: Float, bleedTicks: Int): Boolean {
        val ok = p.damage(world, world.damageSources.mobAttack(this), amount)
        if (ok && bleedTicks > 0) dev.henny.hha_blood.logic.Bleed.apply(p, durationTicks = bleedTicks, source = this)
        return ok
    }

    fun push(p: PlayerEntity, v: Vec3d) {
        p.velocity = p.velocity.multiply(0.3).add(v)
        p.knockedBack = true
        (p as? net.minecraft.server.network.ServerPlayerEntity)?.networkHandler?.sendPacket(net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket(p))
    }

    /** Arena reset (everybody fled): vanish. */
    fun vanish() {
        DragonAttacks.clearOwner(uuid)
        discard()
    }
}
