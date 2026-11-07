# Blood-Boss v0.9 (Stand 07.11.2026 abends): Koepfe brechen, Axt steckt, Schaden x0.6, neuer Cerberus, Weg zur Arena

Aenderungen gegenueber v0.8 (auch in CLAUDE.md, Eintrag v0.9.0):
- Schaden: `PitBoss.BALANCE` 0.6 auf alle Boss-Treffer (Gametest: Cerberus braucht ~17 s fuer ein Totem bei voller Netherit P4).
- **Cerberus = Koepfe brechen**: jeder Kopf hat eigene HP (`headHp`, Stufe 1 je 110, Stufe 2 je 125) + eigene Bossleiste (Reisser/Flammenschlund/Heuler). Treffer gehen an den anvisierten Kopf (`headFor`: Nahkampf-Blickstrahl 1.4, sonst Punktabstand 1.7), Koerpertreffer nur x0.3 (`BODY_FACTOR`, Hinweis beim ersten). Gebrochener Kopf (`BROKEN`-Bitmaske, getrackt) haengt schlaff, sein Angriff faellt weg (0 Blutbiss, 1 Feuer/Feuerkreis, 2 Heulen; Dreifach-Angriffe brauchen 2 Koepfe), kurzes Taumeln. Stufe 1 endet bei 2 gebrochenen Koepfen, Stufe 2 bei allen 3. Entity-Leben spiegelt nur die Summe. Hitbox 3.3x3.6 (Koepfe ragen vor). `/hhablood boss breakhead`, `/kill` und `skip` brechen Koepfe.
- **Minotaurus**: Ansturm nur noch ab 14 Bloecken Abstand, Abklingzeit 18 s (Phase 3) / 15 s (Phase 5) / 12 s (Labyrinth, ab 9 Bloecken), max. 2 Anstuerme am Stueck (vorher 3), kein Ansturm direkt nach dem Heulen. **Verfehlter Spaltschlag = Axt steckt** (`STUCK`, 46 Ticks, x1.5 Schaden, Hinweis, Steinsplitter beim Herausreissen 14 Schaden in 2.6 Bloecken); Kombo nur nach Treffer; in der Raserei 50 %.
- Cerberus-Optik neu (Opus-Agent, `tools/textures/GenCerberus.java`): groessere getrennte Koepfe mit Charakter, Kragen/Mähne/Stacheln, Dobermann-Abzeichen, Hades-Brandzeichen, Magma-Risse entfesselt, eigene Haut `cerberus_broken.png` fuer gebrochene Koepfe, Trophaeen neu (128x128). `/hhablood boss pose <action> [unleashed] [broken]` (nur ruhender Cerberus), Gametest-Modus `HHA_BLOOD_TEST=cerberus`.
- **Klarer Weg zur Arena** (Opus-Agent, `hollows/PitApproach.kt`, `HollowsRoads.kt`): Wahrzeichen "Das Gerippe" (Knochen-Brustkorb auf einem Platz im Lavasee, Hoehe aus dem Rauschen `HollowsRegion.plazaY`), beleuchtete Dammwege O/W, offener Treppenturm (Wendeltreppe + Blut-Aufzug), Vorhalle mit Schaedel-Fassade, Prozessionsring R 62 mit Bogen/Laternen/Leuchtpfeilen; in neuen Chunks 5 Strassen vom Regionsrand. Wird in bestehenden Welten ueber den Arena-Umbau gebaut (`APPROACH_SIGNATURES` in `NEW_SIGNATURES`, ~11 700 Spalten, ~2 s). Biom (nur neue Chunks): Kavernen + Galerien, Terrassen, Saeulen, weniger Aderstein/Blutfaelle, `hanging_glow`.
- Lähmungs-Effekt hat jetzt ein Icon (`tools/textures/GenParalyzedIcon.java`, fehlte seit v0.7).
- Tests: Server-`bosstest` (loggt Kopf-Treffer, Axt-steckt, Anstuerme) + Client-Gametest (Kopfleisten, gebrochener Kopf, Totem, Tod, Todesnachrichten) gruen.

---

# Blood-Boss v0.8 (Stand 07.11.2026): haerter, neue Angriffe, HHA-Lore, Arena/Biom-Ausbau

Aenderungen gegenueber v0.7 (Details auch in CLAUDE.md, Eintrag v0.8.0):

