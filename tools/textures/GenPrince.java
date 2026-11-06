import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Generator fuer den Blutfuersten "Fuerst Sanguin" (Boss Phase 1).
 *
 * Schreibt (deterministisch, keine externen Libs):
 *  - textures/entity/blood_prince.png       128x64 Entity-Skin
 *  - textures/entity/blood_prince_eyes.png  128x64 Leucht-Ebene (nur Augen + Kronenkristall)
 *  - textures/item/blood_offering.png       32x32 Item "Blutopfer"
 *
 * Aufruf (aus dem Projekt-Root): java tools/textures/GenPrince.java [projektRoot]
 *
 * UV-Layout (Standard-Quader-Abwicklung, Offset (u,v), Groesse (w,h,d)):
 *  Kopf (0,0) 8x8x8, Hut (32,0) 8x8x8, Koerper (16,16) 8x12x4, Arme (40,16) 4x12x4,
 *  Beine (0,16) 4x12x4, Kronen-Reif vorne (64,0) 10x3x1, hinten (64,4) 10x3x1,
 *  Seiten (64,8)/(84,8) 1x3x8, Zacken (104/108/112/116,0) 1x4x1, Mittelzacke (120,0) 1x5x1,
 *  Umhang (64,24) 12x22x1 (Aussenseite = BACK-Flaeche), Schulterdornen (96,24)/(104,24) 2x4x2.
 */
public class GenPrince {

    // ---------- Paletten ----------
    static final int SKIN_HI = 0xcdbfba, SKIN = 0xb9aaa6, SKIN_MID = 0x8f7f7c, SKIN_SH = 0x5e4d4f, SKIN_DEEP = 0x3e2f33;
    static final int SOCKET = 0x170a0e, EYE = 0x8a0d14, MOUTH = 0x26090e, BLOOD = 0x8e0c14, BLOOD_HI = 0xc4161f;
    static final int VEIN = 0xa4807f, VEIN_D = 0x7c5a5c;
    static final int HAIR_D = 0x0c0406, HAIR = 0x1a0b0e, HAIR_S = 0x2a0e13, HAIR_HI = 0x3a1218, HAIR_HH = 0x521a22;
    static final int CR_D = 0x1a0306, CR0 = 0x2a0509, CR1 = 0x3a0710, CR2 = 0x4a0a10, CR3 = 0x5c0d15, CR4 = 0x6e1018, CR5 = 0x8a1820;
    static final int BLK = 0x110608, BLK2 = 0x1d0c10, BLK3 = 0x2a1418;
    static final int BONE_HI = 0xf2ead6, BONE = 0xe2d7bd, BONE_M = 0xb9ad92, BONE_SH = 0x7d725d, BONE_D = 0x4f4638, BONE_DD = 0x2e2820;
    static final int CRY_D = 0x6a0810, CRY = 0xc4161f, CRY_HI = 0xff6a55, CRY_W = 0xffcbb8;
    static final int GOLD_HI = 0xd8c690, GOLD = 0xb39a63, GOLD_SH = 0x7a6440;

    static BufferedImage img, eyes;

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : ".";
        String tex = root + "/blood/src/main/resources/assets/hha_blood/textures/";
        img = new BufferedImage(128, 64, BufferedImage.TYPE_INT_ARGB);
        eyes = new BufferedImage(128, 64, BufferedImage.TYPE_INT_ARGB);

        head();
        hat();
        body();
        arms();
        legs();
        crown();
        cape();
        shoulderSpikes();

