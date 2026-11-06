import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Blood flame particle sprites (Mohg-style crimson flames), 4 flicker frames, 8x8.
 * Run: java tools/textures/GenFlame.java .
 * The particle is rendered full-bright, so the colours are what you see.
 */
public class GenFlame {
    static final String[][] FRAMES = {
        {
            "....o...",
            "...oo...",
            "...omo..",
            "..omo...",
            "..omimo.",
            ".omiimo.",
            ".omicmo.",
            "..ommo..",
        },
        {
            "...o....",
            "...om...",
            "..omo...",
            "..omio..",
            ".omiimo.",
            ".omicmo.",
            ".omiimo.",
            "..ommo..",
        },
        {
            ".....o..",
            "....om..",
            "...omo..",
            "..omio..",
            "..omiimo",
            ".omicimo",
            ".omiicmo",
            "..ommmo.",
        },
        {
            "........",
            "...o....",
            "...om...",
            "..omo...",
            "..omio..",
            ".omicmo.",
            ".omiimo.",
            "..ommo..",
        },
    };

    static int color(char c) {
        return switch (c) {
            case 'o' -> 0xC86A0610; // dark crimson edge, slightly transparent
            case 'm' -> 0xFFB3121E; // crimson
            case 'i' -> 0xFFE8293A; // bright blood red
            case 'c' -> 0xFFFF8A86; // hot pink-white core
            default -> 0x00000000;
        };
    }

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : ".";
        File dir = new File(root, "blood/src/main/resources/assets/hha_blood/textures/particle");
        dir.mkdirs();
        for (int f = 0; f < FRAMES.length; f++) {
            BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 8; y++) {
                String row = FRAMES[f][y];
                for (int x = 0; x < 8; x++) img.setRGB(x, y, color(row.charAt(x)));
            }
            ImageIO.write(img, "png", new File(dir, "blood_flame_" + f + ".png"));
        }
        System.out.println("wrote " + FRAMES.length + " blood_flame frames to " + dir);
    }
}
