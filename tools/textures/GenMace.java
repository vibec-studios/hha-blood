import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.IntFunction;

/**
 * Blood Mace inventory icon (textures/item/blood_mace.png, 32x32 animated strip + .mcmeta), rendered from the
 * held 3D model (models/item/blood_mace_3d.json + textures/item/blood_mace_3d.png, made by GenCyclone.java) so
 * icon and model match. The model stands upright; for the icon it is turned -45 deg around z onto the vanilla
 * diagonal (head top right, pommel bottom left), same pipeline and framing rules as GenSwordIcon.java:
 * orthographic front render, dominant colour per icon pixel, hand polish, 1px dark outline, heartbeat pulse of
 * the red parts + a drop of blood running off the lower spike (extra frames).
 *
 * (GenTextures.java used to write the old 16x16 mace icon; it no longer does.)
 *
 * Run (from the project root): java tools/textures/GenMace.java [projectRoot] [previewDir]
 *   previewDir: writes mace_icon.png (icon 10x | render of the model | frames) and mace_grid(_raw).png.
 */
public class GenMace {

    static final int N = 32, SS = 12;

    /** palette of blood_mace_3d.png (GenCyclone): dark plum steel 1..7, blood A..F; K = outline (as the sword icon) */
    static final Map<Character, Integer> PAL = new LinkedHashMap<>();
    static final String STEEL = "1234567", RED = "ABCDEF";
    static {
        PAL.put('.', 0x00000000);
        PAL.put('K', 0xFF0A0C12);
        PAL.put('1', 0xFF171113); PAL.put('2', 0xFF221B1E); PAL.put('3', 0xFF2F272B); PAL.put('4', 0xFF3E3439);
        PAL.put('5', 0xFF53474D); PAL.put('6', 0xFF6C5F65); PAL.put('7', 0xFF857780);
        PAL.put('A', 0xFF6E0910); PAL.put('B', 0xFF9B0A12); PAL.put('C', 0xFFBA121B); PAL.put('D', 0xFFD81E26);
        PAL.put('E', 0xFFEE3E44); PAL.put('F', 0xFFFF8078);
    }

    /** turn the upright model onto the icon diagonal, then the view: 25 deg around the long axis, 15 deg from above (= sword icon) */
    static final double[] PRE = R.around(R.Rz(-45));
    static final double[] VIEW = R.upright(R.mul(R.Rx(15), R.Ry(25)));
    static double[] FIT;
    /** px kept free on every side for the outline; fills the icon like the sword (vanilla maces are chunky too) */
    static final double MARGIN = 1, NUDGE_X = 0, NUDGE_Y = 0;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path assets = root.resolve("blood/src/main/resources/assets/hha_blood");
        Path tex = assets.resolve("textures");
        BufferedImage atlas = ImageIO.read(tex.resolve("item/blood_mace_3d.png").toFile());
        List<R.Quad> model = R.tf(R.jsonModel(assets.resolve("models/item/blood_mace_3d.json"), atlas), PRE);

        FIT = R.fit(model, VIEW, MARGIN, NUDGE_X, NUDGE_Y);
        if (System.getenv("DUMP") != null) {
            for (String row : R.chars(R.render(model, VIEW, FIT, 0.45), PAL)) System.out.println("        \"" + row + "\",");
            return;
        }
        int[] base = icon(model);
        if (System.getenv("DUMP_FINAL") != null) { for (char[] r : BASE) System.out.println("        \"" + new String(r) + "\","); return; }
        List<int[]> frames = new ArrayList<>();
        timeline(tex, "item/blood_mace.png", 48, 2, t -> animate(t), frames);

