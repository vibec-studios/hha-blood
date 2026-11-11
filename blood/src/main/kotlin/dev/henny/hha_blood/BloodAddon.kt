package dev.henny.hha_blood

import dev.henny.hha.api.HhaAddon
import dev.henny.hha.api.HhaAddonContext
import dev.henny.hha.api.ability.AbilitySlot
import dev.henny.hha.api.ability.SimpleAbility
import dev.henny.hha_blood.logic.Bleed
import dev.henny.hha_blood.logic.BloodAbilities
import dev.henny.hha_blood.logic.BloodCombat
import dev.henny.hha_blood.logic.BloodCyclone
import dev.henny.hha_blood.logic.BloodPassives
import dev.henny.hha_blood.logic.BloodTracker
import dev.henny.hha_blood.logic.Bloodsaws
import dev.henny.hha_blood.logic.TrueInvis
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.entity.EquipmentSlot

/**
 * Blood's Armor - HHA addon.
 *
 * Ability keys = HHA's own (Controls -> HHA, no own keybinds):
 *  - Nihil         -> "Ability 1" (G), like HHA's set beams
 *  - Blood Trail   -> "Ability 2" (H), like Hell's Fire Camp
 *  - Blood Tracker -> "Ability 4" (unbound in HHA by default)
 *  - Bloodsaws / Blood Cyclone are on the right mouse button of the sword / mace.
 */
class BloodAddon : HhaAddon {

    override fun onInitialize(context: HhaAddonContext) {
        BloodItems.init()
        registerConfig(context)
        registerSet(context)
        registerAbilities(context)
        registerLore(context)
        BloodCombat.init()

        ServerTickEvents.END_SERVER_TICK.register { server ->
            Bleed.tick(server)
            BloodAbilities.tick(server)
            Bloodsaws.tick(server)
            BloodCyclone.tick()
            BloodTracker.tick(server)
            TrueInvis.tick(server)
            BloodPassives.tickHeldWeapons(server)
        }
        ServerLifecycleEvents.SERVER_STOPPING.register {
            Bleed.reset(); BloodAbilities.reset(); Bloodsaws.reset(); BloodCyclone.reset(); BloodCombat.reset()
            BloodTracker.reset(); TrueInvis.reset()
        }
        context.logger.info("Blood's Armor registered - tracker, infection, Nihil, Bloodsaws, Blood Cyclone.")
    }

    private fun registerConfig(c: HhaAddonContext) {
        // helmet
        c.registerToggle("blood_tracker", true)
        c.registerNumber("tracker_select_range", 250.0)   // 0 = whole dimension
        c.registerNumber("tracker_duration", 6000.0)      // 5 min
        c.registerNumber("tracker_cooldown", 12000.0)     // 10 min, also after ending a hunt early
        // chestplate
        c.registerToggle("enduring_effects", true)
        c.registerNumber("enduring_multiplier", 5.0)
        c.registerNumber("enduring_max_ticks", 24000.0)
        c.registerNumber("enduring_weaving_ticks", 9600.0)   // 8 min
        c.registerNumber("enduring_turtle_ticks", 1200.0)    // 1 min (Turtle Master Resistance)
        c.registerNumber("enduring_turtle_slow_factor", 0.5) // Turtle Master Slowness duration x0.5 (1 = vanilla)
        // Slaughter Pit boss fight (Minotaur + Cerberus)
        c.registerToggle("boss_safe_knockout", false)      // true: lethal hits in the pit knock you out instead of killing you (a totem always pops first)
        c.registerNumber("boss_damage", 1.0)               // multiplier for all boss damage
        c.registerNumber("boss_max_hit", 30.0)             // most damage one hit can deal to a boss
        c.registerNumber("boss_health_per_player", 0.5)    // +50 % effective health per extra player
        c.registerNumber("boss_damage_per_player", 0.15)   // +15 % boss damage per extra player
        // leggings
        c.registerToggle("infection", true)
        c.registerNumber("infection_chance", 15.0)
        c.registerNumber("infection_cooldown", 300.0)
        c.registerToggle("blood_trail", true)
        c.registerNumber("blood_trail_cooldown", 1200.0)
        c.registerNumber("blood_trail_duration", 140.0)     // 7 s incl. dive + rise (renamed from blood_trail_invis so the old saved 60 does not stick)
        c.registerNumber("blood_trail_range", 5.0)
        c.registerNumber("blood_trail_damage", 6.0)
        c.registerNumber("blood_trail_slow", 80.0)
        // boots
        c.registerToggle("fall_immunity", true)
        c.registerToggle("blood_rush", true)
        c.registerNumber("blood_rush_duration", 60.0)
        // bleeding
        c.registerNumber("bleed_duration", 100.0)
        c.registerNumber("bleed_dps", 1.0)
        // sword
        c.registerToggle("hemorrhage", true)
        c.registerNumber("hemorrhage_multiplier", 2.0)
        c.registerToggle("bloodsaws", true)
        c.registerNumber("bloodsaws_cooldown", 400.0)
        c.registerNumber("bloodsaws_damage", 6.0)
        c.registerNumber("bloodsaws_speed", 1.15)
        c.registerNumber("bloodsaws_spread", 0.18)
        c.registerNumber("bloodsaws_width", 1.6)
        c.registerNumber("bloodsaws_bounces", 5.0)        // ricochets off walls AND from enemy to enemy
        c.registerNumber("bloodsaws_life", 70.0)
        c.registerNumber("fountain_window", 12.0)
        c.registerNumber("fountain_range", 5.0)
        c.registerNumber("fountain_damage", 8.0)
        // mace
        c.registerToggle("blood_cyclone", true)
        c.registerNumber("cyclone_cooldown", 800.0)
        c.registerNumber("cyclone_range", 10.0)
        c.registerNumber("cyclone_damage", 5.0)
        c.registerNumber("cyclone_duration", 80.0)
        c.registerNumber("cyclone_height", 17.0)          // blocks above the spot where it was thrown
        // set bonus
        c.registerToggle("nihil", true)
        c.registerNumber("nihil_cooldown", 900.0)
        c.registerNumber("nihil_range", 15.0)
        c.registerNumber("nihil_duration", 60.0)
        c.registerNumber("nihil_drain", 10.0)
        c.registerNumber("nihil_lifesteal", 0.6)
        c.registerNumber("nihil_nova_damage", 8.0)
        c.registerNumber("nihil_spike_damage", 8.0)
        c.registerNumber("nihil_lift_level", 3.0)          // Levitation level of the victims (3 ~ 5 blocks up)        // the impaling thorn (3rd beat), hits everything in its hitbox once
        c.registerToggle("nihil_voice", false)              // plays hha_blood:nihil_voice (supply it via resource pack)
    }

