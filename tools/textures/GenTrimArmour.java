import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/**
 * Blood armour = vanilla netherite armour with REDSTONE armour trims, exactly the way Minecraft
 * composites trims (paletted permutation of the grayscale trim pattern), with a slightly brighter,
 * "glowing" redstone palette:
 *   - helmet + boots:      Flow trim
 *   - chestplate + leggings: Silence trim (the rare Ancient City one)
 * Since helmet, chestplate and boots share ONE layer texture (humanoid), the pattern is chosen per UV
 * region: head/hat -> flow, body/arms -> silence, legs (boots) -> flow. The leggings layer is all silence.
 *
 * Item icons = vanilla netherite icon + vanilla item trim overlay in the same palette, with a slow
 * glowing pulse (2 frames, interpolated).
 *
 * Run: java tools/textures/GenTrimArmour.java . [path/to/minecraft-client.jar]
 */
public class GenTrimArmour {

    static ZipFile jar;
    static Path tex;

    static BufferedImage vanilla(String path) throws Exception {
        var e = jar.getEntry("assets/minecraft/textures/" + path);
        if (e == null) throw new IllegalStateException("missing in jar: " + path);
        try (InputStream in = jar.getInputStream(e)) {
            return raw(ImageIO.read(in));
        }
    }

    /**
     * Raw pixel values like Minecraft's NativeImage reads them. Java's getRGB()/drawImage() apply a
     * linear->sRGB conversion to grayscale PNGs, which would break exact palette matching.
     */
    static BufferedImage raw(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var r = src.getRaster();
        int bands = r.getNumBands();
        boolean indexed = src.getColorModel() instanceof java.awt.image.IndexColorModel;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int argb;
            if (indexed) argb = src.getRGB(x, y);
            else if (bands == 1) { int g = r.getSample(x, y, 0); argb = 0xFF000000 | (g << 16) | (g << 8) | g; }
            else if (bands == 2) { int g = r.getSample(x, y, 0), a = r.getSample(x, y, 1); argb = (a << 24) | (g << 16) | (g << 8) | g; }
            else if (bands == 3) argb = 0xFF000000 | (r.getSample(x, y, 0) << 16) | (r.getSample(x, y, 1) << 8) | r.getSample(x, y, 2);
            else argb = (r.getSample(x, y, 3) << 24) | (r.getSample(x, y, 0) << 16) | (r.getSample(x, y, 1) << 8) | r.getSample(x, y, 2);
            out.setRGB(x, y, argb);
        }
        return out;
    }

    /** Redstone palette, a bit brighter and more saturated so the trims read as glowing; [boost] scales it further. */
    static int[] palette(BufferedImage redstone, double boost) {
        int[] p = new int[redstone.getWidth()];
        for (int i = 0; i < p.length; i++) {
            int c = redstone.getRGB(i, 0);
            float[] hsb = Color.RGBtoHSB((c >> 16) & 255, (c >> 8) & 255, c & 255, null);
            float s = (float) Math.min(1.0, hsb[1] * 1.08 * (0.95 + 0.05 * boost));
            float b = (float) Math.min(1.0, hsb[2] * 1.12 * boost);
            p[i] = Color.HSBtoRGB(hsb[0], s, b) & 0xFFFFFF;
        }
        return p;
    }

    /** Paletted permutation: every pixel whose RGB matches key[i] becomes pal[i] (alpha kept). */
    static BufferedImage recolor(BufferedImage pattern, int[] key, int[] pal) {
        BufferedImage out = new BufferedImage(pattern.getWidth(), pattern.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < pattern.getHeight(); y++) for (int x = 0; x < pattern.getWidth(); x++) {
            int c = pattern.getRGB(x, y);
            int a = (c >>> 24);
            if (a == 0) continue;
            int rgb = c & 0xFFFFFF;
            int mapped = rgb;
            for (int i = 0; i < key.length; i++) if (key[i] == rgb) { mapped = pal[i]; break; }
            out.setRGB(x, y, (a << 24) | mapped);
        }
        return out;
    }

    static int over(int dst, int src) {
        int sa = src >>> 24;
        if (sa == 0) return dst;
        if (sa == 255) return src;
        int da = dst >>> 24;
        double a = sa / 255.0, b = da / 255.0 * (1 - a);
        double oa = a + b;
        int r = (int) Math.round((((src >> 16) & 255) * a + ((dst >> 16) & 255) * b) / oa);
        int g = (int) Math.round((((src >> 8) & 255) * a + ((dst >> 8) & 255) * b) / oa);
        int bl = (int) Math.round(((src & 255) * a + (dst & 255) * b) / oa);
        return ((int) Math.round(oa * 255) << 24) | (r << 16) | (g << 8) | bl;
    }

    /** base + trim, but only inside the given UV rectangle. */
    static void overlay(BufferedImage base, BufferedImage trim, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) base.setRGB(x, y, over(base.getRGB(x, y), trim.getRGB(x, y)));
    }

    /**
     * Glow version of a recoloured trim: additive blending adds this colour on top of the lit armour,
     * so it is pushed towards pure red (no green/blue -> no washed-out pink) and the darkest trim shades
     * are dropped so only the lines themselves light up.
     */
    static BufferedImage glow(BufferedImage trim) {
        BufferedImage out = new BufferedImage(trim.getWidth(), trim.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < trim.getHeight(); y++) for (int x = 0; x < trim.getWidth(); x++) {
            int c = trim.getRGB(x, y);
            if ((c >>> 24) == 0) continue;
            int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            if (r < 90) continue;
            // LED look: full red everywhere, the brightest trim shades get a hot pink-white core
            int gg = Math.min(255, (int) (g * 0.6)), gb = Math.min(255, (int) (b * 0.6));
            out.setRGB(x, y, 0xFF000000 | (255 << 16) | (gg << 8) | gb);
        }
        return out;
    }

    static void write(BufferedImage img, String rel) throws Exception {
        File f = tex.resolve(rel).toFile();
        f.getParentFile().mkdirs();
        ImageIO.write(img, "png", f);
        System.out.println("wrote " + rel);
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        tex = root.resolve("blood/src/main/resources/assets/hha_blood/textures");
        String jarPath = args.length > 1 ? args[1] : System.getProperty("user.home") + "/.gradle/caches/fabric-loom/1.21.11/minecraft-client.jar";
        jar = new ZipFile(jarPath);

        BufferedImage keyImg = vanilla("trims/color_palettes/trim_palette.png");
        int[] key = new int[keyImg.getWidth()];
        for (int i = 0; i < key.length; i++) key[i] = keyImg.getRGB(i, 0) & 0xFFFFFF;
        BufferedImage redstone = vanilla("trims/color_palettes/redstone.png");
        int[] pal = palette(redstone, 1.0);
        int[] palGlow = palette(redstone, 1.18);

        // ---- humanoid layer: helmet (flow), chestplate + arms (silence), boots (flow)
        BufferedImage hum = vanilla("entity/equipment/humanoid/netherite.png");
        BufferedImage flow = recolor(vanilla("trims/entity/humanoid/flow.png"), key, pal);
        BufferedImage silence = recolor(vanilla("trims/entity/humanoid/silence.png"), key, pal);
        overlay(hum, flow, 0, 0, 64, 16);      // head + hat
        overlay(hum, flow, 0, 16, 16, 32);     // legs (boots)
        overlay(hum, silence, 16, 16, 64, 32); // body + arms
        write(hum, "entity/equipment/humanoid/blood.png");

        // ---- glow layers: ONLY the trim pixels, drawn by the client with the additive "eyes" render
        // layer (see EquipmentRendererMixin), so they light up regardless of world light
        BufferedImage humGlow = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        overlay(humGlow, glow(flow), 0, 0, 64, 16);
        overlay(humGlow, glow(flow), 0, 16, 16, 32);
        overlay(humGlow, glow(silence), 16, 16, 64, 32);
        write(humGlow, "entity/equipment/humanoid/blood_glow.png");

        // ---- leggings layer: silence
        BufferedImage leg = vanilla("entity/equipment/humanoid_leggings/netherite.png");
        BufferedImage legTrim = recolor(vanilla("trims/entity/humanoid_leggings/silence.png"), key, pal);
        overlay(leg, legTrim, 0, 0, leg.getWidth(), leg.getHeight());
        write(leg, "entity/equipment/humanoid_leggings/blood.png");
        BufferedImage legGlow = new BufferedImage(leg.getWidth(), leg.getHeight(), BufferedImage.TYPE_INT_ARGB);
        overlay(legGlow, glow(legTrim), 0, 0, leg.getWidth(), leg.getHeight());
        write(legGlow, "entity/equipment/humanoid_leggings/blood_glow.png");

        // ---- item icons: netherite + item trim overlay, 2 frames (normal / glowing) interpolated
        for (String piece : new String[]{"helmet", "chestplate", "leggings", "boots"}) {
            BufferedImage base = vanilla("item/netherite_" + piece + ".png");
            BufferedImage trim = vanilla("trims/items/" + piece + "_trim.png");
            BufferedImage strip = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
            int[][] pals = {pal, palGlow};
            for (int f = 0; f < 2; f++) {
                BufferedImage frame = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                frame.getGraphics().drawImage(base, 0, 0, null);
                overlay(frame, recolor(trim, key, pals[f]), 0, 0, 16, 16);
                strip.getGraphics().drawImage(frame, 0, f * 16, null);
            }
            write(strip, "item/blood_" + piece + ".png");
            Files.writeString(tex.resolve("item/blood_" + piece + ".png.mcmeta"),
                "{\"animation\":{\"frametime\":24,\"interpolate\":true,\"frames\":[0,1]}}\n");
        }
        jar.close();
    }
}
