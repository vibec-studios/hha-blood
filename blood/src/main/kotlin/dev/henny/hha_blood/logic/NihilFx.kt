package dev.henny.hha_blood.logic

import net.minecraft.block.ShapeContext
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.decoration.DisplayEntity
import net.minecraft.item.Items
import net.minecraft.particle.ParticleTypes
import dev.henny.hha.logic.Targeting
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.Box
import net.minecraft.sound.SoundEvents
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 3D visuals of Nihil (item displays, see [FxDisplays]):
 *  - a spinning blood-saw RING (donut, model `blood_saw_ring`) around every levitating victim,
 *    throwing sparks and blood; it bursts when the Blood Nova hits
 *  - blood SPIKES (model `blood_spike`, a twisted thorn pointing up) that erupt from the ground and sink back;
 */
object NihilFx {

    private const val RING_MODEL = "blood_saw_ring"
    private const val SPIKE_MODEL = "blood_spike"
    private const val POOL_MODEL = "blood_pool"
    /** inner / outer radius of the ring model in blocks at scale 1 (7 px / ~12 px) */
    private const val RING_HOLE = 7.0 / 16.0
    private const val RING_OUTER = 12.0 / 16.0
    private const val GROW_TICKS = 6
    private const val BURST_TICKS = 5

    private class Ring(val victim: UUID, val world: ServerWorld, val display: DisplayEntity.ItemDisplayEntity?, val scale: Float, val dir: Float) {
        var age = 0
        var spin = 0f
        var burst = -1
        var lastCenter: Vec3d? = null
    }

    private class Spike(
        val world: ServerWorld, val base: Vec3d, val display: DisplayEntity.ItemDisplayEntity?,
        val rotation: Quaternionf, val scale: Vector3f,
        val rumble: Int, val hold: Int, val sink: Int,
        val victim: UUID? = null, val owner: UUID? = null, val damage: Float = 0f,
        /** direction the thorn grows in (straight up for the main one, leaning out for the crown thorns) */
        val axis: Vec3d = Vec3d(0.0, 1.0, 0.0),
        /** crown thorn: one quick thrust after [delay] ticks instead of rumble + three stabs */
        val single: Boolean = false, val delay: Int = 0,
        val pool: DisplayEntity.ItemDisplayEntity? = null,
        val groundState: net.minecraft.block.BlockState? = null,
        /** lengths after each stab (fractions of the full length) - the first two stop below the victim's feet */
        val levels: DoubleArray = doubleArrayOf(0.14, 0.4, 0.7, 1.0),
    ) {
        var age = 0
        val hit = HashSet<UUID>()
        val length get() = 1.0 * scale.z      // the spike model is 16 px long (along its z axis), base at z = 0
        /** half width of the base in blocks (model base is ~6.6 px wide) */
        val radius get() = 3.3 / 16.0 * scale.x
    }
    private val rings = ArrayList<Ring>()
    private val spikes = ArrayList<Spike>()

    fun reset() {
        rings.forEach { FxDisplays.remove(it.display) }
        spikes.forEach { FxDisplays.remove(it.display); FxDisplays.remove(it.pool) }
        rings.clear()
        spikes.clear()
    }

    // ------------------------------------------------------------------ rings

    fun startRings(world: ServerWorld, victims: List<LivingEntity>) {
        for ((i, v) in victims.withIndex()) {
            val scale = max(1.25, (v.width / 2.0 + 0.25) / RING_HOLE).toFloat()
            val center = ringCenter(v)
            val display = FxDisplays.spawn(world, center, Items.PAPER, RING_MODEL, Quaternionf(), 0.01f, fullBright = true)
            rings += Ring(v.uuid, world, display, scale, if (i % 2 == 0) 1f else -1f)
        }
    }

    /** The Blood Nova shatters the rings: they flare up, grow and vanish in a spray of blood. */
    fun burstRings(victims: Collection<UUID>) {
        for (r in rings) if (r.victim in victims && r.burst < 0) r.burst = 0
    }

