package dev.henny.hha_blood.boss

import dev.henny.hha_blood.logic.Bleed
import net.minecraft.entity.EntityType
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
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.storage.ReadView
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared base of the two Slaughter Pit bosses: an action state machine (no vanilla goals) whose current
 * action is synced to the client as a stamp (action + sequence number, so the same attack twice in a row
 * restarts its animation), scripted mode for cutscenes, damage gating through [PitFight] and helpers.
 */
abstract class PitBoss(type: EntityType<out PitBoss>, world: World) : HostileEntity(type, world) {

    companion object {
        val STAMP: TrackedData<Int> = DataTracker.registerData(PitBoss::class.java, TrackedDataHandlerRegistry.INTEGER)
        /** Global damage balance (Mats 07.11.: v0.8 was too strong -> 60 %). */
        const val BALANCE = 0.6f
    }

    /** Current action (server: authoritative, client: decoded from the stamp). */
    var action = 0
        private set

    /** Ticks since the action started. */
    var actionAge = 0
        private set

    /** Cutscene control: no AI, the scene moves and animates the boss. */
    var scripted = false

    private var seq = 0
    private var lastStamp = Int.MIN_VALUE

    init {
        setPersistent()
    }

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(STAMP, 0)
    }

    fun act(a: Int) {
        seq = (seq + 1) and 0xFFFF
        action = a
        actionAge = 0
        dataTracker.set(STAMP, a or (seq shl 8))
    }

    /** Smooth action time for the renderer. */
    fun actionTime(tickDelta: Float): Float = actionAge + tickDelta

    override fun tick() {
        if (entityWorld.isClient) {
            val s = dataTracker.get(STAMP)
            if (s != lastStamp) {
                lastStamp = s
                action = s and 0xFF
                actionAge = 0
            } else actionAge++
        } else actionAge++
        super.tick()
    }

    override fun cannotDespawn(): Boolean = true
    override fun canImmediatelyDespawn(distanceSquared: Double): Boolean = false
    override fun canBreatheInWater(): Boolean = true
    override fun canUsePortals(allowVehicles: Boolean): Boolean = false
    override fun isPushable(): Boolean = false
    override fun isFireImmune(): Boolean = true

    override fun damage(world: ServerWorld, source: DamageSource, amount: Float): Boolean {
        if (source.isOf(DamageTypes.OUT_OF_WORLD)) return super.damage(world, source, amount)
        if (source.isIn(DamageTypeTags.IS_FALL) || source.isIn(DamageTypeTags.IS_FIRE) || source.isOf(DamageTypes.IN_WALL) ||
            source.isOf(DamageTypes.DROWN) || source.isOf(DamageTypes.CRAMMING) || source.isOf(DamageTypes.HOT_FLOOR)) return false
        val allowed = PitFight.onBossDamaged(this, source, amount)
        // negative = swallowed silently (e.g. a Cerberus head is still in its hurt frames)
        if (allowed < 0f) return false
        if (allowed == 0f) {
            if (source.attacker is PlayerEntity) deflect(world)
            return false
        }
        val hit = super.damage(world, source, allowed)
        if (hit) onHurtBy(source, allowed)
        return hit
    }

    /** Hit while invulnerable: sparks + clang. */
    private fun deflect(world: ServerWorld) {
        val p = entityPos.add(0.0, height * 0.6, 0.0)
        world.spawnParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 8, 0.4, 0.4, 0.4, 0.2)
        world.playSound(null, p.x, p.y, p.z, SoundEvents.ITEM_SHIELD_BLOCK.value(), SoundCategory.HOSTILE, 1.0f, 0.6f)
    }

    override fun readCustomData(view: ReadView) {
        super.readCustomData(view)
        // a fight interrupted by a restart/unload starts over: the arena logic puts the boss back to its place
        PitFight.onBossLoaded(this)
    }

    // ------------------------------------------------------------------------------------- helpers

    /** Turns towards [p] by at most [step] degrees. */
    fun face(p: Vec3d, step: Float = 30f) {
        val d = p.subtract(entityPos)
        if (d.horizontalLengthSquared() < 0.01) return
        val want = (atan2(-d.x, d.z) * 180 / PI).toFloat()
        val y = MathHelper.stepUnwrappedAngleTowards(yaw, want, step)
        yaw = y; bodyYaw = y; headYaw = y
        lastYaw = y
    }

    /** Horizontal unit vector of the facing direction. */
    fun forward(): Vec3d {
        val r = Math.toRadians(yaw.toDouble())
        return Vec3d(-sin(r), 0.0, cos(r))
    }

    fun horizontalDistance(p: Vec3d): Double {
        val dx = p.x - x
        val dz = p.z - z
        return sqrt(dx * dx + dz * dz)
    }

    /** Is [p] in front of the boss within [range] and [halfAngle] degrees? */
    fun inCone(p: Vec3d, from: Vec3d, dir: Vec3d, range: Double, halfAngle: Double): Boolean {
        val d = Vec3d(p.x - from.x, 0.0, p.z - from.z)
        val len = d.length()
        if (len > range) return false
        if (len < 1.2) return true
        val cos = (d.x * dir.x + d.z * dir.z) / (len * dir.horizontalLength().coerceAtLeast(1e-6))
        return cos >= kotlin.math.cos(Math.toRadians(halfAngle))
    }

    fun sound(event: SoundEvent, volume: Float = 2.5f, pitch: Float = 1f) {
        entityWorld.playSound(null, x, y, z, event, SoundCategory.HOSTILE, volume, pitch)
    }

    /**
     * Damages a player with this boss as the attacker; true if it hit. The boss damage types pierce most of the
     * armour (armour still takes off up to 35 %, Protection and Resistance work as usual) - with vanilla armour
     * maths a full netherite set made every hit almost harmless. [kind]: [BossDamage.SLAUGHTER] (melee, a raised
     * shield blocks unless it is broken first), [BossDamage.HELLFIRE] (fire, blood, shock waves: no shield) or
     * [BossDamage.CRUSH] (falling rubble).
     */
    fun hurt(world: ServerWorld, p: PlayerEntity, base: Float, bleedTicks: Int = 0, fireSeconds: Float = 0f, ignoreFrames: Boolean = false,
             kind: Int = BossDamage.SLAUGHTER): Boolean {
        if (ignoreFrames) p.timeUntilRegen = 0
        val armourCut = 1f - 0.35f * (p.armor.coerceIn(0, 20) / 20f)
        val before = p.health + p.absorptionAmount
        val ok = p.damage(world, BossDamage.source(world, kind, this), PitFight.scaledDamage(base) * BALANCE * damageBonus() * armourCut)
        if (System.getProperty("hha_blood.debugDamage") != null) dev.henny.hha_blood.HhaBlood.LOGGER.info("[dmg] {} kind={} base={} -> {} lost {} (armour {})",
            javaClass.simpleName, kind, base, ok, before - p.health - p.absorptionAmount, p.armor)
        if (ok) {
            if (bleedTicks > 0) Bleed.apply(p, durationTicks = bleedTicks, damagePerSecond = 1.0f, source = this)
            if (fireSeconds > 0f) p.setOnFireFor(fireSeconds)
            onPlayerHit(p)
        }
        return ok
    }

    /** Breaks a raised shield ("bricht durch die Deckung"). */
    fun breakShield(world: ServerWorld, p: PlayerEntity, ticks: Int = 100) {
        if (!p.isBlocking) return
        val stack = p.activeItem
        p.itemCooldownManager.set(stack, ticks)
        p.clearActiveItem()
        world.playSound(null, p.x, p.y, p.z, SoundEvents.ITEM_SHIELD_BREAK.value(), SoundCategory.PLAYERS, 1.2f, 0.8f)
    }

    /** Pushes a player away from [from] (and up). */
    fun knock(p: PlayerEntity, from: Vec3d, strength: Double, up: Double) {
        val d = Vec3d(p.x - from.x, 0.0, p.z - from.z)
        val n = if (d.lengthSquared() < 1e-4) forward() else d.normalize()
        p.addVelocity(n.x * strength, up, n.z * strength)
        p.velocityDirty = true
        p.knockedBack = true
    }

    /** Ballistic jump that lands near [target] after about [ticks] ticks. */
    fun leapTo(target: Vec3d, ticks: Int) {
        val d = target.subtract(entityPos)
        val t = ticks.toDouble()
        // vanilla gravity 0.08/tick with 0.98 drag (approximately)
        val vy = 0.08 * t / 2.0 + d.y / t
        velocity = Vec3d(d.x / t * 1.05, vy.coerceIn(0.4, 1.6), d.z / t * 1.05)
        velocityDirty = true
    }

    /** Called when one of its attacks hit a player (the Minotaur gets faster). */
    open fun onPlayerHit(p: PlayerEntity) {}

    /** Multiplier on all damage this boss deals (the Minotaur's fury). */
    open fun damageBonus(): Float = 1f

    /** Multiplier on the damage this boss takes. */
    open fun toughness(): Float = 1f

    /** A hit on the boss counted for [amount] (after the fight's scaling). */
    open fun onHurtBy(source: DamageSource, amount: Float) {}

    /** Sets the base attributes again - entities keep their saved attributes, so new values need this. */
    abstract fun refreshStats()

    /** Puts the boss in its resting state (cutscene start/reset). */
    abstract fun resetAction()

    /** Circle of particles on the ground (attack warnings). */
    fun warnRing(world: ServerWorld, center: Vec3d, radius: Double, color: Int = 0xFF2020) {
        val n = (radius * 6).toInt().coerceIn(12, 120)
        val dust = net.minecraft.particle.DustParticleEffect(color, 1.4f)
        for (i in 0 until n) {
            val a = i * 2 * PI / n
            world.spawnParticles(dust, center.x + cos(a) * radius, center.y + 0.15, center.z + sin(a) * radius, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }
}
