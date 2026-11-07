import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Generator for the boss "Blood Cerberus - Der dreikoepfige Waechter", deterministic, JDK only.
 *
 * Single source of truth for geometry, UVs and textures. Writes:
 *  - textures/entity/cerberus.png                 512x512 skin
 *  - textures/entity/cerberus_glow.png            512x512 emissive layer (RenderLayers.eyes)
 *  - textures/entity/cerberus_glow_unleashed.png  512x512 emissive layer for phase 2
 *  - kotlin client/boss/CerberusModelData.kt      the TexturedModelData (bones, cuboids, UVs)
 *  - models/block/cerberus_head_{bleed,flame,howl}.json + textures/block/cerberus_head_*.png (64x64)
 *  - items/cerberus_head_*.json
 *
 * Usage (from the project root):
 *   java tools/textures/GenCerberus.java [projectRoot] [--preview <outDir>]
 *
 * Model units are half pixels (32 units = 1 block), +Y is down, the creature faces -Z, its right side is -X.
 * Every texel is painted from its 3D rest-pose position, so fur, veins and blood continue across faces and boxes.
 * UV layout = standard cuboid unwrap (Box.px, same as ModelPart.Cuboid); boxes are shelf-packed automatically.
 * The trophy blocks are scaled copies of the entity heads and copy their texels 1:1 (one texel per entity unit).
 */
public class GenCerberus {
    static final int W = 512, H = 512;
    /** skin, stage-1 glow, stage-2 (unleashed) glow, skin of a broken head */
    static final int[] base = new int[W * H], glow = new int[W * H], glowU = new int[W * H], broken = new int[W * H];

    // ---------------- palettes ----------------
    /** hide: black with a warm undertone (dark -> light) */
    static final int[] HIDE = {0x0d0a0a, 0x151010, 0x1d1616, 0x261c1c, 0x302323, 0x3b2a2a, 0x473232, 0x553b3a, 0x644544, 0x74504e};
    /** "points" in old blood: muzzle, chest blaze, socks (the rust markings of a Dobermann, in crimson) */
    static final int[] RUST = {0x1a0a0b, 0x260d0e, 0x341112, 0x441616, 0x561b19, 0x6a221d, 0x7e2a22, 0x943428, 0xa83f2f, 0xbc4c36};
    /** mane: near black, the tips turn crimson */
    static final int[] MANE = {0x0a0708, 0x110a0c, 0x190e11, 0x221216, 0x2d161b, 0x3c1a20, 0x521e24, 0x6c2328, 0x88292b, 0xa6322f};
    /** teeth */
    static final int[] IV = {0x4e4434, 0x7a6c52, 0xa6967a, 0xc8b998, 0xe2d6b8, 0xf6efdc};
    /** claws (dark bone) */
    static final int[] CL = {0x141012, 0x221c1f, 0x342c30, 0x4a4044, 0x645a5c, 0x857a7a};
    /** horns and spikes: dark bone that turns crimson towards the tip (like the Minotaur's horns) */
    static final int[] HO = {0x100b0c, 0x1a1315, 0x261c1f, 0x33262a, 0x433236, 0x574045};
    static final int[] HOT = {0x4a1012, 0x6a1616, 0x8c1e1a, 0xb02a1e, 0xd03c24};
    /** iron of the collars */
    static final int[] FE = {0x111114, 0x1a1a1f, 0x25252b, 0x313139, 0x3f3f48, 0x50505a, 0x65656f, 0x7e7e88};
    /** mouth */
    static final int[] MO = {0x160305, 0x26060a, 0x3a0b10, 0x521218, 0x6c1a22, 0x88262e, 0xa4363e};
    /** blood */
    static final int[] BL = {0x1c0204, 0x300509, 0x46080e, 0x5e0d14, 0x7a141c, 0x9c2028, 0xc4404a};
    static final int[] SCAR = {0x1e0e10, 0x4a2a2c, 0x6e4846, 0x92706a};
    static final int[] EARI = {0x1e0c0e, 0x2c1216, 0x3c1a1e, 0x4e2428};
    static final int[] ST = {0x120608, 0x1c0a0c, 0x281012, 0x341418, 0x42191e, 0x521f24};
    /** veins as painted on the skin under a stage-1 glow */
    static final int[] VB = {0x3e0806, 0x5c0c08, 0x82140a, 0xae240c, 0xd84012};

