package dev.henny.hha_blood.logic

import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.Targeting
import dev.henny.hha_blood.BloodItems
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.decoration.DisplayEntity
import net.minecraft.item.Items
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvents
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import org.joml.Quaternionf
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Blood Mace active - "Blood Cyclone".
 *
 * The mace is hurled up and stays hovering where you threw it (it does not follow you). It spins
 * tilted like a spinning top (3D model `hha_blood:blood_mace_3d`) and fires glowing 3D blood bolts
 * (`hha_blood:blood_bolt`) in all directions + homing bolts at every enemy inside the circle. A
 * pulsing ring of blood on the ground shows exactly where you can be hit. Each hit damages and
 * triggers Infection. At the end the mace flies back into your hand.
 *
 * Your real item stays in your inventory (on cooldown) - nothing can get lost.
 */
object BloodCyclone {

    private class Bolt(
        val owner: UUID,
        val world: ServerWorld,
        var pos: Vec3d,
        var dir: Vec3d,
        val speed: Double,
        var life: Int,
        val homing: UUID?,
        val display: DisplayEntity.ItemDisplayEntity?,
    ) {
        val hit = HashSet<UUID>()
        var lastPos: Vec3d = pos
        var age = 0
    }

    private class Session(
        val owner: UUID,
        val world: ServerWorld,
        val start: Vec3d,
        val center: Vec3d,
        val ground: Vec3d,
        val total: Int,
        val display: DisplayEntity.ItemDisplayEntity?,
    ) {
        var age = 0
        var volleys = 0
        var returning = -1
        var lastMacePos: Vec3d = start
        var ringY: DoubleArray? = null
    }

    private val sessions = ArrayList<Session>()
    private val bolts = ArrayList<Bolt>()

    private const val RISE_TICKS = 16
    private const val RETURN_TICKS = 10
    private const val VOLLEY_EVERY = 4
    private const val MACE_SCALE = 2.6f   // model is ~20 px tall -> ~3.3 blocks, readable from 17 blocks below
    private const val BOLT_SCALE = 2.0f   // model is 8 px long -> 1 block
    private const val BOLT_SPEED = 1.6

    fun reset() {
        sessions.forEach { FxDisplays.remove(it.display) }
        bolts.forEach { FxDisplays.remove(it.display) }
        sessions.clear()
        bolts.clear()
    }

    fun cast(world: ServerWorld, player: ServerPlayerEntity): Boolean {
        val duration = HhaConfig.num("hha_blood.cyclone_duration").toInt().coerceAtLeast(RISE_TICKS + 20)
        val feet = player.entityPos
        val ground = findGround(world, feet.add(0.0, 0.5, 0.0), 6.0) ?: feet
        // hover high above the spot (cyclone_height, ~17 blocks) - but below a ceiling
        val hover = HhaConfig.num("hha_blood.cyclone_height").coerceIn(2.0, 40.0)
        val up = world.raycast(RaycastContext(feet.add(0.0, 1.0, 0.0), feet.add(0.0, hover + 2.0, 0.0), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player))
        val height = if (up.type == HitResult.Type.BLOCK) (up.pos.y - 2.0 - feet.y).coerceAtLeast(2.0) else hover
        val center = feet.add(0.0, height, 0.0)
        val start = feet.add(0.0, 1.3, 0.0)

        val display = FxDisplays.spawn(world, start, BloodItems.BLOOD_MACE, "blood_mace_3d", maceRotation(0, 0.0), MACE_SCALE, teleportTicks = 2)
        sessions += Session(player.uuid, world, start, center, ground, duration, display)

        BloodFx.spawn(world, BloodFx.crumbs, start, 6, 0.3, 0.3, 0.3, 0.2)
        for (i in 0..3) BloodFx.ring(world, feet.add(0.0, 0.2 + i * 0.9, 0.0), 0.9 + i * 0.25, BloodFx.dust(BloodFx.BRIGHT, 0.55f), 18)
        BloodFx.sound(world, feet, SoundEvents.ITEM_TRIDENT_THROW, 1.0f, 0.5f)
        BloodFx.sound(world, feet, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.5f)
        BloodFx.sound(world, feet, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.2f, 0.6f)
        return true
    }

