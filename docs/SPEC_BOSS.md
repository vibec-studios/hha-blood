# Blood-Boss v0.13 (10.11.2026): Höhen-Hitbox und aktiveres Finale

**Status:** v0.13.0 gebaut und in der Modrinth-Instanz installiert (ersetzt 0.12.0); Prüfsummen identisch. Clienttest: Axt +30/+5,5 Blöcke = 0 Schaden, am Boden = 20,25; fünf verschiedene KI-Attacken in Phase V in 16 Sekunden. Balance, Blutspur, Hinterhalt und Sprungwurf bestanden; kompletter Serverkampf inkl. Sieg und Arena-Wiederherstellung erfolgreich. Protokolle: blood/build/verification/v0.13.0/.

- Physische Nahkampf-Kegel prüfen jetzt auch die Höhe: Spielerfüße von 2 Blöcken unter dem Angriff bis 1 Block über der Bosshöhe. Der Axt-Spaltschlag trifft nicht mehr beliebig weit nach oben, und er braucht Sichtkontakt. Die Blutspalten vom Einschlag haben zusätzlich eine untere Höhengrenze.
- Phase V: jeweils die am längsten nicht benutzte verfügbare Attacke auswählen; Schild-Packen hat weiter Vorrang. Stampfen und Bodenaufreißen stehen auch im Nahkampf zur Verfügung. Kürzere Abklingzeiten für alle waffenlosen Attacken, etwas kürzere Pausen nach Faustkombo, Aufwärtshaken und Bodenaufreißen. Ausholzeiten, Verzögerungen und eigentliche Erholungsanimationen bleiben erhalten.
- Bestehende Balance von v0.12 (weniger Schaden/mehr Ausdauer), Blutspur und großes Labyrinth bleiben erhalten.
- Regressionstests: echte Axtschläge gegen Spieler auf gleicher Höhe, +5,5 und +30 Blöcken; Geometrieprüfung ober-/unterhalb; echte KI muss im Finale innerhalb von 30 Sekunden mindestens fünf unterschiedliche waffenlose Attacken einsetzen.

---
# Blood-Boss v0.12 (10.11.2026): weniger Schaden, längerer Kampf, schwerer Ringkampf

**Status:** v0.12.0 gebaut und in der Modrinth-Instanz installiert (ersetzt 0.11.0); Prüfsummen stimmen überein. JAR zusätzlich in dist/HHA_Blood-0.12.0.jar. Kompletter Server-Bosstest erfolgreich; Clienttest für Phase V, Blutspur, Hinterhalt, tatsächliche Schadensmessung (7,11 statt 9,48 HP), eingehenden Schaden (10 -> 5), erfolgreiche Greif-/Sprungwurf-Sequenz und neue Angriffsanimationen erfolgreich. Screenshots geprüft; Protokolle in blood/build/verification/v0.12.0/.

- Minotaurus (Phasen III-V): ausgehender Trefferschaden x0,75 gegenüber v0.11, eingehender Schaden x0,5 statt x0,8. Wegen Vanillas 1024-HP-Grenze bleibt der Lebensbalken bei 1024; vor Rüstung entsprechen die Reserven jetzt 2048 statt 1280 HP (+60 %). Cerberus bleibt unverändert.
- Waffenloses Finale: verzögerte Hammerfaust (Treffer 14/28/62, Erholung bis 88), lange schwere Stampfkette (18/40/76 statt durch Raserei immer schneller), Bodenwellen bis 36 Blöcke weit. Die Trefferzeitpunkte sind fest und client/server synchron.
- Greif-Anlauf aus 6-13 Blöcken, 24 Ticks Vorbereitung, Richtung wird vor dem letzten Anlauf festgelegt; verfehltes Packen macht ihn 24 Ticks angreifbar. Bei Schilden bleibt Packen die Antwort.
- Bodenaufreißen: beide Hände bei Tick 26 in den Boden, sichtbarer Staub/Vorwarnung, bei Tick 46 hochreißen und eine überspringbare Druckwelle bis 40 Blöcke. Längere Erholung nach Sprung/Bodeneinschlag. Stampfen/Bodenschläge erzeugen physische Druckwellen statt Feuerwellen oder zufälligem Felsregen.
- Blutspur, Hinterhalte, waffenloser Zustand und das große Labyrinth bleiben erhalten.

---
# Blood-Boss v0.11 (09.11.2026): waffenloses Finale, bleibende Blutspur, großer Irrgarten

**Status:** v0.11.0 gebaut und in F:/Modrinth/profiles/1.21.11/mods installiert; 0.10.0 dort ersetzt. Server-Bosstest, voller Client-Kampf (inkl. Blutspur/Hinterhalt in Phase V, Sieg/Belohnungen, Totem/Tod/Reset) und gezielter Kameratest vom Arenarand erfolgreich. Screenshots visuell geprüft. Prüfprotokolle: blood/build/verification/v0.11.0/.