    private fun ringCenter(v: LivingEntity): Vec3d = v.entityPos.add(0.0, v.height * 0.45, 0.0)

    private fun tickRing(r: Ring): Boolean {
        val world = r.world
        val victim = world.getEntity(r.victim) as? LivingEntity
        val center = if (victim != null && victim.isAlive) ringCenter(victim) else r.lastCenter ?: return false
        if (victim == null || !victim.isAlive) if (r.burst < 0) r.burst = 0
        r.lastCenter = center
        r.age++
        r.spin += 0.62f * r.dir

        var scale = r.scale * min(1f, r.age / GROW_TICKS.toFloat())
        if (r.burst >= 0) {
            r.burst++
            scale = r.scale * (1f + 0.22f * r.burst)
            if (r.burst == 1) {
                BloodFx.spawn(world, BloodFx.crumbs, center, 16, 0.5, 0.15, 0.25)
                BloodFx.spawn(world, ParticleTypes.CRIT, center, 20, 0.6, 0.6)
                BloodFx.sound(world, center, SoundEvents.ENTITY_ITEM_BREAK.value(), 1.0f, 0.6f)
            }
            if (r.burst >= BURST_TICKS) return false
        }
        // wobbling slightly while it spins - reads as 3D from every angle
        val wobble = (sin(r.age * 0.16) * 0.16).toFloat()
        val rot = Quaternionf().rotationX(wobble).rotateZ(wobble * 0.6f).rotateY(r.spin)
        r.display?.let { FxDisplays.update(it, center, rot, scale) }

        // sparks and blood flung tangentially off the teeth
        val radius = RING_OUTER * scale
        if (r.age % 2 == 0) {
            for (k in 0 until 2) {
                val a = -r.spin.toDouble() + k * PI
                val rim = center.add(cos(a) * radius, 0.0, sin(a) * radius)
                val tangent = Vec3d(-sin(a) * r.dir, 0.15, cos(a) * r.dir)
                BloodFx.spawn(world, ParticleTypes.CRIT, rim.x, rim.y, rim.z, 0, tangent.x, tangent.y, tangent.z, 0.45)
                BloodFx.spawn(world, BloodFx.crumbs, rim.x, rim.y, rim.z, 0, tangent.x, tangent.y, tangent.z, 0.3)
            }
        }
        if (r.age % 7 == 0) BloodFx.sound(world, center, SoundEvents.BLOCK_GRINDSTONE_USE, 0.55f, 1.5f)
        return true
    }

    // ------------------------------------------------------------------ spikes

    /** the thorn stabs up in three jerks: 40 % -> 70 % -> full length (into the victim) */
    private val STAB_LEVELS = doubleArrayOf(0.14, 0.4, 0.7, 1.0)
    private const val STAB_UP = 5       // ticks of one jerk upwards (ease-out, slight overshoot)
    private const val STAB_REST = 7     // ticks it springs back and waits before the next jerk
    private const val STAB = STAB_UP + STAB_REST
    private const val THRUST = STAB * 3
    private const val SETTLE = 0
    private const val PEEK = 0.14       // how far the tip pokes out while the ground still rumbles

    /** rumble + three stabs: ticks until the thorn stands at full length */
    fun riseTicks(rumble: Int) = rumble + THRUST + SETTLE

