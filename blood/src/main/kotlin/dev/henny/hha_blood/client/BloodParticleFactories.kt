package dev.henny.hha_blood.client

import dev.henny.hha_blood.BloodParticles
import net.fabricmc.fabric.api.client.particle.v1.FabricSpriteProvider
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry
import net.minecraft.client.particle.BillboardParticle
import net.minecraft.client.particle.BillboardParticleSubmittable
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleFactory
import net.minecraft.client.render.Camera
import net.minecraft.client.texture.Sprite
import net.minecraft.client.world.ClientWorld
import net.minecraft.particle.SimpleParticleType
import net.minecraft.util.math.MathHelper
import org.joml.Quaternionf

/** Client side of [BloodParticles]: sprites live in `textures/particle/blood_*.png`. */
object BloodParticleFactories {

    fun register() {
        val registry = ParticleFactoryRegistry.getInstance()
        registry.register(BloodParticles.DRIP, pending { p, w, x, y, z, vx, vy, vz ->
            BloodDripParticle(w, x, y, z, vx, vy, vz, p, hang = 3 + w.random.nextInt(6))
        })
        registry.register(BloodParticles.DRIP_FALL, pending { p, w, x, y, z, vx, vy, vz ->
            BloodDripParticle(w, x, y, z, vx, vy, vz, p, hang = 0)
        })
        registry.register(BloodParticles.DRIP_LAND, pending { p, w, x, y, z, _, _, _ -> BloodPuddleParticle(w, x, y, z, p) })
        registry.register(BloodParticles.GLOB, pending { p, w, x, y, z, vx, vy, vz -> BloodGlobParticle(w, x, y, z, vx, vy, vz, p) })
        registry.register(BloodParticles.SPLAT, pending { p, w, x, y, z, vx, vy, vz -> BloodSplatParticle(w, x, y, z, vx, vy, vz, p) })
        registry.register(BloodParticles.FLAME, pending { p, w, x, y, z, vx, vy, vz -> BloodFlameParticle(w, x, y, z, vx, vy, vz, p) })
    }

    private fun pending(
        create: (FabricSpriteProvider, ClientWorld, Double, Double, Double, Double, Double, Double) -> Particle,
    ) = ParticleFactoryRegistry.PendingParticleFactory<SimpleParticleType> { provider ->
        ParticleFactory<SimpleParticleType> { _, world, x, y, z, vx, vy, vz, _ -> create(provider, world, x, y, z, vx, vy, vz) }
    }
}

private fun FabricSpriteProvider.at(index: Int): Sprite {
    val list = sprites
    return list[index.coerceIn(0, list.size - 1)]
}

/** A drop that (optionally) hangs and swells for a moment, then falls and leaves a puddle. */
class BloodDripParticle(
    world: ClientWorld, x: Double, y: Double, z: Double,
    vx: Double, vy: Double, vz: Double,
    private val provider: FabricSpriteProvider,
    private var hang: Int,
) : BillboardParticle(world, x, y, z, provider.at(if (hang > 0) 0 else provider.sprites.size - 1)) {

    init {
        setBoundingBoxSpacing(0.01f, 0.01f)
        velocityX = if (hang > 0) 0.0 else vx
        velocityY = if (hang > 0) 0.0 else vy
        velocityZ = if (hang > 0) 0.0 else vz
        gravityStrength = 0.045f
        maxAge = 90
        scale = 0.075f + random.nextFloat() * 0.045f
    }

    override fun getRenderType(): RenderType = RenderType.PARTICLE_ATLAS_TRANSLUCENT

    override fun tick() {
        lastX = x; lastY = y; lastZ = z
        if (age++ >= maxAge) {
            markDead()
            return
        }
        if (hang > 0) {
            hang--
            move(0.0, -0.0015, 0.0)
            if (hang == 0) setSprite(provider.at(provider.sprites.size - 1))
            return
        }
        velocityY -= gravityStrength
        move(velocityX, velocityY, velocityZ)
        velocityX *= 0.96
        velocityY *= 0.98
        velocityZ *= 0.96
        if (onGround) {
            markDead()
            world.addParticleClient(BloodParticles.DRIP_LAND, x, y, z, 0.0, 0.0, 0.0)
        }
    }
}

/** Small flat puddle lying on the ground; fades out slowly. */
class BloodPuddleParticle(
    world: ClientWorld, x: Double, y: Double, z: Double,
    provider: FabricSpriteProvider,
) : BillboardParticle(world, x, y + 0.015, z, provider.at(0)) {

    private val spin = random.nextFloat() * MathHelper.TAU

    init {
        setBoundingBoxSpacing(0.01f, 0.01f)
        velocityX = 0.0; velocityY = 0.0; velocityZ = 0.0
        maxAge = 70 + random.nextInt(50)
        scale = 0.09f + random.nextFloat() * 0.08f
        collidesWithWorld = false
    }

    override fun getRenderType(): RenderType = RenderType.PARTICLE_ATLAS_TRANSLUCENT

    override fun tick() {
        lastX = x; lastY = y; lastZ = z
        if (age++ >= maxAge) markDead()
        val left = maxAge - age
        alpha = if (left < 25) (left / 25f).coerceIn(0f, 1f) else 1f
    }

    /** Lies flat: drawn facing up and facing down so it is visible whatever the face culling does. */
    override fun render(submittable: BillboardParticleSubmittable, camera: Camera, tickProgress: Float) {
        render(submittable, camera, Quaternionf().rotationX(-MathHelper.HALF_PI).rotateZ(spin), tickProgress)
        render(submittable, camera, Quaternionf().rotationX(MathHelper.HALF_PI).rotateZ(spin), tickProgress)
    }
}

