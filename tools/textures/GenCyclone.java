import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 3D models for the Blood Cyclone ability (rendered by item display entities, display context NONE):
 *   - blood_mace_3d : voxel mace standing upright along +Y, spin axis x=8,z=8, bounding-box centre (8,8,8)
 *   - blood_bolt    : glossy blood spike pointing along +Z, centred on (8,8,8)
 * Writes models/item/*.json, textures/item/*.png (UV atlas) and items/*.json, plus a preview sheet.
 *
 * Run: java tools/textures/GenCyclone.java . [preview.png]
 */
public class GenCyclone {

    // ---------------------------------------------------------------- palettes
    /** dark netherite / plum ramp of the mace head (matches blood_mace.png) */
    static final int[] IRON = {0x0B0708, 0x171113, 0x221B1E, 0x2F272B, 0x3E3439, 0x53474D, 0x6C5F65, 0x857780};
    /** crimson ramp (black 1B0305, dark 4A0509, blood 9B0A12, bright D81E26) */
    static final int[] CRIM = {0x1B0305, 0x4A0509, 0x6E0910, 0x9B0A12, 0xBA121B, 0xD81E26, 0xEE3E44, 0xFF8078};
    /** brighter, more saturated ramp for the projectile (full-bright) */
    static final int[] BOLT = {0x3A0307, 0x5E060D, 0x8C0A14, 0xB5121C, 0xD81E26, 0xF0363C, 0xFF6A66, 0xFFC4BC};

    static final String[] DIRS = {"north", "south", "east", "west", "up", "down"};

    // ---------------------------------------------------------------- model data
    static class Box {
        final String name, mat;
        final double[] f, t;
        String axis;
        double angle;
        double[] origin;
        double[] out = {0, 0, 0}; // outward direction (local, before element rotation)
        final Set<String> skip = new HashSet<>();
        final Map<String, int[]> region = new LinkedHashMap<>(); // dir -> ax, ay, tw, th
        final Map<String, double[]> uv = new LinkedHashMap<>();

        Box(String name, String mat, double x1, double y1, double z1, double x2, double y2, double z2) {
            this.name = name; this.mat = mat;
            f = new double[]{x1, y1, z1}; t = new double[]{x2, y2, z2};
        }
        Box rot(String axis, double angle, double ox, double oy, double oz) {
            this.axis = axis; this.angle = angle; origin = new double[]{ox, oy, oz}; return this;
        }
        Box skip(String... d) { skip.addAll(Arrays.asList(d)); return this; }
        Box out(double x, double y, double z) { out = new double[]{x, y, z}; return this; }
    }

    interface Painter { int paint(Model m, Box b, String dir, double[] p, int i, int j, int tw, int th); }

    static class Model {
        final String name;
        final List<Box> boxes = new ArrayList<>();
        BufferedImage tex;
        int size;
        double[] min = new double[3], max = new double[3];
        Model(String name) { this.name = name; }
        Box add(Box b) { boxes.add(b); return b; }
        Box find(String n) { for (Box b : boxes) if (b.name.equals(n)) return b; throw new IllegalArgumentException(n); }
    }

    // ---------------------------------------------------------------- geometry helpers
    static double[] faceWH(Box b, String d) {
        double dx = b.t[0] - b.f[0], dy = b.t[1] - b.f[1], dz = b.t[2] - b.f[2];
        switch (d) {
            case "north": case "south": return new double[]{dx, dy};
            case "east": case "west": return new double[]{dz, dy};
            default: return new double[]{dx, dz};
        }
    }

