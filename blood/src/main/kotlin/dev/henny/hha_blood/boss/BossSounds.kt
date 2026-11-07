package dev.henny.hha_blood.boss

import dev.henny.hha_blood.HhaBlood
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.sound.SoundEvent

/**
 * Sounds and music of the Slaughter Pit fight. All synthesized (tools/sounds/GenBossSounds.java,
 * tools/sounds/GenBossMusic.java); every event can be replaced with a resource pack (sounds.json).
 */
object BossSounds {
    private fun register(name: String): SoundEvent {
        val id = HhaBlood.id(name)
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id))
    }

    val MINOTAUR_ROAR = register("boss.minotaur.roar")
    val MINOTAUR_SNORT = register("boss.minotaur.snort")
    val MINOTAUR_GRUNT = register("boss.minotaur.grunt")
    val MINOTAUR_HURT = register("boss.minotaur.hurt")
    val MINOTAUR_DEATH = register("boss.minotaur.death")
    val MINOTAUR_STEP = register("boss.minotaur.step")
    val MINOTAUR_VOICE = register("boss.minotaur.voice")
    val MINOTAUR_CHARGE = register("boss.minotaur.charge")
    val AXE_SWING = register("boss.axe.swing")
    val AXE_IMPACT = register("boss.axe.impact")
    val AXE_SPIN = register("boss.axe.spin")
    val WALL_RISE = register("boss.wall_rise")

    val CERBERUS_GROWL = register("boss.cerberus.growl")
    val CERBERUS_BARK = register("boss.cerberus.bark")
    val CERBERUS_BITE = register("boss.cerberus.bite")
    val CERBERUS_HOWL = register("boss.cerberus.howl")
    val CERBERUS_FLAME = register("boss.cerberus.flame")
    val CERBERUS_HURT = register("boss.cerberus.hurt")
    val CERBERUS_WHIMPER = register("boss.cerberus.whimper")
    val CERBERUS_DEATH = register("boss.cerberus.death")
    val CERBERUS_STEP = register("boss.cerberus.step")

    val GATE_OPEN = register("boss.gate_open")
    val CHAINS = register("boss.chains")
    val FIRE_BURST = register("boss.fire_burst")
    val GEYSER = register("boss.geyser")
    val IMPACT_HEAVY = register("boss.impact_heavy")
    val STINGER = register("boss.stinger")
    val HEARTBEAT = register("boss.heartbeat")
    val PARALYZE = register("boss.paralyze")

    val MUSIC_PIT = register("music.pit_ambient")
    val MUSIC_TENSION = register("music.tension")
    val MUSIC_CERBERUS = register("music.cerberus")
    val MUSIC_CERBERUS_UNLEASHED = register("music.cerberus_unleashed")
    val MUSIC_MINOTAUR = register("music.minotaur")
    val MUSIC_MINOTAUR_FINAL = register("music.minotaur_final")
    val MUSIC_VICTORY = register("music.victory")

    fun init() {
        // touching the object registers the events above
    }
}