    /**
     * Impaling spike under a floating victim. Animation (transformation only - smooth on the client):
     *  1. rumble: the ground cracks in a widening ring, the tip pokes out and trembles
     *  2. thrust: the thorn shoots up out of the ground (ease-out), fat at first, twisting into place,
     *     overshooting a little
     *  3. settle: springs back to its length - its tip is inside the victim
     *  4. hold, then it sinks back into the ground
     * Its hitbox damages everything it passes through on the way up.
     */
    fun impale(world: ServerWorld, owner: LivingEntity, victim: LivingEntity, rumble: Int, hold: Int, damage: Float) {
        val ground = groundBelow(world, victim.entityPos) ?: return
        // a GIANT thorn: tip ends up around the chest/head, base about 1.4 blocks wide for a player
        val reach = (victim.y + victim.height * 0.85 - ground.y).coerceIn(1.0, 12.0)
        val thick = (2.6 + victim.width * 1.4).toFloat().coerceAtMost(6.0f)
        val scale = Vector3f(thick, thick, reach.toFloat())
        val rot = Quaternionf().rotationY(world.random.nextFloat() * (2 * PI).toFloat()).rotateX((-PI / 2).toFloat())
        // the entity stays AT the ground; only its transformation moves (buried = offset -length)
        val display = FxDisplays.spawn(world, ground, Items.PAPER, SPIKE_MODEL, rot, 0.01f, fullBright = true, teleportTicks = 0)
        display?.let {
            FxDisplays.cullBox(it, (thick * 0.5f + 1f), reach.toFloat() + 1f)
            FxDisplays.animate(it, Vector3f(0f, (-reach * 0.5 - 0.3).toFloat(), 0f), rot, Vector3f(scale.x * 0.7f, scale.y * 0.7f, scale.z), 1)
        }
        // a pool of blood spreads where it will break out
        val pool = FxDisplays.spawn(world, ground.add(0.0, 0.03, 0.0), Items.PAPER, POOL_MODEL, Quaternionf(), 0.01f, teleportTicks = 0)
        val below = world.getBlockState(net.minecraft.util.math.BlockPos.ofFloored(ground.x, ground.y - 0.05, ground.z))
        // stab 1 and 2 must stay clearly below the floating victim (incl. their overshoot) - only stab 3 goes in
        val feet = ((victim.y - ground.y - 0.45) / reach).coerceIn(0.2, 0.85)
        val levels = doubleArrayOf(0.14, (feet * 0.55).coerceAtLeast(0.16), (feet - 0.08).coerceAtLeast(0.2), 1.0)
        spikes += Spike(world, ground, display, rot, scale, rumble, hold, 10, victim.uuid, owner.uuid, damage, pool = pool, groundState = if (below.isAir) null else below, levels = levels)
        BloodFx.shake(world, ground, 20.0, 0.9f, rumble + 4)
        BloodFx.sound(world, ground, BloodSounds.SPIKE_RUMBLE, 2.0f, 1.0f)   // own sound: the ground trembles and cracks
    }

    private fun groundBelow(world: ServerWorld, at: Vec3d): Vec3d? {
        val from = at.add(0.0, 1.5, 0.0)
        val hit = world.raycast(RaycastContext(from, from.add(0.0, -14.0, 0.0), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, ShapeContext.absent()))
        return if (hit.type == HitResult.Type.BLOCK) hit.pos else null
    }