    /** point on a face for (s,t) in [0,1]: s follows +u, t follows +v (vanilla UV orientation). */
    static double[] facePoint(Box b, String d, double s, double t) {
        double[] f = b.f, to = b.t;
        double dx = to[0] - f[0], dy = to[1] - f[1], dz = to[2] - f[2];
        switch (d) {
            case "north": return new double[]{to[0] - s * dx, to[1] - t * dy, f[2]};
            case "south": return new double[]{f[0] + s * dx, to[1] - t * dy, to[2]};
            case "west":  return new double[]{f[0], to[1] - t * dy, f[2] + s * dz};
            case "east":  return new double[]{to[0], to[1] - t * dy, to[2] - s * dz};
            case "up":    return new double[]{f[0] + s * dx, to[1], f[2] + t * dz};
            default:      return new double[]{f[0] + s * dx, f[1], to[2] - t * dz};
        }
    }

    static double[] normal(String d) {
        switch (d) {
            case "north": return new double[]{0, 0, -1};
            case "south": return new double[]{0, 0, 1};
            case "west":  return new double[]{-1, 0, 0};
            case "east":  return new double[]{1, 0, 0};
            case "up":    return new double[]{0, 1, 0};
            default:      return new double[]{0, -1, 0};
        }
    }

    /** element rotation (single axis, around origin) */
    static double[] elemRot(Box b, double[] p, boolean isNormal) {
        if (b.axis == null) return p;
        double a = Math.toRadians(b.angle), c = Math.cos(a), s = Math.sin(a);
        double[] o = isNormal ? new double[]{0, 0, 0} : b.origin;
        double x = p[0] - o[0], y = p[1] - o[1], z = p[2] - o[2];
        double nx = x, ny = y, nz = z;
        switch (b.axis) {
            case "y": nx = x * c + z * s; nz = -x * s + z * c; break;
            case "x": ny = y * c - z * s; nz = y * s + z * c; break;
            case "z": nx = x * c - y * s; ny = x * s + y * c; break;
        }
        return new double[]{nx + o[0], ny + o[1], nz + o[2]};
    }

    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static double[] norm(double x, double y, double z) { double l = Math.sqrt(x * x + y * y + z * z); return l == 0 ? new double[]{0, 1, 0} : new double[]{x / l, y / l, z / l}; }
    static double clamp(double v, double a, double b) { return Math.max(a, Math.min(b, v)); }
    static int hash(int a, int b, int c) { int h = a * 73856093 ^ b * 19349663 ^ c * 83492791; h ^= h >>> 13; h *= 0x5bd1e995; h ^= h >>> 15; return h & 0x7fffffff; }
    static int ramp(int[] r, double idx) { return 0xFF000000 | r[(int) clamp(Math.round(idx), 0, r.length - 1)]; }

    /** fake light: top-left-front brighter, bottom/right darker */
    static double dirBonus(String d) {
        switch (d) {
            case "up": return 1.5;
            case "down": return -1.6;
            case "north": case "west": return 0.4;
            default: return -0.4;
        }
    }

    // ---------------------------------------------------------------- bounding box + centring
    static void computeBounds(Model m) {
        Arrays.fill(m.min, Double.MAX_VALUE); Arrays.fill(m.max, -Double.MAX_VALUE);
        for (Box b : m.boxes)
            for (int k = 0; k < 8; k++) {
                double[] p = {(k & 1) == 0 ? b.f[0] : b.t[0], (k & 2) == 0 ? b.f[1] : b.t[1], (k & 4) == 0 ? b.f[2] : b.t[2]};
                p = elemRot(b, p, false);
                for (int a = 0; a < 3; a++) { m.min[a] = Math.min(m.min[a], p[a]); m.max[a] = Math.max(m.max[a], p[a]); }
            }
    }

    static void centre(Model m) {
        computeBounds(m);
        double[] sh = new double[3];
        for (int a = 0; a < 3; a++) sh[a] = Math.round((8 - (m.min[a] + m.max[a]) / 2) * 4) / 4.0; // quarter-pixel steps
        for (Box b : m.boxes) {
            for (int a = 0; a < 3; a++) { b.f[a] += sh[a]; b.t[a] += sh[a]; if (b.origin != null) b.origin[a] += sh[a]; }
        }
        computeBounds(m);
        System.out.printf("%s: bounds [%.2f %.2f %.2f] .. [%.2f %.2f %.2f], centre (%.2f %.2f %.2f)%n", m.name,
            m.min[0], m.min[1], m.min[2], m.max[0], m.max[1], m.max[2],
            (m.min[0] + m.max[0]) / 2, (m.min[1] + m.max[1]) / 2, (m.min[2] + m.max[2]) / 2);
        for (Box b : m.boxes) for (int a = 0; a < 3; a++)
            if (b.f[a] < -16 || b.t[a] > 32) throw new IllegalStateException("element out of -16..32: " + b.name);
    }

