import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Generator for "Cruorax, the Blood Wyrm" (boss phase 2), deterministic, no external libraries.
 *
 * Writes:
 *  - textures/entity/cruorax.png        256x128 entity skin (CruoraxModel in client/boss/CruoraxRenderer.kt)
 *  - textures/entity/cruorax_glow.png   256x128 emissive layer (eyes, heart, veins, mouth glow)
 *  - textures/item/heart_of_cruorax.png 32x32 item icon
 *
 * Usage (from the project root):
 *   java tools/textures/GenCruorax.java [projectRoot]
 *   java tools/textures/GenCruorax.java [projectRoot] --preview <outDir>   also writes sheet + 3D previews (no extra files in the repo)
 *
 * UV layout = standard cuboid unwrap, see Box.px(). Every cuboid of the model has a Box constant below; the 3D preview
 * rebuilds the model hierarchy (origins, rotations, mirrored parts) exactly like CruoraxModel.texturedModelData().
 */
public class GenCruorax {
    static final int W = 256, H = 128;
    static final int[] base = new int[W * H], glow = new int[W * H];

    // ---------------- palettes ----------------
    /** scales, dark to light */
    static final int[] SC = {0x0e0204, 0x1c0508, 0x2a070c, 0x3a0a10, 0x4b0d14, 0x5c1018, 0x6d141c, 0x7e1820, 0x93202a, 0xa8242c};
    /** belly plates, dull red-brown */
    static final int[] BE = {0x1a0807, 0x2e1210, 0x431b17, 0x58251e, 0x6c3127, 0x803e30, 0x94503d};
    /** bone */
    static final int[] BO = {0x2e271e, 0x4f4637, 0x7d725d, 0x9b8f76, 0xb9ad92, 0xe2d7bd, 0xf3ecd8};
    /** wing membrane */
    static final int[] ME = {0x1e0407, 0x2a060a, 0x3a080e, 0x4a0a12, 0x5c0f18, 0x6e1420, 0x82202a, 0x98303a};
    /** blood crystal */
    static final int[] CR = {0x2a0408, 0x4a0810, 0x7a0e18, 0xb01822, 0xe0302a, 0xff6a48, 0xffc0a0};
    /** glow (emissive) */
    static final int[] GL = {0x4a0505, 0x7a0a08, 0xb0160c, 0xe82a14, 0xff5a28, 0xff9a50, 0xffd8a0, 0xfff4e0};

    enum F { TOP, BOTTOM, RIGHT, FRONT, LEFT, BACK }