- **v0.8.0** (installiert, 0.7.2 entfernt; Rueckmeldung abwarten) = Mats 07.11. "zu leicht/langweilig", Totem-Bug, Biom/Arena cooler, Death Messages, Zwinger unter den Balkon, Cutscenes + HHA-Lore:
- **Baut jetzt gegen HHA 0.8.0** (`libs/HHA-0.8.0.jar`, `gradle.properties hha_jar`) - Mats spielt 0.8.0. Der Client-Gametest braucht dafuer `-Dfabric.client.gametest.disableNetworkSynchronizer=true` (in `blood/build.gradle.kts`, HHA 0.8.0 sendet unterhalb der Fabric-Paket-API).
- Tod/Totem: Totem poppt normal (Fabric ALLOW_DEATH kommt VOR dem Totem-Check -> `hasTotem` laesst Vanilla machen), sonst echter Tod; altes K.o. nur per Config `boss_safe_knockout` (default aus).
- Schaden: eigene Damage-Types `hha_blood:slaughter|hellfire|crush` (bypasses_armor, Ruestung zieht in `PitBoss.hurt` max. 35 % ab, Protection/Resistenz wirken, scaling never). Blutsturz (Hemorrhage) zaehlt nicht mehr bei Boss-Blutungen (verdoppelte sonst Boss-Schaden).
- Cerberus 760 HP: Kugeln (ORBS), Kombos (Heulen->Sprung->Biss), Feuerspur, Feuerkreis (FIRE_RING -> Dreifach-Odem). Minotaurus 1024 HP (Vanilla-Maximum) + `toughness` 0.8: Kombos, Packen (GRAB, Befreien durch 35 Schaden), Erdbeben (QUAKE, Phase 5), Felssturz bei Wand/Saeule, Raserei <15 %, Axtwurf vom Balkon in Phase 1/2.
- Death Messages fuer alle Blood-Faehigkeiten (`logic/BloodDamage.kt`, Typen nihil, nihil_nova, nihil_spike, blood_trail, blood_cyclone, bloodsaw, blood_fountain, bleed, bleed_out). Achtung Vanilla-Logik: MIT Taeter wird der Basis-Key (mit %2$s) genommen, `.player` nur ohne Taeter ("while fighting").
- Zwinger unter dem Balkon (`GATE_Z` -33, `KENNEL_SPAWN` (0.5,0,-47.5)), alter West-Zwinger wird versiegelt. Biom/Arena (Opus-Agent): schlagendes Herz unter der Kuppel (Tempo nach eigenem Leben), wachende Augen, Galerie mit Skelett-Publikum, Titanenschaedel-Eingang, Blutfaelle/Blut-Aufzuege, Blutschlote, Blutglut, Ambient-Sounds (`tools/textures/GenPit.java`, `tools/sounds/GenPitAmbience.java`).
- Lore: Minotaurus = Asterion (Theseus), Hades gab ihm die Grube + Cerberus vom Tor; Finale mit Hermes-Cameo (HHA-Feder/-Brief/-Sounds per Registry-Lookup, Brief von Hades: Koepfe behalten). Erkennt HHA-Fortschritt (Hermes-Feder/Himmelsruestung).

Schaden-Richtwerte (gemessen im Gametest, volle Netherit + Protection IV): Biss 4.9 HP, Dreifachbiss je 2.7-3.0 HP, Odem-Tick 1.6 HP, Kugel 4.2 HP, Cerberus-Ansturm 6.7 HP. Berechnet: Minotaurus-Ansturm 55 Basis = ca. 12.9 HP, Spaltschlag 45 = ca. 10.5 HP, Packen gesamt ca. 13 HP.

Tests: Server `bosstest` (alte Arena wird umgebaut, alle Phasen) + Client-Gametest (Szenen per Leertaste, Finale komplett mit Screenshots, echter Schaden, Totem, Tod, Todesnachrichten).

---

# Blood-Boss v0.7: Crimson Minotaur + Blood Cerberus in der Schlachtgrube (Stand 07.11.2026)

**Mats (07.11.):** Fürst/Drache war "kacke" -> komplett neu nach seinen Referenzbildern: Minotaurus mit Zweihand-Axt
("Der Schlächter", stürmt, bricht Deckung, wird mit jedem Treffer schneller) und sein dreiköpfiger Höllenhund
(Reißer = Blutung, Flammenschlund = Feuer, Heuler = Lähmung). Phase 1-2 Cerberus, Phase 3-5 Minotaurus. Trophäe = die
drei Köpfe. Alles auf einmal (Modelle, Texturen, Animationen, Cutscenes, Musik), nach dem Vorbild von HHAs Hermes-Kampf
(github.com/vibec-studios/HHA, Branch `new`: Cine-Protokoll, CinePlayer, Kamera-/HUD-/Input-Mixins, KO statt Tod,
Arena-Schutz, Musik pro Stage, Client-Gametest mit Screenshots).

