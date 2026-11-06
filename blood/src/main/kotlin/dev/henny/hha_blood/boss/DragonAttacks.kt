package dev.henny.hha_blood.boss

import dev.henny.hha_blood.logic.BloodFx
import dev.henny.hha_blood.logic.BloodSounds
import dev.henny.hha_blood.logic.FxDisplays
import net.minecraft.entity.decoration.DisplayEntity
import net.minecraft.item.Items
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvents
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Projectiles/zones of Cruorax: blood breath (+ bleeding pools where it lands), falling blood-crystal
 * stalactites (marked on the floor first) and the heartbeat shock rings. They only hurt players.
 */
object DragonAttacks {

    private class Breath(val owner: UUID, val world: ServerWorld, var pos: Vec3d, val vel: Vec3d, var life: Int = 40)
    private class Pool(val owner: UUID, val world: ServerWorld, val at: Vec3d, val display: DisplayEntity.ItemDisplayEntity?, var life: Int = 120)
    private class Stalactite(
        val owner: UUID, val world: ServerWorld, val ground: Vec3d, var y: Double, var speed: Double = 0.0,
        var warn: Int = 26, var display: DisplayEntity.ItemDisplayEntity? = null,
    )

    private val breaths = ArrayList<Breath>()
    private val pools = ArrayList<Pool>()
    private val stalactites = ArrayList<Stalactite>()
    private val ringHit = HashMap<UUID, Int>()

    fun reset() {
        breaths.clear()
        pools.forEach { FxDisplays.remove(it.display) }; pools.clear()
        stalactites.forEach { FxDisplays.remove(it.display) }; stalactites.clear()
    }

    fun clearOwner(owner: UUID) {
        breaths.removeIf { it.owner == owner }
        pools.removeIf { if (it.owner == owner) { FxDisplays.remove(it.display); true } else false }
        stalactites.removeIf { if (it.owner == owner) { FxDisplays.remove(it.display); true } else false }
    }

    // ------------------------------------------------------------------------------------- breath

    fun breath(world: ServerWorld, owner: CruoraxEntity, from: Vec3d, dir: Vec3d) {
        breaths += Breath(owner.uuid, world, from, dir.normalize().multiply(1.15))
    }

    private fun tickBreath(b: Breath): Boolean {
        val world = b.world
        val owner = world.getEntity(b.owner) as? CruoraxEntity ?: return false
        val next = b.pos.add(b.vel)
        BloodFx.spawn(world, BloodFx.flame, b.pos, 3, 0.25, 0.25, 0.25, 0.02)
        BloodFx.spawn(world, BloodFx.dust(BloodFx.BRIGHT, 2.2f), b.pos, 2, 0.3, 0.3, 0.3, 0.0)
        for (p in HeartVaultArena.playersInArena(world)) {
            if (p.boundingBox.expand(0.8).raycast(b.pos, next).isPresent) {
                p.timeUntilRegen = 0
                owner.hurt(world, p, 3f, 60)
            }
        }
        val hit = world.raycast(RaycastContext(b.pos, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, owner))
        if (hit.type == HitResult.Type.BLOCK || --b.life <= 0) {
            val at = if (hit.type == HitResult.Type.BLOCK) hit.pos else next
            BloodFx.spawn(world, BloodFx.crumbs, at, 6, 0.4, 0.1, 0.4, 0.15)
            // a new pool only if there is none close by
            if (hit.type == HitResult.Type.BLOCK && hit.side == net.minecraft.util.math.Direction.UP && pools.none { it.at.squaredDistanceTo(at) < 6.0 }) {
                val d = FxDisplays.spawn(world, at.add(0.0, 0.04, 0.0), Items.PAPER, "blood_pool", Quaternionf(), 3.2f, teleportTicks = 0)
                pools += Pool(b.owner, world, at, d)
                BloodFx.sound(world, at, SoundEvents.ENTITY_PLAYER_SPLASH, 1.2f, 0.5f)
            }
            return false
        }
        b.pos = next
        return true
    }

