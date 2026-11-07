import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Generator for the Crimson Minotaur "Der Schlaechter" (boss), deterministic, JDK only.
 * Single source of truth for geometry, UVs and textures.
 *
 * Writes (relative to the project root):
 *  - blood/src/main/resources/assets/hha_blood/textures/entity/minotaur.png        512x512 skin
 *  - blood/src/main/resources/assets/hha_blood/textures/entity/minotaur_glow.png   512x512 emissive layer (RenderLayers.eyes)
 *  - blood/src/main/kotlin/dev/henny/hha_blood/client/boss/MinotaurModelData.kt    TexturedModelData (generated)
 *  - blood/src/main/resources/assets/hha_blood/models/item/minotaur_axe.json       3D item model of the axe
 *  - blood/src/main/resources/assets/hha_blood/textures/item/minotaur_axe.png      128x128 item texture
 *  - blood/src/main/resources/assets/hha_blood/items/minotaur_axe.json             item definition
 *
 * Usage: java tools/textures/GenMinotaur.java [projectRoot] [--preview <outDir>]
 *
 * Model space: half pixels (32 units = 1 block), +Y down, ground (hoof soles) at y = 24, the creature faces -Z,
 * its right side is -X. Bones (listed in the coordinate contract) have no rest rotation; tilted decorative pieces are
 * extra child parts. Every cuboid gets its own UV block (no mirroring); the texture is painted from the 3D surface
 * position of every face cell, so patterns line up across faces.
 */
public class GenMinotaur {
    static final int TW = 512, TH = 512;
    static final int[] base = new int[TW * TH], glow = new int[TW * TH], owner = new int[TW * TH];

    // ---------------- palettes (dark -> light) ----------------
    /** blackened steel */
    static final int[] ST = {0x060608, 0x0b0b0f, 0x111116, 0x18171e, 0x1f1e26, 0x27262f, 0x312f39, 0x3c3945, 0x4a4652, 0x5c5766, 0x736d7e, 0x8f889a};
    /** charcoal fur */
    static final int[] FU = {0x050506, 0x0a090b, 0x100f11, 0x171517, 0x1e1b1e, 0x262225, 0x2f2a2e, 0x3a3438, 0x464044};
    /** dark hide / muzzle skin */
    static final int[] SK = {0x090708, 0x100d0e, 0x171314, 0x1f1a1b, 0x282223, 0x322a2b, 0x3d3334, 0x4a3f40, 0x5a4d4e};
    /** horn, dark grey-black */
    static final int[] HO = {0x09090b, 0x111013, 0x19171b, 0x221f24, 0x2c282e, 0x37323a, 0x443e47, 0x544c57, 0x675d69};
    /** crimson blood metal */
    static final int[] CR = {0x1a0204, 0x2c0407, 0x3a0508, 0x52080c, 0x6c0c12, 0x8a1016, 0xa8141a, 0xc0141c, 0xd8302a, 0xf0603e, 0xff9a70};
    /** dried blood */
    static final int[] DR = {0x170203, 0x220305, 0x300407, 0x42060a, 0x58090e, 0x721014, 0x901a1a};
    /** bone (teeth) */
    static final int[] BO = {0x3a3226, 0x5a4f3d, 0x7a6d56, 0x9a8c72, 0xb8aa8c, 0xd8ccb0, 0xeee6d2};
    /** dark gold (nose ring) */
    static final int[] GO = {0x1e1206, 0x34200a, 0x4e3210, 0x6a4818, 0x8a6420, 0xa98030, 0xc8a048, 0xe8c870};
    /** cape cloth, outside */
    static final int[] CL = {0x050405, 0x0a0708, 0x100b0d, 0x161012, 0x1d1417, 0x25191d, 0x2e1f24};
    /** dark red cloth (cape lining, loincloth, inner ear) */
    static final int[] LN = {0x170306, 0x220509, 0x2f070c, 0x3e0a10, 0x500d14, 0x641219, 0x7a1a1e};
    /** red leather */
    static final int[] LE = {0x150304, 0x220507, 0x32080a, 0x440b0e, 0x581013, 0x6e1619, 0x861e1f};
    /** hoof keratin */
    static final int[] HF = {0x050506, 0x0a0a0b, 0x111013, 0x18171a, 0x211f23, 0x2b282d, 0x37333a, 0x46414a};
    /** glow */
    static final int[] GL = {0x4a0505, 0x7a0a08, 0xb0160c, 0xe82a14, 0xff5a28, 0xff9a50, 0xffd0a0, 0xfff4e0};

    enum F { TOP, BOTTOM, RIGHT, FRONT, LEFT, BACK }

    /** One cuboid's UV block. TOP = -Y face (visual top), FRONT = -Z face (towards the snout), RIGHT = -X, LEFT = +X. */
    record Box(int u, int v, int w, int h, int d) {
        /** texture pixel of the surface cell (x,y,z) (cell indices inside the cuboid, z = 0 at the front). */
        int[] px(F f, int x, int y, int z) {
            return switch (f) {
                case TOP -> new int[]{u + d + x, v + (d - 1 - z)};
                case BOTTOM -> new int[]{u + d + w + x, v + (d - 1 - z)};
                case RIGHT -> new int[]{u + (d - 1 - z), v + d + y};
                case FRONT -> new int[]{u + d + x, v + d + y};
                case LEFT -> new int[]{u + d + w + z, v + d + y};
                case BACK -> new int[]{u + 2 * d + w + (w - 1 - x), v + d + y};
            };
        }

        int[] origin(F f) {
            return switch (f) {
                case TOP -> new int[]{u + d, v};
                case BOTTOM -> new int[]{u + d + w, v};
                case RIGHT -> new int[]{u, v + d};
                case FRONT -> new int[]{u + d, v + d};
                case LEFT -> new int[]{u + d + w, v + d};
                case BACK -> new int[]{u + 2 * d + w, v + d};
            };
        }

        int[] size(F f) {
            return switch (f) {
                case TOP, BOTTOM -> new int[]{w, d};
                case RIGHT, LEFT -> new int[]{d, h};
                case FRONT, BACK -> new int[]{w, h};
            };
        }
    }

    interface Cell { void at(int x, int y, int z); }

    static void forFace(Box b, F f, Cell c) {
        switch (f) {
            case TOP, BOTTOM -> { for (int z = 0; z < b.d; z++) for (int x = 0; x < b.w; x++) c.at(x, f == F.TOP ? 0 : b.h - 1, z); }
            case RIGHT, LEFT -> { for (int z = 0; z < b.d; z++) for (int y = 0; y < b.h; y++) c.at(f == F.RIGHT ? 0 : b.w - 1, y, z); }
            case FRONT, BACK -> { for (int y = 0; y < b.h; y++) for (int x = 0; x < b.w; x++) c.at(x, y, f == F.FRONT ? 0 : b.d - 1); }
        }
    }

    // =====================================================================================
    // geometry
    enum M { FUR, MANE, TUFT, HEADFUR, SKIN, NOSE, JAW, EAR, FIST, HORN, BROW, SPIKE, GOLD, STEEL, PLATE, LAMES, BELT, CLOTH_RED, CAPE, SHAFT, WRAP, SOCKET, BLADE, EDGE, HOOF }

    static final class Part {
        final String name;
        final Part parent;
        final boolean bone;
        double ox, oy, oz, pitch, yaw, roll; // relative origin + fixed rotation
        double ax, ay, az; // absolute pivot (bones only)
        final List<Cube> cubes = new ArrayList<>();
        final List<Part> kids = new ArrayList<>();

        Part(String n, Part p, boolean b) { name = n; parent = p; bone = b; }
    }

    static final class Cube {
        Part part;
        double x, y, z;
        int w, h, d;
        M mat;
        String tag = "";
        boolean rag;
        double t0 = 0, t1 = 1;
        int axis = -1; // spikes/horns: 0 = tip at -Y, 1 = tip at +X, 2 = tip at -X
        final EnumMap<F, M> fm = new EnumMap<>(F.class);
        int u = -1, v = -1, idx;

        Box box() { return new Box(u, v, w, h, d); }
        Cube f(F face, M m) { fm.put(face, m); return this; }
        Cube tag(String t) { tag = t; return this; }
        Cube rag() { rag = true; return this; }
        Cube t(double a, double b, int ax) { t0 = a; t1 = b; axis = ax; return this; }
        M mat(F face) { return fm.getOrDefault(face, mat); }
    }

    static final List<Part> PARTS = new ArrayList<>();
    static final List<Cube> CUBES = new ArrayList<>();
    static final Part ROOT = new Part("root", null, true);
    static final Map<String, Part> BY_NAME = new HashMap<>();

    static Part bone(String n, Part parent, double ax, double ay, double az) {
        if (!parent.bone) throw new IllegalStateException("bone " + n + " under decorative part");
        Part p = new Part(n, parent, true);
        p.ax = ax; p.ay = ay; p.az = az;
        p.ox = ax - parent.ax; p.oy = ay - parent.ay; p.oz = az - parent.az;
        return reg(p);
    }

    static Part deco(String n, Part parent, double ox, double oy, double oz, double pitch, double yaw, double roll) {
        Part p = new Part(n, parent, false);
        p.ox = ox; p.oy = oy; p.oz = oz; p.pitch = pitch; p.yaw = yaw; p.roll = roll;
        return reg(p);
    }

    static Part reg(Part p) {
        if (BY_NAME.put(p.name, p) != null) throw new IllegalStateException("duplicate part " + p.name);
        p.parent.kids.add(p);
        PARTS.add(p);
        return p;
    }

    /** cuboid in absolute model coordinates (only for bones, whose chain is translation-only in rest pose) */
    static Cube A(Part p, double x, double y, double z, int w, int h, int d, M m) {
        if (!p.bone) throw new IllegalStateException("absolute cube on decorative part " + p.name);
        return L(p, x - p.ax, y - p.ay, z - p.az, w, h, d, m);
    }

    static Cube L(Part p, double x, double y, double z, int w, int h, int d, M m) {
        Cube c = new Cube();
        c.part = p; c.x = x; c.y = y; c.z = z; c.w = w; c.h = h; c.d = d; c.mat = m;
        c.idx = CUBES.size();
        p.cubes.add(c);
        CUBES.add(c);
        return c;
    }

    /** min x of a box given for the left (+X) side, mirrored to the right side for s = -1 */
    static double X(double x, int w, int s) { return s > 0 ? x : -(x + w); }

