package dev.henny.hha_blood.boss

import dev.henny.hha_blood.BloodItems
import dev.henny.hha_blood.logic.BloodFx
import dev.henny.hha_blood.logic.FxDisplays
import net.minecraft.entity.decoration.DisplayEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.Items
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvents
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import org.joml.Quaternionf
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Projectiles of the Blood Prince, simulated on the server and drawn with item displays (same 3D models
 * as the armour's abilities): Bloodsaws and the Blood Cyclone with its blood bolts. They only hurt players.
 */
object PrinceAttacks {

    private class Saw(
        val owner: UUID, val world: ServerWorld, var pos: Vec3d, var vel: Vec3d, val index: Int,
        val display: DisplayEntity.ItemDisplayEntity?, var life: Int = 90, var bounces: Int = 4, var spin: Float = 0f,
        val lastHit: HashMap<UUID, Int> = HashMap(),
    )

    private class Cyclone(
        val owner: UUID, val world: ServerWorld, val ground: Vec3d, val duration: Int,
        val mace: DisplayEntity.ItemDisplayEntity?, var age: Int = 0,
    )

    private class Bolt(
        val owner: UUID, val world: ServerWorld, var pos: Vec3d, var vel: Vec3d,
        val display: DisplayEntity.ItemDisplayEntity?, var life: Int = 60,
    )

    private val saws = ArrayList<Saw>()
    private val cyclones = ArrayList<Cyclone>()
    private val bolts = ArrayList<Bolt>()

    private const val SAW_SCALE = 2.1f
    private const val SAW_DAMAGE = 8f
    private const val BOLT_DAMAGE = 5f
    private const val CYCLONE_HEIGHT = 15.0
    private const val MACE_SCALE = 4.5f

    fun reset() {
        saws.forEach { FxDisplays.remove(it.display) }; saws.clear()
        cyclones.forEach { FxDisplays.remove(it.mace) }; cyclones.clear()
        bolts.forEach { FxDisplays.remove(it.display) }; bolts.clear()
    }

    fun clearOwner(owner: UUID) {
        saws.removeIf { if (it.owner == owner) { FxDisplays.remove(it.display); true } else false }
        cyclones.removeIf { if (it.owner == owner) { FxDisplays.remove(it.mace); true } else false }
        bolts.removeIf { if (it.owner == owner) { FxDisplays.remove(it.display); true } else false }
    }

    fun cycloneCount(owner: UUID) = cyclones.count { it.owner == owner }

    // ------------------------------------------------------------------------------------- saws

    private fun sawRotation(dir: Vec3d, index: Int, spin: Float): Quaternionf {
        val bank = if (index % 2 == 0) 0.45f else -0.45f
        return FxDisplays.facing(dir).rotateZ(bank).rotateX((-PI / 2).toFloat()).rotateZ(spin)
    }

    fun saw(world: ServerWorld, owner: BloodPrinceEntity, from: Vec3d, dir: Vec3d, index: Int) {
        val v = dir.normalize().multiply(0.95)
        val display = FxDisplays.spawn(world, from, Items.PAPER, "bloodsaw", sawRotation(v, index, 0f), SAW_SCALE, fullBright = true)
        saws += Saw(owner.uuid, world, from, v, index, display)
    }

    private fun tickSaw(s: Saw): Boolean {
        val world = s.world
        val owner = world.getEntity(s.owner) as? BloodPrinceEntity
        if (owner == null || !owner.isAlive || --s.life <= 0) { burst(world, s.pos); FxDisplays.remove(s.display); return false }
        // light homing on the closest player
        val target = HeartVaultArena.playersInArena(world).minByOrNull { it.squaredDistanceTo(s.pos) }
        if (target != null) {
            val want = target.entityPos.add(0.0, 1.0, 0.0).subtract(s.pos).normalize().multiply(s.vel.length())
            s.vel = s.vel.multiply(0.94).add(want.multiply(0.06)).normalize().multiply(0.95)
        }
        val next = s.pos.add(s.vel)
        val hit = world.raycast(RaycastContext(s.pos, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner))
        if (hit.type == HitResult.Type.BLOCK) {
            val side = hit.side
            s.vel = when (side.axis) {
                Direction.Axis.X -> Vec3d(-s.vel.x, s.vel.y, s.vel.z)
                Direction.Axis.Y -> Vec3d(s.vel.x, -s.vel.y, s.vel.z)
                else -> Vec3d(s.vel.x, s.vel.y, -s.vel.z)
            }
            s.pos = hit.pos.add(Vec3d.of(side.vector).multiply(0.3))
            BloodFx.spawn(world, BloodFx.crumbs, s.pos, 10, 0.2, 0.2, 0.2, 0.25)
            BloodFx.sound(world, s.pos, SoundEvents.BLOCK_ANVIL_LAND, 0.5f, 1.8f)
            if (--s.bounces < 0) { burst(world, s.pos); FxDisplays.remove(s.display); return false }
        } else s.pos = next
        s.spin += 0.9f
        s.display?.let { FxDisplays.update(it, s.pos, sawRotation(s.vel, s.index, s.spin), SAW_SCALE) }
        BloodFx.spawn(world, BloodFx.fade(BloodFx.BRIGHT, BloodFx.BLACK, 1.2f), s.pos, 1, 0.1)
        for (p in HeartVaultArena.playersInArena(world)) {
            if (p.boundingBox.expand(0.9).contains(s.pos)) {
                val last = s.lastHit[p.uuid]
                if (last != null && last - s.life < 12) continue
                s.lastHit[p.uuid] = s.life
                if (owner.hurt(world, p, SAW_DAMAGE, 80)) {
                    BloodFx.wound(world, p.entityPos.add(0.0, 1.0, 0.0), 2)
                    BloodFx.sound(world, s.pos, SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.7f)
                }
            }
        }
        if (s.life % 8 == 0) BloodFx.sound(world, s.pos, SoundEvents.ENTITY_BREEZE_WHIRL, 0.7f, 1.6f)
        return true
    }

    private fun burst(world: ServerWorld, at: Vec3d) {
        BloodFx.spawn(world, BloodFx.crumbs, at, 18, 0.3, 0.3, 0.3, 0.3)
        BloodFx.sound(world, at, SoundEvents.BLOCK_GLASS_BREAK, 0.8f, 0.6f)
    }

    // ------------------------------------------------------------------------------------- cyclone

    fun cyclone(world: ServerWorld, owner: BloodPrinceEntity, ground: Vec3d, duration: Int) {
        val mace = FxDisplays.spawn(world, ground.add(0.0, 2.0, 0.0), BloodItems.BLOOD_MACE, "blood_mace_3d", Quaternionf(), MACE_SCALE, fullBright = true, teleportTicks = 2)
        mace?.let { FxDisplays.cullBox(it, 6f, 8f) }
        cyclones += Cyclone(owner.uuid, world, ground, duration, mace)
        BloodFx.sound(world, ground, SoundEvents.ENTITY_BREEZE_CHARGE, 3.0f, 0.5f)
    }

    private fun tickCyclone(c: Cyclone): Boolean {
        val world = c.world
        val owner = world.getEntity(c.owner) as? BloodPrinceEntity
        c.age++
        if (owner == null || !owner.isAlive || c.age > c.duration) {
            c.mace?.let { BloodFx.spawn(world, BloodFx.crumbs, it.entityPos, 60, 1.0, 1.0, 1.0, 0.4) }
            BloodFx.sound(world, c.ground.add(0.0, CYCLONE_HEIGHT, 0.0), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 2.0f, 0.6f)
            FxDisplays.remove(c.mace)
            return false
        }
        val rise = (c.age / 30.0).coerceAtMost(1.0)
        val top = c.ground.add(0.0, 2.0 + (CYCLONE_HEIGHT - 2.0) * rise * (2 - rise), 0.0)
        val spin = c.age * 0.45f
        val rot = Quaternionf().rotationY(spin).rotateZ(0.6f).rotateX((PI / 2).toFloat())
        c.mace?.let { FxDisplays.update(it, top, rot, MACE_SCALE, 2) }
        // swirl + falling blood around it
        for (k in 0 until 3) {
            val a = c.age * 0.35 + k * 2 * PI / 3
            BloodFx.spawn(world, BloodFx.flame, top.add(cos(a) * 3.0, -1.0, sin(a) * 3.0), 1, 0.1)
        }
        if (c.age % 4 == 0) BloodFx.spawn(world, BloodFx.crumbs, top, 6, 2.5, 0.5, 2.5, 0.0)
        if (c.age % 20 == 0) BloodFx.sound(world, top, SoundEvents.ENTITY_BREEZE_IDLE_AIR, 2.0f, 0.5f)
        // volleys: aimed at every player, plus a ring of bolts straight down
        if (rise >= 1.0 && c.age % 14 == 0) {
            for (p in HeartVaultArena.playersInArena(world)) {
                val lead = p.velocity.multiply(6.0)
                val dir = p.entityPos.add(lead).add(0.0, 1.0, 0.0).subtract(top).normalize()
                bolt(world, owner, top, dir, 0.85)
            }
            BloodFx.sound(world, top, SoundEvents.ENTITY_BLAZE_SHOOT, 2.0f, 0.6f)
        }
        if (rise >= 1.0 && c.age % 30 == 15) {
            for (k in 0 until 8) {
                val a = k * PI / 4 + c.age * 0.1
                bolt(world, owner, top, Vec3d(cos(a) * 0.55, -1.0, sin(a) * 0.55).normalize(), 0.7)
            }
        }
        return true
    }

    private fun bolt(world: ServerWorld, owner: BloodPrinceEntity, from: Vec3d, dir: Vec3d, speed: Double) {
        val v = dir.normalize().multiply(speed)
        val display = FxDisplays.spawn(world, from, Items.PAPER, "blood_bolt", FxDisplays.facing(v), 1.4f, fullBright = true)
        bolts += Bolt(owner.uuid, world, from, v, display)
    }

    private fun tickBolt(b: Bolt): Boolean {
        val world = b.world
        val owner = world.getEntity(b.owner) as? BloodPrinceEntity
        if (owner == null || --b.life <= 0) { FxDisplays.remove(b.display); return false }
        val next = b.pos.add(b.vel)
        // players first
        for (p in HeartVaultArena.playersInArena(world)) {
            if (p.boundingBox.expand(0.35).raycast(b.pos, next).isPresent) {
                owner.hurt(world, p, BOLT_DAMAGE, 60)
                impact(world, p.entityPos.add(0.0, 1.0, 0.0), true)
                FxDisplays.remove(b.display)
                return false
            }
        }
        val hit = world.raycast(RaycastContext(b.pos, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, owner))
        if (hit.type == HitResult.Type.BLOCK) {
            impact(world, hit.pos, false)
            FxDisplays.remove(b.display)
            return false
        }
        b.pos = next
        b.display?.let { FxDisplays.update(it, b.pos, FxDisplays.facing(b.vel), 1.4f) }
        BloodFx.spawn(world, BloodFx.dust(BloodFx.BRIGHT, 1.0f), b.pos, 1, 0.05)
        return true
    }

    private fun impact(world: ServerWorld, at: Vec3d, onPlayer: Boolean) {
        BloodFx.spawn(world, BloodFx.crumbs, at, 8, 0.2, 0.1, 0.2, 0.2)
        BloodFx.sound(world, at, SoundEvents.ENTITY_ARROW_HIT, 0.9f, 0.7f)
        if (onPlayer) BloodFx.spawn(world, ParticleTypes.CRIT, at, 6, 0.3, 0.3, 0.3, 0.2)
    }

    fun tick() {
        saws.removeIf { !tickSaw(it) }
        cyclones.removeIf { !tickCyclone(it) }
        bolts.removeIf { !tickBolt(it) }
    }

    @Suppress("unused") private fun unused(p: PlayerEntity) = p
}
