# HHA Addon-API - Notizen (HHA 0.6.2)

Quelle: `ADDONS.md` und `docs/` im HHA-Repo (https://github.com/Henny263462/HHA), plus gelesener Quellcode.

## Grundlagen
- Addon = normale Fabric-Mod mit Entrypoints `hha` (Server/Common, `HhaAddon.onInitialize(context)`) und optional `hha_client` (`HhaClientAddon`), `"depends": {"hha": ">=0.6.0"}`.
- Reihenfolge: HHA registriert Items/Sets/Abilities -> Addon-`hha`-Entrypoints -> **dann** Config laden (Config-Werte im Entrypoint nicht lesen/cachen) -> Commands/Netzwerk/Tick.
- Config-Keys werden `<addonid>.<key>` (z. B. `hha_blood.nihil_range`), erscheinen in `/hha list|toggle|set|get`, Werte werden an Clients für die Lore gesynct.
- Lore: `context.registerLore(item) { line("translation.key", num("..."), seconds("..."), hearts("...")) }` - `%s`-Platzhalter, `seconds` rechnet Ticks in Sekunden.
- Sets: `context.armorSet("name") { helmet(..); chestplate(..); leggings(..); boots(..); weapon(..); onTick { world, player, state -> } }`. Für jedes Set erzeugt HHA `/hha kit <addonid>.<path>`.
- Abilities: `SimpleAbility(id, AbilitySlot, available, action, factionGateSlot)`. Pro Slot gewinnt die erste registrierte Ability mit `isAvailable == true`.
- `Cooldowns.set(player, stack, "config.key")` setzt den Item-Cooldown aus der Config und überspringt ihn im Ultra-Modus.
- Events (`HhaEvents`): `ABILITY_CAST` (abbrechbar), `PLAYER_TICK`, `FULL_SET_CHANGED`, `SHOULD_HARM`, `IS_FRIENDLY`.
- Hilfsklassen (halb-stabil): `logic.Fx` (spawn/ring/spiral/launchPlayer), `logic.Targeting` (`shouldHarm`, `isFriendly`), `logic.Trust`, `logic.UltraMode`, `logic.FactionLock`.
- Assets (Modelle, Texturen, `equipment/*.json`, Lang) liefert jedes Addon in seinem eigenen Namespace.

## Gotchas
- **Slots 1-3** haben Standardtasten G/H/U. **Slots 4-6 sind unbelegt** (`GLFW_KEY_UNKNOWN`) -> Addons müssen eigene Tasten mitbringen. Der Payload dafür ist `dev.henny.hha.net.AbilityPayload(ABILITY_4|5|6)` (C2S, von HHA registriert).
- `MOVEMENT`-Slot = Sprung in der Luft (Heaven-Doppelsprung).
- Die FactionLock blockt nur Heaven/Hell-Items; Addon-Items sind neutral (`factionOf(stack) == null` -> erlaubt).
- Für Spieler-Velocity `Fx.launchPlayer(player, vec)` nutzen (sendet das Paket), `velocityDirty` allein reicht nicht.
- DoT-Schaden: `entity.timeUntilRegen = 0` vor `damage(...)`, sonst verschlucken i-frames den Schaden.
- Items: `HhaItemHelpers.register(id) { Item(it...) }` / `registerArmor(id, material, type) { settings }`. Armor-Material braucht `EquipmentAsset`-Key + `assets/<ns>/equipment/<name>.json` (Layer `humanoid`, `humanoid_leggings`).
- Yarn 1.21.11: `Entity.getEntityPos()`, `getEntityWorld()`, `LivingEntity.damage(ServerWorld, DamageSource, float)`, `ItemDisplayEntity.setItemStack` ist public, `DustParticleEffect(int rgb, float scale)`, `KeyBinding.Category.create(Identifier)`.
- Bekannte HHA-eigene Log-Meldung auf dem Dev-Server: Datapack `file/hha_custom` ("Pack declares support for version newer than 81, but is missing mandatory fields min_format and max_format") - nicht unser Fehler.
- Die Modrinth-Instanz hat HHA 0.6.2 (neueste Version, alle HHA-Versionen sind nur für 1.21.11).