## Ablauf
| Teil | Inhalt |
|---|---|
| Intro (überspringbar) | Kamerafahrt über die Grube, Minotaurus erhebt sich vom Thron, "CERBERUS!", Fallgitter hebt sich, Titelkarte. Wiederholung: Kurzfassung |
| 1 Cerberus (520 HP, bis 50 %) | Biss (Blutung), Dreifachbiss, Feueratem (Brandflächen), Heulen (Lähmung, Säule/Wand davor schützt), Sprung, Ansturm. Minotaurus schaut vom Balkon zu und spottet |
| Cutscene | Cerberus bricht zusammen, "STEH AUF!", Blut der Grube -> entfesselt (andere Leuchttextur) |
| 2 Entfesselt (bis 0) | schneller, Feuerspur, Feuerringe (springen), Blutgeysire (Warnkreise), Dreifach-Atem |
| Cutscene | Cerberus stirbt, Minotaurus trauert, springt vom Balkon, Titelkarte |
| 3 Minotaurus (900 HP, bis 66 %) | Hieb + Stachelriss, Rundumschlag, Hornstoß, Ansturm (Schild bricht; Säule = zerbricht + 3,5 s Betäubung, x1,5 Schaden). Raserei: +1 pro Treffer (max 10), schneller |
| Cutscene + 4 Labyrinth (bis 33 %) | Mauern steigen aus dem Boden (Ansturm bricht durch), Wirbel, Axtwurf (Bumerang), Sprung mit Schockring |
| Cutscene + 5 Der letzte Stier | nimmt das Höllenfeuer des Cerberus auf: 3 Anstürme hintereinander mit Feuerspur, Lähmungs-Brüllen, Feuer-Schnauben |
| Finale | Kniefall, letzte Worte, Siegmusik, Belohnung direkt ins Inventar (1. Sieg: 6 Ingots, 2 Netherit, 2 Templates, 12 Splitter, 3 Köpfe; danach 50 % 1 Ingot + Splitter) |

Fair Play wie Hermes: tödlicher Treffer = KO (Eingangstunnel, nichts verloren, darf bis Kampfende nicht zurück), alle weg
10 s = Reset, in Cutscenes unverwundbar + Eingaben gesperrt, Überspringen per Abstimmung (Leertaste, alle müssen),
Arena unzerstörbar (Abbauen/Setzen/Explosionen, Creative darf), alle Kampf-Änderungen werden exakt aus dem Design
zurückgesetzt. Skalierung pro Spieler + Höchstschaden pro Treffer (Config `hha_blood.boss_*`).

## Technik
- `hollows/HeartVault.kt`: `HeartVaultPiece.design(dx,dy,dz)` = einzige Quelle für jeden Arena-Block (Worldgen, Upgrade
  alter Arenen per Prüfpunkten + Neuaufbau in Scheiben, Wiederherstellung). Schlachtgrube: Labyrinth-Boden, 8 Säulen r 34,
  Tribünen mit Feuerschalen, Balkon + Thron Norden, Altar davor, Zwinger mit Fallgitter Westen, Tunnel Süden/Osten.
- `boss/`: `PitFight` (Ablauf/Phasen/KO/Belohnung), `PitScenes` (Drehbuch, Kotlin-Sequenzen), `PitArena` (Schutz,
  temporäre Blöcke, Upgrade), `PitHazards`, `PitBoss` (Basis, Aktions-Stempel), `CerberusEntity`, `MinotaurEntity`,
  `Cine` (Protokoll), `PitLines` (Dialoglängen aus den Lang-Dateien), `BossSounds`.
- `client/cine/CinePlayer.kt` + Mixins Camera/InGameHud/MinecraftClient/KeyboardInput/Mouse/GameRenderer(FOV).
- Modelle/Texturen: `tools/textures/GenMinotaur.java`, `GenCerberus.java` (erzeugen auch `*ModelData.kt`, Trophäen,
  Axt-Itemmodell). Sounds: `tools/sounds/GenBossSounds.java`, Musik: `tools/sounds/GenBossMusic.java` (ffmpeg nötig).
- Tests: Server `blood/run/hha_debug/bosstest` (Fake-Spieler, ganzer Kampf + Arena-Upgrade-Prüfung),
  Client `./gradlew :blood:runClientGameTest` (Screenshots unter `blood/build/run/clientGameTest/screenshots`).
- Befehle: `/hhablood boss tp|info|skip|reset|rebuild`.
- Alter Fürst/Cruorax-Code: `reference/old-boss-v0.6/old-boss.tar`.

---

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
