import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import javax.imageio.ImageIO;

/**
 * "Blood's Armor" restyle (v0.4): bone sword with a horned guard, blood-red chevron armour with dark
 * navy under-plates and horns, the flying blood saw. Generates textures AND the 3D item models
 * (sword held model, saw projectile) so texture and UV layout can never drift apart.
 *
 * Usage (from the project root):
 *   java tools/textures/GenBloodStyle.java [projectRoot] [previewDir] [steveSkin.png] [vanillaSword.png]
 *
 * Writes (under blood/src/main/resources/assets/hha_blood/):
 *   textures/item/blood_sword.png(+mcmeta), textures/item/blood_sword_3d.png, models/item/blood_sword_3d.json,
 *   items/blood_sword.json, textures/item/blood_{helmet,chestplate,leggings,boots}.png(+mcmeta),
 *   textures/entity/equipment/humanoid(_leggings)/blood.png, textures/item/bloodsaw.png,
 *   models/item/bloodsaw.json, items/bloodsaw.json
 * GenTextures.java still owns mace, ingot, HUD, particles.
 */
public class GenBloodStyle {

    // ------------------------------------------------------------------ palette
    static final Map<Character, Integer> PAL = new HashMap<>();
    static final String RED = "abcdefgh";
    static {
        PAL.put('.', 0x00000000);
        // dark navy / charcoal (under-plates, horns, outlines)
        PAL.put('K', 0xFF0A0C12);
        PAL.put('1', 0xFF131722);
        PAL.put('2', 0xFF1C2130);
        PAL.put('3', 0xFF262D3E);
        PAL.put('4', 0xFF333C51);
        PAL.put('5', 0xFF455068);
        PAL.put('6', 0xFF5D6985);
        // blood red plates
        PAL.put('a', 0xFF35070A);
        PAL.put('b', 0xFF5A0A0F);
        PAL.put('c', 0xFF830F15);
        PAL.put('d', 0xFFA8161B);
        PAL.put('e', 0xFFCB1F22);
        PAL.put('f', 0xFFE7362E);
        PAL.put('g', 0xFFFF5E4A);
        PAL.put('h', 0xFFFFA08A);
        // bone (sword blade)
        PAL.put('p', 0xFF4C4245);
        PAL.put('q', 0xFF6B6164);
        PAL.put('r', 0xFF8A7F82);
        PAL.put('s', 0xFFA69B9E);
        PAL.put('t', 0xFFC0B6B8);
        PAL.put('u', 0xFFD7CED0);
        PAL.put('v', 0xFFEDE6E7);
        // steel (guard, pommel)
        PAL.put('m', 0xFF2B2B30);
        PAL.put('n', 0xFF3E3E44);
        PAL.put('o', 0xFF55555C);
        PAL.put('i', 0xFF6D6D75);
        PAL.put('j', 0xFF8A8A93);
        PAL.put('k', 0xFFA8A8B0);
        // white grip wrap
        PAL.put('W', 0xFFEDE7E4);
        PAL.put('X', 0xFFBBB3B1);
        PAL.put('Y', 0xFF8F8786);
    }

    static Path ROOT, ASSETS, TEX, PREVIEW;
    static final List<String> written = new ArrayList<>();

    // ------------------------------------------------------------------ grid
    static final class G {
        final int w, h;
        final char[][] c;

        G(int w, int h) {
            this.w = w; this.h = h;
            c = new char[h][w];
            for (char[] r : c) java.util.Arrays.fill(r, '.');
        }

        G(String... rows) {
            this(rows[0].length(), rows.length);
            for (int y = 0; y < h; y++) {
                if (rows[y].length() != w) throw new IllegalArgumentException("row " + y + " has length " + rows[y].length() + ": '" + rows[y] + "'");
                for (int x = 0; x < w; x++) {
                    char ch = rows[y].charAt(x);
                    if (!PAL.containsKey(ch)) throw new IllegalArgumentException("unknown char '" + ch + "' in row " + y);
                    c[y][x] = ch;
                }
            }
        }

        boolean in(int x, int y) { return x >= 0 && y >= 0 && x < w && y < h; }
        char get(int x, int y) { return in(x, y) ? c[y][x] : '.'; }
        void set(int x, int y, char ch) { if (in(x, y)) c[y][x] = ch; }
        boolean solid(int x, int y) { return get(x, y) != '.'; }

        G copy() {
            G g = new G(w, h);
            for (int y = 0; y < h; y++) g.c[y] = c[y].clone();
            return g;
        }

        String key() {
            StringBuilder sb = new StringBuilder();
            for (char[] r : c) sb.append(r);
            return sb.toString();
        }

        BufferedImage img() {
            BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) im.setRGB(x, y, PAL.get(c[y][x]));
            return im;
        }

