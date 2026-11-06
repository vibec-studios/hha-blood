package dev.henny.hha_blood.logic

import dev.henny.hha.config.HhaConfig
import dev.henny.hha_blood.BloodParticles
import net.minecraft.particle.DustColorTransitionParticleEffect
import net.minecraft.particle.DustParticleEffect
import net.minecraft.particle.ParticleEffect
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket
import net.minecraft.registry.entry.RegistryEntry
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.util.math.Vec3d
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared visual/sound helpers for everything Blood. All particles are blood-coloured
 * dust (no more plain "hearts"), scaled by HHA's `particle_effects` config value.
 */
object BloodFx {

    const val BRIGHT = 0xD81E26
    const val BLOOD = 0x9B0A12
    const val DARK = 0x4A0509
    const val BLACK = 0x1B0305

    fun dust(color: Int = BLOOD, scale: Float = 1.0f): ParticleEffect = DustParticleEffect(color, scale)

    /** Dust that fades from [from] to [to] while it lives. */
    fun fade(from: Int = BRIGHT, to: Int = BLACK, scale: Float = 1.0f): ParticleEffect =
        DustColorTransitionParticleEffect(from, to, scale)

    /** Chunks of blood that fly, fall and leave small puddles - a splatter when spawned with some speed. */
    val crumbs: ParticleEffect get() = BloodParticles.GLOB

    /** A drop that hangs for a moment, then falls and splashes on the ground. */
    val drops: ParticleEffect get() = BloodParticles.DRIP

    /** A drop that falls immediately. */
    val dropsFalling: ParticleEffect get() = BloodParticles.DRIP_FALL

    /** Animated splash burst for hits / opened wounds. */
    val splat: ParticleEffect get() = BloodParticles.SPLAT

    /** Glowing crimson flame (Mohg's blood flames). */
    val flame: ParticleEffect get() = BloodParticles.FLAME

    private fun scaled(world: ServerWorld, count: Int): Int {
        val m = HhaConfig.num("particle_effects")
        if (m >= 1.0) return count
        if (m <= 0.0) return 0
        val exact = count * m
        val base = exact.toInt()
        return base + if (world.random.nextDouble() < exact - base) 1 else 0
    }

    fun spawn(
        world: ServerWorld, particle: ParticleEffect,
        x: Double, y: Double, z: Double,
        count: Int = 1, dx: Double = 0.0, dy: Double = 0.0, dz: Double = 0.0, speed: Double = 0.0,
    ) {
        if (count == 0) {
            // vanilla "directional" mode: (dx, dy, dz) * speed is the velocity of one particle
            if (scaled(world, 1) > 0) world.spawnParticles(particle, x, y, z, 0, dx, dy, dz, speed)
            return
        }
        val n = scaled(world, count)
        if (n > 0) world.spawnParticles(particle, x, y, z, n, dx, dy, dz, speed)
    }

    fun spawn(world: ServerWorld, particle: ParticleEffect, at: Vec3d, count: Int = 1, spread: Double = 0.0, speed: Double = 0.0) =
        spawn(world, particle, at.x, at.y, at.z, count, spread, spread, spread, speed)

    fun spawn(world: ServerWorld, particle: ParticleEffect, at: Vec3d, count: Int, dx: Double, dy: Double, dz: Double, speed: Double = 0.0) =
        spawn(world, particle, at.x, at.y, at.z, count, dx, dy, dz, speed)

    /** Only [viewer] sees these (used by the Blood Tracker). */
    fun spawnFor(viewer: ServerPlayerEntity, world: ServerWorld, particle: ParticleEffect, p: Vec3d, count: Int = 1, spread: Double = 0.0) {
        world.spawnParticles(viewer, particle, true, true, p.x, p.y, p.z, count, spread, spread, spread, 0.0)
    }

    /** A particle at the same spot for everybody, for "functional" visuals that must stay readable. */
    fun ring(world: ServerWorld, center: Vec3d, radius: Double, particle: ParticleEffect, points: Int, rotation: Double = 0.0, speed: Double = 0.0) {
        for (i in 0 until points) {
            val a = rotation + i.toDouble() / points * Math.PI * 2.0
            spawn(world, particle, center.x + cos(a) * radius, center.y, center.z + sin(a) * radius, 1, 0.0, 0.0, 0.0, speed)
        }
    }

    /** Ring in an arbitrary plane spanned by two orthonormal axes (used for the spinning saw blades). */
    fun orientedRing(world: ServerWorld, center: Vec3d, axisA: Vec3d, axisB: Vec3d, radius: Double, particle: ParticleEffect, points: Int, rotation: Double = 0.0) {
        for (i in 0 until points) {
            val a = rotation + i.toDouble() / points * Math.PI * 2.0
            val o = axisA.multiply(cos(a) * radius).add(axisB.multiply(sin(a) * radius))
            spawn(world, particle, center.x + o.x, center.y + o.y, center.z + o.z)
        }
    }

