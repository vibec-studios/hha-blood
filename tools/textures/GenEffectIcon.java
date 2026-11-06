import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Status effect icon "Bleeding" (textures/mob_effect/bleeding.png, 18x18): a fat drop of blood with a
 * dark outline, a wet highlight and two small drops below.
 * Run: java tools/textures/GenEffectIcon.java .
 */
public class GenEffectIcon {
    static final String[] ART = {
        "..................",
        "........oo........",
        ".......orro.......",
        ".......orro.......",
        "......orrrro......",
        "......orHrro......",
        ".....orHHrrro.....",
        ".....orHrrrro.....",
        "....orrrrrrrro....",
        "....orrrrrrrdo....",
        "....orrrrrrddo....",
        "....orrrrrdddo....",
        ".....orrrdddo.....",
        "......odddoo......",
        ".......ooo.....o..",
        "..o...........oro.",
        ".oro...........o..",
        "..o...............",
    };

    static int color(char c) {
        return switch (c) {
            case 'o' -> 0xFF1B0305;   // outline
            case 'r' -> 0xFFC8182A;   // blood
            case 'd' -> 0xFF7A0A12;   // shadow side
            case 'H' -> 0xFFFF9A9A;   // wet highlight
            default -> 0;
        };
    }

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : ".";
        BufferedImage img = new BufferedImage(18, 18, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 18; y++) for (int x = 0; x < 18; x++) img.setRGB(x, y, color(ART[y].charAt(x)));
        File out = new File(root, "blood/src/main/resources/assets/hha_blood/textures/mob_effect/bleeding.png");
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out);
    }
}