    // ---------------------------------------------------------------- atlas packing + painting
    static void buildTexture(Model m, Painter painter) {
        record F(Box b, String d, int tw, int th) {}
        List<F> faces = new ArrayList<>();
        for (Box b : m.boxes)
            for (String d : DIRS) {
                if (b.skip.contains(d)) continue;
                double[] wh = faceWH(b, d);
                faces.add(new F(b, d, Math.max(1, (int) Math.round(wh[0])), Math.max(1, (int) Math.round(wh[1]))));
            }
        faces.sort((a, c) -> a.th != c.th ? c.th - a.th : c.tw - a.tw);
        for (int size : new int[]{32, 64, 128}) {
            int x = 0, y = 0, shelf = 0;
            boolean ok = true;
            Map<F, int[]> pos = new LinkedHashMap<>();
            for (F fc : faces) {
                if (x + fc.tw > size) { x = 0; y += shelf; shelf = 0; }
                if (y + fc.th > size) { ok = false; break; }
                pos.put(fc, new int[]{x, y});
                x += fc.tw; shelf = Math.max(shelf, fc.th);
            }
            if (!ok) continue;
            m.size = size;
            m.tex = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            double sc = size / 16.0;
            for (F fc : faces) {
                int[] p = pos.get(fc);
                fc.b.region.put(fc.d, new int[]{p[0], p[1], fc.tw, fc.th});
                fc.b.uv.put(fc.d, new double[]{p[0] / sc, p[1] / sc, (p[0] + fc.tw) / sc, (p[1] + fc.th) / sc});
                for (int j = 0; j < fc.th; j++)
                    for (int i = 0; i < fc.tw; i++) {
                        double[] pt = facePoint(fc.b, fc.d, (i + 0.5) / fc.tw, (j + 0.5) / fc.th);
                        m.tex.setRGB(p[0] + i, p[1] + j, painter.paint(m, fc.b, fc.d, pt, i, j, fc.tw, fc.th));
                    }
            }
            System.out.printf("%s: %d faces packed into %dx%d (used height %d)%n", m.name, faces.size(), size, size, y + shelf);
            return;
        }
        throw new IllegalStateException("atlas overflow for " + m.name);
    }

