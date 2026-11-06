# Heaven & Hell: Addons - Projektnotizen

Addons für die Minecraft-Mod **H&H Armors (HHA)** (https://modrinth.com/mod/hha, Autor: Henny, MIT, Quellcode https://github.com/Henny263462/HHA - das Repo ist archiviert/read-only).
Ziel des Nutzers (Mats): seine unfertigen Addons **fixen und weiterentwickeln**. Sprache im Chat: Deutsch.

## Umzug (06.11.2026)

- Das Projekt liegt jetzt in `C:\Users\Mats\Desktop\Heaven-Hell-Addons` (Kopie aus dem alten T3-Scratch-Ordner `C:\Users\Mats\.t3\scratch\2026-11-05-kansnt-du-mir-ein-neues-48aac6ed`, der alte Ordner ist unverändert).
- **Nicht mitkopiert** (werden beim ersten `./gradlew :blood:build` neu erzeugt, dauert beim ersten Mal ein paar Minuten): `.gradle/` (Loom-Cache inkl. Minecraft-Sources-Jar), `.kotlin/`, `build/`, `blood/build/`.
- **ffmpeg** (für die eigenen Sounds, `tools/sounds/GenSpikeSound.java` -> WAV -> OGG) lag nur im Scratchpad des alten Chats und ist hier NICHT dabei. Bei Bedarf portable neu laden (https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip, nur entpacken, nicht installieren), dann `ffmpeg -i x.wav -c:a libvorbis -q:a 6 x.ogg`.
- Die Minecraft-Sources für Nachschlagen: nach dem ersten Build in `.gradle/loom-cache/minecraftMaven/.../*-sources.jar`.
- Installiertes Jar: `F:\Modrinth\profiles\1.21.11\mods\HHA_Blood-0.3.0.jar` (Stand 06.11. 13:11 = identisch mit `dist/HHA_Blood-0.3.0.jar`).
- Letzter Stand mit Mats (Runde 6): Bluten-Puls tiefer, Zyklon-Sounds (Abfeuern Vanilla, Einschlag Pfeil-Thunk), roter Blitz beim Dorn sichtbarer - **Rückmeldung von Mats dazu steht noch aus**.

## Boss-Projekt (ab 06.11.2026)

Neuer Endboss zur Blood-Rüstung: **Fürst Sanguin → Blutdrache Cruorax** im eigenen Höhlenbiom
**Aderngrotten** unter dem Nether-Lavasee, Arena **Herzgewölbe**. Konzept, Mats' Entscheidungen, Runden-Plan
und Technik: `docs/SPEC_BOSS.md`. **Runde 1 (Biom, Blöcke, Arena) fertig = v0.4.0** (installiert, Modrinth-
Instanz enthält jetzt `HHA_Blood-0.4.0.jar`, 0.3.0 entfernt). Nur NEUE Nether-Chunks bekommen das Biom.
Nächste Runde: der Blutfürst (Phase 1).
**v0.4.1** (installiert, 0.4.0 entfernt) = Mats' Feedback zu Runde 1: abgeschnittene Teile + schwebende Blöcke
gefixt (Terrain jetzt per Mixin in der Surface-Phase), eigene Flüssigkeit **Blut** statt Wasser (wie Wasser, kein
Eimer), alle Hollows-Texturen 32x32 (`GenHollows.java`, teils von einem Opus-Subagenten gezeichnet, der am
Rate-Limit abbrach - Rest selbst fertiggestellt).
**v0.5.0** (installiert, 0.4.1 entfernt) = Runde 2 Blutfürst + Mats-Wünsche:
- **Nur EINE Region/Arena pro Welt**, 10 000-15 000 Blöcke von 0/0 **im Nether** (Mats: Nether-Koordinaten). Mittelpunkt aus
  dem Seed + Suche nach offener Schachtspalte (HollowsRegion.center()), Struktur-Platzierung hha_blood:single_arena
  (Unterklasse von random_spread, damit /locate sie findet). Details docs/SPEC_BOSS.md.
- **Tasten = HHA-System** (Mats): Nihil = HHA Ability 1 (G), Blood Trail = Ability 2 (H), Blutspürer = Ability 4 (in HHA
  unbelegt). Eigene Tasten R/V/B entfernt. U bleibt HHAs Ultra-Modus.
- **Blutfürst** (oss/): Entity hha_blood:blood_prince (450 HP, Blood-Rüstung + Schwert, Skala 1.45), Zustandsmaschine
  ohne Goals, Angriffe Schwert-Kombo, Ausfallschritt, Sägeblätter, Blutbad-Abtauchen/Auftauchen, Blutzyklon, Nihil (<72 %),
  Wut <50 %. Arena (HeartVaultArena): Thron-Spawn, Erwachen beim Betreten, Bossleiste, Rückzug nach 20 s ohne Spieler,
  Belohnung pro Spieler (1. Kill voll: 6 Blood Ingots, 2 Netherit, 2 Templates, 12 Splitter; danach 50 % 1 Ingot + Splitter),
  Wiederbeschwörung mit lood_offering am Opferaltar. Test-Befehle /hhablood boss tp|wake|reset|info.
  Selbsttest: Datei lood/run/hha_debug/bosstest (Fake-Spieler). Dev-Server hat pause-when-empty-seconds=0.
  Textur GenPrince.java (Opus-Subagent). Phase 2 Drache = Runde 3.
- **v0.5.1** (installiert): Arena-Blöcke harmonisiert (gemeinsame 8-Farben-Rampe, Licht oben links, ruhiger gemeißelter
  Block; Opus-Subagent in `GenHollows.java`), geronnenes Blut = `HoneyBlock` mit Honig-Modell (transluzente Außenhülle +
  Kern, Texturen `clotted_blood_top/side/bottom`).
- **v0.6.0** (installiert, 0.5.1 entfernt; Rückmeldung abwarten) = Runde 3 nach Mats' Test-Feedback ("Arena größer, See in der Mitte weg/kleiner, Fürst trägt Rüstung = komisch,
  dreht sich dauernd"): Arena R 44 / H 42 (`HeartVaultPiece`, Thron per `THRONE_SHIFT` 14 an die Nordwand), statt See
  kleines Ritual-Becken (`POOL_R` 3.2, 1 tief) mit Rune-Ringen, Biom bis Y 64. Fürst ohne Rüstungs-Layer (nur Schwert,
  Rüstungspunkte als Attribut), Dreh-Fix: nicht mehr navigieren wenn < 3.2 Blöcke (Zielpunkt lag unter ihm), eigene `face()`.
  Erwacht sobald jemand in der Kuppel ist. **Phase 2/3 = Cruorax** (`CruoraxEntity`, `DragonAttacks`, Modell+Renderer
  `client/boss/CruoraxRenderer.kt`, Textur `GenCruorax.java` vom Opus-Subagenten): tödlicher Schlag auf den Fürsten ->
  `TRANSFORM` (80 Ticks) -> Drache formt sich (90 Ticks) -> fliegt (noClip, Velocity-Steuerung, in der Kuppel geklemmt),
  Sturzflug, Blut-Odem (+Blutlachen mit Bluten), Stalaktiten-Regen (Bodenmarkierung), Landung (Herz offen = x1.6 Schaden),
  Klaue, Schwanzfeger; < 35 % Phase 3: Herzschlag-Schockringe (springen!), Großer Nihil. 700 HP. Belohnung erst nach dem
  Drachen (+ Trophäe `heart_of_cruorax` beim 1. Sieg). Alle fliehen 20 s -> kompletter Reset (Fürst auf den Thron).
  Selbsttest `bosstest` spielt den ganzen Kampf durch.
- Enduring Effects: **Resistenz aus (verzauberten) Goldäpfeln wird nicht mehr verlängert** (Mats) - erkannt über das aktive
  Item beim Anwenden der Effekte (`EnduringEffects.isEatingGoldenApple`); restliche Apfel-Effekte bleiben x5.
## Stand (05.11.2026)

| Modul | Status |
|-------|--------|
| `blood` | **v0.3.0** (Feedback-Runden 1+2 von Mats eingearbeitet, s. u.), baut, Dev-Server und Dev-Client laden ohne Fehler/Textur-Warnungen. Fähigkeiten, Tracker-Bildschirm, echte Unsichtbarkeit **nicht im Spiel getestet**. Jar in `dist/` + Modrinth-Instanz `F:\Modrinth\profiles\1.21.11\mods\` (0.2.0 dort entfernt). |
| `void` | **auf Eis.** Nicht im Build (`settings.gradle.kts`: `include("void")` auskommentiert). Mats: "Lass Void erstmal in Ruhe" - ein Freund hat die neueren Sachen gemacht, unklar ob man Mats' Ideen nehmen darf. Im Ordner `void/` liegen nur die entpackten Ressourcen des alten Jars. |

Offene Fragen / nächste Schritte:
1. Blood v0.3.0 im Spiel testen (Tracker B inkl. Gejagten-Effekt, echte Unsichtbarkeit V, Tropfen/Blutflammen, Nihil-Sound, neue Texturen/HUD-Icons). Rückmeldung von Mats abwarten. Offen: Mats soll bestätigen, ob "Rib Trims" = Vanilla-Rib-Trim-Linien gemeint war; neues Mod-Icon-Kandidat (Generator `WRITE_ICON`) nicht installiert.
2. Void: erst weitermachen, wenn Mats grünes Licht gibt. Wichtig: der **alte Void weicht stark von Mats' Beschreibung ab** (siehe `docs/SPEC_VOID.md` vs. `docs/VOID_OLD_BEHAVIOUR.md`). Vor dem Start nachfragen: nach Beschreibung neu bauen oder bestehenden Code nur fixen?
3. Texturen/Modelle (alle per Generator in `tools/textures/`):
   - `GenTrimArmour.java`: **aktuelle Rüstung** (Runde 4, Mats' Wunsch per Screenshot): Vanilla-Netherit + **Redstone-Trims**, Flow auf Helm/Schuhen, Silence auf Brust/Hose, Palette etwas heller ("leuchtend"). Exakt wie Minecraft Trims einfärbt (Paletten-Permutation; Rohwerte lesen, Java-Gamma bei Graustufen-PNGs umgehen). Icons = Netherit-Icon + Item-Trim-Overlay, 2-Frame-Glühpuls. Liest aus `~/.gradle/caches/fabric-loom/1.21.11/minecraft-client.jar`. Vorschau: `java tools/textures/GenArmour.java . <dir> <steve.png> <vanillaDir> --preview`. Volle Rüstung: feine rote Funken + gelegentlich Blutflamme (`BloodPassives`).
   - **Nicht** `GenArmour.java` (Rippenkäfig-Ritter, Runde 3, verworfen) oder `GenBloodStyle.java` (rote Chevron-Rüstung, Runde 2, verworfen) normal laufen lassen - beide überschreiben die Rüstung.
   - `GenBloodStyle.java`: Schwert = bleiche Knochenklinge, 32x32-Icon + 3D-Modell `blood_sword_3d` (Hand/Boden 3D, GUI/Rahmen flach, `items/blood_sword.json` select display_context); Sägeblatt-Modell `bloodsaw`.
   - `GenCyclone.java`: 3D-Mace `blood_mace_3d` + Blutgeschoss `blood_bolt` (nur für Display-Entities des Zyklons).
   - Icons Schwert/Mace (Runde 3, Mats: "mehr ans 3D-Modell anpassen"): `GenSwordIcon.java` + `GenMace.java` rendern das 3D-Modell (25° um die Längsachse, 15° von oben, Mace vorher -45° auf die Diagonale), 12x12 Samples pro Pixel, dominante Farbe, Handkorrekturen, 1-px-Umriss; Herzschlag + Tropfen, beide 96-Tick-Schleife. Renderer ist in beiden Dateien kopiert (synchron halten). `GenBloodStyle` schreibt das Schwert-Icon nicht mehr verbindlich - nicht normal laufen lassen.
   - `GenSpike.java` (neu vom Agenten): Blutkristall-Dorn, 28 gedrehte Segmente mit 8 Graten, 7 Widerhaken, geronnene Lache am Fuß, 256x256-Textur aus 3D-Position gemalt (streckungsfest). 80 Elemente.
   - **Eigene Sounds** (`tools/sounds/GenSpikeSound.java`, rein synthetisiert, WAV -> OGG mit ffmpeg aus dem Scratchpad): `hha_blood:nihil_spike_rumble`, `nihil_spike_stab1..3`, `nihil_spike_impale` (Dateien `sounds/nihil/*.ogg`). ffmpeg ist NICHT installiert, nur temporär nach `%TEMP%\claude\...\scratchpad\ffmpeg` entpackt (gyan.dev essentials) - bei Bedarf neu laden.
   - Dorn-Stöße langsamer: 14 Ticks Beben, je Stoß 5 Ticks hoch + 7 Pause (~2,5 s bis er steckt). Nihil-Aussaugen dauert dafür mindestens 30+Dorn+10 Ticks (`nihilTotal`, ~4,5 s), die drei Schläge richten sich nach dem Dorn-Start.
   - `GenTextures.java`: Barren, HUD-Icons, Partikel (Mace-Code entfernt) (alter dunkler Netherit-Stil - passt farblich nicht mehr ganz, Restyle mit Mats klären). `GenFlame.java`: Blutflammen.
   - Schwert-3D: nur Third Person größer (0.95) und 30° höher = Rotation z **25** (Vanilla 55; **kleineres z = Klinge höher**, größeres senkt sie - 67/97 waren falsch herum). Translation **[0, 5.08, 4.17]**: berechnet, damit der Griffpunkt (Modell (8,0,8) vor der -45°-Neigung) nach Größe/Winkel-Änderung an derselben Hand-Stelle bleibt wie bei den Original-Werten (55°, 0.77, [0,4,0.5]). First Person = Vanilla (25, 0.616). Prüfen mit dem Hand-Renderer in GenBloodStyle (Kopie in Temp-Root laufen lassen, schreibt sonst Rüstung!). Änderungen in Modell-JSON UND `GenBloodStyle.swordDisplay()`.
   - Mace in der Hand = 3D-Modell `blood_mace_3d` (items/blood_mace.json select display_context, GUI/Rahmen flach). Display = Vanilla `handheld_mace` mit z-Rotation -45° (Modell steht senkrecht, Sprite liegt diagonal): 3rd z 10; 1st z -20, Skala 0.5 (Mats: zu groß), Translation per Griff-Erhalt [0, 0.37, -0.16]. In `GenCyclone.MACE_HELD_DISPLAY`. Jeder Treffer mit der Blood Mace spielt `ITEM_MACE_SMASH_GROUND` (wie beim Fangen nach dem Zyklon, `BloodCombat`).
   - Leuchtende Trims: zweite Equipment-Ebene `hha_blood:blood_glow` (nur Trim-Pixel, volles Rot, helle Stellen fast weiß) in `equipment/blood.json`; Client-Mixin `EquipmentRendererMixin` zeichnet sie mit `RenderLayers.eyes` (lichtunabhängig) und pulsiert die Tönung (65-100 %, 1,8 s, Mats: "rot pulsierend").
   - Nihil 3D (`NihilFx`): rotierender Sägering (`blood_saw_ring`, `GenSawRing.java`, Donut um jedes schwebende Opfer, zerplatzt bei der Nova). Schläge bei 15/40/62 % der Dauer. **Stacheln nur beim 3. Schlag** (Mats): Opfer werden in der Luft festgehalten (`pin`: Levitation weg, Mobs NoGravity, Spieler 0-Velocity-Paket jeden Tick), ein Stachel (`NihilFx.impale`, Länge = Höhe bis Körpermitte, 14 Ticks Aufstieg) bohrt sich hinein und bleibt bis nach der Nova; dann `release`. Nova hat weiterhin einen Stachel-Wellenring. Dazu Flammenwirbel, Blutregen, Crimson-Sporen.
   - Nihil-Stachel = eigenes Modell `blood_spike` (`GenSpike.java`: gedrehter, sich verjüngender Dorn, 12 Segmente + Kragen + 4 Widerhaken, je Segment eine Volltonfarbe dunkel->hellrot; zeigt entlang +Z, 16 px = 1 Block). Aufspießen: Bodenrisse/Flammenkrone, Blutschwall wenn die Spitze ankommt, Blut läuft am Dorn herunter. Vorschau beliebiger Modell-JSONs: `java tools/textures/GenBloodStyle.java --model-preview <out.png> <model.json> <tex.png> [rx] [z-Streckung]`; Ego-Sicht-Vergleich mit Vanilla: `--held-preview` (beide schreiben keine Texturen).
   - Mace Ego-Sicht final: Skala 0.72, Translation [0, 4, 0.8] (per `--held-preview` mit der Vanilla-Mace verglichen; 0.9 zu wuchtig, 0.5 zu klein).
   - **HHA speichert ALLE Config-Werte** in `config/hha/hha.json` (auch Addon-Keys). Ein geänderter Default greift also nicht, wenn der Key schon gespeichert ist -> bei Default-Änderungen den Key umbenennen (so geschehen: `blood_trail_invis` -> `blood_trail_duration`). Mats hat zum Testen `blood_trail_cooldown` auf 0.1 gesetzt.
   - Nihil: nur noch EIN riesiger Dorn pro Gegner (Spitze bei 85 % Körperhöhe, Basis ~1,3 Blöcke), keine Nova-Stachelringe mehr (Mats). Animation (Runde 2, Mats: "da aber kacke"): Entity steht fest am Boden, animiert wird NUR die Transformation (`FxDisplays.animate`, Offset/Rotation/Skala, Client interpoliert weich) - Position und Transformation von Displays interpolieren getrennt und ruckeln zusammen. 8 Ticks Beben (Spitze lugt zitternd raus) -> **drei Stöße** (Mats: "nacheinander hoch piksen"): 40 % -> 70 % -> volle Länge in den Gegner, je 3 Ticks Ruck (ease-out, Überschwinger, Ausbeulen) + 3 Ticks Zurückfedern, Krach/Risse pro Stoß mit steigender Tonhöhe -> halten bis nach der Nova -> versinken. Schläge bei 12/30/45 % der Dauer, damit der Dorn vor der Nova steckt. `cullBox` setzt die Sichtbarkeits-Box (Displays sind sonst 0x0 und verschwinden, wenn ihr Ursprung aus dem Bild ist). Opfer schweben mit Levitation `nihil_lift_level` (3, ~5 Blöcke);  Echte Hitbox (Box um die Dorn-Achse bis zur aktuellen Spitze) macht `nihil_spike_damage` (8) an allem, was sie trifft, einmal pro Dorn.
   - **Item-Display-Falle:** Minecrafts ItemDisplay-Renderer dreht jedes Item intern um 180° um Y. `FxDisplays` gleicht das mit der rechten Rotation `UNFLIP` aus - ohne das zeigte der Dorn nach unten und die Zyklon-Geschosse flogen rückwärts.
   - Nihil-Opfer sind am Fangort verankert (`anchors`): nur senkrecht schweben. Spieler bekommen jeden Tick ein Velocity-Paket (zurück zur Achse + Levitation-Steigrate 0.05), weil Slowness die Luftsteuerung nicht bremst; Mobs werden auf die Achse zurückgesetzt.
   - Blood Trail dauert jetzt 7 s (`blood_trail_duration` 140). **Auftauchen**: `EMERGE_TICKS` (24) vor Ende öffnet sich am aktuellen Standort ein Bad, der Doppelgänger steigt geduckt auf (folgt dem Spieler), richtet sich auf, dann `TrueInvis.stop` - der echte Spieler erscheint an seiner Stelle. Ohne Unsichtbarkeit (Milch/Tod) keine Animation. Abtauch-Bad/Pfütze am Startort nur ~2,5 s.
   - **Blood Trail Abtauchen** (`BloodDive`): Blutbad (`blood_pool`, `GenBloodPool.java`, animiert) öffnet sich, Doppelgänger = Vanilla-**Mannequin** mit Spieler-Profil/Skin-Layern/Ausrüstung (Accessor-Mixins `MannequinEntityAccessor`, `PlayerLikeEntityAccessor`), duckt sich, versinkt (noClip, 2,1 Blöcke), Platsch, Bad schließt sich nach der Unsichtbarkeit.
   - Status-Meldungen (Tracker-Pfeil, Abklingzeiten, "keine Gegner") laufen über `BloodHud` -> `HudLinePayload` -> `HudLineOverlay` (eigene Zeile **über** HHAs Cooldown-Icons; die Vanilla-Action-Bar liegt dahinter).
   - **Bluten sichtbarer** (Mats: "nicht sichtbar genug"): echter Status-Effekt `hha_blood:bleeding` (`BloodEffects`, Icon `textures/mob_effect/bleeding.png` aus `GenEffectIcon.java`, Effekt-Partikel = unsere fallenden Blut-Tropfen) - zeigt nur an, Schaden/Zeit bleiben in `Bleed`; Milch beendet beides. Wunde öffnet: Spritzer-Ring + eigener Sound `bleed_open`; jede Sekunde Arterien-Spritzer zur Seite + eigener Sound `bleed_pulse` (2 Varianten); Blut läuft dauernd am Körper herunter. Sounds aus `tools/sounds/GenSpikeSound.java` (`sounds/bleed/*.ogg`).
   - Test-Config (Infektion 100 %) ist wieder zurückgestellt (15 % / 300 Ticks). Bluten-Visuals: Mats "gut". Bluten-Sound v2: tiefer/voller ohne Zischen (Mats fand v1 "zu hoch, zu dünn, zu zischig").
   - Runde 6 (06.11.): Bluten-Puls v3 noch tiefer erzeugt + Pitch 0.5 (Minimum). Zyklon: Abfeuern wieder Vanilla (Breeze/Blaze-Shoot, Mats: "davor war besser"), Einschlag = `ENTITY_ARROW_HIT` (+ Crit bei Gegnern); die eigenen `cyclone_bolt_*`-Sounds sind registriert, aber ungenutzt. Roter Blitz beim Aufspießen: 5 FLASH-Partikel, Flammen- und Staub-Burst, Explosion + kurzer roter Vollbild-Blitz (`ScreenFlashPayload` -> `ScreenFlash`, skaliert mit "Verzerrungseffekte").
   - Runde 5 (06.11.): Bluten-Sounds werden mit Pitch ~0.6 abgespielt (Mats: "noch tiefer"). Dorn: Stoß-Stufen pro Opfer berechnet (Stoß 1+2 enden unter den Füßen, `levels`), Hitbox erst ab Stoß 3 (vorher traf schon Stoß 2). Blutzyklon-Geschosse: eigene Sounds `cyclone_bolt_fire` / `cyclone_bolt_impact` (trockenes Zischen, Kristall-Knacken; ersetzt Breeze-Shoot + Slime-Hit = "Wasser-Platschen").
   - Absturz-Fix: Kronen-Dornen wurden während der Iteration in `spikes` eingefügt -> ConcurrentModificationException im Server-Tick. `NihilFx.tick` iteriert jetzt über eine Kopie.
   - Dorn dramatischer: Kamera-Wackeln (`ShakePayload` -> `CameraShake`, Client-Mixin `GameRendererMixin` hinter `tiltViewWhenHurt`, skaliert mit "Verzerrungseffekte"), Blutlache wächst beim Beben (`blood_pool`), echte Bodenblock-Partikel, Druckwelle pro Stoß, beim letzten Stoß 4 schräge Kronen-Dornen (`crown`, `single`-Spikes), roter Blitz + Blutfontäne beim Aufspießen.
   - Blutzyklon-Partikel: auf Mats' Wunsch zweimal verkleinert, keine Splat-Partikel mehr.
   - Enduring/Turtle Master: Resistenz fix 1 min, **Slowness-Dauer x0.5** (`enduring_turtle_slow_factor`; Mats meinte mit "halbiert" die Dauer, nicht die Stärke). Weben fix 8 min.
4. Kein automatischer Test für Fähigkeiten (bräuchte Fabric GameTest mit Mock-Spieler; API in 1.21.11 ist datengetrieben, nicht ausprobiert).

## Bauen / Starten

- JDK 21+ (hier Temurin 25 installiert), Gradle 9.5.1 über den Wrapper, Loom `1.17-SNAPSHOT`, Kotlin 2.4.10, Yarn `1.21.11+build.6`, Fabric Loader 0.19.3, Fabric API 0.141.4, FLK 1.13.13+kotlin.2.4.10.
- `./gradlew :blood:build` -> `blood/build/libs/HHA_Blood-0.3.0.jar` (danach nach `dist/` kopieren, altes Jar in der Modrinth-Instanz ersetzen).
- Texturen werden per Java-Generator in `tools/textures/` erzeugt (kein Python auf dem Rechner).
- Quellen zum Nachschlagen: Minecraft-Sources-Jar in `.gradle/loom-cache/minecraftMaven/...-sources.jar`, HHA mit Vineflower dekompilierbar (`~/.gradle/caches/.../vineflower-1.12.0.jar`).
- `./gradlew :blood:runServer` (Port 25599). EULA wurde mit Mats' Erlaubnis in `blood/run/eula.txt` gesetzt (nur lokal, `run/` ist gitignored).
- HHA selbst liegt als `libs/HHA-0.6.2.jar` (kompiliert dagegen, wird in Dev-Läufen mitgeladen).
- Windows PowerShell 5.1: kein `&&`, kein `Encoding::Latin1` (-> `GetEncoding(28591)`).

## Struktur

```
blood/src/main/kotlin/dev/henny/hha_blood/   Kotlin-Logik (BloodAddon, BloodItems, logic/*, client/*, item/*)
blood/src/main/java/.../mixin/               LivingEntityMixin (Fallschaden, Hemorrhage, Enduring Effects)
blood/src/main/resources/                    fabric.mod.json, Assets, Lang (en_us, de_de), Rezepte, Tags
reference/old-jars/                          Original-Jars HHA_Blood-0.1.0, HHA_void-0.0.1
reference/old-textures-blood/                alte rote Blood-Texturen
docs/                                        Spezifikationen und API-Notizen
dist/                                        fertige Jars
project-icon.png                             Projektbild (Mats' H&H-Armors-Logo, 512x512)
```

Mod-IDs: `hha_blood` (Items `hha_blood:blood_*`, Config-Keys `hha_blood.*`), `hha_void` (alt).

## Blood - Entscheidungen und Hintergründe

- Die alten Jars hatten **keinen Quellcode**; Blood wurde von Hand neu geschrieben (Dekompilat via Vineflower war nur Referenz, nicht kompilierbar).
- **Hauptursachen "Fähigkeiten gehen nicht":** (a) HHA liefert "Ability 4-6" **unbelegt**, Nihil/Blood Trail lagen darauf -> Addon registriert eigene Tasten R/V (`HhaBloodClient`, sendet `AbilityPayload(ABILITY_4/5)`; HHAs eigene Taste hat Vorrang wenn belegt). (b) Unverwundbarkeits-Frames schluckten den Nihil-Drain -> vor jedem DoT-Schaden `timeUntilRegen = 0`.
- Blood Hook wurde **ersetzt** durch **Blood Cyclone** (Mace, Rechtsklick, 40 s). Das echte Item bleibt im Inventar (nichts kann verloren gehen).
- Enduring Effects per Mixin auf `LivingEntity.addStatusEffect`: positive Effekte + Weben (Liste `EXTRA`), nicht ambient/infinite, Faktor `enduring_multiplier` (5), Deckel `enduring_max_ticks` (24000). Turtle Master zählt jetzt mit (Mats-Wunsch): Resistance wird verlängert, die Slowness (schädlich) nicht. Eigene Effekte des Addons laufen über `EnduringEffects.withoutEnduring { }`.
- Bluten (`Bleed`): sichtbares Tropfen, Herzschlag-Sound beim Öffnen und jede Sekunde.
- **Eigene Partikel** (`BloodParticles`, Client: `BloodParticleFactories`): `drip` (hängt kurz, fällt, Pfütze), `drip_fall`, `drip_land` (flache Pfütze, doppelseitig gerendert), `glob` (Spritzer, ersetzt die alten Redstone-Block-Krümel), `splat` (4-Frame-Spritzer). `BloodFx.crumbs/drops/dropsFalling/splat/wound()`. Die alten FALLING_DUST-"Tropfen" waren im Spiel nicht zu sehen. Schwert/Mace tropfen beim Halten (`BloodPassives.tickHeldWeapons`).
- **Blutspürer** ist jetzt aktiv: Taste B -> Server schickt Spielerliste (`TrackerListPayload`) -> `TrackerScreen` -> Auswahl (`TrackerSelectPayload`). Config `tracker_select_range` (250, 0 = ganze Dimension), `tracker_duration` (6000 = 5 min), `tracker_cooldown` (12000 = 10 min; Mats-Wunsch: auch bei vorzeitigem Ende volle 10 min ab dem Ende). Die Beute merkt es (Mats-Wunsch), erfährt aber nicht wer: Meldung, Herzschlag alle 4 s, pulsierender roter Bildschirmrand (`HuntedPayload` -> `HuntedOverlay`). Ader nur für den Jäger sichtbar, Action-Bar mit Pfeil/Distanz/Restzeit, HUD-Icon `hud/tracker`.
- **Blood Trail = echte Unsichtbarkeit** (`TrueInvis`): anderen Spielern wird jeden Tick leere Ausrüstung geschickt, am Ende die echte. `TrueInvisPayload` (auch an einen selbst) + Client-Mixin `BipedEntityRendererMixin` leert Rüstung/Hand-Items im Render-State (auch eigenes F5/Inventar - Mats hatte allein in F5 getestet). `EntityMixin` unterdrückt Sprint-Partikel. Rüstungs-Tropfen/Schwert-Tropfen pausieren solange.
- **3D-Effekte** über Vanilla-`ItemDisplayEntity` + `minecraft:item_model`-Komponente (`FxDisplays`, getaggt `hha_blood_fx`, Überbleibsel werden beim Laden gelöscht).
- **Bloodsaws**: zwei 3D-Sägeblätter (Frisbee, geneigt, rotierend, leuchtend), prallen an Wänden ab und springen von Gegner zu Gegner (Mats-Wunsch), nie zurück zum Spieler (`awayFromOwner`). `bloodsaws_bounces` 5 (Wände + Gegner), `bloodsaws_life` 70.
- **Blood Cyclone** neu: Mace bleibt an der Wurfstelle (folgt nicht), schwebt `cyclone_height` (17) Blöcke hoch (unter Decken 2 darunter), kreiselt schräg (3D-Modell), feuert 3D-Blutgeschosse in Wellen in den Kreis + zielsuchend auf Gegner im Kreis, gestrichelter Blutring mit Flammen am Boden zeigt die Trefferzone. `cyclone_duration` 80.
- **Nihil-Sound** (Mohg-artig, läuft immer, auch ohne Stimme - Mats-Wunsch): Drachen-Knurren, Evoker-Ritual, Glocke, Blaze-Flammen beim Cast; drei "Nihil!"-Schläge (Sonic Boom, Glockenschlag, Feuerstoß) mit Blutflammen-Ring; Feuerknistern während des Saugens; Nova mit Ravager-Brüllen + Glocke. Partikel `hha_blood:blood_flame` (leuchtend, Sprites `blood_flame_0..3`). Eigenes Sound-Event `hha_blood:nihil_voice` ist absichtlich **leer** (Elden-Ring-Audio dürfen wir nicht mitliefern); Mats kann per Resourcepack eine eigene Datei einhängen und `hha_blood.nihil_voice` auf true setzen.
- HUD-Icons: Mats war erst unsicher, ist aber zufrieden, dass alle Icons aktive Fähigkeiten zeigen. Nichts ändern.
- Partikel nutzen HHAs Config `particle_effects` zur Skalierung (`BloodFx`).
- Texturen: dunkles Netherit mit Crimson-Rippen/Akzenten laut Design-Wunsch.

## Konventionen

- Kotlin für Logik, Java nur für Mixins. Jede Fähigkeit hat einen Toggle und Zahlen in der HHA-Config (`context.registerToggle/registerNumber`).
- Items/Sets/Abilities nur über die HHA-Addon-API registrieren (siehe `docs/HHA_API_NOTES.md`).
- Kein HHA-Code ändern; HHA-Asset-Sperren (`HEAVEN_ARMOR_LOCK.md` im HHA-Repo) beachten.

## T3-Code-Projekt

Projekt "Heaven & Hell: Addons" (Projektbild = `project-icon.png`). Der ursprüngliche Chat lag in "No project" und wurde nur umbenannt, das Bild hängt am Projekt, nicht am Chat.