    static void build() {
        Part body = bone("body", ROOT, 0, -20, 0);
        A(body, -17, -20, -9, 34, 8, 18, M.FUR).tag("pelvis");
        A(body, -19, -27, -11, 38, 8, 22, M.BELT).tag("belt");
        A(body, -6, -28.5, -13, 12, 11, 2, M.PLATE).tag("buckle");

        Part lf = bone("loin_front", body, 0, -16, -8);
        A(lf, -8, -19, -11.5, 16, 25, 1, M.CLOTH_RED).rag().tag("loincloth");
        A(lf, -10, -19.5, -13.5, 20, 10, 2, M.PLATE).tag("tasset");
        A(lf, -8, -10.5, -13, 16, 11, 2, M.PLATE).tag("tasset");
        Part lb = bone("loin_back", body, 0, -16, 8);
        A(lb, -8, -19, 9.5, 16, 25, 1, M.CLOTH_RED).rag().tag("loincloth");
        A(lb, -10, -19.5, 10.5, 20, 10, 2, M.PLATE).tag("tasset");
        A(lb, -8, -10.5, 10, 16, 11, 2, M.PLATE).tag("tasset");

        Part torso = bone("torso", body, 0, -24, 0);
        A(torso, -16, -42, -10, 32, 18, 20, M.LAMES).f(F.BACK, M.FUR).f(F.BOTTOM, M.FUR).tag("abdomen");
        A(torso, -22, -62, -12, 44, 22, 24, M.FUR).f(F.FRONT, M.LAMES).f(F.BOTTOM, M.STEEL).tag("chest");
        A(torso, -18, -61, -14, 36, 18, 2, M.PLATE).tag("breast");
        A(torso, -12, -70, -9, 24, 12, 18, M.MANE).tag("neck");
        A(torso, -19, -66, 5, 38, 7, 11, M.MANE).tag("collar");
        for (int i = 0; i < 3; i++) {
            double y = -55 + i * 8;
            Part sp = deco("spine_spike" + i, torso, 0, y + 24, 11.5, -0.95, 0, 0);
            int bh = 6 - i, th = 7 - i;
            L(sp, -2, -bh, -2, 4, bh, 4, M.SPIKE).t(0, bh / (double) (bh + th), 0);
            L(sp, -1, -bh - th, -1, 2, th, 2, M.SPIKE).t(bh / (double) (bh + th), 1, 0);
        }

        Part head = bone("head", torso, 0, -64, -6);
        A(head, -10, -84, -14, 20, 18, 16, M.HEADFUR).tag("cranium");
        A(head, -11, -81, -16, 22, 4, 4, M.BROW).tag("brow");
        A(head, -7, -73, -27, 14, 11, 13, M.SKIN).tag("muzzle");
        A(head, -8, -71, -31, 16, 9, 5, M.NOSE).tag("nose");
        A(head, -8, -66, -14, 16, 9, 11, M.FUR).tag("throat");
        A(head, -9, -83, 2, 18, 22, 5, M.MANE).tag("mane");
        A(head, 2, -63, -30, 2, 6, 2, M.GOLD).tag("ring");
        A(head, -4, -63, -30, 2, 6, 2, M.GOLD).tag("ring");
        A(head, -4, -58, -30, 8, 2, 2, M.GOLD).tag("ring");
        for (int s : new int[]{-1, 1}) {
            String sd = s < 0 ? "right" : "left";
            Part ear = deco("ear_" + sd, head, 9.5 * s, -13, 1, 0, 0.25 * s, 0.5 * s);
            L(ear, X(0, 7, s), -2, -1.5, 7, 4, 3, M.EAR).tag("ear");
            // horn: out, then up, then forward. segment k grows along +X (left) / -X (right)
            double[] len = {12, 10, 9, 8, 7};
            int[] th = {7, 6, 5, 4, 2};
            double[] roll = {-0.22, -0.42, -0.5, -0.42, -0.3};
            double[] yaw = {0.12, 0.08, 0.3, 0.42, 0.4};
            double total = 0;
            for (double l : len) total += l - 1.5;
            Part prev = head;
            double acc = 0;
            for (int k = 0; k < len.length; k++) {
                double ox = k == 0 ? 9 * s : (len[k - 1] - 1.5) * s;
                double oy = k == 0 ? -17 : 0, oz = k == 0 ? -2 : 0;
                Part hp = deco("horn_" + sd + k, prev, ox, oy, oz, 0, yaw[k] * s, roll[k] * s);
                double tt = th[k];
                L(hp, X(0, (int) len[k], s), -tt / 2, -tt / 2, (int) len[k], th[k], th[k], M.HORN)
                        .t(acc / total, (acc + len[k]) / total, s > 0 ? 1 : 2).tag("horn");
                acc += len[k] - 1.5;
                prev = hp;
            }
        }
        double[] cx = {-6, -3, 0, 3, 6};
        int[] cw = {2, 3, 3, 3, 2}, ch = {6, 8, 10, 8, 6};
        for (int i = 0; i < 5; i++) {
            Part cp = deco("crown" + i, head, cx[i], -19.5, -3.5, 0.18, 0, cx[i] * 0.07);
            L(cp, -cw[i] / 2.0, -ch[i], -cw[i] / 2.0, cw[i], ch[i], cw[i], M.SPIKE).t(0, 1, 0).tag("crown");
        }
        Part jaw = bone("jaw", head, 0, -56, -16);
        A(jaw, -6, -62, -27, 12, 6, 12, M.JAW).tag("jaw");
        A(jaw, -4, -56.5, -25, 8, 3, 6, M.TUFT).tag("beard");

        for (int s : new int[]{-1, 1}) {
            String sd = s < 0 ? "right" : "left";
            Part arm = bone("arm_" + sd, torso, 26 * s, -58, 0);
            A(arm, X(19, 14, s), -60, -7, 14, 26, 14, M.FUR).tag("upperarm");
            A(arm, X(16, 21, s), -68, -11, 21, 14, 22, M.PLATE).tag("pauldron");
            A(arm, X(18, 20, s), -55, -10, 20, 6, 20, M.PLATE).tag("lame");
            twoSpike(arm, "pspikeA_" + sd, 24 * s - 26 * s, -67 + 58, -2, 0.12, 0, 0.32 * s, 4, 7, 2, 8);
            twoSpike(arm, "pspikeB_" + sd, 32 * s - 26 * s, -65 + 58, 5, -0.35, 0, 0.8 * s, 4, 5, 2, 6);
            Part pc = deco("pspikeC_" + sd, arm, 32 * s - 26 * s, -65 + 58, -6, 0.35, 0, 0.8 * s);
            L(pc, -1, -7, -1, 2, 7, 2, M.SPIKE).t(0.2, 1, 0);

            Part fa = bone("forearm_" + sd, arm, 26 * s, -34, 0);
            A(fa, X(20, 12, s), -34, -6, 12, 22, 12, M.FUR).tag("forearm");
            A(fa, X(19.5, 13, s), -30, -6.5, 13, 16, 13, M.PLATE).tag("bracer");
            for (int i = 0; i < 2; i++) {
                Part fn = deco("fin_" + sd + i, fa, 6 * s, (i == 0 ? -26 : -19) + 34, 0, 0, 0, -0.55 * s);
                L(fn, X(0, 6, s), -1, -1, 6, 2, 2, M.SPIKE).t(0.15, 1, s > 0 ? 1 : 2);
            }
            A(fa, X(19.5, 13, s), -12.5, -6.5, 13, 9, 13, M.FIST).tag("fist");
            if (s < 0) buildAxe(bone("axe", fa, -26, -8, -2));
        }

        for (int s : new int[]{-1, 1}) {
            String sd = s < 0 ? "right" : "left";
            Part leg = bone("leg_" + sd, body, 12 * s, -16, 0);
            A(leg, X(3, 18, s), -18, -9, 18, 23, 18, M.FUR).tag("thigh");
            A(leg, X(2, 20, s), -16, -10.5, 20, 14, 10, M.PLATE).tag("cuisse");
            Part shin = bone("shin_" + sd, leg, 12 * s, 4, 2);
            A(shin, X(6, 12, s), 4, -4, 12, 15, 12, M.FUR).tag("shin");
            A(shin, X(5, 14, s), 0, -9.5, 14, 8, 5, M.PLATE).tag("knee");
            A(shin, X(5, 14, s), 8, -5.5, 14, 10, 9, M.PLATE).tag("greave");
            A(shin, X(5.5, 13, s), 15, -5, 13, 5, 14, M.TUFT).tag("fetlock");
            A(shin, X(5, 6, s), 18, -9, 6, 6, 14, M.HOOF).tag("toe");
            A(shin, X(13, 6, s), 18, -9, 6, 6, 14, M.HOOF).tag("toe");
            A(shin, X(6, 12, s), 19, 2, 12, 5, 7, M.HOOF).tag("heel");
        }

        Part tail = bone("tail", body, 0, -20, 10);
        Part tbase = deco("tail_base", tail, 0, 0, 0, -0.9, 0, 0);
        L(tbase, -2, -2, 0, 4, 4, 12, M.FUR).tag("tailbase");
        Part ttip = bone("tail_tip", tail, 0, -11, 17);
        A(ttip, -1.5, -12, 15.5, 3, 10, 3, M.FUR).tag("tailtip");
        A(ttip, -2.5, -4, 14.5, 5, 9, 5, M.TUFT).tag("tuft");

        Part cape = bone("cape", torso, 0, -62, 12);
        A(cape, -20, -62, 13.5, 40, 42, 1, M.CAPE).rag().tag("cape");
        Part capeLower = bone("cape_lower", cape, 0, -20, 13);
        A(capeLower, -20, -20, 13.5, 20, 28, 1, M.CAPE).rag().tag("cape");
        A(capeLower, 0, -20, 13.5, 20, 28, 1, M.CAPE).rag().tag("cape");
    }

    static void twoSpike(Part parent, String name, double ox, double oy, double oz, double pitch, double yaw, double roll, int bw, int bh, int tw, int th) {
        Part p = deco(name, parent, ox, oy, oz, pitch, yaw, roll);
        double split = bh / (double) (bh + th);
        L(p, -bw / 2.0, -bh, -bw / 2.0, bw, bh, bw, M.SPIKE).t(0, split, 0);
        L(p, -tw / 2.0, -bh - th, -tw / 2.0, tw, th, tw, M.SPIKE).t(split, 1, 0);
    }

    /** the axe: shaft along -Z from the grip, blade at the far end with its edge towards +Y. All sizes even (item model = half). */
    static void buildAxe(Part axe) {
        A(axe, -28, -10, -62, 4, 4, 70, M.SHAFT).tag("shaft");
        A(axe, -29, -11, 8, 6, 6, 4, M.SOCKET).tag("pommel");
        A(axe, -27, -9, 12, 2, 2, 6, M.SPIKE).t(0, 1, 3).tag("pommel_spike");
        A(axe, -29, -11, -34, 6, 6, 26, M.WRAP).tag("wrap");
        A(axe, -29, -11, -64, 6, 6, 22, M.SOCKET).tag("socket");
        A(axe, -27, -9, -72, 2, 2, 8, M.SPIKE).t(0, 1, 4).tag("finial");
        A(axe, -28, -5, -58, 4, 8, 10, M.BLADE).tag("blade_neck");
        A(axe, -28, 3, -64, 4, 10, 22, M.BLADE).tag("blade_body");
        A(axe, -28, 13, -70, 4, 8, 36, M.BLADE).tag("blade_lower");
        A(axe, -27, 21, -70, 2, 2, 36, M.EDGE).tag("edge");
        A(axe, -27, -1, -76, 2, 22, 6, M.EDGE).tag("tip_front");
        A(axe, -27, 1, -34, 2, 20, 6, M.EDGE).tag("tip_back");
        A(axe, -28, -17, -58, 4, 6, 6, M.SPIKE).t(0, 0.5, 0).tag("back_spike");
        A(axe, -27, -23, -57, 2, 6, 4, M.SPIKE).t(0.5, 1, 0).tag("back_spike");
    }

