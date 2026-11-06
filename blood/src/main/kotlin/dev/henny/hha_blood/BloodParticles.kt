package dev.henny.hha_blood

import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes
import net.minecraft.particle.SimpleParticleType
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry

/**
 * Real blood particles (own sprites, rendered client side by `BloodParticleFactories`).
 *
 * Spawn them like vanilla particles: `count > 0` + spread + speed gives a random burst.
 *  - DRIP      a drop hangs for a moment, then falls and splashes into a little puddle
 *  - DRIP_FALL a drop that falls right away (keeps the velocity it was spawned with)
 *  - DRIP_LAND the small puddle a drop leaves on the ground
 *  - GLOB      a chunk of blood that flies, falls and leaves a puddle (splatter bursts)
 *  - SPLAT     an animated "splash" burst for hits and wounds
 */
object BloodParticles {
    @JvmField val DRIP: SimpleParticleType = register("drip")
    @JvmField val DRIP_FALL: SimpleParticleType = register("drip_fall")
    @JvmField val DRIP_LAND: SimpleParticleType = register("drip_land")
    @JvmField val GLOB: SimpleParticleType = register("glob")
    @JvmField val SPLAT: SimpleParticleType = register("splat")
    /** Glowing crimson flame (Mohg's blood flames) - rises, flickers, shrinks. */
    @JvmField val FLAME: SimpleParticleType = register("blood_flame")

    private fun register(name: String): SimpleParticleType =
        Registry.register(Registries.PARTICLE_TYPE, HhaBlood.id(name), FabricParticleTypes.simple())

    fun init() {
        // touching the object registers the fields above
    }
}
