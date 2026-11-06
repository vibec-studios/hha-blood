import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import javax.imageio.ImageIO;

/**
 * Generates the ingot, HUD and particle textures of the Blood addon (hha_blood) from hand-placed
 * pixel grids and small procedural helpers. Reproducible: same input -> same PNGs.
 * Since v0.4 the armour (icons + layers), the sword (icon + 3D model) and the blood saw come from
 * GenBloodStyle.java - this file no longer writes them.
 *
 * Usage (from the project root):
 *   java tools/textures/GenTextures.java [projectRoot] [previewDir]
 *
 * Palette: one character per pixel.
 *   '.'  transparent
 *   K 1 2 3 4 5 6 7   black netherite, outline -> highlight
 *   a b c d e f g h   blood ramp: near-black red -> crimson -> hot highlight
 *   w x y z           translucent blood (particles only)
 */
public class GenTextures {

    // ------------------------------------------------------------------ palette
    static final Map<Character, Integer> PAL = new HashMap<>();
    static final String RAMP = "abcdefgh";
    static {
        PAL.put('.', 0x00000000);
        PAL.put('K', 0xFF0B0708);
        PAL.put('1', 0xFF171113);
        PAL.put('2', 0xFF221B1E);
        PAL.put('3', 0xFF2F272B);
        PAL.put('4', 0xFF3E3439);
        PAL.put('5', 0xFF53474D);
        PAL.put('6', 0xFF6C5F65);
        PAL.put('7', 0xFF8E7F85);
        PAL.put('a', 0xFF2A0306);
        PAL.put('b', 0xFF4A0509); // code "dark"
        PAL.put('c', 0xFF6E0910);
        PAL.put('d', 0xFF9B0A12); // code "blood"
        PAL.put('e', 0xFFBA121B);
        PAL.put('f', 0xFFD81E26); // code "bright"
        PAL.put('g', 0xFFF04A4C);
        PAL.put('h', 0xFFFF9A90);
        PAL.put('w', 0xB09B0A12);
        PAL.put('x', 0x904A0509);
        PAL.put('y', 0x606E0910);
        PAL.put('z', 0x702A0306);
    }

    static Path ROOT, TEX, PREVIEW;
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

        void rect(int x0, int y0, int rw, int rh, char ch) {
            for (int y = y0; y < y0 + rh; y++) for (int x = x0; x < x0 + rw; x++) set(x, y, ch);
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
    }

    static int ramp(char ch) { return RAMP.indexOf(ch); }

    /** Shifts a blood-ramp pixel by off steps (b..g range); metal and transparent pixels stay. */
    static char shift(char ch, int off) {
        int i = ramp(ch);
        if (i < 1) return ch;
        return RAMP.charAt(Math.max(1, Math.min(6, i + off)));
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
        written.add(rel);
    }

    static void writeStatic(String rel, G g) throws IOException {
        writePng(rel, g.img());
        Files.deleteIfExists(TEX.resolve(rel + ".mcmeta"));
    }

    /** Animated texture built from a timeline; consecutive and repeated frames are deduplicated. */
    static final class Anim {
        final List<G> frames = new ArrayList<>();
        final List<int[]> seq = new ArrayList<>(); // {index, ticks}
    }