    // =====================================================================================
    // math (row-major 4x4)
    static double[] mul(double[] a, double[] b) {
        double[] r = new double[16];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
            double s = 0;
            for (int k = 0; k < 4; k++) s += a[i * 4 + k] * b[k * 4 + j];
            r[i * 4 + j] = s;
        }
        return r;
    }

    static double[] ident() { return new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}; }
    static double[] trans(double x, double y, double z) { return new double[]{1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1}; }
    static double[] rx(double a) { double c = Math.cos(a), s = Math.sin(a); return new double[]{1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1}; }
    static double[] ry(double a) { double c = Math.cos(a), s = Math.sin(a); return new double[]{c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1}; }
    static double[] rz(double a) { double c = Math.cos(a), s = Math.sin(a); return new double[]{c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}; }
    static double[] app(double[] m, double x, double y, double z) {
        return new double[]{m[0] * x + m[1] * y + m[2] * z + m[3], m[4] * x + m[5] * y + m[6] * z + m[7], m[8] * x + m[9] * y + m[10] * z + m[11]};
    }
    static double[] dir(double[] m, double x, double y, double z) {
        return new double[]{m[0] * x + m[1] * y + m[2] * z, m[4] * x + m[5] * y + m[6] * z, m[8] * x + m[9] * y + m[10] * z};
    }

    /** part transform as in ModelPart.applyTransform: translate(origin), rotate Z*Y*X (rotationZYX(roll, yaw, pitch)) */
    static double[] world(Part p, Map<String, double[]> pose) {
        if (p == null || p == ROOT) return ident();
        double[] m = mul(world(p.parent, pose), trans(p.ox, p.oy, p.oz));
        double pi = p.pitch, ya = p.yaw, ro = p.roll;
        if (pose != null && pose.containsKey(p.name)) {
            double[] r = pose.get(p.name);
            pi += r[0]; ya += r[1]; ro += r[2];
        }
        return mul(m, mul(rz(ro), mul(ry(ya), rx(pi))));
    }

    // =====================================================================================
    // UV packing
    static void pack() {
        List<Cube> order = new ArrayList<>(CUBES);
        order.sort((a, b) -> {
            int ha = a.d + a.h, hb = b.d + b.h;
            if (ha != hb) return hb - ha;
            return (2 * (b.d + b.w)) - (2 * (a.d + a.w));
        });
        int x = 0, y = 0, rowH = 0;
        for (Cube c : order) {
            int bw = 2 * (c.d + c.w), bh = c.d + c.h;
            if (x + bw > TW) { x = 0; y += rowH + 1; rowH = 0; }
            if (y + bh > TH) throw new IllegalStateException("texture full at cube " + c.idx + " " + c.tag);
            c.u = x; c.v = y;
            x += bw + 1;
            rowH = Math.max(rowH, bh);
        }
    }

    // =====================================================================================
    // painting
    static final class Ctx {
        Cube c;
        F f;
        int a, b, fw, fh, cx, cy, cz, gi;
        double[] p, n;
        int[] g;
    }

    static final double[] LIGHT = norm(-0.45, -1, -0.55);

    static void paintAll() {
        int errors = 0;
        for (Cube c : CUBES) {
            double[] mw = world(c.part, null);
            Box b = c.box();
            for (F f : F.values()) {
                int[] org = b.origin(f), sz = b.size(f);
                double[] nl = switch (f) {
                    case TOP -> new double[]{0, -1, 0};
                    case BOTTOM -> new double[]{0, 1, 0};
                    case RIGHT -> new double[]{-1, 0, 0};
                    case LEFT -> new double[]{1, 0, 0};
                    case FRONT -> new double[]{0, 0, -1};
                    case BACK -> new double[]{0, 0, 1};
                };
                double[] nw = dir(mw, nl[0], nl[1], nl[2]);
                int[] err = {0};
                forFace(b, f, (x, y, z) -> {
                    double lx = c.x + x + 0.5, ly = c.y + y + 0.5, lz = c.z + z + 0.5;
                    switch (f) {
                        case TOP -> ly = c.y;
                        case BOTTOM -> ly = c.y + c.h;
                        case RIGHT -> lx = c.x;
                        case LEFT -> lx = c.x + c.w;
                        case FRONT -> lz = c.z;
                        case BACK -> lz = c.z + c.d;
                    }
                    int[] q = b.px(f, x, y, z);
                    int gi = q[1] * TW + q[0];
                    if (owner[gi] != 0) { err[0]++; return; }
                    owner[gi] = c.idx + 1;
                    Ctx k = new Ctx();
                    k.c = c; k.f = f; k.a = q[0] - org[0]; k.b = q[1] - org[1]; k.fw = sz[0]; k.fh = sz[1];
                    k.cx = x; k.cy = y; k.cz = z; k.gi = gi; k.g = glow;
                    k.p = app(mw, lx, ly, lz);
                    k.n = nw;
                    int col = shade(k);
                    if (!c.rag && (col >>> 24) != 255) err[0] += 1000;
                    base[gi] = (col >>> 24) == 0 ? 0 : col;
                });
                if (err[0] != 0) {
                    System.out.println("ERROR cube " + c.idx + " (" + c.part.name + "/" + c.tag + ") face " + f + ": " + (err[0] % 1000) + " overlapping, " + (err[0] / 1000) + " transparent pixels");
                    errors++;
                }
            }
        }
        if (errors > 0) throw new IllegalStateException(errors + " face errors");
    }

    static int shade(Ctx x) {
        return switch (x.c.mat(x.f)) {
            case FUR -> fur(x);
            case MANE -> pick(FU, furLv(x, 13, 7, 7) + 0.35);
            case TUFT -> tuft(x);
            case HEADFUR -> headfur(x);
            case SKIN -> skin(x);
            case NOSE -> nose(x);
            case JAW -> jaw(x);
            case EAR -> ear(x);
            case FIST -> fist(x);
            case HORN -> horn(x);
            case BROW -> brow(x);
            case SPIKE -> spike(x);
            case GOLD -> gold(x);
            case STEEL -> pick(ST, steelLv(x));
            case PLATE -> plate(x);
            case LAMES -> lames(x);
            case BELT -> belt(x);
            case CLOTH_RED -> clothRed(x);
            case CAPE -> cape(x);
            case SHAFT -> shaft(x);
            case WRAP -> wrap(x);
            case SOCKET -> socket(x);
            case BLADE -> blade(x);
            case EDGE -> edge(x);
            case HOOF -> hoof(x);
        };
    }

    // ---------------- helpers ----------------
    static int fl(double v) { return (int) Math.floor(v); }
    static double frac(double v) { return v - Math.floor(v); }
    static double clamp(double v, double a, double b) { return Math.max(a, Math.min(b, v)); }
    static double tb(Ctx x) { return x.fh > 1 ? x.b / (x.fh - 1.0) : 0.5; }
    static boolean side(F f) { return f == F.RIGHT || f == F.LEFT || f == F.FRONT || f == F.BACK; }
    static boolean vertical(Ctx x) { return Math.abs(x.n[1]) < 0.4; }
    static double hc(Ctx x) { return Math.abs(x.n[0]) > Math.abs(x.n[2]) ? x.p[2] : x.p[0]; }
    static double lit(Ctx x) { return x.n[0] * LIGHT[0] + x.n[1] * LIGHT[1] + x.n[2] * LIGHT[2]; }
    static double[] norm(double x, double y, double z) { double l = Math.sqrt(x * x + y * y + z * z); return new double[]{x / l, y / l, z / l}; }

    static int dirKey(double[] n) {
        double ax = Math.abs(n[0]), ay = Math.abs(n[1]), az = Math.abs(n[2]);
        if (ax >= ay && ax >= az) return n[0] > 0 ? 1 : 0;
        if (ay >= az) return n[1] > 0 ? 3 : 2;
        return n[2] > 0 ? 5 : 4;
    }

    static int pick(int[] pal, double lv) {
        int i = (int) Math.round(lv);
        return 0xff000000 | pal[Math.max(0, Math.min(pal.length - 1, i))];
    }

    static int mix(int a, int b, double t) {
        t = clamp(t, 0, 1);
        int r = (int) Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return 0xff000000 | r << 16 | g << 8 | bl;
    }

    static double hash(int a, int b, int c) {
        long h = a * 0x9E3779B97F4A7C15L ^ (b * 0xC2B2AE3D27D4EB4FL) ^ (c * 0x165667B19E3779F9L) ^ 0x5DEECE66DL;
        h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32; h *= 0x94D049BB133111EBL; h ^= h >>> 29;
        return ((h >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }

    /** smooth value noise, -0.5..0.5 */
    static double vnoise(double x, double y, double z, int seed) {
        int x0 = fl(x), y0 = fl(y), z0 = fl(z);
        double fx = x - x0, fy = y - y0, fz = z - z0;
        fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy); fz = fz * fz * (3 - 2 * fz);
        double v = 0;
        for (int i = 0; i < 8; i++) {
            int dx = i & 1, dy = (i >> 1) & 1, dz = (i >> 2) & 1;
            double w = (dx == 1 ? fx : 1 - fx) * (dy == 1 ? fy : 1 - fy) * (dz == 1 ? fz : 1 - fz);
            v += w * hash(x0 + dx, (y0 + dy) * 131 + (z0 + dz) * 7919, seed);
        }
        return v - 0.5;
    }

    static double lum(int c) { return ((c >> 16) & 255) * 0.5 + ((c >> 8) & 255) * 0.4 + (c & 255) * 0.1; }

    /** glow pixel; keeps the brighter one if something is already there */
    static void G(Ctx x, int rgb, int a) {
        a = Math.max(0, Math.min(255, a));
        if (a == 0) return;
        int old = x.g[x.gi];
        int nv = (a << 24) | rgb;
        if (old == 0 || lum(nv) * a > lum(old) * (old >>> 24)) x.g[x.gi] = nv;
    }

    /** dried blood running down a vertical face from its top edge, 1 cell wide per column. 0 = none. */
    static int blood(Ctx x, double dens, int seed) {
        if (!vertical(x)) return 0;
        int col = fl(hc(x));
        int dk = dirKey(x.n);
        if (hash(col, dk * 31 + seed, 501) >= dens) return 0;
        double top = x.p[1] - x.b - 0.5;
        double y0 = top + hash(col, dk, 502 + seed) * Math.min(5, x.fh * 0.3);
        double len = 3 + hash(col, dk, 503 + seed) * Math.min(13, x.fh * 0.9);
        double dy = x.p[1] - y0;
        if (dy < 0 || dy > len) return 0;
        if (dy > len - 1) return 0xff000000 | DR[6];
        if (dy > len - 2) return 0xff000000 | DR[5];
        return 0xff000000 | DR[(int) clamp(1 + dy / len * 3.5, 1, 4)];
    }

    /** dried blood blotches */
    static int stain(Ctx x, double dens, int seed) {
        double v = vnoise(x.p[0] * 0.32, x.p[1] * 0.32, x.p[2] * 0.32, seed) + vnoise(x.p[0] * 0.9, x.p[1] * 0.9, x.p[2] * 0.9, seed + 1) * 0.35;
        if (v > 0.5 - dens) return 0xff000000 | DR[v > 0.62 - dens ? 3 : 2];
        return 0;
    }

    // ---------------- fur ----------------
    static double furLv(Ctx x, int seed, double lockMin, double lockVar) {
        double[] p = x.p, n = x.n;
        if (n[1] < -0.6) return 3.1 + vnoise(p[0] * 0.4, 0, p[2] * 0.4, seed) * 1.8 + (hash(fl(p[0]), fl(p[2]), seed) - 0.5) * 0.6;
        if (n[1] > 0.6) return 1.2 + vnoise(p[0] * 0.4, 7, p[2] * 0.4, seed) * 0.8;
        double h = hc(x);
        int col = fl(h);
        int lock = Math.floorDiv(col, 2);
        double ph = hash(lock, 1, seed) * 9, len = lockMin + hash(lock, 2, seed) * lockVar;
        double t = frac((p[1] + ph) / len);
        double lv = 3.3 - 1.7 * t + (Math.floorMod(col, 2) == 0 ? 0.2 : -0.1);
        lv += (hash(col, 3, seed) - 0.5) * 0.3;
        lv += vnoise(h * 0.15, p[1] * 0.1, 0, seed + 5) * 1.3;
        return lv - 0.8 * tb(x);
    }

    static int fur(Ctx x) {
        String t = x.c.tag;
        double lv = furLv(x, 11 + x.c.idx % 3, 4, 4);
        if (t.equals("upperarm") || t.equals("forearm")) {
            // painted iron arm ring on the biceps + dark veins
            double y = x.p[1];
            if (t.equals("upperarm") && y > -45 && y < -40 && vertical(x)) {
                double r = y + 45;
                lv = r < 1 ? 7.0 : r < 2 ? 4.6 : r < 4 ? 3.6 : 1.4;
                if (r >= 2 && r < 3 && Math.floorMod(fl(hc(x)), 4) == 0) { G(x, GL[3], 200); return 0xff000000 | CR[5]; }
                return pick(ST, lv + vnoise(x.p[0], x.p[1], x.p[2], 3) * 0.6);
            }
        }
        if (t.equals("thigh") || t.equals("shin") || t.equals("forearm") || t.equals("upperarm")) {
            int bl = blood(x, 0.07, 17);
            if (bl != 0) return bl;
        }
        return pick(FU, lv);
    }

    static int tuft(Ctx x) {
        double lv = furLv(x, 23, 5, 5) + 0.3;
        if (vertical(x) && x.b >= x.fh - 2 && hash(fl(hc(x)), 9, 4) < 0.5) lv -= 1.4; // ragged tips
        if (x.c.tag.equals("beard")) {
            int bl = blood(x, 0.3, 29);
            if (bl != 0) return bl;
        }
        return pick(FU, lv);
    }

    // ---------------- head ----------------
    /** angry slanted eye, cell centres (|x|, y) on the forehead. 0 none, 1 bright, 2 hot core, 3 dim rim */
    static int eye(double ax, double y) {
        int r = fl(y + 77), c = fl(ax);
        return switch (r) {
            case 0 -> (c == 8) ? 3 : (c == 7) ? 1 : 0;
            case 1 -> (c >= 5 && c <= 8) ? ((c == 6 || c == 7) ? 2 : 1) : 0;
            case 2 -> (c >= 4 && c <= 7) ? ((c == 5 || c == 6) ? 1 : 3) : 0;
            default -> 0;
        };
    }

    static boolean eyeNear(double ax, double y) {
        return eye(ax, y) == 0 && (eye(ax + 1, y) != 0 || eye(ax - 1, y) != 0 || eye(ax, y + 1) != 0 || eye(ax, y - 1) != 0);
    }

    static int headfur(Ctx x) {
        double lv = furLv(x, 21, 3, 3) - 0.1;
        if (x.f == F.FRONT) {
            double ax = Math.abs(x.p[0]), y = x.p[1];
            int e = eye(ax, y);
            if (e != 0) {
                G(x, e == 2 ? GL[7] : e == 1 ? GL[5] : GL[3], 255);
                return 0xff000000 | (e == 2 ? 0xffe0b0 : e == 1 ? 0xff5a28 : 0xa8141a);
            }
            if (eyeNear(ax, y)) { G(x, GL[3], 80); return pick(FU, 0.2); }
            if (y < -76) lv -= 1.3; // under the brow
            if (ax < 2 && y > -76) lv += 0.5; // nose bridge ridge
            if (y > -73 && ax < 8) lv -= 0.6; // above the muzzle
        }
        return pick(FU, lv);
    }

    static int brow(Ctx x) {
        double[] p = x.p;
        double ax = Math.abs(p[0]);
        double lv;
        switch (x.f) {
            case TOP -> lv = 4.6 + (p[2] < -15 ? 0.8 : 0) + vnoise(p[0] * 0.5, 0, p[2] * 0.5, 41) * 0.8;
            case BOTTOM -> {
                if (ax > 3.5 && ax < 10.5) G(x, GL[2], (int) (70 - Math.abs(ax - 7) * 12));
                return pick(HO, 0.6);
            }
            case FRONT -> {
                lv = x.b == 0 ? 6.4 : x.b == x.fh - 1 ? 1.6 : 3.8 - x.b * 0.4;
                if (ax < 1.5) lv -= 1.4; // dip between the brow ridges
                if (x.a == 0) lv += 0.8;
            }
            default -> lv = 3.2 - tb(x) + (x.b == 0 ? 1.4 : 0);
        }
        lv += (hash(fl(p[0]), fl(p[1]), fl(p[2]) + 41) - 0.5) * 0.5;
        return pick(HO, lv);
    }

    static int tooth(Ctx x, double along, boolean fangHere) {
        int m = Math.floorMod(fl(along), 3);
        if (fangHere) return pick(BO, 5);
        if (m == 0) return pick(SK, 0.4);
        int c = pick(BO, m == 1 ? 4.6 : 3.6);
        if (hash(fl(along), 3, 77) < 0.3) c = mix(c, 0xff000000 | DR[4], 0.5);
        return c;
    }

    static int skin(Ctx x) {
        double[] p = x.p;
        double lv = 3.3 - 1.0 * tb(x) + vnoise(p[0] * 0.3, p[1] * 0.3, p[2] * 0.3, 31) * 0.9;
        switch (x.f) {
            case TOP -> {
                if (p[2] > -19.5 + hash(fl(p[0]), 0, 32) * 3) return pick(FU, furLv(x, 21, 3, 3));
                lv = 3.8 + vnoise(p[0] * 0.4, 0, p[2] * 0.4, 33) * 0.8;
                if (p[2] < -20 && Math.floorMod(fl(p[2]), 3) == 0 && Math.abs(p[0]) < 5.5) lv -= 1.0; // wrinkles
                if (Math.abs(p[0]) > 6) lv -= 0.8;
            }
            case RIGHT, LEFT -> {
                if (p[2] > -18 + hash(fl(p[1]), 1, 34) * 2.5 && x.b < x.fh - 2) return pick(FU, furLv(x, 21, 3, 3));
                if (x.b == x.fh - 1) return tooth(x, -p[2], p[2] < -24 && p[2] > -26);
                if (x.b == x.fh - 2) {
                    if (p[2] < -24 && p[2] > -26) return pick(BO, 4); // upper fang root
                    lv = 1.0; // lip
                }
                if (x.b == 0) lv += 0.8;
                int bl = blood(x, 0.22, 35);
                if (bl != 0 && x.b > 2) return bl;
            }
            case BOTTOM -> {
                // roof of the mouth, seen when the jaw opens: teeth along the rim, ridged palate, glowing throat
                double ax = Math.abs(p[0]);
                if (ax > 5.5 || p[2] < -25.5) return tooth(x, ax > 5.5 ? -p[2] : p[0] + 20, false);
                if (p[2] > -19) G(x, GL[(p[2] > -16) ? 3 : 2], (int) ((p[2] + 19) * 45));
                return pick(LN, Math.floorMod(fl(p[2]), 2) == 0 ? 3.2 : 1.8);
            }
            default -> { }
        }
        return pick(SK, lv);
    }

    static int nose(Ctx x) {
        double[] p = x.p;
        if (x.f == F.FRONT) {
            double cx = x.a - (x.fw - 1) / 2.0, ay = x.b;
            double dx = Math.abs(cx) - 4.0, dy = ay - 5.4;
            double d = Math.sqrt(dx * dx * 0.45 + dy * dy * 1.3);
            if (d < 1.1) { G(x, GL[0], (int) (110 - d * 60)); return 0xff000000 | (d < 0.6 ? 0x140304 : 0x070405); }
            if (d < 1.7) return pick(SK, 1.2);
            double lv = 3.0 - ay * 0.15 + vnoise(p[0] * 0.5, p[1] * 0.5, 0, 51) * 0.7;
            if (dy < -0.8 && d < 2.6) lv += 1.0; // flared rim
            if (Math.abs(cx) < 1.2) lv += 0.4; // septum
            if (x.b == 0) lv += 1.2;
            if (Math.abs(cx) > 6.6) lv -= 0.8;
            if (x.b == x.fh - 1 && Math.abs(Math.abs(cx) - 3.0) < 1.1) lv = 0.3; // where the ring goes in
            if ((x.a == 3 || x.a == 12) && x.b == 1) lv = 7; // wet highlights
            return pick(SK, lv);
        }
        if (x.f == F.TOP) {
            double lv = 4.2 + vnoise(p[0] * 0.5, 0, p[2] * 0.5, 52) * 0.8;
            if (p[2] < -30) lv += 1.6;
            if (hash(fl(p[0]), fl(p[2]), 53) < 0.06) lv = 7;
            return pick(SK, lv);
        }
        if (x.f == F.BOTTOM) return pick(SK, 1.0);
        double lv = 3.6 - tb(x) * 1.4 + (x.b == 0 ? 1.0 : 0);
        int bl = blood(x, 0.3, 54);
        if (bl != 0) return bl;
        return pick(SK, lv);
    }

    static int jaw(Ctx x) {
        double[] p = x.p;
        double ax = Math.abs(p[0]);
        switch (x.f) {
            case TOP -> {
                if (ax > 4.5 || p[2] < -25.5) return tooth(x, ax > 4.5 ? -p[2] + 1 : p[0] + 21, ax > 4.5 && p[2] < -24 && p[2] > -26);
                if (ax < 2.5 && p[2] > -24) { // tongue
                    if (p[2] > -19) G(x, GL[2], (int) ((p[2] + 19) * 40));
                    return pick(LN, 4.2 - (ax > 1.5 ? 1.2 : 0) + (Math.floorMod(fl(p[2]), 3) == 0 ? -0.8 : 0));
                }
                if (p[2] > -19) G(x, GL[2], (int) ((p[2] + 19) * 40));
                return pick(LN, 1.4);
            }
            case RIGHT, LEFT -> {
                if (x.b == 0) return tooth(x, -p[2] + 1, p[2] < -24 && p[2] > -26);
                if (x.b == 1) return (p[2] < -24 && p[2] > -26) ? pick(BO, 3.5) : pick(SK, 1.0);
                int bl = blood(x, 0.45, 61);
                if (bl != 0) return bl;
                if (p[2] > -19) return pick(FU, furLv(x, 21, 3, 3));
                return pick(SK, 3.4 - tb(x) * 1.2 + vnoise(p[1] * 0.5, p[2] * 0.5, 0, 62) * 0.8);
            }
            case FRONT -> {
                if (x.b == 0) return tooth(x, p[0] + 21, false);
                if (x.b == 1) return pick(SK, 1.0);
                int bl = blood(x, 0.5, 63);
                if (bl != 0) return bl;
                return pick(SK, 3.8 - tb(x) * 1.4);
            }
            case BOTTOM -> { return pick(FU, furLv(x, 21, 3, 3)); }
            default -> { return pick(LN, 1.0); }
        }
    }

    static int ear(Ctx x) {
        if (x.n[2] < -0.5 || x.n[1] > 0.5) return pick(LN, 3.0 - tb(x) + (x.b == 0 ? 0 : 0.5)); // inner side
        double lv = furLv(x, 27, 3, 2) + lit(x) * 1.2;
        return pick(FU, lv);
    }

    // ---------------- hands ----------------
    static int fist(Ctx x) {
        double[] p = x.p, n = x.n;
        int zi = fl(p[2] + 6.5);
        boolean sep = zi == 3 || zi == 6 || zi == 9;
        int s = p[0] < 0 ? -1 : 1;
        boolean outer = n[0] * s > 0.5, inner = n[0] * s < -0.5;
        double lv = 3.4 - 0.8 * tb(x) + vnoise(p[0] * 0.4, p[1] * 0.4, p[2] * 0.4, 71) * 0.6;
        double fc = 0; // finger roundness: brighter in the middle of each finger
        int start = zi < 3 ? 0 : zi < 6 ? 4 : zi < 9 ? 7 : 10, end = zi < 3 ? 2 : zi < 6 ? 5 : zi < 9 ? 8 : 12;
        if (!sep) fc = 0.6 - Math.abs(zi - (start + end) / 2.0) * 0.45;
        int st = stain(x, 0.28, 72);
        if (outer) {
            if (x.b <= 1) lv = sep ? 1.6 : 5.6 + fc; // knuckles
            else if (sep) lv = 0.8;
            else lv += fc;
        } else if (n[1] > 0.5) {
            lv = sep ? 0.6 : 2.4 + fc;
        } else if (inner) {
            int fromBottom = x.fh - 1 - x.b;
            if (x.b <= 2) lv = 3.8 + (x.b == 0 ? 1 : 0); // thumb across the top
            else if (sep) lv = 0.6;
            else if (fromBottom >= 1 && fromBottom <= 3 && zi != start && zi != end) return pick(HO, 7.5 - fromBottom * 1.3); // claws
            else lv += fc;
        } else if (n[1] < -0.5) {
            lv = 3.2;
        } else {
            // front/back: the curled index finger / little finger ring
            int r = x.b;
            lv = (r % 3 == 0) ? 1.2 : 3.6 - (r % 3) * 0.3;
            if (x.f == F.FRONT && r <= 3) lv = 4.4 - r * 0.3; // thumb tip
        }
        if (st != 0) return st;
        int bl = blood(x, 0.25, 73);
        if (bl != 0) return bl;
        return pick(SK, lv - 0.4);
    }

    // ---------------- horns and spikes ----------------
    /** 0..1 along the cube's growth axis (towards the tip) */
    static double along(Ctx x) {
        Cube c = x.c;
        return switch (c.axis) {
            case 0 -> x.f == F.TOP ? 1 : x.f == F.BOTTOM ? 0 : 1 - (x.cy + 0.5) / c.h;
            case 1 -> x.f == F.LEFT ? 1 : x.f == F.RIGHT ? 0 : (x.cx + 0.5) / c.w;
            case 2 -> x.f == F.RIGHT ? 1 : x.f == F.LEFT ? 0 : 1 - (x.cx + 0.5) / c.w;
            case 3 -> x.f == F.BACK ? 1 : x.f == F.FRONT ? 0 : (x.cz + 0.5) / c.d;
            case 4 -> x.f == F.FRONT ? 1 : x.f == F.BACK ? 0 : 1 - (x.cz + 0.5) / c.d;
            default -> 0.5;
        };
    }

    static boolean capFace(Ctx x) {
        return switch (x.c.axis) {
            case 0 -> x.f == F.TOP || x.f == F.BOTTOM;
            case 1, 2 -> x.f == F.LEFT || x.f == F.RIGHT;
            case 3, 4 -> x.f == F.FRONT || x.f == F.BACK;
            default -> false;
        };
    }

    static int horn(Ctx x) {
        Cube c = x.c;
        double t = c.t0 + (c.t1 - c.t0) * along(x);
        double s = t * 41;
        double lv = 3.0 + lit(x) * 2.2;
        if (frac(s / 2.7) < 0.34) lv -= 1.1; // growth ridges
        lv += (hash(fl(s), x.c.idx, 81) - 0.5) * 0.4;
        if (capFace(x)) lv = 3.0;
        int col = pick(HO, lv);
        if (t > 0.6) {
            double k = (t - 0.6) / 0.4;
            int cr = pick(CR, 2.5 + k * 6 + lit(x) * 1.5 - (frac(s / 2.7) < 0.34 ? 1 : 0));
            col = mix(col, cr, Math.pow(k, 0.7));
        }
        return col;
    }

    static int spike(Ctx x) {
        Cube c = x.c;
        double t = c.t0 + (c.t1 - c.t0) * along(x);
        double lv = 4.0 + lit(x) * 2.6 + (hash(x.cx + x.cy * 7 + x.cz * 13, c.idx, 82) - 0.5) * 0.4;
        if (capFace(x) && t < 0.5) lv = 2.0;
        int col = pick(ST, lv);
        if (t > 0.55) col = mix(col, pick(CR, 3 + (t - 0.55) * 10 + lit(x) * 2), Math.pow((t - 0.55) / 0.45, 0.8));
        return col;
    }

    static int gold(Ctx x) {
        double lv = 4.2 + lit(x) * 2.0;
        if (x.b == 0 && side(x.f)) lv += 1.2;
        if (x.f == F.BOTTOM) lv = 2.4;
        if (hash(x.cx, x.cy, x.c.idx + 90) < 0.15) lv -= 1.5;
        int c = pick(GO, lv);
        if (x.c.w == 8 && x.f != F.TOP && hash(x.cx, x.cz, 91) < 0.35) c = mix(c, 0xff000000 | DR[4], 0.7);
        return c;
    }

    // ---------------- steel ----------------
    static double steelLv(Ctx x) {
        double lv;
        boolean sideF = side(x.f);
        if (x.n[1] < -0.6) lv = 4.6;
        else if (x.n[1] > 0.6) lv = 1.6;
        else lv = 4.1 - 1.5 * tb(x);
        lv += vnoise(x.p[0] * 0.22, x.p[1] * 0.22, x.p[2] * 0.22, 61) * 0.9;
        lv += (hash(fl(x.p[0]), fl(x.p[1]), fl(x.p[2]) + 61) - 0.5) * 0.25;
        int a = x.a, b = x.b, fw = x.fw, fh = x.fh;
        if (fw >= 3 && fh >= 3) {
            if (sideF) {
                if (b == 0) lv = 7.6 - (a == fw - 1 ? 1.5 : 0);
                else if (b == fh - 1) lv -= 1.6;
                else if (a == 0) lv += 1.4;
                else if (a == fw - 1) lv -= 1.0;
                else if (b == 1) lv += 0.8;
            } else if (x.n[1] < -0.6 && (a == 0 || b == 0 || a == fw - 1 || b == fh - 1)) lv = 6.4;
        } else if (sideF && fh <= 2) lv += 1.0;
        // a few bright scratches
        int sc = Math.floorMod(a * 2 + b * 3 + x.c.idx * 5, 37);
        if (sc == 0 && hash(a, b, x.c.idx) < 0.5) lv += 1.4;
        return lv;
    }

    /** engraving: 0 none, 1 groove, 2 node */
    static int engrave(Ctx x) {
        String t = x.c.tag;
        int a = x.a, b = x.b, fw = x.fw, fh = x.fh;
        boolean sideF = side(x.f);
        // only faces looking away from the body axis (or up)
        boolean outward = x.n[0] * x.p[0] + x.n[2] * x.p[2] > 0.1 || x.n[1] < -0.6;
        switch (t) {
            case "breast": return x.f == F.FRONT ? mask("breast", fw, fh)[b * fw + a] : 0;
            case "buckle": return x.f == F.FRONT ? mask("buckle", fw, fh)[b * fw + a] : 0;
            case "knee": {
                if (x.f != F.FRONT) return 0;
                double c0 = (fw - 1) / 2.0, c1 = (fh - 1) / 2.0;
                double dd = Math.abs(a - c0) + Math.abs(b - c1);
                if (dd < 0.6) return 2;
                if (Math.abs(dd - 3) < 0.6) return 1;
                return 0;
            }
            case "lame": {
                if (!sideF || !outward || fh < 5) return 0;
                if (b == fh / 2 && a > 1 && a < fw - 2) return Math.floorMod(a - fw / 2, 6) == 0 ? 2 : 1;
                return 0;
            }
            case "bracer": {
                if (!sideF || !outward) return 0;
                int e = border(a, b, fw, fh, 1);
                if (e != 0) return e;
                int mid = fh / 2;
                if (b == mid - 1 + Math.abs(Math.floorMod(a, 4) - 2) && a > 2 && a < fw - 3) return 1;
                return 0;
            }
            case "greave": {
                if (!sideF || !outward) return 0;
                int e = border(a, b, fw, fh, 1);
                if (e != 0) return e;
                int c = fw / 2;
                int m = Math.floorMod(b - 2, 5);
                if (b < 2 || b > fh - 3) return 0;
                if (a == c && m == 2) return 2;
                if (Math.abs(a - c) + Math.abs(m - 2) == 2) return 1;
                return 0;
            }
            default: {
                if (!outward || x.f == F.BOTTOM) return 0;
                int e = border(a, b, fw, fh, 2);
                if (e != 0) return e;
                return knot(a, b, fw, fh);
            }
        }
    }

    static int border(int a, int b, int fw, int fh, int in) {
        if (fw < 2 * in + 6 || fh < 2 * in + 4) return 0;
        int a0 = in, a1 = fw - 1 - in, b0 = in, b1 = fh - 1 - in;
        if (a < a0 || a > a1 || b < b0 || b > b1) return 0;
        boolean ea = a == a0 || a == a1, eb = b == b0 || b == b1;
        if (ea && eb) return 0; // rounded corner
        return ea || eb ? 3 : 0; // dim trim line
    }

    /** small Celtic knot in the middle of large plate faces */
    static final String[] KNOT = {
            ".XX.XX.",
            "X..X..X",
            "X.X.X.X",
            ".X.O.X.",
            "X.X.X.X",
            "X..X..X",
            ".XX.XX.",
    };

    static int knot(int a, int b, int fw, int fh) {
        if (fw < 13 || fh < 11) return 0;
        int ka = a - (fw - 7) / 2, kb = b - (fh - 7) / 2;
        if (ka < 0 || kb < 0 || ka >= 7 || kb >= 7) return 0;
        char c = KNOT[kb].charAt(ka);
        return c == 'X' ? 1 : c == 'O' ? 2 : 0;
    }

    /** woven diagonal lattice (over/under at the crossings) */
    static int lattice(int a, int b, int a0, int a1, int b0, int b1, int P) {
        if (a1 - a0 < 4 || b1 - b0 < 3) return 0;
        if (a < a0 || a > a1 || b < b0 || b > b1) return 0;
        int s = a + b, d = a - b;
        boolean l1 = Math.floorMod(s, P) == 0, l2 = Math.floorMod(d, P) == 0;
        if (l1 && l2) return 1;
        if (l1) {
            int md = Math.floorMod(d, P);
            int dc = md == 2 ? d - 2 : md == P - 2 ? d + 2 : Integer.MIN_VALUE;
            if (dc != Integer.MIN_VALUE && ((Math.floorDiv(s, P) + Math.floorDiv(dc, P)) & 1) == 1) return 0;
            return 1;
        }
        if (l2) {
            int ms = Math.floorMod(s, P);
            int sc = ms == 2 ? s - 2 : ms == P - 2 ? s + 2 : Integer.MIN_VALUE;
            if (sc != Integer.MIN_VALUE && ((Math.floorDiv(sc, P) + Math.floorDiv(d, P)) & 1) == 0) return 0;
            return 1;
        }
        return 0;
    }

    static final Map<String, int[]> MASKS = new HashMap<>();

    interface Plot { void at(int a, int b); }

    static void line(int a0, int b0, int a1, int b1, Plot p) {
        int da = Math.abs(a1 - a0), db = -Math.abs(b1 - b0), sa = a0 < a1 ? 1 : -1, sb = b0 < b1 ? 1 : -1, err = da + db;
        while (true) {
            p.at(a0, b0);
            if (a0 == a1 && b0 == b1) break;
            int e2 = 2 * err;
            if (e2 >= db) { err += db; a0 += sa; }
            if (e2 <= da) { err += da; b0 += sb; }
        }
    }

    /** precomputed rune masks for special faces */
    static int[] mask(String key, int fw, int fh) {
        return MASKS.computeIfAbsent(key + fw + "x" + fh, k -> {
            int[] m = new int[fw * fh];
            Plot set = (a, b) -> { if (a >= 0 && b >= 0 && a < fw && b < fh && m[b * fw + a] == 0) m[b * fw + a] = 1; };
            boolean br = key.startsWith("breast");
            // the breast rune sits low on the plate: the jaw hangs in front of its upper part
            double cx = (fw - 1) / 2.0, cy = br ? fh - 7.5 : (fh - 1) / 2.0;
            double R = br ? 5.6 : 4.3, rr = br ? 2.75 : 2.1, dd = br ? 2.2 : 1.6;
            for (int b = 0; b < fh; b++) for (int a = 0; a < fw; a++) {
                double px = a - cx, py = b - cy;
                double r = Math.hypot(px, py);
                if (Math.abs(r - R) < 0.55) m[b * fw + a] = 1;
                for (int i = 0; i < 3; i++) {
                    double ang = -Math.PI / 2 + i * 2 * Math.PI / 3;
                    double di = Math.hypot(px - dd * Math.cos(ang), py - dd * Math.sin(ang));
                    if (Math.abs(di - rr) >= 0.52) continue;
                    for (int j = 0; j < 3; j++) {
                        if (j == i) continue;
                        double angj = -Math.PI / 2 + j * 2 * Math.PI / 3;
                        if (Math.hypot(px - dd * Math.cos(angj), py - dd * Math.sin(angj)) < rr) { m[b * fw + a] = 1; break; }
                    }
                }
                if (r < 0.75) m[b * fw + a] = 2;
            }
            if (key.equals("breast")) {
                int[][][] veins = {
                        {{23, 8}, {25, 7}, {27, 5}, {30, 5}, {32, 3}, {34, 2}},
                        {{23, 13}, {25, 14}, {28, 15}, {31, 15}, {34, 16}},
                        {{30, 5}, {31, 7}, {33, 8}},
                        {{28, 15}, {29, 12}, {31, 11}},
                };
                for (int[][] v : veins) for (int mirror = 0; mirror < 2; mirror++) {
                    for (int i = 0; i + 1 < v.length; i++) {
                        int a0 = mirror == 0 ? v[i][0] : fw - 1 - v[i][0], a1 = mirror == 0 ? v[i + 1][0] : fw - 1 - v[i + 1][0];
                        line(a0, v[i][1], a1, v[i + 1][1], set);
                    }
                }
                for (int[] nd : new int[][]{{30, 5}, {28, 15}, {fw - 1 - 30, 5}, {fw - 1 - 28, 15}}) m[nd[1] * fw + nd[0]] = 2;
            }
            return m;
        });
    }

    static int plate(Ctx x) {
        double lv = steelLv(x);
        int e = engrave(x);
        if (e == 1) { G(x, GL[3], 200); return 0xff000000 | CR[4]; }
        if (e == 2) { G(x, GL[5], 240); return 0xff000000 | CR[8]; }
        if (e == 3) { G(x, GL[1], 130); return 0xff000000 | CR[2]; }
        // engraved groove: dark lip above, lit lip below
        if (x.b > 0) {
            Ctx up = shifted(x, 0, -1);
            if (up != null && engrave(up) != 0) lv += 0.9;
        }
        if (x.b < x.fh - 1) {
            Ctx dn = shifted(x, 0, 1);
            if (dn != null && engrave(dn) != 0) lv -= 1.1;
        }
        // soft red glow halo around the breast rune
        if (x.c.tag.equals("breast") && x.f == F.FRONT) {
            double r = Math.hypot(x.a - (x.fw - 1) / 2.0, x.b - (x.fh - 7.5));
            if (r < 7.5) G(x, GL[2], (int) (55 - r * 6));
        }
        int bl = blood(x, x.c.tag.equals("pauldron") || x.c.tag.equals("bracer") ? 0.12 : 0.05, 64);
        if (bl != 0) return bl;
        return pick(ST, lv);
    }

    static Ctx shifted(Ctx x, int da, int db) {
        int a = x.a + da, b = x.b + db;
        if (a < 0 || b < 0 || a >= x.fw || b >= x.fh) return null;
        Ctx k = new Ctx();
        k.c = x.c; k.f = x.f; k.a = a; k.b = b; k.fw = x.fw; k.fh = x.fh; k.n = x.n; k.p = x.p; k.g = x.g; k.gi = x.gi;
        return k;
    }

    static int lames(Ctx x) {
        if (!vertical(x)) return pick(ST, steelLv(x));
        int yi = fl(x.p[1]);
        int r = Math.floorMod(yi, 4);
        double lv = r == 0 ? 6.8 : r == 1 ? 4.7 : r == 2 ? 3.7 : 1.5;
        if (x.a == 0) lv += 0.8;
        if (x.a == x.fw - 1) lv -= 0.7;
        lv += vnoise(x.p[0] * 0.25, x.p[1] * 0.25, x.p[2] * 0.25, 65) * 0.8;
        if (x.f == F.FRONT) {
            double c = (x.fw - 1) / 2.0;
            double ad = Math.abs(x.a - c);
            if (r == 2 && ad < 0.6) { G(x, GL[3], 190); return 0xff000000 | CR[6]; }
            // rivets at the lame ends
            if (r == 1 && (Math.abs(ad - (c - 2)) < 0.6)) lv = 8;
            if (r == 2 && (Math.abs(ad - (c - 2)) < 0.6)) lv = 2;
        }
        int bl = blood(x, 0.05, 66);
        if (bl != 0) return bl;
        return pick(ST, lv);
    }

    static int belt(Ctx x) {
        double lv = 2.9 - tb(x) * 0.8 + vnoise(x.p[0] * 0.3, x.p[1] * 0.3, x.p[2] * 0.3, 67) * 0.8;
        if (vertical(x)) {
            if (x.b == 0) lv += 1.5;
            if (x.b == x.fh - 1) lv -= 1.0;
            if ((x.b == 1 || x.b == x.fh - 2) && Math.floorMod(fl(hc(x)), 2) == 0) lv += 1.6; // stitching
            // studs
            int col = fl(hc(x));
            int m = Math.floorMod(col, 6);
            if ((x.b == 3 || x.b == 4) && (m == 2 || m == 3)) {
                double sl = (x.b == 3 && m == 2) ? 9 : (x.b == 4 && m == 3) ? 3 : 6;
                return pick(ST, sl);
            }
        }
        return pick(SK, lv - 0.6);
    }

    // ---------------- cloth ----------------
    /** ragged bottom edge of the loincloths */
    static boolean loinMask(double[] p) {
        double ax = Math.abs(p[0]);
        double jag = hash(fl((p[0] + 20) / 2), 0, 101) * 5 + Math.abs(frac(p[0] / 3.0) - 0.5) * 3;
        if (p[1] > 6 - jag) return false;
        if (ax > 7.2 && p[1] > -2 - hash(fl(p[1] / 2), 1, 102) * 4) return false; // frayed sides
        return true;
    }

    static int clothRed(Ctx x) {
        double[] p = x.p;
        if (!loinMask(p)) return 0;
        double lv = 3.0 + 1.1 * Math.sin(p[0] * 0.75 + 1.3) - 0.9 * clamp((p[1] + 19) / 25, 0, 1) + vnoise(p[0] * 0.4, p[1] * 0.2, 0, 103) * 0.8;
        double[] q = {p[0], p[1] + 1.2, p[2]};
        if (!loinMask(q)) lv -= 1.2; // frayed rim
        if (!vertical(x) || Math.abs(x.n[0]) > 0.5) lv -= 1;
        return pick(LN, lv);
    }

    static boolean capeMask(double[] p) {
        double x = p[0], y = p[1], ax = Math.abs(x);
        // ragged bottom
        double jag = hash(fl((x + 40) / 2.5), 0, 111) * 9 + Math.abs(frac(x / 4.0) - 0.5) * 4;
        if (y > 8 - jag) return false;
        // central tear and the tail hole
        if (y > -9 && ax < (y + 9) * 0.22 + 0.3) return false;
        if (((x / 3.4) * (x / 3.4) + ((y + 15) / 4.3) * ((y + 15) / 4.3)) < 1) return false;
        // frayed outer edges lower down
        if (ax > 19 - clamp((y + 30) * 0.12, 0, 4) * hash(fl(y / 2), 2, 112) && y > -34) return false;
        // a few holes
        double[][] holes = {{-12, -30, 1.6, 2.4}, {9, -40, 1.3, 1.8}, {14, -6, 1.8, 2.6}, {-6, -2, 1.4, 2.2}, {-15, -14, 1.2, 1.6}};
        for (double[] h : holes) {
            double dx = (x - h[0]) / h[2], dy = (y - h[1]) / h[3];
            if (dx * dx + dy * dy < 1) return false;
        }
        return true;
    }

    static int cape(Ctx x) {
        double[] p = x.p;
        if (!capeMask(p)) return 0;
        boolean rim = !capeMask(new double[]{p[0] + 1, p[1], p[2]}) || !capeMask(new double[]{p[0] - 1, p[1], p[2]}) || !capeMask(new double[]{p[0], p[1] + 1, p[2]});
        double fold = Math.sin(p[0] * 0.45 + 0.9 * Math.sin(p[1] * 0.07)) + 0.4 * Math.sin(p[0] * 1.3 + 2);
        if (x.f == F.FRONT) { // lining
            double lv = 2.6 + fold * 0.9 + vnoise(p[0] * 0.3, p[1] * 0.15, 0, 113) * 0.8 - (rim ? 1.2 : 0);
            return pick(LN, lv - 0.5);
        }
        double lv = 3.0 + fold * 1.2 - clamp((p[1] + 62) / 70, 0, 1) * 1.0 + vnoise(p[0] * 0.25, p[1] * 0.1, 0, 114) * 1.0;
        lv += (hash(fl(p[0]), fl(p[1] / 3), 115) - 0.5) * 0.4;
        if (rim) lv -= 1.4;
        if (x.f != F.BACK) lv = 1.5;
        return pick(CL, lv);
    }

    // ---------------- axe ----------------
    static int shaft(Ctx x) {
        double[] p = x.p;
        double across = Math.abs(x.n[0]) > 0.5 ? p[1] : p[0];
        double lv = 3.0 + lit(x) * 1.6 + (hash(fl(across * 2), 0, 121) - 0.5) * 1.0 + vnoise(p[2] * 0.3, across, 0, 122) * 0.8;
        if (Math.floorMod(fl(p[2]), 12) == 0) return pick(ST, 7 + lit(x) * 2); // iron rings
        return pick(HO, lv);
    }

    static double perim(Ctx x) {
        double cx = -26, cy = -8;
        double[] p = x.p;
        if (x.f == F.RIGHT) return p[1] - cy;
        if (x.f == F.TOP) return -(p[0] - cx) + 6;
        if (x.f == F.LEFT) return -(p[1] - cy) + 12;
        if (x.f == F.BOTTOM) return (p[0] - cx) + 18;
        return 0;
    }

    static int wrap(Ctx x) {
        double[] p = x.p;
        if (x.f == F.FRONT || x.f == F.BACK) return pick(LE, 1.5);
        int k = Math.floorMod(fl(p[2] * 0.75 + perim(x)), 5);
        double lv = k == 0 ? 0.6 : k == 1 ? 5.0 : 3.6 - (k - 2) * 0.6;
        lv += lit(x) * 1.0 + vnoise(p[0], p[1], p[2], 123) * 0.4;
        // runes burnt into the leather on both broad sides
        if (Math.abs(x.n[0]) > 0.5) {
            for (double zc : new double[]{-29, -21, -13}) {
                double dz = Math.abs(p[2] - zc), dy = Math.abs(p[1] + 8);
                if (dz + dy < 0.9) { G(x, GL[5], 255); return 0xff000000 | 0xff7a40; }
                if (Math.abs(dz + dy - 2) < 0.6 && !(dz < 0.6 && dy > 1.4)) { G(x, GL[3], 200); return 0xff000000 | CR[6]; }
            }
        }
        int st = stain(x, 0.15, 124);
        if (st != 0) return st;
        return pick(LE, lv);
    }

    static int socket(Ctx x) {
        double lv = steelLv(x);
        double[] p = x.p;
        if (vertical(x) || x.n[1] != 0) {
            if (!capFace(x) && (x.f != F.FRONT && x.f != F.BACK)) {
                double z = p[2];
                // glowing engraved rings
                if (Math.abs(z + 45.5) < 0.6 || Math.abs(z + 61.5) < 0.6 || Math.abs(z - 9.5) < 0.6) { G(x, GL[3], 210); return 0xff000000 | CR[4]; }
                // rivets
                double across = Math.abs(x.n[0]) > 0.5 ? p[1] + 8 : p[0] + 26;
                if (Math.abs(across) < 0.6 && (Math.floorMod(fl(z), 5) == 1)) return pick(ST, 9.5);
                if (Math.abs(across) < 0.6 && (Math.floorMod(fl(z), 5) == 2)) return pick(ST, 2);
            }
        }
        int bl = blood(x, 0.15, 125);
        if (bl != 0) return bl;
        return pick(ST, lv);
    }

    static final String[][] GLYPHS = {
            {"X.X.X", ".XXX.", "..X..", "..X..", "..X..", "..X..", "..X.."},
            {"..X..", ".X.X.", "X...X", ".X.X.", "..X..", ".X.X.", "X...X"},
            {"X....", "XX...", "X.X..", "X..X.", "X.X..", "XX...", "X...."},
    };

    static int glyph(int a, int b, int a0, int b0, int gi) {
        int ga = a - a0, gb = b - b0;
        if (ga < 0 || gb < 0 || gb >= 7 || ga >= 5) return 0;
        return GLYPHS[gi][gb].charAt(ga) == 'X' ? 1 : 0;
    }

    static int bladeRune(Ctx x) {
        if (!x.c.tag.equals("blade_body") || Math.abs(x.n[0]) < 0.5) return 0;
        int[] a0 = {2, 9, 15};
        for (int i = 0; i < 3; i++) if (glyph(x.a, x.b, a0[i], 1, i) == 1) return 1;
        for (int i = 0; i < 3; i++)
            for (int[] dd : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
                if (glyph(x.a + dd[0], x.b + dd[1], a0[i], 1, i) == 1) return 2;
        return 0;
    }

    static int blade(Ctx x) {
        double[] p = x.p;
        double g = clamp((p[1] + 5) / 28.0, 0, 1); // 0 at the spine, 1 at the edge
        double lv;
        if (Math.abs(x.n[0]) > 0.5) {
            lv = 2.4 + 3.2 * g + vnoise(p[2] * 0.22, p[1] * 0.22, 0, 131) * 1.2;
            int cx = fl((p[2] + 100) / 3), cy = fl((p[1] + 100) / 3);
            lv += (hash(cx, cy, 132) - 0.5) * 0.9; // hammer marks
            if (x.b == 0) lv += 1.4;
            if (x.b == x.fh - 1) lv -= 0.8;
            if (x.a == 0) lv += 0.6;
            int r = bladeRune(x);
            if (r == 1) { G(x, GL[4], 230); return 0xff000000 | CR[9]; }
            if (r == 2) { G(x, GL[2], 110); lv = Math.max(lv, 5.5); }
            if (x.c.tag.equals("blade_lower") && x.b == 2 && x.a > 2 && x.a < x.fw - 3) { G(x, GL[2], 150); return 0xff000000 | CR[7]; }
        } else if (x.n[1] < -0.5) lv = 5.4;
        else if (x.n[1] > 0.5) lv = 2.2;
        else lv = 3.6 + g * 2;
        int bl = blood(x, 0.3, 133);
        if (bl != 0 && x.c.tag.equals("blade_body")) return bl;
        return pick(CR, lv);
    }

    static int edge(Ctx x) {
        double[] p = x.p;
        boolean cutting = x.f == F.BOTTOM || (x.c.tag.equals("tip_front") && x.f == F.FRONT) || (x.c.tag.equals("tip_back") && x.f == F.BACK);
        double lv;
        if (cutting) {
            lv = 9.6;
            G(x, GL[3], 105);
        } else if (Math.abs(x.n[0]) > 0.5 && x.c.tag.startsWith("tip")) {
            double g = clamp((p[1] + 5) / 28.0, 0, 1);
            boolean outerCol = x.c.tag.equals("tip_front") ? (x.f == F.RIGHT ? x.a >= x.fw - 1 : x.a == 0) : (x.f == F.RIGHT ? x.a == 0 : x.a >= x.fw - 1);
            lv = outerCol ? 8.6 : 3.0 + 3.0 * g + vnoise(p[2] * 0.3, p[1] * 0.3, 0, 136) * 0.8 + (x.b == 0 ? 1.5 : 0);
            if (outerCol) G(x, GL[2], 80);
        } else if (Math.abs(x.n[0]) > 0.5) {
            lv = 7.2 + (x.b == x.fh - 1 ? 1.8 : 0) - (x.b == 0 ? 1.2 : 0) + vnoise(p[2] * 0.3, p[1] * 0.3, 0, 134) * 0.6;
            if (x.b == x.fh - 1 && !x.c.tag.startsWith("tip")) G(x, GL[2], 90);
            if (x.c.tag.equals("tip_front") && x.a >= x.fw - 2 && x.f == F.LEFT) G(x, GL[2], 70);
            if (x.c.tag.equals("tip_front") && x.a <= 1 && x.f == F.RIGHT) G(x, GL[2], 70);
            if (x.c.tag.equals("tip_back") && x.a <= 1 && x.f == F.LEFT) G(x, GL[2], 70);
            if (x.c.tag.equals("tip_back") && x.a >= x.fw - 2 && x.f == F.RIGHT) G(x, GL[2], 70);
        } else if (x.f == F.TOP) lv = 5.8;
        else lv = 6.6;
        if (x.c.tag.equals("edge") && Math.abs(x.n[0]) > 0.5) {
            int bl = blood(x, 0.2, 135);
            if (bl != 0) return bl;
        }
        return pick(CR, lv);
    }

    // ---------------- hooves ----------------
    static int hoof(Ctx x) {
        double[] p = x.p;
        double lv = 3.0 - 1.4 * tb(x) + (hash(fl(hc(x)), 0, 141) - 0.5) * 1.1;
        if (x.f == F.TOP) lv = 2.0;
        else if (x.f == F.BOTTOM) lv = 2.4 + (hash(fl(p[0]), fl(p[2]), 142) - 0.5);
        else {
            if (x.b == 0) lv = 5.2;
            if (x.b == x.fh - 1) lv = 0.8;
            if (x.f == F.FRONT && (x.a == 1) && x.b > 0 && x.b < x.fh - 1) lv += 2.2; // gloss
            if (x.f == F.FRONT && x.b >= x.fh - 3 && (x.a == 0 || x.a == x.fw - 1)) lv -= 1.2; // pointed toe
        }
        int bl = blood(x, 0.08, 143);
        if (bl != 0 && x.b < x.fh - 1) return bl;
        return pick(HF, lv);
    }

    // =====================================================================================
    public static void main(String[] args) throws Exception {
        String root = ".";
        String preview = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--preview")) preview = args[++i];
            else root = args[i];
        }
        build();
        pack();
        paintAll();
        String res = root + "/blood/src/main/resources/assets/hha_blood/";
        write(base, TW, TH, res + "textures/entity/minotaur.png");
        write(glow, TW, TH, res + "textures/entity/minotaur_glow.png");
        writeText(root + "/blood/src/main/kotlin/dev/henny/hha_blood/client/boss/MinotaurModelData.kt", kotlin());
        Item.build();
        write(Item.tb, Item.S, Item.S, res + "textures/item/minotaur_axe.png");
        writeText(res + "models/item/minotaur_axe.json", Item.json());
        writeText(res + "items/minotaur_axe.json", "{\n  \"model\": {\n    \"type\": \"minecraft:model\",\n    \"model\": \"hha_blood:item/minotaur_axe\"\n  }\n}\n");
        int used = 0;
        for (int o : owner) if (o != 0) used++;
        System.out.println("parts: " + PARTS.size() + ", cuboids: " + CUBES.size() + ", texture use: " + used + " / " + (TW * TH) + " px");
        if (preview != null) {
            new File(preview).mkdirs();
            sheetPreview(preview);
            Preview.run(preview);
        }
    }

    static void write(int[] px, int w, int h, String path) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < px.length; i++) img.setRGB(i % w, i / w, (px[i] >>> 24) == 0 ? 0 : px[i]);
        File f = new File(path);
        f.getParentFile().mkdirs();
        ImageIO.write(img, "png", f);
    }

    static void writeText(String path, String s) throws Exception {
        File f = new File(path);
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), s, StandardCharsets.UTF_8);
    }

    // =====================================================================================
    // Kotlin output
    static String ff(double v) {
        if (Math.abs(v) < 1e-9) v = 0;
        if (Math.abs(v - Math.rint(v)) < 1e-9) return (long) Math.rint(v) + "f";
        String s = String.format(Locale.ROOT, "%.5f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s + "f";
    }

    static String var(Part p) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char ch : p.name.toCharArray()) {
            if (ch == '_') { up = true; continue; }
            sb.append(up ? Character.toUpperCase(ch) : ch);
            up = false;
        }
        return sb.toString();
    }

    static String kotlin() {
        StringBuilder sb = new StringBuilder();
        sb.append("// GENERATED by tools/textures/GenMinotaur.java - do not edit\n");
        sb.append("package dev.henny.hha_blood.client.boss\n\n");
        sb.append("import net.minecraft.client.model.Dilation\n");
        sb.append("import net.minecraft.client.model.ModelData\n");
        sb.append("import net.minecraft.client.model.ModelPartBuilder\n");
        sb.append("import net.minecraft.client.model.ModelTransform\n");
        sb.append("import net.minecraft.client.model.TexturedModelData\n\n");
        sb.append("/**\n * Crimson Minotaur model. Units: half pixels (32 = 1 block), +Y down, hoof soles at y = 24, faces -Z.\n");
        sb.append(" * Texture 512x512 (minotaur.png + minotaur_glow.png). Bones (all rest rotations 0, absolute pivots):\n");
        for (Part p : PARTS) if (p.bone) sb.append(" *   ").append(p.name).append(" (").append(ff(p.ax)).append(", ").append(ff(p.ay)).append(", ").append(ff(p.az)).append(")\n");
        sb.append(" */\n");
        sb.append("object MinotaurModelData {\n");
        sb.append("    fun create(): TexturedModelData {\n");
        sb.append("        val data = ModelData()\n");
        sb.append("        val root = data.root\n");
        sb.append("        val none = Dilation(0f)\n");
        emit(sb, ROOT);
        sb.append("        return TexturedModelData.of(data, ").append(TW).append(", ").append(TH).append(")\n");
        sb.append("    }\n}\n");
        return sb.toString();
    }

    static void emit(StringBuilder sb, Part parent) {
        for (Part p : parent.kids) {
            String pv = parent == ROOT ? "root" : var(parent);
            sb.append("        ");
            if (!p.kids.isEmpty()) sb.append("val ").append(var(p)).append(" = ");
            sb.append(pv).append(".addChild(\n            \"").append(p.name).append("\",\n            ModelPartBuilder.create()");
            for (Cube c : p.cubes) {
                sb.append("\n                .uv(").append(c.u).append(", ").append(c.v).append(").cuboid(")
                        .append(ff(c.x)).append(", ").append(ff(c.y)).append(", ").append(ff(c.z)).append(", ")
                        .append(ff(c.w)).append(", ").append(ff(c.h)).append(", ").append(ff(c.d)).append(", none)");
            }
            sb.append(",\n            ModelTransform.of(").append(ff(p.ox)).append(", ").append(ff(p.oy)).append(", ").append(ff(p.oz)).append(", ")
                    .append(ff(p.pitch)).append(", ").append(ff(p.yaw)).append(", ").append(ff(p.roll)).append("),\n        )\n");
            emit(sb, p);
        }
    }

    // =====================================================================================
    // item model of the axe (block-model elements). Item px = entity units / 2; the item texture has 2 texels per px,
    // so every item texel is exactly one entity texel (copied from the entity texture).
    static class Item {
        static final int S = 128;
        static final int[] tb = new int[S * S], tg = new int[S * S];
        static final List<double[]> elements = new ArrayList<>(); // from xyz, to xyz
        static final List<int[][]> faceUv = new ArrayList<>(); // per element: 6 faces (down, up, north, south, west, east) -> {u, v, w, h} texels
        static final List<Cube> source = new ArrayList<>();
        static final String[] FACES = {"down", "up", "north", "south", "west", "east"};

        /** entity (x, y, z) -> item (x, y, z) */
        static double[] map(double ex, double ey, double ez) { return new double[]{8 + (ey + 8) / 2, 31 - (ez + 76) / 2, 8 + (ex + 26) / 2}; }

        /** item (x, y, z) -> entity (x, y, z) */
        static double[] unmap(double ix, double iy, double iz) { return new double[]{(iz - 8) * 2 - 26, (ix - 8) * 2 - 8, (31 - iy) * 2 - 76}; }

        static void build() {
            Part axe = BY_NAME.get("axe");
            int px = 0, py = 0, rowH = 0;
            for (Cube c : axe.cubes) {
                double ex0 = axe.ax + c.x, ey0 = axe.ay + c.y, ez0 = axe.az + c.z;
                double[] a = map(ex0, ey0, ez0), b = map(ex0 + c.w, ey0 + c.h, ez0 + c.d);
                double[] from = {Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2])};
                double[] to = {Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])};
                for (int i = 0; i < 3; i++) {
                    if (from[i] < -16 || to[i] > 32) throw new IllegalStateException("item element out of range: " + c.tag);
                    if (Math.abs((to[i] - from[i]) - Math.rint(to[i] - from[i])) > 1e-9) throw new IllegalStateException("item element size not integer: " + c.tag);
                }
                elements.add(new double[]{from[0], from[1], from[2], to[0], to[1], to[2]});
                source.add(c);
                int[][] uv = new int[6][];
                double sx = to[0] - from[0], sy = to[1] - from[1], sz = to[2] - from[2];
                double[][] dims = {{sx, sz}, {sx, sz}, {sx, sy}, {sx, sy}, {sz, sy}, {sz, sy}};
                for (int f = 0; f < 6; f++) uv[f] = new int[]{0, 0, (int) Math.rint(dims[f][0] * 2), (int) Math.rint(dims[f][1] * 2)};
                faceUv.add(uv);
            }
            // shelf packing, tallest rects first
            List<int[]> rects = new ArrayList<>();
            for (int[][] uv : faceUv) rects.addAll(Arrays.asList(uv));
            rects.sort((r1, r2) -> r2[3] != r1[3] ? r2[3] - r1[3] : r2[2] - r1[2]);
            boolean[] used = new boolean[S * S];
            for (int[] r : rects) {
                if (px + r[2] > S) { px = 0; py += rowH + 1; rowH = 0; }
                if (py + r[3] > S) throw new IllegalStateException("item texture full");
                r[0] = px; r[1] = py;
                for (int j = 0; j < r[3]; j++) for (int i = 0; i < r[2]; i++) {
                    if (used[(py + j) * S + px + i]) throw new IllegalStateException("item uv overlap");
                    used[(py + j) * S + px + i] = true;
                }
                px += r[2] + 1;
                rowH = Math.max(rowH, r[3]);
            }
            // fill: every item texel -> entity point on the matching entity face -> entity texel
            for (int e = 0; e < elements.size(); e++) {
                double[] el = elements.get(e);
                Cube c = source.get(e);
                Box bx = c.box();
                for (int f = 0; f < 6; f++) {
                    int[] r = faceUv.get(e)[f];
                    for (int j = 0; j < r[3]; j++) for (int i = 0; i < r[2]; i++) {
                        double[] ip = itemPoint(el, f, i, j);
                        double[] ep = unmap(ip[0], ip[1], ip[2]);
                        // entity face: down(-Y item) = +Z entity (BACK), up = FRONT, north(-Z item) = RIGHT(-X), south = LEFT, west(-X item) = TOP(-Y), east = BOTTOM
                        F ef = switch (f) { case 0 -> F.BACK; case 1 -> F.FRONT; case 2 -> F.RIGHT; case 3 -> F.LEFT; case 4 -> F.TOP; default -> F.BOTTOM; };
                        double lx = ep[0] - (BY_NAME.get("axe").ax + c.x), ly = ep[1] - (BY_NAME.get("axe").ay + c.y), lz = ep[2] - (BY_NAME.get("axe").az + c.z);
                        int cx = (int) clamp(Math.floor(lx), 0, c.w - 1), cy = (int) clamp(Math.floor(ly), 0, c.h - 1), cz = (int) clamp(Math.floor(lz), 0, c.d - 1);
                        switch (ef) {
                            case TOP -> cy = 0;
                            case BOTTOM -> cy = c.h - 1;
                            case RIGHT -> cx = 0;
                            case LEFT -> cx = c.w - 1;
                            case FRONT -> cz = 0;
                            case BACK -> cz = c.d - 1;
                        }
                        int[] q = bx.px(ef, cx, cy, cz);
                        int src = q[1] * TW + q[0];
                        int dst = (r[1] + j) * S + r[0] + i;
                        tb[dst] = base[src];
                        tg[dst] = glow[src];
                    }
                }
            }
        }

        /** centre of texel (i, j) of face f of element el, in item px, using the vanilla default UV orientation */
        static double[] itemPoint(double[] el, int f, int i, int j) {
            double fx = el[0], fy = el[1], fz = el[2], tx = el[3], ty = el[4], tz = el[5];
            double di = (i + 0.5) / 2, dj = (j + 0.5) / 2;
            return switch (f) {
                case 0 -> new double[]{fx + di, fy, tz - dj};       // down
                case 1 -> new double[]{fx + di, ty, fz + dj};       // up
                case 2 -> new double[]{tx - di, ty - dj, fz};       // north
                case 3 -> new double[]{fx + di, ty - dj, tz};       // south
                case 4 -> new double[]{fx, ty - dj, fz + di};       // west
                default -> new double[]{tx, ty - dj, tz - di};      // east
            };
        }

        static String n(double v) {
            if (Math.abs(v - Math.rint(v)) < 1e-9) return String.valueOf((long) Math.rint(v));
            String s = String.format(Locale.ROOT, "%.4f", v);
            while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
            return s;
        }

        static String arr(double... v) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < v.length; i++) sb.append(i > 0 ? ", " : "").append(n(v[i]));
            return sb.append("]").toString();
        }

        /** display transform: rotation (deg, XYZ) + scale, translation chosen so the model is centred in the slot */
        static String display(double rx, double ry, double rz, double scale, double dy) {
            double[] m = mul(rz(Math.toRadians(rz)), ident());
            m = mul(rx(Math.toRadians(rx)), mul(ry(Math.toRadians(ry)), rz(Math.toRadians(rz))));
            double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
            for (double[] el : elements) for (int k = 0; k < 8; k++) {
                double x = (k & 1) == 0 ? el[0] : el[3], y = (k & 2) == 0 ? el[1] : el[4], z = (k & 4) == 0 ? el[2] : el[5];
                double[] q = app(m, x - 8, y - 8, z - 8);
                minX = Math.min(minX, q[0] * scale); maxX = Math.max(maxX, q[0] * scale);
                minY = Math.min(minY, q[1] * scale); maxY = Math.max(maxY, q[1] * scale);
            }
            double tx = -(minX + maxX) / 2, ty = -(minY + maxY) / 2 + dy;
            return "{\"rotation\": " + arr(rx, ry, rz) + ", \"translation\": " + arr(Math.rint(tx * 100) / 100, Math.rint(ty * 100) / 100, 0) + ", \"scale\": " + arr(scale, scale, scale) + "}";
        }

        static String json() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n  \"gui_light\": \"front\",\n");
            sb.append("  \"textures\": {\n    \"axe\": \"hha_blood:item/minotaur_axe\",\n    \"particle\": \"hha_blood:item/minotaur_axe\"\n  },\n");
            sb.append("  \"elements\": [\n");
            for (int e = 0; e < elements.size(); e++) {
                double[] el = elements.get(e);
                sb.append("    {\"name\": \"").append(source.get(e).tag).append("\", \"from\": ").append(arr(el[0], el[1], el[2])).append(", \"to\": ").append(arr(el[3], el[4], el[5])).append(", \"faces\": {\n");
                for (int f = 0; f < 6; f++) {
                    int[] r = faceUv.get(e)[f];
                    double k = 16.0 / S;
                    sb.append("      \"").append(FACES[f]).append("\": {\"uv\": ").append(arr(r[0] * k, r[1] * k, (r[0] + r[2]) * k, (r[1] + r[3]) * k)).append(", \"texture\": \"#axe\"}").append(f < 5 ? ",\n" : "\n");
                }
                sb.append("    }}").append(e < elements.size() - 1 ? ",\n" : "\n");
            }
            sb.append("  ],\n");
            sb.append("  \"display\": {\n");
            sb.append("    \"thirdperson_righthand\": {\"rotation\": [0, -90, 0], \"translation\": [0, 7.5, 1], \"scale\": [0.55, 0.55, 0.55]},\n");
            sb.append("    \"thirdperson_lefthand\": {\"rotation\": [0, 90, 0], \"translation\": [0, 7.5, 1], \"scale\": [0.55, 0.55, 0.55]},\n");
            sb.append("    \"firstperson_righthand\": {\"rotation\": [0, -90, 20], \"translation\": [1.13, 6.5, 0.8], \"scale\": [0.45, 0.45, 0.45]},\n");
            sb.append("    \"firstperson_lefthand\": {\"rotation\": [0, 90, -20], \"translation\": [1.13, 6.5, 0.8], \"scale\": [0.45, 0.45, 0.45]},\n");
            sb.append("    \"ground\": ").append(display(0, 0, 0, 0.3, 0)).append(",\n");
            sb.append("    \"gui\": ").append(display(0, 0, -45, 0.42, 0)).append(",\n");
            sb.append("    \"fixed\": ").append(display(0, 180, 45, 0.5, 0)).append("\n");
            sb.append("  }\n}\n");
            return sb.toString();
        }
    }

    // =====================================================================================
    // previews
    static void sheetPreview(String dir) throws Exception {
        int s = 2;
        BufferedImage a = new BufferedImage(TW * s, TH * s, BufferedImage.TYPE_INT_RGB);
        BufferedImage g = new BufferedImage(TW * s, TH * s, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < TH * s; y++) for (int x = 0; x < TW * s; x++) {
            int bg = ((x / 16 + y / 16) % 2 == 0) ? 0x8a8a8a : 0x7a7a7a;
            int c = base[(y / s) * TW + x / s];
            a.setRGB(x, y, (c >>> 24) == 0 ? bg : c & 0xffffff);
            int gl = glow[(y / s) * TW + x / s];
            int col = (c >>> 24) == 0 ? 0x101010 : (c & 0xffffff);
            g.setRGB(x, y, (gl >>> 24) == 0 ? darken(col) : over(darken(col), gl));
        }
        ImageIO.write(a, "png", new File(dir, "sheet_2x.png"));
        ImageIO.write(g, "png", new File(dir, "sheet_glow_2x.png"));
        BufferedImage it = new BufferedImage(Item.S * 4, Item.S * 4, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < Item.S * 4; y++) for (int x = 0; x < Item.S * 4; x++) {
            int bg = ((x / 16 + y / 16) % 2 == 0) ? 0x8a8a8a : 0x7a7a7a;
            int c = Item.tb[(y / 4) * Item.S + x / 4];
            it.setRGB(x, y, (c >>> 24) == 0 ? bg : c & 0xffffff);
        }
        ImageIO.write(it, "png", new File(dir, "item_sheet_4x.png"));
    }

    static int darken(int c) { return (((c >> 16) & 255) / 3) << 16 | (((c >> 8) & 255) / 3) << 8 | ((c & 255) / 3); }

    static int over(int dst, int src) {
        int a = src >>> 24;
        int r = (((src >> 16) & 255) * a + ((dst >> 16) & 255) * (255 - a)) / 255;
        int gg = (((src >> 8) & 255) * a + ((dst >> 8) & 255) * (255 - a)) / 255;
        int b = ((src & 255) * a + (dst & 255) * (255 - a)) / 255;
        return r << 16 | gg << 8 | b;
    }

    /** Software renderer that rebuilds the model from the same part data with Minecraft's conventions. */
    static class Preview {
        /** quad: 4 vertices (world xyz) + uv per vertex + cube centre */
        record Quad(double[][] p, double[][] uv, double[] center, double[] rect, String part, int solid) {}

        static void collect(Part p, Map<String, double[]> pose, List<Quad> out) {
            double[] m = world(p, pose);
            for (Cube cb : p.cubes) {
                double u = cb.u, v = cb.v, x = cb.x, y = cb.y, z = cb.z, sx = cb.w, sy = cb.h, sz = cb.d;
                double f = x + sx, g = y + sy, h = z + sz;
                double[] v1 = {x, y, z}, v2 = {f, y, z}, v3 = {f, g, z}, v4 = {x, g, z}, v5 = {x, y, h}, v6 = {f, y, h}, v7 = {f, g, h}, v8 = {x, g, h};
                double j = u, k = u + sz, l = u + sz + sx, mm = u + sz + sx + sx, n = u + sz + sx + sz, o = u + sz + sx + sz + sx;
                double pp = v, q = v + sz, r = v + sz + sy;
                double[] cen = app(m, (x + f) / 2, (y + g) / 2, (z + h) / 2);
                Object[][] faces = {
                        {new double[][]{v6, v5, v1, v2}, k, pp, l, q},
                        {new double[][]{v3, v4, v8, v7}, l, q, mm, pp},
                        {new double[][]{v1, v5, v8, v4}, j, q, k, r},
                        {new double[][]{v2, v1, v4, v3}, k, q, l, r},
                        {new double[][]{v6, v2, v3, v7}, l, q, n, r},
                        {new double[][]{v5, v6, v7, v8}, n, q, o, r},
                };
                for (Object[] fa : faces) {
                    double[][] vs = (double[][]) fa[0];
                    double u1 = (double) fa[1], vv1 = (double) fa[2], u2 = (double) fa[3], vv2 = (double) fa[4];
                    double[][] uv = {{u2, vv1}, {u1, vv1}, {u1, vv2}, {u2, vv2}};
                    double[][] wp = new double[4][];
                    for (int i = 0; i < 4; i++) wp[i] = app(m, vs[i][0], vs[i][1], vs[i][2]);
                    out.add(new Quad(wp, uv, cen, new double[]{Math.min(u1, u2), Math.min(vv1, vv2), Math.max(u1, u2), Math.max(vv1, vv2)}, p.name, 0));
                }
            }
            for (Part k : p.kids) collect(k, pose, out);
        }

        static void ground(List<Quad> out) {
            double y = 24.02;
            for (int i = -6; i < 6; i++) for (int j = -8; j < 5; j++) {
                double x0 = i * 12, z0 = j * 12;
                int c = ((i + j) & 1) == 0 ? 0x3c4a3c : 0x344034;
                out.add(new Quad(new double[][]{{x0, y, z0}, {x0 + 12, y, z0}, {x0 + 12, y, z0 + 12}, {x0, y, z0 + 12}},
                        new double[][]{{0, 0}, {0, 0}, {0, 0}, {0, 0}}, new double[]{x0 + 6, y + 1, z0 + 6}, new double[]{0, 0, 0, 0}, "ground", c));
            }
        }

        static Set<String> subtree(String name) {
            Set<String> s = new HashSet<>();
            add(BY_NAME.get(name), s);
            return s;
        }

        static void add(Part p, Set<String> s) { s.add(p.name); for (Part k : p.kids) add(k, s); }

        static void run(String dir) throws Exception {
            Map<String, double[]> rest = new HashMap<>();
            Map<String, double[]> raised = new HashMap<>();
            raised.put("arm_right", new double[]{-2.6, 0, 0});
            raised.put("forearm_right", new double[]{-0.3, 0, 0});
            Map<String, double[]> chop = new HashMap<>();
            chop.put("arm_right", new double[]{-0.75, 0, 0});
            chop.put("forearm_right", new double[]{-0.5, 0, 0});
            chop.put("torso", new double[]{0.25, 0, 0});
            chop.put("jaw", new double[]{0.45, 0, 0});
            List<Quad> q0 = new ArrayList<>(), q1 = new ArrayList<>(), q2 = new ArrayList<>();
            collect(ROOT, rest, q0);
            collect(ROOT, raised, q1);
            collect(ROOT, chop, q2);
            ground(q0); ground(q1); ground(q2);
            int bg = 0x6e6e78;
            render(q0, 35, 14, dir + "/r3d_front34.png", bg, null, true);
            render(q0, 0, 4, dir + "/r3d_front.png", bg, null, true);
            render(q0, 90, 6, dir + "/r3d_side_left.png", bg, null, true);
            render(q0, -90, 6, dir + "/r3d_side_right.png", bg, null, true);
            render(q0, 200, 18, dir + "/r3d_back.png", bg, null, true);
            render(q0, 30, 12, dir + "/r3d_dark.png", 0x0c0809, null, true);
            render(q0, 28, 12, dir + "/r3d_head.png", bg, subtree("head"), true);
            render(q0, -70, 6, dir + "/r3d_head_side.png", bg, subtree("head"), true);
            render(q0, -90, 4, dir + "/r3d_axe_side.png", bg, subtree("axe"), true);
            render(q0, -35, 25, dir + "/r3d_axe_34.png", bg, subtree("axe"), true);
            render(q1, -60, 8, dir + "/r3d_raised_side.png", bg, null, true);
            render(q1, 30, 10, dir + "/r3d_raised_front34.png", bg, null, true);
            render(q2, -80, 8, dir + "/r3d_chop_side.png", bg, null, true);
            // item model
            List<Quad> iq = itemQuads();
            renderTex(iq, 180, 0, dir + "/item_south.png", bg, null, false, Item.tb, Item.tg, Item.S, Item.S);
            renderTex(iq, 35, 25, dir + "/item_34.png", bg, null, false, Item.tb, Item.tg, Item.S, Item.S);
        }

        static List<Quad> itemQuads() {
            List<Quad> out = new ArrayList<>();
            for (int e = 0; e < Item.elements.size(); e++) {
                double[] el = Item.elements.get(e);
                double[] cen = {(el[0] + el[3]) / 2, (el[1] + el[4]) / 2, (el[2] + el[5]) / 2};
                for (int f = 0; f < 6; f++) {
                    int[] r = Item.faceUv.get(e)[f];
                    // corners (i, j) = texel space corners of the face rect, mapped with the same orientation as itemPoint
                    double[][] corners = new double[4][];
                    double[][] uv = new double[4][];
                    int[][] cs = {{0, 0}, {r[2], 0}, {r[2], r[3]}, {0, r[3]}};
                    for (int c = 0; c < 4; c++) {
                        double[] pnt = itemPointCorner(el, f, cs[c][0], cs[c][1]);
                        // item space is Y-up; the renderer flips x and y like an entity, so pre-flip here
                        corners[c] = new double[]{-pnt[0], -pnt[1], pnt[2]};
                        uv[c] = new double[]{r[0] + cs[c][0], r[1] + cs[c][1]};
                    }
                    out.add(new Quad(corners, uv, new double[]{-cen[0], -cen[1], cen[2]}, new double[]{r[0], r[1], r[0] + r[2], r[1] + r[3]}, "item", 0));
                }
            }
            return out;
        }

        static double[] itemPointCorner(double[] el, int f, double i, double j) {
            double fx = el[0], fy = el[1], fz = el[2], tx = el[3], ty = el[4], tz = el[5];
            double di = i / 2, dj = j / 2;
            return switch (f) {
                case 0 -> new double[]{fx + di, fy, tz - dj};
                case 1 -> new double[]{fx + di, ty, fz + dj};
                case 2 -> new double[]{tx - di, ty - dj, fz};
                case 3 -> new double[]{fx + di, ty - dj, tz};
                case 4 -> new double[]{fx, ty - dj, fz + di};
                default -> new double[]{tx, ty - dj, tz - di};
            };
        }

        static void render(List<Quad> quads, double yawDeg, double pitchDeg, String file, int bg, Set<String> focus, boolean flip) throws Exception {
            renderTex(quads, yawDeg, pitchDeg, file, bg, focus, flip, base, glow, TW, TH);
        }

        static void renderTex(List<Quad> quads, double yawDeg, double pitchDeg, String file, int bg, Set<String> focus, boolean flip,
                              int[] tbase, int[] tglow, int W, int H) throws Exception {
            int IW = 1000, IH = 1000;
            double ya = Math.toRadians(yawDeg), pa = Math.toRadians(pitchDeg);
            java.util.function.Function<double[], double[]> view = p -> {
                double X = -p[0], Y = -p[1], Z = p[2];
                double x1 = X * Math.cos(ya) + Z * Math.sin(ya), z1 = -X * Math.sin(ya) + Z * Math.cos(ya);
                double y2 = Y * Math.cos(pa) + z1 * Math.sin(pa), z2 = z1 * Math.cos(pa) - Y * Math.sin(pa);
                return new double[]{x1, -y2, z2};
            };
            double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
            List<double[][]> vq = new ArrayList<>();
            for (Quad q : quads) {
                double[][] s = new double[4][];
                for (int i = 0; i < 4; i++) {
                    s[i] = view.apply(q.p[i]);
                    if (q.part.equals("ground") || (focus != null && !focus.contains(q.part))) continue;
                    minX = Math.min(minX, s[i][0]); maxX = Math.max(maxX, s[i][0]);
                    minY = Math.min(minY, s[i][1]); maxY = Math.max(maxY, s[i][1]);
                }
                vq.add(s);
            }
            double sc = Math.min((IW - 60) / (maxX - minX), (IH - 60) / (maxY - minY));
            double offX = IW / 2.0 - (minX + maxX) / 2 * sc, offY = IH / 2.0 - (minY + maxY) / 2 * sc;
            double[] zb = new double[IW * IH];
            Arrays.fill(zb, 1e18);
            int[] col = new int[IW * IH];
            Arrays.fill(col, bg);
            int[] qi = new int[IW * IH];
            Arrays.fill(qi, -1);
            double[][] lights = {norm(0.35, 1, -0.6), norm(-0.3, 0.6, 0.7)};
            for (int pass = 0; pass < 2; pass++) {
                for (int qn = 0; qn < quads.size(); qn++) {
                    Quad q = quads.get(qn);
                    if (pass == 1 && q.solid != 0) continue;
                    double[][] s = vq.get(qn);
                    double[][] sp = new double[4][];
                    for (int i = 0; i < 4; i++) sp[i] = new double[]{s[i][0] * sc + offX, s[i][1] * sc + offY, s[i][2]};
                    double[] a = q.p[0], b = q.p[1], c = q.p[2];
                    double[] e1 = {b[0] - a[0], b[1] - a[1], b[2] - a[2]}, e2 = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
                    double[] nn = {e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]};
                    double nl = Math.sqrt(nn[0] * nn[0] + nn[1] * nn[1] + nn[2] * nn[2]);
                    if (nl < 1e-9) continue;
                    double fcx = (q.p[0][0] + q.p[2][0]) / 2 - q.center[0], fcy = (q.p[0][1] + q.p[2][1]) / 2 - q.center[1], fcz = (q.p[0][2] + q.p[2][2]) / 2 - q.center[2];
                    if (nn[0] * fcx + nn[1] * fcy + nn[2] * fcz < 0) { nn[0] = -nn[0]; nn[1] = -nn[1]; nn[2] = -nn[2]; }
                    double[] wn = {-nn[0] / nl, -nn[1] / nl, nn[2] / nl};
                    double light = Math.min(1, 0.45 + 0.55 * (Math.max(0, dot(wn, lights[0])) + 0.5 * Math.max(0, dot(wn, lights[1]))));
                    final int fqn = qn;
                    final boolean first = pass == 0;
                    final int[] tex = pass == 0 ? tbase : tglow;
                    Frag frag = (x, y, d, u, v) -> {
                        int i = y * IW + x;
                        if (q.solid != 0) {
                            if (d >= zb[i]) return;
                            zb[i] = d; qi[i] = fqn; col[i] = q.solid;
                            return;
                        }
                        int t = tex[v * W + u];
                        int al = t >>> 24;
                        if (first) {
                            if (al < 26) return;
                            if (d >= zb[i]) return;
                            zb[i] = d;
                            qi[i] = fqn;
                            int r = (int) (((t >> 16) & 255) * light), g = (int) (((t >> 8) & 255) * light), bl = (int) ((t & 255) * light);
                            col[i] = r << 16 | g << 8 | bl;
                        } else {
                            if (al == 0 || qi[i] != fqn) return;
                            col[i] = add(col[i], t);
                        }
                    };
                    tri(sp[0], sp[1], sp[2], q.uv[0], q.uv[1], q.uv[2], q.rect, IW, IH, W, H, frag);
                    tri(sp[0], sp[2], sp[3], q.uv[0], q.uv[2], q.uv[3], q.rect, IW, IH, W, H, frag);
                }
            }
            BufferedImage img = new BufferedImage(IW, IH, BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, IW, IH, col, 0, IW);
            ImageIO.write(img, "png", new File(file));
        }

        /** additive like RenderLayers.eyes */
        static int add(int dst, int src) {
            int a = src >>> 24;
            int r = Math.min(255, ((dst >> 16) & 255) + ((src >> 16) & 255) * a / 255);
            int g = Math.min(255, ((dst >> 8) & 255) + ((src >> 8) & 255) * a / 255);
            int b = Math.min(255, (dst & 255) + (src & 255) * a / 255);
            return r << 16 | g << 8 | b;
        }

        interface Frag { void at(int x, int y, double depth, int u, int v); }

        static void tri(double[] a, double[] b, double[] c, double[] ta, double[] tb, double[] tc, double[] rect, int IW, int IH, int W, int H, Frag fr) {
            int x0 = (int) Math.max(0, Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
            int x1 = (int) Math.min(IW - 1, Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
            int y0 = (int) Math.max(0, Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
            int y1 = (int) Math.min(IH - 1, Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
            double area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
            if (Math.abs(area) < 1e-9) return;
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                double px = x + 0.5, py = y + 0.5;
                double w0 = ((b[0] - px) * (c[1] - py) - (b[1] - py) * (c[0] - px)) / area;
                double w1 = ((c[0] - px) * (a[1] - py) - (c[1] - py) * (a[0] - px)) / area;
                double w2 = 1 - w0 - w1;
                if (w0 < -1e-6 || w1 < -1e-6 || w2 < -1e-6) continue;
                double d = w0 * a[2] + w1 * b[2] + w2 * c[2];
                double u = w0 * ta[0] + w1 * tb[0] + w2 * tc[0], v = w0 * ta[1] + w1 * tb[1] + w2 * tc[1];
                int iu = (int) Math.floor(Math.max(rect[0], Math.min(rect[2] - 1e-4, u)));
                int iv = (int) Math.floor(Math.max(rect[1], Math.min(rect[3] - 1e-4, v)));
                if (iu < 0 || iv < 0 || iu >= W || iv >= H) continue;
                fr.at(x, y, d, iu, iv);
            }
        }

        static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    }
}