    /** One cuboid's UV block. TOP = -Y face (visual top), FRONT = -Z face (towards the head), RIGHT = -X, LEFT = +X. */
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
    }

    static final Box BODY = new Box(0, 0, 16, 12, 28), SPIKE = new Box(150, 40, 2, 4, 3), HEART = new Box(160, 40, 6, 4, 6);
    static final Box NECK = new Box(88, 0, 8, 8, 10);
    static final Box SKULL = new Box(0, 40, 12, 10, 12), SNOUT = new Box(48, 40, 8, 5, 10), JAW = new Box(84, 40, 8, 3, 11), HORN = new Box(122, 40, 2, 2, 12);
    static final Box[] TAIL = {new Box(0, 62, 8, 8, 12), new Box(40, 62, 7, 7, 12), new Box(78, 62, 6, 6, 12), new Box(114, 62, 5, 5, 12), new Box(148, 62, 4, 4, 12)};
    static final Box BLADE = new Box(180, 62, 10, 1, 8);
    static final Box ARM = new Box(124, 0, 20, 3, 3), TIPB = new Box(124, 6, 22, 2, 2), MEM = new Box(0, 84, 20, 1, 24), MTIP = new Box(88, 84, 22, 1, 22);
    static final Box LEG_FU = new Box(0, 110, 4, 6, 4), LEG_FL = new Box(16, 110, 3, 5, 3), FOOT_F = new Box(28, 110, 4, 2, 6);
    static final Box LEG_HU = new Box(48, 110, 5, 6, 5), LEG_HL = new Box(68, 110, 4, 5, 4), FOOT_H = new Box(84, 110, 5, 2, 7);

    interface P { int c(F f, int x, int y, int z); }

    static void paint(Box b, P p) {
        for (F f : F.values()) forFace(b, f, (x, y, z) -> {
            int[] q = b.px(f, x, y, z);
            base[q[1] * W + q[0]] = p.c(f, x, y, z);
        });
    }

    interface Cell { void at(int x, int y, int z); }

    static void forFace(Box b, F f, Cell c) {
        switch (f) {
            case TOP, BOTTOM -> { for (int z = 0; z < b.d; z++) for (int x = 0; x < b.w; x++) c.at(x, f == F.TOP ? 0 : b.h - 1, z); }
            case RIGHT, LEFT -> { for (int z = 0; z < b.d; z++) for (int y = 0; y < b.h; y++) c.at(f == F.RIGHT ? 0 : b.w - 1, y, z); }
            case FRONT, BACK -> { for (int y = 0; y < b.h; y++) for (int x = 0; x < b.w; x++) c.at(x, y, f == F.FRONT ? 0 : b.d - 1); }
        }
    }

    static void setBase(Box b, F f, int x, int y, int z, int rgb) {
        int[] q = b.px(f, x, y, z);
        base[q[1] * W + q[0]] = rgb == 0 ? 0 : 0xff000000 | rgb;
    }

    /** glow pixel; keeps the brighter one if something is already there. a = alpha 0..255 */
    static void setGlow(Box b, F f, int x, int y, int z, int rgb, int a) {
        int[] q = b.px(f, x, y, z);
        int i = q[1] * W + q[0];
        int old = glow[i];
        int nv = (a << 24) | rgb;
        if (old == 0 || lum(nv) * a > lum(old) * (old >>> 24)) glow[i] = nv;
    }

    static double lum(int c) { return ((c >> 16) & 255) * 0.5 + ((c >> 8) & 255) * 0.4 + (c & 255) * 0.1; }

    static int pick(int[] pal, double lv) {
        int i = (int) Math.round(lv);
        return 0xff000000 | pal[Math.max(0, Math.min(pal.length - 1, i))];
    }

    static double hash(int a, int b, int c) {
        long h = a * 0x9E3779B97F4A7C15L ^ (b * 0xC2B2AE3D27D4EB4FL) ^ (c * 0x165667B19E3779F9L) ^ 0x5DEECE66DL;
        h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32; h *= 0x94D049BB133111EBL; h ^= h >>> 29;
        return ((h >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }

    // ---------------- scales ----------------
    static final double[][] S43 = {{-1, 1, 1, 0.3}, {-0.6, 0.3, 0.3, 0}, {-2, -1.2, -1.2, -1.6}};
    static final double[][] S32 = {{-0.8, 1, 0.3}, {-1.8, -0.9, -1.1}};

    /**
     * Scale pattern: staggered (hex) lattice of scale centres, each texel belongs to the nearest centre. Inside a scale
     * the light falls from the top left; the lower rim, where the next row of scales tucks under, is a dark crevice.
     */
    /** fish scales, 4 px wide, rows offset by 2: U-shaped dark rims, light tops (H +1.6, h +0.8, d -1, D -2) */
    static final String[] TILE_BIG = {
            "DhHDDhHD",
            "h..dh..d",
            "d..dd..d",
            "HDDhHDDh",
            ".dh..dh.",
            ".dd..dd.",
    };
    static final String[] TILE_SMALL = {
            "DhHDDhHD",
            "d..dd..d",
            "HDDhHDDh",
            ".dd..dd.",
    };

    /** big body scales, 6 px wide, rows offset by 3 */
    static final String[] TILE_BODY = {
            "DdHhdDDdHhdD",
            "h.h..dh.h..d",
            ".....d.....d",
            "d....dd....d",
            "hdDDdHhdDDdH",
            "..dh.h..dh.h",
            "..d.....d...",
            "..dd....dd..",
    };

    static double scale(int a, int b, int cw, int ch, int seed) {
        String[] t = cw == 6 ? TILE_BODY : cw == 4 ? TILE_BIG : TILE_SMALL;
        int tw = t[0].length();
        char c = t[Math.floorMod(b, t.length)].charAt(Math.floorMod(a, tw));
        double v = switch (c) { case 'H' -> 1.6; case 'h' -> 0.8; case 'd' -> -1.0; case 'D' -> -2.0; default -> 0; };
        int rowH = t.length / 2;
        int row = Math.floorDiv(b, rowH);
        int col = Math.floorDiv(a + ((row & 1) == 1 ? tw / 4 : 0), tw / 2);
        return v + (hash(col, row, seed) - 0.5) * 0.5;
    }

    static double scaleHex(int a, int b, int cw, int ch, int seed) {
        double px = a + 0.5, py = b + 0.5;
        int r0 = (int) Math.floor(py / ch);
        double best = 1e9, second = 1e9, bdx = 0, bdy = 0;
        int bc = 0, br = 0;
        for (int r = r0 - 1; r <= r0 + 1; r++) {
            double off = (r & 1) == 1 ? cw / 2.0 : 0;
            int c0 = (int) Math.floor((px - off) / cw);
            for (int c = c0 - 1; c <= c0 + 1; c++) {
                double cx = c * cw + off + cw * 0.5, cy = r * ch + ch * 0.35;
                double dx = (px - cx) / cw, dy = (py - cy) / ch;
                // scales overlap downwards: stretch the metric upwards so each scale owns more area below its centre
                double d = dx * dx + (dy < 0 ? dy * dy * 2.2 : dy * dy * 0.8);
                if (d < best) { second = best; best = d; bdx = dx; bdy = dy; bc = c; br = r; } else if (d < second) second = d;
            }
        }
        double v = 0.7 - 1.9 * bdy - 0.9 * bdx;
        if (second - best < 0.12 && bdy > -0.05) v -= 1.6; // lower rim crevice
        else if (second - best < 0.12) v -= 0.5;
        return v + (hash(bc, br, seed) - 0.5) * 0.4;
    }

    static double scaleOld(int a, int b, int cw, int ch, int seed) {
        double[][] t = cw == 4 ? S43 : S32;
        int row = Math.floorDiv(b, ch);
        int aa = a + ((row & 1) == 1 ? cw / 2 : 0);
        int col = Math.floorDiv(aa, cw);
        int la = aa - col * cw, lb = b - row * ch;
        return t[lb][la] + (hash(col, row, seed) - 0.5) * 0.35;
    }

    /** mirrored coordinate across the centre line (so patterns are left/right symmetric) */
    static int sym(int x, int w) { return x < w / 2 ? (w - 1) / 2 - x + (w % 2 == 0 ? 0 : 0) : x - w / 2; }

    static double noise(int x, int y, int z, int seed) { return (hash(x * 31 + y, z * 17 + y, seed) - 0.5) * 0.2; }

    /** generic scaled hide. bright shifts all levels. */
    static int hide(Box b, F f, int x, int y, int z, int cw, int ch, int seed, double bright, int band) {
        double n = noise(x, y, z, seed);
        switch (f) {
            case TOP -> {
                int a = sym(x, b.w);
                double lv = 3.3 + bright + scale(a, z, cw, ch, seed) * 0.9 + n;
                if (x == 0 || x == b.w - 1) lv -= 0.7;
                // spine: knobbed dorsal ridge
                if (Math.abs(x - (b.w - 1) / 2.0) < 0.6 + (b.w % 2 == 0 ? 0.0 : 0.0) && b.w >= 4) {
                    lv = (Math.floorMod(z, 3) == 2) ? 1.0 + bright : 4.6 + bright + (Math.floorMod(z, 3) == 0 ? 0.9 : 0);
                }
                return pick(SC, lv);
            }
            case RIGHT, LEFT -> {
                double t = b.h > 1 ? y / (b.h - 1.0) : 0;
                double lv = 3.5 + bright - 2.3 * t + scale(z, y, cw, ch, seed + 1) * 0.9 + n;
                if (y == 0) lv += 0.3;
                if (y == b.h - 1 && b.h > 3) return pick(BE, 1.6 + n);
                return pick(SC, lv);
            }
            case FRONT, BACK -> {
                double t = b.h > 1 ? y / (b.h - 1.0) : 0;
                double lv = 3.2 + bright - 2.0 * t + scale(sym(x, b.w), y, cw, ch, seed + 2) * 0.9 + n - (f == F.BACK ? 0.4 : 0);
                return pick(SC, lv);
            }
            default -> { return belly(x, z, b.w, band, seed); }
        }
    }

    static int belly(int x, int z, int w, int band, int seed) {
        int lb = Math.floorMod(z, band);
        double lv = lb == 0 ? 1.5 : (lb == 1 ? 3.7 : 3.0 - (lb - 2) * 0.3);
        double cx = Math.abs(x - (w - 1) / 2.0) / Math.max(1, w / 2.0);
        lv -= cx * cx * 1.4;
        if (lb != 0 && (x == 0 || x == w - 1)) lv -= 0.6;
        lv += (hash(x, z, seed + 5) - 0.5) * 0.4;
        return pick(BE, lv);
    }

    // ---------------- line helpers (in face cell coordinates) ----------------
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

    static void poly(int[][] pts, Plot p) { for (int i = 0; i + 1 < pts.length; i++) line(pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1], p); }

    // =====================================================================================
    public static void main(String[] args) throws Exception {
        String root = ".";
        String preview = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--preview")) preview = args[++i];
            else root = args[i];
        }
        String tex = root + "/blood/src/main/resources/assets/hha_blood/textures/";
        paintAll();
        // sanity check: every face of every solid cuboid must be opaque (membranes + tail blade are shaped on purpose)
        Box[] solid = {BODY, SPIKE, HEART, NECK, SKULL, SNOUT, JAW, HORN, TAIL[0], TAIL[1], TAIL[2], TAIL[3], TAIL[4], ARM, TIPB,
                LEG_FU, LEG_FL, FOOT_F, LEG_HU, LEG_HL, FOOT_H};
        for (Box b : solid) for (F f : F.values()) forFace(b, f, (x, y, z) -> {
            int[] q = b.px(f, x, y, z);
            if ((base[q[1] * W + q[0]] >>> 24) != 255) System.out.println("WARNING: hole in " + b + " " + f + " at " + q[0] + "," + q[1]);
        });
        write(base, W, H, tex + "entity/cruorax.png");
        write(glow, W, H, tex + "entity/cruorax_glow.png");
        int[] icon = heartIcon();
        write(icon, 32, 32, tex + "item/heart_of_cruorax.png");
        if (preview != null) {
            new File(preview).mkdirs();
            sheetPreview(preview);
            Preview.run(preview);
            iconPreview(icon, preview);
        }
    }

    static void write(int[] px, int w, int h, String path) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < px.length; i++) img.setRGB(i % w, i / w, (px[i] >>> 24) == 0 ? 0 : px[i]);
        File f = new File(path);
        f.getParentFile().mkdirs();
        ImageIO.write(img, "png", f);
    }

    static void paintAll() {
        paintBody();
        paintHeart();
        paintSpike();
        paintNeck();
        paintSkull();
        paintSnout();
        paintJaw();
        paintHorn();
        paintTail();
        paintBlade();
        paintWingBones();
        paintMembranes();
        paintLegs();
    }

    // ---------------- body ----------------
    static void paintBody() {
        Box b = BODY;
        paint(b, (f, x, y, z) -> {
            if (f == F.RIGHT || f == F.LEFT) {
                double n = noise(x, y, z, 11);
                double t = y / 11.0;
                double lv = 3.6 - 2.5 * t + scale(z, y, 6, 4, 12) * 0.9 + n;
                // gaunt ribs pushing through the hide
                for (int k = 0; k < 5; k++) {
                    double zr = 6.5 + 3.3 * k + 0.35 * y;
                    double dz = z - zr;
                    if (y >= 2 && y <= 9) {
                        double fade = (y == 2 || y == 9) ? 0.6 : 1;
                        if (dz >= 0 && dz < 1) lv += 1.4 * fade;
                        else if (dz >= 1 && dz < 2) lv -= 1.4 * fade;
                    }
                }
                if (y == 0) lv += 0.4;
                if (y == 11) return pick(BE, 1.5 + n);
                return pick(SC, lv);
            }
            if (f == F.TOP) {
                int a = sym(x, 16);
                double lv = 3.4 + scale(a, z, 6, 4, 13) * 0.9 + noise(x, y, z, 13);
                if (x == 0 || x == 15) lv -= 0.8;
                if (x == 7 || x == 8) lv = Math.floorMod(z, 3) == 2 ? 1.0 : 4.7 + (Math.floorMod(z, 3) == 0 ? 1.0 : 0) + (x == 7 ? 0.4 : 0);
                if (x == 6 || x == 9) lv -= 0.6;
                return pick(SC, lv);
            }
            if (f == F.BOTTOM) {
                // heart cavity: heart footprint x 5..10, z 3..8
                boolean inFoot = x >= 5 && x <= 10 && z >= 3 && z <= 8;
                boolean corner = (x == 4 || x == 11) && (z == 2 || z == 9);
                boolean ring = x >= 4 && x <= 11 && z >= 2 && z <= 9 && !inFoot && !corner;
                boolean ring2 = x >= 3 && x <= 12 && z >= 1 && z <= 10 && !ring && !inFoot;
                if (inFoot) return pick(CR, 1);
                if (ring) {
                    // torn flesh around the exposed heart, broken rib ends poking in from both sides
                    boolean rib = (x == 4 || x == 11) && (z == 4 || z == 7);
                    boolean ribBar = (z == 2 || z == 9) && (x == 6 || x == 9);
                    if (rib || ribBar) return pick(BO, z == 2 || x == 4 ? 4 : 3);
                    return pick(CR, hash(x, z, 77) < 0.5 ? 0 : 1);
                }
                int c = belly(x, z, 16, 3, 14);
                if (ring2) return pick(BE, 1.2 + hash(x, z, 4));
                return c;
            }
            return hide(b, f, x, y, z, 6, 4, 15, 0, 3);
        });
        // glow: cavity ring + veins on the belly radiating from the heart
        for (int x = 4; x <= 11; x++) for (int z = 2; z <= 9; z++) {
            boolean inFoot = x >= 5 && x <= 10 && z >= 3 && z <= 8;
            if (inFoot) continue;
            int[] q = BODY.px(F.BOTTOM, x, 11, z);
            if ((base[q[1] * W + q[0]] & 0xffffff) == CR[0] || (base[q[1] * W + q[0]] & 0xffffff) == CR[1])
                setGlow(b, F.BOTTOM, x, 11, z, GL[2], 210);
        }
        int[][][] bellyVeins = {
                {{4, 6}, {2, 7}, {1, 9}, {0, 10}},
                {{11, 6}, {13, 7}, {14, 9}, {15, 10}},
                {{8, 10}, {7, 12}, {8, 14}, {7, 17}, {8, 20}},
                {{5, 10}, {4, 12}, {3, 15}},
                {{10, 10}, {11, 12}, {12, 15}},
                {{6, 1}, {5, 0}},
                {{9, 1}, {10, 0}},
        };
        for (int[][] v : bellyVeins) poly(v, (x, z) -> setGlow(b, F.BOTTOM, x, 11, z, GL[1], 230));
        for (int[][] v : bellyVeins) setGlow(b, F.BOTTOM, v[0][0], 11, v[0][1], GL[3], 255);
        // flank veins (both sides), (z, y)
        int[][][] side = {
                {{4, 11}, {5, 9}, {4, 7}, {6, 5}, {5, 3}, {6, 1}},
                {{7, 8}, {10, 9}, {13, 8}, {16, 9}, {19, 8}, {22, 9}, {25, 8}, {27, 7}},
                {{13, 8}, {14, 6}, {13, 4}},
                {{22, 9}, {23, 11}},
        };
        for (F f : new F[]{F.RIGHT, F.LEFT}) {
            int xx = f == F.RIGHT ? 0 : 15;
            for (int[][] v : side) poly(v, (z, y) -> setGlow(b, f, xx, y, z, GL[1], 200));
            setGlow(b, f, xx, 9, 5, GL[2], 255);
            setGlow(b, f, xx, 8, 13, GL[2], 255);
        }
    }

    static void paintHeart() {
        Box b = HEART;
        paint(b, (f, x, y, z) -> {
            if (f == F.BOTTOM) {
                double dd = Math.abs(x - 2.5) + Math.abs(z - 2.5);
                double lv = dd <= 1 ? 6 : dd <= 2 ? 5 : dd <= 3 ? 4 : 3;
                if ((x == z || x == 5 - z) && dd > 1) lv -= 1;
                if (x == 1 && z == 4) lv = 6;
                return pick(CR, lv);
            }
            if (f == F.TOP) return pick(CR, 1);
            double lv = y == 3 ? 4 : y == 2 ? 3 : 2;
            if (y == 3 && (f == F.FRONT || f == F.BACK) && (x == 0 || x == 5)) lv = 3;
            if (y == 3 && (f == F.RIGHT || f == F.LEFT) && (z == 0 || z == 5)) lv = 3;
            return pick(CR, lv);
        });
        forFace(b, F.BOTTOM, (x, y, z) -> {
            double dd = Math.abs(x - 2.5) + Math.abs(z - 2.5);
            int c = dd <= 1 ? GL[7] : dd <= 2 ? GL[6] : dd <= 3 ? GL[4] : GL[3];
            if ((x == z || x == 5 - z) && dd > 1) c = dd <= 3 ? GL[3] : GL[2]; // facet seams
            setGlow(b, F.BOTTOM, x, y, z, c, 255);
        });
        for (F f : new F[]{F.RIGHT, F.LEFT, F.FRONT, F.BACK}) forFace(b, f, (x, y, z) -> {
            if (y >= 2) setGlow(b, f, x, y, z, y == 3 ? GL[3] : GL[2], 255);
        });
    }

    static void paintSpike() {
        paint(SPIKE, (f, x, y, z) -> {
            // stained bone: bright tip, darkening towards a blood-soaked root
            double lv = 4.0 - 1.3 * y;
            switch (f) {
                case TOP -> { return pick(BO, z == 0 ? 4.6 : z == 1 ? 4.0 : 3.0); }
                case BOTTOM -> lv = 1;
                case RIGHT -> lv += 0.3;
                case LEFT -> lv -= 0.6;
                case BACK -> lv -= 0.5;
                default -> { }
            }
            if (f == F.FRONT && x == 0) lv += 0.4;
            if ((f == F.RIGHT || f == F.LEFT) && z == 2) lv -= 0.5; // rear edge in shadow
            if (y == 3 && f != F.TOP) return pick(SC, 2 + (x + z) % 2);
            if (y == 2 && f != F.TOP && (x + z) % 2 == 1) return pick(SC, 4);
            return pick(BO, lv);
        });
    }

    // ---------------- neck ----------------
    static void paintNeck() {
        Box b = NECK;
        paint(b, (f, x, y, z) -> hide(b, f, x, y, z, 3, 2, 21, 0.1, 2));
        for (F f : new F[]{F.RIGHT, F.LEFT}) {
            int xx = f == F.RIGHT ? 0 : 7;
            int[][] v = {{0, 5}, {3, 5}, {4, 4}, {6, 4}, {7, 5}, {9, 5}};
            poly(v, (z, y) -> setGlow(b, f, xx, y, z, GL[1], 175));
            setGlow(b, f, xx, 4, 5, GL[2], 220);
            poly(new int[][]{{6, 4}, {7, 3}, {8, 3}}, (z, y) -> setGlow(b, f, xx, y, z, GL[0], 160));
        }
        // throat veins on the underside
        poly(new int[][]{{3, 0}, {4, 3}, {3, 6}, {4, 9}}, (x, z) -> setGlow(b, F.BOTTOM, x, 7, z, GL[0], 220));
    }

    // ---------------- head ----------------
    static boolean inEyeSide(int z, int y) { return (y == 4 && (z == 0 || z == 1)) || (y == 3 && (z == 1 || z == 2)) || (y == 2 && z == 3); }
    static boolean inEyeFront(int x, int y) {
        int a = Math.min(x, 11 - x);
        return (y == 4 && a <= 1) || (y == 5 && a == 1);
    }

    static void paintSkull() {
        Box b = SKULL;
        paint(b, (f, x, y, z) -> {
            if (f == F.RIGHT || f == F.LEFT) {
                if (inEyeSide(z, y)) return pick(GL, (z == 1) ? 6 : 4);
                // heavy brow directly above the eye, dark socket around the rest
                if (inEyeSide(z, y + 1)) return pick(SC, 6.6 - z * 0.3);
                if (inEyeSide(z - 1, y) || inEyeSide(z + 1, y) || inEyeSide(z, y - 1) || inEyeSide(z - 1, y + 1)) return pick(SC, 0.6);
                // one bone spine sweeping back and up across the cheek
                int[] spineY = {-1, -1, -1, 6, 6, 6, 5, 5, 5, 4, 4, 4};
                if (spineY[z] == y) return pick(BO, 2.6 + (z - 3) * 0.33);
                if (spineY[z] == y - 1 && z >= 3) return pick(SC, 0.8);
                // hollow cheek under the eye (gaunt)
                if (y >= 6 && y <= 7 && z >= 1 && z <= 2) return pick(SC, 1.5);
                double lv = 3.5 - 2.2 * y / 9.0 + scale(z, y, 3, 2, 32) * 0.9 + noise(x, y, z, 33);
                if (y == 9) return pick(BE, 2);
                return pick(SC, lv);
            }
            if (f == F.FRONT) {
                if (inEyeFront(x, y)) return pick(GL, (Math.min(x, 11 - x) == 0) ? 4 : 5);
                int a = Math.min(x, 11 - x);
                if (y == 3 && a <= 2) return pick(SC, 1);
                if (y == 2 && a <= 3) return pick(SC, 6.5 - a * 0.3);
                if (y == 5 && a == 0) return pick(SC, 1);
                if ((x == 5 || x == 6) && y == 1) return pick(SC, 1);
                if ((x == 4 || x == 7) && y == 2) return pick(SC, 1.5);
                double lv = 3.4 - 2.0 * y / 9.0 + scale(sym(x, 12), y, 3, 2, 34) * 0.9 + noise(x, y, z, 35);
                if (y == 9) return pick(BE, 2);
                return pick(SC, lv);
            }
            if (f == F.TOP) {
                int a = sym(x, 12);
                // bone crest down the middle
                if ((x == 5 || x == 6) && z >= 3) {
                    if (z % 4 == 2) return pick(BO, 1);
                    return pick(BO, 3.8 - z * 0.12 + (x == 5 ? 0.7 : 0));
                }
                if ((x == 4 || x == 7) && z >= 3) return pick(SC, 1.5);
                // heavy brow ridges at the front
                if (z <= 1 && a >= 2 && a <= 5) return pick(SC, z == 0 ? 7 - (a - 2) * 0.3 : 5.5);
                if (z == 2 && a >= 2 && a <= 5) return pick(SC, 1.5);
                double lv = 3.4 + scale(a, z, 3, 2, 36) * 0.9 + noise(x, y, z, 37);
                if (x == 0 || x == 11) lv -= 0.7;
                return pick(SC, lv);
            }
            if (f == F.BOTTOM) {
                int c = belly(x, z, 12, 2, 38);
                return c;
            }
            return hide(b, f, x, y, z, 3, 2, 39, -0.3, 2);
        });
        // glow: eyes with a soft halo
        for (F f : new F[]{F.RIGHT, F.LEFT}) {
            int xx = f == F.RIGHT ? 0 : 11;
            for (int z = 0; z < 12; z++) for (int y = 0; y < 10; y++) {
                if (inEyeSide(z, y)) setGlow(b, f, xx, y, z, z == 1 && y == 4 ? GL[7] : z == 1 ? GL[6] : GL[4], 255);
                else if (inEyeSide(z, y + 1) || inEyeSide(z - 1, y) || inEyeSide(z + 1, y) || inEyeSide(z, y - 1))
                    setGlow(b, f, xx, y, z, GL[3], 55);
            }
        }
        for (int x = 0; x < 12; x++) for (int y = 0; y < 10; y++) {
            if (inEyeFront(x, y)) setGlow(b, F.FRONT, x, y, 0, Math.min(x, 11 - x) == 0 ? GL[6] : GL[5], 255);
            else if (inEyeFront(x, y + 1) || inEyeFront(x - 1, y) || inEyeFront(x + 1, y) || inEyeFront(x, y - 1))
                setGlow(b, F.FRONT, x, y, 0, GL[3], 55);
        }
        // throat glow on the skull underside (seen when the jaw opens)
        for (int x = 3; x <= 8; x++) for (int z = 0; z <= 2; z++) setGlow(b, F.BOTTOM, x, 9, z, GL[z == 0 ? 3 : 2], 150 - z * 30);
    }

    static boolean snoutFang(int z) { return z == 1 || z == 6; }

    static void paintSnout() {
        Box b = SNOUT;
        paint(b, (f, x, y, z) -> {
            if (f == F.FRONT) {
                if (y == 1 && (x == 1 || x == 2 || x == 5 || x == 6)) return pick(SC, 0);
                if (y == 0 && (x == 1 || x == 6)) return pick(SC, 1.5);
                if ((x == 1 || x == 6) && y >= 3) return pick(BO, y == 3 ? 4 : 5);
                if ((x == 3 || x == 4) && y == 4) return pick(BO, 5);
                if (y == 4) return pick(SC, 1);
                if (y == 3) return pick(SC, 2);
                if (y == 0) return pick(SC, 5.6 + (x == 3 || x == 4 ? 1 : 0));
                return pick(SC, 3.8 - (x == 0 || x == 7 ? 1 : 0) - (y - 1) * 0.4 + noise(x, y, z, 41));
            }
            if (f == F.RIGHT || f == F.LEFT) {
                if (y == 4) {
                    if (snoutFang(z)) return pick(BO, z == 1 ? 6 : 5);
                    if (z % 2 == 0) return pick(BO, 4.6 - z * 0.15);
                    return pick(SC, 1);
                }
                if (y == 3) {
                    if (snoutFang(z)) return pick(BO, 4);
                    return pick(SC, 1);
                }
                if (y == 0) return pick(SC, 5.6 - z * 0.1);
                double lv = 3.4 - 0.6 * y + scale(z, y, 3, 2, 42) * 0.8 + noise(x, y, z, 43);
                return pick(SC, lv);
            }
            if (f == F.TOP) {
                if ((x == 3 || x == 4) && z >= 1) return pick(SC, (z % 2 == 0 ? 6.4 : 4.8) + (x == 3 ? 0.4 : 0));
                if (z == 0 && (x == 1 || x == 6)) return pick(SC, 2);
                double lv = 3.5 + scale(sym(x, 8), z, 3, 2, 44) * 0.8 + noise(x, y, z, 45);
                if (x == 0 || x == 7) lv -= 0.8;
                return pick(SC, lv);
            }
            if (f == F.BOTTOM) {
                // palate: teeth along the rim, ridged flesh inside
                if (x == 0 || x == 7) {
                    if (snoutFang(z)) return pick(BO, 5);
                    return z % 2 == 0 ? pick(BO, 4) : pick(SC, 1);
                }
                if (z == 0) return (x == 3 || x == 4) ? pick(BO, 4) : pick(SC, 1);
                if (x == 1 || x == 6) return pick(SC, 1);
                return pick(SC, z % 2 == 0 ? 4 : 2.5);
            }
            return pick(SC, 2);
        });
        // nostril embers + mouth glow (roof of the mouth, stronger towards the throat)
        for (int x : new int[]{1, 2, 5, 6}) setGlow(b, F.FRONT, x, 1, 0, GL[0], 90);
        for (int x = 2; x <= 5; x++) for (int z = 5; z < 10; z++)
            setGlow(b, F.BOTTOM, x, 4, z, GL[z >= 8 ? 3 : z >= 7 ? 2 : 1], Math.min(230, (z - 4) * 30));
    }

    static void paintJaw() {
        Box b = JAW;
        paint(b, (f, x, y, z) -> {
            if (f == F.TOP) {
                if (x == 0 || x == 7) {
                    if (z == 2) return pick(BO, 6);
                    return z % 2 == 1 ? pick(BO, 4.5 - z * 0.1) : pick(SC, 1);
                }
                if (z == 0) return (x == 2 || x == 5) ? pick(BO, 5) : pick(SC, 1);
                // forked tongue
                if ((x == 3 || x == 4) && z >= 3) return pick(SC, z % 3 == 0 ? 4 : 5.4 - (x == 4 ? 0.8 : 0));
                if ((x == 2 && z == 1) || (x == 5 && z == 1) || ((x == 2 || x == 5) && z == 2)) return pick(SC, 4.5);
                if (x == 1 || x == 6) return pick(SC, 0.6);
                return pick(SC, 1.4 + (z % 2) * 0.6);
            }
            if (f == F.RIGHT || f == F.LEFT) {
                if (y == 0) {
                    if (z == 2) return pick(BO, 6);
                    return z % 2 == 1 ? pick(BO, 4.6 - z * 0.12) : pick(SC, 1);
                }
                if (y == 1 && z == 2) return pick(BO, 4);
                if (y == 2) return pick(BE, 2.5 + noise(x, y, z, 51));
                return pick(SC, 3.3 + scale(z, y, 3, 2, 52) * 0.6 + noise(x, y, z, 53));
            }
            if (f == F.FRONT) {
                if (y == 0) return (x == 1 || x == 3 || x == 4 || x == 6) ? pick(BO, 5) : pick(SC, 1);
                if ((x == 3 || x == 4) && y == 2) return pick(BO, 3);
                return pick(SC, 3.6 - y + noise(x, y, z, 54));
            }
            if (f == F.BOTTOM) return belly(x, z, 8, 2, 55);
            return pick(SC, 2);
        });
        // the throat glows from deep inside: only the back half of the mouth, fading towards the teeth
        for (int x = 1; x <= 6; x++) for (int z = 5; z < 11; z++) {
            boolean tongue = x == 3 || x == 4;
            int a = (z - 4) * 26 + (tongue ? 0 : 20);
            setGlow(b, F.TOP, x, 0, z, GL[z >= 9 ? 3 : z >= 7 ? 2 : 1], Math.min(230, a));
        }
    }

    static void paintHorn() {
        paint(HORN, (f, x, y, z) -> {
            if (f == F.FRONT) return pick(BO, 1);
            if (f == F.BACK) return pick(BO, 6);
            double lv = 1.2 + 5.0 * z / 11.0;
            if (z % 3 == 1 && z < 10) lv -= 0.9;
            switch (f) {
                case TOP -> lv += 0.5;
                case RIGHT -> lv += 0.2;
                case LEFT -> lv -= 0.3;
                case BOTTOM -> lv -= 0.8;
                default -> { }
            }
            return pick(BO, lv);
        });
    }

    // ---------------- tail ----------------
    static void paintTail() {
        for (int i = 0; i < 5; i++) {
            Box b = TAIL[i];
            int seed = 60 + i * 7;
            double br = -0.15 * i;
            paint(b, (f, x, y, z) -> hide(b, f, x, y, z, 3, 2, seed, br, 2));
            if (i <= 3) {
                int mid = b.h / 2;
                for (F f : new F[]{F.RIGHT, F.LEFT}) {
                    int xx = f == F.RIGHT ? 0 : b.w - 1;
                    int[][] v = {{0, mid}, {3, mid - 1}, {6, mid}, {9, mid + 1}, {11, mid}};
                    int gc = i <= 1 ? GL[1] : GL[0], ga = 200 - i * 25;
                    poly(v, (z, y) -> setGlow(b, f, xx, y, z, gc, ga));
                }
            }
        }
    }

    static final String[] BLADE_MASK = {
            "X..XXXX..X",
            "XX.XXXX.XX",
            "XXXXXXXXXX",
            "XXXXXXXXXX",
            ".XXXXXXXX.",
            "..XXXXXX..",
            "...XXXX...",
            "....XX....",
    };

    static boolean blade(int x, int z) { return x >= 0 && x < 10 && z >= 0 && z < 8 && BLADE_MASK[z].charAt(x) == 'X'; }

    static void paintBlade() {
        Box b = BLADE;
        paint(b, (f, x, y, z) -> {
            boolean on = switch (f) {
                case TOP, BOTTOM -> blade(x, z);
                case RIGHT, LEFT, FRONT, BACK -> blade(x, z);
            };
            if (!on) return 0;
            boolean edge = !blade(x - 1, z) || !blade(x + 1, z) || !blade(x, z + 1) || !blade(x, z - 1);
            double lv;
            if (x == 4 || x == 5) lv = 2.4 + z * 0.15; // central groove
            else lv = edge ? 5.6 : 4.2 - Math.abs(x - 4.5) * 0.1;
            if (z <= 1 && (x >= 3 && x <= 6)) return pick(SC, 3);
            if (f == F.BOTTOM) lv -= 0.8;
            if (f != F.TOP && f != F.BOTTOM) lv = 3;
            if (f == F.TOP && edge && (x + z) % 3 == 0 && z < 5) return pick(CR, 2);
            return pick(BO, lv);
        });
    }

    // ---------------- wings ----------------
    static void paintWingBones() {
        paint(ARM, (f, x, y, z) -> {
            if (f == F.LEFT) return pick(BO, y == 1 ? 6 : 5);
            if (f == F.RIGHT) return pick(SC, 2);
            if (x >= 17) { // knuckle
                double lv = 2.2 + (x - 17) * 0.8;
                if (f == F.TOP) lv += 0.6;
                if (f == F.BOTTOM) lv -= 1.2;
                return pick(BO, lv);
            }
            if (x == 16) return pick(SC, 1);
            double lv;
            switch (f) {
                case TOP -> lv = 4.6 + (x % 4 == 1 ? 1 : 0) - (z == 2 ? 1 : 0);
                case FRONT -> lv = 4.2 - y * 1.1;
                case BACK -> lv = 3.2 - y * 1.0;
                default -> lv = 2;
            }
            lv += noise(x, y, z, 81) + (x % 4 == 0 ? -0.9 : 0);
            return pick(SC, lv);
        });
        paint(TIPB, (f, x, y, z) -> {
            if (f == F.LEFT) return pick(BO, 6);
            if (f == F.RIGHT) return pick(BO, 3);
            if (x >= 16) {
                double lv = 2.4 + (x - 16) * 0.75;
                if (f == F.TOP || f == F.FRONT) lv += 0.5;
                if (f == F.BOTTOM) lv -= 1;
                if (x == 16 || x == 17) return pick(BO, lv - (x == 16 ? 1 : 0));
                return pick(BO, lv);
            }
            if (x <= 1) return pick(BO, 4 + (f == F.TOP ? 1 : 0) - (f == F.BOTTOM ? 1.5 : 0));
            double lv;
            switch (f) {
                case TOP -> lv = 4.6 + (x % 4 == 2 ? 1 : 0);
                case FRONT -> lv = 4 - y;
                case BACK -> lv = 3 - y;
                default -> lv = 2;
            }
            lv += noise(x, y, z, 82) + (x % 4 == 0 ? -0.9 : 0);
            return pick(SC, lv);
        });
    }

    /** combined wing plane: X 0..42 (inner 0..20, tip 20..42), Z 0..24 backwards from the leading bone. */
    static final double WX = 20, WZ = 0;
    static final double[][] WING_PTS = { // boundary points, fingers run from the wrist (20,0) to points 1..4
            {0, 24.5}, {9, 24.2}, {21.5, 22.4}, {33, 22.4}, {41.6, 10}, {42.6, -0.5}};

    static double segDist(double px, double pz, double ax, double az, double bx, double bz) {
        double vx = bx - ax, vz = bz - az;
        double t = Math.max(0, Math.min(1, ((px - ax) * vx + (pz - az) * vz) / (vx * vx + vz * vz)));
        double dx = px - (ax + t * vx), dz = pz - (az + t * vz);
        return Math.sqrt(dx * dx + dz * dz);
    }

    static final int[][] TEARS = {{6, 16}, {6, 17}, {7, 17}, {7, 18}, {30, 12}, {31, 12}, {31, 13}, {13, 9}};

    static boolean wingMask(int X, int Z) {
        if (X < 0 || Z < 0 || X >= 42 || Z >= 24) return false;
        if (X >= 20 && Z >= 22) return false;
        for (int[] t : TEARS) if (t[0] == X && t[1] == Z) return false;
        double px = X + 0.5, pz = Z + 0.5;
        double ang = Math.atan2(pz - WZ, px - WX); // 0 = towards the tip, pi = towards the body
        if (ang < 0) ang += 2 * Math.PI;
        if (ang > Math.PI) return true; // in front of the wrist line (z < 0, not possible)
        // find the chord between consecutive boundary points that covers this angle
        for (int i = 0; i + 1 < WING_PTS.length; i++) {
            double[] a = WING_PTS[i], b = WING_PTS[i + 1];
            double aa = Math.atan2(a[1] - WZ, a[0] - WX), ab = Math.atan2(b[1] - WZ, b[0] - WX);
            if (aa < 0) aa += 2 * Math.PI;
            if (ab < 0) ab = 0;
            if (ang <= aa + 1e-9 && ang >= ab - 1e-9 || (i == 0 && ang > aa)) {
                if (i == 0 && ang > aa) return true; // body side: attached, no cut
                double t = (aa - ang) / (aa - ab);
                double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
                double depth = Math.min(4.2, 0.24 * len);
                // signed distance from chord, positive towards the wrist
                double nx = -(b[1] - a[1]) / len, nz = (b[0] - a[0]) / len;
                double s = (px - a[0]) * nx + (pz - a[1]) * nz;
                double sw = (WX - a[0]) * nx + (WZ - a[1]) * nz;
                if (sw < 0) s = -s;
                return s >= depth * Math.sin(Math.PI * t) - 0.15;
            }
        }
        return true;
    }

    /** distance to the nearest finger bone; side[0] = which side of it (+1 / -1) */
    static double fingerDist(double px, double pz, int[] side) {
        double best = 99;
        for (int i = 1; i <= 4; i++) {
            double d = segDist(px, pz, WX, WZ, WING_PTS[i][0], WING_PTS[i][1]);
            if (d < best) {
                best = d;
                double cr = (WING_PTS[i][0] - WX) * (pz - WZ) - (WING_PTS[i][1] - WZ) * (px - WX);
                if (side != null) side[0] = cr >= 0 ? 1 : -1;
            }
        }
        return best;
    }

    static int wingColor(int X, int Z, boolean top) {
        if (!wingMask(X, Z)) return 0;
        double px = X + 0.5, pz = Z + 0.5;
        double r = Math.hypot(px - WX, pz - WZ);
        int[] side = new int[1];
        double fd = fingerDist(px, pz, side);
        double fw = r < 6 ? 1.15 : 0.62;
        boolean edge = !wingMask(X - 1, Z) && X > 0 || !wingMask(X + 1, Z) && X < 41 || !wingMask(X, Z + 1) || (!wingMask(X, Z - 1) && Z > 0);
        if (X == 41 && Z < 10) edge = true;
        if (fd < fw) return pick(ME, top ? (r < 4 ? 1.4 : 0.3) : 0.8); // finger bone, darker than the skin
        if (fd < fw + 1 && side[0] > 0) return pick(ME, top ? 4.2 : 2.0); // lit ridge on one side only
        if (fd < fw + 1) return pick(ME, top ? 1.6 : 2.4);
        if (edge) return pick(ME, top ? 0.8 : 1.4);
        if (Z == 0) return pick(ME, top ? 1 : 1.5);
        // thin skin stretched between the fingers: lighter in the middle of each panel
        double lv = (top ? 2.2 : 3.3) + Math.min(1.5, (fd - 1.5) * 0.33) - (Z == 1 ? 0.7 : 0);
        lv += (hash(X, Z, 91) - 0.5) * 0.35;
        // faint creases parallel to the trailing edge
        if (((int) Math.floor(r * 0.5 + hash(X / 4, Z / 4, 92) * 0.5)) % 4 == 0 && fd > 2.5) lv -= 0.45;
        return pick(ME, lv);
    }

    interface WPlot { void at(int X, int Z, int rgb, int aTop, int aBottom); }

    static void paintMembranes() {
        for (int part = 0; part < 2; part++) {
            Box b = part == 0 ? MEM : MTIP;
            int off = part == 0 ? 0 : 20;
            paint(b, (f, x, y, z) -> {
                int X = x + off;
                switch (f) {
                    case TOP: return wingColor(X, z, true);
                    case BOTTOM: return wingColor(X, z, false);
                    case FRONT: return pick(ME, 1);
                    default: return wingMask(X, z) ? pick(ME, 1) : 0;
                }
            });
        }
        // glowing veins: one wavy vein per panel between two fingers + a pair of side branches
        WPlot plot = (X, Z, rgb, aTop, aBottom) -> {
            if (!wingMask(X, Z)) return;
            Box b = X < 20 ? MEM : MTIP;
            int x = X < 20 ? X : X - 20;
            if (x >= b.w || Z >= b.d) return;
            setGlow(b, F.TOP, x, 0, Z, rgb, aTop);
            setGlow(b, F.BOTTOM, x, 0, Z, rgb, aBottom);
            int[] q = b.px(F.BOTTOM, x, 0, Z);
            int[] q2 = b.px(F.TOP, x, 0, Z);
            base[q[1] * W + q[0]] = pick(ME, 5);
            base[q2[1] * W + q2[0]] = pick(ME, 3);
        };
        for (int i = 0; i + 1 < WING_PTS.length; i++) {
            double[] a = WING_PTS[i], b = WING_PTS[i + 1];
            double aa = Math.atan2(a[1] - WZ, a[0] - WX), ab = Math.atan2(b[1] - WZ, b[0] - WX);
            double am = (aa + ab) / 2;
            double R = (Math.hypot(a[0] - WX, a[1] - WZ) + Math.hypot(b[0] - WX, b[1] - WZ)) / 2;
            if (i == WING_PTS.length - 2) R *= 0.8;
            double r0 = 2.5, r1 = R * 0.74;
            int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
            for (double r = r0; r < r1; r += 0.25) {
                double ang = am + Math.sin(r * 0.55 + i * 1.7) * 0.09;
                int X = (int) Math.floor(WX + Math.cos(ang) * r), Z = (int) Math.floor(WZ + Math.sin(ang) * r);
                if (X == lastX && Z == lastZ) continue;
                lastX = X; lastZ = Z;
                boolean hot = r < 6;
                plot.at(X, Z, hot ? GL[2] : GL[1], hot ? 170 : 115, hot ? 245 : 215);
            }
            if (R < 9) continue;
            double rb = R * 0.5;
            double ang0 = am + Math.sin(rb * 0.55 + i * 1.7) * 0.09;
            double bx = WX + Math.cos(ang0) * rb, bz = WZ + Math.sin(ang0) * rb;
            for (int s = -1; s <= 1; s += 2) {
                double da = ang0 + s * 0.6;
                double len = R * 0.2;
                lastX = Integer.MIN_VALUE;
                for (double t = 0.6; t < len; t += 0.25) {
                    int X = (int) Math.floor(bx + Math.cos(da) * t), Z = (int) Math.floor(bz + Math.sin(da) * t);
                    if (X == lastX && Z == lastZ) continue;
                    lastX = X; lastZ = Z;
                    plot.at(X, Z, GL[0], 70, 170);
                }
            }
        }
        // the big panel between the body and the first finger gets its own vein
        int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
        for (double r = 2.5; r < 15; r += 0.25) {
            double ang = 2.55 + Math.sin(r * 0.5) * 0.08;
            int X = (int) Math.floor(WX + Math.cos(ang) * r), Z = (int) Math.floor(WZ + Math.sin(ang) * r);
            if (X == lastX && Z == lastZ) continue;
            lastX = X; lastZ = Z;
            plot.at(X, Z, r < 6 ? GL[2] : GL[1], r < 6 ? 170 : 115, r < 6 ? 245 : 215);
        }
        for (double t = 0.6; t < 4.5; t += 0.25) plot.at((int) Math.floor(WX - 7.6 - t * 0.9), (int) Math.floor(4.2 + t * 0.55), GL[0], 70, 170);
        // the wrist joint glows a little
        for (int X = 18; X <= 21; X++) plot.at(X, 1, GL[2], 120, 200);
    }

    // ---------------- legs ----------------
    static void paintLegs() {
        paint(LEG_FU, (f, x, y, z) -> hide(LEG_FU, f, x, y, z, 3, 2, 101, 0, 2));
        paint(LEG_FL, (f, x, y, z) -> hide(LEG_FL, f, x, y, z, 3, 2, 102, -0.5, 2));
        paint(LEG_HU, (f, x, y, z) -> hide(LEG_HU, f, x, y, z, 3, 2, 103, 0, 2));
        paint(LEG_HL, (f, x, y, z) -> hide(LEG_HL, f, x, y, z, 3, 2, 104, -0.5, 2));
        foot(FOOT_F, new int[]{0, 3}, 105);
        foot(FOOT_H, new int[]{0, 2, 4}, 106);
    }

    static void foot(Box b, int[] claws, int seed) {
        java.util.function.IntPredicate claw = x -> { for (int c : claws) if (c == x) return true; return false; };
        paint(b, (f, x, y, z) -> {
            boolean c = claw.test(x);
            switch (f) {
                case FRONT:
                    if (c) return pick(BO, y == 0 ? 5 : 4);
                    return pick(SC, y == 0 ? 3 : 1);
                case TOP:
                    if (c && z == 0) return pick(BO, 4);
                    if (c && z == 1) return pick(BO, 2);
                    return hide(b, f, x, y, z, 3, 2, seed, -0.3, 2);
                case BOTTOM:
                    if (c && z == 0) return pick(BO, 3);
                    return pick(SC, 1.5 + noise(x, y, z, seed));
                case RIGHT:
                case LEFT:
                    if (z == 0 && c) return pick(BO, y == 0 ? 4 : 3);
                    if (z == 1 && c) return pick(BO, 2);
                    return pick(SC, 4 - y * 1.5 + noise(x, y, z, seed));
                default:
                    if (x == b.w / 2 && y == 1) return pick(BO, 3);
                    return pick(SC, 3 - y);
            }
        });
    }

    // =====================================================================================
    // item icon: heart of Cruorax (32x32)
    static int[] heartIcon() {
        int S = 32;
        int[] out = new int[S * S];
        // heart body (metaballs): right atrium (left), left ventricle (right, bigger), apex pointing down-left
        double[][] balls = {{12.2, 15.2, 6.4}, {19.0, 16.2, 7.6}, {15.0, 21.8, 6.4}, {12.8, 25.6, 3.6}, {11.4, 27.4, 1.9}};
        // vessels (capsules x0,y0,x1,y1,r): pulmonary trunk, aortic arch (2 segments), vena cava
        double[][] vessels = {{12.6, 11.0, 10.6, 4.4, 2.2}, {17.6, 11.0, 18.4, 4.6, 2.6}, {18.6, 4.4, 24.0, 5.8, 2.1}, {22.4, 10.6, 25.6, 8.8, 1.5}};
        boolean[] open = {true, false, true, true};
        int[] kind = new int[S * S];
        double[] field = new double[S * S];
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            double px = x + 0.5, py = y + 0.5, fsum = 0;
            for (double[] b : balls) {
                double d2 = (px - b[0]) * (px - b[0]) + (py - b[1]) * (py - b[1]);
                fsum += b[2] * b[2] / Math.max(0.01, d2);
            }
            field[y * S + x] = fsum;
            if (fsum > 1.4) kind[y * S + x] = 1;
            for (int v = 0; v < vessels.length; v++) {
                double[] c = vessels[v];
                if (segDist(px, py, c[0], c[1], c[2], c[3]) < c[4] && (kind[y * S + x] == 0 || fsum < 3.2)) kind[y * S + x] = 2 + v;
            }
        }
        // crystal facets: voronoi cells, each with one flat normal taken from a sphere around the heart centre
        int NS = 18;
        double[][] seeds = new double[NS][2];
        for (int i = 0; i < NS; i++) {
            seeds[i][0] = 5 + hash(i, 1, 301) * 22;
            seeds[i][1] = 8 + hash(i, 2, 301) * 22;
        }
        double[] L = {-0.6, -0.65, 0.48};
        double ln = Math.sqrt(L[0] * L[0] + L[1] * L[1] + L[2] * L[2]);
        for (int i = 0; i < 3; i++) L[i] /= ln;
        double cx = 15.8, cy = 18.0;
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            int k = kind[y * S + x];
            if (k == 0) continue;
            double px = x + 0.5, py = y + 0.5;
            double lv;
            if (k == 1) {
                int best = 0;
                double bd = 1e9, sd = 1e9;
                for (int i = 0; i < NS; i++) {
                    double d = Math.hypot(px - seeds[i][0], py - seeds[i][1]);
                    if (d < bd) { sd = bd; bd = d; best = i; } else if (d < sd) sd = d;
                }
                double sx = (seeds[best][0] * 0.55 + px * 0.45 - cx) / 12.0, sy = (seeds[best][1] * 0.55 + py * 0.45 - cy) / 12.5;
                double sz = Math.sqrt(Math.max(0.05, 1 - sx * sx - sy * sy));
                double nl = Math.sqrt(sx * sx + sy * sy + sz * sz);
                double dot = (sx * L[0] + sy * L[1] + sz * L[2]) / nl;
                lv = 0.7 + 2.5 * Math.max(0, dot);
                double rc = Math.hypot(px - 16.5, py - 19.0);
                lv += Math.max(0, 0.9 - rc / 7.0); // light from inside the crystal
                if (sd - bd < 0.6) lv -= 0.8; // facet seams
                if (dot > 0.9) lv = 4.6; // facet catching the light
            } else {
                double[] c = vessels[k - 2];
                double vx = c[2] - c[0], vy = c[3] - c[1], vl = Math.hypot(vx, vy);
                double t = ((px - c[0]) * vx + (py - c[1]) * vy) / (vl * vl);
                double tc = Math.max(0, Math.min(1, t));
                double qx = px - (c[0] + tc * vx), qy = py - (c[1] + tc * vy), ql = Math.hypot(qx, qy);
                double s = Math.min(0.98, ql / c[4]);
                double nx = ql < 1e-6 ? 0 : qx / ql * s, ny = ql < 1e-6 ? 0 : qy / ql * s, nz = Math.sqrt(1 - s * s);
                double dot = nx * L[0] + ny * L[1] + nz * L[2];
                lv = 0.8 + 2.6 * Math.max(0, dot);
                if (Math.floorMod((int) Math.floor(t * vl / 2.0), 2) == 0) lv += 0.35; // ring bands
            }
            out[y * S + x] = pick(CR, lv);
        }
        // dark seam where a vessel leaves the body or crosses another vessel
        int[] seam = out.clone();
        for (int y = 1; y < S - 1; y++) for (int x = 1; x < S - 1; x++) {
            int k = kind[y * S + x];
            if (k < 2) continue;
            int below = kind[(y + 1) * S + x], right = kind[y * S + x + 1], left = kind[y * S + x - 1];
            if (below == 1 || (below >= 2 && below != k) || (right >= 2 && right != k && k > right) || (left >= 2 && left != k && k > left))
                seam[y * S + x] = pick(CR, 0);
        }
        System.arraycopy(seam, 0, out, 0, out.length);
        // vessel openings: dark mouths with a lit rim
        for (int v = 0; v < vessels.length; v++) {
            if (!open[v]) continue;
            double[] c = vessels[v];
            double vx = c[2] - c[0], vy = c[3] - c[1], vl = Math.hypot(vx, vy);
            double ex = c[2], ey = c[3];
            for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
                if (kind[y * S + x] != 2 + v) continue;
                double along = ((x + 0.5 - ex) * vx + (y + 0.5 - ey) * vy) / vl;
                double d = Math.hypot(x + 0.5 - ex, y + 0.5 - ey);
                if (d < c[4] * 0.65 && along > -1.0) out[y * S + x] = pick(SC, 0);
                else if (d < c[4] * 1.1 && along > -1.3) out[y * S + x] = pick(CR, 4);
            }
        }
        // glowing veins (coronary arteries)
        int[][][] veins = {
                {{15, 12}, {15, 15}, {14, 18}, {14, 21}, {13, 24}, {13, 26}},
                {{15, 15}, {18, 17}, {20, 20}, {21, 23}},
                {{14, 18}, {11, 19}, {9, 21}},
                {{18, 17}, {22, 16}, {24, 17}},
                {{14, 21}, {17, 24}},
        };
        for (int[][] v : veins) poly(v, (x, y) -> { if (kind[y * S + x] == 1) out[y * S + x] = pick(GL, 3); });
        for (int[][] v : veins) poly(new int[][]{v[0], v[1]}, (x, y) -> { if (kind[y * S + x] == 1) out[y * S + x] = pick(GL, 4); });
        out[15 * S + 15] = pick(GL, 6);
        out[17 * S + 18] = pick(GL, 5);
        out[18 * S + 14] = pick(GL, 5);
        // wet specular highlights (top left)
        int[][] spec = {{9, 12}, {10, 12}, {9, 13}, {16, 13}};
        for (int[] s : spec) if (kind[s[1] * S + s[0]] == 1) out[s[1] * S + s[0]] = pick(CR, 6);
        // blood dripping from the apex
        out[29 * S + 11] = pick(SC, 6);
        out[30 * S + 11] = pick(SC, 9);
        // outline: border pixels become a very dark red, slightly lighter on the lit top-left
        int[] o2 = out.clone();
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            if (out[y * S + x] == 0) continue;
            boolean edge = false;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = x + d[0], ny = y + d[1];
                if (nx < 0 || ny < 0 || nx >= S || ny >= S || out[ny * S + nx] == 0) edge = true;
            }
            if (edge) {
                boolean lit = (x > 0 && out[y * S + x - 1] == 0) || (y > 0 && out[(y - 1) * S + x] == 0);
                o2[y * S + x] = 0xff000000 | (lit ? 0x2a070c : 0x12030a);
            }
        }
        return o2;
    }

    // =====================================================================================
    // previews
    static void sheetPreview(String dir) throws Exception {
        int s = 4;
        BufferedImage a = new BufferedImage(W * s, H * s * 2 + 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H * s; y++) for (int x = 0; x < W * s; x++) {
            int bg = ((x / 16 + y / 16) % 2 == 0) ? 0x8a8a8a : 0x7a7a7a;
            int c = base[(y / s) * W + x / s];
            int col = (c >>> 24) == 0 ? bg : c & 0xffffff;
            a.setRGB(x, y, col);
            // glow composited (alpha over), on the lower half also with the base
            int g = glow[(y / s) * W + x / s];
            int col2 = col;
            if ((g >>> 24) != 0) col2 = over(col, g);
            a.setRGB(x, y + H * s + 8, col2);
        }
        ImageIO.write(a, "png", new File(dir, "sheet_4x.png"));
        BufferedImage g = new BufferedImage(W * s, H * s, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H * s; y++) for (int x = 0; x < W * s; x++) {
            int c = glow[(y / s) * W + x / s];
            g.setRGB(x, y, (c >>> 24) == 0 ? 0x101010 : over(0, c));
        }
        ImageIO.write(g, "png", new File(dir, "glow_4x.png"));
    }

    static int over(int dst, int src) {
        int a = src >>> 24;
        int r = (((src >> 16) & 255) * a + ((dst >> 16) & 255) * (255 - a)) / 255;
        int gg = (((src >> 8) & 255) * a + ((dst >> 8) & 255) * (255 - a)) / 255;
        int b = ((src & 255) * a + (dst & 255) * (255 - a)) / 255;
        return r << 16 | gg << 8 | b;
    }

    static void iconPreview(int[] icon, String dir) throws Exception {
        BufferedImage a = new BufferedImage(32 * 8 + 32 + 64 + 3 * 16, 32 * 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++) a.setRGB(x, y, 0xc6c6c6);
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) {
            int bg = ((x / 16 + y / 16) % 2 == 0) ? 0x9a9a9a : 0x8a8a8a;
            int c = icon[(y / 8) * 32 + x / 8];
            a.setRGB(x, y, (c >>> 24) == 0 ? bg : c & 0xffffff);
        }
        // inventory slots 1x (16px -> icon downscaled? MC draws 32px textures at 16 GUI px) and 2x
        slot(a, icon, 256 + 16, 16, 1);
        slot(a, icon, 256 + 16, 64, 2);
        slot(a, icon, 256 + 16, 140, 3);
        ImageIO.write(a, "png", new File(dir, "heart_icon.png"));
    }

    static void slot(BufferedImage a, int[] icon, int ox, int oy, int gui) {
        // slot = 18 GUI px incl. border; item = 16 GUI px; at gui scale 'gui' a 32px texture -> 16*gui screen px
        int sz = 18 * gui;
        for (int y = 0; y < sz; y++) for (int x = 0; x < sz; x++) {
            int c = 0x8b8b8b;
            if (x < gui || y < gui) c = 0x373737;
            if (x >= sz - gui || y >= sz - gui) c = 0xffffff;
            a.setRGB(ox + x, oy + y, c);
        }
        int is = 16 * gui;
        for (int y = 0; y < is; y++) for (int x = 0; x < is; x++) {
            // box-filter sample
            int sx = x * 32 / is, sy = y * 32 / is;
            int c = icon[sy * 32 + sx];
            if ((c >>> 24) != 0) a.setRGB(ox + gui + x, oy + gui + y, c & 0xffffff);
        }
    }

    // =====================================================================================
    /** Small software renderer of CruoraxModel (rest pose) for checking the UV mapping. */
    static class Preview {
        static class Part {
            double ox, oy, oz, pitch, yaw, roll;
            List<double[]> cubes = new ArrayList<>(); // u v x y z w h d mirror
            List<Part> kids = new ArrayList<>();
            String name;
            Part(String n, double x, double y, double z) { name = n; ox = x; oy = y; oz = z; }
            Part cube(int u, int v, double x, double y, double z, double w, double h, double d, boolean m) {
                cubes.add(new double[]{u, v, x, y, z, w, h, d, m ? 1 : 0});
                return this;
            }
            Part add(Part p) { kids.add(p); return p; }
        }

        static Part build(double fold, double jawOpen) {
            Part root = new Part("root", 0, 0, 0);
            Part body = root.add(new Part("body", 0, 6, 0)).cube(0, 0, -8, -6, -14, 16, 12, 28, false);
            for (double z : new double[]{-11, -4, 3, 10}) body.cube(150, 40, -1, -10, z, 2, 4, 3, false);
            body.add(new Part("heart", 0, 0, 0)).cube(160, 40, -3, 3, -11, 6, 4, 6, false);
            Part n1 = body.add(neck(0, -2, -13)), n2 = n1.add(neck(0, 0, -9)), n3 = n2.add(neck(0, 0, -9));
            Part head = n3.add(new Part("head", 0, 0, -9)).cube(0, 40, -6, -5, -12, 12, 10, 12, false).cube(48, 40, -4, -2, -22, 8, 5, 10, false);
            Part jaw = head.add(new Part("jaw", 0, 3, -11)).cube(84, 40, -4, 0, -11, 8, 3, 11, false);
            Part hl = head.add(new Part("horn_left", 4, -4, -3)).cube(122, 40, -1, -1, 0, 2, 2, 12, false);
            hl.pitch = 0.45; hl.yaw = 0.3;
            Part hr = head.add(new Part("horn_right", -4, -4, -3)).cube(122, 40, -1, -1, 0, 2, 2, 12, true);
            hr.pitch = 0.45; hr.yaw = -0.3;
            double[] ts = {8, 7, 6, 5, 4};
            int[][] tuv = {{0, 62}, {40, 62}, {78, 62}, {114, 62}, {148, 62}};
            Part parent = body;
            List<Part> tail = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                double s = ts[i];
                Part t = new Part("tail" + (i + 1), 0, i == 0 ? -1 : 0, i == 0 ? 13 : 11.5).cube(tuv[i][0], tuv[i][1], -s / 2, -s / 2, 0, s, s, 12, false);
                if (i < 4) t.cube(150, 40, -1, -s / 2 - 3, 4, 2, 4, 3, false);
                if (i == 4) t.cube(180, 62, -5, -0.5, 6, 10, 1, 8, false);
                parent = parent.add(t);
                tail.add(t);
            }
            Part wl = body.add(new Part("wing_left", 7, -5, -8)).cube(124, 0, 0, -1.5, -1.5, 20, 3, 3, false).cube(0, 84, 0, 0, 0, 20, 1, 24, false);
            Part tl = wl.add(new Part("tip", 20, 0, 0)).cube(124, 6, 0, -1, -1, 22, 2, 2, false).cube(88, 84, 0, 0, 0, 22, 1, 22, false);
            Part wr = body.add(new Part("wing_right", -7, -5, -8)).cube(124, 0, -20, -1.5, -1.5, 20, 3, 3, true).cube(0, 84, -20, 0, 0, 20, 1, 24, true);
            Part tr = wr.add(new Part("tip_r", -20, 0, 0)).cube(124, 6, -22, -1, -1, 22, 2, 2, true).cube(88, 84, -22, 0, 0, 22, 1, 22, true);
            List<Part> legs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                double x = i % 2 == 0 ? 6 : -6, z = i < 2 ? -9 : 9;
                boolean hind = i >= 2, m = x < 0;
                Part u = new Part("leg", x, 5, z);
                if (hind) u.cube(48, 110, -2.5, 0, -2.5, 5, 6, 5, m); else u.cube(0, 110, -2, 0, -2, 4, 6, 4, m);
                Part l = u.add(new Part("lower", 0, 6, 0));
                if (hind) l.cube(68, 110, -2, 0, -2, 4, 5, 4, false); else l.cube(16, 110, -1.5, 0, -1.5, 3, 5, 3, false);
                Part ft = l.add(new Part("foot", 0, 5, 0));
                if (hind) ft.cube(84, 110, -2.5, 0, -5, 5, 2, 7, false); else ft.cube(28, 110, -2, 0, -4, 4, 2, 6, false);
                body.add(u);
                legs.add(u);
            }
            // ---- setAngles at age 0
            double beat = 0, glide = 0.15 * (1 - fold);
            wl.roll = -beat - glide; wr.roll = beat + glide;
            tl.roll = 0; tr.roll = 0;
            wl.yaw = -1.25 * fold; wr.yaw = 1.25 * fold;
            tl.yaw = 2.6 * fold; tr.yaw = -2.6 * fold;
            wl.pitch = 0.25 * fold; wr.pitch = 0.25 * fold;
            n1.pitch = -0.45; n2.pitch = 0.12; n3.pitch = 0.18; head.pitch = 0.2;
            jaw.pitch = 0.05 + 0.75 * jawOpen;
            for (int i = 0; i < 5; i++) {
                Part sg = tail.get(i);
                sg.yaw = Math.sin(-i * 0.7) * (0.12 + i * 0.02);
                sg.pitch = i == 0 ? 0.12 - 0.1 * fold : 0.04 * (1 - fold) + Math.sin(-i) * 0.03;
            }
            for (Part lg : legs) lg.pitch = 0.95 * (1 - fold);
            return root;
        }

        static Part neck(double x, double y, double z) {
            return new Part("neck", x, y, z).cube(88, 0, -4, -4, -10, 8, 8, 10, false).cube(150, 40, -1, -8, -7, 2, 4, 3, false);
        }

        // ---- math (row-major 4x4)
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

        /** quad: 4 vertices (world xyz) + uv per vertex + cube centre */
        record Quad(double[][] p, double[][] uv, double[] center, double[] rect, String part) {}

        static void collect(Part p, double[] parent, List<Quad> out) {
            double[] m = mul(parent, trans(p.ox, p.oy, p.oz));
            m = mul(m, mul(rz(p.roll), mul(ry(p.yaw), rx(p.pitch))));
            for (double[] c : p.cubes) {
                double u = c[0], v = c[1], x = c[2], y = c[3], z = c[4], sx = c[5], sy = c[6], sz = c[7];
                boolean mir = c[8] != 0;
                double f = x + sx, g = y + sy, h = z + sz;
                if (mir) { double t = f; f = x; x = t; }
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
                    out.add(new Quad(wp, uv, cen, new double[]{Math.min(u1, u2), Math.min(vv1, vv2), Math.max(u1, u2), Math.max(vv1, vv2)}, p.name));
                }
            }
            for (Part k : p.kids) collect(k, m, out);
        }

        static void run(String dir) throws Exception {
            List<Quad> fly = new ArrayList<>(), flyOpen = new ArrayList<>(), land = new ArrayList<>();
            collect(build(0, 0), ident(), fly);
            collect(build(0, 1), ident(), flyOpen);
            collect(build(1, 0.4), ident(), land);
            render(flyOpen, 35, 28, dir + "/r3d_front34.png", 0x6e6e6e);
            render(fly, 90, 12, dir + "/r3d_side.png", 0x6e6e6e);
            render(fly, 200, -48, dir + "/r3d_below.png", 0x6e6e6e);
            render(fly, 160, 55, dir + "/r3d_topback.png", 0x6e6e6e);
            render(land, 40, 18, dir + "/r3d_landed.png", 0x6e6e6e);
            render(flyOpen, 25, 10, dir + "/r3d_dark.png", 0x120c10);
            java.util.Set<String> headSet = java.util.Set.of("head", "jaw", "horn_left", "horn_right");
            render(flyOpen, 30, 15, dir + "/r3d_head.png", 0x6e6e6e, headSet);
            render(fly, 95, 5, dir + "/r3d_head_side.png", 0x6e6e6e, headSet);
            render(fly, 180, -60, dir + "/r3d_belly.png", 0x6e6e6e, java.util.Set.of("body", "heart", "leg", "lower", "foot"));
            render(fly, 20, 70, dir + "/r3d_wing_top.png", 0x6e6e6e, java.util.Set.of("wing_left", "tip"));
        }

        static void render(List<Quad> quads, double yawDeg, double pitchDeg, String file, int bg) throws Exception { render(quads, yawDeg, pitchDeg, file, bg, null); }

        static void render(List<Quad> quads, double yawDeg, double pitchDeg, String file, int bg, java.util.Set<String> focus) throws Exception {
            int IW = 1200, IH = 900;
            double ya = Math.toRadians(yawDeg), pa = Math.toRadians(pitchDeg);
            // world: model x,y flipped like LivingEntityRenderer.scale(-1,-1,1)
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
                    if (focus != null && !focus.contains(q.part)) continue;
                    minX = Math.min(minX, s[i][0]); maxX = Math.max(maxX, s[i][0]);
                    minY = Math.min(minY, s[i][1]); maxY = Math.max(maxY, s[i][1]);
                }
                vq.add(s);
            }
            double sc = Math.min((IW - 60) / (maxX - minX), (IH - 60) / (maxY - minY));
            double offX = IW / 2.0 - (minX + maxX) / 2 * sc, offY = IH / 2.0 - (minY + maxY) / 2 * sc;
            double[] zb = new double[IW * IH];
            java.util.Arrays.fill(zb, 1e18);
            int[] col = new int[IW * IH];
            java.util.Arrays.fill(col, bg);
            int[] qi = new int[IW * IH];
            java.util.Arrays.fill(qi, -1);
            double[][] lights = {norm(0.2, 1, -0.7), norm(-0.2, 1, 0.7)};
            for (int pass = 0; pass < 2; pass++) {
                for (int qn = 0; qn < quads.size(); qn++) {
                    Quad q = quads.get(qn);
                    double[][] s = vq.get(qn);
                    double[][] sp = new double[4][];
                    for (int i = 0; i < 4; i++) sp[i] = new double[]{s[i][0] * sc + offX, s[i][1] * sc + offY, s[i][2]};
                    // world normal (outward)
                    double[] a = q.p[0], b = q.p[1], c = q.p[2];
                    double[] e1 = {b[0] - a[0], b[1] - a[1], b[2] - a[2]}, e2 = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
                    double[] nn = {e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]};
                    double nl = Math.sqrt(nn[0] * nn[0] + nn[1] * nn[1] + nn[2] * nn[2]);
                    if (nl < 1e-9) continue;
                    double fcx = (q.p[0][0] + q.p[2][0]) / 2 - q.center[0], fcy = (q.p[0][1] + q.p[2][1]) / 2 - q.center[1], fcz = (q.p[0][2] + q.p[2][2]) / 2 - q.center[2];
                    if (nn[0] * fcx + nn[1] * fcy + nn[2] * fcz < 0) { nn[0] = -nn[0]; nn[1] = -nn[1]; nn[2] = -nn[2]; }
                    double[] wn = {-nn[0] / nl, -nn[1] / nl, nn[2] / nl}; // to world (x,y flipped)
                    double light = Math.min(1, 0.4 + 0.6 * (Math.max(0, dot(wn, lights[0])) + Math.max(0, dot(wn, lights[1]))));
                    int[] tex = pass == 0 ? base : glow;
                    final int fqn = qn;
                    final boolean first = pass == 0;
                    Frag frag = (x, y, d, u, v) -> {
                        int t = tex[v * W + u];
                        int al = t >>> 24;
                        int i = y * IW + x;
                        if (first) {
                            if (al < 26) return;
                            if (d >= zb[i]) return;
                            zb[i] = d;
                            qi[i] = fqn;
                            int r = (int) (((t >> 16) & 255) * light), g = (int) (((t >> 8) & 255) * light), bl = (int) ((t & 255) * light);
                            col[i] = r << 16 | g << 8 | bl;
                        } else {
                            if (al == 0 || qi[i] != fqn) return;
                            col[i] = over(col[i], t);
                        }
                    };
                    tri(sp[0], sp[1], sp[2], q.uv[0], q.uv[1], q.uv[2], q.rect, IW, IH, frag);
                    tri(sp[0], sp[2], sp[3], q.uv[0], q.uv[2], q.uv[3], q.rect, IW, IH, frag);
                }
            }
            BufferedImage img = new BufferedImage(IW, IH, BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, IW, IH, col, 0, IW);
            ImageIO.write(img, "png", new File(file));
        }

        interface Frag { void at(int x, int y, double depth, int u, int v); }

        static void tri(double[] a, double[] b, double[] c, double[] ta, double[] tb, double[] tc, double[] rect, int IW, int IH, Frag fr) {
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

        static double[] norm(double x, double y, double z) { double l = Math.sqrt(x * x + y * y + z * z); return new double[]{x / l, y / l, z / l}; }
        static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    }
}