- Phase V: Asterion wirft seine Axt in der Übergangsszene sichtbar zur Seite. Sie bleibt als ungefährliches Modell am Boden; auch nach Überspringen der Szene und im Finale kehrt sie nicht in seine Hand zurück.
- Eigene waffenlose Kampfhaltung und Animationen: Links-/Rechtshaken mit abschließendem Hammerfaustschlag, Aufwärtshaken, doppelter Faustschlag auf den Boden, Packen/Suplex, Schulterrempler, Stampfen und Sprung mit Bodeneinschlag. Keine Axtangriffe, Axtwürfe oder Wirbel in Phase V. Faustkombos haben im Nahkampf Vorrang, feste Trefferzeitpunkte und Erholungsfenster. Eine kurze Schwarzblende setzt ihn vor dem Axtwurf in den freigeräumten Hort, damit auch Treffer am Arenarand oder im Sprung die Kamera nicht verdecken.
- Blutspur bleibt nach dem Labyrinth in Phase V für alle über HHA Ability 2 erhalten (10 s Unsichtbarkeit, 19 s Abklingzeit, Hinterhalt x3 + Taumeln). Der Phasenwechsel setzt den Cooldown nicht zurück. Versteckte Spieler werden auch im Finale nicht als Sichtziel gewählt; Flächentreffer können weiterhin treffen.
- Labyrinth: füllt jetzt fast den gesamten Kampfboden (~76 Blöcke Durchmesser), statt kleiner Innenmaze + 8 offener Randkammern. 229 verbundene Zellen; Raster 4, Korridore 3 breit (Minotaurus 1,9 breit), Mauern 7 hoch. Nur der zentrale 11x11-Hort bleibt offen. Geschlossene Außenkanten, lange verzweigte Wege, echte Sackgassen, nur 1,5 % zusätzliche Verbindungen. Bestehende Säulen/Altar und Türdurchgänge werden bei der Erzeugung berücksichtigt; abgeschnittene Taschen werden nicht benutzt. Spieler starten nach Wegentfernung weit hinten im Irrgarten.
- Prüfungen: 16 feste Maze-Seeds auf Zellzahl, Sackgassen und Weglänge (je 229 Zellen, längste Wege mindestens 156 Blöcke; im Serverlauf 408 Blöcke); auf dem realen Kampfboden alle Korridore mit 3x3x4 Platz vom Hort erreichbar. Client-Test betätigt HHA Ability 2 auch in Phase V und prüft den anschließenden Hinterhalt. Server-Test meldet jeden bewaffneten Angriff in Phase V als Fehler.

---
# Blood-Boss v0.10 (Stand 08.11.2026): echtes Labyrinth mit Schleichen, Berserker, bessere Animationen, langer Weg

Mats: "Animationen verbessern (in und ausserhalb der Cutscenes)", "Weg vom Treppenturm zur Arena laenger/dramatischer",
"vierte Phase ein richtiges Labyrinth, man bekommt Blood Trail, er sieht dich nicht mehr und sagt immer was",
"dritte Phase des Minotaurus (= Stufe 5) brutaler, Berserker wie Godfrey Phase 2".

- **Stufe 4 = Labyrinth** (`boss/PitLabyrinth.kt`): Szene 3 neu - Axthieb, Schwarzblende, der Minotaurus steht im
  Hort in der Mitte, die Spieler werden in ferne Gaenge verteilt (`spreadSpots`, >= 18 Bloecke, 10 auseinander), das
  Labyrinth waechst in Wellen von der Mitte nach aussen (Kamera von oben). Generator: Zellen 5 Bloecke (4 breite Gaenge,
  Mauern 5 hoch), Zellen mit i²+j² <= 33 (bis ~31 Bloecke, innerhalb der Saeulen), offener 3x3-Hort, Recursive
  Backtracker + 13 % Schleifen, 8 Ausgaenge in die 8 Kammern zwischen den Saeulen (radiale Mauern ueber die Saeulen,
  3 davon mit Durchgang), Pfosten-Kappen aus Aderstein (leuchten). ~610 Saeulen, 109 Zellen. `PitHazards.raiseMaze`
  (Wellen per `RisingWall.delay`), steht die ganze Stufe, Ansturm/Wirbel/Sprung brechen durch.
- **Blutspur fuer alle** (auch ohne Blood-Ruestung): HHA-Taste Ability 2 (H) -> eigene HHA-Ability `hha_blood:labyrinth_trail`
  (vor Blood Trail registriert); HHAs eigene Ability-2-Faehigkeiten (Hell Fire Camp) werden in der Stufe ueber
  `HhaEvents.ABILITY_CAST` umgeleitet. 10 s unsichtbar (`TrueInvis` + BloodDive-Abtauchen), Speed I, Abklingzeit 19 s.
  HUD-Zeile zeigt Taste (`@key:`-Platzhalter -> `Text.keybind` im Client), Restzeit, Abklingzeit.
