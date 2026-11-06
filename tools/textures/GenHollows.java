import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Vein Hollows ("Aderngrotten") blocks: textures, block/item models, blockstates, item definitions,
 * loot tables, recipes and block tags.
 *
 *   java tools/textures/GenHollows.java <project root> [minecraft-client.jar]
 *
 * Stairs/slab/wall blockstates + models + slab loot are copied from vanilla stone bricks
 * (default jar: ~/.gradle/caches/fabric-loom/1.21.11/minecraft-client.jar).
 */
public class GenHollows {
    static Path assets, data;
    static String jarPath;

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".");
        jarPath = args.length > 1 ? args[1] : System.getProperty("user.home") + "/.gradle/caches/fabric-loom/1.21.11/minecraft-client.jar";
        assets = root.resolve("blood/src/main/resources/assets/hha_blood");
        data = root.resolve("blood/src/main/resources/data/hha_blood");
        textures();
        assetsJson();
        dataJson();
        System.out.println("done");
    }

    // ===================================================================================== colours
    /** Texture resolution of all Vein Hollows blocks (32 px = double vanilla). */
    static final int N = 32;

    static int rgb(int r, int g, int b) { return 0xff000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b); }
    static int hex(int c) { return 0xff000000 | c; }
    static int argb(int c, int a) { return (clamp(a) << 24) | (c & 0xffffff); }
    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
    static int mix(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return rgb((int) Math.round(ar + (br - ar) * t), (int) Math.round(ag + (bg - ag) * t), (int) Math.round(ab + (bb - ab) * t));
    }
    static int shade(int c, double f) {
        return rgb((int) (((c >> 16) & 255) * f), (int) (((c >> 8) & 255) * f), (int) ((c & 255) * f));
    }
    static int w(int v) { return Math.floorMod(v, N); }
    static int lim(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Deterministic hash -> [0,1). */
    static double h01(long seed, long x, long y) {
        long h = seed * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL + y * 0x165667B19E3779F9L;
        h ^= h >>> 31; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 29; h *= 0x94D049BB133111EBL; h ^= h >>> 32;
        return (h >>> 11) * 0x1.0p-53;
    }
    /** Periodic value noise: period pw x ph pixels, cx x cy lattice cells per period (any real coordinates). */
    static double pn(double x, double y, int cx, int cy, double pw, double ph, long seed) {
        double fx = x * cx / pw, fy = y * cy / ph;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        double tx = fx - x0, ty = fy - y0;
        tx = tx * tx * (3 - 2 * tx); ty = ty * ty * (3 - 2 * ty);
        int xa = Math.floorMod(x0, cx), xb = Math.floorMod(x0 + 1, cx), ya = Math.floorMod(y0, cy), yb = Math.floorMod(y0 + 1, cy);
        double a = h01(seed, xa, ya), b = h01(seed, xb, ya), c = h01(seed, xa, yb), d = h01(seed, xb, yb);
        return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty;
    }
    /** Noise tiling on the N x N texture. */
    static double pn(double x, double y, int cells, long seed) { return pn(x, y, cells, cells, N, N, seed); }
    static double fbm(double x, double y, long seed) { return pn(x, y, 4, seed) * 0.55 + pn(x, y, 8, seed + 1) * 0.3 + pn(x, y, 16, seed + 2) * 0.15; }

    static BufferedImage img(int w, int h) { return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB); }

    static void save(BufferedImage im, String rel) throws Exception {
        Path p = assets.resolve("textures/" + rel + ".png");
        Files.createDirectories(p.getParent());
        ImageIO.write(im, "png", p.toFile());
    }
    static void mcmeta(String rel, String body) throws Exception {
        Files.writeString(assets.resolve("textures/" + rel + ".png.mcmeta"), "{\n  \"animation\": {\n" + body + "\n  }\n}\n");
    }

    static BufferedImage toImg(int[][] px) {
        BufferedImage im = img(px[0].length, px.length);
        for (int y = 0; y < px.length; y++) for (int x = 0; x < px[0].length; x++) im.setRGB(x, y, px[y][x]);
        return im;
    }
    static int[][] colour(int[][] idx, int[] pal) {
        int[][] px = new int[N][N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) px[y][x] = hex(pal[lim(idx[y][x], 0, pal.length - 1)]);
        return px;
    }

    // ============================================================================== palettes/bases
    /**
     * THE blood stone ramp, dark -> light (index 4 = base). Every block of the blood stone family (natural, bricks,
     * polished, chiseled, vein stone, altar) is painted only from these 8 values, so they sit next to each other without
     * jumps. Slight hue shift: shadows lean to crimson-violet, lights to warm brick red.
     */
    static final int[] STONE = {0x1c0409, 0x2a0710, 0x370a14, 0x430e17, 0x4f121a, 0x5c161c, 0x6a1c1f, 0x7e2626};
    static int S(int i) { return hex(STONE[lim(i, 0, STONE.length - 1)]); }
    static final int DROP_OUT = 0x220407;
    static final int[] DROP = {0x5e0a10, 0x8a0c14, 0xc0161f, 0xe0333a, 0xff6a55, 0xffc4b4};
    /** Dull blood inlay for carvings (chiseled stone): set into the stone, not glowing. */
    static final int[] INLAY = {0x2a0309, 0x4a0610, 0x5e0a12, 0x740e16, 0x8c161c};
    /** Vanilla bone block colours (bone_block_side / _top), for the altar ribs. */
    static final int BONE_HI = 0xe9e6d4, BONE = 0xe1ddca, BONE_MID = 0xc9c5a7, BONE_SH = 0x9e9a80, BONE_DK = 0x6e6a56;

    /** Natural blood stone as palette indices: lumpy height field lit from the top left, pits, grains, faint cracks. */
    static int[][] stoneIdx(long seed) {
        int[][] lv = new int[N][N];
        Random r = new Random(seed);
        // packed lumps (jittered Voronoi), each lit from the upper left, with faint crevices in between
        int g = 5;
        double cell = (double) N / g;
        double[][] site = new double[g * g][2];
        int[] tone = new int[g * g];
        int[] tones = {-1, 0, 0, 0, 0, 0, 0, 1};
        for (int i = 0; i < g * g; i++) {
            site[i][0] = (i % g + 0.2 + r.nextDouble() * 0.6) * cell;
            site[i][1] = (i / g + 0.2 + r.nextDouble() * 0.6) * cell;
            tone[i] = tones[r.nextInt(tones.length)];
        }
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            double f1 = 1e9, f2 = 1e9, ox = 0, oy = 0; int id = 0;
            for (int i = 0; i < g * g; i++) for (int sx = -N; sx <= N; sx += N) for (int sy = -N; sy <= N; sy += N) {
                double wx = x + 0.5 + (pn(x, y, 4, seed + 21) - 0.5) * 4.5, wy = y + 0.5 + (pn(x, y, 4, seed + 22) - 0.5) * 4.5;
            double dx = wx - site[i][0] - sx, dy = wy - site[i][1] - sy, d = Math.hypot(dx, dy);
                if (d < f1) { f2 = f1; f1 = d; id = i; ox = dx; oy = dy; } else if (d < f2) f2 = d;
            }
            double lt = -(ox + oy) / Math.sqrt(2) / (cell * 0.55);           // >0: upper-left part of the lump
            double grain = pn(x, y, 16, seed + 11) - 0.5 + (pn(x, y, 2, seed + 7) - 0.5) * 0.8;
            int l = 4 + tone[id];
            if (lt > 0.42) l++;
            else if (lt < -0.5) l--;
            if (grain > 0.28) l++;
            else if (grain < -0.3) l--;
            if (f2 - f1 < 1.0) l = pn(x, y, 8, seed + 3) > 0.35 ? Math.min(l - 1, 3) : l - 1;   // crevice, with gaps
            lv[y][x] = lim(l, 2, 6);
        }
        // pits: dark hole, lit lower-right inner wall
        for (int k = 0; k < 6; k++) {
            int x = r.nextInt(N), y = r.nextInt(N);
            lv[y][x] = 1;
            if (r.nextBoolean()) lv[y][w(x + 1)] = r.nextBoolean() ? 1 : 2;
            if (k % 3 == 0) lv[w(y + 1)][x] = 2;
            lv[w(y + 1)][w(x + 1)] = Math.max(lv[w(y + 1)][w(x + 1)], 5);
        }
        // light grains with a tiny shadow below-right
        for (int k = 0; k < 14; k++) {
            int x = r.nextInt(N), y = r.nextInt(N);
            if (lv[y][x] < 4) continue;
            lv[y][x] = r.nextDouble() < 0.4 ? 7 : 6;
            lv[w(y + 1)][w(x + 1)] = Math.min(lv[w(y + 1)][w(x + 1)], 3);
        }
        // two short hairline cracks
        int[][] starts = {{r.nextInt(N), r.nextInt(N)}, {r.nextInt(N), r.nextInt(N)}};
        for (int[] s : starts) {
            int x = s[0], y = s[1], dir = r.nextBoolean() ? 1 : -1;
            int len = 5 + r.nextInt(4);
            for (int i = 0; i < len; i++) {
                lv[w(y)][w(x)] = i == 0 || i == len - 1 ? 3 : 2;
                if (lv[w(y + 1)][w(x)] >= 3 && r.nextDouble() < 0.5) lv[w(y + 1)][w(x)] = Math.min(6, lv[w(y + 1)][w(x)] + 1);
                if (r.nextDouble() < 0.6) x += dir; else y++;
                if (r.nextDouble() < 0.5) y++;
            }
        }
        return lv;
    }

    /** One brick (w x h, top-left x0/y0, wrapping) with its own tone, bevel, chips and maybe a crack. */
    static void paintBrick(int[][] px, int x0, int y0, int bw, int bh, Random r, long seed, int[] pal) {
        int[] shifts = {-1, 0, 0, 0, 1};
        int shift = shifts[r.nextInt(shifts.length)];
        int[][] lv = new int[bh][bw];
        for (int ly = 0; ly < bh; ly++) for (int lx = 0; lx < bw; lx++) {
            int x = w(x0 + lx), y = w(y0 + ly);
            double n = pn(x, y, 8, seed) * 0.6 + pn(x, y, 16, seed + 1) * 0.4;
            int l = 4 + shift + (n > 0.68 ? 1 : n < 0.3 ? -1 : 0);
            if (ly >= bh - 3 && n < 0.45) l--;                       // lower part a bit darker
            if (ly == 0) l += 1;
            if (lx == 0) l += 1;
            if (ly == 0 && lx == 0) l += 1;
            if (ly == bh - 1) l -= 1;
            if (lx == bw - 1) l -= 1;
            lv[ly][lx] = lim(l, 1, 7);
        }
        // scattered grains
        for (int k = 0; k < 2; k++) {
            int gx = 1 + r.nextInt(bw - 3), gy = 1 + r.nextInt(bh - 3);
            lv[gy][gx] = lim(lv[gy][gx] + 2, 1, 7);
            lv[gy + 1][gx + 1] = lim(lv[gy + 1][gx + 1] - 1, 1, 7);
        }
        // crack: from the top or bottom edge a few pixels into the brick
        if (r.nextDouble() < 0.45) {
            boolean top = r.nextBoolean();
            int cx = lim(3 + r.nextInt(Math.max(1, bw - 6)), 1, bw - 2), cy = top ? 0 : bh - 1;
            int len = 2 + r.nextInt(3);
            for (int i = 0; i < len && cy >= 0 && cy < bh; i++) {
                lv[cy][cx] = 1;
                if (cx + 1 < bw && lv[cy][cx + 1] > 1) lv[cy][cx + 1] = lim(lv[cy][cx + 1] + 1, 1, 7);
                cy += top ? 1 : -1;
                if (r.nextDouble() < 0.5) cx = lim(cx + (r.nextBoolean() ? 1 : -1), 1, bw - 2);
            }
        }
        for (int ly = 0; ly < bh; ly++) for (int lx = 0; lx < bw; lx++) px[w(y0 + ly)][w(x0 + lx)] = hex(pal[lv[ly][lx]]);
        // chipped corners
        int[][] corners = {{0, 0, 1, 1}, {bw - 1, 0, -1, 1}, {0, bh - 1, 1, -1}, {bw - 1, bh - 1, -1, -1}};
        for (int[] c : corners) {
            if (r.nextDouble() > 0.35) continue;
            px[w(y0 + c[1])][w(x0 + c[0])] = S(1);
            if (r.nextDouble() < 0.4) px[w(y0 + c[1])][w(x0 + c[0] + c[2])] = S(1);
            px[w(y0 + c[1] + c[3])][w(x0 + c[0] + c[2])] = hex(pal[2]);
        }
    }

    /** Mortar / joints: ramp index 1 with a few darker and lighter specks. */
    static int mortarAt(int x, int y, long seed) {
        double n = h01(seed, x, y);
        return S(n < 0.2 ? 0 : n > 0.9 ? 2 : 1);
    }

    static int[][] bricks(long seed) {
        int[][] px = new int[N][N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) px[y][x] = mortarAt(x, y, seed);
        Random r = new Random(seed);
        for (int row = 0; row < 4; row++) {
            int off = (row % 2) * 8;
            for (int k = 0; k < 2; k++) paintBrick(px, off + k * 16, row * 8, 15, 7, r, seed + row * 13 + k, STONE);
        }
        return px;
    }

    /**
     * Polished surface as ramp indices: the same grain noise as the natural stone, only flattened (levels 3..5) - a
     * ground slab of the same rock. Sparse grains and tiny pits with the same top-left light as the stone.
     */
    static int[][] polishedIdx(long seed) {
        int[][] lv = new int[N][N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            double n = pn(x, y, 4, seed) * 0.6 + pn(x, y, 8, seed + 1) * 0.4;
            int l = n > 0.64 ? 5 : n < 0.32 ? 3 : 4;
            double g = pn(x, y, 16, seed + 2);
            if (g > 0.8 && l < 5) l++;
            else if (g < 0.18 && l > 3) l--;
            lv[y][x] = l;
        }
        Random r = new Random(seed);
        for (int k = 0; k < 7; k++) {                       // light grains, shadow below-right
            int x = 3 + r.nextInt(N - 6), y = 3 + r.nextInt(N - 6);
            lv[y][x] = 6;
            lv[y + 1][x + 1] = Math.min(lv[y + 1][x + 1], 3);
        }
        for (int k = 0; k < 3; k++) {                       // pits, lit lower-right rim
            int x = 3 + r.nextInt(N - 6), y = 3 + r.nextInt(N - 6);
            lv[y][x] = 2;
            lv[y + 1][x + 1] = Math.max(lv[y + 1][x + 1], 5);
        }
        return lv;
    }

    /** Raised bevel in ramp indices: top/left 6,5 - bottom/right 2,3 (the same edge treatment as the bricks). */
    static void bevelIdx(int[][] lv, int x0, int y0, int x1, int y1) {
        for (int i = x0 + 1; i < x1; i++) { lv[y0 + 1][i] = 5; lv[y1 - 1][i] = 3; }
        for (int i = y0 + 1; i < y1; i++) { lv[i][x0 + 1] = 5; lv[i][x1 - 1] = 3; }
        for (int i = x0; i <= x1; i++) { lv[y0][i] = 6; lv[y1][i] = 2; }
        for (int i = y0; i <= y1; i++) { lv[i][x0] = 6; lv[i][x1] = 2; }
        lv[y1][x0] = 4; lv[y0][x1] = 4;                               // mixed corners
        lv[y1 - 1][x0 + 1] = 4; lv[y0 + 1][x1 - 1] = 4;
    }

    /** Recessed (engraved) rectangle outline: dark top/left wall, lit bottom/right lip. */
    static void insetIdx(int[][] lv, int x0, int y0, int x1, int y1, int dark, int light) {
        for (int i = x0; i <= x1; i++) { lv[y0][i] = dark; lv[y1][i] = light; }
        for (int i = y0; i <= y1; i++) { lv[i][x0] = dark; lv[i][x1] = light; }
        lv[y1][x0] = (dark + light) / 2; lv[y0][x1] = (dark + light) / 2;
    }

    static int[][] polished(long seed) {
        int[][] lv = polishedIdx(seed);
        bevelIdx(lv, 0, 0, N - 1, N - 1);
        return colour(lv, STONE);
    }

    // ===================================================================================== textures
    static void textures() throws Exception {
        save(toImg(colour(stoneIdx(11), STONE)), "block/blood_stone");
        save(toImg(bricks(21)), "block/blood_stone_bricks");
        save(toImg(polished(31)), "block/polished_blood_stone");
        save(toImg(chiseled()), "block/chiseled_blood_stone");
        veinStone();
        save(toImg(crystalBlock()), "block/blood_crystal_block");
        save(shards(new double[][]{
            {11.0, 33, 14, 2.6, -15}, {21.0, 33, 16, 2.6, 13},          // back
            {6.5, 33, 17, 3.4, -32}, {25.8, 33, 19, 3.4, 30},            // sides
            {15.6, 33, 30, 4.6, 3}}, false), "block/blood_crystal_cluster");
        save(shards(new double[][]{
            {10.0, 33, 11, 2.6, -24}, {22.0, 33, 12, 2.6, 22},
            {15.6, 33, 17, 3.6, 2}}, false), "block/small_blood_crystal");
        save(shards(new double[][]{{6.2, 26.3, 29, 4.0, 45}}, true), "item/blood_crystal_shard");
        save(clottedSkin(), "block/clotted_blood_bottom");
        save(clottedCore(true), "block/clotted_blood_top");
        save(clottedCore(false), "block/clotted_blood_side");
        save(toImg(altarTop()), "block/sanguine_altar_top");
        save(toImg(altarSide()), "block/sanguine_altar_side");
        bloodStill();
        bloodFlow();
    }

    // ------------------------------------------------------------------------------- drop emblem
    static boolean inDrop(double x, double y, double cx, double cy, double rad, double tipY) {
        double dx = x - cx, dy = y - cy;
        if (dy >= 0) return dx * dx + dy * dy <= rad * rad;
        if (y < tipY) return false;
        double t = (y - tipY) / (cy - tipY);
        double half = rad * Math.sin(t * Math.PI / 2) * Math.pow(t, 0.55);
        return Math.abs(dx) <= half;
    }
    /** Shaded blood drop with dark outline; light from the upper left. */
    static void drawDrop(int[][] px, double cx, double cy, double rad, double tipY, boolean spec) {
        int H = px.length, W = px[0].length;
        boolean[][] in = new boolean[H][W];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) in[y][x] = inDrop(x + 0.5, y + 0.5, cx, cy, rad, tipY);
        double lx = cx - rad * 0.42, ly = cy - rad * 0.25;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            if (in[y][x]) {
                double d = Math.hypot(x + 0.5 - lx, y + 0.5 - ly) / (rad * 1.55);
                int i = d < 0.2 ? 4 : d < 0.45 ? 3 : d < 0.75 ? 2 : d < 1.0 ? 1 : 0;
                px[y][x] = hex(DROP[i]);
            } else {
                boolean edge = (y > 0 && in[y - 1][x]) || (y < H - 1 && in[y + 1][x]) || (x > 0 && in[y][x - 1]) || (x < W - 1 && in[y][x + 1]);
                if (edge) px[y][x] = hex(DROP_OUT);
            }
        }
        if (spec) {
            int sx = (int) Math.floor(lx), sy = (int) Math.floor(ly);
            if (in[sy][sx]) px[sy][sx] = hex(DROP[5]);
        }
    }

    /**
     * Chiseled: a polished slab (same bevel) with a shallow recessed panel, an engraved ring and a carved drop with a
     * dull blood inlay. Only stone values + a dark inlay, so a whole ring of these in the floor stays calm.
     */
    static int[][] chiseled() {
        int[][] lv = polishedIdx(41);
        bevelIdx(lv, 0, 0, N - 1, N - 1);
        // recessed panel 4..27: one step darker, engraved outline at 3/28
        for (int y = 4; y <= 27; y++) for (int x = 4; x <= 27; x++) lv[y][x] = lim(lv[y][x] - (lv[y][x] >= 5 ? 1 : 0), 3, 4);
        insetIdx(lv, 3, 3, 28, 28, 2, 5);
        for (int i = 4; i <= 27; i++) { lv[4][i] = 3; lv[i][4] = 3; }          // shadow cast by the panel edge
        // engraved ring
        double c = 16.0, rOut = 9.6, rIn = 7.6;
        for (int y = 5; y <= 27; y++) for (int x = 5; x <= 27; x++) {
            double dx = x + 0.5 - c, dy = y + 0.5 - c, d = Math.hypot(dx, dy);
            double tl = -(dx + dy) / Math.max(d, 0.01) / Math.sqrt(2);    // +1 = towards the upper left
            if (d >= rIn && d < rOut) {
                int l = 2;
                if (d > rOut - 1.0 && tl > 0.3) l = 1;                     // shadowed outer wall (upper left)
                else if (d > rOut - 1.0 && tl < -0.3) l = 5;               // lit outer wall (lower right)
                lv[y][x] = l;
            } else if (d >= rIn - 1.0 && d < rIn) {
                lv[y][x] = tl > 0.25 ? 5 : tl < -0.25 ? 3 : 4;             // raised disc rim
            } else if (d < rIn - 1.0) {
                lv[y][x] = 4;
            }
        }
        // four short notches outward from the ring (cardinal points), engraved like the ring
        for (int i = 5; i <= 6; i++) {
            lv[i][15] = 2; lv[i][16] = 2; lv[i][17] = 5;                  // top
            lv[31 - i][15] = 2; lv[31 - i][16] = 2; lv[31 - i][17] = 5;   // bottom (y 25..26)
            lv[15][i] = 2; lv[16][i] = 2; lv[17][i] = 5;                  // left
            lv[15][31 - i] = 2; lv[16][31 - i] = 2; lv[17][31 - i] = 5;   // right
        }
        // corner rivets
        int[][] studs = {{6, 6}, {25, 6}, {6, 25}, {25, 25}};
        for (int[] s : studs) {
            int x = s[0], y = s[1];
            lv[y][x] = 5; lv[y][x + 1] = 4; lv[y + 1][x] = 4; lv[y + 1][x + 1] = 2;
        }
        int[][] px = colour(lv, STONE);
        // carved drop with a dull blood inlay
        double cx = 16.0, cy = 18.4, rad = 3.7, tip = 10.6;
        boolean[][] in = new boolean[N][N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) in[y][x] = inDrop(x + 0.5, y + 0.5, cx, cy, rad, tip);
        for (int y = 1; y < N - 1; y++) for (int x = 1; x < N - 1; x++) {
            if (in[y][x]) {
                boolean upLeft = !in[y - 1][x] || !in[y][x - 1];
                boolean downRight = !in[y + 1][x] || !in[y][x + 1];
                double d = Math.hypot(x + 0.5 - (cx - 1.2), y + 0.5 - (cy - 1.0)) / rad;
                int i = upLeft ? 0 : d < 0.45 ? 3 : d < 0.95 ? 2 : 1;
                if (downRight && !upLeft) i = Math.max(i, 2);
                px[y][x] = hex(INLAY[i]);
            } else if (in[y - 1][x] || in[y][x - 1]) {
                px[y][x] = S(5);                                          // lit lip below/right of the carving
            } else if (in[y + 1][x] || in[y][x + 1]) {
                px[y][x] = S(2);                                          // carved edge above/left
            }
        }
        int hx = (int) Math.floor(cx - 1.3), hy = (int) Math.floor(cy - 1.2);
        px[hy][hx] = hex(INLAY[4]);                                       // one wet glint
        return px;
    }

    // ------------------------------------------------------------------------------- vein stone
    /** Stamps a brush along a curve; brush 2 = 2x2 (main vein), 1 = single pixel (branch). */
    static void stamp(double[][] mask, double[][] side, double x, double y, int brush, double val, double tx, double ty) {
        if (brush == 2) {
            int bx = (int) Math.floor(x - 0.5), by = (int) Math.floor(y - 0.5);
            for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++) {
                int px = w(bx + i), py = w(by + j);
                if (val >= mask[py][px]) {
                    mask[py][px] = val;
                    // which side of the centre line (normal of the tangent) faces the upper-left light
                    double ox = bx + i + 0.5 - x, oy = by + j + 0.5 - y;
                    double nx = -ty, ny = tx;
                    double s = (ox * nx + oy * ny) * Math.signum(-(nx + ny) + 1e-9);
                    side[py][px] = s;
                }
            }
        } else {
            int px = w((int) Math.floor(x)), py = w((int) Math.floor(y));
            if (val > mask[py][px]) { mask[py][px] = val; side[py][px] = 0; }
        }
    }

    static void branch(double[][] mask, double[][] side, double x, double y, double ang, int len, double val, Random r, int depth) {
        double curl = (r.nextBoolean() ? 1 : -1) * (0.06 + r.nextDouble() * 0.08);
        double wig = r.nextDouble() * 6;
        boolean forked = false;
        for (double s = 0; s < len; s += 0.2) {
            double t = s / len;
            stamp(mask, side, x, y, 1, val * (1 - 0.5 * t), Math.cos(ang), Math.sin(ang));
            x += Math.cos(ang) * 0.2; y += Math.sin(ang) * 0.2;
            ang += (curl + Math.sin(s * 0.7 + wig) * 0.06) * 0.2;
            if (depth > 0 && !forked && s >= len * 0.5) {
                forked = true;
                double sa = ang - Math.signum(curl) * 0.85;
                branch(mask, side, x, y, sa, Math.max(4, len / 2), val * 0.8, r, depth - 1);
            }
        }
    }

    static void veinStone() throws Exception {
        int[][] base = stoneIdx(51);
        double[][] mask = new double[N][N], side = new double[N][N];
        Random r = new Random(53);
        double tau = Math.PI * 2;
        // main vein A: horizontal meander; main vein B: runs top -> bottom while drifting one tile to the right
        // (both periodic over the tile -> seamless). Branches: {position, angle to the vein, length, sub-branch?}
        double[][] branchesA = {{2, -1.0, 11, 1}, {12, 0.9, 13, 0}, {23, -0.8, 9, 0}};
        double[][] branchesB = {{5, -1.1, 10, 0}, {16, 1.0, 12, 1}, {27, -0.9, 8, 0}};
        for (int vein = 0; vein < 2; vein++) {
            double[][] br = vein == 0 ? branchesA : branchesB;
            int nb = 0;
            for (double s = 0; s < N; s += 0.1) {
                double x, y, tx, ty;
                if (vein == 0) {
                    x = s; y = 7.0 + 4.0 * Math.sin(tau * s / N + 0.4) + 1.6 * Math.sin(2 * tau * s / N + 1.9);
                    tx = 1; ty = 4.0 * tau / N * Math.cos(tau * s / N + 0.4) + 1.6 * 2 * tau / N * Math.cos(2 * tau * s / N + 1.9);
                } else {
                    y = s; x = 21.0 + 6.0 * Math.sin(tau * s / N + 2.1) + 1.6 * Math.sin(2 * tau * s / N + 0.3);
                    ty = 1; tx = 6.0 * tau / N * Math.cos(tau * s / N + 2.1) + 1.6 * 2 * tau / N * Math.cos(2 * tau * s / N + 0.3);
                }
                double tl = Math.hypot(tx, ty);
                stamp(mask, side, x, y, 2, 1.0, tx / tl, ty / tl);
                if (nb < br.length && s >= br[nb][0]) {
                    double ang = Math.atan2(ty, tx) + br[nb][1];
                    branch(mask, side, x + Math.cos(ang), y + Math.sin(ang), ang, (int) br[nb][2], 0.8, r, (int) br[nb][3]);
                    nb++;
                }
            }
        }
        double[][] halo = new double[N][N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            if (mask[y][x] > 0) continue;
            double m = 0;
            for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) {
                double v = mask[w(y + j)][w(x + i)] * (i == 0 || j == 0 ? 1.0 : 0.55);
                m = Math.max(m, v);
            }
            halo[y][x] = m;
        }
        double[] beat = {0.22, 1.0, 0.6, 0.88, 0.5, 0.32, 0.25, 0.22};   // lub-dub
        BufferedImage im = img(N, N * beat.length);
        for (int f = 0; f < beat.length; f++) {
            double I = beat[f];
            for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
                int lvl = base[y][x];
                int c = S(lvl);                                              // same base as blood stone
                double m = mask[y][x];
                if (m > 0) {
                    double k = I * (0.35 + 0.65 * m);
                    if (side[y][x] > 0.05) k += 0.12 * I + 0.05;            // lit side of the tube
                    else if (side[y][x] < -0.05) k -= 0.08;
                    k = Math.round(Math.max(0, Math.min(1, k)) * 6) / 6.0;  // limited ramp
                    c = mix(hex(0x5a080e), hex(0xff3a2e), 0.08 + k * 0.92);
                    if (m >= 1.0 && I > 0.75 && side[y][x] > 0.05) c = mix(c, hex(0xffb090), (I - 0.7) * 2.2);
                } else if (halo[y][x] > 0) {
                    double g = halo[y][x] * I;
                    int groove = S(Math.max(1, lvl - 2));
                    c = g < 0.3 ? groove : mix(groove, hex(0x8a0c14), Math.round((g - 0.3) * 4) / 3.0 * 0.85);
                }
                im.setRGB(x, f * N + y, c);
            }
        }
        save(im, "block/vein_stone");
        mcmeta("block/vein_stone", "    \"frametime\": 3,\n    \"interpolate\": true");
    }

    // ------------------------------------------------------------------------------- crystal block
    static final int[] CRYS = {0x2a0207, 0x3c030a, 0x50050e, 0x660811, 0x7e0c16, 0x96111c, 0xae1622};

    static int[][] crystalBlock() {
        Random r = new Random(61);
        int g = 4, n = g * g;
        double[][] pts = new double[n][2];
        int[] base = new int[n];
        double[] nx = new double[n], ny = new double[n];
        for (int i = 0; i < n; i++) {
            pts[i][0] = (i % g) * 8 + 1.5 + r.nextDouble() * 5;
            pts[i][1] = (i / g) * 8 + 1.5 + r.nextDouble() * 5;
            base[i] = 1 + r.nextInt(4);
            double a = r.nextDouble() * Math.PI * 2;
            nx[i] = Math.cos(a); ny[i] = Math.sin(a);
        }
        int[][] id = new int[N][N];
        double[][] proj = new double[N][N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            double best = 1e9;
            for (int i = 0; i < n; i++) for (int ox = -N; ox <= N; ox += N) for (int oy = -N; oy <= N; oy += N) {
                double dx = x + 0.5 - (pts[i][0] + ox), dy = y + 0.5 - (pts[i][1] + oy);
                double d = dx * dx + dy * dy;
                if (d < best) { best = d; id[y][x] = i; proj[y][x] = dx * nx[i] + dy * ny[i]; }
            }
        }
        int[][] px = new int[N][N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            int i = id[y][x];
            int lv = lim((int) Math.round(base[i] + proj[y][x] / 4.5 * 1.3), 0, CRYS.length - 1);
            px[y][x] = hex(CRYS[lv]);
        }
        // facet edges: 1 px line on the upper/left facet of every boundary; brighter at junctions
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            int i = id[y][x];
            boolean e = id[y][w(x + 1)] != i || id[w(y + 1)][x] != i;
            if (!e) continue;
            java.util.Set<Integer> s = new java.util.HashSet<>();
            for (int j = -1; j <= 1; j++) for (int k = -1; k <= 1; k++) s.add(id[w(y + j)][w(x + k)]);
            int lit = s.size() >= 3 ? 0xe0303a : (base[i] >= 3 ? 0xc8222e : 0xa81a26);
            px[y][x] = hex(lit);
            // dark seam just past the edge on the lower/right facet -> depth
            if (id[y][w(x + 1)] != i && h01(62, x, y) < 0.7) px[y][w(x + 2)] = hex(CRYS[Math.max(0, base[id[y][w(x + 2)]] - 1)]);
        }
        // sparkles
        int[][] sp = {{6, 5}, {21, 12}, {12, 24}, {27, 27}};
        for (int k = 0; k < sp.length; k++) {
            int x = sp[k][0], y = sp[k][1];
            px[y][x] = hex(0xffd8d0);
            if (k % 2 == 0) {
                px[y][w(x - 1)] = hex(0xff6a5c); px[y][w(x + 1)] = hex(0xff6a5c);
                px[w(y - 1)][x] = hex(0xff6a5c); px[w(y + 1)][x] = hex(0xff6a5c);
            } else px[w(y + 1)][w(x + 1)] = hex(0xe0303a);
        }
        return px;
    }

    // ------------------------------------------------------------------------------- crystal sprites
    static final int SH_OUT = 0x36030a, SH_D2 = 0x56060e, SH_D = 0x780a13, SH_M = 0xa6121c, SH_B = 0xd6232c, SH_L = 0xff5a4e,
        SH_H = 0xff9c8a, SH_T = 0xffd4c8;

    /** Crystal shards {baseX, baseY, length, halfWidth, angleDeg} drawn back to front; item = light from the upper left. */
    static BufferedImage shards(double[][] list, boolean item) {
        BufferedImage im = img(N, N);
        for (double[] s : list) drawShard(im, s[0], s[1], s[2], s[3], s[4], item);
        return im;
    }

    static void drawShard(BufferedImage im, double bx, double by, double len, double hw, double angDeg, boolean item) {
        double a = Math.toRadians(angDeg);
        double dx = Math.sin(a), dy = -Math.cos(a);        // axis towards the tip
        double nx = Math.cos(a), ny = Math.sin(a);         // across, to the right of the axis
        boolean[][] in = new boolean[N][N];
        double[][] U = new double[N][N], T = new double[N][N];
        double ts = item ? 0.7 : 0.64;
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            double rx = x + 0.5 - bx, ry = y + 0.5 - by;
            double t = (rx * dx + ry * dy) / len, u = rx * nx + ry * ny;
            if (t < 0 || t > 1) continue;
            double half = t < ts ? hw * (1 - 0.08 * t) : hw * (1 - 0.08 * ts) * (1 - (t - ts) / (1 - ts));
            if (item && t < 0.07) half *= 0.7 + t / 0.07 * 0.3;     // broken-off bottom end
            half = Math.max(half, t < 0.985 ? 0.55 : 0);
            if (Math.abs(u) > half) continue;
            in[y][x] = true; U[y][x] = u / half; T[y][x] = t;
        }
        int[][] face = new int[N][N];                       // 0 left, 1 front, 2 right
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) if (in[y][x]) {
            double u = item ? -U[y][x] : U[y][x];
            face[y][x] = u < -0.34 ? 0 : u < 0.3 ? 1 : 2;
        }
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            if (!in[y][x]) continue;
            boolean edge = (y > 0 && !in[y - 1][x]) || (y < N - 1 && !in[y + 1][x]) || (y == N - 1 && item)
                || x == 0 || x == N - 1 || !in[y][x - 1] || !in[y][x + 1];
            double t = T[y][x];
            int f = face[y][x];
            int c;
            if (edge) c = SH_OUT;
            else {
                // item: faces flip so the lit side is the upper-left one
                int lit = item ? 2 - f : f;
                c = lit == 0 ? (t < 0.14 ? SH_D2 : SH_D) : lit == 1 ? SH_M : SH_B;
                // highlight line along the front/right facet ridge
                if (lit == 2 && x > 0 && in[y][x - 1] && (item ? 2 - face[y][x - 1] : face[y][x - 1]) == 1 && t > 0.12) c = SH_L;
                if (item && lit == 2 && y > 0 && in[y - 1][x] && 2 - face[y - 1][x] == 1 && t > 0.12) c = SH_L;
                if (lit == 1 && Math.abs((item ? -U[y][x] : U[y][x]) + 0.02) < 0.13 && t > 0.3 && t < 0.62) c = SH_H;
                if (t > 0.8) c = lit == 0 ? SH_M : lit == 1 ? SH_B : SH_L;
                if (t > 0.9 && lit > 0) c = SH_T;
                if (t < 0.08 && !item) c = lit == 0 ? SH_D2 : lit == 1 ? SH_D : SH_M;
            }
            im.setRGB(x, y, hex(c));
        }
    }

    // ------------------------------------------------------------------------------- clotted blood
    // Built like vanilla honey: the model has a translucent outer skin cube (texture "bottom" on all faces, like
    // honey_block_bottom) around a smaller, denser inner core cube (uv 1..15 of "top"/"side").
    /** Outer skin: thin, glossy, translucent blood jelly. */
    static final int[] CLOT_SKIN = {0x6a0812, 0x7c0b16, 0x90101b, 0xa61822, 0xbc222c};
    static final int CLOT_SKIN_A = 108;
    /** Inner core: dark, dense coagulated blood. */
    static final int[] CLOT_CORE = {0x180206, 0x26030a, 0x36050e, 0x4a0813, 0x620d18, 0x7e1620};
    static final int CLOT_CORE_A = 232;

    /** Lit-ness of a height field at x,y (light from the upper left; > 0 = facing the light). */
    static double litAt(double[][] h, int x, int y) { return h[w(y + 1)][w(x + 1)] - h[w(y - 1)][w(x - 1)]; }

    /** Places wet glints on the most lit spots (min. spacing), returns their positions. */
    static int[][] glintSpots(double[][] score, int count, int spacing, int lo, int hi) {
        int[][] gl = new int[count][];
        for (int it = 0; it < count; it++) {
            double best = -1e9; int bx = lo, by = lo;
            for (int y = lo; y <= hi; y++) for (int x = lo; x <= hi; x++) {
                boolean free = true;
                for (int q = 0; q < it; q++) {
                    int ddx = Math.abs(x - gl[q][0]), ddy = Math.abs(y - gl[q][1]);
                    ddx = Math.min(ddx, N - ddx); ddy = Math.min(ddy, N - ddy);
                    if (ddx < spacing && ddy < spacing) { free = false; break; }
                }
                if (free && score[y][x] > best) { best = score[y][x]; bx = x; by = y; }
            }
            gl[it] = new int[]{bx, by};
        }
        return gl;
    }

    /** clotted_blood_bottom = the outer skin (seamless: the skins of neighbouring blocks join into one surface). */
    static BufferedImage clottedSkin() {
        double[][] h = new double[N][N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++)
            h[y][x] = pn(x, y, 4, 72) * 0.55 + pn(x, y, 8, 73) * 0.3 + pn(x, y, 16, 74) * 0.15;
        BufferedImage im = img(N, N);
        double[][] score = new double[N][N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            double v = h[y][x], light = litAt(h, x, y);
            int l = v < 0.4 ? 1 : v < 0.6 ? 2 : 3;
            if (light > 0.08) l++;
            if (light < -0.08) l--;
            l = lim(l, 0, 4);
            score[y][x] = light * 3 + v;
            im.setRGB(x, y, argb(CLOT_SKIN[l], CLOT_SKIN_A + (l - 2) * 8));
        }
        int HI = 0xd8444e, HI2 = 0xb82c38, SP = 0xf49a90;
        // soft jelly rim (lit top/left, darker bottom/right) and a glossy corner reflection, like a slime/honey cube
        for (int i = 0; i < N; i++) {
            im.setRGB(i, 0, argb(CLOT_SKIN[3], CLOT_SKIN_A + 14)); im.setRGB(0, i, argb(CLOT_SKIN[3], CLOT_SKIN_A + 14));
            im.setRGB(i, N - 1, argb(CLOT_SKIN[0], CLOT_SKIN_A + 24)); im.setRGB(N - 1, i, argb(CLOT_SKIN[0], CLOT_SKIN_A + 24));
        }
        im.setRGB(0, N - 1, argb(CLOT_SKIN[1], CLOT_SKIN_A + 18)); im.setRGB(N - 1, 0, argb(CLOT_SKIN[1], CLOT_SKIN_A + 18));
        for (int i = 4; i <= 9; i++) im.setRGB(i, 3, i <= 6 ? argb(HI, 175) : argb(HI2, 150));
        for (int i = 4; i <= 8; i++) im.setRGB(3, i, i <= 5 ? argb(HI, 175) : argb(HI2, 150));
        im.setRGB(4, 4, argb(SP, 205));
        // glossy highlights: small crescents on the upper-left of the two most lit bulges
        int[][] gl = glintSpots(score, 2, 12, 8, N - 3);
        for (int[] g : gl) {
            int x = g[0] - 1, y = g[1] - 1;
            im.setRGB(w(x + 1), w(y), argb(HI, 185)); im.setRGB(w(x + 2), w(y), argb(HI2, 160));
            im.setRGB(w(x), w(y + 1), argb(HI, 185)); im.setRGB(w(x + 1), w(y + 1), argb(SP, 215));
            im.setRGB(w(x), w(y + 2), argb(HI2, 160));
        }
        return im;
    }

    /** clotted_blood_top / _side = the inner core: darker, congealed swirls (top) or sagging strata (side), bubbles. */
    static BufferedImage clottedCore(boolean top) {
        long sd = top ? 300 : 320;
        double[][] h = new double[N][N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            double wx = pn(x, y, 4, sd) - 0.5, wy = pn(x, y, 4, sd + 1) - 0.5;
            if (top) {
                double qx = x + wx * 12, qy = y + wy * 12;
                h[y][x] = pn(qx, qy, 4, sd + 2) * 0.65 + pn(qx, qy, 8, sd + 3) * 0.35;
            } else {
                // horizontal strata that sag and drip downwards
                double qy = y + wy * 5 - 2.5 * Math.sin(Math.PI * 2 * x / N * 2 + 0.7) * pn(x, 0, 4, 1, N, N, sd + 4);
                h[y][x] = pn(x + wx * 6, qy, 2, 6, N, N, sd + 2) * 0.7 + pn(x, qy, 8, 12, N, N, sd + 3) * 0.3;
            }
        }
        BufferedImage im = img(N, N);
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            double v = h[y][x], light = litAt(h, x, y);
            int l = v < 0.36 ? 1 : v < 0.5 ? 2 : v < 0.62 ? 3 : 4;
            if (light > 0.07) l++;
            if (light < -0.07) l--;
            l = lim(l, 0, 5);
            im.setRGB(x, y, argb(CLOT_CORE[l], CLOT_CORE_A));
        }
        // edges of the visible core (uv 1..15 = px 2..29): lit top/left, dark bottom/right -> reads as a cube inside
        for (int i = 2; i <= 29; i++) {
            im.setRGB(i, 2, argb(CLOT_CORE[4], CLOT_CORE_A)); im.setRGB(2, i, argb(CLOT_CORE[4], CLOT_CORE_A));
            im.setRGB(i, 29, argb(CLOT_CORE[0], CLOT_CORE_A)); im.setRGB(29, i, argb(CLOT_CORE[0], CLOT_CORE_A));
        }
        im.setRGB(2, 2, argb(CLOT_CORE[5], CLOT_CORE_A));
        im.setRGB(2, 29, argb(CLOT_CORE[2], CLOT_CORE_A)); im.setRGB(29, 2, argb(CLOT_CORE[2], CLOT_CORE_A));
        // trapped bubbles (only the visible area 2..29): dark rim, lit lower-right inside, tiny spec upper-left
        Random r = new Random(sd + 9);
        int[][] at = top ? new int[][]{{7, 9}, {21, 6}, {17, 21}, {9, 24}} : new int[][]{{6, 8}, {22, 13}, {12, 22}};
        for (int b = 0; b < at.length; b++) {
            int x = at[b][0] + r.nextInt(2), y = at[b][1] + r.nextInt(2);
            boolean big = b % 2 == 0;
            if (big) {
                int[][] pat = {{-1, 1, 1, -1}, {1, 9, 4, 3}, {1, 4, 3, 3}, {-1, 3, 3, -1}};   // 9 = spec
                for (int j = 0; j < 4; j++) for (int i = 0; i < 4; i++) {
                    int v = pat[j][i];
                    if (v < 0) continue;
                    im.setRGB(x + i, y + j, v == 9 ? argb(0xe07a7e, 230) : argb(CLOT_CORE[v], CLOT_CORE_A));
                }
            } else {
                im.setRGB(x, y, argb(0xb84450, 225));
                im.setRGB(x + 1, y + 1, argb(CLOT_CORE[4], CLOT_CORE_A));
                im.setRGB(x + 1, y, argb(CLOT_CORE[1], CLOT_CORE_A));
            }
        }
        return im;
    }

    // ------------------------------------------------------------------------------- altar
    static int[][] altarTop() {
        // polished slab (same bevel as polished blood stone) with an engraved frame line, a raised basin in the middle
        int[][] lv = polishedIdx(82);
        bevelIdx(lv, 0, 0, N - 1, N - 1);
        insetIdx(lv, 3, 3, 28, 28, 2, 5);
        int[][] px = colour(lv, STONE);
        // basin
        double c = 16.0;
        for (int y = 4; y <= 27; y++) for (int x = 4; x <= 27; x++) {
            double dx = x + 0.5 - c, dy = y + 0.5 - c, d = Math.hypot(dx, dy);
            double tl = -(dx + dy) / Math.max(d, 0.01) / Math.sqrt(2);
            int col = -1;
            if (d < 9.0 && d >= 7.6) col = tl > 0.3 ? STONE[6] : tl < -0.3 ? STONE[3] : STONE[5];       // raised lip
            else if (d < 9.9 && d >= 9.0 && tl < -0.2) col = STONE[2];                                   // lip shadow on the slab
            else if (d < 7.6 && d >= 6.4) col = tl > 0.15 ? STONE[0] : tl < -0.35 ? STONE[4] : STONE[1]; // inner wall
            else if (d < 6.2) {
                // blood: shadowed crescent at the upper-left wall, ripple ring, deeper centre
                double sx = dx + 0.9, sy = dy + 0.9;
                if (Math.hypot(sx, sy) > 6.2) col = 0x4a0309;
                else col = d < 2.2 ? 0x8e0b15 : 0x7a0812;
                if (Math.abs(d - 4.1) < 0.5 && tl < 0.6) col = 0xa8161e;
                if (Math.abs(d - 1.6) < 0.5 && tl < -0.2) col = 0xa01420;
            }
            if (col >= 0) px[y][x] = hex(col);
        }
        // highlight on the liquid
        px[12][12] = hex(0xffc4b4); px[12][13] = hex(0xff6a55); px[13][12] = hex(0xd8343a); px[12][14] = hex(0xc0262e);
        px[19][20] = hex(0xc0262e);
        // glowing runes in the corners of the inner area
        int[][] runes = {{8, 8}, {23, 8}, {8, 23}, {23, 23}};
        for (int[] q : runes) {
            int x = q[0], y = q[1];
            px[y][x] = hex(0xff6a55);
            px[y - 1][x] = hex(0xc0161f); px[y + 1][x] = hex(0xc0161f); px[y][x - 1] = hex(0xc0161f); px[y][x + 1] = hex(0xc0161f);
            int[][] dg = {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
            for (int[] e : dg) px[y + e[1]][x + e[0]] = hex(0x5e0a10);
            px[y - 2][x] = hex(0x4a0610); px[y + 2][x] = hex(0x4a0610); px[y][x - 2] = hex(0x4a0610); px[y][x + 2] = hex(0x4a0610);
        }
        return px;
    }

    static int[][] altarSide() {
        // polished cap (rows 0..7) with a glowing groove, recessed panel with bone ribs (8..21), sill (22),
        // and a brick course at the bottom that continues the blood stone brick wall exactly (mortar 23/31, offset 8).
        int[][] lv = polishedIdx(91);
        for (int x = 0; x < N; x++) { lv[0][x] = 6; lv[1][x] = 5; lv[3][x] = 1; lv[5][x] = 5; lv[7][x] = 2; }
        for (int y = 0; y <= 22; y++) { lv[y][0] = 6; lv[y][1] = Math.max(lv[y][1], 5); lv[y][N - 1] = 2; lv[y][N - 2] = 3; }
        lv[0][N - 1] = 4; lv[1][N - 2] = 4;
        for (int y = 8; y <= 21; y++) for (int x = 3; x <= 28; x++) lv[y][x] = h01(93, x, y) < 0.8 ? 1 : 2;
        for (int x = 3; x <= 28; x++) { lv[8][x] = 0; lv[21][x] = 3; lv[22][x] = 5; }
        for (int y = 8; y <= 21; y++) { lv[y][3] = 0; lv[y][28] = 3; }
        lv[21][3] = 1; lv[8][28] = 1;
        for (int x = 0; x < N; x++) lv[22][x] = x == N - 1 ? 2 : Math.max(lv[22][x], 4);
        int[][] px = colour(lv, STONE);
        // glowing groove
        for (int x = 0; x < N; x++) px[4][x] = hex(h01(92, x, 4) < 0.25 ? 0xc0161f : 0x8a0c14);
        int[] hot = {7, 15, 16, 24};
        for (int x : hot) px[4][x] = hex(0xe0333a);
        px[4][15] = hex(0xff6a55); px[4][16] = hex(0xff6a55);
        // bone ribs: ( (   ) ) curving outwards, 2 px wide, lit left / shadow right, knobby ends (vanilla bone colours)
        double[][] ribs = {{6.0, -1}, {10.5, -1}, {21.5, 1}, {26.0, 1}};
        for (double[] rb : ribs) {
            for (int y = 9; y <= 20; y++) {
                double t = (y - 9) / 11.0;
                int x = (int) Math.round(rb[0] + rb[1] * Math.sin(t * Math.PI) * 2.2 - (rb[1] < 0 ? 0 : 1));
                boolean knob = y == 9 || y == 20;
                px[y][x] = hex(y <= 11 ? BONE_HI : y >= 18 ? BONE_MID : BONE);
                px[y][x + 1] = hex(BONE_SH);
                if (knob) { px[y][x - 1] = hex(BONE_MID); px[y][x + 2] = hex(BONE_DK); }
                else if (px[y][x + 2] != hex(BONE_MID) && px[y][x + 2] != hex(BONE_SH)) px[y][x + 2] = S(0);   // cast shadow
            }
        }
        // small blood drop in the middle
        int[][] drop = new int[N][N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) drop[y][x] = px[y][x];
        drawDrop(drop, 16.0, 16.6, 2.6, 10.6, false);
        for (int y = 9; y <= 20; y++) for (int x = 12; x <= 19; x++) px[y][x] = drop[y][x];
        px[15][14] = hex(0xffc4b4);
        // brick course like blood_stone_bricks row 3
        for (int y = 23; y < N; y++) for (int x = 0; x < N; x++) px[y][x] = mortarAt(x, y, 94);
        Random r = new Random(95);
        for (int k = 0; k < 2; k++) paintBrick(px, 8 + k * 16, 24, 15, 7, r, 95 + k, STONE);
        return px;
    }

    // ------------------------------------------------------------------------------- blood fluid
    static final int[] BLOOD = {0x3a0005, 0x470007, 0x54000a, 0x62020c, 0x70040f, 0x7e0611, 0x8a0712};
    static final int BLOOD_HI = 0xa3141e, BLOOD_SPEC = 0xcc3a42;

    static int bloodPixel(double v, double light, double sparkle) {
        int l = lim((int) Math.floor(3.3 + (v - 0.5) * 9.0 + light * 14), 0, BLOOD.length - 1);
        int c = BLOOD[l];
        int a = 228 - l;                                      // darker = a little denser
        if (light > 0.16 && v > 0.45) { c = BLOOD_HI; a = 222; }
        if (light > 0.16 && v > 0.45 && sparkle > 0.82) { c = BLOOD_SPEC; a = 226; }
        return argb(c, a);
    }

    static void bloodStill() throws Exception {
        int F = 32;
        BufferedImage im = img(N, N * F);
        double[][] v = new double[N][N];
        for (int f = 0; f < F; f++) {
            double th = Math.PI * 2 * f / F;
            for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
                double wx = pn(x + 2.4 * Math.cos(th), y + 2.4 * Math.sin(th), 4, 101) - 0.5;
                double wy = pn(x - 2.4 * Math.sin(th + 1.1), y + 2.4 * Math.cos(th + 1.1), 4, 102) - 0.5;
                double qx = x + wx * 11, qy = y + wy * 11;
                v[y][x] = 0.62 * pn(qx + 2.0 * Math.cos(-th + 0.7), qy + 2.0 * Math.sin(-th + 0.7), 4, 103)
                    + 0.38 * pn(qx - 1.5 * Math.sin(th), qy + 1.5 * Math.cos(th), 8, 104);
            }
            for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
                double light = v[w(y + 1)][w(x + 1)] - v[w(y - 1)][w(x - 1)];
                double sp = pn(x + 3 * Math.cos(th), y + 3 * Math.sin(th), 16, 105);
                im.setRGB(x, f * N + y, bloodPixel(v[y][x], light, sp));
            }
        }
        save(im, "block/blood_still");
        mcmeta("block/blood_still", "    \"frametime\": 2");
    }

    static void bloodFlow() throws Exception {
        int F = 32, S = 64;
        BufferedImage im = img(S, S * F);
        double[][] v = new double[S][S];
        for (int f = 0; f < F; f++) {
            for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
                double y1 = y - 2.0 * f, y2 = y - 4.0 * f;     // two layers: 1x and 2x the period per loop -> seamless
                double wob = pn(x, y1, 4, 2, S, S, 201) - 0.5;
                double s1 = pn(x + wob * 10, y1, 8, 2, S, S, 202);
                double s2 = pn(x + wob * 6, y2, 16, 3, S, S, 203);
                v[y][x] = 0.62 * s1 + 0.38 * s2;
            }
            for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
                double light = (v[y][Math.floorMod(x + 1, S)] - v[y][Math.floorMod(x - 1, S)]) * 1.15;
                double sp = pn(x, y - 3.0 * f * 2 / 3 * 1.0, 16, 4, S, S, 204);
                im.setRGB(x, f * S + y, bloodPixel(v[y][x], light, sp));
            }
        }
        save(im, "block/blood_flow");
        mcmeta("block/blood_flow", "    \"frametime\": 2");
    }

    // ===================================================================================== json
    static void write(Path p, String s) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, s, StandardCharsets.UTF_8);
    }

    static String vanilla(String entry) throws Exception {
        try (ZipFile z = new ZipFile(jarPath)) {
            ZipEntry e = z.getEntry(entry);
            if (e == null) throw new IllegalStateException("missing in jar: " + entry);
            try (InputStream in = z.getInputStream(e)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                in.transferTo(out);
                return out.toString(StandardCharsets.UTF_8);
            }
        }
    }

    static final String[] CUBES = {"blood_stone", "polished_blood_stone", "blood_stone_bricks", "chiseled_blood_stone", "vein_stone", "blood_crystal_block"};

    static void assetsJson() throws Exception {
        for (String n : CUBES) {
            write(assets.resolve("models/block/" + n + ".json"), "{\n  \"parent\": \"minecraft:block/cube_all\",\n  \"textures\": { \"all\": \"hha_blood:block/" + n + "\" }\n}\n");
            simpleState(n);
            itemDef(n, "block/" + n);
        }
        // clotted blood: two cubes like vanilla honey_block (translucent skin around a smaller core)
        write(assets.resolve("models/block/clotted_blood.json"), vanilla("assets/minecraft/models/block/honey_block.json")
            .replace("block/honey_block_", "hha_blood:block/clotted_blood_"));
        simpleState("clotted_blood");
        itemDef("clotted_blood", "block/clotted_blood");
        write(assets.resolve("models/block/sanguine_altar.json"),
            "{\n  \"parent\": \"minecraft:block/cube_bottom_top\",\n  \"textures\": {\n    \"top\": \"hha_blood:block/sanguine_altar_top\",\n    \"side\": \"hha_blood:block/sanguine_altar_side\",\n    \"bottom\": \"hha_blood:block/blood_stone_bricks\"\n  }\n}\n");
        simpleState("sanguine_altar");
        itemDef("sanguine_altar", "block/sanguine_altar");

        for (String n : new String[]{"blood_crystal_cluster", "small_blood_crystal"}) {
            write(assets.resolve("models/block/" + n + ".json"), "{\n  \"parent\": \"minecraft:block/cross\",\n  \"textures\": { \"cross\": \"hha_blood:block/" + n + "\" }\n}\n");
            String st = vanilla("assets/minecraft/blockstates/amethyst_cluster.json").replace("minecraft:block/amethyst_cluster", "hha_blood:block/" + n);
            write(assets.resolve("blockstates/" + n + ".json"), st);
            write(assets.resolve("models/item/" + n + ".json"), "{\n  \"parent\": \"minecraft:item/generated\",\n  \"textures\": { \"layer0\": \"hha_blood:block/" + n + "\" }\n}\n");
            itemDef(n, "item/" + n);
        }
        write(assets.resolve("models/item/blood_crystal_shard.json"), "{\n  \"parent\": \"minecraft:item/generated\",\n  \"textures\": { \"layer0\": \"hha_blood:item/blood_crystal_shard\" }\n}\n");
        itemDef("blood_crystal_shard", "item/blood_crystal_shard");

        // stairs / slab / wall from vanilla stone bricks
        String[] states = {"stone_brick_stairs", "stone_brick_slab", "stone_brick_wall"};
        for (String s : states) {
            write(assets.resolve("blockstates/blood_" + s + ".json"), rename(vanilla("assets/minecraft/blockstates/" + s + ".json")));
        }
        String[] models = {"stone_brick_stairs", "stone_brick_stairs_inner", "stone_brick_stairs_outer", "stone_brick_slab", "stone_brick_slab_top",
            "stone_brick_wall_post", "stone_brick_wall_side", "stone_brick_wall_side_tall", "stone_brick_wall_inventory"};
        for (String m : models) {
            write(assets.resolve("models/block/blood_" + m + ".json"), rename(vanilla("assets/minecraft/models/block/" + m + ".json")));
        }
        itemDef("blood_stone_brick_stairs", "block/blood_stone_brick_stairs");
        itemDef("blood_stone_brick_slab", "block/blood_stone_brick_slab");
        itemDef("blood_stone_brick_wall", "block/blood_stone_brick_wall_inventory");
    }

    static String rename(String s) { return s.replace("minecraft:block/stone_brick", "hha_blood:block/blood_stone_brick"); }

    static void simpleState(String n) throws Exception {
        write(assets.resolve("blockstates/" + n + ".json"), "{\n  \"variants\": {\n    \"\": { \"model\": \"hha_blood:block/" + n + "\" }\n  }\n}\n");
    }

    static void itemDef(String n, String model) throws Exception {
        write(assets.resolve("items/" + n + ".json"), "{\n  \"model\": {\n    \"type\": \"minecraft:model\",\n    \"model\": \"hha_blood:" + model + "\"\n  }\n}\n");
    }

    static void dataJson() throws Exception {
        String[] self = {"blood_stone", "polished_blood_stone", "blood_stone_bricks", "chiseled_blood_stone", "vein_stone", "clotted_blood",
            "blood_crystal_block", "sanguine_altar", "blood_stone_brick_stairs", "blood_stone_brick_wall"};
        for (String n : self) {
            write(data.resolve("loot_table/blocks/" + n + ".json"),
                "{\n  \"type\": \"minecraft:block\",\n  \"pools\": [\n    {\n      \"bonus_rolls\": 0.0,\n      \"conditions\": [ { \"condition\": \"minecraft:survives_explosion\" } ],\n" +
                    "      \"entries\": [ { \"type\": \"minecraft:item\", \"name\": \"hha_blood:" + n + "\" } ],\n      \"rolls\": 1.0\n    }\n  ],\n  \"random_sequence\": \"hha_blood:blocks/" + n + "\"\n}\n");
        }
        write(data.resolve("loot_table/blocks/blood_stone_brick_slab.json"),
            vanilla("data/minecraft/loot_table/blocks/stone_brick_slab.json").replace("minecraft:stone_brick_slab", "hha_blood:blood_stone_brick_slab")
                .replace("minecraft:blocks/stone_brick_slab", "hha_blood:blocks/blood_stone_brick_slab"));
        write(data.resolve("loot_table/blocks/blood_crystal_cluster.json"),
            vanilla("data/minecraft/loot_table/blocks/amethyst_cluster.json").replace("minecraft:amethyst_cluster", "hha_blood:blood_crystal_cluster")
                .replace("minecraft:amethyst_shard", "hha_blood:blood_crystal_shard").replace("minecraft:blocks/amethyst_cluster", "hha_blood:blocks/blood_crystal_cluster"));
        write(data.resolve("loot_table/blocks/small_blood_crystal.json"),
            "{\n  \"type\": \"minecraft:block\",\n  \"pools\": [\n    {\n      \"bonus_rolls\": 0.0,\n      \"entries\": [\n        {\n          \"type\": \"minecraft:alternatives\",\n          \"children\": [\n" +
                "            {\n              \"type\": \"minecraft:item\",\n              \"conditions\": [ { \"condition\": \"minecraft:match_tool\", \"predicate\": { \"predicates\": { \"minecraft:enchantments\": [ { \"enchantments\": \"minecraft:silk_touch\", \"levels\": { \"min\": 1 } } ] } } } ],\n" +
                "              \"name\": \"hha_blood:small_blood_crystal\"\n            },\n" +
                "            { \"type\": \"minecraft:item\", \"functions\": [ { \"function\": \"minecraft:explosion_decay\" } ], \"name\": \"hha_blood:blood_crystal_shard\" }\n" +
                "          ]\n        }\n      ],\n      \"rolls\": 1.0\n    }\n  ],\n  \"random_sequence\": \"hha_blood:blocks/small_blood_crystal\"\n}\n");

        // recipes
        shaped("blood_stone_bricks", 4, new String[]{"##", "##"}, "#", "hha_blood:blood_stone");
        shaped("polished_blood_stone", 4, new String[]{"##", "##"}, "#", "hha_blood:blood_stone_bricks");
        shaped("chiseled_blood_stone", 1, new String[]{"#", "#"}, "#", "hha_blood:blood_stone_brick_slab");
        shaped("blood_stone_brick_stairs", 4, new String[]{"#  ", "## ", "###"}, "#", "hha_blood:blood_stone_bricks");
        shaped("blood_stone_brick_slab", 6, new String[]{"###"}, "#", "hha_blood:blood_stone_bricks");
        shaped("blood_stone_brick_wall", 6, new String[]{"###", "###"}, "#", "hha_blood:blood_stone_bricks");
        shaped("blood_crystal_block", 1, new String[]{"##", "##"}, "#", "hha_blood:blood_crystal_shard");

        // block tags
        Path tags = data.getParent().resolve("minecraft/tags/block");
        write(tags.resolve("mineable/pickaxe.json"), "{\n  \"values\": [\n" +
            q("blood_stone") + q("polished_blood_stone") + q("blood_stone_bricks") + q("chiseled_blood_stone") + q("blood_stone_brick_stairs") +
            q("blood_stone_brick_slab") + q("blood_stone_brick_wall") + q("vein_stone") + q("blood_crystal_block") + q("blood_crystal_cluster") +
            q("small_blood_crystal") + "    \"hha_blood:sanguine_altar\"\n  ]\n}\n");
        write(tags.resolve("mineable/shovel.json"), "{\n  \"values\": [ \"hha_blood:clotted_blood\" ]\n}\n");
        write(tags.resolve("stairs.json"), "{\n  \"values\": [ \"hha_blood:blood_stone_brick_stairs\" ]\n}\n");
        write(tags.resolve("slabs.json"), "{\n  \"values\": [ \"hha_blood:blood_stone_brick_slab\" ]\n}\n");
        write(tags.resolve("walls.json"), "{\n  \"values\": [ \"hha_blood:blood_stone_brick_wall\" ]\n}\n");
        Path itags = data.getParent().resolve("minecraft/tags/item");
        write(itags.resolve("stairs.json"), "{\n  \"values\": [ \"hha_blood:blood_stone_brick_stairs\" ]\n}\n");
        write(itags.resolve("slabs.json"), "{\n  \"values\": [ \"hha_blood:blood_stone_brick_slab\" ]\n}\n");
        write(itags.resolve("walls.json"), "{\n  \"values\": [ \"hha_blood:blood_stone_brick_wall\" ]\n}\n");
    }

    static String q(String n) { return "    \"hha_blood:" + n + "\",\n"; }

    static void shaped(String result, int count, String[] pattern, String key, String item) throws Exception {
        StringBuilder p = new StringBuilder();
        for (int i = 0; i < pattern.length; i++) p.append(i > 0 ? ", " : "").append('"').append(pattern[i]).append('"');
        write(data.resolve("recipe/" + result + ".json"),
            "{\n  \"type\": \"minecraft:crafting_shaped\",\n  \"category\": \"building\",\n  \"pattern\": [" + p + "],\n  \"key\": { \"" + key + "\": \"" + item + "\" },\n" +
                "  \"result\": { \"id\": \"hha_blood:" + result + "\", \"count\": " + count + " }\n}\n");
    }
}
