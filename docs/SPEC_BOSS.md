# Blood-Boss: Fürst Sanguin / Cruorax (Konzept, mit Mats abgestimmt am 06.11.2026)

## Entscheidungen von Mats
- Konzept **Fürst → Drache** (Phase 1 humanoid, Phase 2 Drache per Cutscene).
- Arena generiert natürlich, Boss erwacht beim Betreten; danach am Thron/Altar mit Opfer-Item **wiederbeschwörbar**.
- Drops: Trophäen/Kosmetik (Drachenkopf, Musikplatte, Trims) + Rüstungs-Craftingmaterial (Blood Ingots, Netherit ...).
  **Bei Wiederholungs-Kills deutlich weniger Material** (pro Spieler zählen).
- Musik selbst synthetisiert, per Resourcepack austauschbar.

## Lore
Fürst Sanguin war der erste Träger der Blood-Rüstung, trank Blut, bis er zum Drachen wurde, wartet im
Herzgewölbe unter dem Nether auf den nächsten Träger. Dialoge reagieren darauf, ob man die Rüstung trägt
("Du trägst meine Haut ...").

## Phase 1 - Der Blutfürst (~2,5 Blöcke)
Verdorbene Blood-Rüstung, nutzt die Rüstungs-Fähigkeiten: Bloodsaws, Blood Trail (taucht in den Blutsee,
kommt hinter dem Spieler hoch), Blutzyklon, Mini-Nihil. Bei 50 %: Cutscene, er spießt sich mit dem eigenen
Dorn auf, der Blutsee steigt, der Drache formt sich.

## Phase 2 - Cruorax, der Blutwyrm (~12 Blöcke Spannweite)
Krallt sich an Decke/Wände, fliegt Runden. Blut-Odem (Strahl, Lachen mit Bluten), Stalaktiten-Regen
(Schwanz an die Decke), Blutflut (Boden flutet, nur Inseln sicher), Herzschlag-Schockwelle, Riesen-Nihil.
Schwachpunkt: pulsierendes Herz in der Brust, nur beim Atemholen offen.

## Runden
1. **Biom/Blöcke/Arena** - erledigt (v0.4.0), Feedback-Fixes in v0.4.1 (Blut-Flüssigkeit, 32x32, Bugs)
2. Blutfürst (Entity, Modell, KI, Arena-Trigger, Bossleiste)
3. Drache Cruorax
4. Cutscenes (Kamerafahrt, Kinobalken, überspringbar, MP), Dialoge, Musik + Biom-Ambience
5. Belohnungen (Drop-Zähler pro Spieler), Feinschliff

## Runde 1 - Technik (`blood/src/main/kotlin/dev/henny/hha_blood/hollows/`)
- **Biom** `hha_blood:vein_hollows` ("Aderngrotten"): `MultiNoiseBiomeSourceMixin` setzt es im Vanilla-Nether
  unter Y 48 überall, wo `HollowsRegion.cellValue` (Nether-Temperaturrauschen an verschobenen Koordinaten)
  > `THRESHOLD` (0.3, ~19 % des Untergrunds). Über Fabric `NetherBiomes` mit unerreichbarem Rauschpunkt
  registriert, damit es ein "mögliches" Nether-Biom ist (Features werden indexiert).
- **Terrain** `HollowsTerrain` per `NoiseChunkGeneratorMixin` am Ende von `buildSurface` (also VOR Carvern und
  Features - als Feature hat es Deko der Nachbar-Chunks überschrieben = "abgeschnittene Teile", Runde-1-Feedback):
  füllt Regionsspalten Y 1-26 mit Blutstein (Lavasee darüber bleibt, Rand per interpoliertem Wert abgerundet),
  gräbt Höhlen Y 6-22 nur in Spalten mit 2 Zellen Abstand zum Rand (feste Hülle), unter Y 7 Blutseen,
  Böden mit geronnenem Blut, Decken mit Aderstein. Nether-Carver können Blutstein nicht schneiden.
- **Blut** (`BloodFluid`, `HollowsFluids`): eigene Flüssigkeit statt Wasser (Mats: "Wasser macht keinen Sinn").
  Im Fluid-Tag `minecraft:water` -> funktioniert wie Wasser (Schwimmen, Ertrinken, Fallschutz, roter Nebel aus dem
  Biom), aber `BloodFluidBlock.tryDrainFluid` = leer -> kein Eimer, keine unendlichen Quellen, Tickrate 6.
  Client: `SimpleFluidRenderHandler` mit eigenen, ungetönten Texturen `blood_still`/`blood_flow`, translucent.
- Deko hält Abstand zur Arena (`HollowsGen.nearArena` über die Struktur-Referenzen der Chunks,
  `HeartVaultPiece.occupied`), Rippenkäfige nur auf ebenem Boden, jede Rippe nur mit Fuß auf festem Grund
  (kein Schweben), bleiben im Chunk + direkten Nachbarn. Selbsttest zählt schwebende Kristalle/Knochen.
- Texturen 32x32 (Mats: "higher quality").
- Deko: `blood_crystals`, `rib_bones` (halb vergrabene Rippenkäfige), Erz-Features Aderstein/Kristallblock,
  `vein_shaft` (Eingangsschacht mit Wendeltreppe durch den Lavasee bis in offene Luft, Knochenkrone).
- **Arena** `HeartVaultStructure`/`HeartVaultPiece` (Strukturschritt top_layer_modification, keine Monster-Spawns):
  Kuppel R 30 / H 34 ab Boden Y 10, 3er Hülle mit Adern, 10 Knochenrippen + Ring, Herz an der Decke mit
  4 Arterien, Blutsee R 14 mit 4 Inseln, Speichen aus Aderstein, Thron-Podest im Norden + Opferaltar (0,10,-17),
  Ost/West-Tunnel mit Endkammer, Südtunnel zum Schacht (nur generiert, wenn die Schachtspalte offene Luft hat).
  Struktur-Set spacing 18 / separation 6.
- Blöcke: Blutstein (+ poliert, Ziegel, gemeißelt, Treppe/Stufe/Mauer), Aderstein (animiert, Herzschlag-Puls),
  geronnenes Blut (bremst), Blutkristallblock/-haufen/klein, Opferaltar, Item Blutkristallsplitter.
  Assets per `java tools/textures/GenHollows.java .` (Treppe/Stufe/Mauer aus Vanilla-Steinziegeln kopiert).
- Biom-Sounds/Musik vorerst Vanilla (Crimson Forest / Basalt Deltas) - eigene in Runde 4.
- **Testen**: `/hhablood hollows stats|slice <y> [r]|cut [r]` (PNGs in `<run>/hha_debug/`). Dev-Selbsttest:
  leere Datei `blood/run/hha_debug/autotest` anlegen, `./gradlew :blood:runServer` -> sucht Biom + Arena,
  schreibt Schnittbilder, stoppt den Server. (Befehle per stdin an runServer funktionieren hier NICHT zuverlässig.)
  Für eine frische Testwelt `level-name` in `blood/run/server.properties` umstellen und danach zurück auf `world`.
- Achtung `Path.of("")`: `File(server.runDirectory.toFile(), x)` zeigt ins Laufwerks-Root -> `server.getPath(x)`.