    /**
     * Upright mace, tilted ~25 degrees, spinning fast around its own haft while the tilt itself
     * slowly circles (precession) - reads as a 3D spinning top from every side.
     */
    private fun maceRotation(age: Int, spinUp: Double): Quaternionf {
        val precess = (age * 0.22).toFloat()
        val tilt = (0.45 * spinUp).toFloat()
        val self = (age * 0.95 * (0.4 + 0.6 * spinUp)).toFloat()
        return Quaternionf().rotationY(precess).rotateZ(tilt).rotateY(self)
    }

    private fun findGround(world: ServerWorld, from: Vec3d, depth: Double): Vec3d? {
        val hit = world.raycast(RaycastContext(from, from.add(0.0, -depth, 0.0), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, net.minecraft.block.ShapeContext.absent()))
        return if (hit.type == HitResult.Type.BLOCK) hit.pos else null
    }

    private fun enemies(world: ServerWorld, player: ServerPlayerEntity, ground: Vec3d, range: Double): List<LivingEntity> =
        world.getEntitiesByClass(LivingEntity::class.java, Box.of(ground, range * 2, range * 2 + 8, range * 2)) {
            it.isAlive && it !== player && Targeting.shouldHarm(player, it) &&
                it.entityPos.subtract(ground).let { d -> d.x * d.x + d.z * d.z } <= range * range
        }