    private fun tickSpike(s: Spike): Boolean {
        val world = s.world
        s.age++
        val t = s.age
        val thrustEnd = s.rumble + THRUST
        val settleEnd = thrustEnd + SETTLE
        val holdEnd = settleEnd + s.hold
        if (t > holdEnd + s.sink) return false

        // out: 0 = buried, 1 = fully out | fat: thickness factor | twist: spin around its own axis | jitter: tremble
        var out: Double
        var fat = 1.0
        var twist = 0.0
        var jx = 0.0
        var jz = 0.0
        when {
            s.single -> {
                val u = t - s.delay
                out = when {
                    u <= 0 -> 0.0
                    u <= 4 -> { val k = u / 4.0; 1.1 * (1 - (1 - k) * (1 - k) * (1 - k)) }   // one hard thrust
                    u <= 6 -> 1.1 - 0.1 * (u - 4) / 2.0
                    u <= 6 + s.hold -> 1.0
                    else -> { val k = ((u - 6 - s.hold) / s.sink.toDouble()).coerceAtMost(1.0); 1.0 - k * k }
                }
                if (u == 1) {
                    s.groundState?.let { BloodFx.spawn(world, net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, it), s.base.add(0.0, 0.1, 0.0), 10, 0.2, 0.25) }
                    BloodFx.spawn(world, BloodFx.crumbs, s.base.add(0.0, 0.1, 0.0), 6, 0.2, 0.25)
                }
                if (u > 6 + s.hold + s.sink) return false
            }
            t <= s.rumble -> {
                val k = t / s.rumble.toDouble()
                out = PEEK * k * k
                // real chunks of the ground jump up, blood flames lick out of the cracks
                s.groundState?.let { BloodFx.spawn(world, net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, it), s.base.add(0.0, 0.1, 0.0), 3, s.radius * (0.4 + k), 0.05, s.radius * (0.4 + k), 0.15) }
                if (t % 2 == 0) {
                    val a = world.random.nextDouble() * PI * 2
                    val r = s.radius * (0.6 + k) * world.random.nextDouble()
                    BloodFx.spawn(world, BloodFx.flame, s.base.x + cos(a) * r, s.base.y + 0.1, s.base.z + sin(a) * r, 0, 0.0, 1.0, 0.0, 0.06 + 0.06 * k)
                }
                fat = 0.7
                jx = (world.random.nextDouble() - 0.5) * 0.12
                jz = (world.random.nextDouble() - 0.5) * 0.12
                // the ground cracks open in a widening ring, chunks hop, dust rises
                BloodFx.spawn(world, BloodFx.crumbs, s.base.x, s.base.y + 0.1, s.base.z, 3, s.radius * (0.3 + k), 0.0, s.radius * (0.3 + k), 0.18)
                BloodFx.ring(world, s.base.add(0.0, 0.06, 0.0), s.radius * (0.5 + k * 1.2), BloodFx.dust(BloodFx.DARK, 1.2f), 18, t * 0.5)
                if (t % 2 == 0) BloodFx.spawn(world, ParticleTypes.CRIT, s.base.add(0.0, 0.15, 0.0), 4, s.radius * 0.7, 0.08)
            }
            t <= thrustEnd -> {
                val tt = t - s.rumble - 1                            // 0 .. THRUST-1
                val stage = tt / STAB                                // 0, 1, 2
                val within = tt % STAB + 1                           // 1 .. STAB
                val from = s.levels[stage]
                val to = s.levels[stage + 1]
                val over = to + (if (stage == 2) 0.08 else 0.05)     // every jerk overshoots a bit, the last one most
                val overall = (tt + 1) / THRUST.toDouble()
                if (within <= STAB_UP) {
                    val k = within / STAB_UP.toDouble()
                    val e = 1 - (1 - k) * (1 - k) * (1 - k)          // ease-out: hard jerk, then it slows
                    out = from + (over - from) * e
                    fat = 0.75 + 0.1 * stage + 0.35 * sin(k * PI)     // bulges with every jerk
                    // a jet of blood and dirt rides up with the tip
                    val tip = s.base.add(0.0, s.length * out, 0.0)
                    BloodFx.spawn(world, BloodFx.crumbs, tip, 3, s.radius * 0.3, 0.2, s.radius * 0.3, 0.2)
                    BloodFx.spawn(world, BloodFx.dust(BloodFx.BRIGHT, 1.2f), tip, 2, s.radius * 0.4, 0.2, s.radius * 0.4, 0.0)
                } else {
                    val k = (within - STAB_UP) / STAB_REST.toDouble()
                    out = over + (to - over) * k                      // springs back to the stage length
                    fat = 0.75 + 0.1 * stage + (if (stage == 2) 0.05 * k else 0.0)
                }
                if (stage == 2 && within > STAB_UP) fat = 1.0
                twist = (1 - overall) * 2.0
                if (within == 1) stabFx(s, stage)
            }
            t <= holdEnd -> out = 1.0
            else -> {
                val k = (t - holdEnd) / s.sink.toDouble()
                out = 1.0 - k * k
                fat = 1.0 - 0.2 * k
            }
        }
        val rot = Quaternionf(s.rotation).rotateZ(twist.toFloat())
        val sc = Vector3f((s.scale.x * fat).toFloat(), (s.scale.y * fat).toFloat(), s.scale.z)
        // model base sits at -length/2 from its centre: offset = axis * length * (out - 0.5) puts the tip at base + axis*length*out
        val along = s.length * (out - 0.5)
        val offset = Vector3f((s.axis.x * along + jx).toFloat(), (s.axis.y * along).toFloat(), (s.axis.z * along + jz).toFloat())
        s.display?.let { FxDisplays.animate(it, offset, rot, sc, 1) }
        // the blood pool under it: spreads during the rumble, stays, shrinks while the thorn sinks
        s.pool?.let {
            val grow = if (t <= s.rumble) t / s.rumble.toDouble() else 1.0
            val shrink = if (t > holdEnd) 1.0 - ((t - holdEnd) / s.sink.toDouble()).coerceAtMost(1.0) else 1.0
            val d = (s.radius * 4.2 * (1 - (1 - grow) * (1 - grow)) * shrink).toFloat().coerceAtLeast(0.01f)
            FxDisplays.animate(it, Vector3f(), Quaternionf().rotationY(t * 0.01f), Vector3f(d, d, d), 1)
        }

        // hitbox: everything the thorn passes through while it shoots out is hit once
        // only the third stab is lethal - the first two are the build-up and stop below the victim
        if (t > s.rumble + 2 * STAB && t <= settleEnd + 1 && s.damage > 0f) hitWithSpike(s, out)

        // impaled: blood running down the spike, dripping off the victim
        val victim = s.victim?.let { world.getEntity(it) as? LivingEntity }
        if (victim != null && victim.isAlive && t > settleEnd && t <= holdEnd) {
            val body = victim.entityPos.add(0.0, victim.height * 0.5, 0.0)
            // a fountain of blood bursts out of the impaled body for a moment
            if (t - settleEnd <= 16) {
                val top = victim.entityPos.add(0.0, victim.height * 0.8, 0.0)
                BloodFx.spawn(world, BloodFx.crumbs, top, 5, 0.15, 0.1, 0.15, 0.35)
                BloodFx.spawn(world, BloodFx.dust(BloodFx.BRIGHT, 1.4f), top, 3, 0.2, 0.3, 0.2, 0.0)
                if (t % 3 == 0) BloodFx.spawn(world, BloodFx.dropsFalling, top, 4, 0.5, 0.2, 0.5, 0.0)
            }
            if (t % 2 == 0) BloodFx.spawn(world, BloodFx.dropsFalling, s.base.x, s.base.y + s.length * world.random.nextDouble(), s.base.z, 2, s.radius * 0.4, 0.0, s.radius * 0.4, 0.0)
            if (t % 3 == 0) BloodFx.spawn(world, BloodFx.dust(BloodFx.BLOOD, 1.0f), body, 3, 0.2, 0.25, 0.2, 0.0)
            if (t % 10 == 0) BloodFx.sound(world, body, SoundEvents.BLOCK_POINTED_DRIPSTONE_DRIP_LAVA, 0.9f, 0.6f)
        }
        if (t == holdEnd + 1) BloodFx.sound(world, s.base, BloodSounds.SPIKE_RUMBLE, 1.0f, 1.4f)
        return true
    }
    /** Every jerk: ground thump + crack; the first one is the big break-out. */
    private fun stabFx(s: Spike, stage: Int) {
        val world = s.world
        val g = s.base
        // every jerk: the camera shakes harder, a shockwave of blood and dust races outwards, real ground chunks fly
        BloodFx.shake(world, g, 24.0, 1.4f + stage * 0.9f, 8 + stage * 4)
        for (i in 0 until 28) {
            val a = i / 28.0 * PI * 2
            BloodFx.spawn(world, BloodFx.dust(if (i % 2 == 0) BloodFx.BRIGHT else BloodFx.DARK, 1.5f), g.x + cos(a) * s.radius, g.y + 0.15, g.z + sin(a) * s.radius, 0, cos(a), 0.02, sin(a), 0.45 + stage * 0.15)
        }
        s.groundState?.let { BloodFx.spawn(world, net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, it), g.add(0.0, 0.2, 0.0), 25 + stage * 15, s.radius * 0.8, 0.2, s.radius * 0.8, 0.3 + stage * 0.1) }
        if (stage == 2) crown(s)
        if (stage == 0) {
            burstOut(s)
            return
        }
        for (i in 0 until 10) {
            val a = i / 10.0 * PI * 2 + stage
            BloodFx.spawn(world, BloodFx.crumbs, g.x + cos(a) * s.radius, g.y + 0.1, g.z + sin(a) * s.radius, 0, cos(a), 0.6, sin(a), 0.3)
        }
        BloodFx.spawn(world, ParticleTypes.CRIT, g.add(0.0, 0.2, 0.0), 6, s.radius * 0.6, 0.1)
        BloodFx.sound(world, g, BloodSounds.SPIKE_STAB[stage], 2.0f, 1.0f)
    }

    /** The moment the thorn breaks out: the ground bursts, chunks fly, a crown of blood flames. */
    private fun burstOut(s: Spike) {
        val world = s.world
        val g = s.base
        for (i in 0 until 20) {
            val a = i / 20.0 * PI * 2
            BloodFx.spawn(world, BloodFx.crumbs, g.x + cos(a) * s.radius, g.y + 0.1, g.z + sin(a) * s.radius, 0, cos(a), 0.7, sin(a), 0.45)
            if (i % 2 == 0) BloodFx.spawn(world, BloodFx.flame, g.x + cos(a) * s.radius * 1.2, g.y + 0.1, g.z + sin(a) * s.radius * 1.2, 0, 0.0, 1.0, 0.0, 0.16)
        }
        BloodFx.spawn(world, ParticleTypes.EXPLOSION, g.add(0.0, 0.3, 0.0), 1)
        BloodFx.spawn(world, BloodFx.splat, g.add(0.0, 0.4, 0.0), 4, s.radius, 0.0)
        BloodFx.sound(world, g, BloodSounds.SPIKE_STAB[0], 2.0f, 1.0f)
    }

    /** Last stab: four smaller thorns break out around the big one, leaning outwards - a crown of thorns. */
    private fun crown(s: Spike) {
        val world = s.world
        val count = 4
        val off = world.random.nextDouble() * PI
        for (i in 0 until count) {
            val a = off + i * 2 * PI / count + (world.random.nextDouble() - 0.5) * 0.5
            val out = Vec3d(cos(a), 0.0, sin(a))
            val lean = 0.45 + world.random.nextDouble() * 0.25
            val axis = out.multiply(sin(lean)).add(0.0, cos(lean), 0.0).normalize()
            val base = Vec3d(s.base.x + out.x * s.radius * 0.9, s.base.y, s.base.z + out.z * s.radius * 0.9)
            val len = (s.length * (0.32 + world.random.nextDouble() * 0.18)).toFloat()
            val thick = (s.scale.x * (0.42 + world.random.nextDouble() * 0.12)).toFloat()
            val rot = Quaternionf().rotationTo(0f, 0f, 1f, axis.x.toFloat(), axis.y.toFloat(), axis.z.toFloat()).rotateZ(world.random.nextFloat() * 6.28f)
            val display = FxDisplays.spawn(world, base, Items.PAPER, SPIKE_MODEL, rot, 0.01f, fullBright = true, teleportTicks = 0)
            val scale = Vector3f(thick, thick, len)
            display?.let {
                FxDisplays.cullBox(it, thick * 0.4f + 1f, len + 1f)
                val hidden = axis.multiply(-len * 0.5 - 0.2)
                FxDisplays.animate(it, Vector3f(hidden.x.toFloat(), hidden.y.toFloat(), hidden.z.toFloat()), rot, scale, 1)
            }
            spikes += Spike(world, base, display, rot, scale, 0, s.hold - 2, s.sink, axis = axis, single = true, delay = 1 + i, groundState = s.groundState)
        }
    }

    private fun hitWithSpike(s: Spike, out: Double) {
        val world = s.world
        // owner = a player (Nihil) or the Blood Prince boss (hits players only)
        val owner = s.owner?.let { world.getEntity(it) as? LivingEntity } ?: return
        val ownerPlayer = owner as? ServerPlayerEntity
        val tip = s.base.y + s.length * out
        val r = s.radius * 0.85
        val box = Box(s.base.x - r, s.base.y, s.base.z - r, s.base.x + r, tip, s.base.z + r)
        for (e in world.getEntitiesByClass(LivingEntity::class.java, box) { it.isAlive && it !== owner && (if (ownerPlayer != null) Targeting.shouldHarm(ownerPlayer, it) else it is net.minecraft.entity.player.PlayerEntity) }) {
            if (!s.hit.add(e.uuid)) continue
            e.timeUntilRegen = 0
            e.damage(world, if (ownerPlayer != null) world.damageSources.playerAttack(ownerPlayer) else world.damageSources.mobAttack(owner), s.damage)
            Bleed.apply(e, source = owner)
            val body = e.entityPos.add(0.0, e.height * 0.5, 0.0)
            BloodFx.wound(world, body, 4)
            BloodFx.spawn(world, BloodFx.crumbs, body, 24, 0.25, 0.2, 0.25, 0.35)
            BloodFx.spawn(world, BloodFx.flame, body, 10, 0.3, 0.3, 0.3, 0.03)
            BloodFx.sound(world, body, BloodSounds.SPIKE_IMPALE, 2.5f, 1.0f)   // own sound: the thorn goes through
            // the RED FLASH (Mats: "sichtbarer"): several bright flashes around the body, a burst of glowing
            // blood flames and big bright dust in all directions, an expanding ring - plus a short red
            // full-screen flash for everybody close by
            val flashFx = net.minecraft.particle.TintedParticleEffect.create(ParticleTypes.FLASH, 0xFF1010)
            BloodFx.spawn(world, flashFx, body, 1)
            for (k in 0 until 4) {
                val a = k * PI / 2
                BloodFx.spawn(world, flashFx, body.x + cos(a) * 0.6, body.y + (k % 2) * 0.5 - 0.25, body.z + sin(a) * 0.6, 1)
            }
            BloodFx.spawn(world, BloodFx.flame, body, 30, 0.2, 0.2, 0.2, 0.18)
            for (i in 0 until 36) {
                val a = i / 36.0 * PI * 2
                BloodFx.spawn(world, BloodFx.dust(BloodFx.BRIGHT, 2.4f), body.x, body.y, body.z, 0, cos(a), (i % 3 - 1) * 0.35, sin(a), 0.55)
            }
            BloodFx.spawn(world, ParticleTypes.EXPLOSION, body, 1)
            BloodFx.screenFlash(world, body, 26.0, 0xC00010, 0.55f, 9)
            BloodFx.shake(world, body, 28.0, 3.2f, 18)
        }
    }
    // ------------------------------------------------------------------ tick

    fun tick() {
        if (rings.isNotEmpty()) {
            val it = rings.iterator()
            while (it.hasNext()) {
                val r = it.next()
                if (!tickRing(r)) {
                    FxDisplays.remove(r.display)
                    it.remove()
                }
            }
        }
        if (spikes.isNotEmpty()) {
            // iterate over a copy: a spike's last stab adds the crown thorns to `spikes` while we tick
            // (doing that on the live list crashed the server with a ConcurrentModificationException)
            val done = ArrayList<Spike>()
            for (s in ArrayList(spikes)) {
                if (!tickSpike(s)) {
                    FxDisplays.remove(s.display)
                    FxDisplays.remove(s.pool)
                    done += s
                }
            }
            spikes.removeAll(done.toSet())
        }
    }
}
