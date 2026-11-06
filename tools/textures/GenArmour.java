import java.awt.image.BufferedImage;
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
 * "Blood's Armor" armour redesign v2 - the "ribcage knight" set.
 * Near-black charcoal plate, bone (the sword blade's material) for ribs / spikes / horns / knees,
 * glowing crimson only as accent (gem, eye slits, vein glow under the ribs).
 *
 * Usage (from the project root):
 *   java tools/textures/GenArmour.java [projectRoot] [previewDir] [steveSkin.png] [vanillaItemDir]
 *
 * Writes (under blood/src/main/resources/assets/hha_blood/textures/) ONLY:
 *   entity/equipment/humanoid/blood.png, entity/equipment/humanoid_leggings/blood.png,
 *   item/blood_{helmet,chestplate,leggings,boots}.png (+ .png.mcmeta when animated)
 *
 * UV layout = vanilla netherite. Every limb is a wrap-around strip (right | front | left | back faces side by side),
 * so features that cross an edge are drawn as one continuous row of text. Pixels that vanilla leaves transparent
 * (everything below the shoulders on the arms, the neck notch, the tapered belly, the boot tops, ...) are masked
 * out at the end, except the helmet face which is a closed visor.
 */
public class GenArmour {

    // ------------------------------------------------------------------ palette
    static final Map<Character, Integer> PAL = new HashMap<>();
    static final String STEEL = "K1234567";   // outline black -> worn highlight
    static final String RED = "abcdefgh";
    static final String BONE = "pqrstuv";
    static {
        PAL.put('.', 0x00000000);
        int[] steel = {0x0C080A, 0x171114, 0x231C20, 0x31282E, 0x42373F, 0x584B54, 0x75676F, 0x978A92};
        for (int i = 0; i < steel.length; i++) PAL.put(STEEL.charAt(i), 0xFF000000 | steel[i]);
        int[] red = {0x35070A, 0x5A0A0F, 0x830F15, 0xA8161B, 0xCB1F22, 0xE7362E, 0xFF5E4A, 0xFFA08A};
        for (int i = 0; i < red.length; i++) PAL.put(RED.charAt(i), 0xFF000000 | red[i]);
        int[] bone = {0x4C4245, 0x6B6164, 0x8A7F82, 0xA69B9E, 0xC0B6B8, 0xD7CED0, 0xEDE6E7};
        for (int i = 0; i < bone.length; i++) PAL.put(BONE.charAt(i), 0xFF000000 | bone[i]);
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

        /** Adds a 1px outline of ch around all solid pixels (4-neighbourhood). */
        G outlined(char ch) {
            G o = copy();
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                if (solid(x, y)) continue;
                if (solid(x - 1, y) || solid(x + 1, y) || solid(x, y - 1) || solid(x, y + 1)) o.set(x, y, ch);
            }
            return o;
        }

        /** Copies an ASCII block into this grid at (x0,y0); '.' clears the pixel. */
        void blit(int x0, int y0, String... rows) {
            for (int y = 0; y < rows.length; y++) for (int x = 0; x < rows[y].length(); x++) {
                char ch = rows[y].charAt(x);
                if (!PAL.containsKey(ch)) throw new IllegalArgumentException("unknown char '" + ch + "' at " + x + "," + y + " in '" + rows[y] + "'");
                set(x0 + x, y0 + y, ch);
            }
        }

        /** Like blit but '.' keeps what is already there (overlay). */
        void ov(int x0, int y0, String... rows) {
            for (int y = 0; y < rows.length; y++) for (int x = 0; x < rows[y].length(); x++) {
                char ch = rows[y].charAt(x);
                if (ch == '.') continue;
                if (!PAL.containsKey(ch)) throw new IllegalArgumentException("unknown char '" + ch + "' at " + x + "," + y + " in '" + rows[y] + "'");
                set(x0 + x, y0 + y, ch);
            }
        }
    }

    static String mir(String s) { return new StringBuilder(s).reverse().toString(); }
    static String[] mir(String[] a) { String[] r = new String[a.length]; for (int i = 0; i < a.length; i++) r[i] = mir(a[i]); return r; }

    /** Row-wise concatenation of faces: join(R, F, L, B) -> one wrap-around strip. */
    static String[] join(String[]... faces) {
        int n = faces[0].length;
        String[] out = new String[n];
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder();
            for (String[] f : faces) {
                if (f.length != n) throw new IllegalArgumentException("face with " + f.length + " rows, expected " + n);
                sb.append(f[i]);
            }
            out[i] = sb.toString();
        }
        return out;
    }

    static String[] fill(String[] a, int rows, String pad) {
        String[] r = new String[rows];
        for (int i = 0; i < rows; i++) r[i] = i < a.length ? a[i] : pad;
        return r;
    }

    /** Steel/bone one step darker (used for the lit-from-top-left mirror half of icons). */
    static char darker(char ch) {
        int i = STEEL.indexOf(ch);
        if (i > 1) return STEEL.charAt(i - 1);
        i = BONE.indexOf(ch);
        if (i > 0) return BONE.charAt(i - 1);
        return ch;
    }

    // ------------------------------------------------------------------ vanilla alpha masks (netherite)
    static final String[] MASK_HUM = {
        "........########................................................",
        "........########................................................",
        "........########................................................",
        "........########................................................",
        "........########................................................",
        "........########................................................",
        "........########................................................",
        "........########................................................",
        "################################................................",
        "################################................................",
        "################################................................",
        "#########..##..#################................................",
        "####..###..##..###..############................................",
        ".......##......##.......########................................",
        ".......###....###.........####..................................",
        ".......###....###...............................................",
        "........####................................####................",
        "........####................................####................",
        "........####................................####................",
        "........####................................####................",
        "................######....##############################........",
        "................######....##############################........",
        "................#######..###############################........",
        "................########################################........",
        "................########################################........",
        "................#################################..#####........",
        "...######.......########################.##.#.#......#..........",
        "########################################........................",
        "########################################........................",
        "################....########....########........................",
        "################.....######......######.........................",
        "################......####........####..........................",
    };
    static final String[] MASK_LEG = {
        "................................................................",
        "................................................................",
        "................................................................",
        "................................................................",
        "................................................................",
        "................................................................",
        "................................................................",
        "................................................................",
        "................................................................",
        "................................................................",
        "................................................................",
        "................................................................",
        "................................................................",
        "................................................................",
        "................................................................",
        "................................................................",
        "....####........................................................",
        "....####........................................................",
        "....####........................................................",
        "....####........................................................",
        "################................................................",
        "################................................................",
        "################................................................",
        "################................................................",
        "################................................................",
        "################................................................",
        "################................................................",
        "##################....####....##########........................",
        "########################################........................",
        "................########################........................",
        "................########################........................",
        "................########################........................",
    };

    /** Clears every pixel at y >= yMin where the vanilla mask is transparent. */
    static void applyMask(G g, String[] mask, int yMin) {
        for (int y = yMin; y < 32; y++) {
            String row = mask[y];
            for (int x = 0; x < 64; x++) if (row.charAt(x) != '#') g.set(x, y, '.');
        }
    }

    // ================================================================== HUMANOID LAYER
    // ---- helmet base: strip = right(8) | front(8) | left(8) | back(8); right: c0 = back edge, c7 = front edge
    // front = steel rim + bone skull visor: slanted glowing eye sockets, nose hole, blood tears on the cheeks, fangs
    static final String[] HR = {
        "34555543",
        "33444332",
        "23333332",
        "23333332",
        "233K6K32",
        "23333321",
        "12333321",
        "11222211"};
    static final String[] HF = {
        "45666643",
        "3tuutsq2",
        "KKKutKKK",
        "KefutfeK",
        "3KfutfK2",
        "3esKKre2",
        "3dKtsKd2",
        "1cuKKuc1"};
    static final String[] HB = {
        "34555543",
        "344c3442",
        "334dc332",
        "233ed332",
        "233de332",
        "233cd332",
        "1233c221",
        "11222211"};
    // ---- helmet hat layer (0.5px proud): two bone horns that sweep back along the temples
    static final String[] HHR = {
        "....tsrq",
        "....tsq.",
        "...tsq..",
        "..tsq...",
        ".tsq....",
        "tsq.....",
        "sq......",
        "q......."};
    static final String[] HHF = {
        "s......q",
        "ss....qq",
        "rs....qp",
        ".q....p.",
        "........",
        "........",
        "........",
        "........"};
    static final String[] HHB = {
        "........",
        "........",
        "........",
        "........",
        "........",
        "........",
        "q......q",
        "........"};

    static void helmet(G g) {
        g.blit(0, 8, join(HR, HF, mir(HR), HB));
        // top of the helm: ridge + red crack
        g.blit(8, 0,
            "34555543",
            "33444432",
            "334K5232",
            "334d5232",
            "33dcK232",
            "33c4K232",
            "33344232",
            "22222211");
        g.blit(32, 8, join(HHR, HHF, mir(HHR), HHB));
    }

    // ---- chestplate: body strip right(4) | front(8) | left(4) | back(8), rows 0..11
    static final String[] BR = {"3454", "3433", "c332", "2332", "2332", "3332", "2332", "pqrq", "dccd", "2222", "2222", "2222"};
    static final String[] BF = {
        "K4sKKs4K",
        "34rKKr43",
        "43qKKq34",
        "34cgfc43",
        "33cedc32",
        "433ts332",
        "3rttrrq3",
        "qddsrddp",
        "d33sr33d",
        "3rttrrq3",
        "3ddsrdd3",
        "KK2trKKK"};
    static final String[] BL = {"4543", "3343", "233c", "2332", "2332", "2333", "2332", "pqrq", "dccd", "2222", "2222", "2222"};
    static final String[] BB = {
        "34555543",
        "343rq342",
        "c33rq33c",
        "4d3rq3d2",
        "33erqe32",
        "433rq332",
        "3qrrqpp3",
        "qddrqddp",
        "d33rq33d",
        "3qrrqpp3",
        "3ddrqdd3",
        "KK2rq2KK"};

    // ---- arms (right arm; left is the mirrored model part): outer(4) | front(4) | inner(4) | back(4)
    static final String[] AO = {"33v3", "3uvt", "tuvr", "1111", "3443", "cded"};
    static final String[] AF = {"4554", "3443", "3d33", "1111", "4d53", "cded"};
    static final String[] AI = {"3443", "3333", "2332", "1111", "3333", "cded"};
    static final String[] AB = {"4554", "4443", "3333", "1111", "3443", "cded"};

    // ---- boots (leg strip rows 6..11 of the 12-row leg): outer | front | inner | back; red anklet = the "binding"
    static final String[] SO = {"4444", "cded", "3443", "q333", "3rqu", "KKKK"};
    static final String[] SF = {"4444", "cded", "4553", "3443", "4uv3", "KKKK"};
    static final String[] SI = {"4444", "cded", "3443", "3333", "3333", "KKKK"};
    static final String[] SB = {"4444", "cded", "3443", "3333", "3qr3", "KKKK"};

    static G humanoidLayer() {
        G g = new G(64, 32);
        helmet(g);
        g.blit(16, 20, join(BR, BF, BL, BB));
        g.blit(20, 16, "34444443", "34455443", "33455433", "22333322");
        g.blit(28, 16, "11111111", "11111111", "11111111", "11111111");
        // arms: right arm region (40,16); the left arm re-uses it mirrored
        g.blit(40, 20, join(fill(AO, 12, "3333"), fill(AF, 12, "3333"), fill(AI, 12, "3333"), fill(AB, 12, "3333")));
        g.blit(44, 16, "3443", "4vu3", "4ur2", "3222");
        // boots: legs strip, rows 6..11
        g.blit(0, 20, fill(new String[0], 6, "3333333333333333"));
        g.blit(0, 26, join(SO, SF, SI, SB));
        g.blit(8, 16, "1111", "1111", "1111", "1111");
        applyMask(g, MASK_HUM, 16);
        return g;
    }

    // ================================================================== LEGGINGS LAYER
    static final String[] LO = {"3d43", "3c33", "KKKK", "33qK", "3qru", "33qK", "KKKK", "3d43", "3c33"};
    static final String[] LF = {"3c43", "3d33", "KKKK", "3uu3", "tuvr", "3qr3", "KKKK", "3d43", "3c33"};
    static final String[] LI = {"3443", "3333", "KKKK", "3333", "3333", "3333", "KKKK", "3443", "3333"};
    static final String[] LB = {"3443", "3d33", "KKKK", "3443", "3d33", "3223", "KKKK", "3443", "3c33"};

    static final String[] PR = {"3343", "K222", "3443", "2332", "1221"};
    static final String[] PF = {"44566544", "K24fe42K", "344dc442", "233dc332", "122cb221"};
    static final String[] PL = {"3433", "K222", "3443", "2332", "1221"};
    static final String[] PB = {"34566543", "K243342K", "344dc442", "233dc332", "122cb221"};

    static G leggingsLayer() {
        G g = new G(64, 32);
        g.blit(0, 20, join(fill(LO, 12, "2222"), fill(LF, 12, "2222"), fill(LI, 12, "2222"), fill(LB, 12, "2222")));
        g.blit(4, 16, "2222", "2222", "2222", "2222");
        g.blit(16, 27, join(PR, PF, PL, PB));
        applyMask(g, MASK_LEG, 16);
        return g;
    }

    // ================================================================== ICONS (16x16)
    /** Left half given (8 cols); the right half is the mirror, one step darker; a K outline is added around the shape. */
    static G sym(String... half) {
        int h = half.length;
        G g = new G(16, h);
        for (int y = 0; y < h; y++) {
            if (half[y].length() != 8) throw new IllegalArgumentException("half row " + y + ": '" + half[y] + "'");
            for (int x = 0; x < 8; x++) {
                char ch = half[y].charAt(x);
                if (!PAL.containsKey(ch)) throw new IllegalArgumentException("unknown char '" + ch + "' row " + y);
                g.set(x, y, ch);
                g.set(15 - x, y, darker(ch));
            }
        }
        return g.outlined('K');
    }

    // interior shapes only (the outline is automatic) - keep a 1px transparent margin
    static final String[] HELM_HALF = {
        "........",
        ".v......",
        ".ut.....",
        ".ut...66",
        "..ut.566",
        "..rt4566",
        "..qt3444",
        "...r3stu",
        "...q3KKK",
        "...q3efu",
        "....3KKu",
        "....3dtK",
        ".....3Ks",
        "......3K",
        "........",
        "........"};    static final String[] CHEST_HALF = {
        "........",
        ".v......",
        ".ut.....",
        ".tr544..",
        ".r455555",
        ".4455555",
        "..44555g",
        "..34555f",
        "..3rttss",
        "..3dddds",
        "..3rttss",
        "...3ddds",
        "....3rss",
        ".....3ts",
        "........",
        "........"};
    static final String[] LEGS_HALF = {
        "........",
        "........",
        ".6666666",
        ".344444f",
        ".4455555",
        ".445553.",
        ".445553.",
        ".4rtu43.",
        ".4rtr33.",
        ".344d43.",
        ".34d443.",
        ".344d43.",
        ".344443.",
        ".344443.",
        ".K3333..",
        "........"};
    static final String[] BOOTS_HALF = {
        "........",
        "........",
        "........",
        "..65555.",
        "....654.",
        "....cec.",
        "....454.",
        "....344.",
        "....344.",
        "...3444.",
        "..33444.",
        ".r33444.",
        ".uv3444.",
        ".uvv333.",
        "........",
        "........"};
    /** Subtle life: the red accents breathe on a slow heartbeat; the helmet fang drips once per cycle. */
    static void icon(String name, String[] half, int dropX, int dropY) throws IOException {
        G base = sym(half);
        timeline("item/" + name + ".png", 48, 2, t -> {
            G g = pulse(base, beat(t, 24));
            if (dropX >= 0) dripFrom(g, dropX, dropY, t - 10);
            return g;
        });
    }

    static char shiftRed(char ch, int off) {
        int i = RED.indexOf(ch);
        if (i < 1) return ch;
        return RED.charAt(Math.max(1, Math.min(6, i + off)));
    }

    static G pulse(G g, int off) {
        if (off == 0) return g.copy();
        G o = g.copy();
        for (int y = 0; y < g.h; y++) for (int x = 0; x < g.w; x++) o.c[y][x] = shiftRed(g.c[y][x], off);
        return o;
    }

    static int beat(int t, int period) {
        int s = Math.floorMod(t, period);
        return switch (s) { case 0, 1, 4 -> 1; default -> 0; };
    }

    static void dripFrom(G g, int x, int y, int a) {
        if (a < 0) return;
        if (a < 6) {
            g.set(x, y, 'f');
            if (a >= 3 && !g.solid(x, y + 1)) g.set(x, y + 1, 'e');
        } else {
            int fy = y + 2 + (a - 6);
            if (fy < g.h) {
                if (!g.solid(x, fy - 1)) g.set(x, fy - 1, 'c');
                g.set(x, fy, 'e');
            }
        }
    }

    static double share(G g, String set) {
        int n = 0, m = 0;
        for (int y = 0; y < g.h; y++) for (int x = 0; x < g.w; x++) { char ch = g.get(x, y); if (ch == '.') continue; n++; if (set.indexOf(ch) >= 0) m++; }
        return n == 0 ? 0 : 100.0 * m / n;
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

    // ================================================================== PREVIEW RENDERER
    static final class Preview {
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
        static double[] Rx(double deg) { double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a); return new double[]{1,0,0,0, 0,c,-s,0, 0,s,c,0, 0,0,0,1}; }
        static double[] Ry(double deg) { double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a); return new double[]{c,0,s,0, 0,1,0,0, -s,0,c,0, 0,0,0,1}; }
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

        static final class Quad {
            double[] o, e1, e2, n;
            BufferedImage tex; double u0, v0, u1, v1;
            Quad(double[] o, double[] e1, double[] e2, double[] n, BufferedImage tex, double u0, double v0, double u1, double v1) {
                this.o = o; this.e1 = e1; this.e2 = e2; this.n = n; this.tex = tex; this.u0 = u0; this.v0 = v0; this.u1 = u1; this.v1 = v1;
            }
            Quad tf(double[] m) {
                double[] a = pt(m, o), b = pt(m, add(o, e1)), c = pt(m, add(o, e2));
                return new Quad(a, sub(b, a), sub(c, a), norm(dir(m, n)), tex, u0, v0, u1, v1);
            }
        }

        static List<Quad> tf(List<Quad> qs, double[] m) { List<Quad> r = new ArrayList<>(); for (Quad q : qs) r.add(q.tf(m)); return r; }

        /** Entity cuboid in preview space (y up, entity faces +z, entity's right = -x) with minecraft box-uv. */
        static List<Quad> cuboid(BufferedImage tex, int u, int v, double x1, double y1, double z1, double w, double h, double d, double dil, boolean mirror) {
            double X1 = x1 - dil, Y1 = y1 - dil, Z1 = z1 - dil, X2 = x1 + w + dil, Y2 = y1 + h + dil, Z2 = z1 + d + dil;
            double W = X2 - X1, H = Y2 - Y1, D = Z2 - Z1;
            List<Quad> r = new ArrayList<>();
            r.add(new Quad(new double[]{X1, Y2, Z2}, new double[]{W, 0, 0}, new double[]{0, -H, 0}, new double[]{0, 0, 1}, tex, u + d, v + d, u + d + w, v + d + h));
            r.add(new Quad(new double[]{X2, Y2, Z1}, new double[]{-W, 0, 0}, new double[]{0, -H, 0}, new double[]{0, 0, -1}, tex, u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h));
            r.add(new Quad(new double[]{X1, Y2, Z1}, new double[]{0, 0, D}, new double[]{0, -H, 0}, new double[]{-1, 0, 0}, tex, u, v + d, u + d, v + d + h));
            r.add(new Quad(new double[]{X2, Y2, Z2}, new double[]{0, 0, -D}, new double[]{0, -H, 0}, new double[]{1, 0, 0}, tex, u + d + w, v + d, u + 2 * d + w, v + d + h));
            r.add(new Quad(new double[]{X1, Y2, Z1}, new double[]{W, 0, 0}, new double[]{0, 0, D}, new double[]{0, 1, 0}, tex, u + d, v, u + d + w, v + d));
            r.add(new Quad(new double[]{X1, Y1, Z2}, new double[]{W, 0, 0}, new double[]{0, 0, -D}, new double[]{0, -1, 0}, tex, u + d + w, v, u + d + 2 * w, v + d));
            if (mirror) {
                double cx = x1 + w / 2;
                return tf(r, mul(T(cx, 0, 0), S(-1, 1, 1), T(-cx, 0, 0)));
            }
            return r;
        }

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
        }

        static void ortho(Img img, List<Quad> qs, double[] view, double cx, double cy, double s) {
            for (Quad q0 : qs) {
                Quad q = q0.tf(view);
                if (q.n[2] <= 1e-6) continue;
                double shade = 0.5 + 0.5 * Math.max(0, dot(q0.n, LIGHT)) + 0.08 * Math.max(0, q0.n[1]);
                raster(img, q, shade, cx, cy, s);
            }
        }

        static void raster(Img img, Quad q, double shade, double cx, double cy, double s) {
            double[][] cs = {q.o, add(q.o, q.e1), add(q.o, q.e2), add(q.o, add(q.e1, q.e2))};
            double minx = 1e9, miny = 1e9, maxx = -1e9, maxy = -1e9;
            for (double[] c : cs) {
                double px = cx + c[0] * s, py = cy - c[1] * s;
                minx = Math.min(minx, px); maxx = Math.max(maxx, px);
                miny = Math.min(miny, py); maxy = Math.max(maxy, py);
            }
            int x0 = Math.max(0, (int) Math.floor(minx) - 1), x1 = Math.min(img.w - 1, (int) Math.ceil(maxx) + 1);
            int y0 = Math.max(0, (int) Math.floor(miny) - 1), y1 = Math.min(img.h - 1, (int) Math.ceil(maxy) + 1);
            for (int py = y0; py <= y1; py++) for (int px = x0; px <= x1; px++) {
                double X = (px + 0.5 - cx) / s - q.o[0], Y = (cy - py - 0.5) / s - q.o[1];
                double det = q.e1[0] * q.e2[1] - q.e2[0] * q.e1[1];
                if (Math.abs(det) < 1e-9) continue;
                double a = (X * q.e2[1] - q.e2[0] * Y) / det;
                double t = (q.e1[0] * Y - X * q.e1[1]) / det;
                if (a < 0 || a >= 1 || t < 0 || t >= 1) continue;
                double zval = -(q.o[2] + a * q.e1[2] + t * q.e2[2]); // viewer at +z: nearer = smaller value
                double tu = q.u0 + a * (q.u1 - q.u0), tv = q.v0 + t * (q.v1 - q.v0);
                int ix = (int) Math.floor(tu), iy = (int) Math.floor(tv);
                if (ix < 0 || iy < 0 || ix >= q.tex.getWidth() || iy >= q.tex.getHeight()) continue;
                int c = q.tex.getRGB(ix, iy);
                if ((c >>> 24) < 8) continue;
                int idx = py * img.w + px;
                if (zval >= img.z[idx]) continue;
                img.z[idx] = zval;
                int rr = (int) Math.min(255, ((c >> 16) & 255) * shade), gg = (int) Math.min(255, ((c >> 8) & 255) * shade), bb = (int) Math.min(255, (c & 255) * shade);
                img.im.setRGB(px, py, 0xFF000000 | (rr << 16) | (gg << 8) | bb);
            }
        }

        static int blend(int bg, int p) {
            int a = p >>> 24;
            int r = (((p >> 16) & 255) * a + ((bg >> 16) & 255) * (255 - a)) / 255;
            int gg = (((p >> 8) & 255) * a + ((bg >> 8) & 255) * (255 - a)) / 255;
            int b = ((p & 255) * a + (bg & 255) * (255 - a)) / 255;
            return 0xFF000000 | (r << 16) | (gg << 8) | b;
        }

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

        static BufferedImage frame(BufferedImage strip, int i) { int n = strip.getWidth(); return strip.getSubimage(0, i * n, n, n); }
        static BufferedImage steve;

        static List<Quad> player(BufferedImage skin) {
            List<Quad> r = new ArrayList<>();
            if (skin == null) return r;
            r.addAll(cuboid(skin, 0, 0, -4, 24, -4, 8, 8, 8, 0, false));
            r.addAll(cuboid(skin, 16, 16, -4, 12, -2, 8, 12, 4, 0, false));
            r.addAll(cuboid(skin, 0, 16, -3.9, 0, -2, 4, 12, 4, 0, false));
            r.addAll(cuboid(skin, 16, 48, -0.1, 0, -2, 4, 12, 4, 0, false));
            r.addAll(cuboid(skin, 32, 48, 4, 12, -2, 4, 12, 4, 0, false));
            r.addAll(cuboid(skin, 40, 16, -8, 12, -2, 4, 12, 4, 0, false));
            return r;
        }

        /** pieces: bit 0 helmet, 1 chest, 2 legs, 3 boots. */
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

        static void armourSheets(Path stevePath) throws IOException {
            if (steve == null && stevePath != null && Files.exists(stevePath)) steve = ImageIO.read(stevePath.toFile());
            BufferedImage hum = ImageIO.read(TEX.resolve("entity/equipment/humanoid/blood.png").toFile());
            BufferedImage leg = ImageIO.read(TEX.resolve("entity/equipment/humanoid_leggings/blood.png").toFile());
            // ---- sheet 1: the two layer textures
            Sheet s = new Sheet(1100, 300, 0xFF202024);
            s.text("humanoid/blood.png (8x)", 10, 18);
            s.checker(10, 24, 512, 256, 8);
            s.scaled(hum, 0, 0, 64, 32, 10, 24, 8);
            s.text("humanoid_leggings/blood.png (8x)", 560, 18);
            s.checker(560, 24, 512, 256, 8);
            s.scaled(leg, 0, 0, 64, 32, 560, 24, 8);
            s.save("armour_layers.png");

            // ---- sheet 2: Steve wearing the full set from 6 angles + each piece
            Sheet t = new Sheet(1300, 800, 0xFF202024);
            String[] names = {"front", "back", "right side", "left side", "3/4 front", "3/4 back"};
            double[][] views = {id(), Ry(180), Ry(90), Ry(-90), mul(Rx(12), Ry(35)), mul(Rx(12), Ry(215))};
            List<Quad> full = new ArrayList<>(player(steve));
            full.addAll(armour(hum, leg, 15));
            for (int i = 0; i < views.length; i++) {
                Img img = new Img(205, 370, 0xFF5A6E8C);
                ortho(img, full, mul(views[i], T(0, -17, 0)), 102, 190, 9);
                t.put(img, 10 + i * 213, 24);
                t.text(names[i], 10 + i * 213, 18);
            }
            String[] pn = {"helmet", "chestplate", "leggings", "boots", "steve (no armour)", "helmet 3/4 close"};
            int[] pm = {1, 2, 4, 8, 0, 1};
            for (int i = 0; i < pn.length; i++) {
                Img img = new Img(205, 370, 0xFF5A6E8C);
                List<Quad> sc = new ArrayList<>(player(steve));
                sc.addAll(armour(hum, leg, pm[i]));
                double[] v = i == 5 ? mul(Rx(20), Ry(35)) : (i == 1 ? mul(Rx(8), Ry(25)) : id());
                if (i == 5) ortho(img, sc, mul(v, T(0, -28, 0)), 102, 185, 18);
                else ortho(img, sc, mul(v, T(0, -17, 0)), 102, 190, 9);
                t.put(img, 10 + i * 213, 420);
                t.text(pn[i], 10 + i * 213, 414);
            }
            t.save("armour_worn.png");

            // ---- sheet 3: big close-ups (head + torso, 3/4 front and back, legs + boots)
            Sheet c = new Sheet(1300, 640, 0xFF202024);
            double[][] cv = {mul(Rx(10), Ry(30)), mul(Rx(10), Ry(-35)), mul(Rx(10), Ry(200))};
            for (int i = 0; i < 3; i++) {
                Img img = new Img(420, 600, 0xFF5A6E8C);
                ortho(img, full, mul(cv[i], T(0, -22, 0)), 210, 220, 17);
                c.put(img, 10 + i * 430, 20);
            }
            c.save("armour_close.png");

            // ---- sheet 4: gameplay distance (small scale), 4 views, plus 2x for a hotbar-ish feel
            Sheet f = new Sheet(1000, 330, 0xFF3B4E3B);
            double[][] fv = {id(), mul(Rx(8), Ry(40)), Ry(90), Ry(180)};
            for (int i = 0; i < fv.length; i++) {
                Img img = new Img(230, 300, 0xFF5E7A52);
                ortho(img, full, mul(fv[i], T(0, -17, 0)), 115, 170, 6);
                f.put(img, 10 + i * 240, 20);
            }
            f.text("distance view (6 px per texel)", 10, 14);
            f.save("armour_far.png");
        }

        static void iconSheet(Path vanillaDir) throws IOException {
            String[] names = {"blood_helmet", "blood_chestplate", "blood_leggings", "blood_boots"};
            Sheet s = new Sheet(1250, 560, 0xFF202024);
            s.text("armour icons, frame 0 at 8x; all frames at 2x below", 10, 18);
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
            // scale comparison: sword (32px icon drawn at 1x like in the slot: 16px slot -> shown 2x), vanilla netherite, ours
            s.text("hotbar look: gui scale 2 (top) / 3 (bottom): ours, sword, vanilla netherite", 600, 18);
            BufferedImage sword = ImageIO.read(TEX.resolve("item/blood_sword.png").toFile());
            for (int gs = 2; gs <= 3; gs++) {
                int y = gs == 2 ? 30 : 100;
                List<BufferedImage> row = new ArrayList<>();
                for (String n : names) row.add(frame(ImageIO.read(TEX.resolve("item/" + n + ".png").toFile()), 0));
                row.add(sword.getSubimage(0, 0, 32, 32));
                String[] vn = {"netherite_helmet", "netherite_chestplate", "netherite_leggings", "netherite_boots"};
                if (vanillaDir != null) for (String n : vn) {
                    Path p = vanillaDir.resolve("assets_minecraft_textures_item_" + n + ".png");
                    if (Files.exists(p)) row.add(ImageIO.read(p.toFile()));
                }
                for (int i = 0; i < row.size(); i++) {
                    BufferedImage im = row.get(i);
                    int x = 600 + i * (20 * gs + 2);
                    s.fill(x, y, 20 * gs, 20 * gs, 0xFF8B8B8B);
                    int px = im.getWidth() == 32 ? gs : gs;   // 32px sword icon fills the 16px slot at half pixel size, like the real item
                    BufferedImage sc = new BufferedImage(16 * gs, 16 * gs, BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D g2 = sc.createGraphics();
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g2.drawImage(im, 0, 0, 16 * gs, 16 * gs, null);
                    g2.dispose();
                    s.put(sc, x + 2 * gs, y + 2 * gs);
                }
            }
            // 8x side by side with sword (sword 32px at 4x = same footprint as 16px at 8x) and vanilla netherite helmet
            s.text("sword (32px at 4x) | blood helmet (8x) | vanilla netherite helmet (8x)", 10, 310);
            s.checker(10, 316, 128, 128, 4);
            s.scaled(sword, 0, 0, 32, 32, 10, 316, 4);
            s.checker(150, 316, 128, 128, 8);
            s.scaled(frame(ImageIO.read(TEX.resolve("item/blood_helmet.png").toFile()), 0), 0, 0, 16, 16, 150, 316, 8);
            if (vanillaDir != null) {
                Path p = vanillaDir.resolve("assets_minecraft_textures_item_netherite_helmet.png");
                if (Files.exists(p)) {
                    s.checker(290, 316, 128, 128, 8);
                    s.scaled(ImageIO.read(p.toFile()), 0, 0, 16, 16, 290, 316, 8);
                }
            }
            s.save("armour_icons.png");
        }
    }

    // ================================================================== main
    public static void main(String[] args) throws Exception {
        ROOT = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        TEX = ROOT.resolve("blood/src/main/resources/assets/hha_blood/textures");
        PREVIEW = Path.of(args.length > 1 ? args[1] : ROOT.resolve("tools/textures/preview").toString());
        Path steve = args.length > 2 ? Path.of(args[2]) : null;
        Path vanillaDir = args.length > 3 ? Path.of(args[3]) : null;

        // "--preview" as 5th arg: only render the preview sheets of whatever armour textures are on disk
        // (used for the trim-based armour from GenTrimArmour.java - running this generator normally would
        // overwrite it with the old "ribcage knight" design)
        if (args.length > 4 && args[4].equals("--preview")) {
            Preview.armourSheets(steve);
            Preview.iconSheet(vanillaDir);
            return;
        }

        G hum = humanoidLayer(), leg = leggingsLayer();
        writeStatic("entity/equipment/humanoid/blood.png", hum);
        writeStatic("entity/equipment/humanoid_leggings/blood.png", leg);
        System.out.printf("red share: humanoid %.1f%%, leggings %.1f%% (bone %.1f%% / %.1f%%)%n", share(hum, RED), share(leg, RED), share(hum, BONE), share(leg, BONE));
        icon("blood_helmet", HELM_HALF, 5, 14);
        icon("blood_chestplate", CHEST_HALF, -1, 0);
        icon("blood_leggings", LEGS_HALF, -1, 0);
        icon("blood_boots", BOOTS_HALF, -1, 0);

        Preview.armourSheets(steve);
        Preview.iconSheet(vanillaDir);
        for (String w : written) System.out.println("wrote " + w);
    }
}
