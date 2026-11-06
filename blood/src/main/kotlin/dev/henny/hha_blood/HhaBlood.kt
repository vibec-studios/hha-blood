package dev.henny.hha_blood

import dev.henny.hha_blood.hollows.HollowsBlocks
import dev.henny.hha_blood.hollows.HollowsDebug
import dev.henny.hha_blood.hollows.HollowsWorldgen
import dev.henny.hha_blood.logic.BloodSounds
import dev.henny.hha_blood.logic.BloodTracker
import dev.henny.hha_blood.logic.FxDisplays
import dev.henny.hha_blood.net.BloodNet
import net.fabricmc.api.ModInitializer
import net.minecraft.util.Identifier
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class HhaBlood : ModInitializer {

    override fun onInitialize() {
        BloodParticles.init()
        BloodEffects.init()      // after the particles: the effect uses the blood drop particle
        BloodSounds.init()
        BloodNet.registerPayloads()
        BloodTracker.registerReceivers()
        FxDisplays.init()
        HollowsBlocks.init()
        dev.henny.hha_blood.hollows.HollowsFluids.init()
        HollowsWorldgen.init()
        HollowsDebug.init()
        dev.henny.hha_blood.boss.BossContent.init()
        LOGGER.info("HHA Blood loaded - Nihil awaits.")
    }

    companion object {
        const val MOD_ID = "hha_blood"
        val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

        fun id(path: String): Identifier = Identifier.of(MOD_ID, path)
    }
}