    private fun tickPool(p: Pool): Boolean {
        val world = p.world
        val owner = world.getEntity(p.owner) as? CruoraxEntity
        if (owner == null || --p.life <= 0) { FxDisplays.remove(p.display); return false }
        if (p.life % 4 == 0) BloodFx.spawn(world, BloodFx.flame, p.at.add(0.0, 0.2, 0.0), 1, 1.0, 0.0, 1.0, 0.01)
        if (p.life % 20 == 0) {
            for (pl in HeartVaultArena.playersInArena(world)) {
                if (pl.squaredDistanceTo(p.at) < 2.6 * 2.6 && kotlin.math.abs(pl.y - p.at.y) < 1.5) {
                    pl.timeUntilRegen = 0
                    owner.hurt(world, pl, 2f, 60)
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------------------------------- stalactites

    fun stalactite(world: ServerWorld, owner: CruoraxEntity, ground: Vec3d) {
        val top = ground.y + 26.0
        stalactites += Stalactite(owner.uuid, world, ground, top)
    }

    private val SPIKE_DOWN: Quaternionf get() = Quaternionf().rotationX((PI / 2).toFloat())

    private fun tickStalactite(s: Stalactite): Boolean {
        val world = s.world
        val owner = world.getEntity(s.owner) as? CruoraxEntity
        if (owner == null) { FxDisplays.remove(s.display); return false }
        if (s.warn > 0) {
            // red circle on the floor where it will hit
            BloodFx.ring(world, s.ground.add(0.0, 0.15, 0.0), 2.2, BloodFx.dust(BloodFx.BRIGHT, 1.3f), 16, rotation = s.warn * 0.2)
            if (--s.warn == 0) {
                s.display = FxDisplays.spawn(world, Vec3d(s.ground.x, s.y, s.ground.z), Items.PAPER, "blood_spike", SPIKE_DOWN, 1.0f, fullBright = true, teleportTicks = 1)
                s.display?.let { FxDisplays.update(it, Vec3d(s.ground.x, s.y, s.ground.z), SPIKE_DOWN, Vector3f(1.6f, 1.6f, 3.2f)) }
            }
            return true
        }
        s.speed = (s.speed + 0.12).coerceAtMost(2.2)
        s.y -= s.speed
        if (s.y <= s.ground.y + 3.0) {
            val at = s.ground
            BloodFx.spawn(world, BloodFx.crumbs, at.add(0.0, 0.5, 0.0), 40, 1.0, 0.4, 1.0, 0.35)
            BloodFx.spawn(world, ParticleTypes.EXPLOSION, at.add(0.0, 0.5, 0.0), 1)
            BloodFx.sound(world, at, SoundEvents.BLOCK_GLASS_BREAK, 2.0f, 0.5f)
            BloodFx.sound(world, at, BloodSounds.SPIKE_IMPALE, 1.5f, 1.3f)
            for (p in HeartVaultArena.playersInArena(world)) {
                if (p.squaredDistanceTo(at) < 2.4 * 2.4 && p.y - at.y < 3.5) {
                    owner.hurt(world, p, 11f, 80)
                    owner.push(p, p.entityPos.subtract(at).multiply(1.0, 0.0, 1.0).normalize().multiply(0.6).add(0.0, 0.4, 0.0))
                }
            }
            FxDisplays.remove(s.display)
            return false
        }
        s.display?.let { FxDisplays.update(it, Vec3d(s.ground.x, s.y, s.ground.z), SPIKE_DOWN, Vector3f(1.6f, 1.6f, 3.2f)) }
        BloodFx.spawn(world, BloodFx.dust(BloodFx.BRIGHT, 1.2f), Vec3d(s.ground.x, s.y + 1.0, s.ground.z), 2, 0.2, 0.6, 0.2, 0.0)
        return true
    }

    // ------------------------------------------------------------------------------------- shock ring

    /** Draws the shock ring at [radius] and hits players standing on the floor in its band (jump to dodge). */
    fun shockRing(world: ServerWorld, owner: CruoraxEntity, center: Vec3d, radius: Double, last: Boolean) {
        if (radius < 1.0) return
        val points = (radius * 5).toInt().coerceIn(12, 260)
        BloodFx.ring(world, center.add(0.0, 0.2, 0.0), radius, BloodFx.flame, points)
        if (points > 40) BloodFx.ring(world, center.add(0.0, 0.5, 0.0), radius, BloodFx.dust(BloodFx.BRIGHT, 1.6f), points / 2)
        for (p in HeartVaultArena.playersInArena(world)) {
            val d = Math.hypot(p.x - center.x, p.z - center.z)
            if (kotlin.math.abs(d - radius) < 1.2 && p.isOnGround && p.y - center.y < 1.5) {
                val last = ringHit[p.uuid]
                if (last != null && owner.age - last < 10) continue
                ringHit[p.uuid] = owner.age
                owner.hurt(world, p, 9f, 60)
                owner.push(p, Vec3d(0.0, 0.9, 0.0).add(Vec3d(p.x - center.x, 0.0, p.z - center.z).normalize().multiply(0.6)))
            }
        }
        @Suppress("UNUSED_EXPRESSION") last
    }

    fun tick() {
        if (breaths.isNotEmpty()) breaths.removeIf { !tickBreath(it) }
        if (pools.isNotEmpty()) pools.removeIf { !tickPool(it) }
        if (stalactites.isNotEmpty()) stalactites.removeIf { !tickStalactite(it) }
    }

    @Suppress("unused") private fun circle(r: Double, a: Double) = Vec3d(cos(a) * r, 0.0, sin(a) * r)
}