        if (args.length > 1) {
            Path dir = Path.of(args[1]);
            Files.createDirectories(dir);
            R.sheet(dir.resolve("mace_icon.png"), frames, model, VIEW, FIT);
            R.grid(dir.resolve("mace_grid.png"), base);
            R.grid(dir.resolve("mace_grid_raw.png"), R.render(model, VIEW, FIT, 0.45));
        }
    }

    // ------------------------------------------------------------------ the icon
    /** hand polish after planes(), before the outline (R.fix: "x,y=c", '.' clears) */
    static final String POLISH = String.join(" ",
        "16,17=4 17,18=4",                               // stray lit pixels in the shaded front plane
        "2,28=C 2,29=B 6,30=.");                         // red pommel tip as one piece, no stub

    static char[][] BASE;

    /**
     * Head steel in clean planes: the 3D texture is noisy plum steel, at 32 px that turns to mud. Head pixels (on the
     * head side of the collar, a = x - y >= -1) become a lit top plane (6, outer rim 7) and a front plane (4, 3 in the
     * lower half towards the collar); red stays as rendered. Everywhere the darkest tone 1 becomes 2 so haft and
     * pommel do not melt into the outline.
     */
    static void planes(char[][] g) {
        char[][] o = R.copy(g);
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            char ch = o[y][x];
            if (ch == '1') { g[y][x] = '2'; continue; }               // darkest plum is as dark as the outline: lift it one step
            if (STEEL.indexOf(ch) < 0 || x - y < -1) continue;
            boolean top = ch >= '5';
            if (top) {
                boolean rim = !R.sol(o, x + 1, y) || !R.sol(o, x, y - 1) || !R.sol(o, x + 1, y - 1);
                g[y][x] = rim ? '7' : '6';
            } else {
                g[y][x] = (x + y) > 33 ? '4' : '3';
            }
        }
    }

    static int[] icon(List<R.Quad> model) {
        char[][] g = R.overlay(R.chars(R.render(model, VIEW, FIT, 0.45), PAL), new String[0]);
        planes(g);
        R.fix(g, POLISH);
        BASE = R.outlined(g, 'K');
        return R.argb(BASE, PAL);
    }

    // ------------------------------------------------------------------ animation
    /** under the lowest spike tip (east spike, lower right) a drop gathers and falls off the icon */
    static final int DX = 28, DY = 19;

    static int[] animate(int t) {
        // heartbeat every 24 steps (lub-dub) like the sword, a drop every 48 steps
        char[][] g = R.shift(BASE, RED, R.beat(t, 24), 1, 4);
        int s = t - 8;
        if (s >= 0 && s < 5) {                                     // gathers and swells under the tip
            g[DY][DX] = s < 2 ? 'B' : 'D';
            if (s >= 3) g[DY + 1][DX] = 'C';
        } else if (s >= 5) {                                       // falls, one pixel per step, with a short trail
            int y = DY + 2 + (s - 5);
            if (y < N) g[y][DX] = 'D';
            if (y - 1 < N && y - 1 > DY) g[y - 1][DX] = 'A';
        }
        return R.argb(g, PAL);
    }

    // ------------------------------------------------------------------ output
    static void timeline(Path tex, String rel, int steps, int ticksPerStep, IntFunction<int[]> fn, List<int[]> outFrames) throws IOException {
        List<int[]> frames = new ArrayList<>();
        List<int[]> seq = new ArrayList<>();
        for (int t = 0; t < steps; t++) {
            int[] g = fn.apply(t);
            int idx = -1;
            for (int i = 0; i < frames.size(); i++) if (Arrays.equals(frames.get(i), g)) { idx = i; break; }
            if (idx < 0) { idx = frames.size(); frames.add(g); }
            if (!seq.isEmpty() && seq.get(seq.size() - 1)[0] == idx) seq.get(seq.size() - 1)[1] += ticksPerStep;
            else seq.add(new int[]{idx, ticksPerStep});
        }
        outFrames.addAll(frames);
        BufferedImage strip = new BufferedImage(N, N * frames.size(), BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < frames.size(); i++) for (int p = 0; p < N * N; p++) strip.setRGB(p % N, i * N + p / N, frames.get(i)[p]);
        Path p = tex.resolve(rel);
        ImageIO.write(strip, "png", p.toFile());
        Path meta = tex.resolve(rel + ".mcmeta");
        if (frames.size() == 1) { Files.deleteIfExists(meta); System.out.println("wrote " + rel + " (static)"); return; }
        StringBuilder sb = new StringBuilder("{\n  \"animation\": {\n    \"interpolate\": false,\n    \"frametime\": ")
            .append(ticksPerStep).append(",\n    \"frames\": [");
        for (int i = 0; i < seq.size(); i++) {
            int[] s = seq.get(i);
            sb.append(i == 0 ? "\n      " : ",\n      ");
            if (s[1] == ticksPerStep) sb.append(s[0]);
            else sb.append("{\"index\": ").append(s[0]).append(", \"time\": ").append(s[1]).append('}');
        }
        sb.append("\n    ]\n  }\n}\n");
        Files.writeString(meta, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("wrote " + rel + " (" + frames.size() + " frames) + .mcmeta");
    }

    // ================================================================== renderer (shared with GenMace.java - keep in sync)
    static final class R {
        // ---- 4x4 matrices, row-major, column vectors
        static double[] id() { return new double[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1}; }
        static double[] mul(double[] a, double[] b) {
            double[] r = new double[16];
            for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) { double s = 0; for (int k = 0; k < 4; k++) s += a[i * 4 + k] * b[k * 4 + j]; r[i * 4 + j] = s; }
            return r;
        }
        static double[] mul(double[]... ms) { double[] r = id(); for (double[] m : ms) r = mul(r, m); return r; }
        static double[] T(double x, double y, double z) { return new double[]{1,0,0,x, 0,1,0,y, 0,0,1,z, 0,0,0,1}; }
        static double[] Rx(double deg) { double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a); return new double[]{1,0,0,0, 0,c,-s,0, 0,s,c,0, 0,0,0,1}; }
        static double[] Ry(double deg) { double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a); return new double[]{c,0,s,0, 0,1,0,0, -s,0,c,0, 0,0,0,1}; }
        static double[] Rz(double deg) { double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a); return new double[]{c,-s,0,0, s,c,0,0, 0,0,1,0, 0,0,0,1}; }
        /** rotation around the vanilla icon diagonal (lower left -> upper right), i.e. around the weapon's long axis */
        static double[] diag(double deg) { return mul(Rz(-45), Ry(deg), Rz(45)); }
        /** rotation m given in the upright weapon frame (long axis = y), applied to the model lying on the icon diagonal */
        static double[] upright(double[] m) { return mul(Rz(-45), m, Rz(45)); }
        /** rotation m around the model centre (8,8,8) */
        static double[] around(double[] m) { return mul(T(8, 8, 8), m, T(-8, -8, -8)); }
        static double[] pt(double[] m, double[] p) { return new double[]{m[0]*p[0]+m[1]*p[1]+m[2]*p[2]+m[3], m[4]*p[0]+m[5]*p[1]+m[6]*p[2]+m[7], m[8]*p[0]+m[9]*p[1]+m[10]*p[2]+m[11]}; }
        static double[] dir(double[] m, double[] p) { return new double[]{m[0]*p[0]+m[1]*p[1]+m[2]*p[2], m[4]*p[0]+m[5]*p[1]+m[6]*p[2], m[8]*p[0]+m[9]*p[1]+m[10]*p[2]}; }
        static double[] sub(double[] a, double[] b) { return new double[]{a[0]-b[0], a[1]-b[1], a[2]-b[2]}; }
        static double[] add(double[] a, double[] b) { return new double[]{a[0]+b[0], a[1]+b[1], a[2]+b[2]}; }
        static double[] cross(double[] a, double[] b) { return new double[]{a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]}; }
        static double dot(double[] a, double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
        static double[] norm(double[] a) { double l = Math.sqrt(dot(a, a)); return new double[]{a[0]/l, a[1]/l, a[2]/l}; }

        /** Quad: corner o maps to texel (u0,v0); o+e1 to (u1,v0); o+e2 to (u0,v1). n = outward normal (model space). */
        static final class Quad {
            final double[] o, e1, e2, n; final BufferedImage tex; final double u0, v0, u1, v1;
            Quad(double[] o, double[] e1, double[] e2, double[] n, BufferedImage tex, double u0, double v0, double u1, double v1) {
                this.o = o; this.e1 = e1; this.e2 = e2; this.n = n; this.tex = tex; this.u0 = u0; this.v0 = v0; this.u1 = u1; this.v1 = v1;
            }
            Quad tf(double[] m) {
                double[] a = pt(m, o), b = pt(m, add(o, e1)), c = pt(m, add(o, e2));
                return new Quad(a, sub(b, a), sub(c, a), norm(dir(m, n)), tex, u0, v0, u1, v1);
            }
        }

        static List<Quad> tf(List<Quad> qs, double[] m) { List<Quad> r = new ArrayList<>(); for (Quad q : qs) r.add(q.tf(m)); return r; }

        // ---- minimal JSON
        static final class MiniJson {
            final String s; int i = 0;
            MiniJson(String s) { this.s = s; }
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
        static List<Quad> jsonModel(Path json, BufferedImage tex) throws IOException {
            Map<String, Object> root = (Map<String, Object>) new MiniJson(Files.readString(json)).value();
            double su = tex.getWidth() / 16.0, sv = tex.getWidth() / 16.0;
            List<Quad> out = new ArrayList<>();
            for (Object eo : (List<Object>) root.get("elements")) {
                Map<String, Object> e = (Map<String, Object>) eo;
                double[] f = nums(e.get("from")), t = nums(e.get("to"));
                double w = t[0] - f[0], h = t[1] - f[1], dd = t[2] - f[2];
                double[] rot = id();
                if (e.get("rotation") instanceof Map<?, ?> r) {
                    double[] o = nums(r.get("origin")); double a = (Double) r.get("angle"); String ax = (String) r.get("axis");
                    double[] Rm = ax.equals("x") ? Rx(a) : ax.equals("y") ? Ry(a) : Rz(a);
                    rot = mul(T(o[0], o[1], o[2]), Rm, T(-o[0], -o[1], -o[2]));
                }
                Map<String, Object> faces = (Map<String, Object>) e.get("faces");
                for (Map.Entry<String, Object> fe : faces.entrySet()) {
                    double[] uv = nums(((Map<String, Object>) fe.getValue()).get("uv"));
                    double u0 = uv[0] * su, v0 = uv[1] * sv, u1 = uv[2] * su, v1 = uv[3] * sv;
                    Quad q = switch (fe.getKey()) {
                        case "south" -> new Quad(new double[]{f[0], t[1], t[2]}, new double[]{w, 0, 0}, new double[]{0, -h, 0}, new double[]{0, 0, 1}, tex, u0, v0, u1, v1);
                        case "north" -> new Quad(new double[]{t[0], t[1], f[2]}, new double[]{-w, 0, 0}, new double[]{0, -h, 0}, new double[]{0, 0, -1}, tex, u0, v0, u1, v1);
                        case "west" -> new Quad(new double[]{f[0], t[1], f[2]}, new double[]{0, 0, dd}, new double[]{0, -h, 0}, new double[]{-1, 0, 0}, tex, u0, v0, u1, v1);
                        case "east" -> new Quad(new double[]{t[0], t[1], t[2]}, new double[]{0, 0, -dd}, new double[]{0, -h, 0}, new double[]{1, 0, 0}, tex, u0, v0, u1, v1);
                        case "up" -> new Quad(new double[]{f[0], t[1], f[2]}, new double[]{w, 0, 0}, new double[]{0, 0, dd}, new double[]{0, 1, 0}, tex, u0, v0, u1, v1);
                        default -> new Quad(new double[]{f[0], f[1], t[2]}, new double[]{w, 0, 0}, new double[]{0, 0, -dd}, new double[]{0, -1, 0}, tex, u0, v0, u1, v1);
                    };
                    out.add(q.tf(rot));
                }
            }
            return out;
        }

        /** face shading happens in the upright weapon frame (both models lie on the -45 deg diagonal) */
        static final double[] SHADE_FRAME = Rz(45);

        /** Minecraft face shading (up 1.0, north/south 0.8, east/west 0.6, down 0.5), blended for tilted faces; normalised so the front = 1. */
        static double shade(double[] n) {
            double s = n[0] * n[0] * 0.6 + n[2] * n[2] * 0.8 + n[1] * n[1] * (n[1] > 0 ? 1.0 : 0.5);
            return Math.min(1.12, s / 0.8);
        }

        /**
         * Orthographic render from the front (viewer at +z) into a w x h ARGB buffer; view = rotation applied
         * around the model centre first; fit = {px per model unit, model x at buffer x 0, model y at buffer y 0}.
         */
        static int[] raw(List<Quad> model, double[] view, double[] fit, int w, int h, double pxScale) {
            int[] col = new int[w * h];
            double[] zb = new double[w * h];
            Arrays.fill(zb, -1e9);
            double s = fit[0] * pxScale;
            double[] m = around(view);
            for (Quad q0 : model) {
                Quad q = q0.tf(m);
                if (q.n[2] <= 1e-6) continue;
                double sh = shade(dir(SHADE_FRAME, q0.n));
                double[][] cs = {q.o, add(q.o, q.e1), add(q.o, q.e2), add(q.o, add(q.e1, q.e2))};
                double minx = 1e9, miny = 1e9, maxx = -1e9, maxy = -1e9;
                for (double[] c : cs) {
                    double px = (c[0] - fit[1]) * s, py = (fit[2] - c[1]) * s;
                    minx = Math.min(minx, px); maxx = Math.max(maxx, px); miny = Math.min(miny, py); maxy = Math.max(maxy, py);
                }
                int x0 = Math.max(0, (int) Math.floor(minx) - 1), x1 = Math.min(w - 1, (int) Math.ceil(maxx) + 1);
                int y0 = Math.max(0, (int) Math.floor(miny) - 1), y1 = Math.min(h - 1, (int) Math.ceil(maxy) + 1);
                // solve o + a e1 + b e2 = (X, Y, z) in x/y
                double det = q.e1[0] * q.e2[1] - q.e1[1] * q.e2[0];
                if (Math.abs(det) < 1e-12) continue;
                for (int py = y0; py <= y1; py++) for (int px = x0; px <= x1; px++) {
                    double X = (px + 0.5) / s + fit[1], Y = fit[2] - (py + 0.5) / s;
                    double bx = X - q.o[0], by = Y - q.o[1];
                    double a = (bx * q.e2[1] - by * q.e2[0]) / det, b = (q.e1[0] * by - q.e1[1] * bx) / det;
                    if (a < 0 || a >= 1 || b < 0 || b >= 1) continue;
                    double z = q.o[2] + a * q.e1[2] + b * q.e2[2];
                    int idx = py * w + px;
                    if (z <= zb[idx]) continue;
                    double tu = q.u0 + a * (q.u1 - q.u0), tv = q.v0 + b * (q.v1 - q.v0);
                    int ix = Math.min(q.tex.getWidth() - 1, (int) Math.floor(tu)), iy = Math.min(q.tex.getHeight() - 1, (int) Math.floor(tv));
                    if (ix < 0 || iy < 0) continue;
                    int c = q.tex.getRGB(ix, iy);
                    if ((c >>> 24) < 128) continue;
                    zb[idx] = z;
                    col[idx] = 0xFF000000 | (cl(((c >> 16) & 255) * sh) << 16) | (cl(((c >> 8) & 255) * sh) << 8) | cl((c & 255) * sh);
                }
            }
            return col;
        }

        /** framing that fits the projected model into the icon with `margin` px free on each side (uniform scale, centred) */
        static double[] fit(List<Quad> model, double[] view, double margin, double nudgeX, double nudgeY) {
            double[] m = around(view);
            double minx = 1e9, miny = 1e9, maxx = -1e9, maxy = -1e9;
            for (Quad q0 : model) {
                Quad q = q0.tf(m);
                for (double[] c : new double[][]{q.o, add(q.o, q.e1), add(q.o, q.e2), add(q.o, add(q.e1, q.e2))}) {
                    minx = Math.min(minx, c[0]); maxx = Math.max(maxx, c[0]); miny = Math.min(miny, c[1]); maxy = Math.max(maxy, c[1]);
                }
            }
            double avail = N - 2 * margin;
            double s = avail / Math.max(maxx - minx, maxy - miny);
            double cx = (minx + maxx) / 2, cy = (miny + maxy) / 2;
            double[] f = {s, cx - (N / 2.0 + nudgeX) / s, cy + (N / 2.0 + nudgeY) / s};
            System.out.printf(Locale.ROOT, "fit: scale %.4f px/unit, bbox x %.2f..%.2f y %.2f..%.2f%n", s, minx, maxx, miny, maxy);
            return f;
        }

        static int cl(double v) { return (int) Math.max(0, Math.min(255, Math.round(v))); }

        /**
         * 32x32 icon: per pixel the dominant colour of its SS x SS samples (no blur); a pixel is solid when at
         * least `cover` of its samples hit the model. If no colour dominates, the sample closest to the mean wins.
         */
        static int[] render(List<Quad> model, double[] view, double[] fit, double cover) {
            int W = N * SS;
            int[] hi = raw(model, view, fit, W, W, SS);
            int[] g = new int[N * N];
            for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
                Map<Integer, Integer> cnt = new HashMap<>();
                int hit = 0; double mr = 0, mg = 0, mb = 0;
                for (int sy = 0; sy < SS; sy++) for (int sx = 0; sx < SS; sx++) {
                    int c = hi[(y * SS + sy) * W + x * SS + sx];
                    if ((c >>> 24) == 0) continue;
                    hit++; cnt.merge(c, 1, Integer::sum);
                    mr += (c >> 16) & 255; mg += (c >> 8) & 255; mb += c & 255;
                }
                if (hit < cover * SS * SS) continue;
                int best = 0, bc = 0;
                for (Map.Entry<Integer, Integer> e : cnt.entrySet()) if (e.getValue() > bc) { bc = e.getValue(); best = e.getKey(); }
                if (bc < 0.3 * hit) {
                    mr /= hit; mg /= hit; mb /= hit; double bd = 1e18;
                    for (int c : cnt.keySet()) {
                        double d = sq(((c >> 16) & 255) - mr) + sq(((c >> 8) & 255) - mg) + sq((c & 255) - mb);
                        if (d < bd) { bd = d; best = c; }
                    }
                }
                g[y * N + x] = best;
            }
            return g;
        }

        static double sq(double v) { return v * v; }

        /** icon -> rows of palette chars (nearest palette colour; transparent = '.') */
        static String[] chars(int[] g, Map<Character, Integer> pal) {
            String[] rows = new String[N];
            for (int y = 0; y < N; y++) {
                StringBuilder sb = new StringBuilder();
                for (int x = 0; x < N; x++) {
                    int c = g[y * N + x];
                    if ((c >>> 24) == 0) { sb.append('.'); continue; }
                    char best = '?'; double bd = 1e18;
                    for (Map.Entry<Character, Integer> e : pal.entrySet()) {
                        int p = e.getValue();
                        if ((p >>> 24) == 0) continue;
                        double d = sq(((c >> 16) & 255) - ((p >> 16) & 255)) + sq(((c >> 8) & 255) - ((p >> 8) & 255)) + sq((c & 255) - (p & 255));
                        if (d < bd) { bd = d; best = e.getKey(); }
                    }
                    sb.append(best);
                }
                rows[y] = sb.toString();
            }
            return rows;
        }

        /**
         * Hand polish: overlay rows on top of the rendered rows. ' ' keeps the rendered pixel, '_' clears it,
         * any palette char replaces it.
         */
        static char[][] overlay(String[] base, String[] fix) {
            char[][] g = new char[N][];
            for (int y = 0; y < N; y++) {
                g[y] = base[y].toCharArray();
                if (y >= fix.length) continue;
                for (int x = 0; x < Math.min(N, fix[y].length()); x++) {
                    char ch = fix[y].charAt(x);
                    if (ch == ' ') continue;
                    g[y][x] = ch == '_' ? '.' : ch;
                }
            }
            return g;
        }

        /** pixel fixes "x,y=c x,y=c ..." ('.' clears) */
        static void fix(char[][] g, String spec) {
            for (String e : spec.trim().split("\\s+")) {
                if (e.isEmpty()) continue;
                String[] p = e.split("[,=]");
                g[Integer.parseInt(p[1])][Integer.parseInt(p[0])] = p[2].charAt(0);
            }
        }

        static char[][] overlay(char[][] base, String[] fix) {
            String[] rows = new String[N];
            for (int y = 0; y < N; y++) rows[y] = new String(base[y]);
            return overlay(rows, fix);
        }

        static int[] argb(char[][] g, Map<Character, Integer> pal) {
            int[] o = new int[N * N];
            for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
                Integer c = pal.get(g[y][x]);
                if (c == null) throw new IllegalArgumentException("unknown char '" + g[y][x] + "' at " + x + "," + y);
                o[y * N + x] = c;
            }
            return o;
        }

        static char[][] copy(char[][] g) { char[][] o = new char[g.length][]; for (int i = 0; i < g.length; i++) o[i] = g[i].clone(); return o; }

        static char[][] outlined(char[][] g, char ch) {
            char[][] o = copy(g);
            for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
                if (g[y][x] != '.') continue;
                if (sol(g, x - 1, y) || sol(g, x + 1, y) || sol(g, x, y - 1) || sol(g, x, y + 1)) o[y][x] = ch;
            }
            return o;
        }

        static boolean sol(char[][] g, int x, int y) { return x >= 0 && y >= 0 && x < N && y < N && g[y][x] != '.'; }

        /** shifts every pixel of the ramp (dark -> light) by off steps, clamped to [lo, hi] */
        static char[][] shift(char[][] g, String ramp, int off, int lo, int hi) {
            char[][] o = copy(g);
            if (off == 0) return o;
            for (char[] r : o) for (int x = 0; x < r.length; x++) {
                int i = ramp.indexOf(r[x]);
                if (i >= lo && i <= hi) r[x] = ramp.charAt(Math.max(lo, Math.min(hi, i + off)));
            }
            return o;
        }

        static boolean solid(int[] g, int x, int y) { return x >= 0 && y >= 0 && x < N && y < N && (g[y * N + x] >>> 24) != 0; }
        /** outline for the raw ARGB icons of --variants */
        static int[] outlined(int[] g, int col) {
            int[] o = g.clone();
            for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
                if (solid(g, x, y)) continue;
                if (solid(g, x - 1, y) || solid(g, x + 1, y) || solid(g, x, y - 1) || solid(g, x, y + 1)) o[y * N + x] = col;
            }
            return o;
        }

        /** heartbeat in steps: lub (2 steps), gap, dub (1 step), rest */
        static int beat(int t, int period) { int s = Math.floorMod(t, period); return switch (s) { case 0, 1, 4 -> 1; default -> 0; }; }

        // ---- previews
        static BufferedImage img(int[] g) {
            BufferedImage im = new BufferedImage(N, N, BufferedImage.TYPE_INT_ARGB);
            for (int p = 0; p < N * N; p++) im.setRGB(p % N, p / N, g[p]);
            return im;
        }

        static void fill(BufferedImage im, int x0, int y0, int w, int h, int c) {
            for (int y = Math.max(0, y0); y < Math.min(im.getHeight(), y0 + h); y++) for (int x = Math.max(0, x0); x < Math.min(im.getWidth(), x0 + w); x++) im.setRGB(x, y, c);
        }

        static void zoom(BufferedImage im, BufferedImage src, int x0, int y0, int z, boolean checker) {
            for (int y = 0; y < src.getHeight() * z; y++) for (int x = 0; x < src.getWidth() * z; x++) {
                int c = src.getRGB(x / z, y / z);
                boolean chk = (((x / z) + (y / z)) & 1) == 0;
                if ((c >>> 24) == 0) { if (checker) im.setRGB(x0 + x, y0 + y, chk ? 0xFF3C4A66 : 0xFF34405A); }
                else im.setRGB(x0 + x, y0 + y, c);
            }
        }

        /** nearest-neighbour resample of a 32px sprite to size px (GUI without mipmaps); phase 0.5 = texel centres, 0 / 0.99 = the two 1x pickings */
        static void scaled(BufferedImage im, BufferedImage src, int x0, int y0, int size, double phase) {
            double f = src.getWidth() / (double) size;
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                int sx = (int) Math.floor((x + phase) * f), sy = (int) Math.floor((y + phase) * f);
                int c = src.getRGB(Math.min(31, sx), Math.min(31, sy));
                if ((c >>> 24) != 0) im.setRGB(x0 + x, y0 + y, c);
            }
        }

        static void text(BufferedImage im, String s, int x, int y) {
            java.awt.Graphics2D g = im.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 13));
            g.drawString(s, x, y);
            g.dispose();
        }

        /** icon 10x | model render with the same framing (8 samples per icon px) | all frames 4x */
        static void sheet(Path out, List<int[]> frames, List<Quad> model, double[] view, double[] fit) throws IOException {
            int z = 10, pad = 14;
            int cols = Math.min(frames.size(), 8);
            int rows = (frames.size() + cols - 1) / cols;
            int W = pad + 2 * (N * z + pad) + cols * (N * 4 + 6);
            BufferedImage im = new BufferedImage(W, Math.max(N * z, rows * (N * 4 + 6)) + 2 * pad + 20, BufferedImage.TYPE_INT_ARGB);
            fill(im, 0, 0, im.getWidth(), im.getHeight(), 0xFF202024);
            zoom(im, img(frames.get(0)), pad, pad + 20, z, true);
            text(im, "icon frame 0 (10x)", pad, 18);
            int R = N * z;
            int[] r = raw(model, view, fit, R, R, z);
            BufferedImage ri = new BufferedImage(R, R, BufferedImage.TYPE_INT_ARGB);
            for (int p = 0; p < R * R; p++) ri.setRGB(p % R, p / R, r[p]);
            int x1 = pad + R + pad;
            fill(im, x1, pad + 20, R, R, 0xFF5A6E8C);
            for (int y = 0; y < R; y++) for (int x = 0; x < R; x++) if ((r[y * R + x] >>> 24) != 0) im.setRGB(x1 + x, pad + 20 + y, r[y * R + x]);
            // icon pixel grid over the render
            for (int k = 0; k <= N; k++) for (int t = 0; t < R; t++) {
                int gx = Math.min(R - 1, k * z);
                im.setRGB(x1 + gx, pad + 20 + t, blend(im.getRGB(x1 + gx, pad + 20 + t), 0x30FFFFFF));
                im.setRGB(x1 + t, pad + 20 + gx, blend(im.getRGB(x1 + t, pad + 20 + gx), 0x30FFFFFF));
            }
            text(im, "3D model, same framing", x1, 18);
            int x2 = x1 + R + pad;
            text(im, frames.size() + " frames (4x)", x2, 18);
            for (int i = 0; i < frames.size(); i++) zoom(im, img(frames.get(i)), x2 + (i % cols) * (N * 4 + 6), pad + 20 + (i / cols) * (N * 4 + 6), 4, true);
            ImageIO.write(im, "png", out.toFile());
            System.out.println("preview " + out);
        }

        static int blend(int bg, int p) {
            int a = p >>> 24;
            int r = (((p >> 16) & 255) * a + ((bg >> 16) & 255) * (255 - a)) / 255;
            int gg = (((p >> 8) & 255) * a + ((bg >> 8) & 255) * (255 - a)) / 255;
            int b = ((p & 255) * a + (bg & 255) * (255 - a)) / 255;
            return 0xFF000000 | (r << 16) | (gg << 8) | b;
        }

        /** hotbar mock: old pair vs new pair at GUI scale 1 (two nearest pickings), 2, 3 and 10x of both new icons */
        static void pairSheet(Path out, Path oldDir, Path tex) throws IOException {
            BufferedImage os = first(oldDir.resolve("blood_sword.png")), om = first(oldDir.resolve("blood_mace.png"));
            BufferedImage ns = first(tex.resolve("item/blood_sword.png")), nm = first(tex.resolve("item/blood_mace.png"));
            int pad = 14;
            BufferedImage im = new BufferedImage(1100, 860, BufferedImage.TYPE_INT_ARGB);
            fill(im, 0, 0, im.getWidth(), im.getHeight(), 0xFF202024);
            int y = pad;
            String[] lab = {"GUI 1x (pick A)", "GUI 1x (pick B)", "GUI 2x", "GUI 3x", "GUI 4x"};
            int[] size = {16, 16, 32, 48, 64};
            double[] ph = {0.0, 0.99, 0.5, 0.5, 0.5};
            for (int k = 0; k < size.length; k++) {
                int s = size[k], slot = s + s / 8 * 2 + 2;
                text(im, lab[k], pad, y + 14);
                int x = pad + 130;
                for (int pair = 0; pair < 2; pair++) {
                    BufferedImage a = pair == 0 ? os : ns, b = pair == 0 ? om : nm;
                    // hotbar strip: slot frame like vanilla (dark grey with lighter inner)
                    for (int i = 0; i < 4; i++) {
                        fill(im, x + i * slot, y, slot, slot, 0xFF373737);
                        fill(im, x + i * slot + 1, y + 1, slot - 2, slot - 2, 0xFF8B8B8B);
                    }
                    scaled(im, a, x + (slot - s) / 2, y + (slot - s) / 2, s, ph[k]);
                    scaled(im, b, x + slot + (slot - s) / 2, y + (slot - s) / 2, s, ph[k]);
                    text(im, pair == 0 ? "old" : "new", x, y - 1 + slot + 14);
                    x += 4 * slot + 40;
                }
                y += s + s / 8 * 2 + 2 + 24;
            }
            zoom(im, ns, pad, y, 10, true);
            zoom(im, nm, pad + 330, y, 10, true);
            zoom(im, os, pad + 680, y, 5, true);
            zoom(im, om, pad + 680 + 165, y, 5, true);
            ImageIO.write(im, "png", out.toFile());
            System.out.println("preview " + out);
        }

        /** experiment: "ax,deg;ax,deg|..." views (axis x/y/z or d = the vanilla diagonal), each rendered as raw icon (8x) + outlined icon at 2x */
        static void variants(Path out, List<Quad> model, String spec) throws IOException {
            String[] vs = spec.split("\\|");
            int z = 8, cw = N * z + 20;
            BufferedImage im = new BufferedImage(vs.length * cw + 20, N * z + 140, BufferedImage.TYPE_INT_ARGB);
            fill(im, 0, 0, im.getWidth(), im.getHeight(), 0xFF202024);
            for (int k = 0; k < vs.length; k++) {
                double[] v = id();
                for (String part : vs[k].split(";")) {
                    if (part.isBlank()) continue;
                    String[] p = part.split(",");
                    double d = Double.parseDouble(p[1]);
                    double[] r = switch (p[0]) {
                        case "x" -> Rx(d); case "y" -> Ry(d); case "z" -> Rz(d);
                        case "X" -> upright(Rx(d)); case "Y" -> upright(Ry(d));
                        default -> mul(Rz(-45), Ry(d), Rz(45)); // around the screen diagonal (blade axis after -45 deg z)
                    };
                    v = mul(r, v);
                }
                double[] f = fit(model, v, 1, 0, 0);
                int[] g = outlined(render(model, v, f, 0.45), 0xFF0A0C12);
                text(im, vs[k], 20 + k * cw, 18);
                zoom(im, img(g), 20 + k * cw, 26, z, true);
                fill(im, 20 + k * cw, 40 + N * z, 72, 72, 0xFF8B8B8B);
                scaled(im, img(g), 20 + k * cw + 4, 44 + N * z, 64, 0.5);
                scaled(im, img(g), 20 + k * cw + 90, 44 + N * z, 32, 0.5);
            }
            ImageIO.write(im, "png", out.toFile());
        }

        /** 20x zoom with a pixel grid and coordinates (for hand polishing) */
        static void grid(Path out, int[] g) throws IOException {
            int z = 20, o = 24;
            BufferedImage im = new BufferedImage(N * z + o + 4, N * z + o + 4, BufferedImage.TYPE_INT_ARGB);
            fill(im, 0, 0, im.getWidth(), im.getHeight(), 0xFF202024);
            zoom(im, img(g), o, o, z, true);
            for (int k = 0; k <= N; k++) for (int t = 0; t < N * z; t++) {
                int c = k % 4 == 0 ? 0x90FFFFFF : 0x40FFFFFF;
                int gx = Math.min(N * z - 1, k * z);
                im.setRGB(o + gx, o + t, blend(im.getRGB(o + gx, o + t), c));
                im.setRGB(o + t, o + gx, blend(im.getRGB(o + t, o + gx), c));
            }
            java.awt.Graphics2D gr = im.createGraphics();
            gr.setColor(java.awt.Color.WHITE);
            gr.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 10));
            for (int k = 0; k < N; k++) { gr.drawString("" + k, o + k * z + 4, 16); gr.drawString("" + k, 2, o + k * z + 14); }
            gr.dispose();
            ImageIO.write(im, "png", out.toFile());
        }

        static BufferedImage first(Path p) throws IOException { BufferedImage b = ImageIO.read(p.toFile()); return b.getSubimage(0, 0, 32, 32); }
    }
}