    private fun registerSet(c: HhaAddonContext) {
        c.armorSet("blood") {
            helmet(BloodItems.BLOOD_HELMET)
            chestplate(BloodItems.BLOOD_CHESTPLATE)
            leggings(BloodItems.BLOOD_LEGGINGS)
            boots(BloodItems.BLOOD_BOOTS)
            weapon(BloodItems.BLOOD_SWORD)
            weapon(BloodItems.BLOOD_MACE)
            onTick { world, player, state -> BloodPassives.onTick(world, player, state) }
        }
    }

    /**
     * Same keys as HHA's own sets (HHA ability system, no own keybinds):
     *  - Ability 1 (G) = set ability, like HHA's light/lava beam -> Nihil (full Blood set)
     *  - Ability 2 (H) = leggings ability, like Hell's Fire Camp -> Blood Trail
     *  - Ability 3 (U) stays HHA's Ultra mode
     *  - Ability 4 (HHA leaves it unbound - set it in Controls -> HHA) -> Blood Tracker (helmet)
     * HHA dispatches per slot to the first registered ability that is available, its own Heaven/Hell
     * abilities only match while their set is worn, so they never collide with these.
     */
    private fun registerAbilities(c: HhaAddonContext) {
        // Slaughter Pit stage 4: everyone in the labyrinth can sink into the blood with Ability 2 (H), with or
        // without the Blood armour. Registered first so it wins over Blood Trail; HHA's own Ability-2 abilities
        // (registered before any addon) are redirected through the cast event while the labyrinth runs.
        c.registerAbility(
            SimpleAbility(
                dev.henny.hha_blood.boss.PitLabyrinth.ABILITY_ID, AbilitySlot.ABILITY_2,
                { p -> dev.henny.hha_blood.boss.PitLabyrinth.canUse(p) },
                dev.henny.hha_blood.boss.PitLabyrinth::cast,
            ),
        )
        dev.henny.hha.api.event.HhaEvents.ABILITY_CAST.register(object : dev.henny.hha.api.event.HhaEvents.AbilityCast {
            override fun allowCast(player: net.minecraft.server.network.ServerPlayerEntity, ability: dev.henny.hha.api.ability.Ability): Boolean {
                if (ability.slot != AbilitySlot.ABILITY_2 || ability.id == dev.henny.hha_blood.boss.PitLabyrinth.ABILITY_ID) return true
                if (!dev.henny.hha_blood.boss.PitLabyrinth.canUse(player)) return true
                dev.henny.hha_blood.boss.PitLabyrinth.cast(player)
                return false
            }
        })
        c.registerAbility(
            SimpleAbility(
                c.id("nihil"), AbilitySlot.ABILITY_1,
                { p ->
                    p.getEquippedStack(EquipmentSlot.HEAD).isOf(BloodItems.BLOOD_HELMET) &&
                        p.getEquippedStack(EquipmentSlot.CHEST).isOf(BloodItems.BLOOD_CHESTPLATE) &&
                        p.getEquippedStack(EquipmentSlot.LEGS).isOf(BloodItems.BLOOD_LEGGINGS) &&
                        p.getEquippedStack(EquipmentSlot.FEET).isOf(BloodItems.BLOOD_BOOTS)
                },
                BloodAbilities::castNihil,
                EquipmentSlot.CHEST,
            ),
        )
        c.registerAbility(
            SimpleAbility(
                c.id("blood_trail"), AbilitySlot.ABILITY_2,
                { p -> p.getEquippedStack(EquipmentSlot.LEGS).isOf(BloodItems.BLOOD_LEGGINGS) },
                BloodAbilities::castBloodTrail,
                EquipmentSlot.LEGS,
            ),
        )
        c.registerAbility(
            SimpleAbility(
                c.id("blood_tracker"), AbilitySlot.ABILITY_4,
                { p -> p.getEquippedStack(EquipmentSlot.HEAD).isOf(BloodItems.BLOOD_HELMET) },
                BloodTracker::openFor,
                EquipmentSlot.HEAD,
            ),
        )
    }