    // ---------------------------------------------------------------- the mace
    static Model buildMace() {
        Model m = new Model("blood_mace_3d");
        double hy = 13; // head centre before centring
        // head: 6-cube core + three 7x4x4 plates -> rounded, octagonal silhouette
        m.add(new Box("head_core", "head", 5, hy - 3, 5, 11, hy + 3, 11));
        m.add(new Box("head_x", "head", 4.5, hy - 2, 6, 11.5, hy + 2, 10));
        m.add(new Box("head_y", "head", 6, hy - 3.5, 6, 10, hy + 3.5, 10));
        m.add(new Box("head_z", "head", 6, hy - 2, 4.5, 10, hy + 2, 11.5));
        // four big spikes around the equator (base 2x2x1.5 + tip 1x1x1.5)
        m.add(new Box("spike_e", "spike", 11.5, hy - 1, 7, 13, hy + 1, 9).skip("west").out(1, 0, 0));
        m.add(new Box("spike_e_tip", "spike", 13, hy - 0.5, 7.5, 14.5, hy + 0.5, 8.5).skip("west").out(1, 0, 0));
        m.add(new Box("spike_w", "spike", 3, hy - 1, 7, 4.5, hy + 1, 9).skip("east").out(-1, 0, 0));
        m.add(new Box("spike_w_tip", "spike", 1.5, hy - 0.5, 7.5, 3, hy + 0.5, 8.5).skip("east").out(-1, 0, 0));
        m.add(new Box("spike_s", "spike", 7, hy - 1, 11.5, 9, hy + 1, 13).skip("north").out(0, 0, 1));
        m.add(new Box("spike_s_tip", "spike", 7.5, hy - 0.5, 13, 8.5, hy + 0.5, 14.5).skip("north").out(0, 0, 1));
        m.add(new Box("spike_n", "spike", 7, hy - 1, 3, 9, hy + 1, 4.5).skip("south").out(0, 0, -1));
        m.add(new Box("spike_n_tip", "spike", 7.5, hy - 0.5, 1.5, 8.5, hy + 0.5, 3).skip("south").out(0, 0, -1));
        // crown spike on top
        m.add(new Box("spike_top", "spike", 7, hy + 3.5, 7, 9, hy + 4.75, 9).skip("down").out(0, 1, 0));
        m.add(new Box("spike_top_tip", "spike", 7.5, hy + 4.75, 7.5, 8.5, hy + 6, 8.5).skip("down").out(0, 1, 0));
        // four smaller diagonal spikes on the upper shoulder (+X / -X spikes rotated 45 deg around the spin axis)
        double dy = hy + 1.75;
        for (int k = 0; k < 4; k++) {
            boolean east = k < 2;
            double ang = (k % 2 == 0) ? 45 : -45;
            String n = "spike_d" + k;
            if (east) {
                m.add(new Box(n, "spike", 11.25, dy - 0.75, 7.25, 12.75, dy + 0.75, 8.75).skip("west").out(1, 0, 0).rot("y", ang, 8, dy, 8));
                m.add(new Box(n + "_tip", "spike", 12.75, dy - 0.5, 7.5, 13.75, dy + 0.5, 8.5).skip("west").out(1, 0, 0).rot("y", ang, 8, dy, 8));
            } else {
                m.add(new Box(n, "spike", 3.25, dy - 0.75, 7.25, 4.75, dy + 0.75, 8.75).skip("east").out(-1, 0, 0).rot("y", ang, 8, dy, 8));
                m.add(new Box(n + "_tip", "spike", 2.25, dy - 0.5, 7.5, 3.25, dy + 0.5, 8.5).skip("east").out(-1, 0, 0).rot("y", ang, 8, dy, 8));
            }
        }
        // collar, haft with crimson wraps, pommel
        m.add(new Box("collar", "collar", 5.5, hy - 5, 5.5, 10.5, hy - 3.5, 10.5));
        m.add(new Box("collar_low", "dark", 6.5, hy - 5.75, 6.5, 9.5, hy - 5, 9.5).skip("up"));
        m.add(new Box("haft", "haft", 7, 1.5, 7, 9, hy - 5.75, 9).skip("up", "down"));
        m.add(new Box("wrap_hi", "wrap", 6.75, 5, 6.75, 9.25, 6, 9.25));
        m.add(new Box("wrap_lo", "wrap", 6.75, 2.5, 6.75, 9.25, 3.5, 9.25));
        m.add(new Box("pommel", "dark", 6.5, 0, 6.5, 9.5, 1.5, 9.5));
        m.add(new Box("pommel_tip", "spike", 7.5, -1, 7.5, 8.5, 0, 8.5).skip("up").out(0, -1, 0));
        centre(m);
        return m;
    }

