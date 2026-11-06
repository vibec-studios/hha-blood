# HHA Blood

Blood addon (**v0.6.0**) for the Minecraft mod **[H&H Armors (HHA)](https://modrinth.com/mod/hha)** by Henny
([source](https://github.com/Henny263462/HHA)) - Fabric, Minecraft 1.21.11, Kotlin.

Adds Blood's Armor, the Blood Cyclone, the Aderngrotten cave biome and the boss fight (Blutfürst -> Blutdrache Cruorax).

`reference/old-jars/` holds the original Blood jar, `reference/old-textures-blood/` the old red Blood textures.

## Build

Requires JDK 21+ (tested with 25). The HHA base mod is picked up from `libs/HHA-0.6.2.jar`.

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

## The boss: Blutfürst -> Cruorax

A single arena (**Herzgewölbe**) per world, deep in the **Aderngrotten** biome below the Nether lava sea,
10 000-15 000 blocks from 0/0 (`/locate structure hha_blood:single_arena`). Phase 1 is the Blood Prince,
once he falls he transforms into the dragon **Cruorax** (phases 2 and 3). Rewards: Blood Ingots, Netherite, smithing templates,
and the trophy *Heart of Cruorax*. Re-summon with a Blood Offering (`blood_offering`) at the altar.
Details: [`docs/SPEC_BOSS.md`](docs/SPEC_BOSS.md). Test commands: `/hhablood boss tp|wake|reset|info`.

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