    /** Vertical column of blood (geyser). */
    fun column(world: ServerWorld, base: Vec3d, height: Double, radius: Double, steps: Int = 10, particle: ParticleEffect = dust(BLOOD, 1.3f)) {
        for (i in 0 until steps) {
            val y = base.y + i.toDouble() / steps * height
            val a = i * 1.7
            spawn(world, particle, base.x + cos(a) * radius, y, base.z + sin(a) * radius, 1, 0.08, 0.05, 0.08)
        }
    }

    /** Curved "vein" of dust flowing from [from] to [to]; [phase] 0..1 animates the flow. */
    fun vein(world: ServerWorld, from: Vec3d, to: Vec3d, phase: Double, packets: Int = 6, arc: Double = 0.7) {
        val delta = to.subtract(from)
        if (delta.lengthSquared() < 0.01) return
        val dir = delta.normalize()
        val side = dir.crossProduct(Vec3d(0.0, 1.0, 0.0)).let { if (it.lengthSquared() < 1e-4) Vec3d(1.0, 0.0, 0.0) else it.normalize() }
        for (k in 0 until packets) {
            val t = (phase + k.toDouble() / packets) % 1.0
            val bend = sin(t * Math.PI)
            val twist = t * Math.PI * 6.0
            val p = from.add(delta.multiply(t))
                .add(side.multiply(sin(twist) * 0.22 * bend))
                .add(0.0, bend * arc + cos(twist) * 0.18 * bend, 0.0)
            spawn(world, dust(BRIGHT, 1.1f), p.x, p.y, p.z, 2, 0.04, 0.04, 0.04, 0.0)
            if (k % 2 == 0) spawn(world, dust(BLACK, 0.8f), p.x, p.y, p.z, 1, 0.08, 0.08, 0.08, 0.0)
        }
    }

    fun sound(world: ServerWorld, at: Vec3d, event: SoundEvent, volume: Float = 1.0f, pitch: Float = 1.0f) {
        world.playSound(null, at.x, at.y, at.z, event, SoundCategory.PLAYERS, volume, pitch)
    }

    /** Camera shake for every player within [radius] of [at] (weaker with distance; needs the addon on the client). */
    fun shake(world: ServerWorld, at: Vec3d, radius: Double, strength: Float, ticks: Int) {
        for (p in world.players) {
            val d = Math.sqrt(p.squaredDistanceTo(at))
            if (d > radius) continue
            val s = strength * (1.0 - d / radius * 0.7).toFloat()
            if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, dev.henny.hha_blood.net.ShakePayload.ID)) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, dev.henny.hha_blood.net.ShakePayload(s, ticks))
            }
        }
    }

    /** Short full-screen colour flash for every player within [radius] of [at] (weaker with distance). */
    fun screenFlash(world: ServerWorld, at: Vec3d, radius: Double, color: Int, strength: Float, ticks: Int) {
        for (p in world.players) {
            val d = Math.sqrt(p.squaredDistanceTo(at))
            if (d > radius) continue
            val s = strength * (1.0 - d / radius * 0.6).toFloat()
            if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, dev.henny.hha_blood.net.ScreenFlashPayload.ID)) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, dev.henny.hha_blood.net.ScreenFlashPayload(color, s, ticks))
            }
        }
    }

    /** A sound only [player] hears (e.g. the Blood Tracker heartbeat). */
    fun soundFor(player: ServerPlayerEntity, event: SoundEvent, volume: Float = 1.0f, pitch: Float = 1.0f) {
        player.networkHandler.sendPacket(
            PlaySoundS2CPacket(RegistryEntry.of(event), SoundCategory.PLAYERS, player.x, player.y, player.z, volume, pitch, player.random.nextLong()),
        )
    }

    /** Wound burst: splash + flying chunks + a few drops (used whenever something starts to bleed or is hit hard). */
    fun wound(world: ServerWorld, at: Vec3d, strength: Int = 1) {
        spawn(world, splat, at, 1 + strength, 0.15, 0.0)
        spawn(world, crumbs, at, 6 * strength, 0.2, 0.18)
        spawn(world, dropsFalling, at, 3 * strength, 0.25, 0.0)
    }

    fun sound(world: ServerWorld, at: Vec3d, event: RegistryEntry<SoundEvent>, volume: Float = 1.0f, pitch: Float = 1.0f) =
        sound(world, at, event.value(), volume, pitch)
}
