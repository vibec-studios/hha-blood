import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Nihil impaling thorn (model hha_blood:item/blood_spike): a twisted crystal of solid blood with an
 * eight-ridged star cross-section, barbs, and a clotted crown where it bursts out of the ground.
 * Points along +Z (the code rotates +Z to "up"), base at z = 0, tip at z = 16, centred on x = y = 8,
 * ~6.6 px across the main ridges at the base.
 *
 * The code scales the model non-uniformly (x/y ~3.4, z = height up to 12): the texture is painted per
 * texel from its 3D position, and every face gets texels in proportion to its size AFTER a typical
 * stretch (Z_STRETCH along z) - so texels stay roughly square in game, streaks run along the length and
 * the pattern continues seamlessly across the stacked, twisted segments.
 *
 * Run: java tools/textures/GenSpike.java .
 */
public class GenSpike {

    // ---------------------------------------------------------------- tuning
    static final int SEG = 28;               // stacked segments of the shaft
    static final double R0 = 3.3;            // corner radius at the base (main ridges)
    static final double TWIST = 30;         // degrees of twist from base to tip
    static final double Z_STRETCH = 2.4;     // assumed in-game z stretch relative to x/y (for texel density)
    static final double DENS = 4;            // texels per model px (across)

    /** blood-crystal ramp, near-black -> crimson -> hot pink-white */
    static final int[] RAMP = {
        0x0C0103, 0x170206, 0x24030A, 0x36050E, 0x4C0712, 0x660915, 0x820C19, 0x9E111D,
        0xB81822, 0xD0222A, 0xE43A3C, 0xF46256, 0xFF9282, 0xFFC4B6, 0xFFEDE6};

    static final String[] DIRS = {"north", "south", "east", "west", "up", "down"};

    // ---------------------------------------------------------------- model data
    static class Box {
        final String name, mat;
        final double[] f, t;
        String axis; double angle; double[] origin;
        final Set<String> skip = new HashSet<>();
        final Map<String, int[]> region = new LinkedHashMap<>();
        final Map<String, double[]> uv = new LinkedHashMap<>();
        double[] dir = {0, 0, 1}; // barbs / shards: their own pointing direction (model space, after rotation)
        Box(String name, String mat, double x1, double y1, double z1, double x2, double y2, double z2) {
            this.name = name; this.mat = mat; f = new double[]{x1, y1, z1}; t = new double[]{x2, y2, z2};
        }
        Box rot(String axis, double angle, double ox, double oy, double oz) { this.axis = axis; this.angle = angle; origin = new double[]{ox, oy, oz}; return this; }
        Box skip(String... d) { skip.addAll(Arrays.asList(d)); return this; }
    }

    static final List<Box> boxes = new ArrayList<>();
    static Box add(Box b) { boxes.add(b); return b; }

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

    static double clamp(double v, double a, double b) { return Math.max(a, Math.min(b, v)); }
    static double smooth(double e0, double e1, double v) { double x = clamp((v - e0) / (e1 - e0), 0, 1); return x * x * (3 - 2 * x); }

