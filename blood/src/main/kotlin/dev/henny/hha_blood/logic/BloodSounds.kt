package dev.henny.hha_blood.logic

import dev.henny.hha_blood.HhaBlood
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.sound.SoundEvent

/**
 * Own sound events.
 *
 * `nihil_voice` ships EMPTY on purpose (see `assets/hha_blood/sounds.json`): put your own voice line
 * into a resource pack (`assets/hha_blood/sounds/nihil_voice.ogg` + a sounds.json entry) and enable
 * `/hha set hha_blood.nihil_voice true` - it is then played when Nihil is cast.
 */
object BloodSounds {
    @JvmField val NIHIL_VOICE: SoundEvent = register("nihil_voice")

    // Nihil blood thorn - own synthesised sounds (tools/sounds/GenSpikeSound.java -> sounds/nihil/*.ogg)
    @JvmField val SPIKE_RUMBLE: SoundEvent = register("nihil_spike_rumble")
    @JvmField val SPIKE_STAB: List<SoundEvent> = listOf(register("nihil_spike_stab1"), register("nihil_spike_stab2"), register("nihil_spike_stab3"))
    @JvmField val SPIKE_IMPALE: SoundEvent = register("nihil_spike_impale")

    // Bleeding - own synthesised sounds (same generator, sounds/bleed/*.ogg)
    @JvmField val BLEED_OPEN: SoundEvent = register("bleed_open")
    @JvmField val BLEED_PULSE: SoundEvent = register("bleed_pulse")

    // Blood Cyclone bolts - dry whistle + crystal crack instead of the old wet splash sounds
    @JvmField val BOLT_FIRE: SoundEvent = register("cyclone_bolt_fire")
    @JvmField val BOLT_IMPACT: SoundEvent = register("cyclone_bolt_impact")

    private fun register(name: String): SoundEvent {
        val id = HhaBlood.id(name)
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id))
    }

    fun init() {
        // touching the object registers the fields above
    }
}
