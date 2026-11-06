package dev.henny.hha_blood.logic

import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.Targeting
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Blood Sword active: two wide, spinning 3D saw blades (item display with the `hha_blood:bloodsaw`
 * model) fly out, cut through enemies and ricochet - off walls and from enemy to enemy - but never
 * back towards you. If both blades hit the same enemy within a short window a Blood Fountain erupts.
 */
object Bloodsaws {

    private class Saw(
        val pairId: UUID,
        val index: Int,
        val owner: UUID,
        val world: ServerWorld,
        var pos: Vec3d,
        var vel: Vec3d,
        var bouncesLeft: Int,
        var life: Int,
        val display: DisplayEntity.ItemDisplayEntity?,
    ) {
        val hit = HashSet<UUID>()
        var age = 0
        var lastPos: Vec3d = pos
        var spin = 0f
    }

    private class PairHit(val pairId: UUID, val entityId: UUID, var bits: Int, val expireTick: Long)

    private val saws = ArrayList<Saw>()
    private val pairHits = ArrayList<PairHit>()
    private var serverTick = 0L

    private const val MODEL = "bloodsaw"
    private const val RICOCHET_RANGE = 10.0

    fun reset() {
        saws.forEach { FxDisplays.remove(it.display) }
        saws.clear()
        pairHits.clear()
    }

    private fun scale(): Float = (HhaConfig.num("hha_blood.bloodsaws_width") * 1.25).toFloat().coerceIn(0.5f, 4f)

    /** Frisbee-like: lies (almost) flat in the flight direction, banked outwards, spinning around its own axis. */
    private fun rotation(dir: Vec3d, index: Int, spin: Float): Quaternionf {
        val bank = if (index == 0) 0.45f else -0.45f
        return FxDisplays.facing(dir).rotateZ(bank).rotateX((-PI / 2).toFloat()).rotateZ(spin)
    }

    fun fire(world: ServerWorld, player: ServerPlayerEntity) {
        val look = player.getRotationVec(1.0f).normalize()
        val right = basisRight(look)
        val speed = HhaConfig.num("hha_blood.bloodsaws_speed")
        val spread = HhaConfig.num("hha_blood.bloodsaws_spread")
        val bounces = HhaConfig.num("hha_blood.bloodsaws_bounces").toInt()
        val life = HhaConfig.num("hha_blood.bloodsaws_life").toInt()
        val pairId = UUID.randomUUID()
        val origin = player.eyePos.add(look.multiply(0.8)).add(0.0, -0.25, 0.0)

        for (i in 0..1) {
            val side = if (i == 0) -1.0 else 1.0
            val dir = look.add(right.multiply(side * spread)).normalize()
            val pos = origin.add(right.multiply(side * 0.45))
            val display = FxDisplays.spawn(world, pos, Items.PAPER, MODEL, rotation(dir, i, 0f), scale(), fullBright = true)
            saws += Saw(pairId, i, player.uuid, world, pos, dir.multiply(speed), bounces, life, display)
        }
        BloodFx.spawn(world, BloodFx.crumbs, origin, 20, 0.3, 0.3, 0.3, 0.2)
        BloodFx.spawn(world, BloodFx.splat, origin, 3, 0.3, 0.0)
        BloodFx.sound(world, player.entityPos, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.6f)
        BloodFx.sound(world, player.entityPos, SoundEvents.ITEM_TRIDENT_THROW, 0.9f, 1.4f)
        BloodFx.sound(world, player.entityPos, SoundEvents.BLOCK_GRINDSTONE_USE, 0.6f, 1.3f)
    }