    static int paintMace(Model m, Box b, String d, double[] p, int i, int j, int tw, int th) {
        Box core = m.find("head_core");
        double[] hc = {(core.f[0] + core.t[0]) / 2, (core.f[1] + core.t[1]) / 2, (core.f[2] + core.t[2]) / 2};
        double bonus = dirBonus(d);
        boolean edge = i == 0 || j == 0 || i == tw - 1 || j == th - 1;
        boolean side = !d.equals("up") && !d.equals("down");
        switch (b.mat) {
            case "head": {
                double[] n = norm(p[0] - hc[0], p[1] - hc[1], p[2] - hc[2]);
                double[] l = norm(-0.25, 1.0, -0.2); // mostly from above: the head spins, so keep all sides readable
                double v = 0.5 + 0.5 * dot(n, l);
                double idx = 3.0 + v * 4.5 + bonus * 0.4;
                // crimson rib around the equator and thin veins from the spikes
                if (side && Math.abs(p[1] - hc[1]) < 0.5) return ramp(CRIM, 2.2 + v * 2.6);
                int h = hash((int) Math.floor(p[0] * 2), (int) Math.floor(p[1] * 2), (int) Math.floor(p[2] * 2) + d.hashCode());
                if (h % 29 == 0) return ramp(CRIM, 2 + v * 2);
                if (edge && !b.name.equals("head_core")) idx -= 0.9;
                if (h % 7 == 1) idx -= 0.6; // slight forged noise
                return ramp(IRON, idx);
            }
            case "spike": {
                double dist = (p[0] - hc[0]) * b.out[0] + (p[1] - hc[1]) * b.out[1] + (p[2] - hc[2]) * b.out[2];
                double fr = b.name.startsWith("pommel") ? 0.6 : clamp((dist - 3.3) / 3.0, 0, 1);
                double idx = 2.8 + fr * 2.6 + bonus * 0.7;
                double[] n = normal(d);
                if (dot(n, b.out) > 0.5) idx += 1.3; // the point itself catches the light
                return ramp(CRIM, idx);
            }
            case "collar": case "wrap": {
                double idx = (b.mat.equals("collar") ? 4.0 : 3.6) + bonus * 0.8;
                if (side && j == 0) idx += 1;
                if (side && j == th - 1) idx -= 1.4;
                return ramp(CRIM, idx);
            }
            case "haft": {
                double idx = 2.6 + bonus * 0.9;
                if (hash(i, (int) Math.floor(p[1] * 2), d.hashCode()) % 5 == 0) idx -= 0.8; // leather grain
                if (side && i == tw - 1) idx -= 0.7;
                return ramp(IRON, idx);
            }
            case "dark": default: {
                double idx = 2.8 + bonus * 0.9;
                if (side && j == th - 1) idx -= 1;
                if (side && j == 0) idx += 0.6;
                return ramp(IRON, idx);
            }
        }
    }

    // ---------------------------------------------------------------- the projectile
    static Model buildBolt() {
        Model m = new Model("blood_bolt");
        m.add(new Box("tail", "tail", 7, 7, 3, 9, 9, 4).skip("south"));
        m.add(new Box("core", "core", 6.5, 6.5, 4, 9.5, 9.5, 8.5));
        m.add(new Box("bulge_x", "core", 6, 7, 4.5, 10, 9, 7.5));
        m.add(new Box("bulge_y", "core", 7, 6, 4.5, 9, 10, 7.5));
        m.add(new Box("tip", "core", 7, 7, 8.5, 9, 9, 10).skip("north"));
        m.add(new Box("point", "point", 7.5, 7.5, 10, 8.5, 8.5, 11).skip("north"));
        centre(m);
        return m;
    }

    static int paintBolt(Model m, Box b, String d, double[] p, int i, int j, int tw, int th) {
        double tz = clamp((p[2] - m.min[2]) / (m.max[2] - m.min[2]), 0, 1);
        double bonus = dirBonus(d);
        switch (b.mat) {
            case "tail": return ramp(BOLT, 1.8 + bonus * 0.5);
            case "point": return ramp(BOLT, d.equals("south") ? 7 : 6 + bonus * 0.3);
            default: {
                double idx = 2.6 + tz * 3.0 + bonus * 0.5;
                // glossy streak: one texel line on the top of the core / tip, front half
                boolean top = d.equals("up") && (b.name.equals("core") || b.name.equals("tip") || b.name.equals("bulge_y"));
                if (top && tz > 0.35 && i == tw / 2 - (tw % 2 == 0 ? 1 : 0)) return ramp(BOLT, b.name.equals("tip") ? 7 : 6.4);
                if (d.equals("south")) idx += 0.8;
                return ramp(BOLT, idx);
            }
        }
    }