    // ---------------- glow palettes (dim -> hot) ----------------
    /** stage 1: crimson, like the Minotaur's runes */
    static final int[] CRIM = {0x7a0a06, 0xa8140a, 0xd2220e, 0xf03a16, 0xff5a24, 0xff8040, 0xffb070};
    /** stage 2: fire */
    static final int[] FIRE = {0xb01a08, 0xe0400e, 0xff6a14, 0xff9426, 0xffbc48, 0xffdc80, 0xfff2c0};
    /** the howler's cold violet (matches the purple warning ring of its howl) */
    static final int[] VIO = {0x3a1270, 0x5a1ea8, 0x7c34e0, 0x9a5cff, 0xbe90ff, 0xe2ccff, 0xf6f0ff};


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
        int uw() { return 2 * (d + w); }
        int vh() { return d + h; }
    }

    // =====================================================================================
    // geometry
    static final class Part {
        final String name;
        final Part parent;
        final double ax, ay, az; // absolute pivot in the rest pose
        final double pitch, yaw, roll;
        final boolean bone;
        /** length of a spike/horn/frond (for its colour gradient) */
        double len = 1;
        final List<Cub> cubes = new ArrayList<>();
        final List<Part> kids = new ArrayList<>();
        String head;
        Part headPart;
        double[] M;

        Part(String n, Part p, double x, double y, double z, double pi, double ya, double ro, boolean bone) {
            name = n; parent = p; ax = x; ay = y; az = z; pitch = pi; yaw = ya; roll = ro; this.bone = bone;
        }
        double ox() { return parent == null ? ax : ax - parent.ax; }
        double oy() { return parent == null ? ay : ay - parent.ay; }
        double oz() { return parent == null ? az : az - parent.az; }
    }

    static final class Cub {
        Part part;
        String mat, tag;
        double x, y, z;
        int w, h, d;
        Box box;
        int idx;
        FurSpec spec;
    }

    record FurSpec(double[] dir, double L, double W, int seed, double contrast, boolean fiery) {}

    static final List<Part> parts = new ArrayList<>();
    static final List<Cub> cubs = new ArrayList<>();
    static final Map<String, Part> byName = new LinkedHashMap<>();

    static Part part(String n, Part p, double x, double y, double z, double pi, double ya, double ro, boolean bone) {
        if (byName.containsKey(n)) throw new IllegalStateException("duplicate part " + n);
        Part q = new Part(n, p, x, y, z, pi, ya, ro, bone);
        if (p != null) { p.kids.add(q); q.head = p.head; q.headPart = p.headPart; }
        parts.add(q);
        byName.put(n, q);
        return q;
    }

    static Part bone(String n, Part p, double x, double y, double z) { return part(n, p, x, y, z, 0, 0, 0, true); }

    static Part deco(String n, Part p, double x, double y, double z, double pi, double ya, double ro) { return part(n, p, x, y, z, pi, ya, ro, false); }

    /** cuboid in part-local coordinates */
    static Cub lb(Part p, String mat, String tag, double x, double y, double z, int w, int h, int d) {
        Cub c = new Cub();
        c.part = p; c.mat = mat; c.tag = tag; c.x = x; c.y = y; c.z = z; c.w = w; c.h = h; c.d = d;
        c.idx = cubs.size();
        p.cubes.add(c);
        cubs.add(c);
        return c;
    }

    static int isz(double a) {
        int r = (int) Math.round(a);
        if (Math.abs(r - a) > 1e-9 || r <= 0) throw new IllegalStateException("non-integer box size " + a);
        return r;
    }

    /** cuboid from two corners relative to the pivot of an unrotated part */
    static Cub rb(Part p, String mat, String tag, double x0, double y0, double z0, double x1, double y1, double z1) {
        return lb(p, mat, tag, x0, y0, z0, isz(x1 - x0), isz(y1 - y0), isz(z1 - z0));
    }

    /** cuboid from two absolute corners (the part and its ancestors must be unrotated) */
    static Cub ab(Part p, String mat, String tag, double x0, double y0, double z0, double x1, double y1, double z1) {
        if (p.pitch != 0 || p.yaw != 0 || p.roll != 0) throw new IllegalStateException("ab on rotated part " + p.name);
        return rb(p, mat, tag, x0 - p.ax, y0 - p.ay, z0 - p.az, x1 - p.ax, y1 - p.ay, z1 - p.az);
    }


    /** the animation contract (CerberusRenderer looks the bones up by these names): bone, parent */
    static final String[][] CONTRACT = {
            {"body", null},
            {"neck_bleed", "body"}, {"head_bleed", "neck_bleed"}, {"jaw_bleed", "head_bleed"},
            {"neck_flame", "body"}, {"head_flame", "neck_flame"}, {"jaw_flame", "head_flame"},
            {"neck_howl", "body"}, {"head_howl", "neck_howl"}, {"jaw_howl", "head_howl"},
            {"leg_front_right", "body"}, {"leg_front_right_lower", "leg_front_right"},
            {"leg_front_left", "body"}, {"leg_front_left_lower", "leg_front_left"},
            {"leg_back_right", "body"}, {"leg_back_right_lower", "leg_back_right"},
            {"leg_back_left", "body"}, {"leg_back_left_lower", "leg_back_left"},
            {"tail", "body"}, {"tail2", "tail"}, {"tail3", "tail2"},
    };

    /** neck bases and head pivots (absolute, model units). index 0 bleed (right, -X), 1 flame (middle), 2 howl (left, +X) */
    static final double[][] NECK_BASE = {{-12, -40, -28}, {0, -42, -30}, {12, -40, -28}};
    static final double[][] HEAD_AT = {{-28, -54, -44}, {0, -62, -47}, {28, -54, -44}};
    static final String[] HEADS = {"bleed", "flame", "howl"};

    /** pitch, yaw (ModelPart order: Rz * Ry * Rx) that turn the local -Z axis from a towards b, and the length */
    static double[] aim(double[] a, double[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new double[]{Math.asin(dy / l), Math.atan2(-dx, -dz), l};
    }

    /** absolute point at local (x,y,z) of a frame at o rotated by pitch/yaw */
    static double[] at(double[] o, double pitch, double yaw, double x, double y, double z) {
        double[] q = app(mul(ry(yaw), rx(pitch)), x, y, z);
        return new double[]{o[0] + q[0], o[1] + q[1], o[2] + q[2]};
    }

    /** a tapering bone spike (two boxes) pointing along the local -Y axis of a fixed decoration part */
    static Part spike(String n, Part p, double x, double y, double z, double pi, double ya, double ro, int len, int th, String mat) {
        Part s = deco(n, p, x, y, z, pi, ya, ro);
        s.len = len;
        int hb = Math.max(2, len / 2);
        lb(s, mat, "spike", -th / 2.0, -hb, -th / 2.0, th, hb + 2, th);
        int tt = Math.max(1, th - 2);
        lb(s, mat, "spike_tip", -tt / 2.0, -len, -tt / 2.0, tt, len - hb + 1, tt);
        return s;
    }

    static void build() {
        Part body = bone("body", null, 0, -26, 0);
        // ---- torso: deep barrel chest, heavy shoulder hump, tucked waist, compact haunches
        ab(body, "fur", "chest", -17, -46, -36, 17, -10, -8);
        ab(body, "fur", "brisket", -12, -12, -35, 12, -2, -16);
        ab(body, "fur", "waist", -13, -43, -10, 13, -18, 14);
        ab(body, "fur", "haunch", -15, -45, 12, 15, -18, 34);
        ab(body, "mane", "hump", -19, -52, -34, 19, -38, -12);
        ab(body, "mane", "withers", -14, -49, -14, 14, -43, 0);
        // ---- mane fronds standing up and back out of the hump, a fringe hanging over the shoulders
        double[][] tufts = { // x, y, z, pitch, roll, width, length
                {-14, -50, -31, -0.85, -0.5, 6, 12}, {-5, -52, -31, -1.0, -0.15, 6, 14}, {5, -52, -31, -1.0, 0.15, 6, 14}, {14, -50, -31, -0.85, 0.5, 6, 12},
                {-11, -52, -23, -1.1, -0.3, 6, 15}, {0, -52, -23, -1.15, 0, 7, 17}, {11, -52, -23, -1.1, 0.3, 6, 15},
                {-7, -51, -15, -1.25, -0.2, 6, 13}, {7, -51, -15, -1.25, 0.2, 6, 13},
                {0, -49, -6, -1.35, 0, 6, 11},
                {-19, -44, -27, -0.35, -1.2, 5, 11}, {19, -44, -27, -0.35, 1.2, 5, 11},
                {-18, -41, -17, -0.25, -1.4, 5, 9}, {18, -41, -17, -0.25, 1.4, 5, 9},
        };
        for (int i = 0; i < tufts.length; i++) {
            double[] t = tufts[i];
            Part s = deco("mane_tuft" + i, body, t[0], t[1], t[2], t[3], 0, t[4]);
            int tw = (int) t[5], tl = (int) t[6];
            s.len = tl;
            lb(s, "mane", "tuft", -tw / 2.0, -tl, -1.5, tw, tl + 2, 3);
        }
        // ---- bone spikes along the spine and out of the shoulders
        double[][] ridge = {{2, -43, 10}, {9, -43, 12}, {16, -45, 13}, {23, -45, 11}, {30, -45, 8}};
        for (int i = 0; i < ridge.length; i++) spike("ridge_spike" + i, body, 0, ridge[i][1], ridge[i][0], -0.6, 0, 0, (int) ridge[i][2], 4, "spike");
        for (int s : new int[]{-1, 1}) {
            spike("shoulder_spike_" + side(s) + "0", body, 17 * s, -50, -26, -0.45, 0, 0.75 * s, 12, 4, "spike");
            spike("shoulder_spike_" + side(s) + "1", body, 17 * s, -49, -18, -0.7, 0, 0.6 * s, 9, 3, "spike");
        }
        // ---- three necks fanning out, each with an iron collar and a mane ruff behind the head
        for (int i = 0; i < 3; i++) neck(i, body);
        // ---- legs: heavy shoulders and forearms in front, digitigrade hind legs (thigh forward, shank back to the hock)
        for (int s : new int[]{-1, 1}) {
            String sd = side(s);
            double X = 15 * s;
            Part fu = bone("leg_front_" + sd, body, X, -30, -24);
            ab(fu, "leg", "shoulder", X - 8, -42, -34, X + 8, -16, -14);
            ab(fu, "leg", "arm", X - 6, -18, -30, X + 6, 4, -16);
            Part fl = bone("leg_front_" + sd + "_lower", fu, X, 2, -23);
            ab(fl, "leg", "forearm", X - 5, 0, -29, X + 5, 15, -18);
            ab(fl, "leg", "wrist", X - 4.5, 14, -30, X + 4.5, 19, -21);
            ab(fl, "paw", "paw", X - 7, 18, -38, X + 7, 24, -20);
            for (int c = 0; c < 4; c++) {
                double x0 = X - 7 + 0.75 + 3.5 * c;
                ab(fl, "claw", "claw", x0, 19, -42, x0 + 2, 24, -37);
            }
            spike("elbow_spur_" + sd, fl, X, 3, -18, -1.25, 0, 0, 8, 3, "spike");

            double HX = 14 * s;
            Part bu = bone("leg_back_" + sd, body, HX, -34, 22);
            Part th = deco("leg_back_" + sd + "_thigh", bu, HX, -34, 22, -0.37, 0, 0);
            lb(th, "leg", "thigh", -7.5, -6, -11, 15, 34, 22);
            Part gk = deco("leg_back_" + sd + "_shank", bu, HX, -8, 12, 0.75, 0, 0);
            lb(gk, "leg", "gaskin", -5, -3, -5, 10, 27, 10);
            Part bl = bone("leg_back_" + sd + "_lower", bu, HX, 10, 28);
            ab(bl, "leg", "meta", HX - 4, 8, 24, HX + 4, 20, 32);
            ab(bl, "paw", "paw", HX - 7, 18, 16, HX + 7, 24, 33);
            for (int c = 0; c < 4; c++) {
                double x0 = HX - 7 + 0.75 + 3.5 * c;
                ab(bl, "claw", "claw", x0, 19, 12, x0 + 2, 24, 17);
            }
            spike("hock_spur_" + sd, bl, HX, 11, 31, -1.2, 0, 0, 8, 3, "spike");
        }
        // ---- tail: thick root, held up and back, spiked, ends in a burning bone club
        double[] t0 = {0, -42, 32}, t1 = {0, -47, 45}, t2 = {0, -46, 57}, t3 = {0, -40, 67};
        Part tb1 = bone("tail", body, t0[0], t0[1], t0[2]);
        tailSeg("tail_fur1", tb1, t0, t1, 9, 3);
        Part tb2 = bone("tail2", tb1, t1[0], t1[1], t1[2]);
        tailSeg("tail_fur2", tb2, t1, t2, 7, 2);
        Part tb3 = bone("tail3", tb2, t2[0], t2[1], t2[2]);
        tailSeg("tail_fur3", tb3, t2, t3, 6, 2);
        double[] a3 = aim(t2, t3);
        Part club = deco("tail_club", tb3, t3[0], t3[1], t3[2], a3[0], a3[1], 0);
        lb(club, "tail", "club", -4.5, -4.5, -6, 9, 9, 10);
        spike("tail_spike_up", tb3, t3[0], t3[1] - 3, t3[2] + 1, -0.9, 0, 0, 9, 3, "spike");
        spike("tail_spike_r", tb3, t3[0] - 3, t3[1], t3[2] + 1, -1.2, 0, -1.0, 8, 3, "spike");
        spike("tail_spike_l", tb3, t3[0] + 3, t3[1], t3[2] + 1, -1.2, 0, 1.0, 8, 3, "spike");
        spike("tail_spike_end", tb3, t3[0], t3[1] + 1, t3[2] + 3, -1.75, 0, 0, 9, 3, "spike");
    }

    static String side(int s) { return s < 0 ? "right" : "left"; }

    static void tailSeg(String n, Part bone, double[] a, double[] b, int th, int over) {
        double[] r = aim(a, b);
        Part s = deco(n, bone, a[0], a[1], a[2], r[0], r[1], 0);
        int len = (int) Math.round(r[2]);
        lb(s, "tail", "tail", -th / 2.0, -th / 2.0, -(len + over), th, th, len + over + 1);
        // small spike on top of every segment
        double[] m = at(a, r[0], r[1], 0, -th / 2.0, -len * 0.5);
        spike(n + "_spike", bone, m[0], m[1], m[2], r[0] - 0.75, r[1], 0, Math.max(4, th - 2), 2, "spike");
    }

    static void neck(int i, Part body) {
        String id = HEADS[i];
        double[] base = NECK_BASE[i], head = HEAD_AT[i];
        Part n = bone("neck_" + id, body, base[0], base[1], base[2]);
        double[] r = aim(base, head);
        int len = (int) Math.round(r[2]);
        int th = i == 1 ? 16 : 14;
        Part s = deco("neck_" + id + "_fur", n, base[0], base[1], base[2], r[0], r[1], 0);
        lb(s, "neck", "neck", -th / 2.0, -th / 2.0, -(len + 3), th, th, len + 10);
        int rt = th + 6;
        lb(s, "mane", "ruff", -rt / 2.0, -rt / 2.0 - 1, -(len - 1), rt, rt, 8);
        int ct = th + 3;
        lb(s, "iron", "collar", -ct / 2.0, -ct / 2.0, -Math.round(len * 0.42) - 2, ct, ct, 5);
        // mane fronds along the top of the neck
        for (int k = 0; k < 2; k++) {
            double tt = 0.62 + 0.0 * k;
            double[] p = at(base, r[0], r[1], (k == 0 ? -3.5 : 3.5), -th / 2.0 + 1, -len * tt);
            Part f = deco("neck_" + id + "_frond" + k, n, p[0], p[1], p[2], r[0] - 0.45, r[1], (k == 0 ? -0.35 : 0.35));
            int fl = i == 2 ? 14 : 11;
            f.len = fl;
            lb(f, "mane", "tuft", -3, -fl, -1.5, 6, fl + 2, 3);
        }
        buildHead(id, n, head);
    }

    static void buildHead(String id, Part neck, double[] hp) {
        double hx = hp[0], hy = hp[1], hz = hp[2];
        Part h = bone("head_" + id, neck, hx, hy, hz);
        h.head = id;
        h.headPart = h;
        boolean fl = id.equals("flame"), ho = id.equals("howl"), bl = id.equals("bleed");
        int sw = fl ? 20 : 18;
        int L = fl ? 16 : bl ? 18 : 15; // muzzle length
        // skull, jowls/throat, muzzle with a raised bridge, nose
        rb(h, "face", "skull", -sw / 2.0, -10, -16, sw / 2.0, 8, 4);
        rb(h, "face", "throat", -(sw / 2.0 - 2), 8, -12, sw / 2.0 - 2, 13, 2);
        rb(h, "snout", "snout", -6, -5, -16 - L, 6, 3, -16);
        rb(h, "snout", "bridge", -4, -8, -14 - L, 4, -5, -14);
        rb(h, "nose", "nose", -2.5, -8, -17 - L, 2.5, -4, -13 - L);
        // upper fangs at the front corners of the muzzle
        int fh = bl ? 7 : ho ? 6 : 6;
        rb(h, "fang", "fang_up", -6, 3, -15 - L, -4, 3 + fh, -13 - L);
        rb(h, "fang", "fang_up", 4, 3, -15 - L, 6, 3 + fh, -13 - L);
        if (bl) { // the ripper: a second row of hooked teeth
            rb(h, "fang", "barb", -6, 3, -10 - L, -4, 7, -8 - L);
            rb(h, "fang", "barb", 4, 3, -10 - L, 6, 7, -8 - L);
            rb(h, "fang", "barb", -6, 3, -5 - L, -4, 6, -3 - L);
            rb(h, "fang", "barb", 4, 3, -5 - L, 6, 6, -3 - L);
        }
        // lower jaw hinged under the back of the skull
        Part j = bone("jaw_" + id, h, hx, hy + 3, hz - 12);
        int jw = ho ? 12 : bl ? 11 : 10;
        int jh = ho ? 6 : 5;
        rb(j, "jaw", "jaw", -jw / 2.0, 0, -4 - L, jw / 2.0, jh, 4);
        int lf = bl ? 5 : 4;
        rb(j, "fang", "fang_lo", -jw / 2.0 + 0.5, -lf + 1, -3 - L, -jw / 2.0 + 2.5, 1, -1 - L);
        rb(j, "fang", "fang_lo", jw / 2.0 - 2.5, -lf + 1, -3 - L, jw / 2.0 - 0.5, 1, -1 - L);
        if (bl) {
            rb(j, "fang", "barb_lo", -jw / 2.0 + 0.5, -2, 2 - L, -jw / 2.0 + 2.5, 1, 4 - L);
            rb(j, "fang", "barb_lo", jw / 2.0 - 2.5, -2, 2 - L, jw / 2.0 - 0.5, 1, 4 - L);
        }
        // angry brows: two slanted ridges forming a V over the eyes
        for (int s : new int[]{-1, 1}) {
            Part b = deco("head_" + id + "_brow_" + (s < 0 ? "r" : "l"), h, hx + 5 * s, hy - 10, hz - 15, 0, 0, -0.35 * s);
            lb(b, "face", "brow", -5.5, -2.5, -5, 11, 4, 6);
        }
        // fur jowls sweeping back from the cheeks
        for (int s : new int[]{-1, 1}) {
            Part e = deco("head_" + id + "_jowl_" + (s < 0 ? "r" : "l"), h, hx + (sw / 2.0 - 1) * s, hy + 1, hz - 9, 0, 0.45 * s, 0);
            lb(e, "face", "jowl", -1.5, -5, 0, 3, ho ? 12 : 10, ho ? 12 : 10);
        }
        // ears
        for (int s : new int[]{-1, 1}) {
            String en = "head_" + id + "_ear_" + (s < 0 ? "r" : "l");
            if (ho) {
                Part e = deco(en, h, hx + 6 * s, hy - 9, hz - 2, -0.3, 0, 0.3 * s);
                lb(e, "ear", "ear_base", -3, -10, -2, 6, 11, 4);
                lb(e, "ear", "ear_tip", -2, -19, -1.5, 4, 10, 3);
            } else if (bl && s < 0) { // torn
                Part e = deco(en, h, hx + 6 * s, hy - 9, hz - 2, -0.55, 0, 0.45 * s);
                lb(e, "ear", "ear_base", -2.5, -6, -1.5, 5, 7, 3);
            } else {
                Part e = deco(en, h, hx + 6.5 * s, hy - 9, hz - 2, -0.55, 0, 0.3 * s);
                lb(e, "ear", "ear_base", -2.5, -7, -1.5, 5, 8, 3);
                lb(e, "ear", "ear_tip", -1.5, -12, -1, 3, 6, 2);
            }
        }
        if (fl) {
            // two horns sweeping up and back over the skull + a small crest
            for (int s : new int[]{-1, 1}) {
                String hn = "head_flame_horn_" + (s < 0 ? "r" : "l");
                hornSeg(hn + "0", h, hx + 7 * s, hy - 9, hz - 9, -0.3, 10, 4);
                hornSeg(hn + "1", h, hx + 7 * s, hy - 17.5, hz - 6.5, -0.95, 9, 3);
                hornSeg(hn + "2", h, hx + 7 * s, hy - 22, hz + 0.5, -1.45, 8, 2);
            }
            for (int k = -1; k <= 1; k++) hornSeg("head_flame_crest" + (k + 1), h, hx + 2.5 * k, hy - 10, hz - 4 + 2 * Math.abs(k), -0.7, k == 0 ? 7 : 5, 2);
        }
        if (bl) {
            // jagged bone crest down the skull
            for (int k = 0; k < 3; k++) hornSeg("head_bleed_crest" + k, h, hx, hy - 10, hz - 10 + 5 * k, -0.8, 6 - k, 2);
        }
        if (ho) {
            // long spiky crest of fur between the ears
            for (int k = 0; k < 3; k++) {
                Part c = deco("head_howl_crest" + k, h, hx, hy - 10, hz - 9 + 4.5 * k, -0.85 - 0.15 * k, 0, 0);
                c.len = 11 - k;
                lb(c, "mane", "tuft", -2.5, -(11 - k), -1.5, 5, 13 - k, 3);
            }
        }
    }

    static void hornSeg(String n, Part h, double x, double y, double z, double pitch, int len, int th) {
        Part s = deco(n, h, x, y, z, pitch, 0, 0);
        s.len = len;
        lb(s, "horn", "horn", -th / 2.0, -len, -th / 2.0, th, len + 1, th);
    }


    // =====================================================================================
    // math
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
    static double[] dirOf(double[] m, double x, double y, double z) {
        return norm(m[0] * x + m[1] * y + m[2] * z, m[4] * x + m[5] * y + m[6] * z, m[8] * x + m[9] * y + m[10] * z);
    }
    /** inverse of a rigid transform applied to a point */
    static double[] invApp(double[] m, double[] p) {
        double x = p[0] - m[3], y = p[1] - m[7], z = p[2] - m[11];
        return new double[]{m[0] * x + m[4] * y + m[8] * z, m[1] * x + m[5] * y + m[9] * z, m[2] * x + m[6] * y + m[10] * z};
    }
    static double[] norm(double x, double y, double z) { double l = Math.sqrt(x * x + y * y + z * z); return new double[]{x / l, y / l, z / l}; }
    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static double[] cross(double[] a, double[] b) { return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
    static double clamp(double v, double a, double b) { return v < a ? a : v > b ? b : v; }

    /** rest (or posed) transforms, like ModelPart.rotate: translate(origin) * Rz * Ry * Rx */
    static void computeM(Map<String, double[]> pose) {
        for (Part p : parts) {
            double[] pr = parentM(p);
            double[] add = pose == null ? null : pose.get(p.name);
            double pi = p.pitch + (add == null ? 0 : add[0]), ya = p.yaw + (add == null ? 0 : add[1]), ro = p.roll + (add == null ? 0 : add[2]);
            p.M = mul(mul(pr, trans(p.ox(), p.oy(), p.oz())), mul(rz(ro), mul(ry(ya), rx(pi))));
        }
    }
    /** extra transform of the whole model (preview of drop/roll poses), model space */
    static double[] GLOBAL = null;

    static double[] parentM(Part p) { return p.parent == null ? (GLOBAL == null ? ident() : GLOBAL) : p.parent.M; }

    // =====================================================================================
    // noise
    static double hash(int a, int b, int c) {
        long h = a * 0x9E3779B97F4A7C15L ^ (b * 0xC2B2AE3D27D4EB4FL) ^ (c * 0x165667B19E3779F9L) ^ 0x5DEECE66DL;
        h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32; h *= 0x94D049BB133111EBL; h ^= h >>> 29;
        return ((h >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }
    static double hashL(long e, int seed) { return hash((int) e, (int) (e >>> 32), seed); }

    static double vnoise(double x, double y, double z, int seed) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double fx = x - ix, fy = y - iy, fz = z - iz;
        fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy); fz = fz * fz * (3 - 2 * fz);
        double r = 0;
        for (int dx = 0; dx < 2; dx++) for (int dy = 0; dy < 2; dy++) for (int dz = 0; dz < 2; dz++) {
            double w = (dx == 1 ? fx : 1 - fx) * (dy == 1 ? fy : 1 - fy) * (dz == 1 ? fz : 1 - fz);
            r += w * hash(ix + dx, iy + dy, (iz + dz) * 131 + seed * 7919);
        }
        return r;
    }
    static double fbm(double x, double y, double z, int seed) {
        return (vnoise(x, y, z, seed) * 0.62 + vnoise(x * 2.1 + 5.3, y * 2.1 + 1.7, z * 2.1 + 9.1, seed + 1) * 0.38);
    }

    static double vD12, vD13, vEh1, vEh2, vNx, vNy, vNz;

    /** 3D Voronoi: distance to the nearest cell wall (= crack), plus per-edge randoms */
    static void voronoi(double x, double y, double z, double cell, int seed) {
        double fx = x / cell, fy = y / cell, fz = z / cell;
        int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy), iz = (int) Math.floor(fz);
        double b1 = 1e9, b2 = 1e9, b3 = 1e9;
        double p1x = 0, p1y = 0, p1z = 0, p2x = 0, p2y = 0, p2z = 0, p3x = 0, p3y = 0, p3z = 0;
        long id1 = 0, id2 = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            int cx = ix + dx, cy = iy + dy, cz = iz + dz;
            double px = cx + 0.1 + 0.8 * hash(cx, cy, cz * 31 + seed), py = cy + 0.1 + 0.8 * hash(cx, cy, cz * 31 + seed + 1), pz = cz + 0.1 + 0.8 * hash(cx, cy, cz * 31 + seed + 2);
            double ex = px - fx, ey = py - fy, ez = pz - fz, d = ex * ex + ey * ey + ez * ez;
            long id = ((long) cx * 73856093L) ^ ((long) cy * 19349663L) ^ ((long) cz * 83492791L);
            if (d < b1) {
                b3 = b2; p3x = p2x; p3y = p2y; p3z = p2z;
                b2 = b1; p2x = p1x; p2y = p1y; p2z = p1z; id2 = id1;
                b1 = d; p1x = px; p1y = py; p1z = pz; id1 = id;
            } else if (d < b2) {
                b3 = b2; p3x = p2x; p3y = p2y; p3z = p2z;
                b2 = d; p2x = px; p2y = py; p2z = pz; id2 = id;
            } else if (d < b3) { b3 = d; p3x = px; p3y = py; p3z = pz; }
        }
        double l12 = Math.sqrt((p2x - p1x) * (p2x - p1x) + (p2y - p1y) * (p2y - p1y) + (p2z - p1z) * (p2z - p1z));
        double l13 = Math.sqrt((p3x - p1x) * (p3x - p1x) + (p3y - p1y) * (p3y - p1y) + (p3z - p1z) * (p3z - p1z));
        vD12 = (b2 - b1) / (2 * l12) * cell;
        vNx = (p2x - p1x) / l12; vNy = (p2y - p1y) / l12; vNz = (p2z - p1z) / l12;
        vD13 = (b3 - b1) / (2 * l13) * cell;
        long e = id1 < id2 ? id1 * 1000003L + id2 : id2 * 1000003L + id1;
        vEh1 = hashL(e, seed);
        vEh2 = hashL(e, seed + 5);
    }

    static boolean vHit, vNear;
    static double vHeat;
    static double[] vN = {0, 1, 0};

    static void hit(double hw, double st) {
        if (hw <= 0) return;
        // distance measured inside the face: walls nearly parallel to the face would smear into wide bands
        double dn = vNx * vN[0] + vNy * vN[1] + vNz * vN[2];
        double g = Math.sqrt(Math.max(0, 1 - dn * dn));
        if (g < 0.4) return;
        double dist = vD12 / g;
        if (dist < hw) {
            vHit = true;
            double core = 1 - dist / hw;
            vHeat = clamp(0.15 + 0.4 * core + 0.25 * vEh2 + (vD13 < hw * 2.4 ? 0.2 : 0) + 0.15 * st, 0, 1);
        } else if (dist < hw + 1.1) vNear = true;
    }

    /**
     * Crack lines along the walls of a 3D Voronoi pattern: thin, branching (some walls stay closed), only where a
     * low-frequency mask is active. [m] 0..1 = how cracked this area may be.
     */
    static void cracks(double[] P, double[] N, double m, double cell, int seed, double width) {
        vN = N;
        vHit = false; vNear = false; vHeat = 0;
        if (m <= 0) return;
        double act = fbm(P[0] / 26 + 3.1, P[1] / 26 + 7.7, P[2] / 26 + 1.3, seed);
        double st = clamp((act - (0.8 - 0.42 * m)) / 0.08, 0, 1);
        if (st <= 0) return;
        voronoi(P[0], P[1], P[2], cell, seed * 13 + 101);
        if (vEh1 < 0.32) return;
        double hw = width * (0.6 + 0.4 * st) * (0.75 + 0.5 * vEh1);
        hit(hw, st);
    }

    static int argb(int rgb, int a) { return (a << 24) | (rgb & 0xffffff); }

    static int pal(int[] p, double heat) { return p[(int) Math.round(clamp(heat, 0, 1) * (p.length - 1))]; }

    static double lum(int c) { return ((c >> 16) & 255) * 0.5 + ((c >> 8) & 255) * 0.4 + (c & 255) * 0.1; }

    static int maxGlow(int a, int b) {
        if (a == 0) return b;
        if (b == 0) return a;
        return lum(b) * (b >>> 24) > lum(a) * (a >>> 24) ? b : a;
    }

    static int scale(int c, double k) {
        int r = (int) clamp(((c >> 16) & 255) * k, 0, 255), g = (int) clamp(((c >> 8) & 255) * k, 0, 255), b = (int) clamp((c & 255) * k, 0, 255);
        return r << 16 | g << 8 | b;
    }

    static int mixc(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bb = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return r << 16 | g << 8 | bb;
    }

    // =====================================================================================
    // painting
    static final class Ctx {
        Cub cub;
        Part part;
        F f;
        int i, j, k;
        int fv, fh; // row on a side face and number of rows (-1 for top/bottom)
        double[] L, P, N, H;
        String head, mat, tag;
        FurSpec spec;
        int furIdx;
        boolean tooth, eye;
    }

    static int oBase, oGlow, oGlowU, oBroken;
    /** light from above, a little from the front and from the hound's right */
    static final double[] LDIR = norm(-0.25, -1, -0.42);

    static FurSpec specFor(Cub c) {
        double[] M = c.part.M;
        return switch (c.mat) {
            case "fur" -> new FurSpec(norm(0, 0.45, 1), 7, 4, 11, 0.45, false);
            case "mane" -> switch (c.tag) {
                case "tuft" -> new FurSpec(dirOf(M, 0, -1, 0), 9, 3, 13, 0.9, true);
                case "ruff" -> new FurSpec(dirOf(M, 0, 0.3, 1), 8, 3.5, 17, 0.9, true);
                case "jowl" -> new FurSpec(dirOf(M, 0, 0.2, 1), 7, 3, 23, 0.8, true);
                case "club" -> new FurSpec(dirOf(M, 0, 0, -1), 6, 3, 53, 0.8, true);
                default -> new FurSpec(norm(0, 0.35, 1), 9, 3.5, 13, 0.85, true);
            };
            case "neck" -> new FurSpec(dirOf(M, 0, 0, 1), 7, 4, 19, 0.45, false);
            case "face" -> new FurSpec(norm(0, 0.15, 1), 5, 3, 23, 0.35, false);
            case "snout" -> new FurSpec(norm(0, 0.05, 1), 4, 2.5, 29, 0.25, false);
            case "jaw" -> new FurSpec(norm(0, 0.35, 1), 4, 3, 31, 0.3, false);
            case "ear" -> new FurSpec(dirOf(M, 0, -1, 0), 4, 2.5, 37, 0.3, false);
            case "leg" -> new FurSpec(norm(0, 1, 0.12), 6, 3.5, 41, 0.4, false);
            case "paw" -> new FurSpec(norm(0, 0.6, -1), 4, 3, 43, 0.3, false);
            case "tail" -> new FurSpec(dirOf(M, 0, 0, 1), 6, 3.5, 47, 0.5, false);
            default -> new FurSpec(norm(0, 1, 0), 6, 3, 59, 0.4, false);
        };
    }

    static void paintAll() {
        computeM(null);
        for (Cub c : cubs) {
            c.spec = specFor(c);
            for (F f : F.values()) forFace(c, f, (ctx) -> {
                shade(ctx);
                int[] q = c.box.px(f, ctx.i, ctx.j, ctx.k);
                int idx = q[1] * W + q[0];
                base[idx] = 0xff000000 | (oBase & 0xffffff);
                glow[idx] = oGlow;
                glowU[idx] = oGlowU;
                broken[idx] = 0xff000000 | (oBroken & 0xffffff);
            });
        }
    }

    interface CellFn { void at(Ctx c); }

    static void forFace(Cub c, F f, CellFn fn) {
        int nu, nv;
        switch (f) {
            case TOP, BOTTOM -> { nu = c.w; nv = c.d; }
            case RIGHT, LEFT -> { nu = c.d; nv = c.h; }
            default -> { nu = c.w; nv = c.h; }
        }
        Part p = c.part;
        for (int b = 0; b < nv; b++) for (int a = 0; a < nu; a++) {
            Ctx x = new Ctx();
            x.cub = c; x.part = p; x.f = f; x.mat = c.mat; x.tag = c.tag; x.spec = c.spec; x.head = p.head;
            double lx, ly, lz;
            double[] n;
            switch (f) {
                case TOP -> { x.i = a; x.j = 0; x.k = b; lx = c.x + a + 0.5; ly = c.y; lz = c.z + b + 0.5; n = new double[]{0, -1, 0}; x.fh = -1; }
                case BOTTOM -> { x.i = a; x.j = c.h - 1; x.k = b; lx = c.x + a + 0.5; ly = c.y + c.h; lz = c.z + b + 0.5; n = new double[]{0, 1, 0}; x.fh = -1; }
                case RIGHT -> { x.i = 0; x.j = b; x.k = a; lx = c.x; ly = c.y + b + 0.5; lz = c.z + a + 0.5; n = new double[]{-1, 0, 0}; x.fv = b; x.fh = c.h; }
                case LEFT -> { x.i = c.w - 1; x.j = b; x.k = a; lx = c.x + c.w; ly = c.y + b + 0.5; lz = c.z + a + 0.5; n = new double[]{1, 0, 0}; x.fv = b; x.fh = c.h; }
                case FRONT -> { x.i = a; x.j = b; x.k = 0; lx = c.x + a + 0.5; ly = c.y + b + 0.5; lz = c.z; n = new double[]{0, 0, -1}; x.fv = b; x.fh = c.h; }
                default -> { x.i = a; x.j = b; x.k = c.d - 1; lx = c.x + a + 0.5; ly = c.y + b + 0.5; lz = c.z + c.d; n = new double[]{0, 0, 1}; x.fv = b; x.fh = c.h; }
            }
            x.L = new double[]{lx, ly, lz};
            x.P = app(p.M, lx, ly, lz);
            x.N = dirOf(p.M, n[0], n[1], n[2]);
            if (p.headPart != null) x.H = new double[]{x.P[0] - p.headPart.ax, x.P[1] - p.headPart.ay, x.P[2] - p.headPart.az};
            fn.at(x);
        }
    }

    static void shade(Ctx c) {
        oGlow = 0; oGlowU = 0; c.tooth = false; c.eye = false;
        switch (c.mat) {
            case "fang" -> fang(c);
            case "claw" -> claw(c);
            case "horn", "spike" -> horn(c);
            case "iron" -> iron(c);
            case "nose" -> nose(c);
            default -> hide(c);
        }
        if (c.head != null) headDetails(c);
        oBroken = brokenCol(c);
    }

    static double lockT;
    static boolean lockIn;

    /** tapered fur locks in staggered rows; a runs along the fur (root -> tip), b across */
    static double furLock(double a, double b, double L, double Wd, int seed) {
        double ra = a / L;
        int r = (int) Math.floor(ra);
        double rb = b / Wd + ((r & 1) == 1 ? 0.5 : 0) + (hash(r, seed, 3) - 0.5) * 0.3;
        int col = (int) Math.floor(rb);
        double bb = rb - col - 0.5;
        double t = ra - r;
        double hv = hash(col, r, seed);
        double half = 0.5 * (1 - 0.78 * t);
        lockT = t;
        if (Math.abs(bb) <= half) {
            lockIn = true;
            double v = -0.35 + 1.9 * t - 0.9 * Math.abs(bb) / 0.5 + (hv - 0.5) * 0.8;
            if (bb < -0.06 && bb > -0.32 && t > 0.25 && t < 0.9) v += 0.65; // strand highlight
            return v * 0.6;
        }
        lockIn = false;
        return (-1.0 + 0.4 * t) * 0.6;
    }

    static double[] tangent(double[] D, double[] N) {
        double dn = dot(D, N);
        double[] T = {D[0] - dn * N[0], D[1] - dn * N[1], D[2] - dn * N[2]};
        double l = Math.sqrt(dot(T, T));
        if (l < 0.35) {
            double[] Y = {0, 1, 0};
            double yn = dot(Y, N);
            T = new double[]{-yn * N[0], 1 - yn * N[1], -yn * N[2]};
            l = Math.sqrt(dot(T, T));
            if (l < 0.35) {
                double zn = N[2];
                T = new double[]{-zn * N[0], -zn * N[1], 1 - zn * N[2]};
                l = Math.sqrt(dot(T, T));
            }
        }
        return new double[]{T[0] / l, T[1] / l, T[2] / l};
    }

    /** baked light, ramp index units (0..9): key light, height gradient, rim on the upper edge, occlusion below */
    static double light(Ctx c) {
        double lam = Math.max(0, dot(c.N, LDIR));
        double v = 3.0 + 3.3 * lam;
        double hgt = clamp((c.P[1] + 56) / 80, 0, 1);
        v += 1.0 - 2.1 * hgt;
        if (c.N[1] > 0.6) v -= 1.4;
        if (c.fh > 0) {
            if (c.fv == 0) v += 0.9;
            else if (c.fv == 1 && c.fh > 6) v += 0.35;
            if (c.fv == c.fh - 1 && c.fh > 3) v -= 0.8;
        }
        if (c.head != null) v += 0.6;
        return v;
    }

    static double dither(Ctx c) {
        int a = c.cub == null ? (int) Math.floor(c.P[0] * 2) : c.cub.idx * 7 + c.f.ordinal();
        return hash(a, c.i * 131 + c.j * 7 + (int) Math.floor(c.P[1] * 3), c.k * 17 + (int) Math.floor(c.P[2] * 3)) - 0.5;
    }

    /** muscle groups: ribs over the chest, bulging shoulders/thighs with a crease around them */
    static double muscle(Ctx c) {
        if (c.cub == null) return 0;
        boolean side = c.f == F.RIGHT || c.f == F.LEFT;
        Cub b = c.cub;
        switch (c.tag) {
            case "chest" -> {
                if (side && c.P[2] > -33 && c.P[1] > -40 && c.P[1] < -13) {
                    int r = Math.floorMod((int) Math.floor(c.P[2] * 0.75 - c.P[1] * 0.4), 6);
                    if (r == 0) return -1.1;
                    if (r == 1) return 0.5;
                }
                if (c.f == F.FRONT) { // pectorals: two bulges with a dark split
                    if (Math.abs(c.P[0]) < 1) return -1.0;
                    double a = (Math.abs(c.P[0]) - 8) / 8, v = (c.P[1] + 30) / 12;
                    double r = a * a + v * v;
                    return r < 1 ? 0.8 * (1 - r) : (r < 1.35 ? -0.6 : 0);
                }
            }
            case "shoulder", "thigh", "haunch" -> {
                if (side) {
                    double a = (c.k + 0.5) / b.d, v = (c.j + 0.5) / b.h;
                    double ca = c.tag.equals("haunch") ? 0.55 : 0.45, cv = c.tag.equals("thigh") ? 0.32 : 0.4;
                    double r = Math.sqrt(Math.pow((a - ca) / 0.42, 2) + Math.pow((v - cv) / 0.42, 2));
                    if (r < 1) return 0.8 * (1 - r * r);
                    if (r < 1.2) return -0.45;
                }
            }
            case "arm", "forearm", "gaskin" -> {
                if (side) {
                    double a = (c.k + 0.5) / b.d;
                    if (a > 0.78) return -0.6; // back of the leg in shadow
                    if (a > 0.2 && a < 0.45) return 0.45; // tendon highlight
                }
            }
            case "waist" -> {
                if (side) {
                    double v = (c.j + 0.5) / b.h;
                    if (v > 0.75) return -0.7; // tucked belly
                }
            }
            default -> { }
        }
        return 0;
    }

    /** where the crimson "points" are: muzzle, throat, chest blaze, socks */
    static double rustW(Ctx c) {
        String t = c.tag;
        if (c.head != null) {
            if (t.equals("snout")) return c.f == F.TOP ? 0 : (c.H[1] > -2.5 ? 1 : 0);
            if (t.equals("jaw") || t.equals("throat")) return 1;
            return 0;
        }
        switch (t) {
            case "chest", "brisket" -> {
                if (c.N[2] < -0.7) {
                    double halfW = 2 + (c.P[1] + 38) * 0.32;
                    return Math.abs(c.P[0]) < halfW && c.P[1] > -38 ? 1 : 0;
                }
                if (t.equals("brisket") && c.N[1] > 0.5) return 1;
                return 0;
            }
            case "forearm", "wrist", "meta", "paw" -> {
                if (c.f == F.BOTTOM) return 0;
                return c.P[1] > 9 + 2.5 * hash((int) Math.floor((c.f == F.RIGHT || c.f == F.LEFT) ? c.P[2] : c.P[0]), 5, 61) ? 1 : 0;
            }
            default -> { return 0; }
        }
    }

    /** how cracked/veined an area is: stage 1 only the molten throat and a few veins, stage 2 the whole body */
    static double crackMask(Ctx c, boolean unl) {
        String t = c.tag;
        String pn = c.part.name;
        if (c.mat.equals("mane") || c.mat.equals("ear") || c.mat.equals("paw")) return 0;
        if (!unl) {
            if ("flame".equals(c.head)) return t.equals("throat") ? 1 : t.equals("jaw") && c.f == F.BOTTOM ? 0.8 : 0;
            if (c.head != null) return 0;
            if (pn.startsWith("neck_flame")) return c.N[1] > 0.2 ? 1 : 0.15;
            return 0;
        }
        if (c.head != null) return "flame".equals(c.head) ? (t.equals("snout") && c.f == F.TOP ? 0 : 0.8) : t.equals("throat") ? 0.6 : 0;
        double low = clamp((c.P[1] - 2) / 14, 0, 1); // fades out towards the paws
        double m0 = switch (t) {
            case "chest", "shoulder", "haunch", "thigh", "neck" -> 1.0;
            case "waist", "arm", "gaskin", "brisket", "tail" -> 0.8;
            case "forearm", "meta" -> 0.55;
            default -> 0;
        };
        return m0 * (1 - low);
    }

    static void hide(Ctx c) {
        FurSpec s = c.spec;
        boolean mane = c.mat.equals("mane");
        double[] T = tangent(s.dir(), c.N);
        double[] B = cross(c.N, T);
        double fl = furLock(dot(c.P, T), dot(c.P, B), s.L(), s.W(), s.seed());
        boolean in = lockIn;
        double lt = lockT;
        double v = light(c) + fl * s.contrast() + muscle(c) + dither(c) * 0.4;
        int idx = (int) Math.round(clamp(v, 0, 9));
        c.furIdx = idx;
        int col;
        if (mane) {
            if (c.tag.equals("tuft")) {
                // a frond: dark root, crimson towards the tip, dark edges so neighbouring fronds separate
                double t = clamp(-c.L[1] / Math.max(1, c.part.len), 0, 1);
                double mv = v - 0.8 + 2.6 * Math.max(0, t - 0.4);
                boolean edge = c.f != F.TOP && c.f != F.BOTTOM && ((c.f == F.FRONT || c.f == F.BACK) ? (c.i == 0 || c.i == c.cub.w - 1) : (c.k == 0 || c.k == c.cub.d - 1));
                if (edge) mv -= 1.4;
                if ((c.f == F.FRONT || c.f == F.BACK) && c.i == c.cub.w / 2 && t > 0.2) mv += 0.6; // centre strand
                col = MANE[(int) Math.round(clamp(mv, 0, 9))];
                lt = t; in = !edge;
            } else {
                int mi = idx;
                if (in && lt > 0.7) mi = Math.min(9, idx + (int) Math.round((lt - 0.7) / 0.3 * 2)); // crimson tips
                col = MANE[Math.max(0, mi - 1)];
            }
        } else {
            double rw = rustW(c);
            boolean rust = rw >= 1 || (rw > 0 && hash(c.i * 3 + c.k, c.j, 77) < rw);
            col = rust ? RUST[Math.min(9, idx + 1)] : HIDE[idx];
        }
        if (c.mat.equals("paw")) col = pawDetail(c, col);
        oBase = col;
        if (c.part == null) return; // trophy stump
        // stage 1: a few smouldering veins
        double m1 = crackMask(c, false);
        if (m1 > 0) {
            cracks(c.P, c.N, m1, 11, 5, 0.55);
            if (vHit) { oBase = pal(VB, vHeat); oGlow = argb(pal(CRIM, vHeat), (int) (160 + 95 * vHeat)); }
            else if (vNear) oBase = mixc(oBase, 0x3a0c0a, 0.45);
        }
        // stage 2: the whole hide splits into glowing cracks, the mane burns at the tips
        double m2 = crackMask(c, true);
        if (m2 > 0) {
            cracks(c.P, c.N, m2, 13, 9, 0.7);
            if (vHit) oGlowU = argb(pal(FIRE, 0.15 + 0.85 * vHeat), 255);
            else if (vNear) oGlowU = argb(0xc0300c, 60);
        }
        brand(c);
        if (oGlow != 0) oGlowU = maxGlow(oGlowU, argb(pal(FIRE, 0.4), 255));
        if (s.fiery() && in && lt > 0.6) {
            double q = (lt - 0.6) / 0.4;
            oGlowU = maxGlow(oGlowU, argb(pal(FIRE, 0.1 + 0.9 * q), (int) (110 + 145 * q)));
        }
    }

    static final String[] BRAND = {
            "x..x..x",
            "x..x..x",
            "x.xxx.x",
            ".xx.xx.",
            "..xxx..",
            "...x...",
            "...x...",
            "..xxx..",
    };

    /** the brand of Hades burnt into the chest blaze: a three-pronged sigil, one prong per head */
    static void brand(Ctx c) {
        if (!c.tag.equals("chest") || c.f != F.FRONT) return;
        int col = (int) Math.floor(c.P[0] + 3.5), row = (int) Math.floor(c.P[1] + 35);
        if (row < 0 || row >= BRAND.length || col < 0 || col >= 7 || BRAND[row].charAt(col) != 'x') return;
        boolean core = col == 3;
        oBase = core ? 0x8a1a0c : 0x5c0c08;
        oGlow = argb(core ? CRIM[5] : CRIM[3], 255);
        oGlowU = argb(core ? FIRE[6] : FIRE[4], 255);
    }

    static int pawDetail(Ctx c, int col) {
        Cub b = c.cub;
        if (b == null) return col;
        // toe gaps line up with the claws (claw centres at 1.75 + 3.5 i)
        boolean gap = c.i == 3 || c.i == 7 || c.i == 10;
        if (gap && c.f == F.FRONT && c.j >= 1) return RUST[0];
        if (gap && c.f == F.TOP && c.k < 6) return RUST[1];
        if (c.f == F.BOTTOM) {
            int cx = c.i, cz = c.k;
            boolean pad = (cz >= 2 && cz <= 4 && cx % 4 != 3) || (cz >= 7 && cz <= 12 && cx >= 3 && cx <= 10);
            return pad ? 0x1a1214 : HIDE[1];
        }
        return col;
    }

    static void fang(Ctx c) {
        int lv = switch (c.f) { case FRONT -> 4; case RIGHT, LEFT -> 3; case BACK -> 2; case TOP -> 3; default -> 2; };
        if (c.f == F.FRONT && c.i == 0) lv++;
        boolean up = !c.tag.endsWith("_lo");
        int fromTip = up ? (c.cub.h - 1 - c.j) : c.j; // 0 at the tip
        if (fromTip == 0 && c.cub.h > 2) lv = Math.min(5, lv + 1);
        int root = up ? c.j : c.cub.h - 1 - c.j;
        if (root == 0 && c.f != F.TOP && c.f != F.BOTTOM && c.cub.h > 3) lv -= 2;
        oBase = IV[Math.max(0, Math.min(IV.length - 1, lv))];
        c.tooth = true;
    }

    static void claw(Ctx c) {
        // hooked: light on top, the tip at the front-bottom, dark underneath
        int lv = switch (c.f) { case TOP -> 4; case FRONT -> 3; case RIGHT -> 3; case LEFT -> 2; case BACK -> 1; default -> 1; };
        if (c.fh > 0 && c.fv == c.fh - 1) lv = Math.max(0, lv - 2);
        if (c.f == F.TOP && c.k == 0) lv = 5;
        if (c.f == F.FRONT && c.j == 0) lv = 5;
        oBase = CL[lv];
    }

    static void horn(Ctx c) {
        double len = Math.max(1, c.part.len);
        double t = clamp(-c.L[1] / len, 0, 1); // 0 at the base, 1 at the tip
        int lv = switch (c.f) { case TOP -> 5; case FRONT -> 4; case RIGHT -> 4; case LEFT -> 2; case BACK -> 2; default -> 1; };
        int along = (int) Math.floor(-c.L[1]);
        if (Math.floorMod(along, 3) == 0 && t < 0.6) lv -= 1; // growth rings
        int col = HO[Math.max(0, Math.min(HO.length - 1, lv))];
        if (t > 0.55) col = HOT[(int) Math.round(clamp((t - 0.55) / 0.45 * (HOT.length - 1) + (lv - 3) * 0.4, 0, HOT.length - 1))];
        oBase = col;
        if ("flame".equals(c.head) && c.mat.equals("horn") && t > 0.7) {
            double q = (t - 0.7) / 0.3;
            oGlow = argb(pal(CRIM, 0.3 + 0.6 * q), (int) (120 + 135 * q));
        }
        if (t > 0.7) oGlowU = argb(pal(FIRE, 0.2 + 0.8 * (t - 0.7) / 0.3), (int) (150 + 105 * (t - 0.7) / 0.3));
    }

    static void iron(Ctx c) {
        int lv = switch (c.f) { case TOP -> 5; case FRONT, BACK -> 3; case RIGHT -> 4; case LEFT -> 3; default -> 1; };
        // the collar is a ring: its outer faces are TOP/BOTTOM/RIGHT/LEFT, k runs along the neck
        int along = c.k, d = c.cub.d;
        if (along == 0 || along == d - 1) lv += 1; // bevelled rims
        else if (along == 1 || along == d - 2) lv -= 1;
        lv += (int) Math.round(dither(c) * 0.8);
        oBase = FE[Math.max(0, Math.min(FE.length - 1, lv))];
        if (c.f == F.FRONT || c.f == F.BACK) return;
        int across = (c.f == F.TOP || c.f == F.BOTTOM) ? c.i : c.j;
        int n = (c.f == F.TOP || c.f == F.BOTTOM) ? c.cub.w : c.cub.h;
        // rune slots: a glowing mark in the middle of every face
        int mid = n / 2;
        if (along == d / 2 && Math.abs(across - mid) <= 2 || (along == d / 2 - 1 || along == d / 2 + 1) && across == mid) {
            oBase = 0x3a0806;
            oGlow = argb(across == mid ? 0xff6a30 : 0xd2220e, 235);
            oGlowU = argb(across == mid ? 0xffe0a0 : 0xff8a2c, 255);
        }
        // spiked studs at the corners
        if ((across == 1 || across == n - 2) && along == d / 2) oBase = FE[7];
    }

    static void nose(Ctx c) {
        int w = c.cub.w;
        oBase = switch (c.f) { case TOP -> 0x2a2226; case FRONT -> 0x120e10; case RIGHT -> 0x181316; default -> 0x0a0809; };
        if (c.f == F.TOP && c.i == 1 && c.k < 3) oBase = 0x5a4a52; // wet shine
        if (c.f == F.FRONT) {
            if (c.j == 0 && c.i <= 2) oBase = 0x3a3036;
            boolean nostril = c.j >= 2 && c.j <= 3 && (c.i == 1 || c.i == w - 2);
            if (nostril) {
                oBase = 0x050304;
                if ("flame".equals(c.head)) { oBase = 0x3a0c06; oGlow = argb(0xd2220e, 150); oGlowU = argb(0xffbc48, 255); }
            }
        }
    }

    // ---------------- head details ----------------
    /** eye sprite: columns inner -> outer, slanted down to the nose (angry) */
    static final String[] EYE = {"..ro", "rywo", "ro.."};

    static int eyeColIn(String id) { return id.equals("flame") ? 6 : 5; }

    static final int EYE_TOP = -8;

    static char eyeChar(String id, double hx, double hy) {
        int col = (int) Math.floor(Math.abs(hx)) - eyeColIn(id), row = (int) Math.floor(hy) - EYE_TOP;
        if (row < 0 || row >= EYE.length || col < 0 || col >= EYE[0].length()) return '.';
        return EYE[row].charAt(col);
    }

    static int[] eyePal(String id) {
        return switch (id) {
            case "bleed" -> new int[]{0xa0100a, 0xe0200e, 0xff4a2a, 0xffb0a0};
            case "howl" -> new int[]{0x5a1ea8, 0x8a48f0, 0xc0a0ff, 0xf4eeff};
            default -> new int[]{0xc0280a, 0xff6a14, 0xffc040, 0xfff2c8};
        };
    }

    static void eyePx(Ctx c, char ch) {
        int[] p = eyePal(c.head);
        int k = switch (ch) { case 'r' -> 0; case 'o' -> 1; case 'y' -> 2; default -> 3; };
        // dark on the skin: the light comes from the glow layer, so a broken head's eyes simply go out
        oBase = new int[]{0x2a0606, 0x3a0a08, 0x4a0e0a, 0x5a140e}[k];
        oGlow = argb(p[k], 255);
        oGlowU = argb(c.head.equals("flame") ? p[Math.min(3, k + 1)] : p[Math.min(3, k + 1)], 255);
        c.eye = true;
    }

    static void headDetails(Ctx c) {
        String id = c.head;
        boolean bl = id.equals("bleed"), ho = id.equals("howl"), fl = id.equals("flame");
        switch (c.tag) {
            case "skull" -> {
                if (c.f == F.FRONT) {
                    char ch = eyeChar(id, c.H[0], c.H[1]);
                    if (ch != '.') eyePx(c, ch);
                    else if (Math.floor(c.H[1]) >= EYE_TOP - 1 && Math.floor(c.H[1]) < EYE_TOP + 3 && Math.abs(c.H[0]) >= eyeColIn(id) - 1) oBase = HIDE[1]; // socket
                }
                if ((c.f == F.RIGHT || c.f == F.LEFT) && c.k == 0) {
                    int row = (int) Math.floor(c.H[1]) - EYE_TOP;
                    if (row == 1) eyePx(c, 'o'); else if (row == 0 || row == 2) eyePx(c, 'r');
                }
                if (ho && c.f == F.TOP) rune(c);
                if (bl) scar(c);
            }
            case "brow" -> {
                if (c.f == F.BOTTOM || c.f == F.BACK) { oBase = HIDE[0]; oGlow = 0; oGlowU = 0; }
                if (bl) scar(c);
            }
            case "snout", "bridge" -> snout(c);
            case "jaw" -> jaw(c);
            case "throat" -> { if (fl && c.f == F.FRONT) { oGlow = 0; } }
            case "ear_base", "ear_tip" -> ear(c);
            default -> { }
        }
        if (bl) bloodHead(c);
    }

    static final String[] RUNES = {
            ".x.....x.",
            "x.x.x.x.x",
            ".x..x..x.",
            "....x....",
            "...x.x...",
            "....x....",
    };

    /** the howler's brand on top of its skull (violet) */
    static void rune(Ctx c) {
        int row = (int) Math.floor(c.H[2]) + 15, col = (int) Math.floor(c.H[0]) + 4;
        if (row < 0 || row >= RUNES.length || col < 0 || col >= 9) return;
        if (RUNES[row].charAt(col) != 'x') return;
        boolean core = col == 4;
        oBase = core ? 0x4a2a7a : 0x2a1a4a;
        oGlow = argb(core ? VIO[4] : VIO[2], core ? 255 : 230);
        oGlowU = argb(core ? VIO[6] : VIO[4], 255);
    }

    /** diagonal scar over the ripper's right (-X) eye, across skull and brow */
    static void scar(Ctx c) {
        double hx = c.H[0], hy = c.H[1], hz = c.H[2];
        boolean on = false, edge = false;
        if (c.f == F.FRONT || (c.tag.equals("brow") && c.f != F.TOP)) {
            double ax = -9.2, ay = -12.5, bx = -4.6, by = -4.2;
            double t = clamp(((hx - ax) * (bx - ax) + (hy - ay) * (by - ay)) / ((bx - ax) * (bx - ax) + (by - ay) * (by - ay)), 0, 1);
            double dx = hx - (ax + t * (bx - ax)), dy = hy - (ay + t * (by - ay));
            double d = Math.sqrt(dx * dx + dy * dy);
            on = d < 0.62;
            edge = !on && d < 1.25 && dx > 0;
        } else if (c.f == F.TOP && (c.tag.equals("skull") || c.tag.equals("brow")) && hz < -6) {
            double xs = -9.0 - (hz + 12) * 0.25;
            on = Math.abs(hx - xs) < 0.55;
        }
        if (!on && !edge) return;
        if (c.eye) { oBase = SCAR[3]; oGlow = argb(0xa0100a, 200); oGlowU = argb(0xe0200e, 230); return; } // the scarred eye glows dimmer
        if (on) {
            oBase = SCAR[2 + (int) (hash((int) Math.floor(hy * 3), 7, 7) * 2)];
            oGlow = 0; oGlowU = 0;
        } else oBase = SCAR[0];
    }

    static void snout(Ctx c) {
        Cub b = c.cub;
        if (c.tag.equals("bridge")) {
            if (c.f == F.TOP) oBase = HIDE[Math.min(9, c.furIdx + 1)];
            return;
        }
        boolean fl = c.head.equals("flame"), ho = c.head.equals("howl");
        int w = b.w, h = b.h, d = b.d;
        switch (c.f) {
            case FRONT -> {
                if (c.j == h - 1) {
                    if (c.i >= 1 && c.i <= w - 2 && c.i % 2 == 1) { oBase = IV[4]; c.tooth = true; } else oBase = MO[1];
                } else if (c.j == h - 2) oBase = MO[2];
            }
            case RIGHT, LEFT -> {
                int kk = c.k; // 0 at the front
                if (c.j == h - 1) {
                    if (kk < d - 2) {
                        if (kk % 3 != 2) { oBase = kk < 3 ? IV[4] : IV[3]; c.tooth = true; } else oBase = MO[1];
                    } else oBase = MO[0];
                } else if (c.j == h - 2 && kk < d - 1) oBase = MO[3];
                else if (c.j == h - 3 && kk >= 2 && kk < d - 2 && kk % 4 == 1) oBase = RUST[2]; // whisker pits
            }
            case BOTTOM -> {
                // the palate: ridges, teeth around the edge, darker towards the throat
                boolean edge = c.i == 0 || c.i == w - 1 || c.k == 0;
                if (edge && c.k < d - 2) { if ((c.i + c.k) % 2 == 0) { oBase = IV[3]; c.tooth = true; } else oBase = MO[1]; }
                else oBase = (c.k % 3 == 1) ? MO[4] : MO[3];
                if (c.k >= d - 3 && !c.tooth) oBase = MO[1];
                if (!c.tooth) mouthGlow(c, (double) c.k / d);
            }
            default -> { }
        }
    }

    /** inside of a mouth: molten in the fire maw, violet deep in the howler's throat, embers when unleashed */
    static void mouthGlow(Ctx c, double depth) {
        String id = c.head;
        if (id.equals("flame")) {
            double heat = clamp(0.35 + 0.6 * depth, 0, 1);
            oBase = pal(VB, heat);
            oGlow = argb(pal(FIRE, heat * 0.85), 235);
            oGlowU = argb(pal(FIRE, 0.4 + 0.6 * heat), 255);
        } else if (id.equals("howl")) {
            if (depth > 0.45) oGlow = argb(pal(VIO, (depth - 0.45) * 1.4), (int) (90 + 140 * (depth - 0.45)));
            oGlowU = argb(pal(VIO, 0.3 + 0.6 * depth), 200);
        } else {
            oGlowU = argb(pal(FIRE, 0.2 + 0.5 * depth), (int) (120 + 100 * depth));
        }
    }

    static void jaw(Ctx c) {
        Cub b = c.cub;
        int w = b.w;
        boolean underSnout = c.H[2] < -16.5;
        switch (c.f) {
            case TOP -> {
                if (!underSnout) { oBase = MO[0]; mouthGlow(c, 1); return; }
                boolean edge = c.i == 0 || c.i == w - 1 || c.k == 0;
                if (edge) { if ((c.i + c.k) % 2 == 1) { oBase = IV[3]; c.tooth = true; } else oBase = MO[1]; }
                else {
                    boolean groove = (c.i == w / 2 - 1 || c.i == w / 2) && c.k > 2;
                    oBase = groove ? MO[3] : (c.k % 5 == 2 && c.i == 2 ? MO[6] : MO[5]); // tongue
                }
                if (!c.tooth) mouthGlow(c, clamp((c.H[2] + 34) / 18, 0, 1));
            }
            case RIGHT, LEFT -> {
                if (c.j == 0 && underSnout) { if (c.k % 3 != 0) { oBase = IV[3]; c.tooth = true; } else oBase = MO[1]; }
                else if (c.j == 1 && underSnout) oBase = MO[2];
            }
            case FRONT -> {
                if (c.j == 0) { if (c.i % 2 == 0 && c.i >= 1 && c.i <= w - 2) { oBase = IV[4]; c.tooth = true; } else oBase = MO[1]; }
                else if (c.j == 1) oBase = MO[2];
            }
            default -> { }
        }
    }

    static void ear(Ctx c) {
        Cub b = c.cub;
        boolean inner = c.f == F.FRONT && c.i >= 1 && c.i <= b.w - 2 && c.j >= 1;
        if (inner) {
            int lv = c.i == 1 ? 3 : (c.j > b.h / 2 ? 1 : 2);
            oBase = EARI[lv];
            if (c.head.equals("howl") && c.i >= 2 && c.i <= b.w - 3 && c.j > 2) { oBase = 0x2a1440; oGlowU = argb(VIO[1], 120); }
        }
        boolean torn = c.head.equals("bleed") && c.part.name.endsWith("_r");
        if (torn && (c.f == F.TOP || c.fv == 0)) { oBase = c.f == F.TOP ? MO[4] : MO[3]; oGlow = 0; }
        if (torn && c.f == F.FRONT && c.j <= 1 && c.i >= 2) oBase = MO[3 + c.j % 2];
    }

    /** wet blood over the ripper's muzzle, running down the jaw */
    static void bloodHead(Ctx c) {
        String t = c.tag;
        int column = (c.f == F.RIGHT || c.f == F.LEFT) ? c.k : c.i;
        double hc = hash(column, c.cub.idx, 71);
        boolean bloody = false;
        int row = 0;
        if (t.equals("snout")) {
            int h = c.cub.h;
            if (c.f == F.RIGHT || c.f == F.LEFT || c.f == F.FRONT) {
                int reach = 3 + (hc < 0.35 ? 1 : 0) + (hc < 0.1 ? 2 : 0);
                bloody = c.j >= h - reach;
                row = c.j - (h - reach);
                if (!bloody && hash(column, c.j, 99) < 0.06 && c.j >= 1) bloody = true; // splatter
            } else if (c.f == F.BOTTOM) bloody = c.k < 6 && !c.tooth;
        } else if (t.equals("jaw")) {
            if (c.f == F.RIGHT || c.f == F.LEFT || c.f == F.FRONT) {
                int drip = 1 + (int) (hc * 4.5);
                bloody = c.j <= drip;
                row = c.j;
            } else if (c.f == F.BOTTOM) {
                bloody = c.k < 3 + (int) (hc * 7);
                row = 3;
            } else if (c.f == F.TOP) bloody = c.tooth && hash(c.i, c.k, 5) < 0.5;
        } else if (t.startsWith("fang") || t.startsWith("barb")) {
            boolean up = !t.endsWith("_lo");
            int fromTip = up ? (c.cub.h - 1 - c.j) : c.j;
            bloody = fromTip <= (up ? 2 : 1) || t.startsWith("barb");
        } else if (t.equals("throat")) {
            bloody = c.f != F.TOP && hash(column, 3, 3) < 0.45 && c.j <= 1 + (int) (hc * 4);
        }
        if (!bloody) return;
        if (c.tooth) { oBase = hash(c.i + c.k, c.j, 12) < 0.5 ? 0xa8584c : 0xc08070; return; }
        double streak = hc < 0.3 ? -0.8 : hc > 0.75 ? 0.7 : 0;
        double lv = 3.2 + streak - row * 0.2;
        if (c.N[1] < -0.5) lv += 0.6;
        oBase = BL[(int) Math.round(clamp(lv, 1, 6))];
        if (c.fh > 0 && hc > 0.75 && c.j == 0) oBase = BL[6];
    }

    /** skin of a broken head (and its neck): dull, split by dark cracks with raw edges, blood running down, eyes out */
    static int brokenCol(Ctx c) {
        boolean neck = c.part.name.startsWith("neck_");
        if (c.head == null && !neck) return oBase;
        if (c.eye) return 0x0c0606;
        if (oGlow != 0) return scale(mixc(oBase, 0x2a0a0a, 0.7), 0.6); // molten mouth / horn tips gone cold
        int col = mixc(scale(oBase, 0.62), 0x1a0e10, 0.15);
        if (c.tooth || c.mat.equals("horn") || c.mat.equals("iron") || c.mat.equals("fang")) {
            col = scale(oBase, 0.55);
            cracks(c.P, c.N, 1, 4, 41, 0.4);
            if (vHit) col = 0x0c0808;
            return col;
        }
        cracks(c.P, c.N, 0.55, 9, 31, 0.6);
        if (vHit) return vHeat > 0.55 ? 0x070304 : 0x1a0608;
        if (vNear) return hash((int) Math.floor(c.P[0] * 2), (int) Math.floor(c.P[1] * 2), (int) Math.floor(c.P[2] * 2)) < 0.3 ? 0x4a0c10 : scale(col, 0.8);
        // blood trickling down from the cracks/mouth: vertical streaks on side faces
        if (c.fh > 0) {
            int column = (int) Math.floor((c.f == F.RIGHT || c.f == F.LEFT) ? c.P[2] : c.P[0]);
            double hc = hash(column, (int) Math.floor(c.P[1] / 9), 93);
            if (hc < 0.1) return BL[1 + (int) (hc * 30) % 3];
        }
        return col;
    }


    // =====================================================================================
    // UV packing
    static void pack() {
        List<Cub> order = new ArrayList<>(cubs);
        order.sort((a, b) -> {
            int c = Integer.compare(b.d + b.h, a.d + a.h);
            return c != 0 ? c : Integer.compare(2 * (b.d + b.w), 2 * (a.d + a.w));
        });
        int x = 0, y = 0, shelf = 0;
        int[] owner = new int[W * H];
        for (Cub c : order) {
            int uw = 2 * (c.d + c.w), vh = c.d + c.h;
            if (x + uw > W) { x = 0; y += shelf; shelf = 0; }
            if (y + vh > H) throw new IllegalStateException("texture full at " + c.part.name + " " + c.tag);
            c.box = new Box(x, y, c.w, c.h, c.d);
            x += uw;
            shelf = Math.max(shelf, vh);
        }
        // overlap assertion over all face texels
        for (Cub c : cubs) for (F f : F.values()) {
            int nu = (f == F.RIGHT || f == F.LEFT) ? c.d : c.w, nv = (f == F.TOP || f == F.BOTTOM) ? c.d : c.h;
            for (int b = 0; b < nv; b++) for (int a = 0; a < nu; a++) {
                int[] q = switch (f) {
                    case TOP, BOTTOM -> c.box.px(f, a, 0, b);
                    case RIGHT, LEFT -> c.box.px(f, 0, b, a);
                    default -> c.box.px(f, a, b, 0);
                };
                if (q[0] < 0 || q[1] < 0 || q[0] >= W || q[1] >= H) throw new IllegalStateException("UV out of range " + c.part.name);
                int i = q[1] * W + q[0];
                if (owner[i] != 0 && owner[i] != c.idx + 1) throw new IllegalStateException("UV overlap " + c.part.name + "/" + c.tag + " with cuboid " + (owner[i] - 1));
                owner[i] = c.idx + 1;
            }
        }
        System.out.println("UV packing used rows 0.." + (y + shelf) + " of " + H);
    }

    // =====================================================================================
    // checks
    static void checks() {
        computeM(null);
        // contract
        for (String[] row : CONTRACT) {
            Part p = byName.get(row[0]);
            if (p == null || !p.bone) throw new IllegalStateException("missing bone " + row[0]);
            String par = row[1];
            if (par == null ? p.parent != null : (p.parent == null || !p.parent.name.equals(par))) throw new IllegalStateException("wrong parent for " + row[0]);
            if (p.pitch != 0 || p.yaw != 0 || p.roll != 0) throw new IllegalStateException("bone with rest rotation " + row[0]);
        }
        for (Part p : parts) if (p.bone && Arrays.stream(CONTRACT).noneMatch(r -> r[0].equals(p.name))) throw new IllegalStateException("bone not in contract " + p.name);
        // ground
        double maxY = -1e9;
        for (Cub c : cubs) for (double[] q : corners(c)) maxY = Math.max(maxY, q[1]);
        if (Math.abs(maxY - 24) > 1e-6) throw new IllegalStateException("lowest point is " + maxY + ", expected 24");
        for (Cub c : cubs) if (c.mat.equals("paw") || c.mat.equals("claw")) {
            double m = -1e9;
            for (double[] q : corners(c)) m = Math.max(m, q[1]);
            if (Math.abs(m - 24) > 1e-6) throw new IllegalStateException("paw/claw not on the ground: " + c.part.name + " " + m);
        }
        // connectivity (rest pose): every cuboid must touch/overlap the rest
        int n = cubs.size();
        int[] uf = new int[n];
        for (int i = 0; i < n; i++) uf[i] = i;
        for (int a = 0; a < n; a++) for (int b = a + 1; b < n; b++) if (touch(cubs.get(a), cubs.get(b)) || touch(cubs.get(b), cubs.get(a))) union(uf, a, b);
        Map<Integer, List<String>> comps = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) comps.computeIfAbsent(find(uf, i), q -> new ArrayList<>()).add(cubs.get(i).part.name + "/" + cubs.get(i).tag);
        if (comps.size() != 1) {
            for (List<String> l : comps.values()) System.out.println("  component: " + l);
            throw new IllegalStateException("model is not connected: " + comps.size() + " pieces");
        }
        System.out.println("checks ok: contract, ground y=24, " + n + " cuboids connected");
    }

    static int find(int[] uf, int i) { while (uf[i] != i) i = uf[i] = uf[uf[i]]; return i; }
    static void union(int[] uf, int a, int b) { uf[find(uf, a)] = find(uf, b); }

    static List<double[]> corners(Cub c) {
        List<double[]> r = new ArrayList<>();
        for (int i = 0; i < 8; i++) r.add(app(c.part.M, c.x + ((i & 1) != 0 ? c.w : 0), c.y + ((i & 2) != 0 ? c.h : 0), c.z + ((i & 4) != 0 ? c.d : 0)));
        return r;
    }

    /** does a sample point of a's surface lie inside (or within 0.3 of) b? */
    static boolean touch(Cub a, Cub b) {
        double e = 0.3;
        for (double u = 0; u <= a.w + 1e-9; u += Math.max(0.5, a.w / 8.0))
            for (double v = 0; v <= a.h + 1e-9; v += Math.max(0.5, a.h / 8.0))
                for (double w = 0; w <= a.d + 1e-9; w += Math.max(0.5, a.d / 8.0)) {
                    double[] p = app(a.part.M, a.x + u, a.y + v, a.z + w);
                    double[] l = invApp(b.part.M, p);
                    if (l[0] >= b.x - e && l[0] <= b.x + b.w + e && l[1] >= b.y - e && l[1] <= b.y + b.h + e && l[2] >= b.z - e && l[2] <= b.z + b.d + e) return true;
                }
        return false;
    }

    // =====================================================================================
    // Kotlin output
    static String fmt(double v) {
        if (Math.abs(v) < 1e-9) return "0f";
        if (Math.abs(v - Math.rint(v)) < 1e-9) return ((long) Math.rint(v)) + "f";
        String s = String.format(Locale.ROOT, "%.4f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s + "f";
    }

    /** model unit in blocks: 1/16 * RENDER_SCALE 0.62 */
    static final double UNIT = 0.62 / 16;

    /** centre of head i (skull + muzzle) in blocks: lateral (to the hound's left), up, forward */
    static double[] headCentre(int i) {
        Part h = byName.get("head_" + HEADS[i]);
        Cub snout = null;
        for (Cub c : h.cubes) if (c.tag.equals("snout")) snout = c;
        double cx = h.ax, cy = h.ay, cz = h.az + (4 + snout.z) / 2.0;
        return new double[]{cx * UNIT, (24 - cy) * UNIT, -cz * UNIT};
    }

    static String kotlin() {
        StringBuilder sb = new StringBuilder();
        sb.append("// GENERATED by tools/textures/GenCerberus.java - do not edit\n");
        sb.append("package dev.henny.hha_blood.client.boss\n\n");
        sb.append("import net.minecraft.client.model.Dilation\n");
        sb.append("import net.minecraft.client.model.ModelData\n");
        sb.append("import net.minecraft.client.model.ModelPartBuilder\n");
        sb.append("import net.minecraft.client.model.ModelTransform\n");
        sb.append("import net.minecraft.client.model.TexturedModelData\n\n");
        sb.append("/**\n * Blood Cerberus model: 32 units = 1 block, +Y down, faces -Z, its right side is -X, paws on y = 24.\n");
        sb.append(" * Bones: ");
        sb.append(String.join(", ", parts.stream().filter(p -> p.bone).map(p -> p.name).toList()));
        sb.append(".\n * Bones have no rest rotation; tilted decoration is in fixed child parts.\n */\n");
        sb.append("object CerberusModelData {\n");
        sb.append("    fun create(): TexturedModelData {\n");
        sb.append("        val data = ModelData()\n");
        sb.append("        val root = data.root\n");
        for (Part p : parts) {
            String parent = p.parent == null ? "root" : "p_" + p.parent.name;
            sb.append("        ");
            if (!p.kids.isEmpty()) sb.append("val p_").append(p.name).append(" = ");
            sb.append(parent).append(".addChild(\n            \"").append(p.name).append("\",\n            ModelPartBuilder.create()");
            for (Cub c : p.cubes) {
                sb.append("\n                .uv(").append(c.box.u()).append(", ").append(c.box.v()).append(").cuboid(")
                        .append(fmt(c.x)).append(", ").append(fmt(c.y)).append(", ").append(fmt(c.z)).append(", ")
                        .append(fmt(c.w)).append(", ").append(fmt(c.h)).append(", ").append(fmt(c.d)).append(", Dilation(0f))");
            }
            sb.append(",\n            ModelTransform.of(").append(fmt(p.ox())).append(", ").append(fmt(p.oy())).append(", ").append(fmt(p.oz())).append(", ")
                    .append(fmt(p.pitch)).append(", ").append(fmt(p.yaw)).append(", ").append(fmt(p.roll)).append("),\n        )\n");
        }
        sb.append("        return TexturedModelData.of(data, ").append(W).append(", ").append(H).append(")\n");
        sb.append("    }\n\n");
        Part body = byName.get("body");
        sb.append("    /** Direct children of \"body\" (the renderer hides everything but a broken neck in the broken-head pass). */\n");
        sb.append("    val BODY_CHILDREN: List<String> = listOf(");
        sb.append(String.join(", ", body.kids.stream().map(p -> "\"" + p.name + "\"").toList()));
        sb.append(")\n\n");
        sb.append("    /** Head centres in the rest pose, blocks relative to the entity (lateral = to its left, up, forward); see CerberusEntity.headPos. */\n");
        sb.append("    val HEAD_CENTRES: Array<DoubleArray> = arrayOf(\n");
        for (int i = 0; i < 3; i++) {
            double[] hc = headCentre(i);
            sb.append(String.format(Locale.ROOT, "        doubleArrayOf(%.2f, %.2f, %.2f),\n", hc[0], hc[1], hc[2]));
        }
        sb.append("    )\n}\n");
        return sb.toString();
    }

    // =====================================================================================
    // main
    public static void main(String[] args) throws Exception {
        String root = ".";
        String preview = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--preview")) preview = args[++i];
            else root = args[i];
        }
        String res = root + "/blood/src/main/resources/assets/hha_blood/";
        build();
        computeM(null);
        checks();
        pack();
        paintAll();
        for (Cub c : cubs) for (F f : F.values()) forFace(c, f, x -> {
            int[] q = c.box.px(f, x.i, x.j, x.k);
            if ((base[q[1] * W + q[0]] >>> 24) != 255) throw new IllegalStateException("hole in " + c.part.name + " " + f);
        });
        write(base, W, H, res + "textures/entity/cerberus.png");
        write(glow, W, H, res + "textures/entity/cerberus_glow.png");
        write(glowU, W, H, res + "textures/entity/cerberus_glow_unleashed.png");
        write(broken, W, H, res + "textures/entity/cerberus_broken.png");
        for (int i = 0; i < 3; i++) {
            double[] hc = headCentre(i);
            System.out.println(String.format(Locale.ROOT, "head %s centre: lateral %.2f, up %.2f, forward %.2f blocks", HEADS[i], hc[0], hc[1], hc[2]));
        }
        Path kt = Path.of(root, "blood/src/main/kotlin/dev/henny/hha_blood/client/boss/CerberusModelData.kt");
        Files.createDirectories(kt.getParent());
        Files.writeString(kt, kotlin(), StandardCharsets.UTF_8);
        int nc = cubs.size();
        System.out.println("cuboids: " + nc + ", parts: " + parts.size());
        for (Part p : parts) if (p.bone) System.out.println(String.format(Locale.ROOT, "  bone %-24s parent %-20s pivot (%s, %s, %s)", p.name,
                p.parent == null ? "-" : p.parent.name, fmt(p.ax), fmt(p.ay), fmt(p.az)));
        List<Trophy> trophies = Trophies.buildAll();
        for (Trophy t : trophies) t.write(res);
        if (preview != null) {
            new File(preview).mkdirs();
            sheetPreview(preview);
            Preview.run(preview, trophies);
        }
    }

    static void write(int[] px, int w, int h, String path) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < px.length; i++) img.setRGB(i % w, i / w, (px[i] >>> 24) == 0 ? 0 : px[i]);
        File f = new File(path);
        f.getParentFile().mkdirs();
        ImageIO.write(img, "png", f);
    }

    static int over(int dst, int src) {
        int a = src >>> 24;
        int r = (((src >> 16) & 255) * a + ((dst >> 16) & 255) * (255 - a)) / 255;
        int gg = (((src >> 8) & 255) * a + ((dst >> 8) & 255) * (255 - a)) / 255;
        int b = ((src & 255) * a + (dst & 255) * (255 - a)) / 255;
        return r << 16 | gg << 8 | b;
    }

    static void sheetPreview(String dir) throws Exception {
        int s = 2;
        int[][] layers = {base, glow, glowU};
        String[] names = {"sheet_2x.png", "sheet_glow_2x.png", "sheet_glow_unleashed_2x.png"};
        for (int l = 0; l < 3; l++) {
            BufferedImage a = new BufferedImage(W * s, H * s, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < H * s; y++) for (int x = 0; x < W * s; x++) {
                int i = (y / s) * W + x / s;
                int bg = ((x / 16 + y / 16) % 2 == 0) ? 0x8a8a8a : 0x7a7a7a;
                int col;
                if (l == 0) col = (base[i] >>> 24) == 0 ? bg : base[i] & 0xffffff;
                else {
                    int b0 = (base[i] >>> 24) == 0 ? 0x101010 : over(0x000000, argb(base[i], 90));
                    col = (layers[l][i] >>> 24) == 0 ? b0 : over(b0, layers[l][i]);
                }
                a.setRGB(x, y, col);
            }
            ImageIO.write(a, "png", new File(dir, names[l]));
        }
    }

    // =====================================================================================
    // trophy heads (block models)
    enum Dir { down, up, north, south, west, east }

    static final class TFace {
        Dir dir;
        int tw, th, tx = -1, ty = -1;
        double[] uv; // in 0..16 units, set after packing (or explicitly for overlays)
    }

    static final class TEl {
        String name;
        double[] from = new double[3], to = new double[3];
        String axis;
        double angle;
        double[] origin;
        int light;
        boolean shade = true;
        final EnumMap<Dir, TFace> faces = new EnumMap<>(Dir.class);
        Cub src;        // copy entity texels from this cuboid
        String proc;    // or paint procedurally ("stump")
    }

    /** trophy texture size and texels per block-model uv unit */
    static final int TS = 128;
    static final double UVS = TS / 16.0;

    static final class Trophy {
        String id;
        double s, xb, yb, zb;
        final List<TEl> els = new ArrayList<>();
        final int[] tex = new int[TS * TS];

        void write(String res) throws Exception {
            GenCerberus.write(tex, TS, TS, res + "textures/block/cerberus_head_" + id + ".png");
            Path model = Path.of(res + "models/block/cerberus_head_" + id + ".json");
            Files.createDirectories(model.getParent());
            Files.writeString(model, json(), StandardCharsets.UTF_8);
            Path item = Path.of(res + "items/cerberus_head_" + id + ".json");
            Files.createDirectories(item.getParent());
            Files.writeString(item, "{\n  \"model\": {\n    \"type\": \"minecraft:model\",\n    \"model\": \"hha_blood:block/cerberus_head_" + id + "\"\n  }\n}\n", StandardCharsets.UTF_8);
        }

        String json() {
            StringBuilder sb = new StringBuilder();
            String tx = "hha_blood:block/cerberus_head_" + id;
            sb.append("{\n  \"credit\": \"GENERATED by tools/textures/GenCerberus.java - do not edit\",\n");
            sb.append("  \"ambientocclusion\": false,\n");
            sb.append("  \"texture_size\": [" + TS + ", " + TS + "],\n");
            sb.append("  \"textures\": {\n    \"0\": \"").append(tx).append("\",\n    \"particle\": \"").append(tx).append("\"\n  },\n");
            sb.append("  \"elements\": [\n");
            for (int e = 0; e < els.size(); e++) {
                TEl el = els.get(e);
                sb.append("    {\n      \"name\": \"").append(el.name).append("\",\n");
                sb.append("      \"from\": ").append(vec(el.from)).append(",\n      \"to\": ").append(vec(el.to)).append(",\n");
                if (el.axis != null) sb.append("      \"rotation\": {\"angle\": ").append(num(el.angle)).append(", \"axis\": \"").append(el.axis).append("\", \"origin\": ").append(vec(el.origin)).append("},\n");
                if (el.light > 0) sb.append("      \"light_emission\": ").append(el.light).append(",\n");
                if (!el.shade) sb.append("      \"shade\": false,\n");
                sb.append("      \"faces\": {\n");
                int fi = 0;
                for (TFace f : el.faces.values()) {
                    sb.append("        \"").append(f.dir.name()).append("\": {\"uv\": [").append(num(f.uv[0])).append(", ").append(num(f.uv[1])).append(", ")
                            .append(num(f.uv[2])).append(", ").append(num(f.uv[3])).append("], \"texture\": \"#0\"}");
                    sb.append(++fi < el.faces.size() ? ",\n" : "\n");
                }
                sb.append("      }\n    }").append(e + 1 < els.size() ? ",\n" : "\n");
            }
            sb.append("  ],\n");
            sb.append("  \"display\": {\n");
            String[] keys = {"gui", "ground", "fixed", "thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand", "head"};
            int k = 0;
            for (String key : keys) {
                double[][] t = display.get(key);
                sb.append("    \"").append(key).append("\": {\"rotation\": ").append(vec(t[0])).append(", \"translation\": ").append(vec(t[1]))
                        .append(", \"scale\": ").append(vec(t[2])).append("}").append(++k < keys.length ? ",\n" : "\n");
            }
            sb.append("  }\n}\n");
            return sb.toString();
        }

        final Map<String, double[][]> display = new LinkedHashMap<>();
    }

    static String num(double v) {
        double r = Math.round(v * 10000) / 10000.0;
        if (Math.abs(r - Math.rint(r)) < 1e-9) return Long.toString((long) Math.rint(r));
        String s = String.format(Locale.ROOT, "%.4f", r);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    static String vec(double[] v) { return "[" + num(v[0]) + ", " + num(v[1]) + ", " + num(v[2]) + "]"; }

    static final class Trophies {
        static final double JAW_OPEN = -22.5;

        static List<Trophy> buildAll() {
            // largest scale (<= 0.5 block px per entity unit) at which all three heads fit into the block
            double s = 0.5;
            while (true) {
                boolean ok = true;
                for (String id : new String[]{"bleed", "flame", "howl"}) if (!fits(build(id, s))) ok = false;
                if (ok) break;
                s -= 0.01;
                if (s < 0.3) throw new IllegalStateException("trophy does not fit");
            }
            System.out.println(String.format(Locale.ROOT, "trophy scale: %.2f block px per entity unit", s));
            List<Trophy> r = new ArrayList<>();
            for (String id : new String[]{"bleed", "flame", "howl"}) {
                Trophy t = build(id, s);
                finish(t);
                r.add(t);
            }
            return r;
        }

        static boolean fits(Trophy t) {
            for (TEl e : t.els) for (double[] p : cornersOf(e)) for (int a = 0; a < 3; a++) if (p[a] < -1e-6 || p[a] > 16 + 1e-6) return false;
            return true;
        }

        static double[][] map(Trophy t, double hx, double hy, double hz) { return null; }

        static Trophy build(String id, double s) {
            Trophy t = new Trophy();
            t.id = id;
            t.s = s;
            Part hp = byName.get("head_" + id);
            double zmin = 1e9, zmax = -1e9;
            for (Cub c : cubs) if (c.part.headPart == hp && c.part.pitch == 0 && c.part.roll == 0 && c.part.yaw == 0) {
                double pz = c.part.az - hp.az;
                zmin = Math.min(zmin, pz + c.z);
                zmax = Math.max(zmax, pz + c.z + c.d);
            }
            t.xb = 8;
            t.zb = 8 - s * (zmin + zmax) / 2;
            t.yb = 0;
            for (Cub c : cubs) if (c.part.headPart == hp) {
                Part p = c.part;
                // block models rotate around one axis in 22.5 degree steps: use the dominant axis of a decoration,
                // drop pieces that are turned too far for that (the outer horn segments)
                String ax = null;
                double ang = 0;
                if (!p.bone) {
                    double[] r = {-p.pitch, p.yaw, -p.roll};
                    int m = 0;
                    for (int a = 1; a < 3; a++) if (Math.abs(r[a]) > Math.abs(r[m])) m = a;
                    double deg = Math.toDegrees(r[m]);
                    if (Math.abs(deg) > 60) continue;
                    ang = clamp(Math.round(deg / 22.5) * 22.5, -45, 45);
                    if (ang != 0) ax = new String[]{"x", "y", "z"}[m];
                }
                double px = p.ax - hp.ax, py = p.ay - hp.ay, pz = p.az - hp.az;
                double x0 = px + c.x, y0 = py + c.y, z0 = pz + c.z, x1 = x0 + c.w, y1 = y0 + c.h, z1 = z0 + c.d;
                TEl e = new TEl();
                e.name = p.name.replace("head_" + id + "_", "") + "_" + c.tag;
                if (p.bone && p.name.startsWith("jaw")) e.name = c.tag;
                e.src = c;
                e.from = new double[]{t.xb + s * x0, -s * y1, t.zb + s * z0};
                e.to = new double[]{t.xb + s * x1, -s * y0, t.zb + s * z1};
                double[] o = {t.xb + s * px, -s * py, t.zb + s * pz};
                if (p.bone && p.name.startsWith("jaw")) { e.axis = "x"; e.angle = JAW_OPEN; e.origin = o; }
                else if (ax != null) { e.axis = ax; e.angle = ang; e.origin = o; }
                t.els.add(e);
            }
            // shift so the lowest point (the open jaw) rests on the floor
            double minY = 1e9;
            for (TEl e : t.els) for (double[] p : cornersOf(e)) minY = Math.min(minY, p[1]);
            for (TEl e : t.els) { e.from[1] -= minY; e.to[1] -= minY; if (e.origin != null) e.origin[1] -= minY; }
            t.yb = -minY;
            // neck stump with a blood-stone foot under the back of the skull
            Cub skull = null;
            for (Cub c : cubs) if (c.part == hp && c.tag.equals("skull")) skull = c;
            double sb = t.yb - s * (skull.y + skull.h); // block y of the skull bottom
            TEl st = new TEl();
            st.name = "neck_stump";
            st.proc = "stump";
            double sw = Math.round(skull.w * s * 0.8 * 2) / 2.0;
            st.from = new double[]{8 - sw / 2, 0, Math.round((t.zb + s * (skull.z + 4)) * 4) / 4.0};
            st.to = new double[]{8 + sw / 2, sb + 0.5, Math.round((t.zb + s * (skull.z + skull.d - 1)) * 4) / 4.0};
            t.els.add(st);
            return t;
        }

        static List<double[]> cornersOf(TEl e) {
            List<double[]> r = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                double[] p = {(i & 1) != 0 ? e.to[0] : e.from[0], (i & 2) != 0 ? e.to[1] : e.from[1], (i & 4) != 0 ? e.to[2] : e.from[2]};
                r.add(rot(e, p));
            }
            return r;
        }

        static double[] rot(TEl e, double[] p) {
            if (e.axis == null) return p;
            double a = Math.toRadians(e.angle), c = Math.cos(a), sn = Math.sin(a);
            double x = p[0] - e.origin[0], y = p[1] - e.origin[1], z = p[2] - e.origin[2];
            double[] r = switch (e.axis) {
                case "x" -> new double[]{x, y * c - z * sn, y * sn + z * c};
                case "y" -> new double[]{x * c + z * sn, y, -x * sn + z * c};
                default -> new double[]{x * c - y * sn, x * sn + y * c, z};
            };
            return new double[]{r[0] + e.origin[0], r[1] + e.origin[1], r[2] + e.origin[2]};
        }

        static double[] unrot(TEl e, double[] p) {
            if (e.axis == null) return p;
            TEl inv = new TEl();
            inv.axis = e.axis; inv.angle = -e.angle; inv.origin = e.origin;
            return rot(inv, p);
        }

        static boolean inside(TEl e, double[] p, double eps) {
            double[] q = unrot(e, p);
            return q[0] > e.from[0] + eps && q[0] < e.to[0] - eps && q[1] > e.from[1] + eps && q[1] < e.to[1] - eps && q[2] > e.from[2] + eps && q[2] < e.to[2] - eps;
        }

        /** face sizes in texels: one texel per entity unit (copied faces) */
        static int[] faceSize(TEl e, Dir d, Trophy t) {
            int w, h, dd;
            if (e.src != null) { w = e.src.w; h = e.src.h; dd = e.src.d; }
            else {
                double k = 0.5 / t.s; // procedural stump: half density
                w = (int) Math.round((e.to[0] - e.from[0]) * k); h = (int) Math.round((e.to[1] - e.from[1]) * k); dd = (int) Math.round((e.to[2] - e.from[2]) * k);
            }
            return switch (d) {
                case up, down -> new int[]{w, dd};
                case north, south -> new int[]{w, h};
                default -> new int[]{dd, h};
            };
        }

        /** point on a face for (u,v) in 0..1 following BakedQuadFactory's default UV orientation */
        static double[] facePoint(TEl e, Dir d, double u, double v) {
            double[] f = e.from, t = e.to;
            return switch (d) {
                case up -> new double[]{f[0] + u * (t[0] - f[0]), t[1], f[2] + v * (t[2] - f[2])};
                case down -> new double[]{f[0] + u * (t[0] - f[0]), f[1], t[2] - v * (t[2] - f[2])};
                case north -> new double[]{t[0] - u * (t[0] - f[0]), t[1] - v * (t[1] - f[1]), f[2]};
                case south -> new double[]{f[0] + u * (t[0] - f[0]), t[1] - v * (t[1] - f[1]), t[2]};
                case west -> new double[]{f[0], t[1] - v * (t[1] - f[1]), f[2] + u * (t[2] - f[2])};
                case east -> new double[]{t[0], t[1] - v * (t[1] - f[1]), t[2] - u * (t[2] - f[2])};
            };
        }

        static double[] outward(Dir d) {
            return switch (d) {
                case up -> new double[]{0, 1, 0}; case down -> new double[]{0, -1, 0}; case north -> new double[]{0, 0, -1};
                case south -> new double[]{0, 0, 1}; case west -> new double[]{-1, 0, 0}; case east -> new double[]{1, 0, 0};
            };
        }

        static void finish(Trophy t) {
            // faces, skipping those completely buried inside other elements
            List<TFace> all = new ArrayList<>();
            for (TEl e : t.els) for (Dir d : Dir.values()) {
                double[] n = outward(d);
                boolean hidden = false;
                for (TEl o : t.els) {
                    if (o == e) continue;
                    boolean allIn = true;
                    for (double[] uv : new double[][]{{0.02, 0.02}, {0.98, 0.02}, {0.98, 0.98}, {0.02, 0.98}, {0.5, 0.5}}) {
                        double[] p = rot(e, facePoint(e, d, uv[0], uv[1]));
                        double[] nn = rotDir(e, n);
                        double[] q = {p[0] + nn[0] * 0.02, p[1] + nn[1] * 0.02, p[2] + nn[2] * 0.02};
                        if (!inside(o, q, 0)) { allIn = false; break; }
                    }
                    if (allIn) { hidden = true; break; }
                }
                if (e.proc != null && d == Dir.down) hidden = true; // stands on the floor
                if (hidden) continue;
                TFace f = new TFace();
                f.dir = d;
                int[] sz = faceSize(e, d, t);
                f.tw = sz[0]; f.th = sz[1];
                e.faces.put(d, f);
                all.add(f);
            }
            // an element whose faces are all buried is invalid in a block model (needs 1-6 faces): drop it
            t.els.removeIf(el -> el.faces.isEmpty());
            // pack faces into the trophy texture
            all.sort((a, b) -> b.th != a.th ? Integer.compare(b.th, a.th) : Integer.compare(b.tw, a.tw));
            boolean[] used = new boolean[TS * TS];
            for (TFace f : all) {
                boolean placed = false;
                for (int y = 0; y + f.th <= TS && !placed; y++) for (int x = 0; x + f.tw <= TS && !placed; x++) {
                    boolean free = true;
                    for (int b = 0; b < f.th && free; b++) for (int a = 0; a < f.tw; a++) if (used[(y + b) * TS + x + a]) { free = false; break; }
                    if (free) {
                        for (int b = 0; b < f.th; b++) for (int a = 0; a < f.tw; a++) used[(y + b) * TS + x + a] = true;
                        f.tx = x; f.ty = y; placed = true;
                    }
                }
                if (!placed) throw new IllegalStateException("trophy texture full (" + t.id + "), needs " + all.stream().mapToInt(z -> z.tw * z.th).sum() + " texels");
                f.uv = new double[]{f.tx / UVS, f.ty / UVS, (f.tx + f.tw) / UVS, (f.ty + f.th) / UVS};
            }
            int usedN = 0;
            for (boolean b : used) if (b) usedN++;
            System.out.println("trophy " + t.id + ": " + t.els.size() + " elements, " + all.size() + " faces, " + usedN + "/" + TS * TS + " texels");
            // fill texels
            for (TEl e : t.els) for (TFace f : e.faces.values()) for (int b = 0; b < f.th; b++) for (int a = 0; a < f.tw; a++)
                t.tex[(f.ty + b) * TS + f.tx + a] = 0xff000000 | texel(t, e, f, a, b);
            // glowing eyes: thin fullbright overlays (block models have no emissive layer)
            addEyeOverlays(t);
            displays(t);
        }

        static double[] rotDir(TEl e, double[] n) {
            if (e.axis == null) return n;
            TEl z = new TEl();
            z.axis = e.axis; z.angle = e.angle; z.origin = new double[]{0, 0, 0};
            return rot(z, n);
        }

        static int texel(Trophy t, TEl e, TFace f, int col, int row) {
            if (e.src != null) {
                Cub c = e.src;
                int i, j, k;
                F ef;
                switch (f.dir) {
                    case up -> { ef = F.TOP; i = col; j = 0; k = row; }
                    case down -> { ef = F.BOTTOM; i = col; j = c.h - 1; k = c.d - 1 - row; }
                    case north -> { ef = F.FRONT; i = c.w - 1 - col; j = row; k = 0; }
                    case south -> { ef = F.BACK; i = col; j = row; k = c.d - 1; }
                    case west -> { ef = F.RIGHT; i = 0; j = row; k = col; }
                    default -> { ef = F.LEFT; i = c.w - 1; j = row; k = c.d - 1 - col; }
                }
                int[] q = c.box.px(ef, i, j, k);
                // block models have no emissive layer: the stage-1 glow is painted in (eyes, rune, molten mouth)
                int g = glow[q[1] * W + q[0]];
                return g == 0 ? base[q[1] * W + q[0]] & 0xffffff : over(base[q[1] * W + q[0]], g);
            }
            // procedural: neck stump (fur, veins) with a blood-stone foot
            double u = (col + 0.5) / f.tw, v = (row + 0.5) / f.th;
            double[] p = facePoint(e, f.dir, u, v);
            double[] n = outward(f.dir);
            Part hp = byName.get("head_" + t.id);
            Ctx c = new Ctx();
            c.P = new double[]{hp.ax + (p[0] - t.xb) / t.s, hp.ay + (t.yb - p[1]) / t.s, hp.az + (p[2] - t.zb) / t.s};
            c.N = new double[]{n[0], -n[1], n[2]};
            c.head = null;
            c.mat = "neck"; c.tag = "stump";
            c.spec = new FurSpec(norm(0, 1, 0.3), 6, 3.5, 19, 1, false);
            c.fh = (f.dir == Dir.up || f.dir == Dir.down) ? -1 : f.th;
            c.fv = row;
            oGlow = 0; oGlowU = 0;
            hide(c);
            int col0 = oBase;
            double stoneTop = 1.5;
            if (p[1] < stoneTop || f.dir == Dir.down) {
                double sv = 2.2 + (f.dir == Dir.up ? 1 : 0) + (hash(col / 2, row, 3) - 0.5) * 1.2 - (p[1] < 0.5 ? 0.8 : 0);
                if (row == 0 && f.dir != Dir.up && f.dir != Dir.down) sv += 0.8;
                col0 = ST[(int) Math.round(clamp(sv, 0, ST.length - 1))];
                // glowing crack through the stone
                if (f.dir != Dir.up && f.dir != Dir.down && Math.abs(((col * 7) % 11) - row * 3) == 1 && hash(col, 1, 4) < 0.4) col0 = 0xd02a14;
            }
            return col0;
        }

        static void addEyeOverlays(Trophy t) {
            TEl skull = null;
            for (TEl e : t.els) if (e.src != null && e.src.tag.equals("skull")) skull = e;
            int eyeIn = eyeColIn(t.id), eyeW = EYE[0].length();
            Cub c = skull.src;
            Part hp = byName.get("head_" + t.id);
            double sx0 = c.part.ax - hp.ax + c.x, sy0 = c.part.ay - hp.ay + c.y;
            TFace front = skull.faces.get(Dir.north);
            for (int s : new int[]{-1, 1}) {
                double x0h = s > 0 ? eyeIn : -eyeIn - eyeW, x1h = x0h + eyeW;
                double y0h = EYE_TOP, y1h = y0h + EYE.length;
                TEl o = new TEl();
                o.name = "eye_glow_" + (s < 0 ? "r" : "l");
                o.light = 15;
                o.shade = false;
                double z = skull.from[2];
                o.from = new double[]{t.xb + t.s * x0h, t.yb - t.s * y1h, z - 0.02};
                o.to = new double[]{t.xb + t.s * x1h, t.yb - t.s * y0h, z - 0.02};
                // sub-rectangle of the skull's north face: column = w-1-i, row = j
                int i0 = (int) Math.round(x0h - sx0), i1 = (int) Math.round(x1h - sx0) - 1;
                int j0 = (int) Math.round(y0h - sy0), j1 = (int) Math.round(y1h - sy0) - 1;
                int c0 = c.w - 1 - i1, c1 = c.w - 1 - i0;
                TFace f = new TFace();
                f.dir = Dir.north;
                f.tx = front.tx + c0; f.ty = front.ty + j0; f.tw = c1 - c0 + 1; f.th = j1 - j0 + 1;
                f.uv = new double[]{f.tx / UVS, f.ty / UVS, (f.tx + f.tw) / UVS, (f.ty + f.th) / UVS};
                o.faces.put(Dir.north, f);
                t.els.add(o);
            }
        }

        static void displays(Trophy t) {
            // gui: 3/4 view like vanilla blocks, scaled + centred to fill the slot
            double[] gr = {30, 225, 0};
            double[] ext = projectedExtent(t, gr);
            double gs = Math.min(1.6, 0.98 / Math.max(ext[1] - ext[0], ext[3] - ext[2]));
            gs = Math.round(gs * 100) / 100.0;
            double[] gt = {-(ext[0] + ext[1]) / 2 * gs * 16, -(ext[2] + ext[3]) / 2 * gs * 16, 0};
            t.display.put("gui", new double[][]{gr, round2(gt), {gs, gs, gs}});
            t.display.put("ground", new double[][]{{0, 0, 0}, {0, 2, 0}, {0.5, 0.5, 0.5}});
            t.display.put("fixed", new double[][]{{0, 180, 0}, {0, 0, 0}, {0.9, 0.9, 0.9}});
            t.display.put("thirdperson_righthand", new double[][]{{75, 45, 0}, {0, 2.5, 0}, {0.5, 0.5, 0.5}});
            t.display.put("thirdperson_lefthand", new double[][]{{75, 45, 0}, {0, 2.5, 0}, {0.5, 0.5, 0.5}});
            t.display.put("firstperson_righthand", new double[][]{{0, 45, 0}, {0, 1, 0}, {0.6, 0.6, 0.6}});
            t.display.put("firstperson_lefthand", new double[][]{{0, 225, 0}, {0, 1, 0}, {0.6, 0.6, 0.6}});
            // head: centre the skull on the player's head (head = +-0.4 item units), slightly enlarged
            TEl skull = null;
            for (TEl e : t.els) if (e.src != null && e.src.tag.equals("skull")) skull = e;
            double skH = skull.to[1] - skull.from[1];
            double hs = Math.round(1.1 * 16 * 0.8 / skH * 100) / 100.0;
            double cx = (skull.from[0] + skull.to[0]) / 2, cy = (skull.from[1] + skull.to[1]) / 2, cz = (skull.from[2] + skull.to[2]) / 2;
            double[] ht = {-(cx - 8) * hs, -(cy - 8) * hs + 0.4, -(cz - 8) * hs};
            t.display.put("head", new double[][]{{0, 0, 0}, round2(ht), {hs, hs, hs}});
        }

        static double[] round2(double[] v) { return new double[]{Math.round(v[0] * 100) / 100.0, Math.round(v[1] * 100) / 100.0, Math.round(v[2] * 100) / 100.0}; }

        /** min/max of x and y (item units) of the model after a display rotation */
        static double[] projectedExtent(Trophy t, double[] rotDeg) {
            double[] r = {1e9, -1e9, 1e9, -1e9};
            for (TEl e : t.els) for (double[] p : cornersOf(e)) {
                double[] q = displayApply(new double[]{p[0] / 16 - 0.5, p[1] / 16 - 0.5, p[2] / 16 - 0.5}, rotDeg, new double[]{0, 0, 0}, 1);
                r[0] = Math.min(r[0], q[0]); r[1] = Math.max(r[1], q[0]); r[2] = Math.min(r[2], q[1]); r[3] = Math.max(r[3], q[1]);
            }
            return r;
        }
    }

    /** Transformation.apply: translate, rotationXYZ (= Rx * Ry * Rz), scale; p already centred (block/16 - 0.5) */
    static double[] displayApply(double[] p, double[] rotDeg, double[] transPx, double sc) {
        double[] m = mul(rx(Math.toRadians(rotDeg[0])), mul(ry(Math.toRadians(rotDeg[1])), rz(Math.toRadians(rotDeg[2]))));
        double[] q = app(m, p[0] * sc, p[1] * sc, p[2] * sc);
        return new double[]{q[0] + transPx[0] / 16, q[1] + transPx[1] / 16, q[2] + transPx[2] / 16};
    }

    // =====================================================================================
    /** Small software renderer for checking geometry + UV mapping. */
    static final class Preview {
        static final class Quad {
            double[][] p = new double[4][];
            double[][] uv = new double[4][];
            double[] rect, center;
            int[] tex, gtex;
            int tw, th, solid;
            boolean emissive;
            String part;
        }

        /** entity quads exactly like ModelPart.Cuboid (vertex order + UVs), converted to world space (x, -y, -z) */
        static List<Quad> entity(Map<String, double[]> pose, int[] glowTex) {
            computeM(pose);
            List<Quad> out = new ArrayList<>();
            for (Cub c : cubs) {
                double[] m = c.part.M;
                double x = c.x, y = c.y, z = c.z, f = x + c.w, g = y + c.h, h = z + c.d;
                double[] v1 = {x, y, z}, v2 = {f, y, z}, v3 = {f, g, z}, v4 = {x, g, z}, v5 = {x, y, h}, v6 = {f, y, h}, v7 = {f, g, h}, v8 = {x, g, h};
                double u = c.box.u(), v = c.box.v(), sx = c.w, sy = c.h, sz = c.d;
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
                    Quad qd = new Quad();
                    qd.uv = new double[][]{{u2, vv1}, {u1, vv1}, {u1, vv2}, {u2, vv2}};
                    for (int i = 0; i < 4; i++) qd.p[i] = toWorld(app(m, vs[i][0], vs[i][1], vs[i][2]));
                    qd.center = toWorld(cen);
                    qd.rect = new double[]{Math.min(u1, u2), Math.min(vv1, vv2), Math.max(u1, u2), Math.max(vv1, vv2)};
                    qd.tex = base; qd.gtex = glowTex; qd.tw = W; qd.th = H;
                    qd.part = c.part.headPart != null ? c.part.headPart.name : c.part.name;
                    out.add(qd);
                }
            }
            computeM(null);
            return out;
        }

        static double[] toWorld(double[] p) { return new double[]{p[0], -p[1], -p[2]}; }

        /** block-model quads in block space (0..16, y up), optional display transform to item space */
        static List<Quad> trophy(Trophy t, double[][] disp) {
            List<Quad> out = new ArrayList<>();
            for (TEl e : t.els) {
                double[] c = {(e.from[0] + e.to[0]) / 2, (e.from[1] + e.to[1]) / 2, (e.from[2] + e.to[2]) / 2};
                for (TFace f : e.faces.values()) {
                    Quad q = new Quad();
                    double[][] cuv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
                    for (int i = 0; i < 4; i++) {
                        double[] p = Trophies.rot(e, Trophies.facePoint(e, f.dir, cuv[i][0], cuv[i][1]));
                        q.p[i] = disp == null ? p : displayApply(new double[]{p[0] / 16 - 0.5, p[1] / 16 - 0.5, p[2] / 16 - 0.5}, disp[0], disp[1], disp[2][0]);
                        q.uv[i] = new double[]{(f.uv[0] + cuv[i][0] * (f.uv[2] - f.uv[0])) * UVS, (f.uv[1] + cuv[i][1] * (f.uv[3] - f.uv[1])) * UVS};
                    }
                    double[] cc = Trophies.rot(e, c);
                    q.center = disp == null ? cc : displayApply(new double[]{cc[0] / 16 - 0.5, cc[1] / 16 - 0.5, cc[2] / 16 - 0.5}, disp[0], disp[1], disp[2][0]);
                    q.rect = new double[]{f.uv[0] * UVS, f.uv[1] * UVS, f.uv[2] * UVS, f.uv[3] * UVS};
                    q.tex = t.tex; q.tw = TS; q.th = TS;
                    q.emissive = e.light > 0;
                    q.part = e.name;
                    out.add(q);
                }
            }
            return out;
        }

        /** untextured box (for the player dummy) */
        static void box(List<Quad> out, double x0, double y0, double z0, double x1, double y1, double z1, int col) {
            double[][][] fs = {
                    {{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}}, {{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}},
                    {{x0, y0, z0}, {x0, y1, z0}, {x0, y1, z1}, {x0, y0, z1}}, {{x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}, {x1, y0, z1}},
                    {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}}, {{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}},
            };
            for (double[][] f : fs) {
                Quad q = new Quad();
                q.p = f;
                q.center = new double[]{(x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2};
                q.solid = col;
                q.part = "dummy";
                out.add(q);
            }
        }

        /** a pose like CerberusRenderer's CPose */
        static Map<String, double[]> cpose(double[] np, double[] ny, double[] hp, double[] jaw, double[] leg, double[] lower, double bodyPitch, double tailPitch, double tailYaw) {
            Map<String, double[]> m = new LinkedHashMap<>();
            m.put("body", new double[]{bodyPitch, 0, 0});
            for (int i = 0; i < 3; i++) {
                m.put("neck_" + HEADS[i], new double[]{np[i], ny[i], 0});
                m.put("head_" + HEADS[i], new double[]{hp[i], 0, 0});
                m.put("jaw_" + HEADS[i], new double[]{jaw[i], 0, 0});
            }
            String[] legs = {"leg_front_right", "leg_front_left", "leg_back_right", "leg_back_left"};
            for (int i = 0; i < 4; i++) { m.put(legs[i], new double[]{leg[i], 0, 0}); m.put(legs[i] + "_lower", new double[]{lower[i], 0, 0}); }
            m.put("tail", new double[]{tailPitch, tailYaw, 0});
            return m;
        }

        /** renders a pose with the renderer's drop (blocks down) and roll (degrees around the hound's left edge at [pivot] blocks) */
        static void posed(String file, Map<String, double[]> pose, double drop, double roll, double pivot, int[] glowTex) throws Exception {
            double u = 1 / UNIT, px = pivot * u;
            GLOBAL = mul(trans(0, drop * u, 0), mul(trans(px, 24, 0), mul(rz(Math.toRadians(roll)), trans(-px, -24, 0))));
            List<Quad> q = entity(pose, glowTex);
            GLOBAL = null;
            computeM(null);
            box(q, -60, -26, -95, 60, -24, 95, 0x2e3a2e);
            render(q, 35, 18, file + "_34.png", 0x6e6e6e, null, 1100, 860, null);
            render(q, 90, 2, file + "_side.png", 0x6e6e6e, null, 1100, 860, null);
            render(q, 0, 2, file + "_front.png", 0x6e6e6e, null, 1100, 860, null);
        }

        static void run(String dir, List<Trophy> trophies) throws Exception {
            Map<String, double[]> open = new LinkedHashMap<>();
            for (String id : new String[]{"bleed", "flame", "howl"}) open.put("jaw_" + id, new double[]{0.6, 0, 0});
            List<Quad> rest = entity(null, glow), openQ = entity(open, glow), unl = entity(open, glowU);
            int g = 0x6e6e6e;
            render(rest, 35, 20, dir + "/r3d_front34.png", g, null, 1100, 860, null);
            render(rest, -35, 20, dir + "/r3d_front34_right.png", g, null, 1100, 860, null);
            render(rest, 0, 6, dir + "/r3d_front.png", g, null, 1100, 860, null);
            render(rest, 90, 4, dir + "/r3d_side.png", g, null, 1100, 860, null);
            render(rest, -90, 4, dir + "/r3d_side_right.png", g, null, 1100, 860, null);
            render(rest, 150, 25, dir + "/r3d_back34.png", g, null, 1100, 860, null);
            render(rest, 0, 85, dir + "/r3d_top.png", g, null, 1100, 860, null);
            render(rest, 200, -35, dir + "/r3d_below.png", g, null, 1100, 860, null);
            render(openQ, 25, 12, dir + "/r3d_jaws_open.png", g, null, 1100, 860, null);
            render(openQ, 25, 12, dir + "/r3d_dark.png", 0x120c10, null, 1100, 860, null);
            render(unl, 25, 12, dir + "/r3d_unleashed.png", 0x120c10, null, 1100, 860, null);
            render(rest, -30, 8, dir + "/r3d_head_bleed.png", g, Set.of("head_bleed"), 900, 760, null);
            render(rest, 0, 8, dir + "/r3d_head_flame.png", g, Set.of("head_flame"), 900, 760, null);
            render(rest, 30, 8, dir + "/r3d_head_howl.png", g, Set.of("head_howl"), 900, 760, null);
            render(openQ, -20, 15, dir + "/r3d_head_bleed_open.png", g, Set.of("head_bleed"), 900, 760, null);
            render(openQ, 0, 15, dir + "/r3d_head_flame_open.png", 0x120c10, Set.of("head_flame"), 900, 760, null);
            render(openQ, 20, 15, dir + "/r3d_head_howl_open.png", g, Set.of("head_howl"), 900, 760, null);
            render(rest, 0, 8, dir + "/r3d_heads_front.png", g, Set.of("head_bleed", "head_flame", "head_howl"), 1100, 760, null);
            List<Quad> bq = entity(open, null);
            for (Quad q : bq) q.tex = broken;
            render(bq, 20, 10, dir + "/r3d_heads_broken.png", g, Set.of("head_bleed", "head_flame", "head_howl"), 1100, 760, null);
            // poses of CerberusRenderer (numbers copied from there) on a floor: lying poses must lie on it, not in it
            double[] z3 = {0, 0, 0};
            Map<String, double[]> sleep = cpose(new double[]{0.95, 1.0, 0.95}, new double[]{0.4, 0, -0.4}, new double[]{0.2, 0.15, 0.2}, z3,
                    new double[]{0.6, 0.6, -1.3, -1.3}, new double[]{-2.1, -2.1, 2.9, 2.9}, 0.1, -0.5, 0.9);
            posed(dir + "/pose_sleep", sleep, 0.85, 0, 0, glow);
            if (System.getProperty("sleepVariants") != null) {
                String[] vs = System.getProperty("sleepVariants").split(";");
                for (int vi = 0; vi < vs.length; vi++) {
                    double[] a = Arrays.stream(vs[vi].split(",")).mapToDouble(Double::parseDouble).toArray();
                    posed(dir + "/pose_sleepv" + vi, cpose(new double[]{0.62, 0.7, 0.62}, new double[]{0.4, 0, -0.4}, new double[]{0.35, 0.3, 0.35}, z3,
                            new double[]{a[1], a[1], a[3], a[3]}, new double[]{a[2], a[2], a[4], a[4]}, 0, 0.9, 0.9), a[0], 0, 0, glow);
                }
            }
            Map<String, double[]> lying = cpose(new double[]{0.75, 0.85, 0.4}, new double[]{0.45, 0.1, 0.55}, new double[]{0.4, 0.3, 0.3}, new double[]{0.35, 0.5, 0.3},
                    new double[]{-0.3, -0.1, 0.4, 0.2}, new double[]{0.2, 0.1, 0.2, 0.1}, 0, 0.6, 0.3);
            posed(dir + "/pose_lying", lying, 0, 85, 0.95, glow);
            Map<String, double[]> brk = cpose(new double[]{0.95, 0, 0.95}, new double[]{0.35, 0, -0.35}, new double[]{0.5, 0, 0.5}, new double[]{0.6, 0.12, 0.6},
                    new double[4], new double[4], 0, -0.1, 0);
            posed(dir + "/pose_broken", brk, 0, 0, 0, glow);
            for (Trophy t : trophies) {
                List<Quad> q = trophy(t, null);
                render(q, 180 + 35, 25, dir + "/trophy_" + t.id + ".png", g, null, 700, 700, null);
                render(q, 180 - 90, 5, dir + "/trophy_" + t.id + "_side.png", g, null, 700, 700, null);
                render(q, 180, 5, dir + "/trophy_" + t.id + "_front.png", 0x120c10, null, 700, 700, null);
                render(trophy(t, t.display.get("gui")), 0, 0, dir + "/trophy_" + t.id + "_gui.png", 0x8b8b8b, null, 256, 256, new double[]{-0.5, 0.5, -0.5, 0.5});
                // worn: item space after HeadFeatureRenderer; player head = +-0.4, torso below
                List<Quad> w = trophy(t, t.display.get("head"));
                box(w, -0.4, -0.4, -0.4, 0.4, 0.4, 0.4, 0xc69c7c);
                box(w, -0.4, -1.6, -0.2, 0.4, -0.4, 0.2, 0x3a7ab0);
                box(w, -0.8, -1.6, -0.2, -0.4, -0.4, 0.2, 0xc69c7c);
                box(w, 0.4, -1.6, -0.2, 0.8, -0.4, 0.2, 0xc69c7c);
                render(w, 180 + 30, 10, dir + "/trophy_" + t.id + "_worn.png", g, null, 600, 700, null);
            }
        }

        static void render(List<Quad> quads, double yawDeg, double pitchDeg, String file, int bg, Set<String> focus, int IW, int IH, double[] frame) throws Exception {
            double ya = Math.toRadians(yawDeg), pa = Math.toRadians(pitchDeg);
            double cy = Math.cos(ya), sy = Math.sin(ya), cp = Math.cos(pa), sp = Math.sin(pa);
            List<double[][]> vq = new ArrayList<>();
            double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
            for (Quad q : quads) {
                double[][] s = new double[4][];
                for (int i = 0; i < 4; i++) {
                    double X = q.p[i][0], Y = q.p[i][1], Z = q.p[i][2];
                    double x1 = X * cy - Z * sy, z1 = X * sy + Z * cy;
                    double y2 = Y * cp - z1 * sp, z2 = Y * sp + z1 * cp;
                    s[i] = new double[]{x1, -y2, z2};
                    if (focus != null && !focus.contains(q.part)) continue;
                    minX = Math.min(minX, x1); maxX = Math.max(maxX, x1); minY = Math.min(minY, -y2); maxY = Math.max(maxY, -y2);
                }
                vq.add(s);
            }
            if (frame != null) { minX = frame[0]; maxX = frame[1]; minY = -frame[3]; maxY = -frame[2]; }
            double sc = Math.min((IW - 40) / (maxX - minX), (IH - 40) / (maxY - minY));
            if (frame != null) sc = Math.min(IW / (maxX - minX), IH / (maxY - minY));
            double offX = IW / 2.0 - (minX + maxX) / 2 * sc, offY = IH / 2.0 - (minY + maxY) / 2 * sc;
            double[] zb = new double[IW * IH];
            Arrays.fill(zb, -1e18);
            int[] col = new int[IW * IH];
            Arrays.fill(col, bg);
            double[] L1 = norm(-0.35, 0.9, 0.55), L2 = norm(0.5, 0.3, -0.6);
            for (int qn = 0; qn < quads.size(); qn++) {
                Quad q = quads.get(qn);
                double[][] s = vq.get(qn);
                double[][] sp2 = new double[4][];
                for (int i = 0; i < 4; i++) sp2[i] = new double[]{s[i][0] * sc + offX, s[i][1] * sc + offY, s[i][2]};
                double[] a = q.p[0], b = q.p[1], c = q.p[2];
                double[] e1 = {b[0] - a[0], b[1] - a[1], b[2] - a[2]}, e2 = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
                double[] nn = cross(e1, e2);
                double nl = Math.sqrt(dot(nn, nn));
                if (nl < 1e-12) continue;
                double fcx = (q.p[0][0] + q.p[2][0]) / 2 - q.center[0], fcy = (q.p[0][1] + q.p[2][1]) / 2 - q.center[1], fcz = (q.p[0][2] + q.p[2][2]) / 2 - q.center[2];
                if (nn[0] * fcx + nn[1] * fcy + nn[2] * fcz < 0) { nn[0] = -nn[0]; nn[1] = -nn[1]; nn[2] = -nn[2]; }
                double[] wn = {nn[0] / nl, nn[1] / nl, nn[2] / nl};
                double light = q.emissive ? 1 : Math.min(1, 0.42 + 0.5 * Math.max(0, dot(wn, L1)) + 0.18 * Math.max(0, dot(wn, L2)));
                final Quad fq = q;
                final double fl = light;
                Frag fr = (x, y, d, u, v) -> {
                    int i = y * IW + x;
                    if (d <= zb[i]) return;
                    int px;
                    if (fq.tex == null) px = fq.solid;
                    else {
                        int t = fq.tex[v * fq.tw + u];
                        if ((t >>> 24) < 26) return;
                        px = t;
                    }
                    zb[i] = d;
                    int r = (int) (((px >> 16) & 255) * fl), gg = (int) (((px >> 8) & 255) * fl), bl = (int) ((px & 255) * fl);
                    int out = r << 16 | gg << 8 | bl;
                    if (fq.gtex != null) {
                        int gl = fq.gtex[v * fq.tw + u];
                        if ((gl >>> 24) != 0) out = over(out, gl);
                    }
                    col[i] = out;
                };
                double[] rect = q.rect == null ? new double[]{0, 0, 1, 1} : q.rect;
                double[][] uv = q.tex == null ? new double[][]{{0, 0}, {0, 0}, {0, 0}, {0, 0}} : q.uv;
                tri(sp2[0], sp2[1], sp2[2], uv[0], uv[1], uv[2], rect, IW, IH, q.tw == 0 ? 1 : q.tw, q.th == 0 ? 1 : q.th, fr);
                tri(sp2[0], sp2[2], sp2[3], uv[0], uv[2], uv[3], rect, IW, IH, q.tw == 0 ? 1 : q.tw, q.th == 0 ? 1 : q.th, fr);
            }
            BufferedImage img = new BufferedImage(IW, IH, BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, IW, IH, col, 0, IW);
            ImageIO.write(img, "png", new File(file));
        }

        interface Frag { void at(int x, int y, double depth, int u, int v); }

        static void tri(double[] a, double[] b, double[] c, double[] ta, double[] tb, double[] tc, double[] rect, int IW, int IH, int TW, int TH, Frag fr) {
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
                if (iu < 0 || iv < 0 || iu >= TW || iv >= TH) continue;
                fr.at(x, y, d, iu, iv);
            }
        }
    }
}
