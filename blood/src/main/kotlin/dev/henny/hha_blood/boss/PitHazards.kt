package dev.henny.hha_blood.boss

import dev.henny.hha_blood.BloodParticles
import dev.henny.hha_blood.logic.Bleed
import dev.henny.hha_blood.logic.BloodFx
import dev.henny.hha_blood.logic.FxDisplays
import net.minecraft.entity.decoration.DisplayEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.Items
import net.minecraft.particle.DustParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.util.math.Vec3d
import org.joml.Quaternionf
import org.joml.Vector3f
import net.minecraft.particle.BlockStateParticleEffect
import dev.henny.hha_blood.hollows.HeartVaultPiece
import dev.henny.hha_blood.hollows.HollowsBlocks
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ground effects and projectiles of the two bosses that outlive a single attack: burning ground, telegraphed
 * blood geysers, expanding shock rings (jump over them), fissures of blood spikes, the Minotaur's thrown axe
 * and the risen labyrinth walls. Only players in the fight are hurt. Everything is cleared on cutscenes/reset.
 */
object PitHazards {

    private abstract class Hazard(val owner: UUID) {
        var age = 0
        /** false = remove. */
        abstract fun tick(world: ServerWorld, boss: PitBoss?): Boolean
        open fun remove() {}
    }

    private const val ORB_SPEED = 0.75

    private val list = ArrayList<Hazard>()
    private val walls = ArrayList<PitArena.RisingWall>()

    fun clear(world: ServerWorld?) {
        list.forEach { it.remove() }
        list.clear()
        if (world != null) for (w in walls) while (!PitArena.sinkStep(world, w)) { /* sink fully */ }
        walls.clear()
    }

    fun tick(world: ServerWorld) {
        if (list.isEmpty() && walls.isEmpty()) return
        val it = list.iterator()
        val added = ArrayList<Hazard>()
        pending = added
        while (it.hasNext()) {
            val h = it.next()
            h.age++
            val boss = world.getEntity(h.owner) as? PitBoss
            if (!h.tick(world, boss)) { h.remove(); it.remove() }
        }
        pending = null
        list += added
        tickWalls(world)
    }

    private var pending: ArrayList<Hazard>? = null

    private fun add(h: Hazard) {
        val p = pending
        if (p != null) p += h else list += h
    }

    private fun targets(world: ServerWorld): List<PlayerEntity> = PitFight.targets(world)

    private fun sound(world: ServerWorld, at: Vec3d, event: SoundEvent, volume: Float, pitch: Float) =
        world.playSound(null, at.x, at.y, at.z, event, SoundCategory.HOSTILE, volume, pitch)

    // ------------------------------------------------------------------------------------- burning ground

    /** Burning patch (Cerberus breath, fire trails): hurts and ignites who stands in it. */
    fun fire(owner: PitBoss, at: Vec3d, radius: Double, ticks: Int, damage: Float = 5f) {
        if (list.count { it is FireZone } > 60) return
        add(FireZone(owner.uuid, at, radius, ticks, damage))
    }