    // ---------------------------------------------------------------- JSON output
    static String num(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return Long.toString(Math.round(v));
        String s = String.format(Locale.ROOT, "%.4f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    static String arr(double... v) {
        StringBuilder sb = new StringBuilder("[");
        for (int k = 0; k < v.length; k++) { if (k > 0) sb.append(", "); sb.append(num(v[k])); }
        return sb.append(']').toString();
    }

    static String modelJson(Model m) {
        String tex = "hha_blood:item/" + m.name;
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"ambientocclusion\": false,\n");
        sb.append("  \"textures\": {\n    \"particle\": \"").append(tex).append("\",\n    \"tex\": \"").append(tex).append("\"\n  },\n");
        sb.append("  \"elements\": [\n");
        for (int k = 0; k < m.boxes.size(); k++) {
            Box b = m.boxes.get(k);
            sb.append("    {\n      \"name\": \"").append(b.name).append("\",\n");
            sb.append("      \"from\": ").append(arr(b.f)).append(",\n");
            sb.append("      \"to\": ").append(arr(b.t)).append(",\n");
            if (b.axis != null)
                sb.append("      \"rotation\": {\"angle\": ").append(num(b.angle)).append(", \"axis\": \"").append(b.axis)
                  .append("\", \"origin\": ").append(arr(b.origin)).append("},\n");
            sb.append("      \"faces\": {\n");
            int c = 0;
            for (Map.Entry<String, double[]> e : b.uv.entrySet()) {
                sb.append("        \"").append(e.getKey()).append("\": {\"uv\": ").append(arr(e.getValue())).append(", \"texture\": \"#tex\"}");
                sb.append(++c < b.uv.size() ? ",\n" : "\n");
            }
            sb.append("      }\n    }").append(k + 1 < m.boxes.size() ? ",\n" : "\n");
        }
        sb.append("  ]");
        if (m.name.equals("blood_mace_3d")) sb.append(",\n").append(MACE_HELD_DISPLAY);
        sb.append("\n}\n");
        return sb.toString();
    }

    /**
     * The 3D mace is also the HELD model (items/blood_mace.json uses it outside of gui/fixed/shelf).
     * Vanilla handheld_mace transforms, but z-rotation minus 45 degrees: the vanilla sprite lies on the
     * 45-degree diagonal, our model stands upright - display rotation pivots on the model centre, so
     * Rz(55) * Rz(-45) = Rz(10) puts the haft exactly where the vanilla mace handle is (grip ~3 px above
     * the pommel lands at sprite pixel (3,3), like vanilla). The item display of the Blood Cyclone uses
     * context NONE and is not affected.
     */
    static final String MACE_HELD_DISPLAY =
        "  \"display\": {\n"
        + "    \"thirdperson_righthand\": {\"rotation\": [0, -90, 10], \"translation\": [0, 4, 1], \"scale\": [1, 1, 1]},\n"
        + "    \"thirdperson_lefthand\": {\"rotation\": [0, 90, -10], \"translation\": [0, 4, 1], \"scale\": [1, 1, 1]},\n"
        + "    \"firstperson_righthand\": {\"rotation\": [0, -90, -20], \"translation\": [0, 4, 0.8], \"scale\": [0.72, 0.72, 0.72]},\n"
        + "    \"firstperson_lefthand\": {\"rotation\": [0, 90, 20], \"translation\": [0, 4, 0.8], \"scale\": [0.72, 0.72, 0.72]},\n"
        + "    \"ground\": {\"rotation\": [0, 0, 0], \"translation\": [0, 2, 0], \"scale\": [0.5, 0.5, 0.5]},\n"
        + "    \"head\": {\"rotation\": [0, 180, 0], \"translation\": [0, 13, 7], \"scale\": [1, 1, 1]}\n"
        + "  }";