/** A chunk of blood flying out of a wound; falls with gravity and may leave a puddle. */
class BloodGlobParticle(
    world: ClientWorld, x: Double, y: Double, z: Double,
    vx: Double, vy: Double, vz: Double,
    provider: FabricSpriteProvider,
) : BillboardParticle(world, x, y, z, provider.getSprite(world.random)) {

    private val spinSpeed = (random.nextFloat() - 0.5f) * 0.5f

    init {
        setBoundingBoxSpacing(0.05f, 0.05f)
        velocityX = vx
        velocityY = vy + 0.05
        velocityZ = vz
        maxAge = 30 + random.nextInt(25)
        scale = 0.07f + random.nextFloat() * 0.09f
        zRotation = random.nextFloat() * MathHelper.TAU
        lastZRotation = zRotation
    }

    override fun getRenderType(): RenderType = RenderType.PARTICLE_ATLAS_TRANSLUCENT

    override fun tick() {
        lastX = x; lastY = y; lastZ = z
        lastZRotation = zRotation
        if (age++ >= maxAge) {
            markDead()
            return
        }
        velocityY -= 0.04
        move(velocityX, velocityY, velocityZ)
        velocityX *= 0.94
        velocityY *= 0.98
        velocityZ *= 0.94
        if (onGround) {
            markDead()
            if (random.nextFloat() < 0.6f) world.addParticleClient(BloodParticles.DRIP_LAND, x, y, z, 0.0, 0.0, 0.0)
            return
        }
        zRotation += spinSpeed
    }
}

/** Crimson flame: full-bright, rises and flickers through its frames, shrinks and fades at the end. */
class BloodFlameParticle(
    world: ClientWorld, x: Double, y: Double, z: Double,
    vx: Double, vy: Double, vz: Double,
    private val provider: FabricSpriteProvider,
) : BillboardParticle(world, x, y, z, provider.at(0)) {

    private val baseScale: Float

    init {
        setBoundingBoxSpacing(0.01f, 0.01f)
        velocityX = vx * 0.5 + (random.nextDouble() - 0.5) * 0.01
        velocityY = vy * 0.5 + 0.02 + random.nextDouble() * 0.02
        velocityZ = vz * 0.5 + (random.nextDouble() - 0.5) * 0.01
        maxAge = 12 + random.nextInt(10)
        baseScale = 0.12f + random.nextFloat() * 0.1f
        scale = baseScale
        collidesWithWorld = false
        updateSprite(provider)
    }

    override fun getRenderType(): RenderType = RenderType.PARTICLE_ATLAS_TRANSLUCENT

    override fun getBrightness(tint: Float): Int = 0xF000F0

    override fun getSize(tickProgress: Float): Float {
        val life = ((age + tickProgress) / maxAge).coerceIn(0f, 1f)
        return baseScale * (1f - life * life * 0.8f)
    }

    override fun tick() {
        lastX = x; lastY = y; lastZ = z
        if (age++ >= maxAge) {
            markDead()
            return
        }
        move(velocityX, velocityY, velocityZ)
        velocityX *= 0.9
        velocityY = velocityY * 0.96 + 0.003
        velocityZ *= 0.9
        val left = maxAge - age
        alpha = if (left < 5) (left / 5f).coerceIn(0f, 1f) else 1f
        updateSprite(provider)
    }
}

/** Animated splash burst (4 frames) for hits and freshly opened wounds. */
class BloodSplatParticle(
    world: ClientWorld, x: Double, y: Double, z: Double,
    vx: Double, vy: Double, vz: Double,
    private val provider: FabricSpriteProvider,
) : BillboardParticle(world, x, y, z, provider.at(0)) {

    init {
        setBoundingBoxSpacing(0.01f, 0.01f)
        velocityX = vx * 0.3
        velocityY = vy * 0.3
        velocityZ = vz * 0.3
        maxAge = 7 + random.nextInt(4)
        scale = 0.22f + random.nextFloat() * 0.18f
        collidesWithWorld = false
        zRotation = random.nextFloat() * MathHelper.TAU
        lastZRotation = zRotation
        updateSprite(provider)
    }

    override fun getRenderType(): RenderType = RenderType.PARTICLE_ATLAS_TRANSLUCENT

    override fun tick() {
        lastX = x; lastY = y; lastZ = z
        lastZRotation = zRotation
        if (age++ >= maxAge) {
            markDead()
            return
        }
        move(velocityX, velocityY, velocityZ)
        velocityX *= 0.8; velocityY *= 0.8; velocityZ *= 0.8
        updateSprite(provider)
    }
}