    fun tick(server: net.minecraft.server.MinecraftServer) {
        serverTick = server.ticks.toLong()
        pairHits.removeAll { it.expireTick < serverTick }
        if (saws.isEmpty()) return

        val damage = HhaConfig.num("hha_blood.bloodsaws_damage").toFloat()
        val width = HhaConfig.num("hha_blood.bloodsaws_width")
        val iterator = saws.iterator()
        while (iterator.hasNext()) {
            val saw = iterator.next()
            val world = saw.world
            saw.life--
            saw.age++
            val owner = world.getPlayerByUuid(saw.owner) as? ServerPlayerEntity
            if (saw.life <= 0 || owner == null || !owner.isAlive) {
                impact(world, saw.pos)
                FxDisplays.remove(saw.display)
                iterator.remove()
                continue
            }

            // ---- move + bounce off blocks (never back towards the owner)
            val next = saw.pos.add(saw.vel)
            val hit = world.raycast(RaycastContext(saw.pos, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner))
            saw.lastPos = saw.pos
            if (hit.type == HitResult.Type.BLOCK) {
                if (saw.bouncesLeft <= 0) {
                    impact(world, hit.pos)
                    FxDisplays.remove(saw.display)
                    iterator.remove()
                    continue
                }
                saw.bouncesLeft--
                val normal = Vec3d.of(hit.side.vector)
                saw.vel = awayFromOwner(reflect(saw.vel, normal), hit.pos, owner)
                saw.pos = hit.pos.add(normal.multiply(0.15))
                BloodFx.spawn(world, BloodFx.crumbs, saw.pos, 14, 0.12, 0.3)
                BloodFx.spawn(world, ParticleTypes.CRIT, saw.pos, 8, 0.1, 0.4)
                BloodFx.sound(world, saw.pos, SoundEvents.BLOCK_GRINDSTONE_USE, 0.8f, 1.6f)
                BloodFx.sound(world, saw.pos, SoundEvents.BLOCK_ANVIL_LAND, 0.25f, 1.8f)
            } else {
                saw.pos = next
            }

            // ---- cut through enemies, then ricochet to the next one
            val victims = world.getEntitiesByClass(LivingEntity::class.java, Box.of(saw.pos, width, width, width)) {
                it.isAlive && it !== owner && Targeting.shouldHarm(owner, it)
            }
            var ricochetFrom: LivingEntity? = null
            for (e in victims) {
                if (!saw.hit.add(e.uuid)) continue
                e.timeUntilRegen = 0
                e.damage(world, world.damageSources.playerAttack(owner), damage)
                Bleed.apply(e, source = owner)
                val body = e.entityPos.add(0.0, e.height * 0.5, 0.0)
                BloodFx.wound(world, body, 2)
                BloodFx.spawn(world, ParticleTypes.SWEEP_ATTACK, body, 1)
                BloodFx.sound(world, body, SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 0.7f)
                registerPairHit(world, owner, saw.pairId, saw.index, e)
                ricochetFrom = e
            }
            if (ricochetFrom != null && saw.bouncesLeft > 0) {
                val target = nextTarget(world, owner, saw, ricochetFrom)
                if (target != null) {
                    saw.bouncesLeft--
                    val speed = saw.vel.length()
                    val aim = target.entityPos.add(0.0, target.height * 0.55, 0.0).subtract(saw.pos)
                    saw.vel = aim.normalize().multiply(speed)
                    BloodFx.sound(world, saw.pos, SoundEvents.BLOCK_GRINDSTONE_USE, 0.6f, 1.9f)
                }
            }

            drawSaw(saw)
        }
    }

    /** Nearest enemy the saw has not cut yet (in reach and in sight). */
    private fun nextTarget(world: ServerWorld, owner: ServerPlayerEntity, saw: Saw, from: LivingEntity): LivingEntity? =
        world.getEntitiesByClass(LivingEntity::class.java, Box.of(saw.pos, RICOCHET_RANGE * 2, RICOCHET_RANGE, RICOCHET_RANGE * 2)) {
            it.isAlive && it !== owner && it !== from && it.uuid !in saw.hit && Targeting.shouldHarm(owner, it) &&
                it.squaredDistanceTo(saw.pos) <= RICOCHET_RANGE * RICOCHET_RANGE
        }.filter { e ->
            val to = e.entityPos.add(0.0, e.height * 0.55, 0.0)
            world.raycast(RaycastContext(saw.pos, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner)).type != HitResult.Type.BLOCK
        }.minByOrNull { it.squaredDistanceTo(saw.pos) }

    /** If a bounce would send the saw back towards its owner, mirror that part of the motion away from them. */
    private fun awayFromOwner(v: Vec3d, at: Vec3d, owner: ServerPlayerEntity): Vec3d {
        val toOwner = Vec3d(owner.x - at.x, 0.0, owner.z - at.z)
        if (toOwner.lengthSquared() < 1e-4) return v
        val d = toOwner.normalize()
        val dot = v.dotProduct(d)
        return if (dot > 0) v.subtract(d.multiply(2 * dot)) else v
    }

    /** 3D blade + a bleeding tail. */
    private fun drawSaw(saw: Saw) {
        val world = saw.world
        val dir = saw.vel.normalize()
        saw.spin += if (saw.index == 0) 0.85f else -0.85f
        saw.display?.let { FxDisplays.update(it, saw.pos, rotation(dir, saw.index, saw.spin), scale()) }

        // tail: interpolate between last and current position so fast saws leave a continuous streak
        for (s in 0 until 4) {
            val p = saw.lastPos.lerp(saw.pos, s / 4.0)
            BloodFx.spawn(world, BloodFx.fade(BloodFx.BRIGHT, BloodFx.BLACK, 1.2f), p, 1, 0.1)
        }
        // sparks of blood flung off the rim
        val right = basisRight(dir)
        for (k in 0 until 2) {
            val a = saw.spin + k * PI
            val rim = right.multiply(cos(a) * 0.6).add(dir.multiply(sin(a) * 0.6))
            BloodFx.spawn(world, BloodFx.crumbs, saw.pos.add(rim), 0, rim.x, 0.15, rim.z, 0.25)
        }
        if (saw.age % 3 == 0) BloodFx.spawn(world, BloodFx.dropsFalling, saw.pos, 1, 0.15)
        if (saw.age % 6 == 0) BloodFx.sound(world, saw.pos, SoundEvents.ENTITY_BREEZE_WIND_BURST, 0.35f, 1.8f)
    }