    static String itemJson(Model m) {
        return "{\n  \"model\": {\n    \"type\": \"minecraft:model\",\n    \"model\": \"hha_blood:item/" + m.name + "\"\n  }\n}\n";
    }

    // ---------------------------------------------------------------- preview renderer
    static int sample(Model m, Box b, String d, double s, double t) {
        int[] r = b.region.get(d);
        int px = r[0] + (int) clamp(Math.floor(s * r[2]), 0, r[2] - 1);
        int py = r[1] + (int) clamp(Math.floor(t * r[3]), 0, r[3] - 1);
        return m.tex.getRGB(px, py);
    }

    static double[] view(double[] p, double roll, double yaw, double pitch, boolean isNormal) {
        double x = p[0], y = p[1], z = p[2];
        if (!isNormal) { x -= 8; y -= 8; z -= 8; }
        double cr = Math.cos(roll), sr = Math.sin(roll);
        double x1 = x * cr - y * sr, y1 = x * sr + y * cr, z1 = z;
        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        double x2 = x1 * cy + z1 * sy, z2 = -x1 * sy + z1 * cy;
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        double y3 = y1 * cp - z2 * sp, z3 = y1 * sp + z2 * cp;
        return new double[]{x2, y3, z3};
    }

    static void render(Model m, BufferedImage img, int x0, int y0, int cw, int ch, double scale,
                       double rollDeg, double yawDeg, double pitchDeg, int bg, boolean lit) {
        double roll = Math.toRadians(rollDeg), yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        double[] zb = new double[cw * ch];
        Arrays.fill(zb, -1e9);
        for (int y = 0; y < ch; y++) for (int x = 0; x < cw; x++) img.setRGB(x0 + x, y0 + y, bg);
        double[] light = norm(-0.35, 0.8, 0.6);
        int cx = cw / 2, cy = ch / 2;
        for (Box b : m.boxes)
            for (String d : DIRS) {
                if (b.skip.contains(d)) continue;
                double[] n = view(elemRot(b, normal(d), true), roll, yaw, pitch, true);
                if (n[2] <= 1e-4) continue;
                double br = lit ? 0.5 + 0.5 * Math.max(0, dot(n, light)) : 1.0;
                double[] wh = faceWH(b, d);
                int ns = (int) Math.ceil(wh[0] * scale * 1.7) + 1, nt = (int) Math.ceil(wh[1] * scale * 1.7) + 1;
                for (int a = 0; a < ns; a++)
                    for (int c = 0; c < nt; c++) {
                        double s = (a + 0.5) / ns, t = (c + 0.5) / nt;
                        double[] v = view(elemRot(b, facePoint(b, d, s, t), false), roll, yaw, pitch, false);
                        int px = (int) Math.floor(cx + v[0] * scale), py = (int) Math.floor(cy - v[1] * scale);
                        if (px < 0 || py < 0 || px >= cw || py >= ch) continue;
                        int k = py * cw + px;
                        if (v[2] < zb[k] - 1e-6) continue;
                        zb[k] = v[2];
                        int col = sample(m, b, d, s, t);
                        int rr = (int) Math.min(255, ((col >> 16) & 255) * br), gg = (int) Math.min(255, ((col >> 8) & 255) * br), bb = (int) Math.min(255, (col & 255) * br);
                        img.setRGB(x0 + px, y0 + py, 0xFF000000 | rr << 16 | gg << 8 | bb);
                    }
            }
    }