- **Jagd-KI** (`MinotaurEntity.perceive/hunt/tickSniff`): sieht nur sichtbare Spieler (Sichtlinie bis 48, immer < 5.5),
  Versteckte nur wenn er sie anrempelt (< 2.4 -> enttarnt, "Er hat dich ertastet"). Hoert Geraeusche (`PitLabyrinth.listen`:
  Sprinten 13 Bloecke, Gehen 4.5, Landung 6, Schleichen/Stehen nichts; Versteckte +-2 Bloecke ungenau, Hinweis "Er hoert dich").
  Laute Geraeusche > 11 Bloecke: Brüllen + Ansturm durch die Mauern; sonst hingehen. Ohne Spur: wandert zwischen Zellen,
  schnueffelt (SNIFF 26, 40 Ticks) und bekommt eine Ahnung (75 %: Ziel = naechster Spieler +-3). Sprueche ueber
  `Cine.dialog`: hunt.1-10, found.1-4, vanish.1-3, backstab.1-3, heard.1-3, scent.1-4 (Pause zwischen Spruechen).
- **Hinterhalt**: Nahkampftreffer aus dem Blut = x3 (`PitLabyrinth.BACKSTAB`), er taumelt 40 Ticks (dann x1.5). Jeder
  Angriff enttarnt. Bosstest: Hinterhalt 21 Schaden statt 8.
- **Stufe 5 = Berserker** (`berserk`, getrackt): Szene 4 neu - kniet in seinem Labyrinth, nimmt das Hoellenfeuer, Nahaufnahme,
  "Kein Labyrinth mehr. Keine Spiele mehr...", Schrei sprengt das Labyrinth (`PitHazards.collapseWalls`, Szene mit
  `keepWalls`), Augen gluehen rot (Glow-Layer rot pulsierend), roter Dampf von Schultern und Nuestern. Werte: Windups x0.82,
  Pause x0.7, +20 % Tempo, +8 % Schaden, Wut mind. 5, Axt steckt nur 60 % (32 Ticks). Neue Angriffe:
  STOMP 27 (3 Stampfer, Schockringe 7/12.5/18, der dritte nach einem Hopser mit Felssturz), TACKLE 28 (Schulterrempler,
  9 Ticks Sprint, danach evtl. Packen), SLAM 29 (Kriegssprung mit Krater, zwei Ringe, 6 Spalten; in der Raserei doppelt),
  RAGE 30 (Wutschrei: Rueckstoss 10 Bloecke, 10 s volle Wut), SPLIT 31 (Axt durch den Boden ziehen, dann bricht die
  Spalte als Kette von Blutgeysiren auf + 2 Seitenrisse). Packen wird zum Suplex (Sprung mit dem Opfer, 54 Schaden, Ring 13).
  Kombos: Spaltschlag -> Fegen -> Rempler/Stampfen, Hornstoss -> Spaltschlag, Rempler -> Packen, Stampfen -> Sprung.
  Kein Mauern-Hochziehen mehr in Stufe 5.
- **Fix**: Axt war nach einem von einer Cutscene unterbrochenen Wurf fuer immer weg (`axeOut`) -> nur noch Hornstoesse.
  `runScene` gibt ihm die Axt zurueck.
- **Animationen** (Opus-Agent): Minotaurus mit Keyframe-Spuren auf dem Server-Windup (Ausholen -> Treffer genau auf dem
  Schadens-Tick -> Nachschwung -> Erholung), Cross-Fades zwischen Aktionen, echter Gang (Hufe bleiben stehen, Hoehe aus den
  Beinen), Federn fuer Umhang/Lendenschurz/Schwanz/Ohren/Kiefer, Mund bewegt sich beim Sprechen (`CinePlayer.mouth`).
  Cerberus: Trab/Galopp, Pfoten bestimmen Neigung/Hoehe (Bodenhaftung), Koepfe einzeln (Blick, Ohren, Kiefer), Biss mit
  Zerren. Cutscene-Kamera folgt mit kurzer Verzoegerung + leichtem Handkamera-Drift. Szenen 1/2 fluessiger.
  `/hhablood boss mpose <action> [fury] [burning]` (Minotaurus ausserhalb des Kampfs; burning = auch Berserker),
  `/hhablood boss walk`, Gametest-Modi `minotaur`, `walk`. Gemeinsame Helfer `client/boss/BossAnim.kt` (Ease, Spring, Motion).
- **Weg laenger** (Opus-Agent, `hollows/PitApproach.kt`): vom Turmfuss ein ~66 Bloecke langer geschlossener Schlachtweg:
  Gang der Gefesselten -> Halle der Hoerner -> Blutschlund (Knochenbruecke ueber Blutsee) -> grosse Treppe -> Schaedeltor
  (Stierschaedel-Fassade = Tunnel in die Kuppel); Herzschlag wird lauter (Client). Bestehende Welten bekommen ihn ueber
  den Arena-Umbau (neue Signaturen).
- Tests: Bosstest (Labyrinth/Verstecken/Hinterhalt/Hoeren/Berserker-Angriffe geloggt) + Client-Gametest
  (`minotaur,arena,fight`: Posen inkl. Berserker, Weg, Labyrinth von oben, H-Taste versteckt, Totem, Tod) gruen.

---

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