    private fun registerPairHit(world: ServerWorld, owner: ServerPlayerEntity, pairId: UUID, sawIndex: Int, entity: LivingEntity) {
        val bit = 1 shl sawIndex
        val window = HhaConfig.num("hha_blood.fountain_window").toLong()
        val existing = pairHits.firstOrNull { it.pairId == pairId && it.entityId == entity.uuid && it.expireTick >= serverTick }
        if (existing != null) {
            val bits = existing.bits or bit
            pairHits.remove(existing)
            if (bits == 3) {
                bloodFountain(world, owner, entity.entityPos.add(0.0, entity.height * 0.3, 0.0))
            } else {
                pairHits += PairHit(pairId, entity.uuid, bits, serverTick + window)
            }
        } else {
            pairHits += PairHit(pairId, entity.uuid, bit, serverTick + window)
        }
    }

    /** Both saws hit: a geyser of blood, expanding rings and a crimson spiral. */
    private fun bloodFountain(world: ServerWorld, owner: ServerPlayerEntity, center: Vec3d) {
        val range = HhaConfig.num("hha_blood.fountain_range")
        val damage = HhaConfig.num("hha_blood.fountain_damage").toFloat()
        val bleedTicks = HhaConfig.num("hha_blood.bleed_duration").toInt() + 60
        val dps = HhaConfig.num("hha_blood.bleed_dps").toFloat() * 1.5f
        val targets = world.getEntitiesByClass(LivingEntity::class.java, Box.of(center, range * 2, range * 2, range * 2)) {
            it.isAlive && it !== owner && Targeting.shouldHarm(owner, it) && it.squaredDistanceTo(center) <= range * range
        }
        for (e in targets) {
            e.timeUntilRegen = 0
            e.damage(world, world.damageSources.playerAttack(owner), damage)
            Bleed.apply(e, bleedTicks, dps, owner)
            BloodFx.wound(world, e.entityPos.add(0.0, 1.0, 0.0), 2)
        }

        val base = Vec3d(center.x, center.y - 0.2, center.z)
        // geyser: layered columns that taper to the top
        for (layer in 0 until 3) {
            BloodFx.column(world, base, 5.5 - layer, 0.5 - layer * 0.12, 22, BloodFx.dust(if (layer == 0) BloodFx.BRIGHT else BloodFx.BLOOD, 1.8f - layer * 0.3f))
        }
        BloodFx.spawn(world, BloodFx.crumbs, base.add(0.0, 1.0, 0.0), 60, 0.4, 1.2, 0.4, 0.6)
        BloodFx.spawn(world, BloodFx.splat, base.add(0.0, 1.5, 0.0), 8, 0.6, 1.0, 0.6, 0.0)
        BloodFx.spawn(world, BloodFx.dropsFalling, base.add(0.0, 5.0, 0.0), 30, 0.9, 0.2, 0.9, 0.0)
        for (ring in 1..4) {
            BloodFx.ring(world, base.add(0.0, 0.1 + ring * 0.05, 0.0), ring * (range / 4.0), BloodFx.dust(if (ring % 2 == 0) BloodFx.DARK else BloodFx.BRIGHT, 1.7f), 18 + ring * 6, ring * 0.4, 0.05)
        }
        for (i in 0 until 40) {
            val t = i / 40.0
            val ang = t * PI * 6
            BloodFx.spawn(world, BloodFx.dust(BloodFx.BRIGHT, 1.3f), base.x + cos(ang) * range * 0.5 * (1 - t * 0.4), base.y + t * 3.5, base.z + sin(ang) * range * 0.5 * (1 - t * 0.4))
        }
        BloodFx.spawn(world, ParticleTypes.EXPLOSION, center, 2, 0.5, 0.2, 0.5, 0.0)
        BloodFx.sound(world, center, SoundEvents.ENTITY_GENERIC_EXPLODE, 0.9f, 0.8f)
        BloodFx.sound(world, center, SoundEvents.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 1.2f, 0.5f)
        BloodFx.sound(world, center, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.5f, 0.5f)
    }

    private fun impact(world: ServerWorld, pos: Vec3d) {
        BloodFx.spawn(world, BloodFx.crumbs, pos, 22, 0.3, 0.3, 0.3, 0.3)
        BloodFx.spawn(world, BloodFx.splat, pos, 3, 0.2, 0.0)
        BloodFx.spawn(world, BloodFx.fade(BloodFx.BRIGHT, BloodFx.BLACK, 1.5f), pos, 14, 0.25, 0.25, 0.25, 0.05)
        BloodFx.sound(world, pos, SoundEvents.BLOCK_SLIME_BLOCK_BREAK, 0.9f, 0.7f)
    }

    private fun reflect(v: Vec3d, normal: Vec3d): Vec3d {
        val n = normal.normalize()
        return v.subtract(n.multiply(2.0 * v.dotProduct(n)))
    }

    private fun basisRight(direction: Vec3d): Vec3d =
        direction.crossProduct(if (abs(direction.y) > 0.98) Vec3d(1.0, 0.0, 0.0) else Vec3d(0.0, 1.0, 0.0)).normalize()
}