    static void preview(Model mace, Model bolt, Path out) throws Exception {
        int cell = 300, cols = 5, label = 18;
        int W = cell * cols, H = (cell + label) * 3;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x30343A)); g.fillRect(0, 0, W, H);
        int sky = 0xFF9AA9B8, night = 0xFF1E2228;
        String[] ml = {"mace front (from south)", "mace side (from east)", "mace top", "mace 3/4", "mace tilted 25deg, spinning"};
        double[][] mv = {{0, 0, 0}, {0, 90, 0}, {0, 0, 90}, {0, 35, 25}, {25, 60, 15}};
        for (int k = 0; k < 5; k++)
            render(mace, img, k * cell, label, cell, cell, 12, mv[k][0], mv[k][1], mv[k][2], sky, true);
        String[] bl = {"bolt side (+Z right)", "bolt top", "bolt from front", "bolt 3/4 (night)", "bolt 3/4 at 3px/px"};
        double[][] bv = {{0, 90, 0}, {0, 90, 90}, {0, 180, 0}, {0, 60, 25}, {0, 60, 25}};
        for (int k = 0; k < 5; k++)
            render(bolt, img, k * cell, 2 * label + cell, cell, cell, k == 4 ? 3 : 26, bv[k][0], bv[k][1], bv[k][2], k >= 3 ? night : sky, k != 4);
        // row 3: textures + small mace for scale
        int y3 = 3 * label + 2 * cell;
        render(mace, img, 0, y3, cell, cell, 4, 15, 40, 20, sky, true);
        render(mace, img, cell, y3, cell, cell, 4, 15, 40, 20, night, true);
        g.drawImage(scaleUp(mace.tex, 256 / mace.size), 2 * cell + 22, y3 + 22, null);
        g.drawImage(scaleUp(bolt.tex, 256 / bolt.size), 3 * cell + 22, y3 + 22, null);
        File origF = new File("blood/src/main/resources/assets/hha_blood/textures/item/blood_mace.png");
        BufferedImage orig = origF.exists() ? ImageIO.read(origF) : null;
        if (orig != null) g.drawImage(scaleUp(orig.getSubimage(0, 0, 16, 16), 16), 4 * cell + 22, y3 + 22, null);
        String[] l3 = {"mace small (day)", "mace small (night)", "mace_3d atlas " + mace.size + "px", "bolt atlas " + bolt.size + "px", "2D item texture (reference)"};
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        for (int k = 0; k < 5; k++) {
            g.drawString(ml[k], k * cell + 6, 14);
            g.drawString(bl[k], k * cell + 6, cell + label + 14);
            g.drawString(l3[k], k * cell + 6, 2 * cell + 2 * label + 14);
        }
        g.dispose();
        Files.createDirectories(out.getParent());
        ImageIO.write(img, "png", out.toFile());
        System.out.println("preview: " + out.toAbsolutePath());
    }

    static BufferedImage scaleUp(BufferedImage src, int f) {
        BufferedImage o = new BufferedImage(src.getWidth() * f, src.getHeight() * f, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < o.getHeight(); y++)
            for (int x = 0; x < o.getWidth(); x++) {
                int c = src.getRGB(x / f, y / f);
                boolean chk = ((x / 8) + (y / 8)) % 2 == 0;
                o.setRGB(x, y, (c >>> 24) == 0 ? (chk ? 0xFF555555 : 0xFF444444) : c);
            }
        return o;
    }

    // ---------------------------------------------------------------- main
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        Path assets = root.resolve("blood/src/main/resources/assets/hha_blood");
        Model mace = buildMace();
        buildTexture(mace, GenCyclone::paintMace);
        Model bolt = buildBolt();
        buildTexture(bolt, GenCyclone::paintBolt);
        for (Model m : List.of(mace, bolt)) {
            Files.createDirectories(assets.resolve("models/item"));
            Files.createDirectories(assets.resolve("textures/item"));
            Files.createDirectories(assets.resolve("items"));
            Files.writeString(assets.resolve("models/item/" + m.name + ".json"), modelJson(m), StandardCharsets.UTF_8);
            Files.writeString(assets.resolve("items/" + m.name + ".json"), itemJson(m), StandardCharsets.UTF_8);
            ImageIO.write(m.tex, "png", assets.resolve("textures/item/" + m.name + ".png").toFile());
        }
        if (args.length > 1) preview(mace, bolt, Path.of(args[1]));
    }
}