    static Anim timeline(String rel, int steps, int ticksPerStep, IntFunction<G> fn) throws IOException {
        Anim a = new Anim();
        List<String> keys = new ArrayList<>();
        for (int t = 0; t < steps; t++) {
            G g = fn.apply(t);
            String k = g.key();
            int idx = keys.indexOf(k);
            if (idx < 0) { idx = keys.size(); keys.add(k); a.frames.add(g); }
            if (!a.seq.isEmpty() && a.seq.get(a.seq.size() - 1)[0] == idx) a.seq.get(a.seq.size() - 1)[1] += ticksPerStep;
            else a.seq.add(new int[]{idx, ticksPerStep});
        }
        if (a.frames.size() == 1) { writeStatic(rel, a.frames.get(0)); return a; }
        int w = a.frames.get(0).w, h = a.frames.get(0).h;
        BufferedImage strip = new BufferedImage(w, h * a.frames.size(), BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < a.frames.size(); i++) {
            BufferedImage f = a.frames.get(i).img();
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) strip.setRGB(x, i * h + y, f.getRGB(x, y));
        }
        writePng(rel, strip);
        StringBuilder sb = new StringBuilder("{\n  \"animation\": {\n    \"interpolate\": false,\n    \"frametime\": ")
            .append(ticksPerStep).append(",\n    \"frames\": [");
        for (int i = 0; i < a.seq.size(); i++) {
            int[] s = a.seq.get(i);
            sb.append(i == 0 ? "\n      " : ",\n      ");
            if (s[1] == ticksPerStep) sb.append(s[0]);
            else sb.append("{\"index\": ").append(s[0]).append(", \"time\": ").append(s[1]).append('}');
        }
        sb.append("\n    ]\n  }\n}\n");
        Files.writeString(TEX.resolve(rel + ".mcmeta"), sb.toString(), StandardCharsets.UTF_8);
        written.add(rel + ".mcmeta");
        return a;
    }

    // ------------------------------------------------------------------ drips
    /** A free-falling drop (in empty space): 'e' head with a 'c' tail above. */
    static void fallingDrop(G g, int x, int y) {
        if (!g.solid(x, y - 1)) g.set(x, y - 1, 'c');
        g.set(x, y, 'e');
    }

    // ================================================================== ITEMS
    static final String[] INGOT = {
        "................",
        "................",
        "................",
        "....KKKKKKKKKKK.",
        "...K6665555443K.",
        "..K6bcddccbbb3K.",
        ".K5bcdeedccbc23K",
        "K7766655544K223K",
        "K5544444433K222K",
        "K4c4333c332K12K.",
        "K43cc3333c2K1K..",
        "K3332222c22KK...",
        "KKKKKKKKKKKK....",
        "................",
        "................",
        "................",
    };

    static void items() throws IOException {
        // armour icons, sword (icon + 3D model), armour layers and the blood saw: see GenBloodStyle.java
        // mace icon: see GenMace.java
        ingot();
    }

    // --- sword: black blade, crimson fuller near the guard, blood-wet lower edge, crimson grip
    static G swordBase() {
        G g = new G(16, 16);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            int u = x - y, v = x + y;
            // blade
            if (v >= 13 && v <= 17 && u >= -1) {
                if (u >= 12 && Math.abs(v - 15) > 15 - u) continue;
                char ch = switch (v) {
                    case 13, 17 -> 'K';
                    case 14 -> u >= 9 ? '6' : (u >= 3 ? '5' : '4');
                    case 15 -> (u >= 0 && u <= 6) ? (u >= 2 && u <= 4 ? 'd' : 'c') : (u >= 12 ? '5' : '2');
                    default -> (u == 2 || u == 6 || u == 10) ? 'b' : '1';
                };
                if (u == 15) ch = '7';
                if (u == 14) ch = v == 14 ? '6' : '3';
                g.set(x, y, ch);
            }
        }
        // guard: two diagonal pixel lines across the blade base
        for (int v = 8; v <= 22; v++) {
            if ((v & 1) == 0) { // u = -2
                int x = (v - 2) / 2, y = (v + 2) / 2;
                char ch = (v == 8 || v == 22) ? 'K' : (v == 10 || v == 20) ? 'c' : (v == 14 || v == 16) ? 'd' : '5';
                g.set(x, y, ch);
                // u = -4 underside outline
                int x4 = (v - 4) / 2, y4 = (v + 4) / 2;
                g.set(x4, y4, 'K');
            } else { // u = -3
                int x = (v - 3) / 2, y = (v + 3) / 2;
                char ch = (v == 7 || v == 23) ? 'K' : (v == 9 || v == 21) ? 'b' : v == 15 ? 'f' : '3';
                g.set(x, y, ch);
            }
        }
        // the tips of the guard
        g.set(2, 5, 'K'); g.set(3, 5, 'K'); g.set(10, 12, 'K'); g.set(10, 13, 'K');
        g.set(4, 5, 'K'); g.set(10, 11, 'K');
        // grip: core on v=15, outlined
        int[][] grip = {{5, 10}, {4, 11}, {3, 12}};
        char[] wrap = {'d', 'b', 'e'};
        for (int i = 0; i < grip.length; i++) g.set(grip[i][0], grip[i][1], wrap[i]);
        g.set(4, 10, 'K'); g.set(6, 11, 'K'); g.set(3, 11, 'K'); g.set(5, 12, 'K'); g.set(2, 12, 'K'); g.set(4, 13, 'K');
        // pommel
        g.set(2, 13, 'K'); g.set(1, 13, 'K'); g.set(3, 14, 'K');
        g.set(1, 14, 'f'); g.set(2, 14, '3');
        g.set(0, 14, 'K'); g.set(0, 15, 'K'); g.set(1, 15, '2'); g.set(2, 15, 'K');
        return g;
    }

    // --- mace icon: moved to GenMace.java (32x32, matches the sword icon) - not written here any more

    // --- ingot: dark bar with a pool of liquid blood on top and a slow glint over it
    static void ingot() throws IOException {
        G base = new G(INGOT);
        timeline("item/blood_ingot.png", 40, 2, t -> {
            G g = pulse(base, beat(t, 40) > 0 && Math.floorMod(t, 40) < 2 ? 1 : 0);
            // glint wanders along the pool (rows 5-6) left to right
            int p = t - 10;
            if (p >= 0 && p < 12) {
                for (int y = 5; y <= 6; y++) for (int x = 0; x < 16; x++) {
                    char ch = g.get(x, y);
                    if (ramp(ch) < 1) continue;
                    int k = x + (6 - y) - (2 + p);
                    if (k == 0) g.set(x, y, 'g');
                    else if (k == -1 || k == 1) g.set(x, y, shift(ch, 1));
                }
            }
            return g;
        });
    }

    // ================================================================== HUD
    static G drop(int cx, int top, boolean big) {
        // drop shape around column cx (cx = left of the two centre columns)
        String[] s = big ? new String[]{
            "..KK..",
            ".KedK.",
            ".KfdK.",
            "KgedcK",
            "KfedcK",
            "KfeddcK".substring(0, 6),
            "KeddcK",
            ".KccK.",
            "..KK..",
        } : new String[]{
            ".KK.",
            "KfdK",
            "KgdK",
            "KedK",
            ".KK.",
        };
        G g = new G(16, 16);
        int ox = cx - (s[0].length() / 2 - 1);
        for (int y = 0; y < s.length; y++) for (int x = 0; x < s[y].length(); x++) {
            char ch = s[y].charAt(x);
            if (ch != '.') g.set(ox + x, top + y, ch);
        }
        return g;
    }

    static void overlay(G dst, G src) {
        for (int y = 0; y < dst.h; y++) for (int x = 0; x < dst.w; x++) if (src.solid(x, y)) dst.set(x, y, src.get(x, y));
    }

    static void hud() throws IOException {
        // NIHIL: blood drop at the centre of a nova of eight rays; rays breathe slowly
        timeline("gui/sprites/hud/nihil.png", 4, 6, t -> {
            G g = new G(16, 16);
            double ext = t == 1 || t == 2 ? 7.4 : 6.6;
            for (int k = 0; k < 8; k++) {
                double ang = k * Math.PI / 4;
                for (double r = 4.6; r <= ext; r += 0.25) {
                    int x = (int) Math.floor(7.5 + Math.cos(ang) * r + 0.5);
                    int y = (int) Math.floor(7.5 + Math.sin(ang) * r + 0.5);
                    if (k % 2 == 1 && r > ext - 1.0) continue; // diagonals shorter
                    g.set(x, y, r > ext - 0.9 ? (t == 2 ? 'g' : 'f') : 'd');
                }
            }
            G o = g.outlined('K');
            G d = drop(7, 3, true);
            if (t == 2) d = pulse(d, 1);
            overlay(o, d);
            return o;
        });

        // BLOOD TRAIL: a figure's drop diving into a blood pool, ripples run outwards
        timeline("gui/sprites/hud/trail.png", 6, 5, t -> {
            G g = new G(16, 16);
            // pool: flat ellipse, lit rim at the back, ripples on the surface
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                double nx = (x + 0.5 - 8) / 7.5, ny = (y + 0.5 - 12.5) / 2.6;
                double r = Math.sqrt(nx * nx + ny * ny);
                if (r > 1) continue;
                char ch = ny < -0.45 ? 'd' : ny < 0.4 ? 'c' : 'b';
                double ring = 0.22 + t * 0.15;
                if (t < 5 && Math.abs(r - ring) < 0.1) ch = t >= 3 ? 'd' : 'f';
                g.set(x, y, ch);
            }
            G o = g.outlined('K');
            // the drop, falling head first (tip up), separated from the pool by a gap
            G d = drop(7, 1, true);
            if (t == 0) d = pulse(d, 1);
            overlay(o, d);
            // splash crown droplets
            if (t <= 2) { o.set(3, 8 - (t == 2 ? 1 : 0), 'e'); o.set(12, 8 - (t == 2 ? 1 : 0), 'e'); }
            return o;
        });

        // BLOODSAWS: two toothed blades, slowly turning in opposite directions
        timeline("gui/sprites/hud/saws.png", 4, 3, t -> {
            G g = new G(16, 16);
            saw(g, 5.0, 5.0, 4.6, t, false);
            G h = new G(16, 16);
            saw(h, 11.0, 11.0, 4.6, t, true);
            G o = g.outlined('K');
            G p = h.outlined('K');
            overlay(o, p);
            return o;
        });

        // BLOOD CYCLONE: a two-armed blood whirl around a mace head, bolts flung out
        timeline("gui/sprites/hud/cyclone.png", 4, 4, t -> {
            G g = new G(16, 16);
            double rot = t * Math.PI / 8;
            for (int arm = 0; arm < 2; arm++) {
                for (double s = 0; s <= 1.0; s += 0.01) {
                    double r = 2.6 + s * 4.6;
                    double ang = rot + arm * Math.PI + s * 3.6;
                    int x = (int) Math.round(7.5 + Math.cos(ang) * r);
                    int y = (int) Math.round(7.5 + Math.sin(ang) * r * 0.9);
                    char ch = s > 0.82 ? 'f' : s > 0.45 ? 'd' : 'c';
                    if (ramp(g.get(x, y)) < ramp(ch)) g.set(x, y, ch);
                }
            }
            // mace head in the middle
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                int d = Math.abs(x - 7) + Math.abs(y - 7);
                if (d <= 2) g.set(x, y, d == 0 ? 'f' : (x < 7 && y < 7) ? '5' : (x <= 7 || y <= 7) ? '4' : '3');
            }
            G o = g.outlined('K');
            return o;
        });

        // BLOOD TRACKER: a blood-shot eye with a slit pupil; iris glows on the beat
        timeline("gui/sprites/hud/tracker.png", 6, 5, t -> {
            G g = new G(
                "................",
                "................",
                "................",
                ".....KKKKKK.....",
                "...KK344443KK...",
                "..K3c4bddb43cK..",
                ".K3c4deffed4c3K.",
                "K3c43efKKfe34c3K",
                "K2c32efKKfe23c2K",
                ".K2c3deffed3c2K.",
                "..K2c2bddb2c2K..",
                "...KK122221KK...",
                ".....KKKKKK.....",
                "................",
                "................",
                "................");
            g.set(5, 6, 'h');
            if (t == 0) {
                for (int y = 5; y <= 10; y++) for (int x = 0; x < 16; x++) {
                    char ch = g.get(x, y);
                    if (ch == 'e' || ch == 'f') g.set(x, y, shift(ch, 1));
                }
            }
            // a blood vein trailing off from the eye corner towards the target, pulse runs along it
            int[][] vein = {{8, 12}, {8, 13}, {9, 14}, {10, 15}};
            for (int i = 0; i < vein.length; i++) g.set(vein[i][0], vein[i][1], (t - 1) == i ? 'f' : (i < 2 ? 'd' : 'c'));
            return g;
        });
    }

    static void saw(G g, double cx, double cy, double R, int t, boolean ccw) {
        int teeth = 6;
        double rot = (ccw ? -1 : 1) * t * (2 * Math.PI / teeth) / 4.0;
        double body = R - 1.5;
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            double dx = x + 0.5 - cx, dy = y + 0.5 - cy;
            double r = Math.sqrt(dx * dx + dy * dy);
            double a = Math.atan2(dy, dx) - rot;
            double f = ((a / (2 * Math.PI) * teeth) % 1 + 1) % 1; // 0..1 inside a tooth
            if (ccw) f = 1 - f;
            double toothR = body + 1.6 * (1 - f); // hooked sawtooth profile
            if (r > Math.max(body, toothR)) continue;
            char ch;
            if (r < 0.9) ch = 'K';
            else if (r < 1.8) ch = (dx + dy < 0) ? '6' : '4';
            else if (r > body - 0.2) ch = (dx + dy < 0) ? 'g' : 'e';
            else ch = (dx + dy < -1) ? 'e' : (dx + dy < 1.5 ? 'd' : 'c');
            g.set(x, y, ch);
        }
    }

    // ================================================================== PARTICLES
    static void particles() throws IOException {
        writeStatic("particle/blood_drip_hang.png", new G(
            "........",
            "...cb...",
            "...dc...",
            "..edcb..",
            "..fedc..",
            "..edcb..",
            "...cb...",
            "........"));
        writeStatic("particle/blood_drip_fall.png", new G(
            "........",
            "...c....",
            "...dc...",
            "..edc...",
            "..fedb..",
            "..gedb..",
            "..edcb..",
            "...cb..."));
        writeStatic("particle/blood_drip_land.png", new G(
            "........",
            "........",
            "........",
            "........",
            ".c....b.",
            "...ed...",
            ".bdfedcb",
            "..bccb.."));
        writeStatic("particle/blood_splat_0.png", new G(
            "........",
            "...ed...",
            "..efdc..",
            ".edgfdc.",
            ".dffedc.",
            "..dedcb.",
            "...cb...",
            "........"));
        writeStatic("particle/blood_splat_1.png", new G(
            "...e....",
            ".e..d.c.",
            "...edc..",
            "e.dffdc.",
            "..defdc.",
            ".c.dcb..",
            "...b..b.",
            ".c......"));
        writeStatic("particle/blood_splat_2.png", new G(
            "d..c...c",
            "........",
            "c..dc...",
            "...edb.c",
            ".c.dcb..",
            "........",
            "b...b..b",
            "........"));
        writeStatic("particle/blood_splat_3.png", new G(
            "w......y",
            "........",
            "...x....",
            "y..wx...",
            "........",
            "..x...y.",
            "z.......",
            "....z..z"));
        writeStatic("particle/blood_glob_0.png", new G(
            "........",
            "...dc...",
            "..efdc..",
            "..fedcb.",
            "...dcb..",
            "........",
            "........",
            "........"));
        writeStatic("particle/blood_glob_1.png", new G(
            "........",
            "........",
            "..ed....",
            ".efdc...",
            ".ddccb..",
            "..cbb...",
            "........",
            "........"));
        writeStatic("particle/blood_glob_2.png", new G(
            "........",
            "....e...",
            "...fdc..",
            "..gedcb.",
            "..edcb..",
            "...cb...",
            ".....b..",
            "........"));
    }

    // armour layers: see GenBloodStyle.java

    // ================================================================== ICON
    static void icon() throws IOException {
        // 128x128: the sword over a pulsing blood drop. Drawn at 16x16 grid scale 8 on a dark tile.
        int S = 128;
        BufferedImage im = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        // backdrop on a 32x32 pixel grid: dark disc, crimson rib ring, transparent corners
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            int px = x / 4, py = y / 4;
            double qx = px + 0.5 - 16, qy = py + 0.5 - 16;
            double q = Math.sqrt(qx * qx + qy * qy);
            int col;
            if (q > 15.6) col = 0;
            else if (q > 14.6) col = PAL.get('K');
            else if (q > 13.4) col = PAL.get(qy < 0 ? 'e' : 'd');
            else if (q > 12.4) col = PAL.get('K');
            else col = PAL.get(q < 5 ? 'e' : q < 8 ? 'd' : q < 10.5 ? 'c' : 'b');
            im.setRGB(x, y, col);
        }
        // the blood sword on top, 6x, centred
        G sw = swordBase();
        blit(im, sw, 16, 16, 6, 0.0);
        Path p = WRITE_ICON ? ROOT.resolve("blood/src/main/resources/assets/hha_blood/icon.png") : PREVIEW.resolve("icon_candidate.png");
        Files.createDirectories(p.getParent());
        ImageIO.write(im, "png", p.toFile());
        written.add(WRITE_ICON ? "../icon.png" : p.toString());
    }

    static void blit(BufferedImage im, G g, int ox, int oy, int s, double unused) {
        for (int y = 0; y < g.h; y++) for (int x = 0; x < g.w; x++) {
            int c = PAL.get(g.c[y][x]);
            if ((c >>> 24) == 0) continue;
            for (int j = 0; j < s; j++) for (int i = 0; i < s; i++) {
                int X = ox + x * s + i, Y = oy + y * s + j;
                if (X >= 0 && Y >= 0 && X < im.getWidth() && Y < im.getHeight()) im.setRGB(X, Y, c);
            }
        }
    }

    // ================================================================== PREVIEWS
    static int blend(int bg, int p) {
        int a = p >>> 24;
        int r = (((p >> 16) & 255) * a + ((bg >> 16) & 255) * (255 - a)) / 255;
        int gg = (((p >> 8) & 255) * a + ((bg >> 8) & 255) * (255 - a)) / 255;
        int b = ((p & 255) * a + (bg & 255) * (255 - a)) / 255;
        return 0xFF000000 | (r << 16) | (gg << 8) | b;
    }

    static final class Canvas {
        final BufferedImage im;
        Canvas(int w, int h, int bg) {
            im = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) im.setRGB(x, y, bg);
        }
        void checker(int x0, int y0, int w, int h) {
            for (int y = y0; y < y0 + h; y++) for (int x = x0; x < x0 + w; x++)
                im.setRGB(x, y, ((((x - x0) / 8) + ((y - y0) / 8)) & 1) == 0 ? 0xFF8B8B93 : 0xFF7A7A82);
        }
        void fill(int x0, int y0, int w, int h, int col) {
            for (int y = y0; y < y0 + h; y++) for (int x = x0; x < x0 + w; x++) im.setRGB(x, y, blend(im.getRGB(x, y), col));
        }
        /** draws src region scaled by s at (dx,dy); mirror flips horizontally */
        void draw(BufferedImage src, int sx, int sy, int sw, int sh, int dx, int dy, int s, boolean mirror) {
            for (int y = 0; y < sh * s; y++) for (int x = 0; x < sw * s; x++) {
                int bx = x / s, by = y / s;
                if (mirror) bx = sw - 1 - bx;
                int p = src.getRGB(sx + bx, sy + by);
                if ((p >>> 24) == 0) continue;
                int X = dx + x, Y = dy + y;
                if (X < 0 || Y < 0 || X >= im.getWidth() || Y >= im.getHeight()) continue;
                im.setRGB(X, Y, blend(im.getRGB(X, Y), p));
            }
        }
        void save(String name) throws IOException {
            Files.createDirectories(PREVIEW);
            ImageIO.write(im, "png", PREVIEW.resolve(name).toFile());
        }
    }

    static BufferedImage read(String rel) throws IOException { return ImageIO.read(TEX.resolve(rel).toFile()); }

    static void previewItems() throws IOException {
        // only the 16x16 items this generator still owns; armour + sword previews come from GenBloodStyle
        String[] names = {"blood_ingot"}; // blood_mace: GenMace.java (32x32)
        int s = 8, cell = 16 * s + 6;
        int maxFrames = 0;
        List<BufferedImage> imgs = new ArrayList<>();
        for (String n : names) { BufferedImage b = read("item/" + n + ".png"); imgs.add(b); maxFrames = Math.max(maxFrames, b.getHeight() / 16); }
        int cols = Math.min(maxFrames, 12);
        int rowsPerItem = (maxFrames + cols - 1) / cols;
        Canvas c = new Canvas(16 * 4 * 7 + 40 + cols * (16 * 3 + 4), 0 + 1, 0xFF202024);
        // simpler: one row per item: hotbar-ish 4x on the left, then unique frames at 3x
        int fs = 3, fcell = 16 * fs + 3;
        int width = 8 + 16 * s + 12 + cols * fcell + 8;
        int height = 8 + names.length * (16 * s + 8);
        c = new Canvas(width, height, 0xFF202024);
        for (int i = 0; i < names.length; i++) {
            BufferedImage b = imgs.get(i);
            int y0 = 8 + i * (16 * s + 8);
            c.checker(8, y0, 16 * s, 16 * s);
            c.draw(b, 0, 0, 16, 16, 8, y0, s, false);
            int n = b.getHeight() / 16;
            for (int f = 0; f < n; f++) {
                int col = f % cols, row = f / cols;
                int x = 8 + 16 * s + 12 + col * fcell, y = y0 + row * fcell;
                if (y + 16 * fs > y0 + 16 * s) continue;
                c.fill(x, y, 16 * fs, 16 * fs, 0xFF8B8B93);
                c.draw(b, 0, f * 16, 16, 16, x, y, fs, false);
            }
        }
        c.save("preview_items.png");

        // hotbar look: all first frames at 2x and 1x on a vanilla-like slot colour
        Canvas h = new Canvas(9 * 44 + 8, 44 * 2 + 40, 0xFF3C3C3C);
        for (int i = 0; i < names.length; i++) {
            h.fill(4 + i * 44, 4, 40, 40, 0xFF8B8B8B);
            h.draw(imgs.get(i), 0, 0, 16, 16, 8 + i * 44, 8, 2, false);
            h.fill(4 + i * 44, 50, 20, 20, 0xFF8B8B8B);
            h.draw(imgs.get(i), 0, 0, 16, 16, 6 + i * 44, 52, 1, false);
        }
        h.save("preview_hotbar.png");
    }

    static void previewArmour(Path vanillaSteve) throws IOException {
        BufferedImage hum = read("entity/equipment/humanoid/blood.png");
        BufferedImage leg = read("entity/equipment/humanoid_leggings/blood.png");
        int s = 10;
        Canvas c = new Canvas(64 * s + 16, 32 * s * 2 + 24 + 34 * 8 + 16, 0xFF202024);
        c.checker(8, 8, 64 * s, 32 * s);
        c.draw(hum, 0, 0, 64, 32, 8, 8, s, false);
        c.checker(8, 16 + 32 * s, 64 * s, 32 * s);
        c.draw(leg, 0, 0, 64, 32, 8, 16 + 32 * s, s, false);
        // dolls: front, back, side at 8x
        BufferedImage steve = vanillaSteve != null && Files.exists(vanillaSteve) ? ImageIO.read(vanillaSteve.toFile()) : null;
        int ds = 8, oy = 24 + 64 * s;
        int[][] views = {{0}, {1}, {2}};
        for (int v = 0; v < 3; v++) {
            int ox = 8 + v * (20 * ds);
            c.fill(ox, oy, 16 * ds, 34 * ds, 0xFF5A6E8C);
            doll(c, steve, ox, oy + ds, ds, v, 0);
            doll(c, leg, ox, oy + ds, ds, v, 1);
            doll(c, hum, ox, oy + ds, ds, v, 2);
        }
        c.save("preview_armour.png");
    }

    /** Rough paper doll. view 0 front, 1 back, 2 right side. layer 0 skin, 1 leggings, 2 humanoid. */
    static void doll(Canvas c, BufferedImage t, int ox, int oy, int s, int view, int layer) {
        if (t == null) return;
        int[] head = view == 0 ? new int[]{8, 8} : view == 1 ? new int[]{24, 8} : new int[]{0, 8};
        int[] body = view == 0 ? new int[]{20, 20} : view == 1 ? new int[]{32, 20} : new int[]{16, 20};
        int[] arm = view == 0 ? new int[]{44, 20} : view == 1 ? new int[]{52, 20} : new int[]{40, 20};
        int[] lg = view == 0 ? new int[]{4, 20} : view == 1 ? new int[]{12, 20} : new int[]{0, 20};
        if (view == 2) {
            c.draw(t, head[0], head[1], 8, 8, ox + 4 * s, oy, s, false);
            c.draw(t, body[0], body[1], 4, 12, ox + 6 * s, oy + 8 * s, s, false);
            c.draw(t, lg[0], lg[1], 4, 12, ox + 6 * s, oy + 20 * s, s, false);
            c.draw(t, arm[0], arm[1], 4, 12, ox + 6 * s, oy + 8 * s, s, false);
            return;
        }
        c.draw(t, head[0], head[1], 8, 8, ox + 4 * s, oy, s, false);
        c.draw(t, body[0], body[1], 8, 12, ox + 4 * s, oy + 8 * s, s, false);
        c.draw(t, arm[0], arm[1], 4, 12, ox, oy + 8 * s, s, view == 1);
        c.draw(t, arm[0], arm[1], 4, 12, ox + 12 * s, oy + 8 * s, s, view != 1);
        c.draw(t, lg[0], lg[1], 4, 12, ox + 4 * s, oy + 20 * s, s, view == 1);
        c.draw(t, lg[0], lg[1], 4, 12, ox + 8 * s, oy + 20 * s, s, view != 1);
    }

    static void previewHud() throws IOException {
        String[] names = {"nihil", "trail", "saws", "cyclone", "tracker"};
        int s = 8, cols = 6, fs = 4;
        Canvas c = new Canvas(8 + names.length * (16 * s + 12) + 8, 8 + 16 * s + 12 + cols * 0 + 16 * fs + 8 + 60, 0xFF6F8F5F);
        for (int i = 0; i < names.length; i++) {
            BufferedImage b = read("gui/sprites/hud/" + names[i] + ".png");
            int x0 = 8 + i * (16 * s + 12);
            c.fill(x0 - 4, 4, 16 * s + 8, 16 * s + 8, 0x90000000);
            c.draw(b, 0, 0, 16, 16, x0, 8, s, false);
            int n = b.getHeight() / 16;
            for (int f = 0; f < n; f++) {
                int x = x0 + (f % 2) * (16 * fs + 2), y = 8 + 16 * s + 8 + (f / 2) * (16 * fs + 2);
                if (y + 16 * fs > c.im.getHeight()) continue;
                c.fill(x, y, 16 * fs, 16 * fs, 0x90000000);
                c.draw(b, 0, f * 16, 16, 16, x, y, fs, false);
            }
        }
        c.save("preview_hud.png");
        // actual size strip on a dark bar
        Canvas a = new Canvas(names.length * 22 + 8, 30, 0xFF6F8F5F);
        for (int i = 0; i < names.length; i++) {
            a.fill(4 + i * 22, 4, 20, 20, 0x90000000);
            a.draw(read("gui/sprites/hud/" + names[i] + ".png"), 0, 0, 16, 16, 6 + i * 22, 6, 1, false);
        }
        a.save("preview_hud_1x.png");
    }

    static void previewParticles() throws IOException {
        String[] names = {"blood_drip_hang", "blood_drip_fall", "blood_drip_land", "blood_splat_0", "blood_splat_1", "blood_splat_2", "blood_splat_3", "blood_glob_0", "blood_glob_1", "blood_glob_2"};
        int s = 12;
        Canvas c = new Canvas(8 + names.length * (8 * s + 8), 8 + 8 * s * 2 + 16, 0xFF202024);
        for (int i = 0; i < names.length; i++) {
            BufferedImage b = read("particle/" + names[i] + ".png");
            int x0 = 8 + i * (8 * s + 8);
            c.checker(x0, 8, 8 * s, 8 * s);
            c.draw(b, 0, 0, 8, 8, x0, 8, s, false);
            c.fill(x0, 16 + 8 * s, 8 * s, 8 * s, 0xFF9AB0C8);
            c.draw(b, 0, 0, 8, 8, x0, 16 + 8 * s, s, false);
        }
        c.save("preview_particles.png");
    }

    static boolean WRITE_ICON = false;

    static void previewIcon() throws IOException {
        BufferedImage ic = ImageIO.read((WRITE_ICON ? ROOT.resolve("blood/src/main/resources/assets/hha_blood/icon.png") : PREVIEW.resolve("icon_candidate.png")).toFile());
        Canvas c = new Canvas(128 * 2 + 32 + 64 + 8, 128 * 2 + 16, 0xFF202024);
        c.draw(ic, 0, 0, 128, 128, 8, 8, 2, false);
        c.draw(ic, 0, 0, 128, 128, 128 * 2 + 16, 8, 1, false);
        c.save("preview_icon.png");
    }

    // ================================================================== main
    public static void main(String[] args) throws Exception {
        ROOT = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        TEX = ROOT.resolve("blood/src/main/resources/assets/hha_blood/textures");
        PREVIEW = Path.of(args.length > 1 ? args[1] : ROOT.resolve("tools/textures/preview").toString());
        Path steve = args.length > 2 ? Path.of(args[2]) : null;

        items();
        hud();
        particles();
        icon();

        previewItems();
        previewArmour(steve);
        previewHud();
        previewParticles();
        previewIcon();

        for (String w : written) System.out.println("wrote " + w);
    }
}
