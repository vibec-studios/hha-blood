package dev.henny.hha_blood.hollows

import dev.henny.hha_blood.HhaBlood
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes
import net.minecraft.particle.SimpleParticleType
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.sound.SoundEvent

/** Particles and sounds of the Vein Hollows ambience (client side: client/hollows). */
object HollowsParticles {
    /**
     * A tiny glowing drop of blood that floats UPWARDS ("reverse blood rain") - ambient particle of the biome,
     * also used by blood vents and rising blood columns. Velocity y of the spawn call = rising speed (0 = default).
     */
    lateinit var MOTE: SimpleParticleType private set

    /** One "lub-dub" of the giant heart in the Slaughter Pit (positional, mono). */
    lateinit var HEARTBEAT: SoundEvent private set
    /** A distant, muffled heartbeat of the whole cave (non-positional). */
    lateinit var HEARTBEAT_FAR: SoundEvent private set
    /** Ambient loop of the biome: deep breathing drone. */
    lateinit var AMBIENT_LOOP: SoundEvent private set

    fun init() {
        MOTE = Registry.register(Registries.PARTICLE_TYPE, HhaBlood.id("blood_mote"), FabricParticleTypes.simple())
        HEARTBEAT = sound("ambient.pit_heart.beat")
        HEARTBEAT_FAR = sound("ambient.vein_hollows.heartbeat")
        AMBIENT_LOOP = sound("ambient.vein_hollows.loop")
    }

    private fun sound(name: String): SoundEvent {
        val id = HhaBlood.id(name)
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id))
    }
}
