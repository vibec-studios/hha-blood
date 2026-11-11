# HHA Blood

Blood addon (**v0.13.0**) for the Minecraft mod **[H&H Armors (HHA)](https://modrinth.com/mod/hha)** by Henny
([source](https://github.com/Henny263462/HHA)) - Fabric, Minecraft 1.21.11, Kotlin.

Adds Blood's Armor, the Blood Cyclone, the Aderngrotten cave biome and the Slaughter Pit boss fight (Blood Cerberus and Crimson Minotaur).

`reference/old-jars/` holds the original Blood jar, `reference/old-textures-blood/` the old red Blood textures.

## Build

Requires JDK 21+ (tested with 25). The HHA base mod is picked up from `libs/HHA-0.8.0.jar`.

```
./gradlew build            # jar lands in blood/build/libs/
./gradlew :blood:runServer # dev server (accept the EULA in blood/run/eula.txt first)
./gradlew :blood:runClient # dev client
```

The first build downloads and decompiles Minecraft (Loom) and takes a few minutes.
Copy the resulting `HHA_Blood-<version>.jar` into your `mods/` folder next to HHA, Fabric API and Fabric Language Kotlin.

## Blood's Armor

**Keys** use HHA's own ability system (Controls -> HHA): **Ability 1 (G)** = Nihil, **Ability 2 (H)** = Blood Trail,
**Ability 4** = Blood Tracker (unbound in HHA by default - bind it yourself).

| Piece | Effect |
|-------|--------|
| Helmet | Regeneration I. **Blood Tracker**: pick a player, a pulsing vein of blood points at them (only you see it); the prey feels it but not who hunts them. |
| Chestplate | Fire Resistance. **Enduring Effects**: every beneficial effect lasts x5 longer (cap 20 min); Resistance from golden apples is not extended. **Nihil** (full set): stun + drain everything nearby, then a giant blood spike impales them and a Blood Nova makes them bleed. |
| Leggings | **Infection**: chance on hit to make the target bleed. **Blood Trail**: sink into a blood pool, become truly invisible, enemies nearby take damage and get Slowness VI, then re-emerge. |
| Boots | No fall damage, Speed I. **Blood Rush**: hitting a bleeding enemy gives Speed II. |
| Sword | **Hemorrhage**: bleeding enemies take x2 damage. **Bloodsaws** (right click): 2 spinning, bouncing blades; both hit = Blood Fountain. |
| Mace | **Blood Cyclone** (right click): the mace spins high above and rains blood bolts on everything inside the marked circle; every hit applies Infection. |

Bleeding is a real status effect, visible and audible. All values are in the HHA config (`/hha list`, keys start with `hha_blood.`).

## The boss: Blood Cerberus and Crimson Minotaur

A single arena (**Slaughter Pit / Schlachtgrube**) per world, deep in the **Aderngrotten** biome below the Nether lava sea,
10 000-15 000 Nether blocks from 0/0 (`/locate structure hha_blood:single_arena`). A marked route leads through the entrance halls to the pit.

- **Phases I-II:** Blood Cerberus, with three separately breakable heads.
- **Phase III:** Crimson Minotaur with axe attacks, grabs and charges.
- **Phase IV:** a large maze with 229 connected cells, seven-block walls, dead ends and long branching routes. Every fighter gets Blood Trail through HHA Ability 2, even without Blood armor. Hide, sneak and ambush the Minotaur.
- **Phase V:** the Minotaur throws away his axe and fights with fists, grabs, wrestling slams, stomps and delayed ground shockwaves. Blood Trail remains available.

Version 0.13.0 fixes the overhead axe strike hitting players far above the boss and makes the unarmed finale more active and varied.
The Minotaur retains v0.12's reduced hit damage and increased effective health. Boss models, animation, music, dialogue and skippable cutscenes are included.
Rewards include Blood Ingots, Netherite, smithing templates and Cerberus head trophies. Re-summon with a Blood Offering (`blood_offering`) at the altar.
Details: [`docs/SPEC_BOSS.md`](docs/SPEC_BOSS.md). Test commands: `/hhablood boss tp|skip|reset|info`.

Validation: the server boss self-test completes all five phases, victory and arena restoration. The client game test checks the axe hitbox at ground level and 5.5/30 blocks above, Blood Trail and ambushes, attack variety, damage balance and wrestling attacks.

## Repository layout

```
blood/src/main/kotlin   Kotlin logic (abilities, items, boss, client)
blood/src/main/java     Mixins
blood/src/main/resources  Assets, lang (en_us, de_de), recipes, tags, worldgen
tools/                  Java generators for textures, models and sounds (no Python needed)
docs/                   Specs and API notes
libs/                   HHA base mod (compile dependency)
```

## Notes for development

- The decompiled old jars had no sources; `blood` was rewritten by hand.
- Damage-over-time uses `timeUntilRegen = 0` before each hit so invulnerability frames do not swallow it.
- Textures: never run `GenArmour.java` / `GenBloodStyle.java` normally - they overwrite the current armour (see `CLAUDE.md`).
- `CLAUDE.md` contains the detailed project notes (in German).
