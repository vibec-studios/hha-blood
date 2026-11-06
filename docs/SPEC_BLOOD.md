# Blood's Armor - Spezifikation (Mats' ursprüngliche Prompts, wörtlich)

Hinweis: Dies sind die "damaligen Prompts" des Nutzers. Umsetzungsstand siehe `CLAUDE.md` (Blood ist umgesetzt, Hook wurde durch Cyclone ersetzt).

## Grundkonzept

🩸 **BLOOD'S ARMOR**

🪖 **Helm**
- Regeneration I
- **Blood Tracker** - Spawnt Blutpartikel in Richtung nahestehender Spieler, nur für dich sichtbar. Gegner können sich nicht verstecken. *(Inspiriert vom Bloodlust Sword im Altar SMP - ab 2 Kills freigeschaltet)*

🛡️ **Brustplatte**
- Fire Resistance
- **Enduring Strength** - Strength-Effekte dauern drastisch länger als normal: Strength II = 8 Minuten, Strength I = 16 Minuten. *(Direkt übernommen vom Heretic Gem im Bliss SMP)*

👖 **Leggings**
- **Infection** *(Aktiv)* - 15% Chance bei jedem Treffer den Gegner bluten zu lassen (DoT). 15s Cooldown. *(Bloodlust Sword, Altar SMP - ab 0 Kills)*
- **Blood Trail** *(Aktiv)* - Tauche dich in eine Blutlache ein: kurze Invisibility + Blutpartikel-AoE um dich. 60s Cooldown. *(Bloodlust Sword, Altar SMP - ab 3 Kills)*

👢 **Boots**
- Fall Damage Immunity
- Speed I
- **Blood Rush** - Wenn du einen blutenden Gegner triffst, bekommst du für 3s Speed II.

⚡ **Set Bonus - Nihil**
- Alle Gegner in 15 Blöcken werden für 3s gestunnt während du ihr Blut absaugst (HP-Drain -> Lifesteal). Nach 3s: Blut-Nova die alle Gegner bluten lässt. Blutige Ringe breiten sich nach außen aus. *(Inspiriert von der Phase-2-Transition von Mohg, Lord of Blood aus Elden Ring - "Nihil!")*

⚔️ **BLOOD SWORD** *(Legendary)*
- 9 Basis-Schaden (4.5 Herzen)
- **Passiv - Hemorrhage:** Blutende Gegner nehmen doppelten Schaden von allen Treffern. Macht Infection extrem wertvoll. *(Heretic Gem, Bliss SMP)*
- **Aktiv - Bloodsaws:** Schieße 2 breite Blut-Projektile die abprallen und durch Entities fliegen. Treffen beide gleichzeitig: Blood Fountain spawnt und belegt alle Gegner in der AoE mit Blutung + verstärktem Schaden. *(Heretic Gem, Bliss SMP)*

🔨 **BLOOD MACE** *(Legendary)*
- **Aktiv - Blood Hook:** Wirf eine Blutkette in Blickrichtung - alles was sie berührt wird zu dir rangezogen. 30s Cooldown. *(Bloodlust Sword, Altar SMP - ab 5 Kills)*  -> **ersetzt durch Blood Cyclone (s. u.)**

## Änderungswünsche (spätere Runde)

Helm:
* Bessere Partikel/Effekte für die Trail anstatt nur Herzen

Brustplatte:
* Besser wäre Enduring Effects, also dass alle Effekte länger gehen außer Turtle Master

Leggings:
* Ein besonderer Sound und ein Effekt wenn der Gegner blutet.
* Blood Trail: Wäre besser, wenn die Gegner Slowness 6 kriegen und ein bisschen mehr Schaden macht

Set Bonus: Bessere Effekte

Blood Sword: Aktiv: Bessere Effekte

Blood Mace:
* Komplett neue Fähigkeit: Du schleuderst den Mace in die Luft, er rotiert und schießt Blut-Projektile in alle Richtungen die Gegner in 10 Blöcken treffen und bluten lassen (Infection auslösen). Du fängst ihn danach wieder auf. 40s Cooldown. Technisch: Spawn eines rotierenden Projektils mit mehreren Sub-Projektilen die radial nach außen geschossen werden, jeder Treffer triggert den Blutungs-Custom-Tag auf dem getroffenen Entity.

Allgemein: Bitte verbessere Visuals immens.

## Design

* Blood Armor: Dunkles/schwarzes Netherit mit Rib Trims in dunkelrot/crimson. Rote Tropfen-Partikel die langsam nach unten fallen - als würde die Rüstung leicht bluten.
* Blood Sword: Schwarze Klinge mit dunkelroten Akzenten. Rote Partikel die von der Klinge tropfen, crimson-farbener Griff.
* Blood Mace: Schwarzer Mace-Kopf mit dunkelroten Akzenten. Rote Tropfen-Partikel um den Kopf, dunkler Stiel mit crimson Akzenten.

## Umsetzungsstand (Abweichungen/Details)
- Enduring Effects: Faktor x5 (konfigurierbar) statt fester Strength-Zeiten.
- Cyclone-Cooldown 800 Ticks, Reichweite 10, Schaden 5 pro Bolzen + Infection.
- Blood Trail: Schaden 6, Slowness VI (Verstärker 5) 80 Ticks, Radius 5.
- Tasten: R = Nihil (Ability 4), V = Blood Trail (Ability 5).
- Offen: Tropfen an Schwert/Mace (Item-Partikel) sind noch nicht umgesetzt (nur die Rüstung "blutet" per Partikel).