    private fun registerLore(c: HhaAddonContext) {
        c.registerLore(BloodItems.BLOOD_HELMET) {
            line("item.hha_blood.blood_helmet.lore1")
            line("item.hha_blood.blood_helmet.lore2", num("hha_blood.tracker_select_range"))
            line("item.hha_blood.blood_helmet.lore3", seconds("hha_blood.tracker_duration"), seconds("hha_blood.tracker_cooldown"))
        }
        c.registerLore(BloodItems.BLOOD_CHESTPLATE) {
            line("item.hha_blood.blood_chestplate.lore1")
            line("item.hha_blood.blood_chestplate.lore2", num("hha_blood.enduring_multiplier"), seconds("hha_blood.enduring_weaving_ticks"), seconds("hha_blood.enduring_turtle_ticks"))
            line("item.hha_blood.blood_chestplate.lore3", num("hha_blood.nihil_range"), seconds("hha_blood.nihil_duration"), seconds("hha_blood.nihil_cooldown"))
            line("item.hha_blood.blood_chestplate.lore4", num("hha_blood.nihil_spike_damage"), num("hha_blood.nihil_nova_damage"))
        }
        c.registerLore(BloodItems.BLOOD_LEGGINGS) {
            line("item.hha_blood.blood_leggings.lore1", num("hha_blood.infection_chance"), seconds("hha_blood.infection_cooldown"))
            line("item.hha_blood.blood_leggings.lore2", seconds("hha_blood.blood_trail_duration"), seconds("hha_blood.blood_trail_cooldown"))
            line("item.hha_blood.blood_leggings.lore3", num("hha_blood.blood_trail_range"), num("hha_blood.blood_trail_damage"), seconds("hha_blood.blood_trail_slow"))
        }
        c.registerLore(BloodItems.BLOOD_BOOTS) {
            line("item.hha_blood.blood_boots.lore1")
            line("item.hha_blood.blood_boots.lore2", seconds("hha_blood.blood_rush_duration"))
        }
        c.registerLore(BloodItems.BLOOD_SWORD) {
            line("item.hha_blood.blood_sword.lore1", num("hha_blood.hemorrhage_multiplier"))
            line("item.hha_blood.blood_sword.lore2", num("hha_blood.bloodsaws_damage"), seconds("hha_blood.bloodsaws_cooldown"))
            line("item.hha_blood.blood_sword.lore3", num("hha_blood.fountain_damage"), num("hha_blood.fountain_range"))
        }
        c.registerLore(BloodItems.BLOOD_MACE) {
            line("item.hha_blood.blood_mace.lore1")
            line("item.hha_blood.blood_mace.lore2", num("hha_blood.cyclone_range"), num("hha_blood.cyclone_damage"), seconds("hha_blood.cyclone_cooldown"))
        }
        c.registerLore(BloodItems.BLOOD_INGOT) {
            line("item.hha_blood.blood_ingot.lore1")
        }
    }
}
