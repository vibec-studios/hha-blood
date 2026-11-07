import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

/**
 * Assets of the living Vein Hollows / Slaughter Pit blocks (round "Biom + Arena cooler"):
 * watching eye (block + iris/lid sheet for the renderer), the heart's grain texture, blood fall / rising blood,
 * blood glow ("Blutglut"), blood vent ("Blutschlot"), the rising blood mote particle, plus blockstates, block/item
 * models, item definitions and loot tables.
 *
 *   java tools/textures/GenPit.java <project root> [--preview <dir>]
 *
 * Same conventions as GenHollows: 32 x 32 block textures, the shared 8-step blood stone ramp, light from top left.
 */
public class GenPit {
    static Path assets, data;
    static final int N = 32;

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".");
        assets = root.resolve("blood/src/main/resources/assets/hha_blood");
        data = root.resolve("blood/src/main/resources/data/hha_blood");
        eyeTextures();
        heartGrain();
        bloodFall(false);
        bloodFall(true);
        bloodGlow();
        vent();
        motes();
        json();
        if (args.length > 2 && args[1].equals("--preview")) preview(Paths.get(args[2]));
        System.out.println("done");
    }

    // ===================================================================================== helpers
    static final int[] STONE = {0x1c0409, 0x2a0710, 0x370a14, 0x430e17, 0x4f121a, 0x5c161c, 0x6a1c1f, 0x7e2626};
    static int S(int i) { return hex(STONE[lim(i, 0, STONE.length - 1)]); }
    static int rgb(int r, int g, int b) { return 0xff000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b); }
    static int hex(int c) { return 0xff000000 | c; }
    static int argb(int c, int a) { return (clamp(a) << 24) | (c & 0xffffff); }
    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
    static int lim(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    static int w(int v) { return Math.floorMod(v, N); }
    static int mix(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return rgb((int) Math.round(ar + (br - ar) * t), (int) Math.round(ag + (bg - ag) * t), (int) Math.round(ab + (bb - ab) * t));
    }
    static double h01(long seed, long x, long y) {
        long h = seed * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL + y * 0x165667B19E3779F9L;
        h ^= h >>> 31; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 29; h *= 0x94D049BB133111EBL; h ^= h >>> 32;
        return (h >>> 11) * 0x1.0p-53;
    }
    static double pn(double x, double y, int cx, int cy, double pw, double ph, long seed) {
        double fx = x * cx / pw, fy = y * cy / ph;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        double tx = fx - x0, ty = fy - y0;
        tx = tx * tx * (3 - 2 * tx); ty = ty * ty * (3 - 2 * ty);
        int xa = Math.floorMod(x0, cx), xb = Math.floorMod(x0 + 1, cx), ya = Math.floorMod(y0, cy), yb = Math.floorMod(y0 + 1, cy);
        double a = h01(seed, xa, ya), b = h01(seed, xb, ya), c = h01(seed, xa, yb), d = h01(seed, xb, yb);
        return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty;
    }
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
    static void write(Path p, String s) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, s, StandardCharsets.UTF_8);
    }

    // ===================================================================================== flesh
    /** Wrinkled flesh: dark crimson folds with a wet sheen, lit from the top left. Returns ARGB pixels. */
    static int[][] flesh(long seed, double bright) {
        double[][] h = new double[N][N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            // folds: ridged noise
            double f = 1 - Math.abs(pn(x, y + pn(x, y, 4, seed + 9) * 6, 4, seed) * 2 - 1);
            h[y][x] = f * 0.6 + fbm(x, y, seed + 3) * 0.4;
        }
        int[][] px = new int[N][N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            double lit = h[w(y + 1)][w(x + 1)] - h[w(y - 1)][w(x - 1)];
            double v = h[y][x] * 0.75 + lit * 1.4 + bright;
            int lvl = lim((int) Math.floor(1.0 + v * 6.0), 0, 7);
            int c = mix(S(lvl), hex(0x8e1a1f), 0.25 + 0.1 * lvl / 7.0);
            if (lit > 0.11 && h[y][x] > 0.55) c = mix(c, hex(0xc4504a), 0.5);      // wet highlight
            px[y][x] = c;
        }
        // a few thin dark veins
        Random r = new Random(seed);
        for (int k = 0; k < 3; k++) {
            double x = r.nextDouble() * N, y = r.nextDouble() * N, a = r.nextDouble() * Math.PI * 2;
            for (int s = 0; s < 22; s++) {
                px[w((int) y)][w((int) x)] = mix(px[w((int) y)][w((int) x)], hex(0x2a0510), 0.7);
                a += (r.nextDouble() - 0.5) * 0.7;
                x += Math.cos(a); y += Math.sin(a);
            }
        }
        return px;
    }

    static BufferedImage toImg(int[][] px) {
        BufferedImage im = img(px[0].length, px.length);
        for (int y = 0; y < px.length; y++) for (int x = 0; x < px[0].length; x++) im.setRGB(x, y, px[y][x]);
        return im;
    }

    // ===================================================================================== eye
    /** Opening of the eye on the 32 px face: x 6..25, y 10..21 (= model 3..13 / 5..11). */
    static final int OX0 = 4, OX1 = 28, OY0 = 8, OY1 = 24;

    static void eyeTextures() throws Exception {
        // side: flesh; front: flesh with a swollen, dark socket around the opening
        save(toImg(flesh(301, 0.0)), "block/watching_eye_flesh");
        int[][] front = flesh(302, 0.05);
        double cx = (OX0 + OX1) / 2.0, cy = (OY0 + OY1) / 2.0;
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            double dx = (x + 0.5 - cx) / 14.5, dy = (y + 0.5 - cy) / 10.5;
            double d = Math.sqrt(dx * dx + dy * dy);
            if (d < 1.0) {
                // the swollen lid folds around the socket, lit from the top left
                double ring = Math.max(0, 1 - Math.abs(d - 0.86) / 0.16);
                double up = (y + 0.5 < cy) ? 1 : 0;
                int c = front[y][x];
                c = mix(c, hex(0x2a0510), (1 - d) * 0.7);
                if (ring > 0) c = mix(c, up > 0 ? hex(0xa8403c) : hex(0x5a0e16), ring * 0.65);
                front[y][x] = c;
            }
        }
        // dark rim right at the opening edge
        for (int y = OY0 - 1; y <= OY1; y++) for (int x = OX0 - 1; x <= OX1; x++) {
            boolean edge = (y == OY0 - 1 || y == OY1 || x == OX0 - 1 || x == OX1);
            if (edge && y >= 0 && y < N && x >= 0 && x < N) front[y][x] = mix(front[y][x], hex(0x1a0207), 0.75);
        }
        save(toImg(front), "block/watching_eye_front");

        // sclera (seen through the opening): yellowed white, red veins from the corners, shadow under the lids
        int[][] sc = flesh(303, 0.0);
        Random r = new Random(304);
        for (int y = OY0; y < OY1; y++) for (int x = OX0; x < OX1; x++) {
            double u = (x + 0.5 - cx) / 12.0, v = (y + 0.5 - cy) / 8.0;
            double shade = 0.78 - 0.18 * u * u - 0.25 * Math.max(0, -v) + 0.05 * pn(x, y, 8, 305);
            int c = mix(hex(0x6e5a48), hex(0xefe3c8), shade);
            if (v < -0.55) c = mix(c, hex(0x3a1410), (-0.55 - v) * 1.6);             // shadow of the upper lid
            sc[y][x] = c;
        }
        for (int k = 0; k < 9; k++) {
            boolean left = k % 2 == 0;
            double x = left ? OX0 : OX1 - 1, y = OY0 + 2 + r.nextDouble() * (OY1 - OY0 - 4);
            double a = left ? 0 : Math.PI;
            a += (r.nextDouble() - 0.5) * 0.9;
            int len = 4 + r.nextInt(6);
            for (int s = 0; s < len; s++) {
                int ix = (int) x, iy = (int) y;
                if (ix >= OX0 && ix < OX1 && iy >= OY0 && iy < OY1) sc[iy][ix] = mix(sc[iy][ix], hex(0xb0161c), 0.75 - s * 0.05);
                a += (r.nextDouble() - 0.5) * 0.8;
                x += Math.cos(a); y += Math.sin(a) * 0.7;
            }
        }
        save(toImg(sc), "block/watching_eye_sclera");

        // renderer sheet: irises (14 x 14) at (0,0) and (14,0), the lid at (0,14) 24 x 16
        BufferedImage sheet = img(32, 32);
        for (int k = 0; k < 2; k++) {
            for (int y = 0; y < 14; y++) for (int x = 0; x < 14; x++) {
                double dx = (x + 0.5 - 7) * 5 / 7.0, dy = (y + 0.5 - 7) * 5 / 7.0;
                double d = Math.sqrt(dx * dx + dy * dy);
                if (d > 5.0) continue;
                double ang = Math.atan2(dy, dx);
                double streak = 0.5 + 0.5 * Math.sin(ang * 9 + d * 0.8);
                int c = mix(hex(0xff7a2a), hex(0xd0101c), d / 5.0);
                c = mix(c, hex(0xffd27a), streak * Math.max(0, 0.45 - d / 10));
                if (d > 4.1) c = hex(0x3a0004);                                        // limbal ring
                boolean pupil = k == 0 ? d < 1.9 : (Math.abs(dx) < 0.75 + 0.25 * (1 - Math.abs(dy) / 4) && Math.abs(dy) < 3.6);
                if (pupil) c = hex(0x0a0003);
                if (Math.abs(dx + 1.7) < 0.6 && Math.abs(dy + 1.7) < 0.6) c = hex(0xfff4e0); // glint
                sheet.setRGB(k * 14 + x, y, c);
            }
        }
        int[][] lidFlesh = flesh(306, 0.12);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 24; x++) {
            double xl = (x + 0.5) / 2.0;           // 0..12 model px
            double yl = 8 - (y + 0.5) / 2.0;       // 8..0 from the bottom
            double c = 4.0 * (1 - Math.pow((xl - 6) / 6, 2));
            if (yl <= c) continue;
            int col = lidFlesh[y + 2][x + 4];
            double edge = yl - c;
            if (edge < 0.5) col = hex(0x1c0306);                                        // lid margin + lashes
            else if (edge < 1.1) col = mix(col, hex(0xc65a52), 0.55);                   // swollen wet edge
            else col = mix(col, hex(0x2a0510), Math.max(0, (yl - 6) / 3));           // darker towards the socket
            sheet.setRGB(x, 14 + y, col);
        }
        save(sheet, "entity/watching_eye");
    }

    // ===================================================================================== heart grain
    static void heartGrain() throws Exception {
        BufferedImage im = img(16, 16);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            double n = h01(401, x, y);
            double v = 0.86 + 0.14 * pn(x, y, 4, 4, 16, 16, 402) + (n > 0.9 ? 0.08 : 0) - (n < 0.07 ? 0.12 : 0);
            int g = clamp((int) (v * 255));
            im.setRGB(x, y, rgb(g, (int) (g * 0.97), (int) (g * 0.97)));
        }
        save(im, "entity/pit_heart");
    }

    // ===================================================================================== blood fall
    static final int[] BLOOD = {0x2e0004, 0x3a0005, 0x470007, 0x54000a, 0x62020c, 0x70040f, 0x7e0611, 0x8f0a15, 0xa3141e};

    /** A pouring curtain of blood (32 frames), streaks scroll down (or up for rising blood). */
    static void bloodFall(boolean rising) throws Exception {
        int F = 32;
        BufferedImage im = img(N, N * F);
        for (int f = 0; f < F; f++) {
            for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
                int yy = rising ? y + f : y - f;                                  // one tile per loop -> seamless
                int yy2 = rising ? y + 2 * f : y - 2 * f;
                double wob = pn(x, yy, 4, 2, N, N, 501) - 0.5;
                double s1 = pn(x + wob * 6, yy, 8, 2, N, N, 502);
                double s2 = pn(x + wob * 3, yy2, 16, 4, N, N, 503);
                double v = 0.6 * s1 + 0.4 * s2;
                double light = (pn(x + 1 + wob * 6, yy, 8, 2, N, N, 502) - pn(x - 1 + wob * 6, yy, 8, 2, N, N, 502)) * 1.4;
                int l = lim((int) Math.floor(2.5 + (v - 0.45) * 12 + light * 8), 0, BLOOD.length - 1);
                int c = BLOOD[l];
                int a = 205 + l * 4;
                if (v > 0.62 && light > 0.04) { c = 0xc22a2e; a = 236; }
                if (v > 0.66 && light > 0.09) { c = 0xe8645a; a = 240; }
                // rising blood: a few bubbles
                if (rising) {
                    double b = pn(x, yy2 * 1.0, 8, 4, N, N, 504);
                    if (b > 0.83) { c = 0xd8403c; a = 230; }
                }
                im.setRGB(x, f * N + y, argb(c, a));
            }
        }
        String name = rising ? "block/blood_rise" : "block/blood_fall";
        save(im, name);
        mcmeta(name, "    \"frametime\": 1");
    }

    // ===================================================================================== blood glow
    static void bloodGlow() throws Exception {
        int F = 8;
        double[] beat = {0.55, 1.0, 0.75, 0.92, 0.7, 0.6, 0.56, 0.55};
        BufferedImage im = img(N, N * F);
        double[][] h = new double[N][N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) h[y][x] = fbm(x, y, 601);
        for (int f = 0; f < F; f++) {
            for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
                double lit = h[w(y + 1)][w(x + 1)] - h[w(y - 1)][w(x - 1)];
                double crack = Math.min(Math.abs(pn(x, y, 4, 602) - 0.5) * 1.3, Math.abs(pn(x, y, 8, 607) - 0.5) * 1.6);
                double v = h[y][x] + lit * 1.2;
                int c;
                if (crack < 0.04) c = mix(hex(0xffd0a0), hex(0xffffff), beat[f] - 0.5);         // glowing cracks
                else if (crack < 0.08) c = mix(hex(0xff6a3a), hex(0xffb070), beat[f] - 0.5);
                else c = mix(mix(hex(0x9a0e18), hex(0xe02a2a), v), hex(0xff6040), (beat[f] - 0.55) * 0.9 * v);
                im.setRGB(x, f * N + y, c);
            }
        }
        save(im, "block/blood_glow");
        mcmeta("block/blood_glow", "    \"frametime\": 3,\n    \"interpolate\": true");
    }

    // ===================================================================================== blood vent
    static void vent() throws Exception {
        // side: blood stone with glowing cracks running up
        int[][] side = new int[N][N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            double h = fbm(x, y, 701);
            double lit = fbm(x + 1, y + 1, 701) - fbm(x - 1, y - 1, 701);
            int lvl = lim((int) Math.floor(1.5 + h * 4 - lit * 6), 0, 7);
            int c = S(lvl);
            double crack = Math.abs(pn(x, y, 8, 2, N, N, 702) - 0.5);
            double heat = (N - y) / (double) N;
            if (crack < 0.05) c = mix(hex(0x7e0611), hex(0xff5a30), heat);
            side[y][x] = c;
        }
        save(toImg(side), "block/blood_vent_side");
        // top: a crater with a glowing throat
        int[][] top = new int[N][N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            double dx = x + 0.5 - 16, dy = y + 0.5 - 16;
            double d = Math.sqrt(dx * dx + dy * dy) + (pn(x, y, 8, 703) - 0.5) * 3;
            double h = fbm(x, y, 704);
            int c = S(lim((int) Math.floor(2 + h * 4), 0, 7));
            if (d < 13 && d >= 9) {
                double lip = 1 - Math.abs(d - 11) / 2;                                    // raised lip
                boolean litSide = (dx + dy) < 0;
                c = mix(c, litSide ? S(7) : S(1), lip * 0.8);
            } else if (d < 9) {
                double t = d / 9;
                c = mix(hex(0xffe0a0), hex(0x6a0410), Math.pow(t, 0.7));                   // hot throat
                if (t > 0.7) c = mix(c, hex(0x2a0207), (t - 0.7) * 2.5);
            }
            top[y][x] = c;
        }
        save(toImg(top), "block/blood_vent_top");
    }

    // ===================================================================================== particles
    static void motes() throws Exception {
        for (int k = 0; k < 3; k++) {
            int sz = 8;
            BufferedImage im = img(sz, sz);
            for (int y = 0; y < sz; y++) for (int x = 0; x < sz; x++) {
                double dx = x + 0.5 - 4, dy = y + 0.5 - (k == 2 ? 4.5 : 4);
                if (k == 2) dy = dy * (dy < 0 ? 0.6 : 1.0);                               // a little drop shape
                double d = Math.sqrt(dx * dx + dy * dy) / (k == 1 ? 2.6 : 3.2);
                if (d > 1) continue;
                int c = mix(hex(0xffd2b0), hex(0xd01020), Math.pow(d, 0.8));
                int a = (int) (255 * Math.min(1, (1 - d) * 2.2));
                im.setRGB(x, y, argb(c, a));
            }
            save(im, "particle/blood_mote_" + k);
        }
        write(assets.resolve("particles/blood_mote.json"),
            "{\"textures\":[\"hha_blood:blood_mote_0\",\"hha_blood:blood_mote_1\",\"hha_blood:blood_mote_2\"]}\n");
    }

    // ===================================================================================== json
    static void json() throws Exception {
        // ---- watching eye: socket frame + recessed eyeball, front = north
        String f = "hha_blood:block/watching_eye_flesh", fr = "hha_blood:block/watching_eye_front", sc = "hha_blood:block/watching_eye_sclera";
        StringBuilder el = new StringBuilder();
        // body behind the recess (its north face is the eyeball)
        el.append(elem(0, 0, 1, 16, 16, 16, "" +
            "\"east\": {\"uv\": [0, 0, 15, 16], \"texture\": \"#flesh\", \"cullface\": \"east\"}, " +
            "\"south\": {\"uv\": [0, 0, 16, 16], \"texture\": \"#flesh\", \"cullface\": \"south\"}, " +
            "\"west\": {\"uv\": [1, 0, 16, 16], \"texture\": \"#flesh\", \"cullface\": \"west\"}, " +
            "\"up\": {\"uv\": [0, 1, 16, 16], \"texture\": \"#flesh\", \"cullface\": \"up\"}, " +
            "\"down\": {\"uv\": [0, 0, 16, 15], \"texture\": \"#flesh\", \"cullface\": \"down\"}"));
        // frame: top, bottom, left, right of the opening (x 3..13, y 5..11)
        el.append(",\n").append(frame(0, 0, 2, 16, false, true, false, false));
        el.append(",\n").append(frame(14, 0, 16, 16, true, false, false, false));
        el.append(",\n").append(frame(2, 12, 14, 16, false, false, true, false));
        el.append(",\n").append(frame(2, 0, 14, 4, false, false, false, true));
        // the eyeball, glowing faintly in the dark
        el.append(",\n    { \"from\": [2, 4, 1], \"to\": [14, 12, 1], \"light_emission\": 8, \"faces\": { \"north\": {\"uv\": [2, 4, 14, 12], \"texture\": \"#sclera\"} } }");
        write(assets.resolve("models/block/watching_eye.json"),
            "{\n  \"parent\": \"minecraft:block/block\",\n  \"textures\": {\n    \"particle\": \"" + f + "\",\n    \"flesh\": \"" + f + "\",\n    \"front\": \"" + fr + "\",\n    \"sclera\": \"" + sc + "\"\n  },\n" +
                "  \"elements\": [\n" + el + "\n  ]\n}\n");
        write(assets.resolve("blockstates/watching_eye.json"), "{\n  \"variants\": {\n" +
            "    \"facing=north\": { \"model\": \"hha_blood:block/watching_eye\" },\n" +
            "    \"facing=east\": { \"model\": \"hha_blood:block/watching_eye\", \"y\": 90 },\n" +
            "    \"facing=south\": { \"model\": \"hha_blood:block/watching_eye\", \"y\": 180 },\n" +
            "    \"facing=west\": { \"model\": \"hha_blood:block/watching_eye\", \"y\": 270 },\n" +
            "    \"facing=up\": { \"model\": \"hha_blood:block/watching_eye\", \"x\": 270 },\n" +
            "    \"facing=down\": { \"model\": \"hha_blood:block/watching_eye\", \"x\": 90 }\n  }\n}\n");
        itemDef("watching_eye", "block/watching_eye");

        // ---- heart anchor: nothing to render (the heart is drawn by its renderer)
        write(assets.resolve("models/block/pit_heart.json"), "{\n  \"textures\": { \"particle\": \"hha_blood:block/vein_stone\" }\n}\n");
        write(assets.resolve("blockstates/pit_heart.json"), "{\n  \"variants\": {\n    \"\": { \"model\": \"hha_blood:block/pit_heart\" }\n  }\n}\n");

        // ---- blood fall / rising blood: a full cube of flowing blood, faces culled against walls and each other
        for (String n : new String[]{"blood_fall", "blood_rise"}) {
            String t = "hha_blood:block/" + n;
            StringBuilder faces = new StringBuilder();
            String[] dirs = {"north", "east", "south", "west", "up", "down"};
            for (int i = 0; i < dirs.length; i++) {
                if (i > 0) faces.append(", ");
                faces.append("\"").append(dirs[i]).append("\": {\"uv\": [0, 0, 16, 16], \"texture\": \"#fall\", \"cullface\": \"").append(dirs[i]).append("\"}");
            }
            write(assets.resolve("models/block/" + n + ".json"), "{\n  \"textures\": { \"particle\": \"" + t + "\", \"fall\": \"" + t + "\" },\n" +
                "  \"elements\": [\n" + elem(0, 0, 0, 16, 16, 16, faces.toString()) + "\n  ]\n}\n");
        }
        write(assets.resolve("blockstates/blood_fall.json"), "{\n  \"variants\": {\n" +
            "    \"rising=false\": { \"model\": \"hha_blood:block/blood_fall\" },\n" +
            "    \"rising=true\": { \"model\": \"hha_blood:block/blood_rise\" }\n  }\n}\n");

        // ---- blood glow, blood vent
        write(assets.resolve("models/block/blood_glow.json"), "{\n  \"parent\": \"minecraft:block/cube_all\",\n  \"textures\": { \"all\": \"hha_blood:block/blood_glow\" }\n}\n");
        simpleState("blood_glow");
        itemDef("blood_glow", "block/blood_glow");
        write(assets.resolve("models/block/blood_vent.json"), "{\n  \"parent\": \"minecraft:block/cube_bottom_top\",\n  \"textures\": {\n" +
            "    \"top\": \"hha_blood:block/blood_vent_top\",\n    \"side\": \"hha_blood:block/blood_vent_side\",\n    \"bottom\": \"hha_blood:block/blood_stone\"\n  }\n}\n");
        simpleState("blood_vent");
        itemDef("blood_vent", "block/blood_vent");

        // ---- loot
        for (String n : new String[]{"watching_eye", "blood_glow", "blood_vent"}) {
            write(data.resolve("loot_table/blocks/" + n + ".json"),
                "{\n  \"type\": \"minecraft:block\",\n  \"pools\": [\n    {\n      \"bonus_rolls\": 0.0,\n      \"conditions\": [ { \"condition\": \"minecraft:survives_explosion\" } ],\n" +
                    "      \"entries\": [ { \"type\": \"minecraft:item\", \"name\": \"hha_blood:" + n + "\" } ],\n      \"rolls\": 1.0\n    }\n  ],\n  \"random_sequence\": \"hha_blood:blocks/" + n + "\"\n}\n");
        }
    }

    static String elem(int x0, int y0, int z0, int x1, int y1, int z1, String faces) {
        return "    { \"from\": [" + x0 + ", " + y0 + ", " + z0 + "], \"to\": [" + x1 + ", " + y1 + ", " + z1 + "], \"faces\": { " + faces + " } }";
    }

    /** A 1 px deep piece of the front frame (z 0..1) with the requested faces. UVs follow the block position. */
    static String frame(int x0, int y0, int x1, int y1, boolean innerWest, boolean innerEast, boolean innerDown, boolean innerUp) {
        // texture u for a north face runs from east (x 16) to west (x 0)
        int u0 = 16 - x1, u1 = 16 - x0, v0 = 16 - y1, v1 = 16 - y0;
        StringBuilder s = new StringBuilder();
        s.append("\"north\": {\"uv\": [").append(u0).append(", ").append(v0).append(", ").append(u1).append(", ").append(v1).append("], \"texture\": \"#front\", \"cullface\": \"north\"}");
        if (x1 == 16) s.append(", \"east\": {\"uv\": [15, ").append(v0).append(", 16, ").append(v1).append("], \"texture\": \"#flesh\", \"cullface\": \"east\"}");
        if (x0 == 0) s.append(", \"west\": {\"uv\": [0, ").append(v0).append(", 1, ").append(v1).append("], \"texture\": \"#flesh\", \"cullface\": \"west\"}");
        if (y1 == 16) s.append(", \"up\": {\"uv\": [").append(x0).append(", 0, ").append(x1).append(", 1], \"texture\": \"#flesh\", \"cullface\": \"up\"}");
        if (y0 == 0) s.append(", \"down\": {\"uv\": [").append(x0).append(", 15, ").append(x1).append(", 16], \"texture\": \"#flesh\", \"cullface\": \"down\"}");
        // faces looking into the opening (the socket walls)
        if (innerDown) s.append(", \"down\": {\"uv\": [2, 3, 14, 4], \"texture\": \"#front\"}");
        if (innerUp) s.append(", \"up\": {\"uv\": [2, 12, 14, 13], \"texture\": \"#front\"}");
        if (innerEast) s.append(", \"east\": {\"uv\": [1, 4, 2, 12], \"texture\": \"#front\"}");
        if (innerWest) s.append(", \"west\": {\"uv\": [14, 4, 15, 12], \"texture\": \"#front\"}");
        return elem(x0, y0, 0, x1, y1, 1, s.toString());
    }

    static void simpleState(String n) throws Exception {
        write(assets.resolve("blockstates/" + n + ".json"), "{\n  \"variants\": {\n    \"\": { \"model\": \"hha_blood:block/" + n + "\" }\n  }\n}\n");
    }

    static void itemDef(String n, String model) throws Exception {
        write(assets.resolve("items/" + n + ".json"), "{\n  \"model\": {\n    \"type\": \"minecraft:model\",\n    \"model\": \"hha_blood:" + model + "\"\n  }\n}\n");
    }

    // ===================================================================================== preview
    static void preview(Path dir) throws Exception {
        Files.createDirectories(dir);
        String[] names = {"block/watching_eye_front", "block/watching_eye_sclera", "block/watching_eye_flesh", "entity/watching_eye",
            "block/blood_glow", "block/blood_vent_top", "block/blood_vent_side", "block/blood_fall", "block/blood_rise"};
        int sc = 8;
        BufferedImage out = img(names.length * 34 * sc, 34 * sc);
        java.awt.Graphics2D g = out.createGraphics();
        g.setColor(new java.awt.Color(40, 40, 40));
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        for (int i = 0; i < names.length; i++) {
            BufferedImage t = ImageIO.read(assets.resolve("textures/" + names[i] + ".png").toFile());
            g.drawImage(t.getSubimage(0, 0, t.getWidth(), Math.min(t.getHeight(), 32)), i * 34 * sc, 0, 32 * sc, 32 * sc, null);
        }
        // composed eye: front + sclera + iris + lids (open)
        ImageIO.write(out, "png", dir.resolve("pit_textures.png").toFile());
    }
}
