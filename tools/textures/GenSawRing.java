import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * "Nihil" saw ring: a flat blood-saw ring (donut) with hooked teeth on the outside and an empty
 * centre, lying in the X/Z plane, centred on (8, 8, 8). Rendered by an item display around every
 * levitating Nihil victim (model hha_blood:item/blood_saw_ring, spun by the code around Y).
 *
 * Inner radius 7 px (hole of ~0.9 blocks at scale 1), blade band 7..10 px, teeth to ~12.5 px.
 * Built from rotated boxes - 1.21.11 allows any element rotation angle.
 *
 * Run: java tools/textures/GenSawRing.java .
 */
public class GenSawRing {
    static final int SEGMENTS = 24;
    static final double R_IN = 7.0, R_OUT = 10.0, TOOTH = 2.4;

    static String n(double v) {
        String s = String.format(Locale.ROOT, "%.3f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    static void box(StringBuilder sb, String name, double x0, double y0, double z0, double x1, double y1, double z1,
                    double angle, int[] uvTop, int[] uvSide, boolean last) {
        sb.append("    {\"name\": \"").append(name).append("\", \"from\": [").append(n(x0)).append(", ").append(n(y0)).append(", ").append(n(z0))
          .append("], \"to\": [").append(n(x1)).append(", ").append(n(y1)).append(", ").append(n(z1)).append("],\n");
        sb.append("      \"rotation\": {\"angle\": ").append(n(angle)).append(", \"axis\": \"y\", \"origin\": [8, 8, 8]},\n");
        sb.append("      \"faces\": {");
        String[] dirs = {"up", "down", "north", "south", "east", "west"};
        for (int i = 0; i < dirs.length; i++) {
            int[] uv = i < 2 ? uvTop : uvSide;
            sb.append("\"").append(dirs[i]).append("\": {\"uv\": [").append(uv[0]).append(", ").append(uv[1]).append(", ").append(uv[2]).append(", ").append(uv[3])
              .append("], \"texture\": \"#tex\"}").append(i < dirs.length - 1 ? ", " : "");
        }
        sb.append("}}").append(last ? "\n" : ",\n");
    }

    public static void main(String[] args) throws Exception {
        Path assets = Path.of(args.length > 0 ? args[0] : ".").resolve("blood/src/main/resources/assets/hha_blood");

        // ---- texture 16x16: rows 0-5 blade face, 6-7 sharpened outer edge, 8-11 teeth, 12-13 inner rim
        BufferedImage t = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        int[] blade = {0xFF5A0A10, 0xFF6E0C14, 0xFF84101A, 0xFF9B1420, 0xFF84101A, 0xFF6E0C14};
        for (int y = 0; y < 6; y++) for (int x = 0; x < 16; x++) {
            int c = blade[y];
            if ((x * 7 + y * 3) % 11 == 0) c = 0xFFB81E2A;          // wet glints
            if ((x + y * 5) % 13 == 0) c = 0xFF3A0508;              // dark pits
            t.setRGB(x, y, c);
        }
        for (int x = 0; x < 16; x++) { t.setRGB(x, 6, 0xFFE0303A); t.setRGB(x, 7, 0xFFFF6A6A); }
        int[] tooth = {0xFFC0202C, 0xFFE0383E, 0xFFFF7070, 0xFFFFC0B8};
        for (int y = 8; y < 12; y++) for (int x = 0; x < 16; x++) t.setRGB(x, y, tooth[y - 8]);
        for (int x = 0; x < 16; x++) { t.setRGB(x, 12, 0xFF2A0306); t.setRGB(x, 13, 0xFFD02434); }
        Files.createDirectories(assets.resolve("textures/item"));
        ImageIO.write(t, "png", assets.resolve("textures/item/blood_saw_ring.png").toFile());

        // ---- model
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"ambientocclusion\": false,\n");
        sb.append("  \"textures\": {\"particle\": \"hha_blood:item/blood_saw_ring\", \"tex\": \"hha_blood:item/blood_saw_ring\"},\n");
        sb.append("  \"elements\": [\n");
        double step = 360.0 / SEGMENTS;
        double w = 2 * R_OUT * Math.tan(Math.PI / SEGMENTS) + 0.3;   // tangential width incl. overlap
        int[] faceUv = {0, 0, 16, 6}, edgeUv = {0, 6, 16, 8}, toothUv = {0, 8, 16, 12}, rimUv = {0, 12, 16, 14};
        int total = SEGMENTS * 3, k = 0;
        for (int i = 0; i < SEGMENTS; i++) {
            double a = i * step;
            // blade band (1.6 px thick)
            box(sb, "band" + i, 8 - w / 2, 7.2, 8 + R_IN, 8 + w / 2, 8.8, 8 + R_OUT, a, faceUv, edgeUv, ++k == total);
            // inner rim, a bit thicker so the hole has a bright lip
            box(sb, "rim" + i, 8 - w * 0.36, 6.9, 8 + R_IN - 0.6, 8 + w * 0.36, 9.1, 8 + R_IN, a, rimUv, rimUv, ++k == total);
            // hooked tooth: leans in spin direction (+angle offset), thinner than the band
            box(sb, "tooth" + i, 8 - 0.9, 7.5, 8 + R_OUT - 0.3, 8 + 0.7, 8.5, 8 + R_OUT + TOOTH, a + step * 0.35, toothUv, toothUv, ++k == total);
        }
        sb.append("  ]\n}\n");
        Files.createDirectories(assets.resolve("models/item"));
        Files.writeString(assets.resolve("models/item/blood_saw_ring.json"), sb.toString(), StandardCharsets.UTF_8);
        Files.createDirectories(assets.resolve("items"));
        Files.writeString(assets.resolve("items/blood_saw_ring.json"),
            "{\n  \"model\": {\n    \"type\": \"minecraft:model\",\n    \"model\": \"hha_blood:item/blood_saw_ring\"\n  }\n}\n", StandardCharsets.UTF_8);
        System.out.println("wrote blood_saw_ring model (" + total + " elements), texture and item definition");
    }
}