        /** Adds a 1px outline of ch around all solid pixels (4-neighbourhood). */
        G outlined(char ch) {
            G o = copy();
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                if (solid(x, y)) continue;
                if (solid(x - 1, y) || solid(x + 1, y) || solid(x, y - 1) || solid(x, y + 1)) o.set(x, y, ch);
            }
            return o;
        }

        /** Copies an ASCII block into this grid at (x0,y0); '.' in the block clears the pixel. */
        void blit(int x0, int y0, String... rows) {
            for (int y = 0; y < rows.length; y++) for (int x = 0; x < rows[y].length(); x++) {
                char ch = rows[y].charAt(x);
                if (!PAL.containsKey(ch)) throw new IllegalArgumentException("unknown char '" + ch + "' at " + x + "," + y + " in '" + rows[y] + "'");
                set(x0 + x, y0 + y, ch);
            }
        }

        /** Like blit, but mirrored horizontally (for the left side of symmetric pieces). */
        void blitMirror(int x0, int y0, String... rows) {
            String[] m = new String[rows.length];
            for (int i = 0; i < rows.length; i++) m[i] = new StringBuilder(rows[i]).reverse().toString();
            blit(x0, y0, m);
        }
    }

    static int red(char ch) { return RED.indexOf(ch); }

    /** Shifts a blood pixel by off steps (b..g); everything else stays. */
    static char shift(char ch, int off) {
        int i = red(ch);
        if (i < 1) return ch;
        return RED.charAt(Math.max(1, Math.min(6, i + off)));
    }

    static G pulse(G g, int off) {
        if (off == 0) return g.copy();
        G o = g.copy();
        for (int y = 0; y < g.h; y++) for (int x = 0; x < g.w; x++) o.c[y][x] = shift(g.c[y][x], off);
        return o;
    }

    /** Heartbeat in steps: lub (2 steps), short gap, dub (1 step), then rest. */
    static int beat(int t, int period) {
        int s = Math.floorMod(t, period);
        return switch (s) { case 0, 1, 4 -> 1; default -> 0; };
    }

    static int hash(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h ^ (h >>> 16)) & 0x7fffffff;
    }

    // ------------------------------------------------------------------ output
    static void writePng(String rel, BufferedImage im) throws IOException {
        Path p = TEX.resolve(rel);
        Files.createDirectories(p.getParent());
        ImageIO.write(im, "png", p.toFile());
        written.add("textures/" + rel);
    }

    static void writeStatic(String rel, G g) throws IOException {
        writePng(rel, g.img());
        Files.deleteIfExists(TEX.resolve(rel + ".mcmeta"));
    }

    static void writeAsset(String rel, String text) throws IOException {
        Path p = ASSETS.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
        written.add(rel);
    }

    /** Animated texture from a timeline; consecutive and repeated frames are deduplicated. */
    static void timeline(String rel, int steps, int ticksPerStep, IntFunction<G> fn) throws IOException {
        List<G> frames = new ArrayList<>();
        List<int[]> seq = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (int t = 0; t < steps; t++) {
            G g = fn.apply(t);
            String k = g.key();
            int idx = keys.indexOf(k);
            if (idx < 0) { idx = keys.size(); keys.add(k); frames.add(g); }
            if (!seq.isEmpty() && seq.get(seq.size() - 1)[0] == idx) seq.get(seq.size() - 1)[1] += ticksPerStep;
            else seq.add(new int[]{idx, ticksPerStep});
        }
        if (frames.size() == 1) { writeStatic(rel, frames.get(0)); return; }
        int w = frames.get(0).w, h = frames.get(0).h;
        BufferedImage strip = new BufferedImage(w, h * frames.size(), BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < frames.size(); i++) {
            BufferedImage f = frames.get(i).img();
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) strip.setRGB(x, i * h + y, f.getRGB(x, y));
        }
        writePng(rel, strip);
        StringBuilder sb = new StringBuilder("{\n  \"animation\": {\n    \"interpolate\": false,\n    \"frametime\": ")
            .append(ticksPerStep).append(",\n    \"frames\": [");
        for (int i = 0; i < seq.size(); i++) {
            int[] s = seq.get(i);
            sb.append(i == 0 ? "\n      " : ",\n      ");
            if (s[1] == ticksPerStep) sb.append(s[0]);
            else sb.append("{\"index\": ").append(s[0]).append(", \"time\": ").append(s[1]).append('}');
        }
        sb.append("\n    ]\n  }\n}\n");
        Files.writeString(TEX.resolve(rel + ".mcmeta"), sb.toString(), StandardCharsets.UTF_8);
        written.add("textures/" + rel + ".mcmeta");
    }

    // ================================================================== SWORD ICON (32x32)
    // Sword space on the 32x32 diagonal: a = x - y runs pommel (-31) -> tip (+31),
    // c = x + y - 31 runs across (c < 0 = upper-left edge, c > 0 = lower-right edge).
    // A pixel exists for every (a, c) with a + c odd.

    /** Half width of the blade on one side at axis position a (fractional, compared with |c|). */
    static double bladeHalf(int a, boolean upper) {
        double hw;
        if (a <= 10) hw = 3.6;
        else if (a <= 23) hw = 3.6 - (a - 10) * (1.6 / 13.0);
        else hw = 2.0 - (a - 23) * 0.42;
        // serrations: barbs that hook towards the hilt, staggered between the edges
        int[] barbs = upper ? new int[]{-1, 11, 21} : new int[]{4, 16};
        for (int b : barbs) {
            if (a == b || a == b + 1) hw += 1.0;
            else if (a == b + 2 || a == b + 3) hw -= 0.7;
        }
        return hw;
    }

    static char swordPix(int a, int c) {
        // ---- blade (the hilt is drawn pixel by pixel in swordIconBase)
        if (a >= -6 && a <= 29) {
            boolean upper = c < 0;
            double hw = bladeHalf(a, upper);
            if (Math.abs(c) <= hw + 0.01) {
                int edge = (int) Math.floor(hw + 0.01);
                if (c == 0) return a > 25 ? 'u' : 'v';
                if (Math.abs(c) == edge && edge >= 1) return upper ? 'u' : 'q';
                if (upper) return Math.abs(c) == 1 ? 'u' : 't';
                return Math.abs(c) == 1 ? 's' : (Math.abs(c) >= edge - 1 ? 'r' : 's');
            }
            return '.';
        }
        return '.';
    }

    /** Sets a pixel and its mirror image across the blade axis (x+y = 31); the mirrored side is shaded darker. */
    static void hilt(G g, int x, int y, char ch, char mirrored) {
        g.set(x, y, ch);
        if (mirrored != 0) g.set(31 - y, 31 - x, mirrored);
    }

    static G swordIconBase() {
        G g = new G(32, 32);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
            char ch = swordPix(x - y, x + y - 31);
            if (ch != '.') g.set(x, y, ch);
        }
        // ---- guard: a three-pixel band across the blade (upper-left half drawn, lower-right mirrored)
        int[][] top = {{6, 16}, {7, 17}, {8, 18}, {9, 19}, {10, 20}};   // blade side, catches the light
        int[][] mid = {{6, 17}, {7, 18}, {8, 19}, {9, 20}};
        int[][] bot = {{6, 18}, {7, 19}, {8, 20}, {9, 21}};             // grip side, in shadow
        for (int[] p : top) hilt(g, p[0], p[1], 'k', 'j');
        for (int[] p : mid) hilt(g, p[0], p[1], 'i', 'o');
        for (int[] p : bot) hilt(g, p[0], p[1], 'o', 'n');
        g.set(10, 21, 'i');
        // horn spike rising outwards at each end
        hilt(g, 7, 16, 'j', 'o');
        hilt(g, 6, 15, 'k', 'i');
        hilt(g, 5, 14, 'j', 'o');
        // hook curling round towards the grip
        hilt(g, 5, 16, 'k', 'j');
        hilt(g, 5, 17, 'j', 'i');
        hilt(g, 4, 17, 'k', 'j');
        hilt(g, 4, 18, 'j', 'i');
        hilt(g, 5, 18, 'i', 'o');
        hilt(g, 5, 19, 'o', 'n');
        hilt(g, 4, 19, 'i', 'o');
        hilt(g, 4, 20, 'o', 'n');
        hilt(g, 5, 21, 'n', 'm');
        hilt(g, 6, 19, 'n', 'm');
        // red cross binding over the middle of the guard
        g.set(10, 21, 'g');
        g.set(10, 20, 'f'); g.set(9, 21, 'e'); g.set(11, 21, 'e'); g.set(10, 22, 'd');
        // ---- collar with a red V running into the ridge
        hilt(g, 10, 19, 'n', 'm');
        hilt(g, 10, 18, 'e', 'd');
        hilt(g, 11, 19, 'o', 'n');
        g.set(11, 20, 'n');
        hilt(g, 11, 18, 'e', 'd');
        g.set(12, 19, 'o');
        hilt(g, 12, 18, 'f', 'e');
        g.set(13, 18, 'f');
        // ---- grip: red and white wrap in diagonal bands of two
        for (int a = -24; a <= -13; a++) for (int c = -1; c <= 1; c++) {
            if (((a + c) & 1) == 0) continue;
            int x = (a + c + 31) / 2, y = (c - a + 31) / 2;
            char ch;
            if (a >= -14) ch = c < 0 ? 'c' : 'b';
            else if (Math.floorMod(-a - 15, 4) < 2) ch = c < 0 ? 'W' : (c == 0 ? 'W' : 'X');
            else ch = c < 0 ? 'f' : (c == 0 ? 'e' : 'd');
            g.set(x, y, ch);
        }
        // ---- pommel: a diamond (in sword space) with a red gem
        for (int y = 28; y <= 30; y++) for (int x = 1; x <= 3; x++) {
            boolean lit = x + y < 31 || (x + y == 31 && x < 2);
            g.set(x, y, lit ? 'j' : 'o');
        }
        g.set(1, 28, 'k'); g.set(3, 30, 'n');
        g.set(2, 29, 'g'); g.set(3, 28, 'i'); g.set(1, 30, 'i');
        g.set(0, 31, 'n');
        G o = g.outlined('K');
        // dried blood near the hilt and along the lower edge
        int[][] stains = {{-3, 1, 'c'}, {-2, 2, 'b'}, {-1, 2, 'c'}, {0, 3, 'b'}, {2, 1, 'c'}, {-4, -1, 'c'}, {9, 2, 'b'}, {1, -2, 'c'}};
        for (int[] s : stains) {
            int a = s[0], cc = s[1];
            if (((a + cc) & 1) == 0) continue;
            int x = (a + cc + 31) / 2, y = (cc - a + 31) / 2;
            if (o.solid(x, y) && "qrstuv".indexOf(o.get(x, y)) >= 0) o.set(x, y, (char) s[2]);
        }
        return o;
    }

    static int[] sp(int a, int c) { return new int[]{(a + c + 31) / 2, (c - a + 31) / 2}; }

    static void dump(G g) {
        if (System.getenv("GEN_DUMP") == null) return;
        for (char[] r : g.c) System.out.println("  \"" + new String(r) + "\",");
    }

    static void swordIcon() throws IOException {
        G base = swordIconBase();
        dump(base);
        // a bead of blood runs down the lower-right edge from the middle of the blade to the guard
        timeline("item/blood_sword.png", 48, 2, t -> {
            G g = pulse(base, beat(t, 24));
            int s = t - 8;
            if (s >= 0 && s < 16) {
                int a = 17 - s * 2, cc = 2; // a odd, c even -> a valid pixel
                int[] p = sp(a, cc);
                int[] q = sp(a + 2, cc);
                if (g.solid(q[0], q[1]) && "qrstuv".indexOf(g.get(q[0], q[1])) >= 0) g.set(q[0], q[1], 'd');
                if (g.solid(p[0], p[1])) g.set(p[0], p[1], 'f');
            } else if (s >= 16 && s < 22) {
                int[] p = sp(-4, 1);
                g.set(p[0], p[1], s < 19 ? 'f' : 'e');
            }
            return g;
        });
    }

    // ================================================================== 3D BOX MODELS
    static final String[] DIRS = {"north", "east", "south", "west", "up", "down"};

    /** An element of a block-model; coordinates in model units (pixels). */
    static final class Box {
        final String tag;
        final double x1, y1, z1, x2, y2, z2;
        final Map<String, double[]> uv = new HashMap<>(); // dir -> {u1,v1,u2,v2} in texels
        Box(String tag, double x1, double y1, double z1, double x2, double y2, double z2) {
            this.tag = tag; this.x1 = x1; this.y1 = y1; this.z1 = z1; this.x2 = x2; this.y2 = y2; this.z2 = z2;
        }
        double[] min() { return new double[]{x1, y1, z1}; }
        double[] max() { return new double[]{x2, y2, z2}; }
        /** face size in texels: {w, h} */
        int[] size(String d) {
            double w = x2 - x1, h = y2 - y1, dd = z2 - z1;
            return switch (d) {
                case "north", "south" -> new int[]{(int) Math.round(w), (int) Math.round(h)};
                case "east", "west" -> new int[]{(int) Math.round(dd), (int) Math.round(h)};
                default -> new int[]{(int) Math.round(w), (int) Math.round(dd)};
            };
        }
        /** model-space position of the centre of texel (i,j) of face d (minecraft default-uv orientation). */
        double[] texel(String d, int i, int j) {
            double u = i + 0.5, v = j + 0.5;
            return switch (d) {
                case "south" -> new double[]{x1 + u, y2 - v, z2};
                case "north" -> new double[]{x2 - u, y2 - v, z1};
                case "west" -> new double[]{x1, y2 - v, z1 + u};
                case "east" -> new double[]{x2, y2 - v, z2 - u};
                case "up" -> new double[]{x1 + u, y2, z1 + v};
                default -> new double[]{x1 + u, y1, z2 - v};
            };
        }
    }

    interface Painter { char paint(Box b, String dir, double x, double y, double z, int i, int j); }

    /** True if the face of b in direction d is completely covered by another box touching it. */
    static boolean hidden(List<Box> boxes, Box b, String d) {
        int ax = switch (d) { case "east", "west" -> 0; case "up", "down" -> 1; default -> 2; };
        boolean pos = d.equals("east") || d.equals("up") || d.equals("south");
        double plane = pos ? b.max()[ax] : b.min()[ax];
        for (Box o : boxes) {
            if (o == b) continue;
            double omin = o.min()[ax], omax = o.max()[ax];
            boolean spans = pos ? (omin <= plane + 1e-6 && omax > plane + 1e-6) : (omax >= plane - 1e-6 && omin < plane - 1e-6);
            if (!spans) continue;
            boolean cover = true;
            for (int k = 0; k < 3; k++) {
                if (k == ax) continue;
                if (o.min()[k] > b.min()[k] + 1e-6 || o.max()[k] < b.max()[k] - 1e-6) { cover = false; break; }
            }
            if (cover) return true;
        }
        return false;
    }

    /** Packs all visible faces into a square atlas (1 texel per model unit) and paints them. */
    static G atlas(List<Box> boxes, Painter p, int size) {
        G g = new G(size, size);
        List<Object[]> faces = new ArrayList<>();
        for (Box b : boxes) for (String d : DIRS) {
            if (hidden(boxes, b, d)) continue;
            int[] s = b.size(d);
            if (s[0] <= 0 || s[1] <= 0) continue;
            faces.add(new Object[]{b, d, s});
        }
        // shelf packing, tallest first
        faces.sort((A, B) -> {
            int[] sa = (int[]) A[2], sb = (int[]) B[2];
            if (sb[1] != sa[1]) return sb[1] - sa[1];
            return sb[0] - sa[0];
        });
        int x = 0, y = 0, rowH = 0;
        for (Object[] f : faces) {
            Box b = (Box) f[0];
            String d = (String) f[1];
            int[] s = (int[]) f[2];
            if (x + s[0] > size) { x = 0; y += rowH; rowH = 0; }
            if (y + s[1] > size) throw new IllegalStateException("atlas " + size + " too small");
            for (int j = 0; j < s[1]; j++) for (int i = 0; i < s[0]; i++) {
                double[] q = b.texel(d, i, j);
                char ch = p.paint(b, d, q[0], q[1], q[2], i, j);
                g.set(x + i, y + j, ch == '.' ? 'K' : ch);
            }
            b.uv.put(d, new double[]{x, y, x + s[0], y + s[1]});
            x += s[0];
            rowH = Math.max(rowH, s[1]);
        }
        return g;
    }

    static String num(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return Long.toString(Math.round(v));
        String s = String.format(Locale.ROOT, "%.4f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    static String vec(double a, double b, double c) { return "[" + num(a) + ", " + num(b) + ", " + num(c) + "]"; }

    /** Block-model JSON. rotZ != 0 rotates every element around (8,8,8) about z (rigid rotation of the model). */
    static String modelJson(List<Box> boxes, String texture, String particle, int atlasSize, double rotZ, String display) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"textures\": {\n    \"0\": \"").append(texture).append("\",\n    \"particle\": \"").append(particle).append("\"\n  },\n");
        sb.append("  \"elements\": [\n");
        double k = 16.0 / atlasSize;
        for (int n = 0; n < boxes.size(); n++) {
            Box b = boxes.get(n);
            sb.append("    {\n      \"name\": \"").append(b.tag).append("\",\n");
            sb.append("      \"from\": ").append(vec(b.x1, b.y1, b.z1)).append(",\n");
            sb.append("      \"to\": ").append(vec(b.x2, b.y2, b.z2)).append(",\n");
            if (rotZ != 0) sb.append("      \"rotation\": {\"origin\": [8, 8, 8], \"axis\": \"z\", \"angle\": ").append(num(rotZ)).append("},\n");
            sb.append("      \"faces\": {\n");
            List<String> fs = new ArrayList<>();
            for (String d : DIRS) {
                double[] u = b.uv.get(d);
                if (u == null) continue;
                fs.add("        \"" + d + "\": {\"uv\": [" + num(u[0] * k) + ", " + num(u[1] * k) + ", " + num(u[2] * k) + ", " + num(u[3] * k) + "], \"texture\": \"#0\"}");
            }
            sb.append(String.join(",\n", fs)).append("\n      }\n    }").append(n < boxes.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ]");
        if (display != null) sb.append(",\n  \"display\": ").append(display);
        sb.append("\n}\n");
        return sb.toString();
    }

    // ================================================================== 3D SWORD
    // Built upright (blade along +y, centred on x = 8, z = 8), then every element is rotated -45 degrees
    // about z around (8,8,8) so the model lies on the same diagonal as a vanilla handheld sprite
    // (pommel bottom-left, tip top-right). That lets it reuse vanilla's handheld transforms.
    static final double SWORD_ROT = -45;

    static List<Box> swordBoxes() {
        List<Box> L = new ArrayList<>();
        // blade: jagged bone segments (x extents per y range), 1 thick
        double[][] seg = {
            // y1, y2, xL, xR
            {8, 10, 5.5, 10.5},
            {10, 11, 4.5, 10.5},  // barb left
            {11, 13, 5.5, 10.5},
            {13, 14, 5.5, 11.5},  // barb right
            {14, 16, 6.5, 10.5},
            {16, 17, 5.5, 10.5},  // barb left
            {17, 19, 6.5, 9.5},
            {19, 20, 6.5, 10.5},  // barb right
            {20, 22, 6.5, 9.5},
            {22, 23, 5.5, 9.5},   // barb left
            {23, 24, 7.5, 9.5},
            {24, 26, 7.5, 8.5},   // tip
        };
        for (double[] s : seg) L.add(new Box("blade", s[2], s[0], 7.5, s[3], s[1], 8.5));
        // raised ridge down the middle of the blade
        L.add(new Box("ridge", 7.5, 7, 7, 8.5, 22, 9));
        // collar with the red V
        L.add(new Box("collar", 6, 6, 6.5, 10, 8, 9.5));
        // guard: core block, arms, horned ends
        L.add(new Box("guard", 5, 3, 6, 11, 6, 10));
        L.add(new Box("arm", 1, 4, 6.5, 5, 6, 9.5));
        L.add(new Box("arm", 11, 4, 6.5, 15, 6, 9.5));
        L.add(new Box("horn_up", 0, 6, 7, 2, 7, 9));
        L.add(new Box("horn_up", 14, 6, 7, 16, 7, 9));
        L.add(new Box("horn_tip", -1, 7, 7.5, 1, 8, 8.5));
        L.add(new Box("horn_tip", 15, 7, 7.5, 17, 8, 8.5));
        L.add(new Box("hook", 0, 1, 7, 2, 4, 9));
        L.add(new Box("hook", 14, 1, 7, 16, 4, 9));
        L.add(new Box("hook_tip", 2, 0, 7.5, 3, 2, 8.5));
        L.add(new Box("hook_tip", 13, 0, 7.5, 14, 2, 8.5));
        // grip, wrapped red and white
        L.add(new Box("grip", 7, -3, 7, 9, 3, 9));
        // pommel: wide block and a point
        L.add(new Box("pommel", 6, -6, 6, 10, -3, 10));
        L.add(new Box("pommel_tip", 7, -8, 7, 9, -6, 9));
        return L;
    }

    static char swordPaint(Box b, String d, double x, double y, double z, int i, int j) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        int n = hash(xi * 3 + zi, yi, 7) % 11;
        boolean side = d.equals("east") || d.equals("west");
        boolean flat = d.equals("north") || d.equals("south");
        switch (b.tag) {
            case "blade": {
                if (d.equals("up")) return 't';
                if (d.equals("down")) return 'q';
                if (side) return d.equals("west") ? 'u' : 's';  // the cutting edges catch the light
                double dx = Math.abs(x - 8);
                boolean edge = x - b.x1 < 1 || b.x2 - x < 1;
                char ch = edge ? (x < 8 ? 's' : 'r') : (dx < 2 ? 't' : 's');
                if (n == 0 && !edge) ch = 'u';
                if (n == 1) ch = edge ? 'q' : 'r';
                // dried blood near the hilt
                if (yi <= 10 && hash(xi, yi, d.equals("south") ? 3 : 4) % 4 == 0) ch = yi == 8 ? 'c' : 'b';
                if (yi == 12 && xi == (d.equals("south") ? 9 : 6)) ch = 'c';
                return ch;
            }
            case "ridge": {
                if (flat) return yi >= 20 ? 'u' : (yi <= 8 ? 'd' : ((yi & 3) == 0 ? 'u' : 'v'));
                if (d.equals("up")) return 'v';
                return d.equals("west") ? 't' : 'r';
            }
            case "collar": {
                if (flat) {
                    // red V running from the collar corners into the ridge
                    int dx = (int) Math.floor(Math.abs(x - 8));
                    int row = (int) Math.floor(8 - y); // 0 top, 1 bottom
                    if ((row == 1 && dx == 1) || (row == 0 && dx == 0)) return 'e';
                    return row == 0 ? 'j' : 'i';
                }
                if (d.equals("up")) return 'j';
                return d.equals("west") ? 'i' : 'o';
            }
            case "guard": {
                if (flat) {
                    // red X binding across the core block
                    double lx = x - 5, ly = 6 - y; // 0..6, 0..3
                    int cx = (int) Math.floor(lx), cy = (int) Math.floor(ly);
                    if ((cy == 0 && (cx == 1 || cx == 4)) || (cy == 1 && (cx == 2 || cx == 3)) || (cy == 2 && (cx == 1 || cx == 4))) return cy == 1 ? 'f' : 'e';
                    if (cy == 0) return 'k';
                    if (cx == 0 || cx == 5) return 'n';
                    return cy == 2 ? 'o' : 'i';
                }
                if (d.equals("up")) return 'k';
                if (d.equals("down")) return 'm';
                return d.equals("west") ? 'j' : 'o';
            }
            case "arm": {
                if (flat) {
                    int row = (int) Math.floor(6 - y);
                    if (row == 0) return n == 0 ? 'k' : 'j';
                    return n == 0 ? 'o' : 'i';
                }
                if (d.equals("up")) return 'j';
                if (d.equals("down")) return 'n';
                return 'o';
            }
            case "horn_up": case "horn_tip": {
                if (d.equals("up")) return 'k';
                if (d.equals("down")) return 'n';
                if (flat) return b.tag.equals("horn_tip") ? 'k' : 'j';
                return 'i';
            }
            case "hook": case "hook_tip": {
                if (d.equals("down")) return 'm';
                if (d.equals("up")) return 'i';
                if (flat) return b.tag.equals("hook_tip") ? 'n' : (y > 3 ? 'i' : (y > 2 ? 'o' : 'n'));
                return 'n';
            }
            case "grip": {
                if (d.equals("up") || d.equals("down")) return 'c';
                // spiral wrap: the band index walks with the height plus the position around the grip
                int around = switch (d) { case "south" -> (int) Math.floor(x - 7); case "east" -> 2 + (int) Math.floor(9 - z); case "north" -> 4 + (int) Math.floor(9 - x); default -> 6 + (int) Math.floor(z - 7); };
                int band = Math.floorMod(yi * 2 + around, 6);
                boolean lit = d.equals("south") || d.equals("west");
                if (band < 3) return lit ? 'W' : 'X';
                return lit ? 'e' : 'd';
            }
            case "pommel": {
                if (flat) {
                    int cx = (int) Math.floor(x - 6), cy = (int) Math.floor(-3 - y); // 0..3, 0..2
                    if ((cx == 1 || cx == 2) && cy == 1) return cx == 1 ? 'g' : 'e';
                    if (cy == 0) return 'j';
                    return cx == 0 || cx == 3 ? 'n' : 'o';
                }
                if (d.equals("up")) return 'k';
                if (d.equals("down")) return 'n';
                if (side) {
                    int cy = (int) Math.floor(-3 - y);
                    return cy == 0 ? 'j' : (cy == 1 ? 'i' : 'o');
                }
                return 'o';
            }
            case "pommel_tip": {
                if (d.equals("down")) return 'n';
                return d.equals("west") || d.equals("south") ? 'i' : 'o';
            }
        }
        return 'K';
    }

    static final int SWORD_ATLAS = 64;
    static final double SWORD_SCALE_TP = 0.95, SWORD_SCALE_FP = 0.616;

    static String swordDisplay() {
        String s3 = num(SWORD_SCALE_TP), s1 = num(SWORD_SCALE_FP);
        return "{\n"
            + "    \"thirdperson_righthand\": {\"rotation\": [0, -90, 25], \"translation\": [0, 5.08, 4.17], \"scale\": [" + s3 + ", " + s3 + ", " + s3 + "]},\n"
            + "    \"thirdperson_lefthand\": {\"rotation\": [0, 90, -25], \"translation\": [0, 5.08, 4.17], \"scale\": [" + s3 + ", " + s3 + ", " + s3 + "]},\n"
            + "    \"firstperson_righthand\": {\"rotation\": [0, -90, 25], \"translation\": [1.13, 3.2, 1.13], \"scale\": [" + s1 + ", " + s1 + ", " + s1 + "]},\n"
            + "    \"firstperson_lefthand\": {\"rotation\": [0, 90, -25], \"translation\": [1.13, 3.2, 1.13], \"scale\": [" + s1 + ", " + s1 + ", " + s1 + "]},\n"
            + "    \"ground\": {\"rotation\": [0, 0, 0], \"translation\": [0, 2, 0], \"scale\": [0.42, 0.42, 0.42]},\n"
            + "    \"head\": {\"rotation\": [0, 180, 0], \"translation\": [0, 13, 7], \"scale\": [0.9, 0.9, 0.9]}\n"
            + "  }";
    }

    static List<Box> SWORD;

    static void sword3d() throws IOException {
        SWORD = swordBoxes();
        G tex = atlas(SWORD, GenBloodStyle::swordPaint, SWORD_ATLAS);
        writeStatic("item/blood_sword_3d.png", tex);
        writeAsset("models/item/blood_sword_3d.json",
            modelJson(SWORD, "hha_blood:item/blood_sword_3d", "hha_blood:item/blood_sword", SWORD_ATLAS, SWORD_ROT, swordDisplay()));
        writeAsset("items/blood_sword.json", """
            {
              "model": {
                "type": "minecraft:select",
                "property": "minecraft:display_context",
                "cases": [
                  {
                    "when": ["gui", "fixed", "on_shelf"],
                    "model": {
                      "type": "minecraft:model",
                      "model": "hha_blood:item/blood_sword"
                    }
                  }
                ],
                "fallback": {
                  "type": "minecraft:model",
                  "model": "hha_blood:item/blood_sword_3d"
                }
              }
            }
            """);
    }

    // ================================================================== ARMOUR LAYERS (64x32, vanilla layout)
    // Face column order (minecraft box uv): right side = back..front, front = entity right..left,
    // left side = front..back, back = entity left..right, top = rows back..front.

    /**
     * Stacked V chevrons (pointing down to the centre line) over a w x h face: bright top edge, red body,
     * shadow, then a navy groove. starts = row where each chevron begins at the outer columns.
     */
    static void chevrons(G g, int u, int v, int w, int h, int[] starts, char above) {
        int half = w / 2;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int k = x < half ? x : w - 1 - x;  // 0 at the outer edge, half-1 at the centre
            char ch = above;
            for (int si = 0; si < starts.length; si++) {
                int r = y - (starts[si] + k);
                if (r == 0) ch = si == 0 ? 'g' : 'f';
                else if (r == 1) ch = 'e';
                else if (r == 2) ch = 'd';
                else if (r == 3) ch = x < half ? '2' : '1';
            }
            g.set(u + x, v + y, ch);
        }
    }

    static G humanoidLayer() {
        G g = new G(64, 32);
        // ---------------- helmet: top 8,0 | right 0,8 | front 8,8 | left 16,8 | back 24,8
        g.blit(8, 0,
            "bcdffdcb",
            "cdeggedc",
            "cdfggfdc",
            "dcdffdcd",
            "cdeggedc",
            "cdfggfdc",
            "dcdffdcd",
            "deeggeed");
        g.blit(8, 8,
            "gefggfeg",
            "cgeffegc",
            "bcgeegcb",
            "KbcggcbK",
            "cK1ee1Kc",
            "dcKddKcd",
            "2dcffcd2",
            "12dccd21");
        String[] side = {
            "cdeeffgf",
            "bcddefge",
            "2bcdgfed",
            "12bgfedc",
            "21bgfeed",
            "322bgfed",
            "2322bgfe",
            "32322bcd"};
        g.blit(0, 8, side);
        g.blitMirror(16, 8, side);
        g.blit(24, 8,
            "cdeffedc",
            "dcdggdcd",
            "2dcffcd2",
            "12dggd21",
            "212ff212",
            "3212d123",
            "23212132",
            "32321323");
        // ---------------- horns on the hat layer (sits 0.5px above the helmet -> real relief)
        // top 40,0 | right 32,8 | front 40,8 | left 48,8 | back 56,8
        g.blit(40, 0,
            "..4..4..",
            ".54..45.",
            "54....45",
            "43....34",
            "54....45",
            "43....34",
            "65....56",
            "56....65");
        g.blit(40, 8,
            "65....56",
            "54....45",
            "43....34",
            "3......3",
            "........",
            "........",
            "........",
            "........");
        String[] hornSide = {
            "..434556",
            "...32345",
            "......34",
            ".......3",
            "........",
            "........",
            "........",
            "........"};
        g.blit(32, 8, hornSide);
        g.blitMirror(48, 8, hornSide);
        g.blit(56, 8,
            ".3....3.",
            "........",
            "........",
            "........",
            "........",
            "........",
            "........",
            "........");

        // ---------------- chestplate: top 20,16 | bottom 28,16 | right 16,20 | front 20,20 | left 28,20 | back 32,20
        g.blit(20, 16,
            "deeffeed",
            "deffffed",
            "efgffgfe",
            "fgfeefgf");
        g.blit(28, 16,
            "21212121",
            "12121212",
            "21212121",
            "12121212");
        chevrons(g, 20, 20, 8, 12, new int[]{1, 5, 9}, '3');
        // V-neck collar and a navy waist band under the last chevron
        g.set(23, 20, '1'); g.set(24, 20, '1'); g.set(22, 20, '2'); g.set(25, 20, '2');
        for (int x = 20; x < 28; x++) if (x != 23 && x != 24) g.set(x, 31, (x & 1) == 0 ? '2' : '1');
        chevrons(g, 32, 20, 8, 12, new int[]{0, 4, 8}, '2');
        // spine with small spikes on the back
        for (int y = 20; y < 32; y++) { g.set(35, y, (y & 1) == 0 ? '4' : '3'); g.set(36, y, (y & 1) == 0 ? '3' : '2'); }
        g.set(35, 22, 'f'); g.set(36, 22, 'e'); g.set(35, 26, 'f'); g.set(36, 26, 'e'); g.set(35, 30, 'e'); g.set(36, 30, 'd');
        String[] chestSide = {
            "ffgg",
            "eeff",
            "ddee",
            "2222",
            "ffgf",
            "eeef",
            "ddde",
            "2222",
            "effg",
            "deef",
            "cdde",
            "1212"};
        g.blit(16, 20, chestSide);
        g.blitMirror(28, 20, chestSide);

        // ---------------- arms (pauldron + bracer): top 44,16 | bottom 48,16 | outer 40,20 | front 44,20 | inner 48,20 | back 52,20
        g.blit(44, 16,
            "effe",
            "fggf",
            "fggf",
            "effe");
        g.blit(48, 16,
            "2121",
            "1212",
            "2121",
            "1212");
        g.blit(40, 20,          // outer, back..front
            "fggf",
            "efge",
            "dcfd",
            "fgdg",
            "dfef",
            "c3d3",
            "3232",
            "2323",
            "e3e3",
            "fefe",
            "gfgf",
            "dcdc");
        g.blit(44, 20,          // front, outer..inner
            "ggfe",
            "gfed",
            "fdc2",
            "gfd2",
            "efc3",
            "d3c2",
            "3232",
            "2323",
            "e3e3",
            "ffee",
            "gfed",
            "dcc2");
        g.blit(48, 20,          // inner (towards the body), front..back
            "edde",
            "dccd",
            "2112",
            "3223",
            "2332",
            "3223",
            "2112",
            "1221",
            "2d2d",
            "dcdc",
            "edcd",
            "c2c2");
        g.blit(52, 20,          // back, inner..outer
            "efgg",
            "defg",
            "2cdf",
            "2dfg",
            "3cfe",
            "2c3d",
            "2323",
            "3232",
            "3e3e",
            "eeff",
            "defg",
            "2ccd");

        // ---------------- boots (lower legs): top 4,16 | sole 8,16 | outer 0,20 | front 4,20 | inner 8,20 | back 12,20
        g.blit(8, 16,
            "1K1K",
            "K1K1",
            "1K1K",
            "K1K1");
        g.blit(0, 25,           // outer, back..front
            ".e.f",
            "dfeg",
            "efgf",
            "cdfe",
            "2cde",
            "32cd",
            "1K1K");
        g.blit(4, 25,           // front, outer..inner
            "g..g",
            "fggf",
            "efge",
            "dffd",
            "cdec",
            "3cc3",
            "K11K");
        g.blit(8, 25,           // inner, front..back
            "....",
            "edde",
            "dccd",
            "2cc2",
            "3223",
            "2332",
            "1K1K");
        g.blit(12, 25,          // back, inner..outer
            "f.e.",
            "gefd",
            "fgfe",
            "efdc",
            "edc2",
            "dc23",
            "K1K1");
        return g;
    }

    static G leggingsLayer() {
        G g = new G(64, 32);
        // ---------------- legs: outer 0,20 | front 4,20 | inner 8,20 | back 12,20 (rows 20..31)
        g.blit(0, 20,           // outer, back..front
            "deef",
            "effg",
            "cdde",
            "fggf",
            "deef",
            "cdde",
            "2323",
            "3232",
            "2c2d",
            "3232",
            "2323",
            "3232");
        g.blit(4, 20,           // front, outer..inner
            "efge",
            "dfgd",
            "cefc",
            "gefg",
            "dfgd",
            "cefc",
            "2gg2",
            "3ff3",
            "23d2",
            "3232",
            "2323",
            "3232");
        g.blit(8, 20,           // inner, front..back
            "dccd",
            "2dd2",
            "3223",
            "2332",
            "3223",
            "2332",
            "3223",
            "2332",
            "3223",
            "2332",
            "3223",
            "2332");
        g.blit(12, 20,          // back, inner..outer
            "feed",
            "gffe",
            "edc2",
            "fgfe",
            "efed",
            "dcc2",
            "3232",
            "2323",
            "3d3c",
            "2323",
            "3232",
            "2323");
        // ---------------- belt + faulds on the body: right 16,20 | front 20,20 | left 28,20 | back 32,20 (rows 26..31)
        g.blit(20, 26,
            "21122112",
            "1K1ff1K1",
            "geffffeg",
            "cgeddegc",
            "2cgeegc2",
            "32cggc23");
        g.blit(32, 26,
            "21122112",
            "1K1dd1K1",
            "feeddeef",
            "cfeddefc",
            "2cfddfc2",
            "32cffc23");
        String[] beltSide = {
            "2112",
            "1K1K",
            "effg",
            "deef",
            "cdde",
            "2ccd"};
        g.blit(16, 26, beltSide);
        g.blitMirror(28, 26, beltSide);
        return g;
    }

    static void armourLayers() throws IOException {
        writeStatic("entity/equipment/humanoid/blood.png", humanoidLayer());
        writeStatic("entity/equipment/humanoid_leggings/blood.png", leggingsLayer());
    }

    // ================================================================== ARMOUR ICONS (16x16)
    static final String[] HELMET_ICON = {
        "................",
        ".K............K.",
        "K6K..........K6K",
        "K54K........K45K",
        ".K43K......K34K.",
        "..K32KKKKKK23K..",
        "...KgeffffegK...",
        "..KcgeffffegcK..",
        "..KbcgeffegcbK..",
        "..K1bcgeegcb1K..",
        "..KcK1cggc1KcK..",
        "..KdcKKddKKcdK..",
        "..K2dcbffbcd2K..",
        "...K2dcddcd2K...",
        "....KKKKKKKK....",
        "................",
    };

    static final String[] CHEST_ICON = {
        "................",
        ".K............K.",
        "KgK.KKK..KKK.KgK",
        "KfgKfeK..KefKgfK",
        "KefgfeKKKKefgfeK",
        "KdeffK1221KffedK",
        "KcdeeKK22KKeedcK",
        ".KKKdgfKKfgdKKK.",
        "...K1dgffgd1K...",
        "...KgfKddKfgK...",
        "...K1dgffgd1K...",
        "...KgfKddKfgK...",
        "...K2dgffgd2K...",
        "...K12KddK21K...",
        "....KKK..KKK....",
        "................",
    };

    static final String[] LEGS_ICON = {
        "................",
        "................",
        "...KKKKKKKKKK...",
        "...K21K11K12K...",
        "...KgeffffegK...",
        "...KcgeffegcK...",
        "...KfcgKKgcfK...",
        "...KefK..KfeK...",
        "...KdgK..KgdK...",
        "...KcfK..KfcK...",
        "...KgfK..KfgK...",
        "...K3gK..Kg3K...",
        "...K23K..K32K...",
        "...K32K..K23K...",
        "...KKKK..KKKK...",
        "................",
    };

    static final String[] BOOTS_ICON = {
        "................",
        "................",
        "................",
        "...K.K....K.K...",
        "..KgKgK..KgKgK..",
        "..KfgfK..KfgfK..",
        "..KefeK..KefeK..",
        "..KdgdK..KdgdK..",
        "..KcfcK..KcfcK..",
        ".KedcdK..KdcdeK.",
        "KgfedcK..KcdefgK",
        "K32211K..K11223K",
        "KKKKKKK..KKKKKKK",
        "................",
        "................",
        "................",
    };

    /** A drop gathering under (x,y) for 6 steps, then falling one pixel per step until it leaves the canvas. */
    static void dripFrom(G g, int x, int y, int a) {
        if (a < 0) return;
        if (a < 6) {
            g.set(x, y, 'g');
            if (a >= 3 && !g.solid(x, y + 1)) g.set(x, y + 1, 'e');
        } else {
            int fy = y + 2 + (a - 6);
            if (fy < g.h) {
                if (!g.solid(x, fy - 1)) g.set(x, fy - 1, 'c');
                g.set(x, fy, 'e');
            }
        }
    }

    /** Heartbeat on the red plates + a drop forming at (dx,dy) and falling off. */
    static void armourIcon(String name, String[] rows, int dx, int dy) throws IOException {
        G base = new G(rows);
        timeline("item/" + name + ".png", 48, 2, t -> {
            G g = pulse(base, beat(t, 24));
            dripFrom(g, dx, dy, t - 10);
            return g;
        });
    }

    static void armourIcons() throws IOException {
        armourIcon("blood_helmet", HELMET_ICON, 3, 14);
        armourIcon("blood_chestplate", CHEST_ICON, 2, 8);
        armourIcon("blood_leggings", LEGS_ICON, 7, 7);
        armourIcon("blood_boots", BOOTS_ICON, 0, 13);
    }

    // ================================================================== BLOOD SAW (3D projectile)
    // A 12x12 disc in the x/y plane (z 7.5..8.5) centred on (8,8,8), built from one box per row run so the
    // edge faces sit on the real (toothed) rim; a 4x4x2 hub sticks out on both sides.
    static final double SAW_BODY = 4.25, SAW_TOOTH = 2.1;
    static final int SAW_TEETH = 8;

    static boolean sawSolid(double x, double y) {
        double dx = x - 8, dy = y - 8;
        double r = Math.sqrt(dx * dx + dy * dy);
        if (r <= SAW_BODY) return true;
        double a = Math.atan2(dy, dx);
        double f = ((a / (2 * Math.PI) * SAW_TEETH) % 1 + 1) % 1; // 0..1 across a tooth
        return r <= SAW_BODY + SAW_TOOTH * (1 - f) * (1 - f * 0.35) && r <= 6.2;
    }

    static List<Box> sawBoxes() {
        List<Box> L = new ArrayList<>();
        for (int y = 2; y < 14; y++) {
            int x = 2;
            while (x < 14) {
                if (!sawSolid(x + 0.5, y + 0.5)) { x++; continue; }
                int x0 = x;
                while (x < 14 && sawSolid(x + 0.5, y + 0.5)) x++;
                L.add(new Box("disc", x0, y, 7.5, x, y + 1, 8.5));
            }
        }
        L.add(new Box("hub", 7, 7, 7, 9, 9, 9));
        return L;
    }

    static char sawPaint(Box b, String d, double x, double y, double z, int i, int j) {
        double dx = x - 8, dy = y - 8;
        double r = Math.sqrt(dx * dx + dy * dy);
        boolean flat = d.equals("north") || d.equals("south");
        if (b.tag.equals("hub")) {
            if (flat) {
                int cx = (int) Math.floor(x - 7), cy = (int) Math.floor(9 - y);
                return cx == 0 && cy == 0 ? 'g' : (cx == 1 && cy == 1 ? 'c' : 'e');
            }
            return d.equals("up") ? '4' : '2';
        }
        if (!flat) {
            // rim: tooth edges brighter than the body's
            return r > SAW_BODY + 0.4 ? (d.equals("up") || d.equals("west") ? 'f' : 'd') : (d.equals("up") ? 'd' : 'c');
        }
        // light from the upper left on the south face, mirrored on the back
        double lx = d.equals("south") ? -dx : dx;
        double lit = lx * 0.6 + dy * 0.8;
        double ang = Math.atan2(dy, dx);
        double f = ((ang / (2 * Math.PI) * SAW_TEETH) % 1 + 1) % 1;
        if (r > SAW_BODY + 0.2) {                                                      // teeth, hooked tip catches light
            if (f < 0.18 && lit > -2) return 'h';
            return r > SAW_BODY + 1.0 ? (lit > 0 ? 'g' : 'f') : (lit > 0 ? 'f' : 'e');
        }
        if (r > SAW_BODY - 0.8) return lit > 1.5 ? 'g' : (lit > -1.5 ? 'e' : 'd'); // sharpened ring
        if (r > 1.6) {
            // three curved vent slots cut into the dark body
            double sw = ((ang + r * 0.55) / (2 * Math.PI) * 3 % 1 + 1) % 1;
            if (r > 2.0 && r < 3.3 && sw < 0.16) return 'a';
            return lit > 1.8 ? 'd' : (lit > -1 ? 'c' : 'b');
        }
        return '2';                                                                    // dark navy core
    }

    static List<Box> SAW;
    static final int SAW_ATLAS = 32;

    static void saw() throws IOException {
        SAW = sawBoxes();
        G tex = atlas(SAW, GenBloodStyle::sawPaint, SAW_ATLAS);
        writeStatic("item/bloodsaw.png", tex);
        writeAsset("models/item/bloodsaw.json", modelJson(SAW, "hha_blood:item/bloodsaw", "hha_blood:item/bloodsaw", SAW_ATLAS, 0, null));
        writeAsset("items/bloodsaw.json", """
            {
              "model": {
                "type": "minecraft:model",
                "model": "hha_blood:item/bloodsaw"
              }
            }
            """);
    }

    // ================================================================== PREVIEW RENDERER
    /** Tiny ray-cast renderer for textured quads (boxes) - only for preview sheets. */
    static final class Preview {
        // ---- 4x4 matrices, row-major, column vectors
        static double[] id() { return new double[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1}; }
        static double[] mul(double[] a, double[] b) {
            double[] r = new double[16];
            for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
                double s = 0;
                for (int k = 0; k < 4; k++) s += a[i * 4 + k] * b[k * 4 + j];
                r[i * 4 + j] = s;
            }
            return r;
        }
        static double[] mul(double[]... ms) { double[] r = id(); for (double[] m : ms) r = mul(r, m); return r; }
        static double[] T(double x, double y, double z) { return new double[]{1,0,0,x, 0,1,0,y, 0,0,1,z, 0,0,0,1}; }
        static double[] S(double x, double y, double z) { return new double[]{x,0,0,0, 0,y,0,0, 0,0,z,0, 0,0,0,1}; }
        static double[] S(double s) { return S(s, s, s); }
        static double[] Rx(double deg) { double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a); return new double[]{1,0,0,0, 0,c,-s,0, 0,s,c,0, 0,0,0,1}; }
        static double[] Ry(double deg) { double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a); return new double[]{c,0,s,0, 0,1,0,0, -s,0,c,0, 0,0,0,1}; }
        static double[] Rz(double deg) { double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a); return new double[]{c,-s,0,0, s,c,0,0, 0,0,1,0, 0,0,0,1}; }
        static double[] pt(double[] m, double[] p) {
            return new double[]{m[0]*p[0]+m[1]*p[1]+m[2]*p[2]+m[3], m[4]*p[0]+m[5]*p[1]+m[6]*p[2]+m[7], m[8]*p[0]+m[9]*p[1]+m[10]*p[2]+m[11]};
        }
        static double[] dir(double[] m, double[] p) {
            return new double[]{m[0]*p[0]+m[1]*p[1]+m[2]*p[2], m[4]*p[0]+m[5]*p[1]+m[6]*p[2], m[8]*p[0]+m[9]*p[1]+m[10]*p[2]};
        }
        static double[] sub(double[] a, double[] b) { return new double[]{a[0]-b[0], a[1]-b[1], a[2]-b[2]}; }
        static double[] add(double[] a, double[] b) { return new double[]{a[0]+b[0], a[1]+b[1], a[2]+b[2]}; }
        static double[] cross(double[] a, double[] b) { return new double[]{a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]}; }
        static double dot(double[] a, double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
        static double[] norm(double[] a) { double l = Math.sqrt(dot(a, a)); return new double[]{a[0]/l, a[1]/l, a[2]/l}; }

        /** Quad: corner O maps to texel (u0,v0); O+E1 to (u1,v0); O+E2 to (u0,v1). n = outward normal. */
        static final class Quad {
            double[] o, e1, e2, n;
            BufferedImage tex; double u0, v0, u1, v1;
            Quad(double[] o, double[] e1, double[] e2, double[] n, BufferedImage tex, double u0, double v0, double u1, double v1) {
                this.o = o; this.e1 = e1; this.e2 = e2; this.n = n; this.tex = tex; this.u0 = u0; this.v0 = v0; this.u1 = u1; this.v1 = v1;
            }
            Quad tf(double[] m) {
                double[] a = pt(m, o), b = pt(m, add(o, e1)), c = pt(m, add(o, e2));
                double[] nn = norm(dir(m, n));
                // a mirroring matrix flips the winding; the outward normal transforms like a direction for
                // orthogonal (+ uniformly scaled) matrices, so it stays correct
                return new Quad(a, sub(b, a), sub(c, a), nn, tex, u0, v0, u1, v1);
            }
        }

        static List<Quad> tf(List<Quad> qs, double[] m) { List<Quad> r = new ArrayList<>(); for (Quad q : qs) r.add(q.tf(m)); return r; }

        /** Quads of a block-model element list (model units, minecraft uv orientation). */
        static List<Quad> blockModel(List<Box> boxes, BufferedImage atlas, double rotZ) {
            List<Quad> r = new ArrayList<>();
            double[] rot = mul(T(8, 8, 8), Rz(rotZ), T(-8, -8, -8));
            for (Box b : boxes) for (String d : DIRS) {
                double[] uv = b.uv.get(d);
                if (uv == null) continue;
                double w = b.x2 - b.x1, h = b.y2 - b.y1, dd = b.z2 - b.z1;
                Quad q = switch (d) {
                    case "south" -> new Quad(new double[]{b.x1, b.y2, b.z2}, new double[]{w, 0, 0}, new double[]{0, -h, 0}, new double[]{0, 0, 1}, atlas, uv[0], uv[1], uv[2], uv[3]);
                    case "north" -> new Quad(new double[]{b.x2, b.y2, b.z1}, new double[]{-w, 0, 0}, new double[]{0, -h, 0}, new double[]{0, 0, -1}, atlas, uv[0], uv[1], uv[2], uv[3]);
                    case "west" -> new Quad(new double[]{b.x1, b.y2, b.z1}, new double[]{0, 0, dd}, new double[]{0, -h, 0}, new double[]{-1, 0, 0}, atlas, uv[0], uv[1], uv[2], uv[3]);
                    case "east" -> new Quad(new double[]{b.x2, b.y2, b.z2}, new double[]{0, 0, -dd}, new double[]{0, -h, 0}, new double[]{1, 0, 0}, atlas, uv[0], uv[1], uv[2], uv[3]);
                    case "up" -> new Quad(new double[]{b.x1, b.y2, b.z1}, new double[]{w, 0, 0}, new double[]{0, 0, dd}, new double[]{0, 1, 0}, atlas, uv[0], uv[1], uv[2], uv[3]);
                    default -> new Quad(new double[]{b.x1, b.y1, b.z2}, new double[]{w, 0, 0}, new double[]{0, 0, -dd}, new double[]{0, -1, 0}, atlas, uv[0], uv[1], uv[2], uv[3]);
                };
                r.add(rotZ == 0 ? q : q.tf(rot));
            }
            return r;
        }

        /** Extruded sprite like builtin/generated: 16 units wide, z 7.5..8.5. */
        static List<Quad> sprite(BufferedImage im) {
            int N = im.getWidth();
            double p = 16.0 / N;
            List<Quad> r = new ArrayList<>();
            for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
                if ((im.getRGB(x, y) >>> 24) == 0) continue;
                double x1 = x * p, x2 = x1 + p, y2 = 16 - y * p, y1 = y2 - p;
                double z1 = 7.5, z2 = 8.5;
                r.add(new Quad(new double[]{x1, y2, z2}, new double[]{p, 0, 0}, new double[]{0, -p, 0}, new double[]{0, 0, 1}, im, x, y, x + 1, y + 1));
                r.add(new Quad(new double[]{x2, y2, z1}, new double[]{-p, 0, 0}, new double[]{0, -p, 0}, new double[]{0, 0, -1}, im, x + 1, y, x, y + 1));
                boolean l = x > 0 && (im.getRGB(x - 1, y) >>> 24) != 0, rr = x < N - 1 && (im.getRGB(x + 1, y) >>> 24) != 0;
                boolean u = y > 0 && (im.getRGB(x, y - 1) >>> 24) != 0, dn = y < N - 1 && (im.getRGB(x, y + 1) >>> 24) != 0;
                if (!l) r.add(new Quad(new double[]{x1, y2, z1}, new double[]{0, 0, 1}, new double[]{0, -p, 0}, new double[]{-1, 0, 0}, im, x, y, x + 1, y + 1));
                if (!rr) r.add(new Quad(new double[]{x2, y2, z2}, new double[]{0, 0, -1}, new double[]{0, -p, 0}, new double[]{1, 0, 0}, im, x, y, x + 1, y + 1));
                if (!u) r.add(new Quad(new double[]{x1, y2, z1}, new double[]{p, 0, 0}, new double[]{0, 0, 1}, new double[]{0, 1, 0}, im, x, y, x + 1, y + 1));
                if (!dn) r.add(new Quad(new double[]{x1, y1, z2}, new double[]{p, 0, 0}, new double[]{0, 0, -1}, new double[]{0, -1, 0}, im, x, y, x + 1, y + 1));
            }
            return r;
        }

        /**
         * Entity cuboid in preview space (y up, entity faces +z, entity's right = -x) with minecraft box-uv
         * at (u,v). x1..: the undilated box; dil grows it like Dilation. mirror = vanilla "mirrored()" limb.
         */
        static List<Quad> cuboid(BufferedImage tex, int u, int v, double x1, double y1, double z1, double w, double h, double d, double dil, boolean mirror) {
            double X1 = x1 - dil, Y1 = y1 - dil, Z1 = z1 - dil, X2 = x1 + w + dil, Y2 = y1 + h + dil, Z2 = z1 + d + dil;
            double W = X2 - X1, H = Y2 - Y1, D = Z2 - Z1;
            List<Quad> r = new ArrayList<>();
            // front (+z): region (u+d, v+d) w x h
            r.add(new Quad(new double[]{X1, Y2, Z2}, new double[]{W, 0, 0}, new double[]{0, -H, 0}, new double[]{0, 0, 1}, tex, u + d, v + d, u + d + w, v + d + h));
            // back (-z): region (u+2d+w, v+d)
            r.add(new Quad(new double[]{X2, Y2, Z1}, new double[]{-W, 0, 0}, new double[]{0, -H, 0}, new double[]{0, 0, -1}, tex, u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h));
            // entity's right side (-x): region (u, v+d), u grows towards the front
            r.add(new Quad(new double[]{X1, Y2, Z1}, new double[]{0, 0, D}, new double[]{0, -H, 0}, new double[]{-1, 0, 0}, tex, u, v + d, u + d, v + d + h));
            // entity's left side (+x): region (u+d+w, v+d), u grows towards the back
            r.add(new Quad(new double[]{X2, Y2, Z2}, new double[]{0, 0, -D}, new double[]{0, -H, 0}, new double[]{1, 0, 0}, tex, u + d + w, v + d, u + 2 * d + w, v + d + h));
            // top: region (u+d, v), last row touches the front
            r.add(new Quad(new double[]{X1, Y2, Z1}, new double[]{W, 0, 0}, new double[]{0, 0, D}, new double[]{0, 1, 0}, tex, u + d, v, u + d + w, v + d));
            // bottom: region (u+d+w, v)
            r.add(new Quad(new double[]{X1, Y1, Z2}, new double[]{W, 0, 0}, new double[]{0, 0, -D}, new double[]{0, -1, 0}, tex, u + d + w, v, u + d + 2 * w, v + d));
            if (mirror) {
                double cx = x1 + w / 2;
                return tf(r, mul(T(cx, 0, 0), S(-1, 1, 1), T(-cx, 0, 0)));
            }
            return r;
        }

        // ---- rendering
        static final double[] LIGHT = norm(new double[]{-0.45, 0.8, 0.55});

        static final class Img {
            final BufferedImage im; final double[] z; final int w, h;
            Img(int w, int h, int bg) {
                this.w = w; this.h = h;
                im = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                z = new double[w * h];
                java.util.Arrays.fill(z, Double.MAX_VALUE);
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) im.setRGB(x, y, bg);
            }
            void resetDepth() { java.util.Arrays.fill(z, Double.MAX_VALUE); }
        }

        /** Orthographic: screen x = cx + X*s, screen y = cy - Y*s, viewer at +z. view = rotation applied first. */
        static void ortho(Img img, List<Quad> qs, double[] view, double cx, double cy, double s, boolean lit) {
            for (Quad q0 : qs) {
                Quad q = q0.tf(view);
                if (q.n[2] <= 1e-6) continue;
                double shade = lit ? shade(q0.n) : 1;
                raster(img, q, shade, (px, py) -> new double[][]{{(px - cx) / s, (cy - py) / s, 1e5}, {0, 0, -1}},
                    p -> new double[]{cx + p[0] * s, cy - p[1] * s});
            }
        }

        /** Perspective from the origin looking down -z (camera space), vertical fov in degrees. */
        static void persp(Img img, List<Quad> qs, double fov, boolean lit) {
            double f = (img.h / 2.0) / Math.tan(Math.toRadians(fov / 2));
            double cx = img.w / 2.0, cy = img.h / 2.0;
            for (Quad q : qs) {
                double[] c = add(q.o, add(q.e1, q.e2));
                double[] mid = new double[]{q.o[0] + (q.e1[0] + q.e2[0]) / 2, q.o[1] + (q.e1[1] + q.e2[1]) / 2, q.o[2] + (q.e1[2] + q.e2[2]) / 2};
                if (dot(q.n, mid) >= 0) continue; // facing away from the camera at the origin
                double shade = lit ? shade(q.n) : 1;
                raster(img, q, shade, (px, py) -> new double[][]{{0, 0, 0}, norm(new double[]{(px - cx) / f, (cy - py) / f, -1})},
                    p -> new double[]{cx + p[0] / -p[2] * f, cy - p[1] / -p[2] * f});
            }
        }

        static double shade(double[] n) { return 0.5 + 0.5 * Math.max(0, dot(n, LIGHT)) + 0.08 * Math.max(0, n[1]); }

        interface Ray { double[][] at(double px, double py); }
        interface Proj { double[] at(double[] p); }

        static void raster(Img img, Quad q, double shade, Ray ray, Proj proj) {
            double[][] cs = {q.o, add(q.o, q.e1), add(q.o, q.e2), add(q.o, add(q.e1, q.e2))};
            double minx = 1e9, miny = 1e9, maxx = -1e9, maxy = -1e9;
            for (double[] c : cs) {
                double[] p = proj.at(c);
                minx = Math.min(minx, p[0]); maxx = Math.max(maxx, p[0]);
                miny = Math.min(miny, p[1]); maxy = Math.max(maxy, p[1]);
            }
            int x0 = Math.max(0, (int) Math.floor(minx) - 1), x1 = Math.min(img.w - 1, (int) Math.ceil(maxx) + 1);
            int y0 = Math.max(0, (int) Math.floor(miny) - 1), y1 = Math.min(img.h - 1, (int) Math.ceil(maxy) + 1);
            for (int py = y0; py <= y1; py++) for (int px = x0; px <= x1; px++) {
                double[][] r = ray.at(px + 0.5, py + 0.5);
                double[] ro = r[0], rd = r[1];
                // o + s e1 + t e2 = ro + l rd  ->  s e1 + t e2 - l rd = ro - o
                double[] b = sub(ro, q.o);
                double[] m1 = q.e1, m2 = q.e2, m3 = new double[]{-rd[0], -rd[1], -rd[2]};
                double det = dot(m1, cross(m2, m3));
                if (Math.abs(det) < 1e-12) continue;
                double s = dot(b, cross(m2, m3)) / det;
                double t = dot(m1, cross(b, m3)) / det;
                double l = dot(m1, cross(m2, b)) / det;
                if (s < 0 || s >= 1 || t < 0 || t >= 1 || l <= 0) continue;
                double tu = q.u0 + s * (q.u1 - q.u0), tv = q.v0 + t * (q.v1 - q.v0);
                int ix = (int) Math.floor(tu), iy = (int) Math.floor(tv);
                if (ix < 0 || iy < 0 || ix >= q.tex.getWidth() || iy >= q.tex.getHeight()) continue;
                int c = q.tex.getRGB(ix, iy);
                int a = c >>> 24;
                if (a < 8) continue;
                int idx = py * img.w + px;
                if (l >= img.z[idx]) continue;
                img.z[idx] = l;
                int rr = (int) Math.min(255, ((c >> 16) & 255) * shade), gg = (int) Math.min(255, ((c >> 8) & 255) * shade), bb = (int) Math.min(255, (c & 255) * shade);
                int bg = img.im.getRGB(px, py);
                int col = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
                img.im.setRGB(px, py, a == 255 ? col : blend(bg, (a << 24) | (col & 0xFFFFFF)));
            }
        }

        static int blend(int bg, int p) {
            int a = p >>> 24;
            int r = (((p >> 16) & 255) * a + ((bg >> 16) & 255) * (255 - a)) / 255;
            int gg = (((p >> 8) & 255) * a + ((bg >> 8) & 255) * (255 - a)) / 255;
            int b = ((p & 255) * a + (bg & 255) * (255 - a)) / 255;
            return 0xFF000000 | (r << 16) | (gg << 8) | b;
        }

        // ---- sheet helpers
        static final class Sheet {
            final BufferedImage im;
            final java.awt.Graphics2D g;
            Sheet(int w, int h, int bg) {
                im = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                g = im.createGraphics();
                g.setColor(new java.awt.Color(bg, true));
                g.fillRect(0, 0, w, h);
                g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 13));
            }
            void put(BufferedImage src, int x, int y) { g.drawImage(src, x, y, null); }
            void put(Img src, int x, int y) { put(src.im, x, y); }
            /** nearest-neighbour upscale of a region */
            void scaled(BufferedImage src, int sx, int sy, int sw, int sh, int dx, int dy, int s) {
                for (int y = 0; y < sh * s; y++) for (int x = 0; x < sw * s; x++) {
                    int p = src.getRGB(sx + x / s, sy + y / s);
                    if ((p >>> 24) == 0) continue;
                    im.setRGB(dx + x, dy + y, blend(im.getRGB(dx + x, dy + y), p));
                }
            }
            void checker(int x0, int y0, int w, int h, int cell) {
                for (int y = y0; y < y0 + h; y++) for (int x = x0; x < x0 + w; x++)
                    im.setRGB(x, y, ((((x - x0) / cell) + ((y - y0) / cell)) & 1) == 0 ? 0xFF8B8B93 : 0xFF7A7A82);
            }
            void fill(int x, int y, int w, int h, int col) { g.setColor(new java.awt.Color(col, true)); g.fillRect(x, y, w, h); }
            void text(String s, int x, int y) { g.setColor(java.awt.Color.WHITE); g.drawString(s, x, y); }
            void save(String name) throws IOException {
                Files.createDirectories(PREVIEW);
                ImageIO.write(im, "png", PREVIEW.resolve(name).toFile());
                System.out.println("preview " + PREVIEW.resolve(name));
            }
        }

        /** Big nearest-neighbour zoom with a pixel grid and coordinates every 4 px. */
        static void zoom(BufferedImage src, int z, String name) throws IOException {
            int w = src.getWidth(), h = src.getHeight();
            Sheet s = new Sheet(w * z + 30, h * z + 30, 0xFF202024);
            s.checker(24, 24, w * z, h * z, z);
            s.scaled(src, 0, 0, w, h, 24, 24, z);
            for (int i = 0; i <= w; i++) s.fill(24 + i * z, 24, 1, h * z, 0x40000000);
            for (int i = 0; i <= h; i++) s.fill(24, 24 + i * z, w * z, 1, 0x40000000);
            s.g.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 10));
            for (int i = 0; i < w; i += 4) s.text("" + i, 24 + i * z + 2, 14);
            for (int i = 0; i < h; i += 4) s.text("" + i, 2, 24 + i * z + 12);
            s.save(name);
        }

        static BufferedImage frame(BufferedImage strip, int i) {
            int n = strip.getWidth();
            return strip.getSubimage(0, i * n, n, n);
        }

        // ---- player
        static BufferedImage steve;

        /** Steve in preview space, feet at y=0; right arm optionally posed holding an item (pitch -18). */
        static List<Quad> player(BufferedImage skin, boolean holding) {
            List<Quad> r = new ArrayList<>();
            if (skin == null) return r;
            r.addAll(cuboid(skin, 0, 0, -4, 24, -4, 8, 8, 8, 0, false));
            r.addAll(cuboid(skin, 16, 16, -4, 12, -2, 8, 12, 4, 0, false));
            r.addAll(cuboid(skin, 0, 16, -3.9, 0, -2, 4, 12, 4, 0, false));
            r.addAll(cuboid(skin, 16, 48, -0.1, 0, -2, 4, 12, 4, 0, false));
            r.addAll(cuboid(skin, 32, 48, 4, 12, -2, 4, 12, 4, 0, false));
            List<Quad> arm = cuboid(skin, 40, 16, -8, 12, -2, 4, 12, 4, 0, false);
            if (holding) arm = tf(arm, armPose());
            r.addAll(arm);
            return r;
        }

        static final double[] F = S(1, -1, -1); // model space (y down, face -z) <-> preview space

        /** Pose of the right arm (preview space): pivot at model (-5, 2, 0), pitch -18 deg (ArmPose.ITEM). */
        static double[] armPose() {
            // preview-space pivot: model (-5, 2, 0) -> body top is model y 0 = preview y 24
            double[] toModel = mul(T(0, 24, 0), F);              // model px -> preview px
            double[] fromModel = mul(F, T(0, -24, 0));
            return mul(toModel, T(-5, 2, 0), Rx(-18), T(5, -2, 0), fromModel);
        }

        /** Item-model coords -> preview space (pixels) for a third-person right-hand item with the given display transform. */
        static double[] heldItem(double[] rot, double[] tr, double sc) {
            double[] toModel = mul(T(0, 24, 0), F);
            double[] chain = mul(
                T(-5, 2, 0), Rx(-18),                                  // ModelPart.applyTransform (right arm), in px
                Rx(-90), Ry(180), T(1, 2, -10),                        // HeldItemFeatureRenderer: translate(1/16, 0.125, -0.625) in px
                T(tr[0], tr[1], tr[2]), Rx(rot[0]), Ry(rot[1]), Rz(rot[2]), S(sc), // display transform
                T(-8, -8, -8));                                        // centre the 16px model
            return mul(toModel, chain);
        }

        /** First-person right hand (camera space, px units): equip offset (0.56, -0.52, -0.72) then the display transform. */
        static double[] firstPerson(double[] rot, double[] tr, double sc) {
            return mul(T(0.56 * 16, -0.52 * 16, -0.72 * 16), T(tr[0], tr[1], tr[2]), Rx(rot[0]), Ry(rot[1]), Rz(rot[2]), S(sc), T(-8, -8, -8));
        }

        /** Armour boxes in preview space. pieces: bit 0 helmet, 1 chest, 2 legs, 3 boots. */
        static List<Quad> armour(BufferedImage hum, BufferedImage leg, int pieces) {
            List<Quad> r = new ArrayList<>();
            if ((pieces & 1) != 0) {
                r.addAll(cuboid(hum, 0, 0, -4, 24, -4, 8, 8, 8, 1.0, false));
                r.addAll(cuboid(hum, 32, 0, -4, 24, -4, 8, 8, 8, 1.5, false));
            }
            if ((pieces & 2) != 0) {
                r.addAll(cuboid(hum, 16, 16, -4, 12, -2, 8, 12, 4, 1.0, false));
                r.addAll(cuboid(hum, 40, 16, -8, 12, -2, 4, 12, 4, 1.0, false));
                r.addAll(cuboid(hum, 40, 16, 4, 12, -2, 4, 12, 4, 1.0, true));
            }
            if ((pieces & 4) != 0) {
                r.addAll(cuboid(leg, 16, 16, -4, 12, -2, 8, 12, 4, 0.5, false));
                r.addAll(cuboid(leg, 0, 16, -3.9, 0, -2, 4, 12, 4, 0.4, false));
                r.addAll(cuboid(leg, 0, 16, -0.1, 0, -2, 4, 12, 4, 0.4, true));
            }
            if ((pieces & 8) != 0) {
                r.addAll(cuboid(hum, 0, 16, -3.9, 0, -2, 4, 12, 4, 0.9, false));
                r.addAll(cuboid(hum, 0, 16, -0.1, 0, -2, 4, 12, 4, 0.9, true));
            }
            return r;
        }

        static void armourSheet(Path stevePath) throws IOException {
            if (steve == null && stevePath != null && Files.exists(stevePath)) steve = ImageIO.read(stevePath.toFile());
            BufferedImage hum = ImageIO.read(TEX.resolve("entity/equipment/humanoid/blood.png").toFile());
            BufferedImage leg = ImageIO.read(TEX.resolve("entity/equipment/humanoid_leggings/blood.png").toFile());
            Sheet s = new Sheet(1300, 1180, 0xFF202024);
            s.text("humanoid/blood.png (8x)", 10, 18);
            s.checker(10, 24, 512, 256, 8);
            s.scaled(hum, 0, 0, 64, 32, 10, 24, 8);
            s.text("humanoid_leggings/blood.png (8x)", 540, 18);
            s.checker(540, 24, 512, 256, 8);
            s.scaled(leg, 0, 0, 64, 32, 540, 24, 8);
            String[] names = {"front", "back", "right side", "left side", "3/4 front-right", "3/4 back-left"};
            double[][] views = {id(), Ry(180), Ry(90), Ry(-90), mul(Rx(12), Ry(35)), mul(Rx(12), Ry(215))};
            List<Quad> full = new ArrayList<>(player(steve, false));
            full.addAll(armour(hum, leg, 15));
            for (int i = 0; i < views.length; i++) {
                Img img = new Img(205, 370, 0xFF5A6E8C);
                ortho(img, full, mul(views[i], T(0, -17, 0)), 102, 190, 9, true);
                s.put(img, 10 + i * 213, 310);
                s.text(names[i], 10 + i * 213, 304);
            }
            String[] pn = {"helmet", "chestplate", "leggings", "boots", "steve (no armour)", "helmet 3/4"};
            int[] pm = {1, 2, 4, 8, 0, 1};
            for (int i = 0; i < pn.length; i++) {
                Img img = new Img(205, 370, 0xFF5A6E8C);
                List<Quad> sc = new ArrayList<>(player(steve, false));
                sc.addAll(armour(hum, leg, pm[i]));
                double[] v = i == 5 ? mul(Rx(25), Ry(40)) : (i == 1 ? mul(Rx(8), Ry(25)) : id());
                if (i == 5) ortho(img, sc, mul(v, T(0, -28, 0)), 102, 185, 18, true);
                else ortho(img, sc, mul(v, T(0, -17, 0)), 102, 190, 9, true);
                s.put(img, 10 + i * 213, 710);
                s.text(pn[i], 10 + i * 213, 704);
            }
            s.save("armour.png");
        }

        static void itemsAndSaw(Path vanillaDir) throws IOException {
            String[] names = {"blood_helmet", "blood_chestplate", "blood_leggings", "blood_boots", "blood_sword", "blood_mace", "blood_ingot"};
            Sheet s = new Sheet(1300, 1000, 0xFF202024);
            s.text("armour icons, frame 0 at 8x (+ every frame at 2x below)", 10, 18);
            for (int i = 0; i < 4; i++) {
                BufferedImage im = ImageIO.read(TEX.resolve("item/" + names[i] + ".png").toFile());
                int x = 10 + i * 140;
                s.checker(x, 24, 128, 128, 8);
                s.scaled(frame(im, 0), 0, 0, 16, 16, x, 24, 8);
                int n = im.getHeight() / 16;
                for (int f = 0; f < n; f++) {
                    int fx = x + (f % 4) * 34, fy = 160 + (f / 4) * 34;
                    s.fill(fx, fy, 32, 32, 0xFF8B8B93);
                    s.scaled(frame(im, f), 0, 0, 16, 16, fx, fy, 2);
                }
            }
            // hotbar at gui scale 2 and 3, all blood items incl. the untouched mace and ingot
            s.text("hotbar look, gui scale 2 and 3 (mace + ingot unchanged)", 600, 18);
            for (int gs = 2; gs <= 3; gs++) {
                int y = gs == 2 ? 30 : 90;
                for (int i = 0; i < names.length; i++) {
                    BufferedImage im = frame(ImageIO.read(TEX.resolve("item/" + names[i] + ".png").toFile()), 0);
                    int x = 600 + i * (20 * gs + 2);
                    s.fill(x, y, 20 * gs, 20 * gs, 0xFF8B8B8B);
                    BufferedImage sc = new BufferedImage(16 * gs, 16 * gs, BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D g2 = sc.createGraphics();
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g2.drawImage(im, 0, 0, 16 * gs, 16 * gs, null);
                    g2.dispose();
                    s.put(sc, x + 2 * gs, y + 2 * gs);
                }
            }
            // vanilla netherite armour icons for size comparison
            if (vanillaDir != null) {
                String[] vn = {"netherite_helmet", "netherite_chestplate"};
                for (int i = 0; i < vn.length; i++) {
                    Path p = vanillaDir.resolve("assets_minecraft_textures_item_" + vn[i] + ".png");
                    if (!Files.exists(p)) continue;
                    BufferedImage im = ImageIO.read(p.toFile());
                    int x = 600 + i * 62;
                    s.fill(x, 170, 60, 60, 0xFF8B8B8B);
                    s.scaled(im, 0, 0, 16, 16, x + 6, 176, 3);
                }
                s.text("vanilla netherite (3x)", 740, 205);
            }
            // --- saw
            BufferedImage sawIm = ImageIO.read(TEX.resolve("item/bloodsaw.png").toFile());
            List<Quad> saw = blockModel(SAW, sawIm, 0);
            String[] vn = {"front (south)", "back (north)", "edge (east)", "edge (up)", "3/4", "spin 30deg + tilt"};
            double[][] views = {id(), Ry(180), Ry(-90), Rx(90), mul(Rx(20), Ry(-40)), mul(Rx(-25), Ry(55), Rz(30))};
            for (int i = 0; i < views.length; i++) {
                Img img = new Img(205, 205, 0xFF2C2C34);
                ortho(img, saw, mul(views[i], T(-8, -8, -8)), 102, 102, 13, true);
                s.put(img, 10 + i * 213, 430);
                s.text(vn[i], 10 + i * 213, 424);
            }
            // spinning about the centre (what the code does): z rotations
            s.text("bloodsaw rotated about z around (8,8,8) - should stay centred", 10, 660);
            for (int i = 0; i < 6; i++) {
                Img img = new Img(120, 120, 0xFF6F8F5F);
                ortho(img, saw, mul(Rz(i * 15), T(-8, -8, -8)), 60, 60, 7, true);
                img.im.setRGB(60, 60, 0xFFFFFFFF);
                s.put(img, 10 + i * 128, 666);
            }
            s.text("bloodsaw.png atlas (32x32 at 6x)", 800, 660);
            s.checker(800, 666, 192, 192, 12);
            s.scaled(sawIm, 0, 0, 32, 32, 800, 666, 6);
            s.save("items_and_saw.png");
        }

        static void sword(Path vanillaSword, Path stevePath) throws IOException {
            if (stevePath != null && Files.exists(stevePath)) steve = ImageIO.read(stevePath.toFile());
            BufferedImage icon = ImageIO.read(TEX.resolve("item/blood_sword.png").toFile());
            BufferedImage atlasIm = ImageIO.read(TEX.resolve("item/blood_sword_3d.png").toFile());
            BufferedImage van = vanillaSword != null && Files.exists(vanillaSword) ? ImageIO.read(vanillaSword.toFile()) : null;
            int frames = icon.getHeight() / icon.getWidth();

            // --- sheet 1: icon frames + slot sizes
            Sheet s = new Sheet(1100, 1010, 0xFF202024);
            s.text("blood_sword.png (32x32, " + frames + " frames)  - frame 0 at 8x", 10, 18);
            s.checker(10, 26, 256, 256, 16);
            s.scaled(frame(icon, 0), 0, 0, 32, 32, 10, 26, 8);
            int fx = 280;
            for (int i = 0; i < frames; i++) {
                int x = fx + (i % 12) * 68, y = 26 + (i / 12) * 68;
                s.fill(x, y, 64, 64, 0xFF8B8B93);
                s.scaled(frame(icon, i), 0, 0, 32, 32, x, y, 2);
            }
            s.text("inventory slot, gui scale 2 (vanilla netherite 16px / new 32px) and gui scale 1 / 3", 10, 310);
            int[] scales = {2, 1, 3};
            int x = 10;
            for (int gs : scales) {
                for (int k = 0; k < 2; k++) {
                    s.fill(x, 320, 18 * gs, 18 * gs, 0xFF8B8B8B);
                    BufferedImage im = k == 0 ? van : frame(icon, 0);
                    if (im != null) {
                        // vanilla draws the sprite into 16 gui px; a 32px sprite is downsampled 2:1 at gui scale 1
                        int target = 16 * gs;
                        BufferedImage sc = new BufferedImage(target, target, BufferedImage.TYPE_INT_ARGB);
                        java.awt.Graphics2D g2 = sc.createGraphics();
                        g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                        g2.drawImage(im, 0, 0, target, target, null);
                        g2.dispose();
                        s.put(sc, x + gs, 320 + gs);
                    }
                    x += 18 * gs + 6;
                }
                x += 20;
            }
            // --- 3D orthographic views of the upright model (no element rotation) + atlas
            List<Quad> up = blockModel(SWORD, atlasIm, 0);
            int vy = 420;
            String[] names = {"front (south)", "back (north)", "side (east)", "3/4 front", "3/4 back", "top"};
            double[][] views = {id(), Ry(180), Ry(-90), mul(Rx(15), Ry(-35)), mul(Rx(15), Ry(145)), Rx(90)};
            for (int i = 0; i < views.length; i++) {
                Img img = new Img(170, 330, 0xFF2C2C34);
                double[] v = mul(views[i], T(-8, -9, -8));
                ortho(img, up, v, 85, i == 5 ? 165 : 150, 8.5, true);
                s.put(img, 10 + i * 178, vy);
                s.text(names[i], 10 + i * 178, vy - 4);
            }
            s.text("atlas 64x64 at 4x", 10, 785);
            s.checker(10, 790, 256, 256 - 50, 16);
            s.scaled(atlasIm, 0, 0, 64, 52, 10, 790, 4);
            s.save("sword_icon_and_model.png");
            zoom(frame(icon, 0), 20, "sword_icon_zoom.png");

            // --- sheet 2: in hand (third person, first person), vanilla vs new, same camera
            List<Quad> mine = blockModel(SWORD, atlasIm, SWORD_ROT);
            List<Quad> vq = van != null ? sprite(van) : new ArrayList<>();
            Sheet h = new Sheet(1240, 900, 0xFF202024);
            double[] tpRot = {0, -90, 25}, tpTr = {0, 4, 0.5};
            double[][] cams = {mul(Rx(8), Ry(40)), mul(Rx(5), Ry(90)), mul(Rx(10), Ry(-30))};
            String[] camNames = {"3rd person, front-right", "3rd person, right side", "3rd person, front-left"};
            for (int c = 0; c < cams.length; c++) {
                for (int k = 0; k < 2; k++) {
                    Img img = new Img(200, 300, 0xFF5A6E8C);
                    List<Quad> scene = new ArrayList<>(player(steve, true));
                    List<Quad> item = k == 0 ? vq : mine;
                    double sc = k == 0 ? 0.85 : SWORD_SCALE_TP;
                    scene.addAll(tf(item, heldItem(tpRot, tpTr, sc)));
                    ortho(img, scene, mul(cams[c], T(0, -16, 0)), 100, 160, 6, true);
                    int ox = 10 + c * 410 + k * 205;
                    h.put(img, ox, 30);
                    h.text((k == 0 ? "vanilla  " : "blood  ") + camNames[c], ox, 22);
                }
            }
            // first person
            double[] fpRot = {0, -90, 25}, fpTr = {1.13, 3.2, 1.13};
            for (int k = 0; k < 2; k++) {
                Img img = new Img(560, 315, 0xFF7FA6D8);
                List<Quad> item = k == 0 ? vq : mine;
                double sc = k == 0 ? 0.68 : SWORD_SCALE_FP;
                persp(img, tf(item, firstPerson(fpRot, fpTr, sc)), 70, true);
                h.put(img, 10 + k * 600, 370);
                h.text(k == 0 ? "vanilla netherite sword - first person (fov 70, 16:9)" : "blood sword 3D - first person", 10 + k * 600, 362);
            }
            // ground + dropped
            for (int k = 0; k < 2; k++) {
                Img img = new Img(200, 160, 0xFF6F8F5F);
                List<Quad> item = k == 0 ? vq : mine;
                double sc = k == 0 ? 0.5 : 0.42;
                ortho(img, tf(item, mul(T(0, 2, 0), S(sc), T(-8, -8, -8))), mul(Rx(20), Ry(-30)), 100, 80, 8, true);
                h.put(img, 10 + k * 210, 720);
                h.text(k == 0 ? "vanilla ground" : "blood ground", 10 + k * 210, 712);
            }
            h.save("sword_in_hand.png");
        }
    }

    // ================================================================== held-item preview of any model JSON
    /** Tiny JSON reader (objects, arrays, numbers, strings, booleans) - enough for item model files. */
    static final class MiniJson {
        final String s; int i = 0;
        MiniJson(String s) { this.s = s; }
        static Object parse(String s) { MiniJson p = new MiniJson(s); return p.value(); }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') { i++; Map<String, Object> m = new LinkedHashMap<>(); ws(); if (s.charAt(i) == '}') { i++; return m; }
                while (true) { ws(); String k = (String) value(); ws(); i++; m.put(k, value()); ws(); if (s.charAt(i++) == '}') return m; } }
            if (c == '[') { i++; List<Object> l = new ArrayList<>(); ws(); if (s.charAt(i) == ']') { i++; return l; }
                while (true) { l.add(value()); ws(); if (s.charAt(i++) == ']') return l; } }
            if (c == '"') { int j = s.indexOf('"', i + 1); String r = s.substring(i + 1, j); i = j + 1; return r; }
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            int j = i; while (j < s.length() && "+-0123456789.eE".indexOf(s.charAt(j)) >= 0) j++;
            double d = Double.parseDouble(s.substring(i, j)); i = j; return d;
        }
    }

    @SuppressWarnings("unchecked")
    static double[] nums(Object o) { List<Object> l = (List<Object>) o; double[] r = new double[l.size()]; for (int k = 0; k < r.length; k++) r[k] = (Double) l.get(k); return r; }

    /** Quads of a block-model JSON (elements, per-element rotation, face uv in 0..16 units). */
    @SuppressWarnings("unchecked")
    static List<Preview.Quad> jsonModel(Path json, BufferedImage tex) throws IOException {
        Map<String, Object> root = (Map<String, Object>) MiniJson.parse(Files.readString(json));
        double su = tex.getWidth() / 16.0, sv = tex.getHeight() / 16.0;
        List<Preview.Quad> out = new ArrayList<>();
        for (Object eo : (List<Object>) root.get("elements")) {
            Map<String, Object> e = (Map<String, Object>) eo;
            double[] f = nums(e.get("from")), t = nums(e.get("to"));
            double w = t[0] - f[0], h = t[1] - f[1], dd = t[2] - f[2];
            double[] rot = Preview.id();
            if (e.get("rotation") instanceof Map<?, ?> r) {
                double[] o = nums(r.get("origin")); double a = (Double) r.get("angle"); String ax = (String) r.get("axis");
                double[] R = ax.equals("x") ? Preview.Rx(a) : ax.equals("y") ? Preview.Ry(a) : Preview.Rz(a);
                rot = Preview.mul(Preview.T(o[0], o[1], o[2]), R, Preview.T(-o[0], -o[1], -o[2]));
            }
            Map<String, Object> faces = (Map<String, Object>) e.get("faces");
            for (Map.Entry<String, Object> fe : faces.entrySet()) {
                double[] uv = nums(((Map<String, Object>) fe.getValue()).get("uv"));
                double u0 = uv[0] * su, v0 = uv[1] * sv, u1 = uv[2] * su, v1 = uv[3] * sv;
                Preview.Quad q = switch (fe.getKey()) {
                    case "south" -> new Preview.Quad(new double[]{f[0], t[1], t[2]}, new double[]{w, 0, 0}, new double[]{0, -h, 0}, new double[]{0, 0, 1}, tex, u0, v0, u1, v1);
                    case "north" -> new Preview.Quad(new double[]{t[0], t[1], f[2]}, new double[]{-w, 0, 0}, new double[]{0, -h, 0}, new double[]{0, 0, -1}, tex, u0, v0, u1, v1);
                    case "west" -> new Preview.Quad(new double[]{f[0], t[1], f[2]}, new double[]{0, 0, dd}, new double[]{0, -h, 0}, new double[]{-1, 0, 0}, tex, u0, v0, u1, v1);
                    case "east" -> new Preview.Quad(new double[]{t[0], t[1], t[2]}, new double[]{0, 0, -dd}, new double[]{0, -h, 0}, new double[]{1, 0, 0}, tex, u0, v0, u1, v1);
                    case "up" -> new Preview.Quad(new double[]{f[0], t[1], f[2]}, new double[]{w, 0, 0}, new double[]{0, 0, dd}, new double[]{0, 1, 0}, tex, u0, v0, u1, v1);
                    default -> new Preview.Quad(new double[]{f[0], f[1], t[2]}, new double[]{w, 0, 0}, new double[]{0, 0, -dd}, new double[]{0, -1, 0}, tex, u0, v0, u1, v1);
                };
                out.add(q.tf(rot));
            }
        }
        return out;
    }

    /**
     * java GenBloodStyle.java --held-preview <outPng> <model.json> <modelTexture.png> <vanillaSprite.png> "<rx,ry,rz;tx,ty,tz;s>"...
     * Renders the vanilla sprite with vanilla handheld_mace first-person transform and the model with each
     * candidate transform, all in the same first-person camera (fov 70). Writes nothing else.
     */
    static void heldPreview(String[] a) throws Exception {
        BufferedImage tex = ImageIO.read(new File(a[3]));
        tex = tex.getSubimage(0, 0, tex.getWidth(), Math.min(tex.getHeight(), tex.getWidth()));
        List<Preview.Quad> model = jsonModel(Path.of(a[2]), tex);
        List<Preview.Quad> van = Preview.sprite(ImageIO.read(new File(a[4])));
        int n = a.length - 5 + 1;
        BufferedImage sheet = new BufferedImage(n * 410, 260, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = sheet.createGraphics();
        g.setColor(new java.awt.Color(32, 32, 36)); g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        for (int k = 0; k < n; k++) {
            Preview.Img img = new Preview.Img(400, 225, 0xFF7FA6D8);
            String label;
            if (k == 0) {
                Preview.persp(img, Preview.tf(van, Preview.firstPerson(new double[]{0, -90, 25}, new double[]{0, 3, 0.8}, 0.9)), 70, true);
                label = "vanilla mace";
            } else {
                String[] p = a[4 + k].split(";");
                double[] r = Arrays.stream(p[0].split(",")).mapToDouble(Double::parseDouble).toArray();
                double[] tr = Arrays.stream(p[1].split(",")).mapToDouble(Double::parseDouble).toArray();
                double s = Double.parseDouble(p[2]);
                Preview.persp(img, Preview.tf(model, Preview.firstPerson(r, tr, s)), 70, true);
                label = a[4 + k];
            }
            g.drawImage(img.im, k * 410 + 5, 30, null);
            g.setColor(java.awt.Color.WHITE); g.drawString(label, k * 410 + 8, 20);
        }
        g.dispose();
        ImageIO.write(sheet, "png", new File(a[1]));
    }

    /**
     * java GenBloodStyle.java --model-preview <outPng> <model.json> <texture.png> [rx]
     * Four orthographic views of a model JSON (first rotated by rx degrees around X, e.g. -90 to stand a
     * +Z-pointing model up). Writes nothing else.
     */
    static void modelPreview(String[] a) throws Exception {
        BufferedImage tex = ImageIO.read(new File(a[3]));
        tex = tex.getSubimage(0, 0, tex.getWidth(), Math.min(tex.getHeight(), tex.getWidth()));
        double rx = a.length > 4 ? Double.parseDouble(a[4]) : 0;
        List<Preview.Quad> model = Preview.tf(jsonModel(Path.of(a[2]), tex), Preview.mul(Preview.T(8, 8, 8), Preview.Rx(rx), Preview.T(-8, -8, -8)));
        double[][] views = {Preview.id(), Preview.Ry(90), Preview.mul(Preview.Rx(20), Preview.Ry(35)), Preview.mul(Preview.Rx(-35), Preview.Ry(-20))};
        String[] names = {"front", "side", "3/4 from above", "3/4 from below"};
        BufferedImage sheet = new BufferedImage(4 * 260, 420, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = sheet.createGraphics();
        g.setColor(new java.awt.Color(32, 32, 36)); g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        for (int k = 0; k < views.length; k++) {
            Preview.Img img = new Preview.Img(250, 390, 0xFF5A6E8C);
            Preview.ortho(img, model, Preview.mul(views[k], Preview.T(-8, -8, -8)), 125, 195, 18, true);
            g.drawImage(img.im, k * 260 + 5, 25, null);
            g.setColor(java.awt.Color.WHITE); g.drawString(names[k], k * 260 + 8, 18);
        }
        g.dispose();
        ImageIO.write(sheet, "png", new File(a[1]));
    }

    // ================================================================== main
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--held-preview")) { heldPreview(args); return; }
        if (args.length > 0 && args[0].equals("--model-preview")) { modelPreview(args); return; }
        ROOT = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        ASSETS = ROOT.resolve("blood/src/main/resources/assets/hha_blood");
        TEX = ASSETS.resolve("textures");
        PREVIEW = Path.of(args.length > 1 ? args[1] : ROOT.resolve("tools/textures/preview").toString());
        Path steve = args.length > 2 ? Path.of(args[2]) : null;
        Path vanillaSword = args.length > 3 ? Path.of(args[3]) : null;

        swordIcon();
        sword3d();
        armourLayers();
        armourIcons();
        saw();

        Preview.sword(vanillaSword, steve);
        Preview.armourSheet(steve);
        Preview.itemsAndSaw(vanillaSword != null ? vanillaSword.getParent() : null);

        for (String w : written) System.out.println("wrote " + w);
    }
}