    private class FireZone(owner: UUID, val at: Vec3d, val radius: Double, val life: Int, val damage: Float) : Hazard(owner) {
        override fun tick(world: ServerWorld, boss: PitBoss?): Boolean {
            if (age % 3 == 0) {
                val n = (radius * radius * 1.2).toInt().coerceIn(2, 14)
                world.spawnParticles(BloodParticles.FLAME, at.x, at.y + 0.2, at.z, n, radius * 0.5, 0.1, radius * 0.5, 0.01)
                if (age % 6 == 0) world.spawnParticles(ParticleTypes.FLAME, at.x, at.y + 0.1, at.z, n / 2, radius * 0.45, 0.05, radius * 0.45, 0.01)
                if (age % 9 == 0) world.spawnParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 0.3, at.z, 1, radius * 0.3, 0.1, radius * 0.3, 0.01)
            }
            if (age % 10 == 0 && boss != null) for (p in PitFight.targets(world)) {
                if (p.squaredDistanceTo(at.x, p.y, at.z) < radius * radius && abs(p.y - at.y) < 1.5) {
                    boss.hurt(world, p, damage, fireSeconds = 3f, ignoreFrames = true, kind = BossDamage.HELLFIRE)
                }
            }
            return age < life
        }
    }

    // ------------------------------------------------------------------------------------- telegraphed eruptions

    /** Warning circle for [delay] ticks, then a blood geyser (damage, knock up, bleeding). */
    fun geyser(owner: PitBoss, at: Vec3d, delay: Int, damage: Float = 26f, radius: Double = 1.8) {
        add(Geyser(owner.uuid, at, delay, damage, radius))
    }

    private class Geyser(owner: UUID, val at: Vec3d, val delay: Int, val damage: Float, val radius: Double) : Hazard(owner) {
        override fun tick(world: ServerWorld, boss: PitBoss?): Boolean {
            if (age < delay) {
                if (age % 3 == 0) boss?.warnRing(world, at, radius * (0.6 + 0.4 * age / delay), 0xC01020)
                if (age % 5 == 0) world.spawnParticles(BloodParticles.DRIP_FALL, at.x, at.y + 0.2, at.z, 3, radius * 0.4, 0.0, radius * 0.4, 0.0)
                return true
            }
            if (age == delay) {
                sound(world, at, BossSounds.GEYSER, 1.6f, 0.9f + world.random.nextFloat() * 0.2f)
                for (h in 0..12) world.spawnParticles(BloodParticles.GLOB, at.x, at.y + h * 0.5, at.z, 4, radius * 0.25, 0.2, radius * 0.25, 0.25)
                world.spawnParticles(BloodParticles.SPLAT, at.x, at.y + 0.3, at.z, 10, radius * 0.6, 0.1, radius * 0.6, 0.1)
                if (boss != null) for (p in PitFight.targets(world)) {
                    if (p.squaredDistanceTo(at.x, p.y, at.z) < radius * radius && p.y - at.y < 4 && p.y >= at.y - 1) {
                        if (boss.hurt(world, p, damage, bleedTicks = 80, kind = BossDamage.HELLFIRE)) {
                            p.addVelocity(0.0, 1.1, 0.0); p.velocityDirty = true; p.knockedBack = true
                        }
                    }
                }
            }
            if (age in (delay + 1)..(delay + 10) && age % 2 == 0) {
                world.spawnParticles(BloodFx.dust(BloodFx.BRIGHT, 2.5f), at.x, at.y + 2.5, at.z, 10, radius * 0.3, 2.0, radius * 0.3, 0.0)
            }
            return age < delay + 12
        }
    }

    // ------------------------------------------------------------------------------------- shock ring

    /** Ring that expands along the floor; standing on the ground inside its edge hurts - jump over it. */
    fun shockRing(owner: PitBoss, center: Vec3d, maxRadius: Double, speed: Double, damage: Float, fire: Boolean) {
        add(ShockRing(owner.uuid, center, maxRadius, speed, damage, fire))
    }

    private class ShockRing(owner: UUID, val c: Vec3d, val max: Double, val speed: Double, val damage: Float, val fire: Boolean) : Hazard(owner) {
        val hit = HashSet<UUID>()
        override fun tick(world: ServerWorld, boss: PitBoss?): Boolean {
            val r = age * speed
            if (r > max) return false
            val n = (r * 5).toInt().coerceIn(10, 110)
            val particle = if (fire) BloodParticles.FLAME else BloodFx.dust(BloodFx.BRIGHT, 1.8f)
            for (i in 0 until n) {
                val a = i * 2 * PI / n + age * 0.05
                world.spawnParticles(particle, c.x + cos(a) * r, c.y + 0.2, c.z + sin(a) * r, 1, 0.05, 0.12, 0.05, 0.01)
            }
            if (age % 2 == 0) for (i in 0 until n / 3) {
                val a = i * 6 * PI / n
                world.spawnParticles(ParticleTypes.LARGE_SMOKE, c.x + cos(a) * r, c.y + 0.3, c.z + sin(a) * r, 1, 0.0, 0.05, 0.0, 0.0)
            }
            if (boss != null) for (p in PitFight.targets(world)) {
                if (p.uuid in hit) continue
                val d = sqrt(p.squaredDistanceTo(c.x, p.y, c.z))
                // only on the ground (a jump clears it) and close to the ring's edge
                if (abs(d - r) < 0.9 && p.y - c.y < 0.6 && p.isOnGround) {
                    hit += p.uuid
                    if (boss.hurt(world, p, damage, fireSeconds = if (fire) 3f else 0f, kind = BossDamage.HELLFIRE)) boss.knock(p, c, 0.9, 0.45)
                }
            }
            return true
        }
    }

    // ------------------------------------------------------------------------------------- fissure

    /** A line of blood spikes bursting out of the floor one after another (axe cleave). */
    fun fissure(owner: PitBoss, from: Vec3d, dir: Vec3d, length: Int, damage: Float) {
        add(Fissure(owner.uuid, from, dir.normalize(), length, damage))
    }

    private class Fissure(owner: UUID, val from: Vec3d, val dir: Vec3d, val length: Int, val damage: Float) : Hazard(owner) {
        val spikes = ArrayList<Pair<DisplayEntity.ItemDisplayEntity?, Int>>()
        val hit = HashSet<UUID>()
        override fun tick(world: ServerWorld, boss: PitBoss?): Boolean {
            if (age % 2 == 0 && age / 2 < length) {
                val i = age / 2
                val p = from.add(dir.multiply(1.6 * (i + 1)))
                if (PitArena.radius(p) < dev.henny.hha_blood.hollows.HeartVaultPiece.FIGHT_R - 0.5) {
                    val yaw = (world.random.nextFloat() - 0.5f) * 0.6f
                    val rot = Quaternionf().rotateY(yaw).rotateX((-PI / 2).toFloat() + (world.random.nextFloat() - 0.5f) * 0.35f)
                    val at = Vec3d(p.x, from.y + 0.5, p.z)
                    val d = FxDisplays.spawn(world, at, Items.PAPER, "blood_spike", rot, 0.01f, fullBright = true, teleportTicks = 0)
                    d?.let {
                        FxDisplays.cullBox(it, 1.5f, 2.5f)
                        val s = 1.0f + i * 0.08f
                        FxDisplays.update(it, at, rot, org.joml.Vector3f(s, s, 1.7f * s), 3)
                    }
                    spikes += d to age
                    world.spawnParticles(BloodParticles.GLOB, p.x, from.y + 0.3, p.z, 6, 0.3, 0.2, 0.3, 0.2)
                    world.spawnParticles(net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, dev.henny.hha_blood.hollows.HollowsBlocks.BLOOD_STONE.defaultState),
                        p.x, from.y + 0.2, p.z, 10, 0.4, 0.1, 0.4, 0.15)
                    if (i % 2 == 0) sound(world, p, net.minecraft.sound.SoundEvents.BLOCK_POINTED_DRIPSTONE_LAND, 1.2f, 0.6f)
                    if (boss != null) for (pl in PitFight.targets(world)) {
                        if (pl.uuid in hit) continue
                        if (pl.squaredDistanceTo(p.x, pl.y, p.z) < 1.4 * 1.4 && pl.y - from.y < 2.5) {
                            hit += pl.uuid
                            if (boss.hurt(world, pl, damage, bleedTicks = 60, kind = BossDamage.HELLFIRE)) { pl.addVelocity(0.0, 0.8, 0.0); pl.velocityDirty = true; pl.knockedBack = true }
                        }
                    }
                }
            }
            // spikes sink again after 24 ticks
            val sit = spikes.iterator()
            while (sit.hasNext()) {
                val (d, born) = sit.next()
                if (age - born > 24) { FxDisplays.removeEntity(d); sit.remove() }
            }
            return age / 2 < length || spikes.isNotEmpty()
        }

        override fun remove() { spikes.forEach { FxDisplays.removeEntity(it.first) }; spikes.clear() }
    }

    // ------------------------------------------------------------------------------------- flame cone

    /** Particles + damage of a cone of fire from [from] along [dir] (one tick of a breath). */
    fun flameTick(world: ServerWorld, boss: PitBoss, from: Vec3d, dir: Vec3d, range: Double, halfAngle: Double, age: Int,
                  damage: Float, effect: Int = 0) {
        val d = dir.normalize()
        for (k in 0 until 6) {
            val s = 0.5 + world.random.nextDouble() * range
            val spread = s * Math.tan(Math.toRadians(halfAngle)) * 0.8
            val p = from.add(d.multiply(s))
            val particle = when (effect) {
                1 -> BloodParticles.GLOB
                2 -> DustParticleEffect(0x7A4AFF, 2.0f)
                else -> if (world.random.nextBoolean()) BloodParticles.FLAME else ParticleTypes.FLAME
            }
            world.spawnParticles(particle, p.x, p.y, p.z, 1, spread * 0.4, spread * 0.3, spread * 0.4, 0.02)
        }
        if (age % 5 != 0) return
        for (p in PitFight.targets(world)) {
            val eye = p.entityPos.add(0.0, 1.0, 0.0)
            val to = eye.subtract(from)
            val dist = to.length()
            if (dist > range || dist < 0.1) continue
            val cos = to.dotProduct(d) / dist
            if (cos < Math.cos(Math.toRadians(halfAngle + 6))) continue
            // pillars and labyrinth walls block the fire
            if (!PitFight.lineOfSight(world, from, eye)) continue
            when (effect) {
                1 -> boss.hurt(world, p, damage, bleedTicks = 60, ignoreFrames = true, kind = BossDamage.HELLFIRE)
                2 -> { if (boss.hurt(world, p, damage, ignoreFrames = true, kind = BossDamage.HELLFIRE)) p.addStatusEffect(net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.SLOWNESS, 40, 2)) }
                else -> boss.hurt(world, p, damage, fireSeconds = 4f, ignoreFrames = true, kind = BossDamage.HELLFIRE)
            }
        }
    }

    // ------------------------------------------------------------------------------------- thrown axe

    /** The Minotaur's axe as a spinning boomerang: flies out to [target], comes back, [onCaught] when it returns. */
    fun throwAxe(owner: MinotaurEntity, from: Vec3d, target: Vec3d, damage: Float) {
        val world = owner.entityWorld as ServerWorld
        val d = FxDisplays.spawn(world, from, Items.PAPER, "minotaur_axe", Quaternionf(), 0.95f, teleportTicks = 2)?.also { FxDisplays.cullBox(it, 3.5f, 3.5f) }
        add(ThrownAxe(owner.uuid, d, from, target, damage))
    }

    private class ThrownAxe(owner: UUID, val display: DisplayEntity.ItemDisplayEntity?, var pos: Vec3d, target: Vec3d, val damage: Float) : Hazard(owner) {
        val out: Vec3d
        val hitOut = HashSet<UUID>()
        val hitBack = HashSet<UUID>()
        var returning = false
        val outTicks: Int

        init {
            val d = target.subtract(pos)
            outTicks = (d.length() / 1.15).toInt().coerceIn(8, 26)
            out = d.multiply(1.0 / outTicks)
        }

        override fun tick(world: ServerWorld, boss: PitBoss?): Boolean {
            val m = boss as? MinotaurEntity ?: return false
            if (!returning && age >= outTicks + 4) returning = true
            val next = if (!returning) (if (age <= outTicks) pos.add(out) else pos) else {
                val home = m.entityPos.add(0.0, m.height * 0.55, 0.0)
                val d = home.subtract(pos)
                if (d.length() < 1.6 || age > outTicks + 70) { m.catchAxe(); return false }
                pos.add(d.normalize().multiply(minOf(1.25, d.length())))
            }
            pos = next
            if (display != null) {
                val spin = Quaternionf().rotateY((age * 0.9f)).rotateZ((PI / 2).toFloat())
                FxDisplays.update(display, pos, spin, 0.95f, 2)
            }
            world.spawnParticles(BloodParticles.FLAME, pos.x, pos.y, pos.z, 2, 0.4, 0.4, 0.4, 0.01)
            world.spawnParticles(BloodFx.dust(BloodFx.BRIGHT, 1.6f), pos.x, pos.y, pos.z, 3, 0.6, 0.2, 0.6, 0.0)
            if (age % 6 == 0) sound(world, pos, BossSounds.AXE_SPIN, 1.4f, 1.0f)
            val set = if (returning) hitBack else hitOut
            for (p in PitFight.targets(world)) {
                if (p.uuid in set) continue
                if (p.boundingBox.expand(0.9).contains(pos)) {
                    set += p.uuid
                    m.breakShield(world, p)
                    if (m.hurt(world, p, damage, bleedTicks = 40)) m.knock(p, pos, 0.6, 0.3)
                }
            }
            return true
        }

        override fun remove() { FxDisplays.removeEntity(display) }
    }

    // ------------------------------------------------------------------------------------- hellfire orbs

    /** A slowly homing ball of the Cerberus' fire. [head]: 0 bleed, 1 flame, 2 howl decides the effect. */
    fun orb(owner: PitBoss, from: Vec3d, target: PlayerEntity?, aim: Vec3d, head: Int, damage: Float) {
        add(Orb(owner.uuid, from, target?.uuid, aim, head, damage))
    }

    private class Orb(owner: UUID, var pos: Vec3d, val target: UUID?, aim: Vec3d, val head: Int, val damage: Float) : Hazard(owner) {
        var vel: Vec3d = aim.subtract(pos).let { if (it.lengthSquared() < 1e-4) Vec3d(0.0, -1.0, 0.0) else it.normalize() }.multiply(ORB_SPEED)
        val color = intArrayOf(0xC0101A, 0xFF7A1A, 0x8A40FF)[head.coerceIn(0, 2)]

        override fun tick(world: ServerWorld, boss: PitBoss?): Boolean {
            if (boss == null || age > 80) return false
            val t = target?.let { id -> PitFight.targets(world).firstOrNull { it.uuid == id } }
            if (t != null && age > 5) {
                // turns a little towards its target every tick - a side step still dodges it
                val want = t.entityPos.add(0.0, 1.0, 0.0).subtract(pos).normalize().multiply(ORB_SPEED)
                vel = vel.add(want.subtract(vel).multiply(0.06)).normalize().multiply(ORB_SPEED)
            }
            val next = pos.add(vel)
            world.spawnParticles(BloodFx.dust(color, 2.4f), next.x, next.y, next.z, 4, 0.16, 0.16, 0.16, 0.0)
            world.spawnParticles(if (head == 1) ParticleTypes.FLAME else BloodParticles.FLAME, next.x, next.y, next.z, 2, 0.12, 0.12, 0.12, 0.01)
            if (age % 3 == 0) world.spawnParticles(ParticleTypes.SMOKE, pos.x, pos.y, pos.z, 1, 0.05, 0.05, 0.05, 0.0)
            val bp = net.minecraft.util.math.BlockPos.ofFloored(next)
            val solid = !world.getBlockState(bp).getCollisionShape(world, bp).isEmpty
            val victim = PitFight.targets(world).any { it.boundingBox.expand(0.45).contains(next) }
            pos = next
            if (solid || victim || next.y < PitArena.mid().y + 0.25) { burst(world, boss); return false }
            return true
        }

        private fun burst(world: ServerWorld, boss: PitBoss) {
            sound(world, pos, BossSounds.FIRE_BURST, 1.6f, 1.25f)
            world.spawnParticles(ParticleTypes.EXPLOSION, pos.x, pos.y, pos.z, 1, 0.0, 0.0, 0.0, 0.0)
            world.spawnParticles(BloodFx.dust(color, 2.6f), pos.x, pos.y, pos.z, 24, 1.0, 0.6, 1.0, 0.0)
            world.spawnParticles(BloodParticles.FLAME, pos.x, pos.y, pos.z, 14, 0.8, 0.4, 0.8, 0.05)
            for (p in PitFight.targets(world)) {
                if (p.entityPos.add(0.0, 0.9, 0.0).squaredDistanceTo(pos) > 2.5 * 2.5) continue
                when (head) {
                    0 -> boss.hurt(world, p, damage, bleedTicks = 100, kind = BossDamage.HELLFIRE)
                    1 -> boss.hurt(world, p, damage, fireSeconds = 4f, kind = BossDamage.HELLFIRE)
                    else -> if (boss.hurt(world, p, damage, kind = BossDamage.HELLFIRE))
                        p.addStatusEffect(net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.SLOWNESS, 60, 2))
                }
            }
            if (head == 1) {
                val floor = PitArena.mid().y
                if (pos.y - floor < 3.0) fire(boss, Vec3d(pos.x, floor, pos.z), 1.7, 90)
            }
        }
    }

    // ------------------------------------------------------------------------------------- ring of fire

    /**
     * A ring of hellfire that closes around [center] within [formTicks] (the missing part is the way out). Once
     * closed, touching it burns and throws you back to the side you came from - trapped with the Cerberus.
     */
    fun fireRing(owner: PitBoss, center: Vec3d, radius: Double, formTicks: Int, life: Int, damage: Float) {
        add(FireRing(owner.uuid, center, radius, formTicks, life, damage))
    }

    private class FireRing(owner: UUID, val c: Vec3d, val radius: Double, val formTicks: Int, val life: Int, val damage: Float) : Hazard(owner) {
        val start = abs(c.x * 13.1 + c.z * 7.7) % (2 * PI)
        val lastHit = HashMap<UUID, Int>()

        private fun fraction(x: Double, z: Double): Double {
            var a = kotlin.math.atan2(z - c.z, x - c.x) - start
            while (a < 0) a += 2 * PI
            return a / (2 * PI)
        }

        override fun tick(world: ServerWorld, boss: PitBoss?): Boolean {
            if (age > life) return false
            val formed = (age.toDouble() / formTicks).coerceAtMost(1.0)
            val n = (radius * 7).toInt().coerceIn(24, 90)
            if (age % 2 == 0) for (i in 0 until n) {
                val f = i.toDouble() / n
                val a = start + f * 2 * PI
                val x = c.x + cos(a) * radius
                val z = c.z + sin(a) * radius
                if (f <= formed) {
                    world.spawnParticles(BloodParticles.FLAME, x, c.y + 0.3 + world.random.nextDouble() * 1.8, z, 1, 0.08, 0.35, 0.08, 0.01)
                    if (i % 3 == 0) world.spawnParticles(ParticleTypes.FLAME, x, c.y + 0.2, z, 1, 0.05, 0.25, 0.05, 0.01)
                } else if (i % 2 == 0) {
                    world.spawnParticles(BloodFx.dust(0xFF5010, 1.3f), x, c.y + 0.15, z, 1, 0.0, 0.0, 0.0, 0.0)
                }
            }
            if (age % 16 == 0) sound(world, c, net.minecraft.sound.SoundEvents.BLOCK_FIRE_AMBIENT, 2.5f, 0.7f)
            if (age == formTicks) sound(world, c, BossSounds.FIRE_BURST, 2.5f, 0.7f)
            if (age > life - 12 || boss == null) return true
            for (p in PitFight.targets(world)) {
                val d = sqrt(p.squaredDistanceTo(c.x, p.y, c.z))
                if (abs(d - radius) > 0.85 || p.y - c.y > 3.0 || p.y < c.y - 1.0) continue
                if (fraction(p.x, p.z) > formed) continue
                if ((lastHit[p.uuid] ?: -99) > age - 10) continue
                lastHit[p.uuid] = age
                boss.hurt(world, p, damage, fireSeconds = 3f, ignoreFrames = true, kind = BossDamage.HELLFIRE)
                val out = Vec3d(p.x - c.x, 0.0, p.z - c.z).let { if (it.lengthSquared() < 1e-4) Vec3d(1.0, 0.0, 0.0) else it.normalize() }
                val push = out.multiply(if (d < radius) -0.75 else 0.75)
                p.addVelocity(push.x, 0.3, push.z)
                p.velocityDirty = true
                p.knockedBack = true
            }
            return true
        }
    }

    // ------------------------------------------------------------------------------------- falling rubble

    /** Rubble from the ceiling: a shadow ring and dust for [delay] ticks, then a boulder slams down. */
    fun rock(owner: PitBoss, at: Vec3d, delay: Int, damage: Float) {
        if (list.count { it is Rock } > 40) return
        add(Rock(owner.uuid, at, delay.coerceAtLeast(14), damage))
    }

    /** [count] rocks: one on (and slightly ahead of) every player, the rest anywhere on the fighting floor. */
    fun rockfall(owner: PitBoss, world: ServerWorld, count: Int, damage: Float) {
        val m = PitArena.mid()
        val c = PitArena.center() ?: return
        var left = count
        for (p in PitFight.targets(world)) {
            if (left <= 0) break
            val lead = p.velocity.multiply(10.0)
            rock(owner, PitArena.clampToFloor(Vec3d(p.x + lead.x, m.y, p.z + lead.z), 2.5), 30 + world.random.nextInt(8), damage)
            left--
        }
        var tries = 0
        while (left > 0 && tries++ < 60) {
            val a = world.random.nextDouble() * 2 * PI
            val r = 4.0 + world.random.nextDouble() * (HeartVaultPiece.FIGHT_R - 7.0)
            val p = Vec3d(m.x + cos(a) * r, m.y, m.z + sin(a) * r)
            if (HeartVaultPiece.pillarAt(p.x - c.x, p.z - c.z, 1.5) >= 0) continue
            rock(owner, p, 24 + world.random.nextInt(30), damage)
            left--
        }
    }

    private class Rock(owner: UUID, val at: Vec3d, val delay: Int, val damage: Float) : Hazard(owner) {
        var display: DisplayEntity.ItemDisplayEntity? = null
        val rot: Quaternionf = Quaternionf().rotateXYZ((abs(at.x * 1.7) % 1.0).toFloat(), (abs(at.z * 2.3) % 3.0).toFloat(), (abs(at.x * 0.9) % 1.0).toFloat())
        val size = 1.5f + ((abs(at.x * 7.3 + at.z * 3.1) % 1.0) * 0.8).toFloat()

        override fun tick(world: ServerWorld, boss: PitBoss?): Boolean {
            if (age < delay) {
                if (age % 3 == 0) boss?.warnRing(world, at, 1.8 * (0.45 + 0.55 * age / delay), 0x5A1010)
                if (age % 2 == 0) world.spawnParticles(BlockStateParticleEffect(ParticleTypes.FALLING_DUST, HollowsBlocks.BLOOD_STONE.defaultState),
                    at.x, at.y + 12.0, at.z, 2, 0.5, 2.0, 0.5, 0.0)
                if (age == delay - 12) {
                    // appears high above (transformation offset) and falls by interpolating the offset back to 0
                    display = FxDisplays.spawn(world, at.add(0.0, size * 0.5, 0.0), Items.PAPER, "blood_stone", rot, size)?.also {
                        FxDisplays.cullBox(it, 3f, 26f)
                        FxDisplays.animate(it, Vector3f(0f, 24f, 0f), rot, Vector3f(size, size, size), 0)
                    }
                    sound(world, at, net.minecraft.sound.SoundEvents.BLOCK_POINTED_DRIPSTONE_FALL, 1.5f, 0.5f)
                }
                if (age == delay - 10) display?.let { FxDisplays.animate(it, Vector3f(), rot, Vector3f(size, size, size), 10) }
                return true
            }
            if (age == delay) {
                sound(world, at, BossSounds.IMPACT_HEAVY, 2.0f, 1.1f)
                sound(world, at, net.minecraft.sound.SoundEvents.BLOCK_STONE_BREAK, 2.0f, 0.6f)
                world.spawnParticles(BlockStateParticleEffect(ParticleTypes.BLOCK, HollowsBlocks.BLOOD_STONE.defaultState),
                    at.x, at.y + 0.4, at.z, 40, 0.9, 0.3, 0.9, 0.2)
                world.spawnParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 0.5, at.z, 6, 0.8, 0.3, 0.8, 0.02)
                BloodFx.shake(world, at, 10.0, 0.8f, 6)
                if (boss != null) for (p in PitFight.targets(world)) {
                    if (p.squaredDistanceTo(at.x, p.y, at.z) > 1.9 * 1.9 || p.y - at.y > 2.5 || p.y < at.y - 1.0) continue
                    if (boss.hurt(world, p, damage, kind = BossDamage.CRUSH)) boss.knock(p, at, 0.6, 0.2)
                }
            }
            if (age == delay + 30) display?.let { FxDisplays.animate(it, Vector3f(0f, -0.9f, 0f), rot, Vector3f(0.01f, 0.01f, 0.01f), 10) }
            return age < delay + 42
        }

        override fun remove() { FxDisplays.removeEntity(display) }
    }

    // ------------------------------------------------------------------------------------- labyrinth walls

    fun raiseWalls(world: ServerWorld, owner: PitBoss, life: Int) {
        val avoid = PitFight.targets(world).map { it.entityPos } + owner.entityPos
        val cols = PitArena.labyrinthColumns(world, world.random.nextInt(), avoid)
        if (cols.isEmpty()) return
        // split into chunks of neighbouring columns so they rise in waves
        walls += PitArena.RisingWall(cols, 3, 0, life)
        sound(world, PitArena.mid(), BossSounds.WALL_RISE, 3.0f, 0.8f)
    }

    fun wallsUp(): Boolean = walls.isNotEmpty()

    private fun tickWalls(world: ServerWorld) {
        val it = walls.iterator()
        while (it.hasNext()) {
            val w = it.next()
            w.life--
            if (w.life > 0) {
                if (w.level < w.height && w.life % 3 == 0) {
                    PitArena.riseStep(world, w)
                    for (c in w.columns) if (world.random.nextInt(6) == 0) {
                        world.spawnParticles(net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, dev.henny.hha_blood.hollows.HollowsBlocks.BLOOD_STONE_BRICKS.defaultState),
                            c.x + 0.5, c.y + w.level.toDouble(), c.z + 0.5, 3, 0.4, 0.1, 0.4, 0.1)
                    }
                }
            } else if (w.life % 3 == 0) {
                if (PitArena.sinkStep(world, w)) it.remove()
            }
        }
    }
}