    fun tick() {
        if (sessions.isNotEmpty()) {
            val it = sessions.iterator()
            while (it.hasNext()) {
                val s = it.next()
                if (!tickSession(s)) {
                    FxDisplays.remove(s.display)
                    it.remove()
                }
            }
        }
        if (bolts.isNotEmpty()) {
            val it = bolts.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (!tickBolt(b)) {
                    FxDisplays.remove(b.display)
                    it.remove()
                }
            }
        }
    }

    private fun tickSession(s: Session): Boolean {
        val world = s.world
        val owner = world.getPlayerByUuid(s.owner) as? ServerPlayerEntity ?: return false
        if (!owner.isAlive) return false
        s.age++
        val range = HhaConfig.num("hha_blood.cyclone_range")

        // ---- flying back into the hand
        if (s.returning >= 0) {
            s.returning++
            val hand = owner.entityPos.add(0.0, 1.2, 0.0)
            val t = (s.returning.toDouble() / RETURN_TICKS).coerceAtMost(1.0)
            val p = s.lastMacePos.lerp(hand, t * t)
            s.display?.let { FxDisplays.update(it, p, maceRotation(s.age, 1.0 - t), MACE_SCALE * (1f - 0.4f * t.toFloat())) }
            BloodFx.spawn(world, BloodFx.fade(BloodFx.BRIGHT, BloodFx.BLACK, 0.5f), p, 2, 0.1)
            if (s.returning >= RETURN_TICKS) {
                catchMace(world, owner)
                return false
            }
            return true
        }

        // ---- rise, then hover at the fixed spot
        val rise = (s.age.toDouble() / RISE_TICKS).coerceAtMost(1.0)
        val ease = 1 - (1 - rise) * (1 - rise)
        val bob = if (rise >= 1.0) sin(s.age * 0.18) * 0.15 else 0.0
        val pos = s.start.lerp(s.center, ease).add(0.0, bob, 0.0)
        s.lastMacePos = pos
        s.display?.let { FxDisplays.update(it, pos, maceRotation(s.age, ease), MACE_SCALE) }

        // swirling blood around the spinning mace
        for (k in 0 until 3) {
            val a = s.age * 0.7 + k * 2 * PI / 3
            BloodFx.spawn(world, BloodFx.dust(BloodFx.BRIGHT, 0.55f), pos.x + cos(a) * 1.2, pos.y + sin(s.age * 0.3 + k) * 0.4, pos.z + sin(a) * 1.2)
        }
        if (s.age % 2 == 0) BloodFx.spawn(world, BloodFx.flame, pos, 2, 0.4, 0.4, 0.4, 0.01)
        if (s.age % 4 == 0) BloodFx.spawn(world, BloodFx.dropsFalling, pos, 1, 0.5)
        if (s.age % 4 == 0) BloodFx.sound(world, pos, SoundEvents.ENTITY_BREEZE_WIND_BURST, 1.6f, 1.1f)

        drawBorder(s, range)

        if (rise >= 1.0 && s.age % VOLLEY_EVERY == 0) volley(s, owner, pos, range)

        if (s.age >= s.total) {
            s.returning = 0
            BloodFx.sound(world, pos, SoundEvents.ITEM_TRIDENT_RETURN, 1.0f, 0.7f)
        }
        return true
    }

    /**
     * The danger zone, easy to see from far away: a solid, thick, pulsing ring of blood on the ground
     * hugging the terrain, 16 flame pillars rising from it, and a wave that sweeps from the centre to
     * the edge again and again.
     */
    private fun drawBorder(s: Session, range: Double) {
        val world = s.world
        val points = (range * 16).toInt().coerceIn(60, 220)
        val heights = s.ringY ?: DoubleArray(points) { i ->
            val a = i.toDouble() / points * PI * 2
            groundAt(world, s.ground.x + cos(a) * range, s.ground.z + sin(a) * range, s.ground.y)
        }.also { s.ringY = it }

        if (s.age % 2 == 0) {
            val pulse = 0.9f + 0.25f * sin(s.age * 0.25).toFloat()
            for (i in 0 until points) {
                val a = i.toDouble() / points * PI * 2
                val x = s.ground.x + cos(a) * range
                val z = s.ground.z + sin(a) * range
                BloodFx.spawn(world, BloodFx.dust(BloodFx.BRIGHT, pulse), x, heights[i] + 0.12, z)
                if (s.age % 4 == 0) BloodFx.spawn(world, BloodFx.dust(BloodFx.BLOOD, 0.6f), x, heights[i] + 0.4, z)
                // flame pillars: 16 evenly spread, flickering upwards
                if (i % (points / 16) == 0) {
                    BloodFx.spawn(world, BloodFx.flame, x, heights[i] + 0.1, z, 0, 0.0, 1.0, 0.0, 0.12)
                    BloodFx.spawn(world, BloodFx.flame, x, heights[i] + 0.7, z, 0, 0.0, 1.0, 0.0, 0.08)
                }
            }
        }
        // wave sweeping outwards from the centre
        val cycle = s.age % 30
        if (cycle < 18 && s.age % 2 == 0) {
            val r = range * (cycle + 1) / 18.0
            BloodFx.ring(world, s.ground.add(0.0, 0.15, 0.0), r, BloodFx.dust(BloodFx.DARK, 0.6f), (r * 10).toInt().coerceAtLeast(12))
        }
        // thin tether from the mace down to the centre
        if (s.age % 6 == 0) {
            val steps = (s.lastMacePos.y - s.ground.y).toInt().coerceIn(4, 40) * 2
            for (k in 0..steps) {
                val p = s.ground.lerp(s.lastMacePos, k / steps.toDouble())
                BloodFx.spawn(world, BloodFx.dust(BloodFx.DARK, 0.45f), p)
            }
        }
    }

    /** Ground height near [y] at x/z (so the ring hugs slopes and steps). */
    private fun groundAt(world: ServerWorld, x: Double, z: Double, y: Double): Double {
        val hit = findGround(world, Vec3d(x, y + 2.5, z), 6.0)
        return hit?.y ?: y
    }

    private fun volley(s: Session, owner: ServerPlayerEntity, from: Vec3d, range: Double) {
        val world = s.world
        val offset = s.volleys * 0.39
        val radial = 8
        val height = from.y - s.ground.y
        // radial bolts rain down inside the circle: each volley lands on a ring at 35..100 % of the radius
        val reach = range * (0.35 + 0.65 * ((s.volleys * 0.37) % 1.0))
        val dist = Math.sqrt(height * height + reach * reach)
        for (i in 0 until radial) {
            val a = offset + i * 2 * PI / radial
            val dir = Vec3d(cos(a) * reach, -height, sin(a) * reach).normalize()
            spawnBolt(world, owner, from, dir, BOLT_SPEED, (dist / BOLT_SPEED).toInt() + 3, null)
        }
        // guaranteed homing bolts for enemies inside the circle (every second volley, max 6)
        if (s.volleys % 2 == 0) {
            for (e in enemies(world, owner, s.ground, range).sortedBy { it.squaredDistanceTo(from) }.take(6)) {
                val target = e.entityPos.add(0.0, e.height * 0.6, 0.0)
                val life = (target.distanceTo(from) / BOLT_SPEED).toInt() + 12
                spawnBolt(world, owner, from, target.subtract(from).normalize(), BOLT_SPEED * 1.1, life, e.uuid)
            }
        }
        s.volleys++
        // loud enough to be heard on the ground below
        // Mats: the original firing sound was better (own synthesised one reverted)
        BloodFx.sound(world, from, SoundEvents.ENTITY_BREEZE_SHOOT, 2.0f, 0.6f + (s.volleys % 4) * 0.1f)
        BloodFx.sound(world, from, SoundEvents.ENTITY_BLAZE_SHOOT, 1.2f, 1.4f)
    }

    private fun spawnBolt(world: ServerWorld, owner: ServerPlayerEntity, from: Vec3d, dir: Vec3d, speed: Double, life: Int, homing: UUID?) {
        val display = FxDisplays.spawn(world, from, Items.PAPER, "blood_bolt", FxDisplays.facing(dir), BOLT_SCALE, fullBright = true)
        bolts += Bolt(owner.uuid, world, from, dir, speed, life, homing, display)
    }

    private fun tickBolt(b: Bolt): Boolean {
        val world = b.world
        val owner = world.getPlayerByUuid(b.owner) as? ServerPlayerEntity ?: return false
        b.life--
        b.age++
        if (b.life <= 0) {
            BloodFx.spawn(world, BloodFx.crumbs, b.pos, 1, 0.05)
            return false
        }

        if (b.homing != null) {
            val target = world.getEntity(b.homing) as? LivingEntity
            if (target != null && target.isAlive) {
                b.dir = target.entityPos.add(0.0, target.height * 0.6, 0.0).subtract(b.pos).normalize()
            }
        }
        b.lastPos = b.pos
        val next = b.pos.add(b.dir.multiply(b.speed))
        val blocked = world.raycast(RaycastContext(b.pos, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner))
        if (blocked.type == HitResult.Type.BLOCK) {
            BloodFx.spawn(world, BloodFx.crumbs, blocked.pos, 2, 0.1, 0.1, 0.1, 0.1)
            // a dry projectile "thunk" (arrow hit) - no water splash, no synthetic crack
            return false
        }
        b.pos = next
        b.display?.let { FxDisplays.update(it, b.pos, FxDisplays.facing(b.dir), BOLT_SCALE) }

        // bright, thick trail so the bolts are easy to follow
        for (s in 0 until 3) {
            val p = b.lastPos.lerp(b.pos, s / 3.0)
            BloodFx.spawn(world, BloodFx.dust(BloodFx.BRIGHT, 0.45f), p, 1, 0.01)
        }
        BloodFx.spawn(world, BloodFx.fade(BloodFx.BRIGHT, BloodFx.BLACK, 0.4f), b.lastPos, 1, 0.03)
        if (b.age % 2 == 0) BloodFx.spawn(world, BloodFx.flame, b.lastPos, 1, 0.04)

        val damage = HhaConfig.num("hha_blood.cyclone_damage").toFloat()
        for (e in world.getEntitiesByClass(LivingEntity::class.java, Box.of(b.pos, 1.4, 1.4, 1.4)) {
            it.isAlive && it !== owner && Targeting.shouldHarm(owner, it)
        }) {
            if (!b.hit.add(e.uuid)) continue
            e.timeUntilRegen = 0
            e.damage(world, world.damageSources.playerAttack(owner), damage)
            Bleed.apply(e, source = owner)          // triggers the Infection bleed
            val body = e.entityPos.add(0.0, e.height * 0.55, 0.0)
            BloodFx.spawn(world, BloodFx.crumbs, body, 3, 0.15, 0.12)
            BloodFx.spawn(world, ParticleTypes.SWEEP_ATTACK, body, 1)
            BloodFx.sound(world, body, SoundEvents.ENTITY_ARROW_HIT, 0.9f, 0.7f)
            if (b.homing != null) return false
        }
        return true
    }

    private fun catchMace(world: ServerWorld, owner: ServerPlayerEntity) {
        val hand = owner.entityPos.add(0.0, 1.2, 0.0)
        BloodFx.spawn(world, BloodFx.crumbs, hand, 6, 0.3, 0.3, 0.3, 0.2)
        BloodFx.ring(world, owner.entityPos.add(0.0, 0.2, 0.0), 1.6, BloodFx.dust(BloodFx.BRIGHT, 0.55f), 24, 0.0, 0.1)
        BloodFx.sound(world, owner.entityPos, SoundEvents.ITEM_MACE_SMASH_GROUND, 0.9f, 1.1f)
        BloodFx.sound(world, owner.entityPos, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.2f, 0.8f)
    }
}