        new File(tex + "entity").mkdirs();
        ImageIO.write(img, "png", new File(tex + "entity/blood_prince.png"));
        ImageIO.write(eyes, "png", new File(tex + "entity/blood_prince_eyes.png"));
        ImageIO.write(offering(), "png", new File(tex + "item/blood_offering.png"));
        System.out.println("blood_prince.png, blood_prince_eyes.png, blood_offering.png geschrieben");
    }

    // ---------- Grundfunktionen ----------
    static void px(int x, int y, int rgb) { img.setRGB(x, y, 0xff000000 | rgb); }
    static void glow(int x, int y, int rgb) { eyes.setRGB(x, y, 0xff000000 | rgb); }
    static void rect(int x, int y, int w, int h, int rgb) {
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) px(x + i, y + j, rgb);
    }
    static int hash(int x, int y, int s) {
        int h = x * 374761393 + y * 668265263 + s * 982451653;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h ^ (h >>> 16)) & 0x7fffffff;
    }
    static double rnd(int x, int y, int s) { return (hash(x, y, s) % 10000) / 10000.0; }
    static int pick(int[] pal, int i) { return pal[Math.max(0, Math.min(pal.length - 1, i))]; }

    /** Zeichnet eine Zeichen-Karte; Legende siehe col(). '.' = nichts. */
    static void map(int x, int y, String... rows) {
        for (int j = 0; j < rows.length; j++)
            for (int i = 0; i < rows[j].length(); i++) {
                char c = rows[j].charAt(i);
                if (c != '.') px(x + i, y + j, col(c));
            }
    }
    static int col(char c) {
        switch (c) {
            case 'k': return HAIR_D; case 'h': return HAIR; case 's': return HAIR_S; case 'H': return HAIR_HI; case 'G': return HAIR_HH;
            case 'L': return SKIN_HI; case 'S': return SKIN; case 'm': return SKIN_MID; case 'd': return SKIN_SH; case 'D': return SKIN_DEEP;
            case 'o': return SOCKET; case 'e': return EYE; case 'q': return MOUTH; case 'b': return BLOOD; case 'B': return BLOOD_HI;
            case 'v': return VEIN; case 'V': return VEIN_D;
            case 'W': return BONE_HI; case 'O': return BONE; case 'M': return BONE_M; case 'N': return BONE_SH; case 'n': return BONE_D; case 'x': return BONE_DD;
            case 'c': return CRY_D; case 'C': return CRY; case 'R': return CRY_HI; case 'w': return CRY_W;
            case 'g': return GOLD_SH; case 'y': return GOLD; case 'Y': return GOLD_HI;
            case '0': return CR0; case '1': return CR1; case '2': return CR2; case '3': return CR3; case '4': return CR4; case '5': return CR5;
            case 'z': return CR_D; case 'K': return BLK; case 'Z': return BLK2;
        }
        throw new IllegalArgumentException("Farbe " + c);
    }

    // ---------- Haare ----------
    static final int[] HAIR_PAL = {HAIR_D, HAIR, HAIR_S, HAIR_HI, HAIR_HH};
    /** Glatte, senkrechte Straehnen: Grundton je Spalte + Glanzband oben, unten dunkler. */
    static int hairAt(int col, int row, int seed, int sheenRow) {
        int base = 1 + (hash(col, 0, seed) % 3 == 0 ? 1 : 0) - (hash(col, 1, seed) % 4 == 0 ? 1 : 0);
        int v = base;
        if (row == sheenRow || row == sheenRow + 1) v += 1 + (hash(col, row, seed + 7) % 3 == 0 ? 1 : 0);
        if (row >= 6) v -= 1;
        return pick(HAIR_PAL, v);
    }

    // ---------- Kopf (0,0) 8x8x8 ----------
    static void head() {
        // Gesicht (Front) x8..15, y8..15
        map(8, 8,
            "kHGHHsHk",
            "hsLLSmsh",
            "hLLmmSdh",
            "hoemmeoh",
            "hdoLmodh",
            "hSdmdddh",
            "hDqqqbDh",
            "kdSmdBDk");
        // feine rote Adern/Risse
        px(13, 9, VEIN); px(9, 13, VEIN_D); px(14, 13, VEIN_D);
        // Augen-Glut (Leuchtebene): Pupille gluehend, Hoehle schwach
        glow(10, 11, 0xff5a3a); glow(13, 11, 0xff5a3a);
        glow(9, 11, 0x5a0806); glow(14, 11, 0x5a0806);
        glow(10, 12, 0x3a0404); glow(13, 12, 0x3a0404);

        // rechte Seite x0..7 (x7 grenzt ans Gesicht): hinten Haar, vorn Schlaefe/Wange, Straehne an der Kante
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) px(c, 8 + r, hairAt(c, r, 11, 1));
        // spitzes, nach hinten-oben zeigendes Ohr ragt aus dem Haar (Spalten 2-4)
        map(2, 10,
            "mdSSSh",
            ".LdmSh",
            "..mSmh",
            "..dmdh",
            "..DddH",
            "..kDDk");
        px(1, 9, SKIN_SH); px(6, 9, SKIN_MID); px(5, 9, HAIR_S);
        px(6, 12, VEIN_D);
        // linke Seite x16..23 (x16 grenzt ans Gesicht), gespiegelt und dunkler (Schatten)
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) px(16 + c, 8 + r, hairAt(7 - c, r, 23, 1) == HAIR_HH ? HAIR_HI : hairAt(7 - c, r, 23, 1));
        map(16, 10,
            "hmmmDm",
            "hmdDm.",
            "hdmd..",
            "hDdD..",
            "sDDD..",
            "kDDk..");
        px(22, 9, SKIN_SH); px(17, 9, SKIN_SH); px(18, 9, HAIR_S);
        // Hinterkopf x24..31: nur Haar
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) px(24 + c, 8 + r, hairAt(c, r, 37, 2));
        // Oberseite x8..15, y0..7 (y7 = Stirnkante): Haar mit Mittelscheitel
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) {
            int v = (c <= 1 || c >= 6) ? 1 : 2;
            if (c == 2 || c == 5) v = 3;
            if ((r == 2 || r == 5) && (c == 2 || c == 5 || c == 1)) v++;
            if (c == 3 || c == 4) v = (r >= 3 && c == 3) ? -1 : 1;
            px(8 + c, r, v < 0 ? SKIN_DEEP : pick(HAIR_PAL, v));
        }
        // Unterseite x16..23, y0..7: Kinn/Hals im Schatten, Haar an den Raendern
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) {
            int color = (c == 0 || c == 7 || r >= 5) ? HAIR_D : (r == 0 ? SKIN_SH : SKIN_DEEP);
            px(16 + c, r, color);
        }
        px(21, 0, BLOOD); px(21, 1, CRY_D); // Bluttropfen laeuft unters Kinn
    }

    // ---------- Hut-Ebene (32,0): lose Haarstraehnen hinten/seitlich ----------
    static void hat() {
        // Hinterkopf x56..63: haengende Straehnen, unten ausgefranst
        int[] len = {8, 6, 8, 7, 8, 5, 8, 7};
        for (int c = 0; c < 8; c++) for (int r = 2; r < len[c]; r++) {
            if ((c + r) % 3 == 0 && r < 5) continue;
            px(56 + c, 8 + r, hairAt(c + 3, r, 51, 3));
        }
        // rechte Seite x32..39 (hinten = x32): Straehnen hinten, eine lose vorn
        int[] lenR = {8, 7, 8, 6, 0, 0, 0, 7};
        for (int c = 0; c < 8; c++) for (int r = 1; r < lenR[c]; r++) {
            if (c < 4 && (c * 3 + r) % 4 == 1 && r < 6) continue;
            if (c == 7 && r < 3) continue;
            px(32 + c, 8 + r, hairAt(c, r, 61, 2));
        }
        // linke Seite x48..55 (vorn = x48)
        int[] lenL = {7, 0, 0, 0, 6, 8, 7, 8};
        for (int c = 0; c < 8; c++) for (int r = 1; r < lenL[c]; r++) {
            if (c >= 4 && (c * 5 + r) % 4 == 2 && r < 6) continue;
            if (c == 0 && r < 3) continue;
            int h = hairAt(7 - c, r, 71, 2);
            px(48 + c, 8 + r, h == HAIR_HH ? HAIR_HI : h);
        }
        // Oberseite x40..47: ein paar abstehende Straehnen am Hinterkopf (y0 = hinten)
        for (int c = 0; c < 8; c++) if (c != 3 && c != 4) {
            px(40 + c, 0, hairAt(c, 2, 81, 2));
            if (c % 2 == 0) px(40 + c, 1, hairAt(c, 3, 81, 2));
        }
        // Front-Ebene x40..47: Straehnen, die neben dem Gesicht herabfallen
        for (int r = 1; r < 8; r++) {
            if (r != 2) px(40, 8 + r, r < 4 ? HAIR_HI : HAIR_S);
            if (r != 3 && r < 7) px(47, 8 + r, r < 4 ? HAIR_S : HAIR);
        }
    }

    // ---------- Koerper (16,16) 8x12x4: dunkles Wams ----------
    static void body() {
        // Vorderseite x20..27, y20..31
        map(20, 20,
            "4yddddy3",
            "34Kdd K3".replace(' ', 'K'),
            "232KYK21",
            "232KgK21",
            "342KYK21",
            "232KgK21",
            "232KYK21",
            "232KgK21",
            "ZZZOMZZZ",
            "121NnK10",
            "1210K010",
            "0z0z0z0z");
        // Rueckseite x32..39 (unter dem Umhang)
        for (int r = 0; r < 12; r++) for (int c = 0; c < 8; c++) {
            int v = (c == 0 || c == 7) ? 1 : 2;
            if (c == 3 || c == 4) v = 1;
            if (r == 8) { px(32 + c, 20 + r, BLK2); continue; }
            if (r == 11) v = 0;
            px(32 + c, 20 + r, pick(new int[]{CR0, CR1, CR2, CR3}, v - (r > 8 ? 1 : 0)));
        }
        rect(32, 20, 8, 1, CR4);
        // Seiten x16..19 und x28..31
        for (int r = 0; r < 12; r++) for (int c = 0; c < 4; c++) {
            int v = r == 8 ? -1 : (c == 1 || c == 2 ? 2 : 1);
            if (r > 8) v--;
            px(16 + c, 20 + r, v < 0 ? BLK2 : pick(new int[]{CR0, CR1, CR2, CR3}, v + 1));
            px(28 + c, 20 + r, v < 0 ? BLK : pick(new int[]{CR0, CR1, CR2, CR3}, v));
        }
        // Oberseite x20..27, y16..19 (Schultern) / Unterseite x28..35
        for (int r = 0; r < 4; r++) for (int c = 0; c < 8; c++) {
            px(20 + c, 16 + r, (c >= 2 && c <= 5) ? (r == 3 ? CR4 : SKIN_DEEP) : (r == 3 ? CR5 : CR3));
            px(28 + c, 16 + r, (c + r) % 2 == 0 ? CR0 : CR_D);
        }
    }

    // ---------- Arme (40,16) 4x12x4: Aermel, Manschette, bleiche Haende ----------
    static void arms() {
        int[] sleeve = {CR0, CR1, CR2, CR3, CR4};
        // vier Seiten: rechts x40, vorn x44, links x48, hinten x52 (je 4 breit, y20..31)
        for (int f = 0; f < 4; f++) {
            int fx = 40 + f * 4;
            int light = f == 0 ? 1 : f == 1 ? 1 : f == 2 ? -1 : 0; // rechts/vorn heller
            for (int r = 0; r < 8; r++) for (int c = 0; c < 4; c++) {
                int v = 2 + light + (c == 0 ? 0 : c == 3 ? -1 : (c == 1 ? 1 : 0));
                if (r == 0) v -= 1;
                if (r == 6 && c == 1) v += 1; // Falte
                if (r == 3 && c == 2) v -= 1;
                px(fx + c, 20 + r, pick(sleeve, v));
            }
            // Manschette: Knochen/Gold-Saum
            for (int c = 0; c < 4; c++) px(fx + c, 28, light >= 1 ? (c == 0 ? GOLD_HI : GOLD) : (c == 3 ? GOLD_SH : GOLD));
            // Haende, Finger angedeutet (Reihe 30/31)
            for (int c = 0; c < 4; c++) {
                int top = light >= 1 ? (c == 0 ? SKIN_HI : c == 3 ? SKIN_MID : SKIN) : (c == 3 ? SKIN_SH : SKIN_MID);
                px(fx + c, 29, top);
                boolean gap = (f == 1 || f == 3) && c == 2; // Fingerspalt vorn/hinten
                px(fx + c, 30, gap ? SKIN_SH : (light >= 1 ? (c == 3 ? SKIN_MID : SKIN) : (c % 2 == 0 ? SKIN_MID : SKIN_SH)));
                px(fx + c, 31, gap || c == 3 ? SKIN_DEEP : (c % 2 == 0 ? SKIN_SH : SKIN_MID));
            }
            if (f == 1 || f == 3) { px(fx + 1, 29, VEIN); px(fx + 0, 31, BONE_SH); px(fx + 1, 31, BONE_SH); px(fx + 3, 31, BONE_D); } // Knoechel/Krallen
        }
        // Oberseite x44..47 y16..19: Schulter; Unterseite x48..51: Handflaeche/Fingerspitzen
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) {
            px(44 + c, 16 + r, (r == 0 || c == 3) ? CR2 : CR3);
            px(48 + c, 16 + r, (c == 1 || c == 3) ? SKIN_SH : SKIN_DEEP);
        }
    }

    // ---------- Beine (0,16) 4x12x4: dunkle Hose, schwarze Stiefel ----------
    static void legs() {
        for (int f = 0; f < 4; f++) {
            int fx = f * 4;
            int light = f == 1 ? 1 : f == 0 ? 0 : -1;
            for (int r = 0; r < 12; r++) for (int c = 0; c < 4; c++) {
                int color;
                if (r >= 9) {
                    color = r == 11 ? BONE_DD : (c == 0 && light >= 0 ? BLK3 : (c == 3 ? BLK : BLK2));
                    if (r == 9) color = c == 3 ? BLK2 : BLK3;
                } else {
                    int v = 1 + light + (c == 1 ? 1 : c == 3 ? -1 : 0);
                    if (r == 0) v = 0;
                    color = pick(new int[]{CR_D, BLK2, CR0, CR1}, v);
                    if (f == 0 && c == 0 || f == 2 && c == 3) color = CR2; // Seitennaht
                }
                px(fx + c, 20 + r, color);
            }
        }
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) {
            px(4 + c, 16 + r, CR0);
            px(8 + c, 16 + r, (c + r) % 2 == 0 ? BONE_DD : BLK);
        }
    }

    // ---------- Krone ----------
    static void crown() {
        // Reif vorne (64,0) 10x3x1: oben x65..74 y0, unten x75..84 y0, rechts x64 y1..3, vorn x65..74, links x75, hinten x76..85
        band(64, 0, true);
        band(64, 4, false);
        // Seitenreifen (64,8)/(84,8) 1x3x8: oben (u+8,v) 1x8, unten (u+9,v) 1x8, rechts (u,v+8) 8x3, vorn (u+8,v+8) 1x3, links (u+9,v+8) 8x3, hinten (u+17,v+8) 1x3
        for (int k = 0; k < 2; k++) {
            int u = 64 + k * 20, v = 8;
            for (int i = 0; i < 8; i++) { px(u + 8, v + i, BONE_HI); px(u + 9, v + i, BONE_SH); }
            for (int side = 0; side < 2; side++) {
                int sx = side == 0 ? u : u + 9;
                boolean outer = (k == 0) == (side == 0); // rechter Reif: Aussenseite = rechte Flaeche
                for (int i = 0; i < 8; i++) {
                    px(sx + i, v + 8, outer ? BONE : BONE_M);
                    px(sx + i, v + 9, outer ? ((i == 2 || i == 5) ? CRY : (i % 2 == 0 ? BONE_M : BONE)) : BONE_SH);
                    px(sx + i, v + 10, outer ? BONE_SH : BONE_D);
                }
                if (outer) { px(sx + 2, v + 8, CRY_HI); px(sx + 5, v + 8, BONE_HI); px(sx + 5, v + 9, CRY_HI); px(sx + 2, v + 10, CRY_D); px(sx + 5, v + 10, CRY_D); }
            }
            for (int r = 0; r < 3; r++) { px(u + 8, v + 8 + r, r == 0 ? BONE_HI : r == 1 ? BONE_M : BONE_SH); px(u + 17, v + 8 + r, r == 0 ? BONE : r == 1 ? BONE_SH : BONE_D); }
        }
        // Zacken 1x4x1 / Mitte 1x5x1: oben (u+1,0), unten (u+2,0), rechts (u,1), vorn (u+1,1), links (u+2,1), hinten (u+3,1)
        for (int u = 104; u <= 120; u += 4) spike(u, u == 120 ? 5 : 4);
    }

    static void band(int u, int v, boolean front) {
        for (int i = 0; i < 10; i++) { px(u + 1 + i, v, BONE_HI); px(u + 11 + i, v, BONE_SH); }
        // Aussenseite (vorn bei Front-Reif, hinten beim Hinter-Reif) mit Kristallen; Innenseite schlicht
        int outX = front ? u + 1 : u + 12, inX = front ? u + 12 : u + 1;
        map(outX, v + 1,
            "OOWOWWOWOO",
            "MCMNRwNMCM",
            "NcNnCCnNcN");
        if (front) { px(outX + 1, v + 1, BONE_HI); px(outX + 8, v + 1, BONE_HI); }
        for (int i = 0; i < 10; i++) { px(inX + i, v + 1, BONE_M); px(inX + i, v + 2, BONE_SH); px(inX + i, v + 3, BONE_D); }
        if (front) { px(outX + 1, v + 2, CRY_HI); px(outX + 8, v + 2, CRY); glow(outX + 4, v + 2, 0xff5a40); glow(outX + 5, v + 2, 0xd01a14); }
        else { px(outX + 1, v + 2, CRY); px(outX + 8, v + 2, CRY_HI); }
        for (int r = 1; r <= 3; r++) {
            px(u, v + r, r == 1 ? BONE : r == 2 ? BONE_M : BONE_SH);
            px(u + 11, v + r, r == 1 ? BONE_M : r == 2 ? BONE_SH : BONE_D);
        }
    }

    static void spike(int u, int h) {
        px(u + 1, 0, CRY_W);   // Spitze oben
        px(u + 2, 0, BONE_SH); // Unterseite
        for (int r = 0; r < h; r++) {
            int[] fr; // vorn, rechts, links, hinten
            if (r == 0) fr = new int[]{CRY_HI, CRY, CRY_D, CRY};
            else if (r == 1 && h == 5) fr = new int[]{CRY, CRY, CRY_D, CRY_D};
            else if (r == (h == 5 ? 2 : 1)) fr = new int[]{BONE_HI, BONE, BONE_M, BONE_M};
            else if (r == h - 1) fr = new int[]{BONE_M, BONE_SH, BONE_D, BONE_SH};
            else fr = new int[]{BONE, BONE_M, BONE_SH, BONE_M};
            px(u + 1, 1 + r, fr[0]);
            px(u, 1 + r, fr[1]);
            px(u + 2, 1 + r, fr[2]);
            px(u + 3, 1 + r, fr[3]);
        }
    }

    // ---------- Umhang (64,24) 12x22x1 ----------
    // Ausgefranster Saum: Anzahl weggeschnittener Reihen je Spalte der INNEN-(Front-)Flaeche
    static final int[] TATTER = {2, 0, 3, 1, 4, 2, 0, 1, 5, 2, 3, 1};
    static boolean capeCut(int frontCol, int row) {
        if (row >= 22 - TATTER[frontCol]) return true;
        // zwei kleine Risse knapp ueber dem Saum
        if (frontCol == 3 && row == 18) return true;
        if (frontCol == 9 && (row == 16 || row == 17)) return true;
        return false;
    }

    static void cape() {
        int[] velvet = {CR_D, CR0, CR1, CR2, CR3, CR4, CR5};
        int[] fold = {0, 1, 1, 0, -1, 0, 1, 1, 0, -1, 0, 1}; // senkrechte Falten (Aussenseite, Spalte im Bild)
        // Aussenseite = BACK x78..89, y25..46; Bildspalte i entspricht Frontspalte 11-i
        for (int r = 0; r < 22; r++) for (int i = 0; i < 12; i++) {
            if (capeCut(11 - i, r)) continue;
            int v = 4 + fold[i];
            if (r <= 1) v -= 2;            // Kragen-Schatten unter den Schultern
            else if (r <= 3) v -= 1;
            if (r >= 15) v -= 1;           // unten dunkler
            if (i == 0) v -= 1;
            if (i == 11) v -= 2;
            if (fold[i] == 1 && hash(i, r, 5) % 5 == 0) v += 1; // Samtglanz
            int c = pick(velvet, v);
            // Saum-Linie (Knochen/Gold) eine Spalte nach innen und unter dem Kragen
            boolean trim = (i == 1 || i == 10) && r >= 2;
            if (trim) c = (r % 4 == 0) ? GOLD_HI : (i == 1 ? GOLD : GOLD_SH);
            if (r == 2 && i >= 1 && i <= 10) c = i <= 5 ? GOLD : GOLD_SH;
            // ausgefranster Rand: Saum endet etwas vor dem Ende, letzte Reihe ganz dunkel
            if (r + 1 < 22 && capeCut(11 - i, r + 1)) c = trim ? GOLD_SH : CR_D;
            else if (r + 2 < 22 && capeCut(11 - i, r + 2) && !trim) c = pick(velvet, v - 1);
            px(78 + i, 25 + r, c);
        }
        // Kragen-Spange oben: zwei Knochen-Agraffen
        px(80, 25, BONE_M); px(87, 25, BONE_M); px(80, 26, BONE_SH); px(87, 26, BONE_SH);
        // eingestickter Bluttropfen in der Mitte (Gold-Kontur, Blut-Fuellung)
        String[] drop = {
            "....yy....",
            "...yCCy...",
            "...yCCg...",
            "..yRCCCg..",
            "..yRCCCg..",
            ".yRwCCCcg.",
            ".yRCCCCcg.",
            ".yCCCCccg.",
            "..gcccc g.".replace(' ', 'g'),
            "...gggg...",
        };
        for (int j = 0; j < drop.length; j++) for (int i = 0; i < 10; i++) {
            char ch = drop[j].charAt(i);
            if (ch != '.') px(79 + i, 30 + j, col(ch));
        }
        // kleine Dornen-Stickerei links/rechts vom Tropfen und darunter
        px(80, 34, GOLD_SH); px(87, 34, GOLD_SH); px(81, 41, GOLD); px(86, 41, GOLD_SH); px(83, 41, GOLD); px(84, 41, GOLD_SH);
        px(82, 42, CR5); px(85, 42, CR4);

        // Innenseite = FRONT x65..76 (dunkler Samt, schmaler Saum)
        int[] inner = {CR_D, CR0, CR1, CR2};
        for (int r = 0; r < 22; r++) for (int c = 0; c < 12; c++) {
            if (capeCut(c, r)) continue;
            int v = 2 + (fold[11 - c] > 0 ? 0 : -1);
            if (r <= 1 || r >= 16) v -= 1;
            int color = pick(inner, v);
            if ((c == 1 || c == 10) && r >= 2) color = GOLD_SH;
            if (r + 1 < 22 && capeCut(c, r + 1)) color = (c == 1 || c == 10) ? BONE_D : CR_D;
            px(65 + c, 25 + r, color);
        }
        // Kanten: rechts x64 (= Frontspalte 0), links x77 (= Frontspalte 11)
        for (int r = 0; r < 22; r++) {
            if (!capeCut(0, r)) px(64, 25 + r, r % 5 == 2 ? GOLD : GOLD_SH);
            if (!capeCut(11, r)) px(77, 25 + r, r % 5 == 2 ? GOLD_SH : BONE_D);
        }
        // Oberkante x65..76 y24, Unterkante x77..88 y24 (nur unter ungeschnittenen Spalten)
        for (int c = 0; c < 12; c++) {
            px(65 + c, 24, c % 3 == 0 ? CR3 : CR2);
            if (TATTER[c] == 0) px(77 + c, 24, CR_D);
        }
    }

    // ---------- Schulterdornen (96,24)/(104,24) 2x4x2 ----------
    static void shoulderSpikes() {
        for (int u = 96; u <= 104; u += 8) {
            // oben (u+2,24) 2x2: Spitze; unten (u+4,24) 2x2: dunkle Basis
            px(u + 2, 24, BONE_HI); px(u + 3, 24, BONE); px(u + 2, 25, BONE); px(u + 3, 25, BONE_M);
            rect(u + 4, 24, 2, 2, BONE_DD);
            // Seiten je 2x4 ab y26: rechts u, vorn u+2, links u+4, hinten u+6
            for (int f = 0; f < 4; f++) {
                int fx = u + f * 2;
                boolean lit = f == 0 || f == 1;
                String[] rows = lit
                    ? new String[]{"WO", "OM", "MN", "nx"}
                    : new String[]{"OM", "MN", "Nn", "xx"};
                map(fx, 26, rows);
                if (f == 1) px(fx + 1, 28, CRY_D); // Blutfleck am Dorn
            }
        }
    }

    // ================= Item: Blutopfer (32x32) =================
    static BufferedImage offering() {
        int N = 32;
        boolean[][] in = new boolean[N][N];
        double s = 10.4;
        // Herz-Kurve (x^2+y^2-1)^3 - x^2 y^3 <= 0, leicht geneigt
        double tilt = Math.toRadians(-8);
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            int hits = 0;
            for (int sy = 0; sy < 3; sy++) for (int sx = 0; sx < 3; sx++) {
                double X0 = (x + (sx + 0.5) / 3.0 - 16.0) / s;
                double Y0 = -(y + (sy + 0.5) / 3.0 - 15.2) / s;
                double X = X0 * Math.cos(tilt) - Y0 * Math.sin(tilt);
                double Y = X0 * Math.sin(tilt) + Y0 * Math.cos(tilt);
                Y += 0.12;
                double a = X * X + Y * Y - 1;
                if (a * a * a - X * X * Y * Y * Y <= 0) hits++;
            }
            in[x][y] = hits >= 5;
        }
        // Tiefe (Abstand zum Rand) -> Hoehenfeld
        double[][] dist = new double[N][N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            if (!in[x][y]) continue;
            double best = 99;
            for (int yy = 0; yy < N; yy++) for (int xx = 0; xx < N; xx++)
                if (!in[xx][yy]) best = Math.min(best, Math.hypot(xx - x, yy - y));
            dist[x][y] = best;
        }
        // Facetten: Voronoi-Zellen, jede Zelle eine ebene Flaeche
        int[][] seeds = {{9, 8}, {13, 11}, {8, 14}, {12, 18}, {16, 15}, {16, 22}, {20, 10}, {24, 9}, {21, 16}, {24, 14}, {19, 20}, {14, 7}, {6, 10}, {11, 23}};
        int[][] cell = new int[N][N];
        double[][] nxs = new double[seeds.length][3];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            if (!in[x][y]) continue;
            int bi = 0; double bd = 1e9;
            for (int i = 0; i < seeds.length; i++) {
                double d = Math.hypot(seeds[i][0] - x, (seeds[i][1] - y) * 1.1);
                if (d < bd) { bd = d; bi = i; }
            }
            cell[x][y] = bi;
            double hx = h(dist, in, x + 1, y) - h(dist, in, x - 1, y);
            double hy = h(dist, in, x, y + 1) - h(dist, in, x, y - 1);
            nxs[bi][0] += -hx; nxs[bi][1] += -hy; nxs[bi][2] += 1.2;
        }
        int[] red = {0x1c0306, 0x3a0710, 0x5c0a12, 0x86101a, 0xa8141e, 0xc81e24, 0xe8453a, 0xff7a62, 0xffc4b0};
        double[] L = norm(-0.55, -0.7, 0.75);
        int[][] out = new int[N][N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            if (!in[x][y]) continue;
            double[] n = norm(nxs[cell[x][y]][0], nxs[cell[x][y]][1], nxs[cell[x][y]][2]);
            double d = n[0] * L[0] + n[1] * L[1] + n[2] * L[2];
            // innere Glut: Mitte heller (Kristall leuchtet von innen)
            double core = Math.max(0, 1 - Math.hypot(x - 15, y - 15) / 9.0);
            double v = Math.min(6.4, 1.7 + d * 4.6 + core * 1.4);
            // Facettenkanten: Grenze zur hellen Nachbarzelle als Lichtkante
            boolean edge = (x + 1 < N && in[x + 1][y] && cell[x + 1][y] != cell[x][y]) || (y + 1 < N && in[x][y + 1] && cell[x][y + 1] != cell[x][y]);
            if (edge) v += 0.9;
            out[x][y] = 0xff000000 | pick(red, (int) Math.round(v));
        }
        // Mittelkerbe zwischen den Kammern
        // Glanzlichter auf der linken Kammer
        put(out, in, 9, 9, 0xffffc4b0); put(out, in, 10, 8, 0xffff7a62); put(out, in, 8, 10, 0xffff7a62);
        put(out, in, 10, 10, 0xffe8453a);
        put(out, in, 20, 9, 0xffff7a62);
        put(out, in, 13, 18, 0xffe8453a);

        // Dornige Adern, die das Herz umschlingen (duerfen ueber den Rand ragen)
        int VD = 0xff160407, VM = 0xff2a0a10, VH = 0xffb83a34, TH = 0xffe2d7bd, THS = 0xffb9ad92;
        boolean[][] vine = new boolean[N][N];
        // Ader 1: von links oben nach rechts unten (welliger Bogen)
        for (int x = 3; x <= 27; x++) {
            int y = (int) Math.round(10.5 + (x - 3) * 0.55 + Math.sin(x * 0.55) * 1.6);
            vine[x][y] = true;
        }
        // Ader 2: von links unten nach rechts oben
        for (int x = 7; x <= 28; x++) {
            int y = (int) Math.round(21.0 - (x - 7) * 0.62 + Math.sin(x * 0.7 + 1) * 1.1);
            vine[x][y] = true;
        }
        // Luecken schliessen (diagonale Spruenge)
        boolean[][] v2 = new boolean[N][N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            v2[x][y] |= vine[x][y];
            if (vine[x][y] && x + 1 < N) {
                for (int dy = -3; dy <= 3; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= N || !vine[x + 1][yy] || Math.abs(dy) < 2) continue;
                    int step = dy > 0 ? 1 : -1;
                    for (int k = step; Math.abs(k) < Math.abs(dy); k += step) v2[x][y + k] = true;
                }
            }
        }
        vine = v2;
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            if (!vine[x][y]) continue;
            boolean onHeart = in[x][y];
            boolean near = onHeart || nearIn(in, x, y, 2);
            if (!near) continue;
            out[x][y] = onHeart ? VM : VD;
            if (y - 1 >= 0 && !vine[x][y - 1] && in[x][y - 1]) out[x][y - 1] = VH;
            if (y + 1 < N && !vine[x][y + 1] && in[x][y + 1]) out[x][y + 1] = darker(out[x][y + 1]);
        }
        // Dornen
        int[][] thorns = {{6, 10, 0, -1}, {11, 13, 0, -1}, {18, 13, 1, -1}, {23, 18, 0, -1}, {27, 18, 1, 0},
                          {10, 19, 0, 1}, {15, 16, 0, 1}, {21, 13, 1, 1}, {27, 7, 1, -1}, {4, 11, -1, -1}, {8, 21, -1, 1}};
        for (int[] t : thorns) {
            int bx = t[0], by = t[1];
            // naechsten Aderpunkt in derselben Spalte suchen
            int vy = -1;
            for (int d = 0; d < 4 && vy < 0; d++) {
                if (by + d < N && vine[bx][by + d]) vy = by + d;
                else if (by - d >= 0 && vine[bx][by - d]) vy = by - d;
            }
            if (vy < 0 || !nearIn(in, bx, vy, 2)) continue;
            int tx = bx + t[2], ty = vy + t[3];
            if (tx < 0 || ty < 0 || tx >= N || ty >= N) continue;
            // Dorn: dunkler Ansatz, knochenbleiche Spitze
            out[tx][ty] = VD;
            int tx2 = tx + t[2], ty2 = ty + (t[3] == 0 ? 0 : t[3]);
            if (t[2] == 0) ty2 = ty + t[3];
            if (tx2 >= 0 && ty2 >= 0 && tx2 < N && ty2 < N && !(tx2 == bx && ty2 == vy)) out[tx2][ty2] = in[tx2][ty2] ? VM : VD;
        }
        // Aorta-Stummel oben zwischen den Kammern (Ader, die aus dem Herz waechst)
        int[][] stub = {{16, 5}, {16, 4}, {17, 3}, {17, 2}, {15, 4}};
        for (int[] p : stub) if (out[p[0]][p[1]] == 0) out[p[0]][p[1]] = p[1] <= 3 ? VM : VD;
        out[18][2] = 0xffa8141e; out[18][3] = 0xff5c0a12; // offenes Ende, Blut
        out[17][1] = VD;

        // Bluttropfen: von der Spitze und von einer Ader
        dripCol(out, findBottom(in, 15) , 15, 4);
        dripCol(out, 24, 22, 2);
        dripCol(out, 22, 9, 1);

        // Kontur: Randpixel dunkel (oben links etwas heller)
        int[][] res = new int[N][N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) res[x][y] = out[x][y];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            if (out[x][y] == 0) continue;
            boolean edgeL = x == 0 || out[x - 1][y] == 0, edgeR = x == N - 1 || out[x + 1][y] == 0;
            boolean edgeU = y == 0 || out[x][y - 1] == 0, edgeD = y == N - 1 || out[x][y + 1] == 0;
            if (!(edgeL || edgeR || edgeU || edgeD)) continue;
            int c = out[x][y];
            if (c == TH || c == THS) { res[x][y] = THS; if (edgeD || edgeR) res[x][y] = 0xff4f4638; continue; }
            res[x][y] = (edgeD || edgeR) ? 0xff16020a : 0xff3a0710;
        }
        BufferedImage im = new BufferedImage(N, N, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) im.setRGB(x, y, res[x][y]);
        return im;
    }

    static int findBottom(boolean[][] in, int x) {
        for (int y = in.length - 1; y >= 0; y--) if (in[x][y]) return y;
        return 0;
    }
    /** Tropfen-Spur unter (x, y): duenne Spur, unten ein dicker Tropfen. */
    static void dripCol(int[][] out, int y0, int x, int len) {
        int D = 0xff86101a, M = 0xffc81e24, H = 0xffff7a62;
        for (int k = 1; k <= len; k++) if (y0 + k < 32) out[x][y0 + k] = k == 1 ? D : M;
        int yb = y0 + len + 1;
        if (yb < 32) { out[x][yb] = M; if (x + 1 < 32) out[x + 1][yb] = D; }
        if (yb + 1 < 32) { out[x][yb + 1] = D; if (x + 1 < 32) out[x + 1][yb + 1] = D; }
        if (yb < 32 && len >= 2) out[x][yb] = H;
    }
    static boolean nearIn(boolean[][] in, int x, int y, int r) {
        for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) {
            int xx = x + dx, yy = y + dy;
            if (xx >= 0 && yy >= 0 && xx < in.length && yy < in.length && in[xx][yy]) return true;
        }
        return false;
    }
    static void put(int[][] o, boolean[][] in, int x, int y, int c) { if (in[x][y]) o[x][y] = c; }
    static double h(double[][] d, boolean[][] in, int x, int y) {
        if (x < 0 || y < 0 || x >= d.length || y >= d.length || !in[x][y]) return 0;
        return Math.sqrt(d[x][y]) * 1.6;
    }
    static double[] norm(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / l, y / l, z / l};
    }
    static int mix(int a, int b) {
        int r = (((a >> 16) & 255) + ((b >> 16) & 255)) / 2, g = (((a >> 8) & 255) + ((b >> 8) & 255)) / 2, bb = ((a & 255) + (b & 255)) / 2;
        return 0xff000000 | r << 16 | g << 8 | bb;
    }
    static int darker(int a) {
        int r = ((a >> 16) & 255) * 7 / 10, g = ((a >> 8) & 255) * 7 / 10, b = (a & 255) * 7 / 10;
        return 0xff000000 | r << 16 | g << 8 | b;
    }
}
