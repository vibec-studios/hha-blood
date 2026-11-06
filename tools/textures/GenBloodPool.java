import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Blood Trail pool: a flat, round pool of blood (model hha_blood:item/blood_pool), lying in the X/Z
 * plane at model y = 8 (centre of the item display). 32x32 texture, 6 animation frames: ripples
 * running outwards and wet glints drifting over the surface. Irregular, splashy outline (alpha cutout).
 *
 * Run: java tools/textures/GenBloodPool.java .
 */
public class GenBloodPool {
    static final int N = 32, FRAMES = 6;

    static double hash(int x, int y) {
        long h = x * 374761393L + y * 668265263L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535.0;
    }

    static int mix(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    public static void main(String[] args) throws Exception {
        Path assets = Path.of(args.length > 0 ? args[0] : ".").resolve("blood/src/main/resources/assets/hha_blood");
        BufferedImage strip = new BufferedImage(N, N * FRAMES, BufferedImage.TYPE_INT_ARGB);
        double c = (N - 1) / 2.0;
        for (int f = 0; f < FRAMES; f++) {
            double phase = f / (double) FRAMES;
            for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
                double dx = x - c, dy = y - c;
                double r = Math.sqrt(dx * dx + dy * dy);
                double ang = Math.atan2(dy, dx);
                // splashy outline: radius wobbles with angle, plus a few droplets outside
                double edge = 13.4 + 0.6 * Math.sin(ang * 3 + 0.7) + 0.5 * Math.sin(ang * 7 + 2.1) + 0.35 * Math.sin(ang * 11 + 4.0);
                boolean droplet = r > edge + 0.8 && r < edge + 2.6 && hash(x, y) > 0.86;
                if (r > edge && !droplet) continue;
                double t = Math.min(1, r / edge);
                int col = mix(0xFF2A0306, 0xFF7A0A12, Math.pow(t, 1.6));   // deep centre -> redder edge
                if (r > edge - 1.3 && !droplet) col = 0xFF9C1420;              // thick rim
                // ripples running outwards
                double ripple = Math.sin((r / 3.2 - phase) * Math.PI * 2);
                if (ripple > 0.82 && r < edge - 1.5) col = mix(col, 0xFFB01A28, 0.55);
                // drifting glints (wet surface)
                double gx = c + Math.cos(phase * Math.PI * 2) * 4 - 3, gy = c + Math.sin(phase * Math.PI * 2) * 3 - 4;
                double gd = Math.hypot(x - gx, y - gy);
                if (gd < 1.6) col = mix(col, 0xFFFF8A8A, 0.75 - gd * 0.3);
                if (hash(x + f * 7, y) > 0.985 && r < edge - 2) col = 0xFFE0404A;
                if (droplet) col = 0xFF7A0A12;
                strip.setRGB(x, y + f * N, col);
            }
        }
        Files.createDirectories(assets.resolve("textures/item"));
        ImageIO.write(strip, "png", assets.resolve("textures/item/blood_pool.png").toFile());
        Files.writeString(assets.resolve("textures/item/blood_pool.png.mcmeta"),
            "{\"animation\":{\"frametime\":3,\"interpolate\":true}}\n", StandardCharsets.UTF_8);

        String model = "{\n"
            + "  \"ambientocclusion\": false,\n"
            + "  \"textures\": {\"particle\": \"hha_blood:item/blood_pool\", \"tex\": \"hha_blood:item/blood_pool\"},\n"
            + "  \"elements\": [\n"
            + "    {\"from\": [0, 7.98, 0], \"to\": [16, 8.02, 16], \"faces\": {\n"
            + "      \"up\": {\"uv\": [0, 0, 16, 16], \"texture\": \"#tex\"},\n"
            + "      \"down\": {\"uv\": [0, 0, 16, 16], \"texture\": \"#tex\"}\n"
            + "    }}\n"
            + "  ]\n}\n";
        Files.createDirectories(assets.resolve("models/item"));
        Files.writeString(assets.resolve("models/item/blood_pool.json"), model, StandardCharsets.UTF_8);
        Files.createDirectories(assets.resolve("items"));
        Files.writeString(assets.resolve("items/blood_pool.json"),
            "{\n  \"model\": {\n    \"type\": \"minecraft:model\",\n    \"model\": \"hha_blood:item/blood_pool\"\n  }\n}\n", StandardCharsets.UTF_8);
        System.out.println("wrote blood_pool model, item definition and " + FRAMES + "-frame texture");
    }
}