    // ---------------------------------------------------------------- noise
    static double hash(int x, int y, int s) {
        long h = x * 374761393L + y * 668265263L + s * 2147483647L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535.0;
    }
    /** value noise, x periodic with period px (so it wraps around the thorn) */
    static double noise(double x, double y, int px, int seed) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        double fx = x - x0, fy = y - y0;
        fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy);
        int xa = Math.floorMod(x0, px), xb = Math.floorMod(x0 + 1, px);
        double a = hash(xa, y0, seed), b = hash(xb, y0, seed), c = hash(xa, y0 + 1, seed), d = hash(xb, y0 + 1, seed);
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
    }

    static final int[][] BAYER = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};
    static int col(double idx, int i, int j) { return col(idx, i, j, true); }
    static int col(double idx, int i, int j, boolean dither) {
        double d = dither ? (BAYER[j & 3][i & 3] + 0.5) / 16.0 - 0.5 : 0;
        int k = (int) Math.floor(idx + 0.5 + d * 0.4);
        return 0xFF000000 | RAMP[(int) clamp(k, 0, RAMP.length - 1)];
    }

    // ---------------------------------------------------------------- shape
    /** corner radius of the shaft at height z */
    static double envelope(double z) {
        double t = clamp(z / 16.0, 0, 1);
        return 0.16 + (R0 - 0.16) * Math.pow(1 - t, 1.12);
    }
    static double twistAt(double z) { return TWIST * clamp(z / 16.0, 0, 1); }

    static void build() {
        double seg = 16.0 / SEG;
        for (int i = 0; i < SEG; i++) {
            double z0 = i * seg, z1 = Math.min(16, z0 + seg + 0.04);
            double r = envelope(z0 + seg * 0.35);
            double a = r / Math.sqrt(2);          // main square (4 big ridges)
            double b = a * 0.80;                  // second square, 45 deg off (4 smaller ridges)
            double th = twistAt(z0 + seg * 0.5);
            double angA = norm90(th), angB = norm90(th + 45);
            Box A = add(new Box("seg" + i, "shaft", 8 - a, 8 - a, z0, 8 + a, 8 + a, z1).rot("z", angA, 8, 8, 8));
            Box B = add(new Box("seg" + i + "b", "shaft", 8 - b, 8 - b, z0, 8 + b, 8 + b, z1).rot("z", angB, 8, 8, 8));
            if (i > 0) { A.skip("north"); B.skip("north"); }
        }
        // tip needle
        add(new Box("tip", "tip", 7.88, 7.88, 15.3, 8.12, 8.12, 16.15).rot("z", norm90(twistAt(16)), 8, 8, 8).skip("north"));

        // barbs: hooks growing out of the shaft and curling towards the tip
        double[][] barbs = { // side (0=+x 1=-x 2=+y 3=-y), z of the root, length
            {0, 3.2, 2.6}, {1, 5.4, 2.3}, {2, 7.4, 2.0}, {3, 4.3, 2.4}, {0, 9.4, 1.6}, {3, 10.8, 1.3}, {1, 2.0, 1.9}};
        for (int k = 0; k < barbs.length; k++) barb(k, (int) barbs[k][0], barbs[k][1], barbs[k][2]);

        // crown: clotted collar + shards bursting outward at the base
        // low twelve-pointed pool of clotted blood (three thin squares, 30 degrees apart)
        for (int k = 0; k < 3; k++)
            add(new Box("clot" + k, "clot", 8 - 3.0 + k * 0.25, 8 - 3.0 + k * 0.25, -0.05, 8 + 3.0 - k * 0.25, 8 + 3.0 - k * 0.25, 0.3 + k * 0.12)
                .rot("z", -30 + k * 30 + 7, 8, 8, 8).skip("north"));
        double[][] shards = { // side, offset along the side, length, width, lean
            {0, 1.0, 2.0, 1.1, 55}, {1, -0.8, 1.7, 1.0, 50}, {2, -1.4, 1.5, 0.9, 58}, {3, 1.6, 1.9, 1.1, 52}, {2, 1.6, 1.1, 0.7, 64}, {0, -1.5, 1.2, 0.7, 62}};
        for (int k = 0; k < shards.length; k++) shard(k, (int) shards[k][0], shards[k][1], shards[k][2], shards[k][3], shards[k][4]);
    }

    static double norm90(double a) { a = ((a % 90) + 90) % 90; return a > 45 ? a - 90 : a; }

    /** one barb: thick root + thin hooked tip, both tilted by the same rotation */
    static void barb(int k, int side, double z, double len) {
        double r = envelope(z) * 0.62;            // start inside the shaft
        double w = 0.5 + 0.18 * len, w2 = w * 0.55;
        double lean = 30;                         // degrees towards the tip
        Box root, tip;
        switch (side) {
            case 0: // +x, rotate around y: positive angle tilts +x towards -z, so use negative
                root = new Box("barb" + k, "barb", 8 + r, 8 - w / 2, z, 8 + r + len * 0.65, 8 + w / 2, z + w).rot("y", -lean, 8 + r, 8, z);
                tip = new Box("barb" + k + "t", "barb", 8 + r + len * 0.6, 8 - w2 / 2, z + (w - w2) / 2, 8 + r + len, 8 + w2 / 2, z + (w + w2) / 2).rot("y", -lean - 16, 8 + r, 8, z);
                break;
            case 1:
                root = new Box("barb" + k, "barb", 8 - r - len * 0.65, 8 - w / 2, z, 8 - r, 8 + w / 2, z + w).rot("y", lean, 8 - r, 8, z);
                tip = new Box("barb" + k + "t", "barb", 8 - r - len, 8 - w2 / 2, z + (w - w2) / 2, 8 - r - len * 0.6, 8 + w2 / 2, z + (w + w2) / 2).rot("y", lean + 16, 8 - r, 8, z);
                break;
            case 2: // +y, rotate around x: positive angle tilts +y towards +z
                root = new Box("barb" + k, "barb", 8 - w / 2, 8 + r, z, 8 + w / 2, 8 + r + len * 0.65, z + w).rot("x", lean, 8, 8 + r, z);
                tip = new Box("barb" + k + "t", "barb", 8 - w2 / 2, 8 + r + len * 0.6, z + (w - w2) / 2, 8 + w2 / 2, 8 + r + len, z + (w + w2) / 2).rot("x", lean + 16, 8, 8 + r, z);
                break;
            default:
                root = new Box("barb" + k, "barb", 8 - w / 2, 8 - r - len * 0.65, z, 8 + w / 2, 8 - r, z + w).rot("x", -lean, 8, 8 - r, z);
                tip = new Box("barb" + k + "t", "barb", 8 - w2 / 2, 8 - r - len, z + (w - w2) / 2, 8 + w2 / 2, 8 - r - len * 0.6, z + (w + w2) / 2).rot("x", -lean - 16, 8, 8 - r, z);
        }
        add(root); add(tip);
    }

    /** a short crystal shard leaning outward from the base */
    static void shard(int k, int side, double off, double len, double w, double lean) {
        double r = 2.2;
        Box s;
        switch (side) {
            case 0: s = new Box("shard" + k, "shard", 8 + r - w / 2, 8 + off - w / 2, -0.1, 8 + r + w / 2, 8 + off + w / 2, len).rot("y", lean, 8 + r, 8, 0); break;
            case 1: s = new Box("shard" + k, "shard", 8 - r - w / 2, 8 + off - w / 2, -0.1, 8 - r + w / 2, 8 + off + w / 2, len).rot("y", -lean, 8 - r, 8, 0); break;
            case 2: s = new Box("shard" + k, "shard", 8 + off - w / 2, 8 + r - w / 2, -0.1, 8 + off + w / 2, 8 + r + w / 2, len).rot("x", -lean, 8, 8 + r, 0); break;
            default: s = new Box("shard" + k, "shard", 8 + off - w / 2, 8 - r - w / 2, -0.1, 8 + off + w / 2, 8 - r + w / 2, len).rot("x", lean, 8, 8 - r, 0);
        }
        s.skip("north");
        add(s);
    }

    // ---------------------------------------------------------------- painting
    /** across-edge texel of a face (the long edges of shaft faces = the crystal ridges) */
    static boolean longEdge(String d, int i, int j, int tw, int th) {
        if (d.equals("east") || d.equals("west")) return j == 0 || j == th - 1;
        if (d.equals("up") || d.equals("down")) return i == 0 || i == tw - 1;
        return false;
    }
    static boolean anyEdge(int i, int j, int tw, int th) { return i == 0 || j == 0 || i == tw - 1 || j == th - 1; }

    /** angular distance in degrees, -180..180 */
    static double dAng(double a, double b) { return ((a - b) % 360 + 540) % 360 - 180; }

    /** helical veins: {angle at the base, end t, wiggle phase} - they follow the twist */
    static final double[][] VEINS = {{12, 0.93, 0.0}, {101, 0.78, 1.7}, {166, 0.88, 3.1}, {243, 0.70, 4.4}, {305, 0.83, 5.2}};
    /** wet trails of fresh blood: {angle, top t, bottom t} */
    static final double[][] TRAILS = {{60, 0.66, 0.20}, {200, 0.58, 0.28}, {283, 0.72, 0.36}};

    static int paint(Box b, String d, double[] p, double[] n, int i, int j, int tw, int th) {
        double x = p[0] - 8, y = p[1] - 8, z = p[2];
        double t = clamp(z / 16.0, 0, 1);
        double r = Math.hypot(x, y);
        double ang = Math.toDegrees(Math.atan2(y, x)) - twistAt(z);   // angle in the twisted frame
        double dMaj = Math.abs(((ang - 45) % 90 + 135) % 90 - 45);   // to the nearest major ridge (box A corners)
        double dMin = Math.abs((ang % 90 + 135) % 90 - 45);          // to the nearest minor ridge (box B corners)
        double fine = noise(ang / 15.0, t * 6, 24, 3);                // very soft organic variation
        // body colour along the length: black clot -> deep crimson -> glossy bright -> hot tip
        double body = 1.6 + 5.4 * smooth(0.02, 0.5, t) + 2.4 * smooth(0.45, 0.92, t) + 1.6 * smooth(0.9, 1.0, t);

        switch (b.mat) {
            case "clot": {
                // dark glossy pool of clotted blood, wet rim
                double idx = 1.6 + fine * 1.2;
                if (n[2] > 0.5) idx = 2.4 + fine * 1.4 + (r > 3.5 ? 2.2 : 0);
                if (anyEdge(i, j, tw, th) && Math.abs(n[2]) < 0.5) idx += 2.5;
                return col(idx, i, j);
            }
            case "barb": case "shard": {
                // own axis gradient: root dark like the shaft, point hot; edges darker so the form reads
                double[] o = b.origin;
                double h = b.mat.equals("shard")
                    ? clamp(z / ((b.t[2] - b.f[2]) * Math.cos(Math.toRadians(b.angle))), 0, 1)
                    : clamp(Math.hypot(p[0] - o[0], Math.hypot(p[1] - o[1], p[2] - o[2])) / 2.6, 0, 1);
                double start = b.mat.equals("shard") ? 2.6 : Math.max(3, body - 1.5);
                double idx = start + h * ((b.mat.equals("shard") ? 8.4 : 10.2) - start);
                if (n[2] > 0.35) idx += 0.8;           // faces looking towards the tip catch light
                if (n[2] < -0.35) idx -= 1.8;
                if (anyEdge(i, j, tw, th)) idx -= 1.2;
                if (h > 0.9) idx = 11.0;
                return col(idx, i, j, false);
            }
            case "tip": return col(12.4, i, j);
            default: {
                double idx = body + (fine - 0.5) * 0.8;
                boolean ledge = n[2] > 0.5;
                boolean isA = !b.name.endsWith("b");
                // facets: bright towards the major ridges, darker in the valleys -> faceted crystal
                double ridge = 1 - clamp(dMaj / 30.0, 0, 1), minor = 1 - clamp(dMin / 30.0, 0, 1);
                idx += ridge * ridge * 1.8 + minor * minor * 0.9 - 0.9;
                // crisp ridge lines: the long face edges (major = bright glint, minor = softer)
                if (!ledge && longEdge(d, i, j, tw, th) && t > 0.08) idx = Math.max(idx, body + (isA ? 2.0 : 1.1) + 0.6 * t);
                // glassy reflection: a broken thin line inside every main facet, upper part
                if (!ledge && t > 0.3 && Math.abs(dMaj - 29) < 1.6 && noise(ang / 30.0, t * 9, 12, 17) > 0.42) idx = Math.max(idx, body + 1.6);
                // inner glow: the middle of the facets lets the hot core shine through towards the top
                idx += smooth(0.45, 0.95, t) * clamp(1 - ridge - minor, 0, 1) * 1.2;
                // veins: follow the twist with a slow wiggle; dark in the bright upper part, glowing in the dark base
                for (double[] v : VEINS) {
                    if (t > v[1]) continue;
                    double va = v[0] + 9 * Math.sin(t * 13 + v[2]) + 4 * Math.sin(t * 31 + v[2] * 2);
                    double arc = Math.abs(Math.toRadians(dAng(ang, va))) * Math.max(r, 0.4);
                    double wv = 0.1 + 0.1 * (1 - t / v[1]);  // thinner towards their end
                    if (arc < wv) idx = t < 0.42 ? 9.6 + (1 - t / 0.42) * 0.8 : body - 3.2;
                    else if (arc < wv + 0.14 && t < 0.42) idx = Math.max(idx, 6.6 + (0.42 - t) * 3); // glow halo
                }
                // fresh blood running down: glossy trails ending in a drop
                for (double[] tr : TRAILS) {
                    if (t > tr[1] || t < tr[2] - 0.02) continue;
                    double wob = 0.22 + 0.06 * Math.sin(t * 50 + tr[0]);
                    if (t < tr[2] + 0.025) wob = 0.36;                 // the drop
                    double arc = Math.abs(Math.toRadians(dAng(ang, tr[0] + 5 * Math.sin(t * 17)))) * Math.max(r, 0.4);
                    if (arc < wob) idx = arc < wob * 0.4 ? 11.0 : 8.8;
                }
                // ledges (faces looking toward the tip): the game lights up-facing faces brightest anyway
                if (ledge) idx = body - 2.0;
                return col(idx, i, j);
            }
        }
    }

    // ---------------------------------------------------------------- atlas
    static int texSize;
    static BufferedImage tex;

    static void buildTexture() {
        record F(Box b, String d, int tw, int th) {}
        List<F> faces = new ArrayList<>();
        for (Box b : boxes)
            for (String d : DIRS) {
                if (b.skip.contains(d)) continue;
                double[] wh = faceWH(b, d);
                // texel counts after the typical in-game stretch (z grows Z_STRETCH times more than x/y)
                double su = (d.equals("east") || d.equals("west")) ? Z_STRETCH : 1;
                double sv = (d.equals("up") || d.equals("down")) ? Z_STRETCH : 1;
                if (b.axis != null && !b.axis.equals("z")) { su = sv = 1.6; } // barbs/shards: tilted, mixed
                int tw = Math.max(1, (int) Math.round(wh[0] * su * DENS)), th = Math.max(1, (int) Math.round(wh[1] * sv * DENS));
                faces.add(new F(b, d, tw, th));
            }
        faces.sort((a, c) -> a.th != c.th ? c.th - a.th : c.tw - a.tw);
        for (int size : new int[]{64, 128, 256}) {
            int x = 0, y = 0, shelf = 0; boolean ok = true;
            Map<F, int[]> pos = new LinkedHashMap<>();
            for (F fc : faces) {
                if (x + fc.tw > size) { x = 0; y += shelf; shelf = 0; }
                if (y + fc.th > size) { ok = false; break; }
                pos.put(fc, new int[]{x, y}); x += fc.tw; shelf = Math.max(shelf, fc.th);
            }
            if (!ok) continue;
            texSize = size;
            tex = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            double sc = size / 16.0;
            for (F fc : faces) {
                int[] p = pos.get(fc);
                fc.b.region.put(fc.d, new int[]{p[0], p[1], fc.tw, fc.th});
                fc.b.uv.put(fc.d, new double[]{p[0] / sc, p[1] / sc, (p[0] + fc.tw) / sc, (p[1] + fc.th) / sc});
                double[] nrm = elemRot(fc.b, normal(fc.d), true);
                for (int j = 0; j < fc.th; j++)
                    for (int i = 0; i < fc.tw; i++) {
                        double[] pt = elemRot(fc.b, facePoint(fc.b, fc.d, (i + 0.5) / fc.tw, (j + 0.5) / fc.th), false);
                        tex.setRGB(p[0] + i, p[1] + j, paint(fc.b, fc.d, pt, nrm, i, j, fc.tw, fc.th));
                    }
            }
            System.out.printf("blood_spike: %d elements, %d faces packed into %dx%d (used height %d)%n", boxes.size(), faces.size(), size, size, y + shelf);
            return;
        }
        throw new IllegalStateException("atlas overflow");
    }

    // ---------------------------------------------------------------- JSON
    static String num(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return Long.toString(Math.round(v));
        String s = String.format(Locale.ROOT, "%.4f", v).replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
    static String arr(double... v) {
        StringBuilder sb = new StringBuilder("[");
        for (int k = 0; k < v.length; k++) { if (k > 0) sb.append(", "); sb.append(num(v[k])); }
        return sb.append(']').toString();
    }

    static String modelJson() {
        String t = "hha_blood:item/blood_spike";
        StringBuilder sb = new StringBuilder("{\n  \"ambientocclusion\": false,\n");
        sb.append("  \"textures\": {\"particle\": \"").append(t).append("\", \"tex\": \"").append(t).append("\"},\n");
        sb.append("  \"elements\": [\n");
        for (int k = 0; k < boxes.size(); k++) {
            Box b = boxes.get(k);
            sb.append("    {\n      \"name\": \"").append(b.name).append("\",\n");
            sb.append("      \"from\": ").append(arr(b.f)).append(", \"to\": ").append(arr(b.t)).append(",\n");
            if (b.axis != null)
                sb.append("      \"rotation\": {\"angle\": ").append(num(b.angle)).append(", \"axis\": \"").append(b.axis).append("\", \"origin\": ").append(arr(b.origin)).append("},\n");
            sb.append("      \"faces\": {\n");
            int c = 0;
            for (Map.Entry<String, double[]> e : b.uv.entrySet())
                sb.append("        \"").append(e.getKey()).append("\": {\"uv\": ").append(arr(e.getValue())).append(", \"texture\": \"#tex\"}").append(++c < b.uv.size() ? ",\n" : "\n");
            sb.append("      }\n    }").append(k + 1 < boxes.size() ? ",\n" : "\n");
        }
        return sb.append("  ]\n}\n").toString();
    }

    public static void main(String[] args) throws Exception {
        Path assets = Path.of(args.length > 0 ? args[0] : ".").resolve("blood/src/main/resources/assets/hha_blood");
        build();
        for (Box b : boxes) for (int a = 0; a < 3; a++)
            if (b.f[a] < -16 || b.t[a] > 32) throw new IllegalStateException("element out of -16..32: " + b.name);
        buildTexture();
        Files.createDirectories(assets.resolve("textures/item"));
        ImageIO.write(tex, "png", assets.resolve("textures/item/blood_spike.png").toFile());
        Files.createDirectories(assets.resolve("models/item"));
        Files.writeString(assets.resolve("models/item/blood_spike.json"), modelJson(), StandardCharsets.UTF_8);
        Files.createDirectories(assets.resolve("items"));
        Files.writeString(assets.resolve("items/blood_spike.json"),
            "{\n  \"model\": {\n    \"type\": \"minecraft:model\",\n    \"model\": \"hha_blood:item/blood_spike\"\n  }\n}\n", StandardCharsets.UTF_8);
        System.out.println("wrote blood_spike model, texture and item definition");
    }
}
